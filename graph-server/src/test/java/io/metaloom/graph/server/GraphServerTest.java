package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * The HTTP layer, against a real server on a real port.
 *
 * <p>
 * The assertions that matter are about the two shapes the UI depends on — a table and a graph, from the same result —
 * and about a bad query producing a 400 with a usable message rather than a 500 with a stack trace.
 * </p>
 */
@Tag("unit")
public class GraphServerTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphStore store;
	private GraphServer server;
	private HttpClient client;
	private String base;

	@BeforeEach
	public void start() throws Exception {
		// A root of databases now, not one store. The default is made by open(); the tests that care about several
		// databases make their own.
		databases = GraphDatabases.open(stores.path().resolve("root"));
		store = databases.database(GraphDatabases.DEFAULT_NAME);
		try (Transaction txn = store.begin()) {
			long alice = store.createNode(java.util.List.of("Person", "Employee"),
				Properties.of().set("name", "Alice").set("age", 34L));
			long bob = store.createNode(java.util.List.of("Person"), Properties.of().set("name", "Bob"));
			long widget = store.createNode(java.util.List.of("Product"), Properties.of().set("name", "Widget"));
			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L));
			store.createEdge(alice, "BOUGHT", widget);
			txn.commit();
		}
		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, true);
		// Port zero: the OS picks a free one, so the test cannot collide with anything already listening.
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

	private HttpResponse<String> post(String query) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/query"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("query", query).encode()))
			.timeout(Duration.ofSeconds(20))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20)).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * A query that outlives its budget is abandoned, and says so.
	 *
	 * <p>
	 * The point is not that the request fails — it is <em>where</em> it fails. {@code executeBlocking} cannot
	 * interrupt a task that has started, so a route that merely stopped waiting would leave the scan running with a
	 * worker thread and a read view for as long as the data took. The assertion that the view count is back to zero
	 * immediately afterwards is the one that distinguishes the two.
	 * </p>
	 */
	@Test
	public void testAQueryPastItsBudgetIsAbandonedAndReleasesItsView() throws Exception {
		// Enough nodes that a scan crosses the engine's abort-check interval many times over, so the deadline is
		// consulted during execution rather than only between rows.
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 20_000; i++) {
				store.createNode(java.util.List.of("Filler"), Properties.of().set("n", (long) i));
			}
			txn.commit();
		}

		try (GraphServer strict = new GraphServer(databases, GraphDatabases.DEFAULT_NAME, 1, true)) {
			int port = strict.listen(0).toCompletionStage().toCompletableFuture().join();
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/query"))
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
					new JsonObject().put("query", "MATCH (n) RETURN n").encode()))
				.timeout(Duration.ofSeconds(20))
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			assertEquals(503, response.statusCode(),
				"the query was not wrong and the server is not broken; it declined to keep spending");
			JsonObject json = new JsonObject(response.body());
			assertEquals("timeout", json.getString("error"));
			assertTrue(json.getString("message").contains("1 ms"),
				"the message has to name the budget so an operator knows what to raise: " + json.getString("message"));
		}

		assertEquals(0, store.snapshotStats().openViews(),
			"an abandoned query must give its read view back, or the timeout has only hidden the problem");
	}

	@Test
	public void testAGenerousBudgetDoesNotTruncateAResult() throws Exception {
		// The inverse, so the test above cannot pass by the deadline always firing.
		try (GraphServer relaxed = new GraphServer(databases, GraphDatabases.DEFAULT_NAME, 60_000, true)) {
			int port = relaxed.listen(0).toCompletionStage().toCompletableFuture().join();
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/query"))
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
					new JsonObject().put("query", "MATCH (n:Person) RETURN n.name").encode()))
				.timeout(Duration.ofSeconds(20))
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			assertEquals(200, response.statusCode());
			assertEquals(2, new JsonObject(response.body()).getInteger("rowCount"));
		}
	}

	/** A [{name, count}] array as a map, so an assertion can name what it expects rather than an index. */
	private static Map<String, Long> namedCounts(JsonArray array) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (int i = 0; i < array.size(); i++) {
			counts.put(array.getJsonObject(i).getString("name"), array.getJsonObject(i).getLong("count"));
		}
		return counts;
	}

	private static Map<String, Long> counts(Object... pairs) {
		Map<String, Long> expected = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			expected.put((String) pairs[i], (Long) pairs[i + 1]);
		}
		return expected;
	}

	@Test
	public void testAQueryReturnsATableAndAGraph() throws Exception {
		HttpResponse<String> response = post("MATCH (a:Person)-[r:KNOWS]->(b) RETURN a, r, b");
		assertEquals(200, response.statusCode());
		JsonObject json = new JsonObject(response.body());

		assertEquals(new JsonArray().add("a").add("r").add("b"), json.getJsonArray("columns"));
		assertEquals(1, json.getInteger("rowCount"));

		JsonObject graph = json.getJsonObject("graph");
		assertEquals(2, graph.getJsonArray("nodes").size(), "both endpoints become vertices");
		assertEquals(1, graph.getJsonArray("links").size());

		JsonObject link = graph.getJsonArray("links").getJsonObject(0);
		assertEquals("KNOWS", link.getString("type"));
		assertTrue(link.getBoolean("materialised"), "this one has a relationship record");
	}

	@Test
	public void testACaptionIsChosenForEachNode() throws Exception {
		JsonObject json = new JsonObject(post("MATCH (n:Person) RETURN n").body());
		JsonArray nodes = json.getJsonObject("graph").getJsonArray("nodes");
		assertEquals(2, nodes.size());
		for (int i = 0; i < nodes.size(); i++) {
			String caption = nodes.getJsonObject(i).getString("caption");
			assertTrue(caption.equals("Alice") || caption.equals("Bob"),
				"the name property should be preferred over the id, got " + caption);
		}
	}

	@Test
	public void testALightweightEdgeIsMarkedAsSuch() throws Exception {
		JsonObject json = new JsonObject(post("MATCH (a)-[r:BOUGHT]->(b) RETURN a, r, b").body());
		JsonObject link = json.getJsonObject("graph").getJsonArray("links").getJsonObject(0);
		assertTrue(link.getValue("id") != null);
		assertEquals(Boolean.FALSE, link.getBoolean("materialised"),
			"an edge with no record must be distinguishable in the UI");
	}

	/**
	 * Ids must cross as strings, exactly.
	 *
	 * <p>
	 * A real id from this engine is around 2^60, and a JavaScript number is a double with 53 bits of mantissa — so
	 * {@code 1152921504606846977} arrives in a browser as {@code 1152921504606846976}. Two ids near enough together
	 * would land on the same double and D3 would draw one vertex for two nodes. Caught by running the UI, not by a
	 * test, which is why there is now a test.
	 * </p>
	 */
	@Test
	public void testIdsAreStringsAndSurviveExactly() throws Exception {
		JsonObject json = new JsonObject(post("MATCH (a:Person)-[r:KNOWS]->(b) RETURN a, r, b").body());
		JsonObject graph = json.getJsonObject("graph");

		JsonArray nodes = graph.getJsonArray("nodes");
		Set<String> ids = new java.util.HashSet<>();
		for (int i = 0; i < nodes.size(); i++) {
			Object id = nodes.getJsonObject(i).getValue("id");
			assertTrue(id instanceof String, "a node id must be a string, was " + id.getClass().getSimpleName());
			assertTrue(Long.parseLong((String) id) > (1L << 53),
				"this engine's ids are past the exactly-representable range, which is the whole point");
			ids.add((String) id);
		}
		assertEquals(nodes.size(), ids.size(), "no two nodes may collapse onto one id");

		JsonArray links = graph.getJsonArray("links");
		for (int i = 0; i < links.size(); i++) {
			JsonObject link = links.getJsonObject(i);
			assertTrue(link.getValue("source") instanceof String);
			assertTrue(ids.contains(link.getString("source")), "an endpoint must match a node exactly");
			assertTrue(ids.contains(link.getString("target")));
		}

		// The same applies to an id in a result column, which a user might paste back into a query.
		JsonObject scalar = new JsonObject(post("MATCH (n:Person) RETURN id(n) AS i LIMIT 1").body());
		Object cell = scalar.getJsonArray("rows").getJsonArray(0).getValue(0);
		assertTrue(cell instanceof String, "id() in a column must not be rounded on the way out, was " + cell);
	}

	@Test
	public void testAScalarOnlyResultHasAnEmptyGraph() throws Exception {
		JsonObject json = new JsonObject(post("MATCH (n:Person) RETURN n.name").body());
		assertEquals(2, json.getInteger("rowCount"));
		assertEquals(0, json.getJsonObject("graph").getJsonArray("nodes").size(),
			"nothing renderable was returned, and the UI says so rather than showing a blank canvas");
	}

	/**
	 * A relationship whose far end was not returned must not be drawn.
	 * <p>
	 * Otherwise D3 invents a vertex for the missing endpoint and the picture contains a node the query never produced.
	 * It is not thrown away either — see {@link #testAnUnreturnedEndpointArrivesAsADanglingLink()}.
	 */
	@Test
	public void testALinkToAnUnreturnedNodeIsNotDrawn() throws Exception {
		JsonObject json = new JsonObject(post("MATCH (a:Person)-[r:KNOWS]->(b) RETURN a, r").body());
		JsonObject graph = json.getJsonObject("graph");
		assertEquals(1, graph.getJsonArray("nodes").size(), "only a was returned");
		assertEquals(0, graph.getJsonArray("links").size(), "so the link has nowhere to end");
	}

	@Test
	public void testAPathIsExpandedIntoNodesAndLinks() throws Exception {
		JsonObject json = new JsonObject(post("MATCH p = (a:Person)-[:KNOWS*1..2]->(b) RETURN p").body());
		JsonObject graph = json.getJsonObject("graph");
		assertTrue(graph.getJsonArray("nodes").size() >= 2, "a path contributes every node it crosses");
		assertTrue(graph.getJsonArray("links").size() >= 1);
	}

	@Test
	public void testASyntaxErrorIsA400WithACaret() throws Exception {
		HttpResponse<String> response = post("MATCH (n:Person) WHERE n.age > > 30 RETURN n");
		assertEquals(400, response.statusCode(), "the user's query is wrong, not the server");
		JsonObject json = new JsonObject(response.body());
		assertEquals("syntax", json.getString("error"));
		assertEquals(1, json.getInteger("line"));
		assertTrue(json.getInteger("column") > 0);
		assertTrue(json.getString("message").contains("^"), "the caret line is what makes the message usable");
	}

	@Test
	public void testAWriteQueryIsRefusedAsReadOnly() throws Exception {
		HttpResponse<String> response = post("CREATE (n:Person) RETURN n");
		assertEquals(400, response.statusCode());
		assertTrue(new JsonObject(response.body()).getString("message").contains("read-only"));
	}

	@Test
	public void testATypeErrorIsA400() throws Exception {
		HttpResponse<String> response = post("MATCH (n) RETURN frobnicate(n)");
		assertEquals(400, response.statusCode());
		assertEquals("type", new JsonObject(response.body()).getString("error"));
	}

	@Test
	public void testAnEmptyQueryIsRefusedWithGuidance() throws Exception {
		HttpResponse<String> response = post("   ");
		assertEquals(400, response.statusCode());
		assertTrue(new JsonObject(response.body()).getString("message").contains("MATCH"));
	}

	@Test
	public void testStats() throws Exception {
		HttpResponse<String> response = get("/api/stats");
		assertEquals(200, response.statusCode());
		JsonObject json = new JsonObject(response.body());
		assertEquals(3, json.getLong("nodes"));
		assertEquals(1, json.getLong("relationships"), "the lightweight edge has no record and is not counted");
		assertEquals(0, json.getInteger("openViews"), "a finished query must not have left a view open");
		// Not the value — that legitimately differs between a build with the Rust crate and one without, and
		// between a jar and a GraalVM binary. The point is that it is answerable at all: spec/NATIVE_FFM.md
		// section 4 promises the store works either way, which makes "which way is this one" worth exposing.
		assertNotNull(json.getString("nativeLayer"), "the native path this process took has to be visible");
		assertFalse(json.getString("nativeLayer").isBlank(), "nativeLayer must say something");
	}

	/**
	 * The UI, whichever of the two supported builds this is.
	 *
	 * <p>
	 * With npm present the React bundle is in the jar and the page must load — including its hashed asset, which is
	 * the part a broken build would silently drop while still serving a valid-looking index. With {@code -Dui.skip} or
	 * no npm there is no bundle, and the server must say so rather than 404: a user who gets "Not Found" from the root
	 * of their own server has no way to tell a missing bundle from a broken deployment.
	 * </p>
	 */
	@Test
	public void testTheUiIsServed() throws Exception {
		HttpResponse<String> page = get("/");
		boolean bundled = GraphServer.class.getResource("/webroot/index.html") != null;
		if (!bundled) {
			assertEquals(503, page.statusCode());
			assertTrue(page.body().contains("No UI bundle"), "the page has to name the reason");
			return;
		}

		assertEquals(200, page.statusCode());
		assertTrue(page.body().contains("Graph browser"));

		// Vite writes content-hashed asset names into the page, so following one proves the bundle in this jar is the
		// one the page asks for. A stale or half-copied webroot fails here and nowhere else.
		Matcher script = Pattern.compile("src=\"\\.?/?(assets/[^\"]+\\.js)\"").matcher(page.body());
		assertTrue(script.find(), "the page must reference a bundled script, was:\n" + page.body());
		HttpResponse<String> asset = get("/" + script.group(1));
		assertEquals(200, asset.statusCode(), "the script the page asks for must be in the jar");
		assertTrue(asset.body().length() > 10_000, "that is not a React bundle");

		Matcher style = Pattern.compile("href=\"\\.?/?(assets/[^\"]+\\.css)\"").matcher(page.body());
		assertTrue(style.find(), "the page must reference a bundled stylesheet");
		assertEquals(200, get("/" + style.group(1)).statusCode());
	}

	@Test
	public void testTheSchemaListsLabelsAndTypesWithCounts() throws Exception {
		HttpResponse<String> response = get("/api/schema");
		assertEquals(200, response.statusCode());
		JsonObject json = new JsonObject(response.body());

		assertEquals(counts("Person", 2L, "Employee", 1L, "Product", 1L), namedCounts(json.getJsonArray("labels")));
		// A lightweight edge has no relationship record, so a count from the record store would miss it. These counts
		// come from the adjacency list, where both kinds live.
		assertEquals(counts("BOUGHT", 1L, "KNOWS", 1L), namedCounts(json.getJsonArray("relationshipTypes")));
		assertEquals(3L, json.getLong("nodeCount"));
		assertEquals(2L, json.getLong("relationshipCount"));
		assertEquals(0L, json.getLong("unlabelledNodeCount"));
		assertEquals(Boolean.FALSE, json.getBoolean("cached"), "the first call cannot come from a cache");
	}

	/**
	 * The scan is O(nodes + edges), so it is cached — and the response says which answer this is.
	 * <p>
	 * A user looking at a sidebar has to be able to tell "this is what the store contains" from "this is what it
	 * contained a minute ago", which is what the flag and the rescan button are for.
	 */
	@Test
	public void testTheSchemaIsCachedUntilARescanIsAskedFor() throws Exception {
		assertEquals(Boolean.FALSE, new JsonObject(get("/api/schema").body()).getBoolean("cached"));
		assertEquals(Boolean.TRUE, new JsonObject(get("/api/schema").body()).getBoolean("cached"));
		assertEquals(Boolean.FALSE, new JsonObject(get("/api/schema?refresh=true").body()).getBoolean("cached"));
	}

	@Test
	public void testARescanSeesANewNode() throws Exception {
		try (Transaction txn = store.begin()) {
			store.createNode(java.util.List.of("Robot"), Properties.of().set("name", "Marvin"));
			txn.commit();
		}
		JsonObject json = new JsonObject(get("/api/schema?refresh=true").body());
		assertEquals(4L, json.getLong("nodeCount"));
		assertTrue(namedCounts(json.getJsonArray("labels")).containsKey("Robot"));
	}

	/**
	 * A relationship whose far end this result did not return travels separately.
	 * <p>
	 * It cannot be drawn now — D3 would invent a vertex for the missing end — but in merge mode the far end may
	 * already be on the canvas from an earlier query, so discarding it would make merge mode unable to join anything
	 * up.
	 */
	@Test
	public void testAnUnreturnedEndpointArrivesAsADanglingLink() throws Exception {
		JsonObject graph = new JsonObject(post("MATCH (a:Person)-[r:KNOWS]->(b) RETURN a, r").body())
			.getJsonObject("graph");
		assertEquals(0, graph.getJsonArray("links").size(), "not drawable from this result alone");
		assertEquals(1, graph.getJsonArray("danglingLinks").size());
		JsonObject dangling = graph.getJsonArray("danglingLinks").getJsonObject(0);
		assertEquals("KNOWS", dangling.getString("type"));
		assertTrue(dangling.getValue("target") instanceof String, "still a string id, like every other id");
	}

	@Test
	public void testConcurrentQueriesDoNotBlockEachOther() throws Exception {
		// Queries run on worker threads, so several can be in flight at once. If they ran on the event loop this
		// would still pass but would have serialised; what it really guards is that none of them fails.
		java.util.List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> pending = new java.util.ArrayList<>();
		for (int i = 0; i < 8; i++) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/query"))
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
					new JsonObject().put("query", "MATCH (n) RETURN count(*) AS c").encode()))
				.build();
			pending.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
		}
		for (java.util.concurrent.CompletableFuture<HttpResponse<String>> future : pending) {
			HttpResponse<String> response = future.join();
			assertEquals(200, response.statusCode());
			assertNotNull(new JsonObject(response.body()).getJsonArray("rows"));
		}
	}
}
