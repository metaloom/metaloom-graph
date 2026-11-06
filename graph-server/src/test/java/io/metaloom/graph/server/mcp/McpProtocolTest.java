package io.metaloom.graph.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * The protocol and, above all, the write gate.
 *
 * <p>
 * Driven through {@link McpJsonRpcHandler} rather than over a socket: the transport is thin and the rules that matter
 * are all in the handler, so testing there covers both SSE and WebSocket at once.
 * </p>
 */
@Tag("unit")
public class McpProtocolTest {

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
			store.createNode("Airport", Properties.of().set("code", "BON"));
			store.createNode("Airport", Properties.of().set("code", "CGN"));
			txn.commit();
		}
	}

	@AfterEach
	public void stop() throws Exception {
		databases.close();
		vertx.close().toCompletionStage().toCompletableFuture().join();
	}

	private McpJsonRpcHandler handler(boolean allowWrite) {
		List<GraphMcpTool> tools = GraphMcpTools.all(vertx, databases, GraphDatabases.DEFAULT_NAME, allowWrite, 5_000);
		return new McpJsonRpcHandler(tools, allowWrite, GraphMetrics.NOOP);
	}

	private JsonObject call(McpJsonRpcHandler handler, String method, JsonObject params) {
		JsonRpcResponse response = handler.handle(new JsonRpcRequest(1, method, params))
			.toCompletionStage().toCompletableFuture().join();
		assertNotNull(response, method + " should have produced a response");
		return response.toJson();
	}

	private JsonObject callTool(McpJsonRpcHandler handler, String name, JsonObject arguments) {
		return call(handler, McpConstants.METHOD_TOOLS_CALL,
			new JsonObject().put("name", name).put("arguments", arguments));
	}

	/** The text of the single content block a tool returns. */
	private static String textOf(JsonObject response) {
		return response.getJsonObject("result").getJsonArray("content").getJsonObject(0).getString("text");
	}

	@Test
	public void testInitializeAnnouncesTheVersionAndTools() {
		JsonObject result = call(handler(false), McpConstants.METHOD_INITIALIZE, new JsonObject())
			.getJsonObject("result");
		assertEquals(McpConstants.MCP_PROTOCOL_VERSION, result.getString("protocolVersion"));
		assertNotNull(result.getJsonObject("capabilities").getJsonObject("tools"),
			"a server with tools has to say so, or a client will never call tools/list");
		assertEquals("metaloom-graph", result.getJsonObject("serverInfo").getString("name"));
		assertTrue(result.getString("instructions").contains("READ-ONLY"),
			"the read-only posture belongs in the instructions, so the model knows before its first refused write");
	}

	@Test
	public void testANotificationIsNotAnswered() {
		// Answering a notification is a protocol violation rather than merely noise.
		assertNull(handler(false).handle(new JsonRpcRequest(null, McpConstants.METHOD_INITIALIZED, new JsonObject()))
			.toCompletionStage().toCompletableFuture().join());
	}

	@Test
	public void testAWriteToolIsHiddenOnAReadOnlyServer() {
		Set<String> readOnly = toolNames(handler(false));
		Set<String> writable = toolNames(handler(true));

		assertFalse(readOnly.contains("graph_create_index"),
			"an agent should not be shown a capability it cannot use — it will spend turns trying");
		assertFalse(readOnly.contains("graph_drop_index"));
		assertTrue(writable.contains("graph_create_index"));
		assertTrue(writable.contains("graph_drop_index"));

		// The read tools are there either way, and the guide most of all.
		for (String expected : List.of("graph_query", "graph_schema", "graph_stats", "graph_list_databases",
			"graph_list_indexes", "graph_cypher_guide")) {
			assertTrue(readOnly.contains(expected), expected + " should be offered on a read-only server");
		}
	}

	@Test
	public void testAHiddenToolIsAlsoRefusedWhenCalledByName() {
		// The second enforcement point. Listing alone trusts the client's memory: a tools/call naming a tool
		// remembered from an earlier session, against a different server, must not work.
		JsonObject response = callTool(handler(false), "graph_create_index",
			new JsonObject().put("label", "Airport").put("key", "code"));
		assertTrue(response.getJsonObject("result").getBoolean("isError"),
			"a refusal has to be marked isError, or the model reads it as a success");
		assertTrue(textOf(response).contains("--allow-write"), "and name the flag that would enable it");
		assertTrue(textOf(response).contains("Do not retry"), "and stop the model retrying it forever");
	}

	@Test
	public void testAWriteQueryIsRefusedOnAReadOnlyServer() {
		JsonObject response = callTool(handler(false), "graph_query",
			new JsonObject().put("query", "CREATE (n:Thing) RETURN n"));
		assertTrue(response.getJsonObject("result").getBoolean("isError"));
		assertTrue(textOf(response).contains("--allow-write"));
	}

	@Test
	public void testAReadQueryReturnsRows() {
		JsonObject response = callTool(handler(false), "graph_query",
			new JsonObject().put("query", "MATCH (a:Airport) RETURN a.code AS code"));
		String text = textOf(response);
		assertTrue(text.contains("BON") && text.contains("CGN"), "both airports should come back, was: " + text);
		assertTrue(text.contains("\"truncated\" : false"), "and the result has to say it is complete");
	}

	@Test
	public void testTheRowCapStopsTheScanAndSaysSo() {
		// An LLM context is not a result set. The cap matters less than the fact that the model is told about it: a
		// model that believes it saw everything will draw a conclusion from half an answer.
		JsonObject response = callTool(handler(false), "graph_query",
			new JsonObject().put("query", "MATCH (a:Airport) RETURN a.code AS code").put("limit", 1));
		String text = textOf(response);
		assertTrue(text.contains("\"truncated\" : true"), "was: " + text);
		assertTrue(text.contains("\"rowCount\" : 1"));
		assertTrue(text.contains("assuming this is the whole answer"), "and say what to do about it");
	}

	@Test
	public void testASyntaxErrorComesBackAsAToolErrorRatherThanAProtocolError() {
		// The model wrote it and the model can fix it; a JSON-RPC error would not reach it as tool output.
		JsonObject response = callTool(handler(false), "graph_query", new JsonObject().put("query", "MATCH ("));
		assertNull(response.getJsonObject("error"), "a bad query is not a bad call");
		assertTrue(response.getJsonObject("result").getBoolean("isError"));
		assertTrue(textOf(response).contains("Syntax error"));
	}

	@Test
	public void testTheGuideNamesWhatIsNotSupported() {
		String guide = textOf(callTool(handler(false), "graph_cypher_guide", new JsonObject()));
		assertTrue(guide.contains("FOREACH"),
			"a construct a model that has seen Neo4j will reach for, and that this build refuses");
		assertTrue(guide.contains("$name"), "and the parameter syntax, so it stops interpolating values");
	}

	@Test
	public void testAnUnknownToolIsAMethodNotFound() {
		JsonObject response = callTool(handler(false), "graph_drop_database", new JsonObject());
		assertEquals(McpConstants.METHOD_NOT_FOUND, response.getJsonObject("error").getInteger("code"));
	}

	@Test
	public void testAnUnknownMethodIsRefusedRatherThanIgnored() {
		JsonObject response = call(handler(false), "tools/invent", new JsonObject());
		assertEquals(McpConstants.METHOD_NOT_FOUND, response.getJsonObject("error").getInteger("code"));
	}

	@Test
	public void testWritingWorksWhenItIsAllowed() {
		JsonObject response = callTool(handler(true), "graph_create_index",
			new JsonObject().put("label", "Airport").put("key", "code"));
		assertNull(response.getJsonObject("result").getBoolean("isError"), "was: " + textOf(response));

		String indexes = textOf(callTool(handler(true), "graph_list_indexes", new JsonObject()));
		assertTrue(indexes.contains("Airport"), "the index should now be listed, was: " + indexes);
	}

	private static Set<String> toolNames(McpJsonRpcHandler handler) {
		JsonArray tools = handler.handle(new JsonRpcRequest(1, McpConstants.METHOD_TOOLS_LIST, new JsonObject()))
			.toCompletionStage().toCompletableFuture().join()
			.result().getJsonArray("tools");
		return tools.stream().map(JsonObject.class::cast).map(tool -> tool.getString("name"))
			.collect(java.util.stream.Collectors.toSet());
	}

	@Test
	public void testTheWriteFlagIsNeverSentToTheClient() {
		// It is a server-side dispatch detail. Sending it would invite the model to argue about it.
		JsonArray tools = handler(true).handle(new JsonRpcRequest(1, McpConstants.METHOD_TOOLS_LIST, new JsonObject()))
			.toCompletionStage().toCompletableFuture().join().result().getJsonArray("tools");
		for (Object entry : tools) {
			assertFalse(((JsonObject) entry).containsKey("write"),
				"the descriptor's write flag must not be serialised");
		}
	}

	/**
	 * An unknown tool name is recorded as {@code tool="unknown"}, never as itself.
	 *
	 * <p>
	 * The name comes from the client. Used as a label it is one new time series per string a caller cares to send,
	 * which is unbounded memory in the registry and on every scrape.
	 * </p>
	 */
	@Test
	public void testAnUnknownToolNameIsNotUsedAsAMetricLabel() {
		io.micrometer.prometheusmetrics.PrometheusMeterRegistry registry =
			new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
				io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
		McpJsonRpcHandler handler = new McpJsonRpcHandler(
			GraphMcpTools.all(vertx, databases, GraphDatabases.DEFAULT_NAME, false, 5_000), false,
			new io.metaloom.graph.server.metrics.MicrometerGraphMetrics(registry));

		for (int i = 0; i < 3; i++) {
			JsonObject response = callTool(handler, "made_up_" + i, new JsonObject());
			assertNotNull(response.getJsonObject("error"), response.encode());
		}

		String scrape = registry.scrape();
		assertFalse(scrape.contains("made_up_"), "a client-chosen name became a label: " + scrape);
		assertTrue(scrape.contains("tool=\"unknown\""), scrape);
	}
}
