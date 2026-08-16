package io.metaloom.graph.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.server.mcp.tool.GraphMcpTools;
import io.metaloom.graph.server.metrics.GraphMetrics;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * The SSE transport, over a real socket.
 *
 * <p>
 * {@link McpProtocolTest} covers the rules; this covers the handshake, which is the part a client actually trips over:
 * the stream has to announce its own endpoint URL before anything else can happen, and the reply has to arrive on
 * <b>both</b> the stream and the POST response.
 * </p>
 */
@Tag("unit")
@Timeout(30)
public class McpServerTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private Vertx vertx;
	private GraphDatabases databases;
	private McpServer mcp;
	private HttpClient client;
	private String base;

	@BeforeEach
	public void start() throws Exception {
		vertx = Vertx.vertx();
		databases = GraphDatabases.open(stores.path().resolve("root"));
		mcp = new McpServer(vertx, new McpJsonRpcHandler(
			GraphMcpTools.all(vertx, databases, GraphDatabases.DEFAULT_NAME, false, 5_000), false, GraphMetrics.NOOP),
			"127.0.0.1");
		int port = mcp.listen(0).toCompletionStage().toCompletableFuture().join();
		base = "http://127.0.0.1:" + port;
		// HTTP/1.1 explicitly. java.net.http defaults to HTTP/2 and attempts an h2c upgrade, and SSE does not survive
		// h2c — the server therefore turns h2c off (see McpServer), but pinning it here as well makes this test
		// describe what an MCP client actually does rather than relying on a negotiation falling back.
		client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	}

	@AfterEach
	public void stop() throws Exception {
		mcp.closeQuietly();
		databases.close();
		vertx.close().toCompletionStage().toCompletableFuture().join();
	}

	@Test
	public void testTheStreamAnnouncesItsEndpointAndCarriesTheReply() throws Exception {
		LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
		CompletableFuture<HttpResponse<Stream<String>>> stream = client.sendAsync(
			HttpRequest.newBuilder(URI.create(base + McpConstants.SSE_PATH)).GET().build(),
			HttpResponse.BodyHandlers.ofLines());
		// The body is consumed on another thread: the stream never ends on its own, so reading it inline would block
		// this test for as long as the server stayed up.
		Thread reader = Thread.ofVirtual().start(() -> {
			try {
				stream.join().body().forEach(lines::add);
			} catch (RuntimeException ignored) {
				// The socket is closed in teardown, which is a normal end to this thread.
			}
		});

		String endpoint = awaitLine(lines, "data: ");
		assertTrue(endpoint.contains(McpConstants.MESSAGE_PATH + "?sessionId="),
			"the first event has to name the URL to post to, was: " + endpoint);
		assertEquals(1, mcp.sessionCount(), "and the session has to be registered before the client can use it");

		String messageUrl = base + endpoint.substring("data: ".length()).trim();
		HttpResponse<String> posted = client.send(HttpRequest.newBuilder(URI.create(messageUrl))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(new JsonObject()
				.put("jsonrpc", "2.0").put("id", 7).put("method", McpConstants.METHOD_TOOLS_LIST)
				.encode()))
			.build(), HttpResponse.BodyHandlers.ofString());

		// Both, and that is the point: an SSE client reads the stream, and a client treating the POST as a
		// request/response pair would hang forever if only the stream carried it.
		assertEquals(200, posted.statusCode());
		assertTrue(posted.body().contains("graph_cypher_guide"), "the POST response carries the reply");
		String fromStream = awaitLine(lines, "data: {");
		assertTrue(fromStream.contains("graph_cypher_guide"), "and so does the stream, was: " + fromStream);

		reader.interrupt();
	}

	@Test
	public void testAnHttp2ClientStillGetsTheStream() throws Exception {
		// The regression guard for the defect this test class was written to find. Vert.x enables h2c by default and
		// SSE does not survive it: measured against this server, the same request returned the endpoint event over
		// HTTP/1.1 and *zero bytes* over h2c. java.net.http defaults to HTTP/2, so an MCP client written in Java
		// would connect, be told nothing, and wait forever — with no error anywhere to explain it.
		//
		// McpServer therefore declines h2c, which makes this client fall back to HTTP/1.1 and work. If somebody
		// re-enables it, this fails here rather than in somebody's editor.
		HttpClient http2 = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)
			.connectTimeout(Duration.ofSeconds(5))
			.build();

		LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
		CompletableFuture<HttpResponse<Stream<String>>> stream = http2.sendAsync(
			HttpRequest.newBuilder(URI.create(base + McpConstants.SSE_PATH)).GET().build(),
			HttpResponse.BodyHandlers.ofLines());
		Thread reader = Thread.ofVirtual().start(() -> {
			try {
				stream.join().body().forEach(lines::add);
			} catch (RuntimeException ignored) {
				// Closed in teardown.
			}
		});

		String endpoint = awaitLine(lines, "data: ");
		assertTrue(endpoint.contains(McpConstants.MESSAGE_PATH), "was: " + endpoint);
		assertEquals(HttpClient.Version.HTTP_1_1, stream.join().version(),
			"the server has to decline h2c, or the event above never arrives");
		reader.interrupt();
	}

	@Test
	public void testPostingToAnUnknownSessionIsRefused() throws Exception {
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(
			URI.create(base + McpConstants.MESSAGE_PATH + "?sessionId=made-up"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(404, response.statusCode());
		assertTrue(response.body().contains(McpConstants.SSE_PATH), "and say how to get one");
	}

	@Test
	public void testTheSessionIsForgottenWhenTheClientDisappears() throws Exception {
		// A client that drops its TCP connection sends neither an end nor a close frame. A session map that only
		// shrank on a clean goodbye would grow for the lifetime of the process, which is a leak no assertion in the
		// rest of the suite could see.
		CompletableFuture<HttpResponse<Stream<String>>> abandoned = client.sendAsync(
			HttpRequest.newBuilder(URI.create(base + McpConstants.SSE_PATH)).GET().build(),
			HttpResponse.BodyHandlers.ofLines());

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (mcp.sessionCount() == 0 && System.nanoTime() - deadline < 0) {
			Thread.sleep(20);
		}
		assertEquals(1, mcp.sessionCount(), "the stream should have registered a session");

		abandoned.cancel(true);

		deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (mcp.sessionCount() > 0 && System.nanoTime() - deadline < 0) {
			Thread.sleep(20);
		}
		assertEquals(0, mcp.sessionCount(), "the session has to be dropped when the socket goes");
	}

	private static String awaitLine(LinkedBlockingQueue<String> lines, String prefix) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() - deadline < 0) {
			String line = lines.poll(200, TimeUnit.MILLISECONDS);
			if (line != null && line.startsWith(prefix)) {
				return line;
			}
		}
		throw new AssertionError("No line starting with '" + prefix + "' arrived, saw: " + List.copyOf(lines));
	}
}
