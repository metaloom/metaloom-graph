package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The HTTP routes against bodies, names and parameters of the wrong shape.
 *
 * <p>
 * The rule every case here checks is the one {@code respondToFailure} states: a request that is wrong is a 4xx naming
 * what is wrong, and only the server breaking is a 500. A 500 for a malformed body tells the caller "retry later, it is
 * our fault" about a request that will never succeed, and it lands in the error log as if something had broken.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class HttpRouteEdgeCaseTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphServer server;
	private HttpClient client;
	private String base;

	@BeforeEach
	public void start() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		GraphStore store = databases.database(GraphDatabases.DEFAULT_NAME);
		try (Transaction txn = store.begin()) {
			store.createNode("Thing", Properties.of().set("name", "Alice"));
			txn.commit();
		}
		databases.create("other");
		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, true, true);
		server.allowBackup(true);
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		base = "http://127.0.0.1:" + port;
	}

	@AfterEach
	public void stop() throws Exception {
		if (server != null) {
			server.close();
		}
		if (databases != null) {
			databases.close();
		}
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return client.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> postRaw(String path, String body) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(base + path)).header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)));
	}

	private static void assertClientError(HttpResponse<String> response, int status, String what) {
		assertEquals(status, response.statusCode(), what + " -> " + response.body());
		JsonObject body = new JsonObject(response.body());
		assertTrue(body.containsKey("error") && body.containsKey("message"), what + " -> " + response.body());
	}

	// ---------------------------------------------------------------- /api/query bodies

	@Test
	public void testABodyThatIsNotJsonIsA400() throws Exception {
		for (String body : List.of("not json", "{\"query\": ", "{'query': 'RETURN 1'}", "\u0000")) {
			assertClientError(postRaw("/api/query", body), 400, body);
		}
	}

	@Test
	public void testJsonOfTheWrongShapeIsA400() throws Exception {
		for (String body : List.of("[1, 2]", "\"RETURN 1\"", "42", "null", "{\"query\": 5}",
			"{\"query\": [\"RETURN 1\"]}", "{\"query\": \"RETURN $p\", \"parameters\": [1]}",
			"{\"query\": \"RETURN $p\", \"parameters\": \"p=1\"}")) {
			HttpResponse<String> response = postRaw("/api/query", body);
			assertClientError(response, 400, body);
		}
	}

	@Test
	public void testNestedJsonParametersReachTheQueryAsValues() throws Exception {
		HttpResponse<String> response = postRaw("/api/query",
			"{\"query\": \"RETURN $p.a AS a, $p.b.c AS c, size($p.a) AS n\","
				+ " \"parameters\": {\"p\": {\"a\": [1, 2, {\"x\": true}], \"b\": {\"c\": \"deep\"}}}}");
		assertEquals(200, response.statusCode(), response.body());
		JsonArray row = new JsonObject(response.body()).getJsonArray("rows").getJsonArray(0);
		assertEquals(new JsonArray().add(1).add(2).add(new JsonObject().put("x", true)), row.getJsonArray(0));
		assertEquals("deep", row.getString(1));
		assertEquals(3, row.getInteger(2));
	}

	@Test
	public void testNumericParametersKeepTheirValue() throws Exception {
		HttpResponse<String> response = postRaw("/api/query",
			"{\"query\": \"RETURN $big = 9007199254740993 AS exact, $small + 1 AS next, $f * 2 AS twice\","
				+ " \"parameters\": {\"big\": 9007199254740993, \"small\": 41, \"f\": 1.25}}");
		assertEquals(200, response.statusCode(), response.body());
		JsonArray row = new JsonObject(response.body()).getJsonArray("rows").getJsonArray(0);
		assertEquals(true, row.getBoolean(0), "an integer past 2^53 must reach the engine exactly");
		assertEquals(42, row.getInteger(1));
		assertEquals(2.5, row.getDouble(2));
	}

	@Test
	public void testABodyOverTheLimitIsA413() throws Exception {
		String query = "RETURN '" + "x".repeat(2 * 1024 * 1024) + "'";
		HttpResponse<String> response = postRaw("/api/query", new JsonObject().put("query", query).encode());
		assertEquals(413, response.statusCode(), "a body over 1 MiB");
	}

	@Test
	public void testDatabaseNamesInTheQueryString() throws Exception {
		assertClientError(postRaw("/api/query?database=nope", "{\"query\": \"RETURN 1\"}"), 404, "unknown database");
		assertClientError(postRaw("/api/query?database=..%2Fescape", "{\"query\": \"RETURN 1\"}"), 400, "a path");
		assertClientError(postRaw("/api/query?database=" + "a".repeat(200), "{\"query\": \"RETURN 1\"}"), 400,
			"an over-long name");
		assertEquals(200, postRaw("/api/query?database=other", "{\"query\": \"RETURN 1\"}").statusCode());
	}

	// ---------------------------------------------------------------- the other routes

	@Test
	public void testIndexRoutesWithBadBodies() throws Exception {
		for (String body : List.of("not json", "[1]", "{\"label\": 5, \"key\": \"k\"}", "{\"label\": \"L\"}",
			"{\"label\": \"L\", \"key\": \"k\", \"unique\": \"yes\"}")) {
			HttpResponse<String> response = postRaw("/api/indexes", body);
			assertClientError(response, 400, body);
		}
		HttpResponse<String> missing = send(HttpRequest.newBuilder(URI.create(base + "/api/indexes/Nope/nope"))
			.DELETE());
		assertClientError(missing, 404, "dropping an index that was never declared");
	}

	@Test
	public void testDatabaseRoutesWithBadNames() throws Exception {
		HttpResponse<String> invalid = send(HttpRequest.newBuilder(URI.create(base + "/api/databases/.hidden"))
			.PUT(HttpRequest.BodyPublishers.noBody()));
		assertClientError(invalid, 400, "a name that is not a name");
		HttpResponse<String> missing = send(HttpRequest.newBuilder(URI.create(base + "/api/databases/ghost"))
			.DELETE());
		assertClientError(missing, 404, "dropping a database that does not exist");
		HttpResponse<String> backup = send(HttpRequest.newBuilder(URI.create(base + "/api/databases/ghost/backup"))
			.GET());
		assertClientError(backup, 404, "backing up a database that does not exist");
		HttpResponse<String> exists = send(HttpRequest.newBuilder(URI.create(base + "/api/databases/other"))
			.PUT(HttpRequest.BodyPublishers.noBody()));
		assertTrue(exists.statusCode() >= 400 && exists.statusCode() < 500,
			"creating a database that exists is the caller's mistake: " + exists.statusCode() + " " + exists.body());
	}

	@Test
	public void testWrongMethodsAndContentTypes() throws Exception {
		HttpResponse<String> text = send(HttpRequest.newBuilder(URI.create(base + "/api/query"))
			.header("content-type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"RETURN 1\"}")));
		assertEquals(415, text.statusCode());
		HttpResponse<String> none = send(HttpRequest.newBuilder(URI.create(base + "/api/query"))
			.POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"RETURN 1\"}")));
		assertEquals(415, none.statusCode(), "no content type at all is not JSON either");
		HttpResponse<String> charset = send(HttpRequest.newBuilder(URI.create(base + "/api/query"))
			.header("content-type", "Application/JSON; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"RETURN 1\"}")));
		assertEquals(200, charset.statusCode(), "media types are case-insensitive and may carry parameters");
		HttpResponse<String> get = send(HttpRequest.newBuilder(URI.create(base + "/api/query")).GET());
		assertTrue(get.statusCode() >= 400 && get.statusCode() < 500 || get.statusCode() == 503,
			"GET on a POST route: " + get.statusCode());
	}
}
