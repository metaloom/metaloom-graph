package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * The HTTP surface of the declared composite indexes: list, declare, drop.
 *
 * <p>
 * These routes exist because the index has no other surface reachable from a browser — the Cypher subset has no
 * {@code CREATE INDEX}, so before them a declaration was a Java call. So what is asserted here is not only the status
 * codes but that a declaration made over HTTP is the same declaration the engine acts on: the index is listed, the
 * unique flag is enforced on the next write, and dropping it gives the entries back.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/INDEX.md">spec/INDEX.md</a> section 3.3
 */
@Tag("unit")
public class IndexRoutesTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphServer server;
	private HttpClient client;
	private String base;

	@BeforeEach
	public void start() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		seed(databases.database(GraphDatabases.DEFAULT_NAME), "wes@example.com");
		seed(databases.create("sales"), "owen@example.com");
		startServer(true);
	}

	private void startServer(boolean allowWrite) throws Exception {
		if (server != null) {
			server.close();
		}
		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, false, allowWrite);
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

	private static void seed(GraphStore store, String email) throws Exception {
		try (Transaction txn = store.begin()) {
			store.createNode("Person", Properties.of().set("email", email));
			store.createNode("Company", Properties.of().set("email", email));
			txn.commit();
		}
	}

	// ---------------------------------------------------------------- listing

	@Test
	public void testAStoreWithNoDeclarationListsNone() throws Exception {
		JsonObject body = getJson("/api/indexes");
		assertEquals("default", body.getString("database"));
		assertEquals(0, body.getJsonArray("indexes").size());
		assertEquals(0, body.getJsonArray("uniqueConstraints").size());
		assertTrue(body.getBoolean("allowWrite"));
	}

	@Test
	public void testDeclaringThenListing() throws Exception {
		assertEquals(200, create("Person", "email", false).statusCode());

		JsonArray indexes = getJson("/api/indexes").getJsonArray("indexes");
		assertEquals(1, indexes.size());
		assertEquals("Person", indexes.getJsonObject(0).getString("label"));
		assertEquals("email", indexes.getJsonObject(0).getString("key"));
		assertFalse(indexes.getJsonObject(0).getBoolean("unique"));
		// The store agrees, which is the assertion that makes the route worth having.
		assertTrue(databases.database("default").hasIndex("Person", "email"));
	}

	@Test
	public void testTheListIsScopedToItsDatabase() throws Exception {
		assertEquals(200, create("Person", "email", false).statusCode());
		assertEquals(1, getJson("/api/indexes").getJsonArray("indexes").size());
		assertEquals(0, getJson("/api/indexes?database=sales").getJsonArray("indexes").size(),
			"a declaration belongs to one store, and the panel must not show another's");

		assertEquals(200, create("sales", "Company", "email", false).statusCode());
		assertEquals("Company", getJson("/api/indexes?database=sales").getJsonArray("indexes")
			.getJsonObject(0).getString("label"));
	}

	@Test
	public void testTheListNamesTheStoreWideConstraintsToo() throws Exception {
		databases.database("default").createUniqueConstraint("ssn");
		JsonObject body = getJson("/api/indexes");
		assertEquals(List.of("ssn"), body.getJsonArray("uniqueConstraints").getList());
		assertEquals(0, body.getJsonArray("indexes").size(),
			"a store-wide constraint is not a composite index and must not be listed as one");
	}

	@Test
	public void testAnUnknownDatabaseIs404() throws Exception {
		assertEquals(404, get("/api/indexes?database=nope").statusCode());
	}

	// ---------------------------------------------------------------- declaring

	@Test
	public void testAUniqueDeclarationIsEnforcedOnTheNextWrite() throws Exception {
		HttpResponse<String> created = create("Person", "email", true);
		assertEquals(200, created.statusCode(), created.body());
		assertTrue(getJson("/api/indexes").getJsonArray("indexes").getJsonObject(0).getBoolean("unique"));

		HttpResponse<String> refused = post("/api/query",
			"CREATE (p:Person {email: 'wes@example.com'}) RETURN p");
		assertEquals(409, refused.statusCode(), refused.body());
		assertEquals("constraint", new JsonObject(refused.body()).getString("error"));
		assertTrue(refused.body().contains(":Person.email"), refused.body());

		// And the scope is real: the same address under another label is still accepted.
		assertEquals(200, post("/api/query", "CREATE (c:Company {email: 'wes@example.com'}) RETURN c").statusCode());
	}

	@Test
	public void testDeclaringUniqueOverDuplicatesIs409AndDeclaresNothing() throws Exception {
		assertEquals(200, post("/api/query", "CREATE (p:Person {email: 'wes@example.com'})").statusCode());

		HttpResponse<String> refused = create("Person", "email", true);
		assertEquals(409, refused.statusCode(), refused.body());
		assertEquals(0, getJson("/api/indexes").getJsonArray("indexes").size(),
			"a refused declaration must not be recorded");
	}

	@Test
	public void testDeclaringTheSamePairTwiceIsAccepted() throws Exception {
		assertEquals(200, create("Person", "email", false).statusCode());
		assertEquals(200, create("Person", "email", false).statusCode(),
			"createIndex is idempotent, so a repeat is not a conflict");
		assertEquals(1, getJson("/api/indexes").getJsonArray("indexes").size());
	}

	@Test
	public void testABodyWithoutALabelOrKeyIs400() throws Exception {
		assertEquals(400, postJson("/api/indexes", new JsonObject().put("key", "email")).statusCode());
		assertEquals(400, postJson("/api/indexes", new JsonObject().put("label", "Person")).statusCode());
		assertEquals(400, postJson("/api/indexes", new JsonObject().put("label", " ").put("key", "email"))
			.statusCode());
	}

	// ---------------------------------------------------------------- dropping

	@Test
	public void testDroppingRemovesTheDeclarationAndTheConstraint() throws Exception {
		assertEquals(200, create("Person", "email", true).statusCode());
		assertEquals(200, delete("/api/indexes/Person/email").statusCode());

		assertEquals(0, getJson("/api/indexes").getJsonArray("indexes").size());
		assertFalse(databases.database("default").hasIndex("Person", "email"));
		// The constraint went with it, which is what the UI warns about before sending this.
		assertEquals(200, post("/api/query", "CREATE (p:Person {email: 'wes@example.com'}) RETURN p").statusCode());
	}

	@Test
	public void testDroppingWhatWasNeverDeclaredIs404() throws Exception {
		HttpResponse<String> response = delete("/api/indexes/Person/email");
		assertEquals(404, response.statusCode());
		assertEquals("index", new JsonObject(response.body()).getString("error"),
			"an index 404 must not read as a missing database");
	}

	@Test
	public void testALabelWithAwkwardCharactersSurvivesTheRoundTrip() throws Exception {
		// A label is arbitrary text; the grammar backtick-quotes the ones that are not identifiers. The path segments
		// carry it encoded, and a route that decoded it differently from the way the UI encoded it would drop the
		// wrong index or none at all.
		String label = "Air port/Hub";
		String key = "iata code";
		assertEquals(200, create(label, key, false).statusCode());
		assertEquals(label, getJson("/api/indexes").getJsonArray("indexes").getJsonObject(0).getString("label"));

		assertEquals(200, delete("/api/indexes/" + encode(label) + "/" + encode(key)).statusCode());
		assertEquals(0, getJson("/api/indexes").getJsonArray("indexes").size());
	}

	// ---------------------------------------------------------------- the write flag

	@Test
	public void testDeclaringAndDroppingAreRefusedOnAReadOnlyServer() throws Exception {
		assertEquals(200, create("Person", "email", false).statusCode());
		startServer(false);

		HttpResponse<String> declined = create("Company", "email", false);
		assertEquals(403, declined.statusCode());
		assertTrue(declined.body().contains("--allow-write"), declined.body());

		HttpResponse<String> refused = delete("/api/indexes/Person/email");
		assertEquals(403, refused.statusCode());
		assertTrue(databases.database("default").hasIndex("Person", "email"), "and nothing was dropped");

		// Listing still works, and says why the buttons are missing.
		JsonObject body = getJson("/api/indexes");
		assertEquals(1, body.getJsonArray("indexes").size());
		assertFalse(body.getBoolean("allowWrite"),
			"the UI has to be told, or it offers buttons whose every click comes back 403");
	}

	// ---------------------------------------------------------------- plumbing

	/**
	 * Path-segment encoding, which is not what {@code URLEncoder} does.
	 * <p>
	 * {@code URLEncoder} is form encoding: it writes a space as {@code +}, which inside a path is a literal plus sign
	 * and addresses a different index. The browser's {@code encodeURIComponent} — what {@code api.ts} calls — writes
	 * {@code %20}, so this mirrors that rather than the other spelling.
	 */
	private static String encode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
	}

	private HttpResponse<String> create(String label, String key, boolean unique) throws Exception {
		return create(null, label, key, unique);
	}

	private HttpResponse<String> create(String database, String label, String key, boolean unique) throws Exception {
		return postJson("/api/indexes" + (database == null ? "" : "?database=" + database),
			new JsonObject().put("label", label).put("key", key).put("unique", unique));
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
		return postJson(path, new JsonObject().put("query", query));
	}

	private HttpResponse<String> postJson(String path, JsonObject body) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(base + path))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.encode()))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> delete(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
			HttpResponse.BodyHandlers.ofString());
	}
}
