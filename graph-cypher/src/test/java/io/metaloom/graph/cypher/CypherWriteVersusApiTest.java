package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * The load-bearing test for the write clauses, and the inverse of {@link CypherVersusApiTest}.
 *
 * <p>
 * There the query is the thing under test and the storage API is the oracle. Here the <em>write</em> is the thing under
 * test, and there are two oracles: the Java API, which says whether the store now holds what the query claimed, and
 * {@code verify(FULL)}, which says whether it holds anything it should not. The second is the one that catches the
 * failures a hand-written assertion never would — a relationship recorded on one endpoint and not the other, a degree
 * counter that drifted, a property block nobody owns, an index entry pointing at a freed slot.
 * </p>
 *
 * <p>
 * The graphs are seeded and random, so what the clauses meet is not what a test author would have thought to write:
 * nodes with several labels and none, missing properties, parallel edges between one pair, self-loops, and a mix of
 * materialised relationships and lightweight edges. Each test seeds its own graph, and the seed is in the call so a
 * failure reproduces.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a>
 */
@Tag("unit")
public class CypherWriteVersusApiTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int NODES = 120;
	private static final String[] LABELS = { "Person", "Employee", "Manager", "Product", "Tag" };
	private static final String[] CITIES = { "Bonn", "Berlin", "Hamburg" };
	private static final String[] TYPES = { "KNOWS", "OWNS", "TAGGED" };

	private record Graph(GraphStore store, List<Long> nodes) {
	}

	private Graph seed(long seed) throws Exception {
		GraphStore store = stores.open();
		Random random = new Random(seed);
		List<Long> nodes = new ArrayList<>(NODES);
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < NODES; i++) {
				List<String> labels = new ArrayList<>();
				int labelCount = random.nextInt(4);
				while (labels.size() < labelCount) {
					String label = LABELS[random.nextInt(LABELS.length)];
					if (!labels.contains(label)) {
						labels.add(label);
					}
				}
				Properties properties = Properties.of().set("i", (long) i);
				if (random.nextInt(3) != 0) {
					properties.set("city", CITIES[random.nextInt(CITIES.length)]);
				}
				if (random.nextInt(2) == 0) {
					properties.set("age", (long) (18 + random.nextInt(50)));
				}
				nodes.add(store.createNode(labels, properties));
			}
			for (int i = 0; i < NODES * 3; i++) {
				long from = nodes.get(random.nextInt(nodes.size()));
				long to = nodes.get(random.nextInt(nodes.size()));
				String type = TYPES[random.nextInt(TYPES.length)];
				if (random.nextInt(2) == 0) {
					store.createRelationship(from, type, to, Properties.of().set("w", (long) i));
				} else {
					store.createEdge(from, type, to);
				}
			}
			txn.commit();
		}
		return new Graph(store, nodes);
	}

	// ---------------------------------------------------------------- CREATE

	@Test
	public void testCreatedNodesAreWhereTheApiSaysTheyAre() throws Exception {
		Graph graph = seed(1);
		int before = graph.store().nodesByLabel("Person").size();

		CypherResult result = Cypher.query(graph.store(),
			"MATCH (n:Person) WHERE n.city = 'Bonn' CREATE (:Person:Copy {of: n.i})");

		List<Long> copies = graph.store().nodesByLabel("Copy");
		assertEquals(result.stats().nodesCreated(), copies.size(), "the counter must equal what the API can find");
		assertEquals(before + copies.size(), graph.store().nodesByLabel("Person").size(),
			"a copy carries both labels, so the Person index has to have grown by the same number");
		assertTrue(copies.size() > 0, "seed 1 must produce some Bonn people, or this test asserts nothing");

		// Every copy's 'of' must be the 'i' of some Bonn person, and each exactly once.
		Set<Long> expected = new HashSet<>();
		for (long node : graph.store().nodesByLabel("Person")) {
			if ("Bonn".equals(graph.store().nodeProperty(node, "city"))
				&& !graph.store().nodeHasLabel(node, "Copy")) {
				expected.add((Long) graph.store().nodeProperty(node, "i"));
			}
		}
		Set<Long> actual = new HashSet<>();
		for (long copy : copies) {
			actual.add((Long) graph.store().nodeProperty(copy, "of"));
		}
		assertEquals(expected, actual);
		verify(graph);
	}

	@Test
	public void testCreatedRelationshipsAppearOnBothEndpoints() throws Exception {
		Graph graph = seed(2);
		long[] degreeBefore = new long[graph.nodes().size()];
		for (int i = 0; i < graph.nodes().size(); i++) {
			degreeBefore[i] = degree(graph.store(), graph.nodes().get(i));
		}

		CypherResult result = Cypher.query(graph.store(),
			"MATCH (a:Manager), (b:Product) CREATE (a)-[r:SELLS {n: 1}]->(b)");

		long created = result.stats().relationshipsCreated();
		assertTrue(created > 0, "seed 2 must have managers and products");

		// Counted from the adjacency, which is the structure the engine actually maintains, on both sides.
		long outgoing = 0;
		long incoming = 0;
		for (long node : graph.nodes()) {
			for (Edge edge : graph.store().neighbours(node, Direction.OUT, Set.of("SELLS"))) {
				outgoing++;
				assertTrue(edge.isMaterialised(), "CREATE must always make a relationship with an id");
				assertEquals(1L, graph.store().relationshipProperties(edge.relationshipId()).getLong("n"));
			}
			for (Edge ignored : graph.store().neighbours(node, Direction.IN, Set.of("SELLS"))) {
				incoming++;
			}
		}
		assertEquals(created, outgoing, "every created relationship must be reachable from its start node");
		assertEquals(created, incoming, "and from its end node");

		long degreeGrowth = 0;
		for (int i = 0; i < graph.nodes().size(); i++) {
			degreeGrowth += degree(graph.store(), graph.nodes().get(i)) - degreeBefore[i];
		}
		assertEquals(2 * created, degreeGrowth, "each relationship adds one out-degree and one in-degree");
		verify(graph);
	}

	// ---------------------------------------------------------------- SET and REMOVE

	@Test
	public void testSetAgreesWithTheApiAndTheIndex() throws Exception {
		Graph graph = seed(3);
		Set<Long> targeted = new HashSet<>(graph.store().nodesByLabel("Employee"));

		CypherResult result = Cypher.query(graph.store(),
			"MATCH (n:Employee) SET n.city = 'Cologne', n.checked = true REMOVE n.age");

		long changed = 0;
		for (long node : graph.nodes()) {
			Properties properties = graph.store().nodeProperties(node);
			if (targeted.contains(node)) {
				assertEquals("Cologne", properties.get("city"));
				assertEquals(Boolean.TRUE, properties.get("checked"));
				assertFalse(properties.has("age"), "REMOVE must actually remove the key");
				assertTrue(properties.has("i"), "and must leave every other property alone");
				changed++;
			} else {
				assertFalse("Cologne".equals(properties.get("city")), "no other node may have been touched");
			}
		}
		assertEquals(targeted.size(), changed);

		// The property index is keyed on a value hash, so a stale entry is invisible to the assertions above.
		Set<Long> byIndex = new HashSet<>(graph.store().nodesByProperty("city", "Cologne"));
		assertEquals(targeted, byIndex, "the index must find exactly the nodes that now hold the value");
		assertTrue(graph.store().nodesByProperty("city", "Bonn").stream().noneMatch(targeted::contains),
			"and must no longer find them under the value they used to hold");
		assertTrue(result.stats().propertiesSet() >= 2L * targeted.size());
		verify(graph);
	}

	@Test
	public void testLabelChangesAgreeWithTheLabelIndex() throws Exception {
		Graph graph = seed(4);
		Set<Long> managers = new HashSet<>(graph.store().nodesByLabel("Manager"));
		Set<Long> employeesBefore = new HashSet<>(graph.store().nodesByLabel("Employee"));

		Cypher.query(graph.store(), "MATCH (n:Manager) SET n:Senior REMOVE n:Employee");

		assertEquals(managers, new HashSet<>(graph.store().nodesByLabel("Senior")));
		for (long node : managers) {
			assertTrue(graph.store().nodeHasLabel(node, "Senior"));
			assertFalse(graph.store().nodeHasLabel(node, "Employee"));
			assertEquals(new HashSet<>(graph.store().nodeLabels(node)).size(), graph.store().nodeLabels(node).size(),
				"a label must never be stored twice");
		}
		Set<Long> employeesAfter = new HashSet<>(graph.store().nodesByLabel("Employee"));
		Set<Long> expected = new HashSet<>(employeesBefore);
		expected.removeAll(managers);
		assertEquals(expected, employeesAfter, "the index must lose exactly the managers and nothing else");
		verify(graph);
	}

	// ---------------------------------------------------------------- DELETE

	@Test
	public void testDetachDeleteRemovesEveryTraceOfItsNodes() throws Exception {
		Graph graph = seed(5);
		Set<Long> doomed = new HashSet<>(graph.store().nodesByLabel("Tag"));
		assertTrue(doomed.size() > 0, "seed 5 must have tags");

		CypherResult result = Cypher.query(graph.store(), "MATCH (n:Tag) DETACH DELETE n");

		assertEquals(doomed.size(), result.stats().nodesDeleted());
		assertEquals(List.of(), graph.store().nodesByLabel("Tag"), "the label index must be empty");
		for (long node : doomed) {
			assertFalse(graph.store().nodeExists(node));
		}
		// The survivors must have no adjacency left pointing at a deleted node — the failure this would otherwise be
		// is a dangling neighbour id that only shows up on the next traversal.
		long survivorEdges = 0;
		for (long node : graph.nodes()) {
			if (doomed.contains(node)) {
				continue;
			}
			for (Edge edge : graph.store().neighbours(node, Direction.BOTH)) {
				assertFalse(doomed.contains(edge.neighbourId()),
					"node " + node + " still points at deleted node " + edge.neighbourId());
				survivorEdges++;
			}
		}
		assertTrue(survivorEdges > 0, "the graph must not have been emptied, or this proves little");
		verify(graph);
	}

	@Test
	public void testDeletingRelationshipsLeavesTheNodesAndTheirOtherEdges() throws Exception {
		Graph graph = seed(6);
		long knowsBefore = countByType(graph, "KNOWS");
		long ownsBefore = countByType(graph, "OWNS");
		assertTrue(knowsBefore > 0 && ownsBefore > 0, "seed 6 must have both types");
		int nodesBefore = liveNodes(graph);

		CypherResult result = Cypher.query(graph.store(), "MATCH ()-[r:KNOWS]->() DELETE r");

		assertEquals(0, countByType(graph, "KNOWS"), "every KNOWS edge must be gone, materialised or not");
		assertEquals(knowsBefore, result.stats().relationshipsDeleted());
		assertEquals(ownsBefore, countByType(graph, "OWNS"), "and no other type may have been touched");
		assertEquals(nodesBefore, liveNodes(graph), "the endpoints stay");
		verify(graph);
	}

	/**
	 * A self-loop is one adjacency entry but two degrees, and a parallel edge is two of everything. Both are shapes the
	 * seeded graph produces on its own and both are easy to double-count, so this pins the counter against the API.
	 */
	@Test
	public void testTheDeletedCounterMatchesWhatDisappeared() throws Exception {
		Graph graph = seed(7);
		long edgesBefore = totalEdges(graph);

		CypherResult result = Cypher.query(graph.store(), "MATCH (n:Product) DETACH DELETE n");

		assertEquals(edgesBefore - totalEdges(graph), result.stats().relationshipsDeleted(),
			"the reported count must equal the number that actually went, self-loops and parallels included");
		verify(graph);
	}

	// ---------------------------------------------------------------- plumbing

	private static void verify(Graph graph) throws Exception {
		// The oracle that no hand-written assertion replaces: bidirectional edges, degree counters, adjacency chains,
		// no dangling references, no orphan blocks, every index entry resolving.
		graph.store().verify(VerifyLevel.FULL);
		graph.store().close();
	}

	private static int degree(GraphStore store, long node) {
		return store.degreeOut(node) + store.degreeIn(node);
	}

	private static int liveNodes(Graph graph) {
		int[] count = { 0 };
		graph.store().forEachNode(id -> count[0]++);
		return count[0];
	}

	/** Counted from the OUT side only, so each edge is counted once however many endpoints it touches. */
	private static long countByType(Graph graph, String type) {
		long count = 0;
		for (long node : graph.nodes()) {
			if (graph.store().nodeExists(node)) {
				count += graph.store().neighbours(node, Direction.OUT, Set.of(type)).size();
			}
		}
		return count;
	}

	private static long totalEdges(Graph graph) {
		long count = 0;
		for (long node : graph.nodes()) {
			if (graph.store().nodeExists(node)) {
				count += graph.store().neighbours(node, Direction.OUT).size();
			}
		}
		return count;
	}
}
