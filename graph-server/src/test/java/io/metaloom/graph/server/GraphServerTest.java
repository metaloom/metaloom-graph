package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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

	private GraphStore store;
	private GraphServer server;
	private HttpClient client;
	private String base;

	@BeforeEach
	public void start() throws Exception {
		store = stores.open();
		try (Transaction txn = store.begin()) {
			long alice = store.createNode(java.util.List.of("Person", "Employee"),
				Properties.of().set("name", "Alice").set("age", 34L));
			long bob = store.createNode(java.util.List.of("Person"), Properties.of().set("name", "Bob"));
			long widget = store.createNode(java.util.List.of("Product"), Properties.of().set("name", "Widget"));
			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L));
			store.createEdge(alice, "BOUGHT", widget);
			txn.commit();
		}
		server = new GraphServer(store);
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
		if (store != null) {
			store.close();
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
	 */
	@Test
	public void testALinkToAnUnreturnedNodeIsDropped() throws Exception {
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
	}

	@Test
	public void testTheUiIsServed() throws Exception {
		HttpResponse<String> page = get("/");
		assertEquals(200, page.statusCode());
		assertTrue(page.body().contains("Graph browser"));

		// Vendored rather than fetched from a CDN, so the UI works on a machine with no internet access.
		HttpResponse<String> d3 = get("/vendor/d3.v7.min.js");
		assertEquals(200, d3.statusCode());
		assertTrue(d3.body().startsWith("// https://d3js.org"), "the D3 bundle must actually be there");

		assertEquals(200, get("/app.js").statusCode());
		assertEquals(200, get("/app.css").statusCode());
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
