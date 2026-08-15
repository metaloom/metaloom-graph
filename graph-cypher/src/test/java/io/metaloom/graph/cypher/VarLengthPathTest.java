package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.value.CypherPath;

/**
 * Variable-length patterns, and how they differ from {@code GraphStore.traverse}.
 *
 * <p>
 * The engine already has a breadth-first walk. It would have been the obvious thing to reuse, and it would have been
 * wrong: {@code traverse} deduplicates by <b>node</b> and returns a flat list of edges, answering "what can I reach".
 * Cypher's {@code -[*1..3]->} deduplicates by <b>relationship</b> within each path and yields one row per distinct
 * route, answering "how can I get there". On a graph with more than one route between the same pair, those are
 * different answers — and this test builds exactly such a graph so the difference is visible rather than argued.
 * </p>
 */
@Tag("unit")
public class VarLengthPathTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/**
	 * A diamond: two distinct two-hop routes from A to D, plus a direct edge.
	 *
	 * <pre>
	 *      B
	 *    /   \
	 *  A       D
	 *    \   /
	 *      C
	 * </pre>
	 */
	private GraphStore diamond() throws Exception {
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			long a = store.createNode("N", Properties.of().set("name", "A"));
			long b = store.createNode("N", Properties.of().set("name", "B"));
			long c = store.createNode("N", Properties.of().set("name", "C"));
			long d = store.createNode("N", Properties.of().set("name", "D"));
			store.createEdge(a, "R", b);
			store.createEdge(a, "R", c);
			store.createEdge(b, "R", d);
			store.createEdge(c, "R", d);
			txn.commit();
		}
		return store;
	}

	@Test
	public void testOneRowPerRouteNotPerReachableNode() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store,
				"MATCH (a:N {name:'A'})-[:R*2]->(d) RETURN d.name ORDER BY d.name");
			assertEquals(List.of("D", "D"), result.column("d.name"),
				"two routes to D means two rows, even though D is one node");
		}
	}

	/** The contrast, spelled out: the same graph through the storage API's walk gives a different shape. */
	@Test
	public void testTraverseAndCypherAnswerDifferentQuestions() throws Exception {
		try (GraphStore store = diamond()) {
			long a = store.nodesByProperty("name", "A").get(0);

			List<Edge> reachable = store.traverse(a, Direction.OUT, Set.of("R"), 2);
			List<String> traverseNames = new ArrayList<>();
			for (Edge edge : reachable) {
				traverseNames.add((String) store.nodeProperty(edge.neighbourId(), "name"));
			}
			traverseNames.sort(null);
			assertEquals(List.of("B", "C", "D", "D"), traverseNames,
				"traverse visits each node once but reports the edge that reached it");

			List<Object> cypherNames = Cypher.query(store,
				"MATCH (a:N {name:'A'})-[:R*1..2]->(x) RETURN x.name ORDER BY x.name").column("x.name");
			assertEquals(List.of("B", "C", "D", "D"), cypherNames);

			// Where they genuinely part company: node uniqueness caps traverse at one visit per node, so a longer
			// bound cannot produce more paths, whereas Cypher keeps finding routes.
			assertEquals(4, store.traverse(a, Direction.OUT, Set.of("R"), 5).size());
			assertEquals(4, Cypher.query(store, "MATCH (a:N {name:'A'})-[:R*1..5]->(x) RETURN x").size());
		}
	}

	/**
	 * Relationship uniqueness is what makes an unbounded pattern terminate on a cycle.
	 *
	 * <p>
	 * Without it, {@code -[*]-} on a three-node ring would loop forever. With it, each path may use each edge once, so
	 * the enumeration is finite even though the graph has no leaves.
	 * </p>
	 */
	@Test
	public void testACycleTerminates() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				long a = store.createNode("N", Properties.of().set("name", "A"));
				long b = store.createNode("N", Properties.of().set("name", "B"));
				long c = store.createNode("N", Properties.of().set("name", "C"));
				store.createEdge(a, "R", b);
				store.createEdge(b, "R", c);
				store.createEdge(c, "R", a);
				txn.commit();
			}

			CypherResult result = Cypher.query(store, "MATCH (a:N {name:'A'})-[:R*]->(x) RETURN x.name ORDER BY x.name");
			// A to B, A to B to C, A to B to C back to A. Three edges, so at most three hops.
			assertEquals(List.of("A", "B", "C"), result.column("x.name"));
		}
	}

	@Test
	public void testTheSameEdgeIsNeverUsedTwiceInOnePath() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				long a = store.createNode("N", Properties.of().set("name", "A"));
				long b = store.createNode("N", Properties.of().set("name", "B"));
				store.createEdge(a, "R", b);
				txn.commit();
			}
			// Undirected, so without the rule the walk would bounce A-B-A-B forever.
			CypherResult result = Cypher.query(store, "MATCH (a:N {name:'A'})-[:R*]-(x) RETURN x.name");
			assertEquals(List.of("B"), result.column("x.name"));
		}
	}

	@Test
	public void testTwoParallelEdgesAreTwoPaths() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				long a = store.createNode("N", Properties.of().set("name", "A"));
				long b = store.createNode("N", Properties.of().set("name", "B"));
				// Two materialised relationships between the same pair are two distinct edges.
				store.createRelationship(a, "R", b, Properties.of().set("w", 1L));
				store.createRelationship(a, "R", b, Properties.of().set("w", 2L));
				txn.commit();
			}
			assertEquals(2, Cypher.query(store, "MATCH (a:N {name:'A'})-[:R*1]->(x) RETURN x").size(),
				"distinct relationships between one pair are distinct paths");
		}
	}

	@Test
	public void testABoundPathCarriesEveryNodeAndRelationship() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store,
				"MATCH p = (a:N {name:'A'})-[:R*2]->(d:N {name:'D'}) RETURN p");
			assertEquals(2, result.size());
			for (CypherRow row : result) {
				CypherPath path = row.path("p");
				assertEquals(2, path.length());
				assertEquals(3, path.nodes().size(), "a two-hop path has three nodes");
				assertEquals("A", path.start().property("name"));
				assertEquals("D", path.end().property("name"));
				String middle = (String) path.nodes().get(1).property("name");
				assertTrue(middle.equals("B") || middle.equals("C"), "the middle node distinguishes the routes");
			}
		}
	}

	@Test
	public void testBoundsAreRespected() throws Exception {
		try (GraphStore store = diamond()) {
			assertEquals(2, Cypher.query(store, "MATCH (a:N {name:'A'})-[:R*1..1]->(x) RETURN x").size());
			assertEquals(2, Cypher.query(store, "MATCH (a:N {name:'A'})-[:R*2..2]->(x) RETURN x").size());
			assertEquals(0, Cypher.query(store, "MATCH (a:N {name:'A'})-[:R*3..3]->(x) RETURN x").size(),
				"nothing is three hops from A in a diamond");
			assertEquals(4, Cypher.query(store, "MATCH (a:N {name:'A'})-[:R*1..2]->(x) RETURN x").size());
		}
	}

	@Test
	public void testABoundVariableOverAVariableLengthPatternIsAList() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store,
				"MATCH (a:N {name:'A'})-[rs:R*2]->(d) RETURN size(rs) AS hops");
			assertEquals(List.of(2L, 2L), result.column("hops"),
				"a variable-length relationship variable binds the list of edges crossed");
		}
	}
}
