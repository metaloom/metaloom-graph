package io.metaloom.graph.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.server.auth.AuthPolicy;
import io.metaloom.graph.server.metrics.GraphMetrics;
import io.metaloom.graph.server.mcp.tool.GraphMcpTools;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The one port an LLM drives, and what it now asks for first.
 *
 * <p>
 * {@code spec/MCP.md} section 2 explains why this port is the one that is off by default: it is a remote code path a
 * model decides what to run on. The two enforcement points it already had for writes — omit from {@code tools/list},
 * re-check on {@code tools/call} — carry over to privileges unchanged, and both halves are asserted here because
 * either alone is a hole.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class McpAuthTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private Vertx vertx;
	private GraphDatabases databases;
	private AuthStore identities;
	private McpServer server;
	private HttpClient client;
	private String base;

	/**
	 * The server's own policy, and the same object the test issues tokens from.
	 *
	 * <p>
	 * It has to be the same one: sessions live in the policy's table, so a second {@code AuthPolicy} over the same
	 * identity store would hand out tokens the server has never heard of.
	 * </p>
	 */
	private AuthPolicy policy;

	@BeforeEach
	public void start() throws Exception {
		vertx = Vertx.vertx();
		databases = GraphDatabases.open(stores.path().resolve("root"));
		databases.database(GraphDatabases.DEFAULT_NAME);

		identities = AuthStore.open(stores.path().resolve("root"));
		identities.bootstrap("admin-pw".toCharArray());
		identities.createRole("reader");
		identities.grantToRole("reader", Grant.on(Privilege.MATCH));
		identities.createUser("reader", "reader-pw".toCharArray(), List.of("reader"));

		policy = AuthPolicy.enabled(identities, 600_000);
		server = new McpServer(vertx,
			new McpJsonRpcHandler(GraphMcpTools.all(vertx, databases, GraphDatabases.DEFAULT_NAME, true, 10_000),
				true, GraphMetrics.NOOP, GraphDatabases.DEFAULT_NAME),
			"127.0.0.1");
		server.authPolicy(() -> policy);
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		base = "http://127.0.0.1:" + port;
	}

	@AfterEach
	public void stop() throws Exception {
		if (server != null) {
			server.close().toCompletionStage().toCompletableFuture().join();
		}
		if (identities != null) {
			identities.close();
		}
		if (databases != null) {
			databases.close();
		}
		if (vertx != null) {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}
	}

	/** A token for a user. Issued from the server's own policy: this port has no login route of its own. */
	private String tokenFor(String user, String password) {
		policy.authenticate(user, password.toCharArray()).orElseThrow();
		return policy.sessions().issue(user);
	}

	private HttpResponse<String> sse(String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + McpConstants.SSE_PATH))
			.timeout(Duration.ofSeconds(5)).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		try {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		} catch (java.io.IOException timeout) {
			// A successful SSE stream never ends, so the read times out. That is the success case here; the refusal
			// case answers and closes, which is what the assertions below distinguish.
			throw timeout;
		}
	}

	// ---------------------------------------------------------------- the gate

	@Test
	public void testTheSseTransportRefusesARequestWithNoToken() throws Exception {
		HttpResponse<String> response = sse(null);
		assertEquals(401, response.statusCode(), response.body());
		assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").contains("Bearer"));
	}

	@Test
	public void testTheMessageTransportRefusesARequestWithNoToken() throws Exception {
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(
			URI.create(base + McpConstants.MESSAGE_PATH + "?sessionId=whatever"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("method", "tools/list").encode()))
			.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());

		assertEquals(401, response.statusCode(), response.body());
	}

	/**
	 * The websocket is closed rather than refused, because it cannot be refused.
	 *
	 * <p>
	 * Vert.x 5 removed {@code accept()}/{@code reject()} from {@code ServerWebSocket}, so by the time the handler runs
	 * the handshake has completed. 1008 — policy violation — is the only way left to say no, and it is the same
	 * mechanism the wrong-path branch already used.
	 * </p>
	 */
	@Test
	public void testTheWebSocketIsClosedWithoutASession() throws Exception {
		java.util.concurrent.CompletableFuture<String> outcome = new java.util.concurrent.CompletableFuture<>();
		vertx.createWebSocketClient()
			.connect(server.port(), "127.0.0.1", McpConstants.WEBSOCKET_PATH)
			.onSuccess(socket -> {
				socket.closeHandler(ignored -> outcome.complete("closed:" + socket.closeStatusCode()));
				socket.exceptionHandler(t -> outcome.complete("failed:" + t.getClass().getSimpleName()));
			})
			// The close lands so early that the client may see it as the handshake failing rather than as a close
			// frame. Either is the refusal; what must never happen is a usable socket.
			.onFailure(t -> outcome.complete("failed:" + t.getClass().getSimpleName()));

		String result = outcome.get(10, TimeUnit.SECONDS);
		assertTrue(result.startsWith("failed:") || result.equals("closed:1008"),
			"an unauthenticated MCP websocket must not end up usable, got " + result);
	}

	/** And with a token it does connect, so the refusal above is about the credential and not about the path. */
	@Test
	public void testTheWebSocketConnectsWithAToken() throws Exception {
		java.util.concurrent.CompletableFuture<String> opened = new java.util.concurrent.CompletableFuture<>();
		vertx.createWebSocketClient()
			.connect(new io.vertx.core.http.WebSocketConnectOptions()
				.setHost("127.0.0.1").setPort(server.port()).setURI(McpConstants.WEBSOCKET_PATH)
				.addHeader("Authorization", "Bearer " + tokenFor("admin", "admin-pw")))
			.onSuccess(socket -> {
				socket.textMessageHandler(opened::complete);
				socket.writeTextMessage(new JsonObject()
					.put("jsonrpc", "2.0").put("id", 9).put("method", "tools/list").encode());
			})
			.onFailure(t -> opened.completeExceptionally(t));

		assertTrue(opened.get(10, TimeUnit.SECONDS).contains("graph_query"), "an authenticated websocket works");
	}

	// ---------------------------------------------------------------- both enforcement points

	/**
	 * {@code tools/list} omits what this principal cannot use.
	 *
	 * <p>
	 * The first of the two points, and the reason it exists is about the model rather than about security: an agent
	 * shown a capability it cannot use will spend turns trying to use it.
	 * </p>
	 */
	@Test
	public void testToolsListOmitsWhatThePrincipalCannotUse() throws Exception {
		JsonArray forAdmin = toolNames(tokenFor("admin", "admin-pw"));
		JsonArray forReader = toolNames(tokenFor("reader", "reader-pw"));

		assertTrue(forAdmin.contains("graph_create_index"), forAdmin.encode());
		assertTrue(forAdmin.contains("graph_schema"), forAdmin.encode());

		assertFalse(forReader.contains("graph_create_index"),
			"a reader was offered an index tool it cannot use: " + forReader.encode());
		assertFalse(forReader.contains("graph_schema"),
			"the reader holds MATCH and not INTROSPECT: " + forReader.encode());
		assertTrue(forReader.contains("graph_query"), forReader.encode());
	}

	/**
	 * {@code tools/call} re-checks, so a name remembered from another session does not work.
	 *
	 * <p>
	 * The second point. Listing without dispatch-checking trusts the client's memory; dispatch-checking without
	 * listing wastes the model's time. {@code spec/MCP.md} section 5 made that argument about writes, and it is the
	 * same argument.
	 * </p>
	 */
	@Test
	public void testToolsCallRechecksAToolThatWasNotListed() throws Exception {
		String reader = tokenFor("reader", "reader-pw");
		JsonObject result = call(reader, "graph_create_index",
			new JsonObject().put("label", "Person").put("key", "email"));

		// A JSON-RPC success carrying a tool error, not a transport failure: the model has to be able to read it.
		assertTrue(result.containsKey("result"), result.encode());
		JsonObject content = result.getJsonObject("result");
		assertTrue(content.getBoolean("isError", false), content.encode());
		assertTrue(content.encode().contains("CREATE_INDEX"), content.encode());
	}

	/** A query the principal may not run is refused per query, because what it needs depends on the query. */
	@Test
	public void testAQueryToolCallIsCheckedAgainstTheQuery() throws Exception {
		String reader = tokenFor("reader", "reader-pw");

		JsonObject read = call(reader, "graph_query", new JsonObject().put("query", "MATCH (n) RETURN n"));
		assertFalse(read.getJsonObject("result").getBoolean("isError", false), read.encode());

		JsonObject write = call(reader, "graph_query", new JsonObject().put("query", "CREATE (n:Person)"));
		assertTrue(write.getJsonObject("result").getBoolean("isError", false), write.encode());
		assertTrue(write.encode().contains("CREATE"), write.encode());
	}

	/** LOAD CSV stays refused on this port whatever anybody was granted. */
	@Test
	public void testLoadCsvIsStillRefusedUnconditionally() throws Exception {
		JsonObject result = call(tokenFor("admin", "admin-pw"), "graph_query",
			new JsonObject().put("query", "LOAD CSV FROM 'file:///x.csv' AS row RETURN row"));
		assertTrue(result.getJsonObject("result").getBoolean("isError", false), result.encode());
	}

	// ---------------------------------------------------------------- plumbing

	/**
	 * Opens a session and posts one request to it.
	 *
	 * <p>
	 * The SSE stream never ends, so it is read on a separate thread only far enough to learn the session id, and the
	 * answer is taken from the POST's own response — which the server sends on both, precisely so a client using it
	 * as a request/response pair does not hang.
	 * </p>
	 */
	private JsonObject rpc(String token, JsonObject request) throws Exception {
		java.util.concurrent.CompletableFuture<String> endpoint = new java.util.concurrent.CompletableFuture<>();
		Thread reader = new Thread(() -> {
			try {
				HttpResponse<java.io.InputStream> response = client.send(
					HttpRequest.newBuilder(URI.create(base + McpConstants.SSE_PATH))
						.header("Authorization", "Bearer " + token).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
				java.io.BufferedReader lines = new java.io.BufferedReader(
					new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8));
				String line;
				while ((line = lines.readLine()) != null) {
					if (line.startsWith("data: ")) {
						endpoint.complete(line.substring("data: ".length()).trim());
						return;
					}
				}
			} catch (Exception e) {
				endpoint.completeExceptionally(e);
			}
		});
		reader.setDaemon(true);
		reader.start();

		String path = endpoint.get(10, TimeUnit.SECONDS);
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + path))
			.header("content-type", "application/json")
			.header("Authorization", "Bearer " + token)
			.POST(HttpRequest.BodyPublishers.ofString(request.encode()))
			.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		return new JsonObject(response.body());
	}

	private JsonArray toolNames(String token) throws Exception {
		JsonObject response = rpc(token, new JsonObject()
			.put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list"));
		JsonArray names = new JsonArray();
		for (Object tool : response.getJsonObject("result").getJsonArray("tools")) {
			names.add(((JsonObject) tool).getString("name"));
		}
		return names;
	}

	private JsonObject call(String token, String tool, JsonObject arguments) throws Exception {
		return rpc(token, new JsonObject()
			.put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
			.put("params", new JsonObject().put("name", tool).put("arguments", arguments)));
	}
}
