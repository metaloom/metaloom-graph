package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The HTTP surface of several databases: listing, scoping, creating, dropping.
 *
 * <p>
 * The assertions that matter are that {@code ?database=} genuinely reaches a different store — proved by writing
 * different data into each and reading it back through the routes — and that a name arriving in a URL cannot become a
 * path outside the root.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/DATABASES.md">spec/DATABASES.md</a>
 */
@Tag("unit")
public class DatabaseRoutesTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphServer server;
	private HttpClient client;
	private String base;

	@BeforeEach
	public void start() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		seed(databases.database(GraphDatabases.DEFAULT_NAME), "Alice");
		seed(databases.create("sales"), "Widget");

		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, true);
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		base = "http://localhost:" + port;
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

	private static void seed(GraphStore store, String name) throws Exception {
		try (Transaction txn = store.begin()) {
			store.createNode("Thing", Properties.of().set("name", name));
			txn.commit();
		}
	}

	// ---------------------------------------------------------------- listing

	@Test
	public void testTheListNamesEveryDatabaseAndWhichIsDefault() throws Exception {
		JsonObject body = getJson("/api/databases");
		assertEquals(GraphDatabases.DEFAULT_NAME, body.getString("default"));
		assertTrue(body.getBoolean("allowDrop"), "this server was started with dropping enabled");

		JsonArray list = body.getJsonArray("databases");
		assertEquals(2, list.size());
		assertEquals(List.of("default", "sales"), List.of(
			list.getJsonObject(0).getString("name"), list.getJsonObject(1).getString("name")));
		assertTrue(list.getJsonObject(0).getBoolean("default"));
		assertFalse(list.getJsonObject(1).getBoolean("default"));
	}

	@Test
	public void testAnUnopenedDatabaseIsListedWithoutCounts() throws Exception {
		// Listing a root of fifty databases must not map fifty sets of segment files, so counts are only there for
		// stores that are already open.
		databases.create("cold");
		databases.close();
		databases = GraphDatabases.open(stores.path().resolve("root"));
		server.close();
		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, true);
		base = "http://localhost:" + server.listen(0).toCompletionStage().toCompletableFuture().join();

		for (Object entry : getJson("/api/databases").getJsonArray("databases")) {
			JsonObject database = (JsonObject) entry;
			assertFalse(database.getBoolean("open"), database.getString("name") + " should still be closed");
			assertFalse(database.containsKey("nodes"), "an unopened database has no counts to report");
		}
	}

	// ---------------------------------------------------------------- scoping

	@Test
	public void testAQueryReachesTheDatabaseItNames() throws Exception {
		assertEquals("Alice", queryOneName(null), "no ?database= means the server's default");
		assertEquals("Alice", queryOneName("default"));
		assertEquals("Widget", queryOneName("sales"));
	}

	@Test
	public void testStatsAndSchemaAreScopedToo() throws Exception {
		// A picker that switched the query but not the sidebar would show one database's labels beside another's rows.
		assertEquals("default", getJson("/api/stats").getString("database"));
		assertEquals("sales", getJson("/api/stats?database=sales").getString("database"));

		JsonObject schema = getJson("/api/schema?database=sales");
		assertEquals(1, schema.getInteger("nodeCount"));
		assertEquals("Thing", schema.getJsonArray("labels").getJsonObject(0).getString("name"));
	}

	@Test
	public void testANameThatIsNotThereIs404AndNot500() throws Exception {
		// 404 and not 409: the name is wrong, so listing or creating is the next step. A 409 would invite a retry
		// that cannot work.
		HttpResponse<String> response = get("/api/stats?database=nope");
		assertEquals(404, response.statusCode());
		assertEquals("database", new JsonObject(response.body()).getString("error"));

		assertEquals(404, post("/api/query?database=nope", "MATCH (n) RETURN n").statusCode());
		assertEquals(404, get("/api/schema?database=nope").statusCode());
	}

	@Test
	public void testANameThatIsNotANameIs400() throws Exception {
		// The name is about to become a path. Refused before it is resolved against the root, and refused with a
		// message that says what a name may hold rather than what this one did wrong.
		for (String hostile : List.of("../escaped", "..", "/etc/passwd", "with%20space", "a;b")) {
			HttpResponse<String> response = get("/api/stats?database="
				+ java.net.URLEncoder.encode(hostile, java.nio.charset.StandardCharsets.UTF_8));
			assertEquals(400, response.statusCode(), "'" + hostile + "' should be a bad request, not a lookup");
			assertNotNull(new JsonObject(response.body()).getString("message"));
		}
		assertFalse(Files.exists(stores.path().resolve("escaped")), "nothing may be created outside the root");
	}

	// ---------------------------------------------------------------- create and drop

	@Test
	public void testCreatingADatabaseAndQueryingIt() throws Exception {
		HttpResponse<String> created = put("/api/databases/hr");
		assertEquals(201, created.statusCode());
		assertEquals("hr", new JsonObject(created.body()).getString("name"));

		assertTrue(databases.exists("hr"));
		assertTrue(Files.isRegularFile(stores.path().resolve("root").resolve("hr").resolve("MANIFEST")),
			"a database is a directory of its own");

		// Empty, and separately queryable.
		assertEquals(0, getJson("/api/stats?database=hr").getInteger("nodes"));
		assertEquals(1, getJson("/api/stats?database=sales").getInteger("nodes"));
	}

	@Test
	public void testCreatingOneThatExistsIsRefusedRatherThanEmptyingIt() throws Exception {
		HttpResponse<String> response = put("/api/databases/sales");
		assertEquals(409, response.statusCode());
		// And the data is still there, which is the assertion that matters.
		assertEquals("Widget", queryOneName("sales"));
	}

	@Test
	public void testCreatingWithAHostileNameIsRefused() throws Exception {
		assertEquals(400, put("/api/databases/" + java.net.URLEncoder.encode("../escaped",
			java.nio.charset.StandardCharsets.UTF_8)).statusCode());
		assertFalse(Files.exists(stores.path().resolve("escaped")));
		assertFalse(Files.exists(stores.path().getParent().resolve("escaped")));
	}

	@Test
	public void testDroppingRemovesTheDirectory() throws Exception {
		assertEquals(200, delete("/api/databases/sales").statusCode());
		assertFalse(databases.exists("sales"));
		assertFalse(Files.exists(stores.path().resolve("root").resolve("sales")));
		assertEquals(404, get("/api/stats?database=sales").statusCode());
	}

	@Test
	public void testDroppingOneThatIsNotThereIs404() throws Exception {
		assertEquals(404, delete("/api/databases/nope").statusCode());
	}

	@Test
	public void testTheLastDatabaseCannotBeDropped() throws Exception {
		assertEquals(200, delete("/api/databases/sales").statusCode());
		HttpResponse<String> response = delete("/api/databases/default");
		assertEquals(409, response.statusCode());
		assertTrue(new JsonObject(response.body()).getString("message").contains("only database"));
	}

	@Test
	public void testDroppingIsRefusedWhenTheServerWasNotStartedForIt() throws Exception {
		// The default. Deleting data over an unauthenticated port beside a query language that cannot delete a single
		// node should take a deliberate flag.
		server.close();
		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, false);
		base = "http://localhost:" + server.listen(0).toCompletionStage().toCompletableFuture().join();

		HttpResponse<String> response = delete("/api/databases/sales");
		assertEquals(403, response.statusCode());
		assertTrue(response.body().contains("--allow-drop"), response.body());
		assertTrue(databases.exists("sales"), "and nothing was deleted");
		assertFalse(getJson("/api/databases").getBoolean("allowDrop"),
			"the UI has to be told, or it will offer a button that always fails");
	}

	// ---------------------------------------------------------------- plumbing

	private String queryOneName(String database) throws Exception {
		String path = database == null ? "/api/query" : "/api/query?database=" + database;
		HttpResponse<String> response = post(path, "MATCH (n:Thing) RETURN n.name AS name");
		assertEquals(200, response.statusCode(), response.body());
		return new JsonObject(response.body()).getJsonArray("rows").getJsonArray(0).getString(0);
	}

	private JsonObject getJson(String path) throws Exception {
		HttpResponse<String> response = get(path);
		assertEquals(200, response.statusCode(), response.body());
		return new JsonObject(response.body());
	}

	private HttpResponse<String> get(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String query) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(base + path))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("query", query).encode()))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> put(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(base + path))
			.PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> delete(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
			HttpResponse.BodyHandlers.ofString());
	}
}
