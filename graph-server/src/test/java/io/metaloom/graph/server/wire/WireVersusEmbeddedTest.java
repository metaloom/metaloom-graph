package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.client.GraphClient;
import io.metaloom.graph.client.Row;
import io.metaloom.graph.client.RowStream;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.CypherResult;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;
import io.metaloom.graph.protocol.WireNode;
import io.metaloom.graph.protocol.WirePath;
import io.metaloom.graph.protocol.WireRelationship;

/**
 * The load-bearing test: the same query, in process and over the wire, must give the same answer.
 *
 * <p>
 * This is the wire's version of {@code CypherVersusApiTest}, and it exists for the same reason. Everything between the
 * engine and the client — the value conversion, the codec, the batching, the framing, the client's decoder — is a
 * chance to change a value quietly. A truncated result, a {@code Long} that became a {@code Double}, a lightweight
 * edge that acquired an id, a path whose nodes came back in a different order: none of those throw, and none of them
 * are visible in a test that only checks the row count.
 * </p>
 *
 * <p>
 * The comparison walks the values field by field rather than calling the server's own converter, because a test that
 * used the code under test to decide what the answer should be would agree with it however wrong it was.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
@Tag("unit")
public class WireVersusEmbeddedTest {

	/**
	 * Long enough that the storage codec would have escaped it to the blob store — a write, which a read-only wire has
	 * no right to do. Carrying it inline is the reason the wire has a codec of its own.
	 */
	private static final String LONG_TEXT = "über-lange Beschreibung ".repeat(64);

	private static final List<String> QUERIES = List.of(
		// Scalars, and every branch of the tag byte.
		"RETURN 1 AS one",
		"RETURN 'hello' AS greeting",
		"RETURN true AS yes, false AS no",
		"RETURN null AS nothing",
		"UNWIND [1, 2, 3] AS x RETURN x",
		"UNWIND [3, 1, 2] AS x RETURN x ORDER BY x",

		// Nodes, whole and projected.
		"MATCH (n) RETURN n",
		"MATCH (n:Person) RETURN n.name ORDER BY n.name",
		"MATCH (n:Person) RETURN n.name, n.age ORDER BY n.age DESC",
		"MATCH (n:Person:Employee) RETURN n",
		"MATCH (n:Product) RETURN n",

		// The properties that stress the codec: a long string, a null, a float, a list.
		"MATCH (n:Document) RETURN n",
		"MATCH (n:Document) RETURN n.body",
		"MATCH (n:Person) RETURN n.name, n.height ORDER BY n.name",
		"MATCH (n:Person) WHERE n.age IS NULL RETURN n.name",

		// Relationships, materialised and lightweight — the case where id() is null on purpose.
		"MATCH ()-[r:KNOWS]->() RETURN r, type(r), id(r)",
		"MATCH ()-[r:BOUGHT]->() RETURN r, type(r), id(r)",
		"MATCH (a)-[r]->(b) RETURN a.name, type(r), b.name ORDER BY a.name, type(r), b.name",

		// Paths.
		"MATCH p = (a:Person {name:'Alice'})-[:KNOWS*1..2]->(b) RETURN p, length(p)",
		"MATCH p = (a:Person {name:'Alice'})-[:KNOWS]->(b) RETURN p",

		// Aggregation, distinct and collect — the clauses that buffer whatever the protocol does.
		"MATCH (n:Person) RETURN count(*) AS rows, count(n.age) AS ages",
		"MATCH (n:Person) RETURN collect(n.city) AS every, collect(DISTINCT n.city) AS uniq",
		"MATCH (n:Person) RETURN DISTINCT n.city ORDER BY n.city",
		"MATCH (n:Person) RETURN n.city, count(*) AS people ORDER BY n.city",

		// Slicing, and the empty result — which still has to carry its column names.
		"MATCH (n:Person) RETURN n.name ORDER BY n.name SKIP 1 LIMIT 1",
		"MATCH (n {name: 'Nobody'}) RETURN n",
		"MATCH (n:Person)-[:KNOWS]->(f) RETURN *");

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;
	private WireServer server;
	private GraphClient client;

	@BeforeEach
	public void start() throws Exception {
		store = stores.open();
		seed(store);
		server = new WireServer(store, WireServerOptions.defaults().withPort(0));
		int port = server.listen().toCompletionStage().toCompletableFuture().join();
		client = GraphClient.connect("127.0.0.1", port);
	}

	@AfterEach
	public void stop() throws Exception {
		if (client != null) {
			client.close();
		}
		if (server != null) {
			// Every test in this class leaves the server idle. A permit that was taken and not given back shows up
			// here and nowhere else.
			assertEquals(0, server.runningStreams(), "a stream was still running after the test finished");
			server.close();
		}
		if (store != null) {
			store.close();
		}
	}

	private static void seed(GraphStore store) throws java.io.IOException {
		try (Transaction txn = store.begin()) {
			long alice = store.createNode(List.of("Person", "Employee"),
				Properties.of().set("name", "Alice").set("age", 34L).set("city", "Bonn").set("height", 1.68d));
			long bob = store.createNode(List.of("Person"),
				Properties.of().set("name", "Bob").set("city", "Bonn").set("height", 1.81d));
			long carol = store.createNode(List.of("Person"),
				Properties.of().set("name", "Carol").set("age", 41L).set("city", "Köln").set("height", 1.7d));
			long widget = store.createNode(List.of("Product"), Properties.of().set("name", "Widget"));
			store.createNode(List.of("Document"),
				Properties.of().set("title", "Handbuch").set("body", LONG_TEXT).set("pages", 12L));

			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L));
			store.createRelationship(bob, "KNOWS", carol, Properties.of().set("since", 2021L));
			// No record, so no id and no properties: the case a sentinel id would have quietly destroyed.
			store.createEdge(alice, "BOUGHT", widget);
			txn.commit();
		}
	}

	@Test
	public void testEveryQueryAgreesCellByCell() {
		for (String query : QUERIES) {
			CypherResult embedded = Cypher.query(store, query);
			List<List<Object>> overTheWire = new ArrayList<>();
			List<String> wireColumns;
			try (RowStream stream = client.query(query)) {
				wireColumns = stream.columns();
				for (Row row : stream) {
					overTheWire.add(row.values());
				}
			}

			assertEquals(embedded.columns(), wireColumns, "columns of: " + query);
			assertEquals(embedded.size(), overTheWire.size(), "row count of: " + query);
			for (int r = 0; r < embedded.size(); r++) {
				List<Object> expected = embedded.values().get(r);
				List<Object> actual = overTheWire.get(r);
				assertEquals(expected.size(), actual.size(), "cell count of row " + r + " of: " + query);
				for (int c = 0; c < expected.size(); c++) {
					assertSame(expected.get(c), actual.get(c),
						"row " + r + " column " + embedded.columns().get(c) + " of: " + query);
				}
			}
		}
	}

	@Test
	public void testALongStringSurvivesInline() {
		// Stated separately from the loop because it is the single fact that decided the codec split, and a failure
		// buried in a loop over twenty-seven queries says much less than one that names itself.
		try (RowStream stream = client.query("MATCH (n:Document) RETURN n.body")) {
			assertTrue(stream.hasNext());
			assertEquals(LONG_TEXT, stream.next().string("n.body"));
			assertEquals(LONG_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255, true,
				"the fixture has to exceed the storage codec's inline limit or this proves nothing");
		}
	}

	@Test
	public void testManyRowsArriveInOrderAcrossManyBatches() {
		// One batch is 64 KiB, so this crosses dozens of frame boundaries. Reassembly and ordering are the two things
		// that break there, and both are invisible in a query that fits in one frame.
		int count = 20_000;
		try (RowStream stream = client.query("UNWIND range(0, $n) AS x RETURN x", Map.of("n", (long) (count - 1)))) {
			long expected = 0;
			for (Row row : stream) {
				assertEquals(expected, row.longValue("x"), "row " + expected + " arrived out of order");
				expected++;
			}
			assertEquals(count, expected);
		}
	}

	@Test
	public void testAnEmptyResultStillCarriesItsColumns() {
		try (RowStream stream = client.query("MATCH (n:Nothing) RETURN n.name AS name, n.age AS age")) {
			assertEquals(List.of("name", "age"), stream.columns());
			assertEquals(false, stream.hasNext());
			assertEquals(0, stream.rowsRead());
		}
	}

	// ---------------------------------------------------------------- the comparison

	/**
	 * Compares an engine value with a wire value without going through the server's own converter.
	 *
	 * <p>
	 * The types are deliberately different — {@code CypherNode} against {@link WireNode} — so this cannot be an
	 * {@code equals}. Writing it out is what makes it a check rather than a restatement.
	 * </p>
	 */
	private static void assertSame(Object embedded, Object wire, String where) {
		switch (embedded) {
			case null -> assertNull(wire, where);
			case CypherNode node -> {
				WireNode actual = assertInstanceOf(WireNode.class, wire, where);
				assertEquals(node.id(), actual.id(), where + ": node id");
				assertEquals(node.labels(), actual.labels(), where + ": labels");
				assertSameMap(node.properties(), actual.properties(), where + ": properties");
			}
			case CypherRelationship rel -> {
				WireRelationship actual = assertInstanceOf(WireRelationship.class, wire, where);
				// Null and not zero. A lightweight edge has no record, and a client cannot tell an invented id from
				// a real one.
				assertEquals(rel.id(), actual.id(), where + ": relationship id");
				assertEquals(rel.isMaterialised(), actual.isMaterialised(), where + ": materialised");
				assertEquals(rel.type(), actual.type(), where + ": type");
				assertEquals(rel.startId(), actual.startId(), where + ": start");
				assertEquals(rel.endId(), actual.endId(), where + ": end");
				assertSameMap(rel.properties(), actual.properties(), where + ": properties");
			}
			case CypherPath path -> {
				WirePath actual = assertInstanceOf(WirePath.class, wire, where);
				assertEquals(path.length(), actual.length(), where + ": path length");
				assertEquals(path.nodes().size(), actual.nodes().size(), where + ": path node count");
				for (int i = 0; i < path.nodes().size(); i++) {
					assertSame(path.nodes().get(i), actual.nodes().get(i), where + ": path node " + i);
				}
				for (int i = 0; i < path.relationships().size(); i++) {
					assertSame(path.relationships().get(i), actual.relationships().get(i),
						where + ": path relationship " + i);
				}
			}
			case List<?> list -> {
				List<?> actual = assertInstanceOf(List.class, wire, where);
				assertEquals(list.size(), actual.size(), where + ": list size");
				for (int i = 0; i < list.size(); i++) {
					assertSame(list.get(i), actual.get(i), where + "[" + i + "]");
				}
			}
			case Map<?, ?> map -> assertSameMap(map, assertInstanceOf(Map.class, wire, where), where);
			default -> {
				// Scalars compare by equals, and by class as well: a Long that arrived as a Double is the exact kind
				// of quiet change this test is for, and 34L.equals(34.0d) is false but 34 == 34.0 reads as fine.
				assertEquals(embedded.getClass(), wire == null ? null : wire.getClass(), where + ": type");
				assertEquals(embedded, wire, where);
			}
		}
	}

	private static void assertSameMap(Map<?, ?> embedded, Map<?, ?> wire, String where) {
		assertEquals(embedded.keySet(), wire.keySet(), where + ": keys");
		for (Map.Entry<?, ?> entry : embedded.entrySet()) {
			assertSame(entry.getValue(), wire.get(entry.getKey()), where + "." + entry.getKey());
		}
	}
}
