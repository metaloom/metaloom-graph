package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.CypherResult;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;
import io.metaloom.graph.protocol.QuerySummary;
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
	 * Long enough that the storage codec would have escaped it to the blob store, which needs a blob store and the
	 * right to write to one — neither of which a value being encoded onto a socket has. Carrying it inline is the
	 * reason the wire has a codec of its own.
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

	private GraphDatabases databases;
	private GraphStore store;
	private WireServer server;
	private GraphClient client;

	@BeforeEach
	public void start() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		store = databases.database(GraphDatabases.DEFAULT_NAME);
		seed(store);
		server = new WireServer(databases, GraphDatabases.DEFAULT_NAME, WireServerOptions.defaults().withPort(0), true);
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
		if (databases != null) {
			databases.close();
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

	/**
	 * The write arm: the same statement run both ways, against two stores, compared by what it left behind.
	 *
	 * <p>
	 * A write query returns few rows or none, so comparing results the way the read arm does would compare almost
	 * nothing. What has to agree is the effect — so each statement runs embedded against one store and over the wire
	 * against another seeded identically, and both the resulting graphs and the reported counters are compared.
	 * </p>
	 */
	@Test
	public void testEveryWriteHasTheSameEffectBothWays() throws Exception {
		List<String> writes = List.of(
			"CREATE (n:City {name: 'Bonn', founded: -12})",
			"MATCH (p:Person) CREATE (p)-[:LIVES_IN {since: 2020}]->(:City {name: 'Köln'})",
			"MATCH (p:Person {name: 'Alice'}) SET p.age = 35, p.city = 'Köln', p.tags = ['a', 'b']",
			"MATCH (p:Person) SET p:Resident",
			"MATCH (p:Person {name: 'Bob'}) REMOVE p.city, p:Person",
			"MATCH (p:Person) WHERE p.age IS NULL SET p = {name: p.name, replaced: true}",
			"MATCH ()-[r:KNOWS]->() SET r.checked = true",
			"MATCH ()-[r:KNOWS {since: 2019}]->() DELETE r",
			"MATCH (n:Document) DETACH DELETE n",
			"MATCH (n:Product) DETACH DELETE n",
			"MATCH (p:Person) SET p.n = size(p.name) RETURN p.name AS name, p.n AS n ORDER BY name");

		// A second database under the same root, which is the cheapest way to get an independent store — and the
		// connection is bound to the default one at its handshake, so the two cannot be confused.
		GraphStore reference = databases.create("reference");

		// The control: the fingerprint has to say something, and has to change when the graph does. Without this the
		// comparison below could be two empty strings agreeing.
		reseed(reference);
		String seeded = fingerprint(reference);
		assertTrue(seeded.lines().count() >= 5, "the fingerprint must describe the seeded graph");
		Cypher.query(reference, "MATCH (p:Person {name: 'Alice'}) SET p.age = 99");
		assertNotEquals(seeded, fingerprint(reference), "a fingerprint that cannot tell two graphs apart proves nothing");

		for (String write : writes) {
			reseed(reference);
			reseed(store);

			CypherResult embedded = Cypher.query(reference, write);
			QuerySummary wire = client.execute(write);

			assertEquals(embedded.stats().nodesCreated(), wire.nodesCreated(), "nodesCreated of: " + write);
			assertEquals(embedded.stats().nodesDeleted(), wire.nodesDeleted(), "nodesDeleted of: " + write);
			assertEquals(embedded.stats().relationshipsCreated(), wire.relationshipsCreated(),
				"relationshipsCreated of: " + write);
			assertEquals(embedded.stats().relationshipsDeleted(), wire.relationshipsDeleted(),
				"relationshipsDeleted of: " + write);
			assertEquals(embedded.stats().propertiesSet(), wire.propertiesSet(), "propertiesSet of: " + write);
			assertEquals(embedded.stats().labelsAdded(), wire.labelsAdded(), "labelsAdded of: " + write);
			assertEquals(embedded.stats().labelsRemoved(), wire.labelsRemoved(), "labelsRemoved of: " + write);
			assertEquals(embedded.size(), wire.rows(), "row count of: " + write);

			// The counters agreeing is not enough: two runs could report the same numbers and leave different graphs.
			assertEquals(fingerprint(reference), fingerprint(store), "the resulting graph of: " + write);
			reference.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}

	/** Every node with its labels, properties and outgoing edges, ordered so two stores can be compared as text. */
	private static String fingerprint(GraphStore store) {
		List<String> lines = new ArrayList<>();
		store.forEachNode(id -> {
			StringBuilder line = new StringBuilder();
			line.append(new java.util.TreeSet<>(store.nodeLabels(id))).append(' ');
			java.util.TreeMap<String, Object> properties = new java.util.TreeMap<>();
			for (String key : store.nodeProperties(id).keys()) {
				properties.put(key, String.valueOf(store.nodeProperties(id).get(key)));
			}
			line.append(properties).append(" ->");
			List<String> edges = new ArrayList<>();
			for (io.metaloom.graph.core.api.Edge edge : store.neighbours(id,
				io.metaloom.graph.core.api.Direction.OUT)) {
				// By the far end's identity rather than its id: the two stores allocate different ids.
				edges.add(edge.type() + ":" + store.nodeProperties(edge.neighbourId()).get("name")
					+ (edge.isMaterialised()
						? String.valueOf(new java.util.TreeMap<>(
							store.relationshipProperties(edge.relationshipId()).asMap()))
						: "[light]"));
			}
			java.util.Collections.sort(edges);
			line.append(edges);
			lines.add(line.toString());
		});
		java.util.Collections.sort(lines);
		return String.join("\n", lines);
	}

	/** Empties a store and seeds it again, so each write statement starts from the same graph. */
	private static void reseed(GraphStore store) throws Exception {
		try (Transaction txn = store.begin()) {
			for (long id : allNodes(store)) {
				// deleteNode cascades, so a node whose relationships went with an earlier one is simply gone.
				if (store.nodeExists(id)) {
					store.deleteNode(id);
				}
			}
			txn.commit();
		}
		seed(store);
	}

	private static List<Long> allNodes(GraphStore store) {
		List<Long> ids = new ArrayList<>();
		store.forEachNode(ids::add);
		return ids;
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
