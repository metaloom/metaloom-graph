package io.metaloom.graph.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.server.mcp.model.JsonRpcRequest;
import io.metaloom.graph.server.mcp.model.JsonRpcResponse;
import io.metaloom.graph.server.mcp.tool.GraphMcpTool;
import io.metaloom.graph.server.mcp.tool.GraphMcpTools;
import io.metaloom.graph.server.metrics.GraphMetrics;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tool calls and messages with arguments of the wrong shape.
 *
 * <p>
 * The caller on this port is a language model, and a model sends a string where an integer belongs, an array where an
 * object belongs, a number where a name belongs. Every one of those must come back as an answer the model can read —
 * a JSON-RPC error or a tool result with {@code isError} — and never as an exception escaping the handler. Over SSE an
 * escaped exception is a 500 with nothing on the stream; over a WebSocket it is <em>no answer at all</em>, and a
 * client waiting on a request id waits for ever.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class McpBadArgumentsTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private Vertx vertx;
	private GraphDatabases databases;

	@BeforeEach
	public void start() throws Exception {
		vertx = Vertx.vertx();
		databases = GraphDatabases.open(stores.path().resolve("root"));
		GraphStore store = databases.database(GraphDatabases.DEFAULT_NAME);
		try (Transaction txn = store.begin()) {
			long bon = store.createNode("Airport", Properties.of().set("code", "BON"));
			long cgn = store.createNode("Airport", Properties.of().set("code", "CGN"));
			store.createRelationship(bon, "ROUTE", cgn, Properties.of().set("km", 30L));
			txn.commit();
		}
	}

	@AfterEach
	public void stop() throws Exception {
		databases.close();
		vertx.close().toCompletionStage().toCompletableFuture().join();
	}

	private McpJsonRpcHandler handler() {
		List<GraphMcpTool> tools = GraphMcpTools.all(vertx, databases, GraphDatabases.DEFAULT_NAME, false, 5_000);
		return new McpJsonRpcHandler(tools, false, GraphMetrics.NOOP);
	}

	/** One message in, one response out, whatever the message. A thrown exception fails the test. */
	private JsonObject send(JsonObject message) {
		JsonRpcResponse response = handler().handle(JsonRpcRequest.from(message))
			.toCompletionStage().toCompletableFuture().join();
		assertNotNull(response, "every request with an id gets a response: " + message.encode());
		return response.toJson();
	}

	private static JsonObject toolCall(Object name, Object arguments) {
		return new JsonObject().put("jsonrpc", "2.0").put("id", 7).put("method", McpConstants.METHOD_TOOLS_CALL)
			.put("params", new JsonObject().put("name", name).put("arguments", arguments));
	}

	private static boolean isToolError(JsonObject response) {
		return response.containsKey("result") && response.getJsonObject("result").getBoolean("isError", false);
	}

	private static boolean isRpcError(JsonObject response, int code) {
		return response.containsKey("error") && response.getJsonObject("error").getInteger("code") == code;
	}

	@Test
	public void testAToolNameThatIsNotAStringIsAnInvalidParamsError() {
		JsonObject response = send(toolCall(42, new JsonObject()));
		assertTrue(isRpcError(response, McpConstants.INVALID_PARAMS), response.encode());
		assertEquals(7, response.getInteger("id"));
	}

	@Test
	public void testArgumentsThatAreNotAnObjectAreAnInvalidParamsError() {
		JsonObject response = send(toolCall("graph_query", "MATCH (n) RETURN n"));
		assertTrue(isRpcError(response, McpConstants.INVALID_PARAMS), response.encode());
	}

	@Test
	public void testParamsThatAreNotAnObjectAreAnInvalidRequest() {
		JsonObject message = new JsonObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
			.put("params", new JsonArray().add("graph_query"));
		JsonRpcResponse response = handler().handle(JsonRpcRequest.from(message))
			.toCompletionStage().toCompletableFuture().join();
		assertNotNull(response);
		assertTrue(isRpcError(response.toJson(), McpConstants.INVALID_PARAMS)
			|| isRpcError(response.toJson(), McpConstants.INVALID_REQUEST), response.toJson().encode());
	}

	@Test
	public void testAMethodThatIsNotAStringIsAnInvalidRequest() {
		JsonObject message = new JsonObject().put("jsonrpc", "2.0").put("id", 4).put("method", 12);
		JsonRpcResponse response = handler().handle(JsonRpcRequest.from(message))
			.toCompletionStage().toCompletableFuture().join();
		assertNotNull(response);
		assertTrue(isRpcError(response.toJson(), McpConstants.INVALID_REQUEST), response.toJson().encode());
	}

	/** A model's argument mistakes are tool errors it can read and correct, not failures of the call. */
	@Test
	public void testWronglyTypedToolArgumentsAreToolErrors() {
		List<JsonObject> wrong = List.of(
			new JsonObject().put("query", 5),
			new JsonObject().put("query", "MATCH (a:Airport) RETURN a.code").put("limit", "10"),
			new JsonObject().put("query", "MATCH (a:Airport) RETURN a.code").put("limit", new JsonArray()),
			new JsonObject().put("query", "RETURN $p").put("parameters", "p=1"),
			new JsonObject().put("query", "RETURN $p").put("parameters", new JsonArray().add(1)),
			new JsonObject().put("query", "MATCH (a:Airport) RETURN a.code").put("database", 9));
		for (JsonObject arguments : wrong) {
			JsonObject response = send(toolCall("graph_query", arguments));
			assertTrue(isToolError(response) || response.containsKey("error"),
				arguments.encode() + " -> " + response.encode());
		}
		for (String tool : List.of("graph_schema", "graph_stats", "graph_list_indexes")) {
			JsonObject response = send(toolCall(tool, new JsonObject().put("database", new JsonArray().add("x"))));
			assertTrue(isToolError(response) || response.containsKey("error"), tool + " -> " + response.encode());
		}
	}

	@Test
	public void testALimitOutsideTheRangeIsClampedNotRefused() {
		JsonObject response = send(toolCall("graph_query",
			new JsonObject().put("query", "MATCH (a:Airport) RETURN a.code").put("limit", -5)));
		assertFalse(isToolError(response), response.encode());
		JsonObject huge = send(toolCall("graph_query",
			new JsonObject().put("query", "MATCH (a:Airport) RETURN a.code").put("limit", Long.MAX_VALUE)));
		assertFalse(isToolError(huge), huge.encode());
		// Clamped to the maximum, not truncated to an int first: Long.MAX_VALUE as an int is -1, which clamped to one row.
		JsonObject rows = new JsonObject(huge.getJsonObject("result").getJsonArray("content").getJsonObject(0)
			.getString("text"));
		assertEquals(2, rows.getInteger("rowCount"), rows.encode());
		assertFalse(rows.getBoolean("truncated"), rows.encode());
	}

	/**
	 * A query returning graph elements answers with them, as the HTTP route does.
	 *
	 * <p>
	 * The tool put the engine's node and relationship objects straight into a {@code JsonObject}, which the JSON
	 * encoder has no mapping for. Every {@code RETURN n} — the first query anybody writes — came back as
	 * "graph_query failed".
	 * </p>
	 */
	@Test
	public void testAQueryReturningElementsAnswersWithThem() {
		for (String query : List.of("MATCH (a:Airport) RETURN a", "MATCH ()-[r:ROUTE]->() RETURN r",
			"MATCH p = (:Airport)-[:ROUTE]->(:Airport) RETURN p", "MATCH (a:Airport) RETURN collect(a) AS airports",
			"MATCH (a:Airport) RETURN {node: a} AS wrapped")) {
			JsonObject response = send(toolCall("graph_query", new JsonObject().put("query", query)));
			assertFalse(isToolError(response), query + " -> " + response.encode());
			String text = response.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text");
			JsonObject result = new JsonObject(text);
			assertTrue(result.getInteger("rowCount") > 0, query + " -> " + text);
			assertTrue(text.contains("BON") || text.contains("ROUTE"), query + " -> " + text);
		}
	}

	/**
	 * The same malformed messages over a real WebSocket each get an answer, rather than silence.
	 */
	@Test
	public void testAMalformedMessageOverTheWebSocketIsAnsweredNotDropped() throws Exception {
		McpServer server = new McpServer(vertx, handler(), "127.0.0.1");
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();
		try {
			List<String> messages = List.of(
				toolCall(42, new JsonObject()).encode(),
				toolCall("graph_query", "not an object").encode(),
				toolCall("graph_query", new JsonObject().put("query", "RETURN 1").put("limit", "x")).encode(),
				new JsonObject().put("jsonrpc", "2.0").put("id", 9).put("method", 3).encode(),
				"[1, 2, 3]");
			java.util.concurrent.BlockingQueue<String> answers = new java.util.concurrent.LinkedBlockingQueue<>();
			CompletableFuture<io.vertx.core.http.WebSocket> opened = new CompletableFuture<>();
			io.vertx.core.http.WebSocketClient client = vertx.createWebSocketClient();
			client.connect(port, "127.0.0.1", McpConstants.WEBSOCKET_PATH)
				.onSuccess(socket -> {
					socket.textMessageHandler(answers::add);
					opened.complete(socket);
				})
				.onFailure(opened::completeExceptionally);
			io.vertx.core.http.WebSocket socket = opened.get(10, TimeUnit.SECONDS);
			for (String message : messages) {
				socket.writeTextMessage(message);
				String received = answers.poll(10, TimeUnit.SECONDS);
				assertNotNull(received, "no answer to " + message);
				JsonObject response = new JsonObject(received);
				assertTrue(response.containsKey("error") || isToolError(response), message + " -> " + received);
			}
			// And the socket is still serving after all of them.
			socket.writeTextMessage(new JsonObject().put("jsonrpc", "2.0").put("id", 99).put("method", "ping").encode());
			assertTrue(answers.poll(10, TimeUnit.SECONDS).contains("\"id\":99"));
			// Used after the exchanges, so it stays reachable through them: a Vert.x 5 client nobody references is closed by
			// a cleaner when collected, which ended this socket mid-test before it was held.
			client.close();
		} finally {
			server.close().toCompletionStage().toCompletableFuture().join();
		}
	}
}
