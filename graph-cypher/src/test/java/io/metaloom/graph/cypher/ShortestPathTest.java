package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;
import io.metaloom.graph.cypher.value.CypherPath;

/**
 * {@code shortestPath} and {@code allShortestPaths}.
 *
 * <p>
 * The two constructs are the only place in this engine where a pattern is answered by a <em>search</em> rather than by
 * enumeration, which is what most of these tests are really about: a breadth-first walk that fixes each node's distance
 * on first sight, against the depth-first enumeration {@link VarLengthPathTest} covers. Where the two can be compared
 * they are — {@link #testTheShortestPathIsTheShortestOfEveryPath} runs both over the same pairs and asserts the search
 * agrees with the enumeration it exists to avoid, which is the one assertion here that would catch a subtly wrong
 * frontier rather than an obviously wrong one.
 * </p>
 *
 * @see <a href="../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@Tag("unit")
public class ShortestPathTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/**
	 * A diamond with a tail, a shortcut of a different type, and an island.
	 *
	 * <pre>
	 *      B
	 *    /   \
	 *  A       D --R--> E
	 *    \   /
	 *      C
	 *
	 *  A --S--> E      one hop, but only if the pattern lets type S through
	 *  F               reachable from nothing
	 * </pre>
	 *
	 * <p>
	 * Every shape a shortest-path search has to get right is in here: two routes of equal minimal length, a longer
	 * route that must lose to them, a shorter route that only exists under a different type filter, and a node no
	 * search can reach.
	 * </p>
	 */
	private GraphStore diamond() throws Exception {
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			long a = store.createNode("N", Properties.of().set("name", "A"));
			long b = store.createNode("N", Properties.of().set("name", "B"));
			long c = store.createNode("N", Properties.of().set("name", "C"));
			long d = store.createNode("N", Properties.of().set("name", "D"));
			long e = store.createNode("N", Properties.of().set("name", "E"));
			store.createNode("N", Properties.of().set("name", "F"));
			store.createEdge(a, "R", b);
			store.createEdge(a, "R", c);
			store.createEdge(b, "R", d);
			store.createEdge(c, "R", d);
			store.createEdge(d, "R", e);
			store.createEdge(a, "S", e);
			txn.commit();
		}
		return store;
	}

	// ---------------------------------------------------------------- the search itself

	@Test
	public void testOneRowPerPairHoweverManyRoutesAreEquallyShort() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..10]-(b:N {name:'D'})) RETURN length(p) AS len");
			assertEquals(List.of(2L), result.column("len"),
				"two routes of length two, and shortestPath reports the pair once");
		}
	}

	@Test
	public void testAllShortestPathsReportsEveryRouteOfMinimalLength() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store, "MATCH p = allShortestPaths("
				+ "(a:N {name:'A'})-[:R*..10]-(b:N {name:'D'})) RETURN p");
			assertEquals(2, result.size());

			List<String> middles = new ArrayList<>();
			for (CypherRow row : result) {
				CypherPath path = row.path("p");
				assertEquals(2, path.length());
				assertEquals("A", path.start().property("name"));
				assertEquals("D", path.end().property("name"));
				middles.add((String) path.nodes().get(1).property("name"));
			}
			middles.sort(null);
			assertEquals(List.of("B", "C"), middles, "the two routes differ only in the node between");
		}
	}

	@Test
	public void testALongerRouteLosesToAShorterOne() throws Exception {
		try (GraphStore store = diamond()) {
			// Through R alone, E is three hops away. The S edge reaches it in one, so dropping the type filter has to
			// change the answer rather than merely add a row.
			assertEquals(List.of(3L), Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..10]-(b:N {name:'E'})) RETURN length(p) AS len").column("len"));
			assertEquals(List.of(1L), Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[*..10]-(b:N {name:'E'})) RETURN length(p) AS len").column("len"));

			// And allShortestPaths must not fall back to reporting the long ones alongside the short one.
			assertEquals(1, Cypher.query(store, "MATCH p = allShortestPaths("
				+ "(a:N {name:'A'})-[*..10]-(b:N {name:'E'})) RETURN p").size());
			assertEquals(2, Cypher.query(store, "MATCH p = allShortestPaths("
				+ "(a:N {name:'A'})-[:R*..10]-(b:N {name:'E'})) RETURN p").size());
		}
	}

	@Test
	public void testAnUnreachableEndProducesNoRows() throws Exception {
		try (GraphStore store = diamond()) {
			assertEquals(0, Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..10]-(b:N {name:'F'})) RETURN p").size());
			assertEquals(0, Cypher.query(store, "MATCH p = allShortestPaths("
				+ "(a:N {name:'A'})-[:R*..10]-(b:N {name:'F'})) RETURN p").size());
		}
	}

	@Test
	public void testTheUpperBoundStillApplies() throws Exception {
		try (GraphStore store = diamond()) {
			assertEquals(0, Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..1]-(b:N {name:'D'})) RETURN p").size(),
				"D is two hops away and the search was allowed one");
			assertEquals(1, Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..2]-(b:N {name:'D'})) RETURN p").size());
		}
	}

	/**
	 * A minimum of zero, which is the only minimum other than one that openCypher allows.
	 *
	 * <p>
	 * It is what makes a node its own shortest path: {@code *0..} matches a path of no relationships at all, so a start
	 * that satisfies the end pattern answers with itself rather than with the shortest way back to itself.
	 * </p>
	 */
	@Test
	public void testAZeroLengthPathIsTheShortestPathFromANodeToItself() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*0..5]-(b:N {name:'A'})) RETURN length(p) AS len, size(nodes(p)) AS nodes");
			assertEquals(List.of(0L), result.column("len"));
			assertEquals(List.of(1L), result.column("nodes"), "a path of no hops is still one node");

			// Without the zero, there is no route from A back to A at all — the diamond's edges all lead away.
			assertEquals(0, Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*1..5]-(b:N {name:'A'})) RETURN p").size());
		}
	}

	@Test
	public void testDirectionIsHonoured() throws Exception {
		try (GraphStore store = diamond()) {
			assertEquals(1, Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..10]->(b:N {name:'D'})) RETURN p").size());
			assertEquals(0, Cypher.query(store, "MATCH p = shortestPath("
				+ "(d:N {name:'D'})-[:R*..10]->(a:N {name:'A'})) RETURN p").size(),
				"every edge in the diamond points away from A");
			assertEquals(1, Cypher.query(store, "MATCH p = shortestPath("
				+ "(d:N {name:'D'})<-[:R*..10]-(a:N {name:'A'})) RETURN p").size());
		}
	}

	/**
	 * Several nodes satisfying the end pattern, each at its own distance.
	 *
	 * <p>
	 * The rule is one row per <em>pair</em>, not one row for the nearest node — so this cannot be implemented by
	 * stopping the walk at the first node that matches, which is the obvious shortcut and the wrong answer.
	 * </p>
	 */
	@Test
	public void testEveryEndTheSearchReachesGetsItsOwnShortestPath() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..10]-(b:N)) RETURN b.name AS name, length(p) AS len "
				+ "ORDER BY len, name");
			assertEquals(List.of("B", "C", "D", "E"), result.column("name"),
				"A itself is excluded: the range starts at one hop");
			assertEquals(List.of(1L, 1L, 2L, 3L), result.column("len"));
		}
	}

	// ---------------------------------------------------------------- what a match binds

	@Test
	public void testThePathAlternatesNodesAndRelationships() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..10]-(b:N {name:'E'})) RETURN p");
			assertEquals(1, result.size());

			CypherPath path = result.row(0).path("p");
			assertEquals(3, path.length());
			assertEquals(4, path.nodes().size(), "one more node than relationships, always");
			assertEquals("A", path.start().property("name"));
			assertEquals("E", path.end().property("name"));
			// Each relationship joins the node before it to the node after it, whichever way its arrow points.
			for (int i = 0; i < path.relationships().size(); i++) {
				long from = path.nodes().get(i).id();
				long to = path.nodes().get(i + 1).id();
				long start = path.relationships().get(i).startId();
				long end = path.relationships().get(i).endId();
				assertTrue((start == from && end == to) || (start == to && end == from),
					"relationship " + i + " does not join the nodes around it");
			}
		}
	}

	@Test
	public void testTheRelationshipVariableBindsTheListOfEdgesCrossed() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store, "MATCH shortestPath("
				+ "(a:N {name:'A'})-[rs:R*..10]-(b:N {name:'E'})) RETURN size(rs) AS hops, b.name AS name");
			assertEquals(List.of(3L), result.column("hops"));
			assertEquals(List.of("E"), result.column("name"),
				"the endpoints bind even with no path variable to hold the route");
		}
	}

	@Test
	public void testEndpointsBoundByAnEarlierClauseAreJoinedRatherThanSearchedFor() throws Exception {
		try (GraphStore store = diamond()) {
			CypherResult result = Cypher.query(store,
				"MATCH (a:N {name:'A'}) MATCH (b:N {name:'D'}) "
					+ "MATCH p = shortestPath((a)-[:R*..10]-(b)) RETURN length(p) AS len");
			assertEquals(List.of(2L), result.column("len"));

			// The same query for a pair with no route between them, which must produce nothing rather than the
			// shortest path to some other node.
			assertEquals(0, Cypher.query(store,
				"MATCH (a:N {name:'A'}) MATCH (f:N {name:'F'}) "
					+ "MATCH p = shortestPath((a)-[:R*..10]-(f)) RETURN p").size());
		}
	}

	@Test
	public void testTwoParallelEdgesAreTwoShortestPaths() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				long a = store.createNode("N", Properties.of().set("name", "A"));
				long b = store.createNode("N", Properties.of().set("name", "B"));
				// Distinct relationships between one pair are distinct one-hop routes, so both are shortest.
				store.createRelationship(a, "R", b, Properties.of().set("w", 1L));
				store.createRelationship(a, "R", b, Properties.of().set("w", 2L));
				txn.commit();
			}
			assertEquals(1, Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..3]-(b:N {name:'B'})) RETURN p").size());

			CypherResult all = Cypher.query(store, "MATCH p = allShortestPaths("
				+ "(a:N {name:'A'})-[rs:R*..3]-(b:N {name:'B'})) RETURN rs[0].w AS w ORDER BY w");
			assertEquals(List.of(1L, 2L), all.column("w"));
		}
	}

	/** An inline map on the relationship restricts which edges the search may cross, not just which paths survive. */
	@Test
	public void testARelationshipPredicateRestrictsTheSearch() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				long a = store.createNode("N", Properties.of().set("name", "A"));
				long b = store.createNode("N", Properties.of().set("name", "B"));
				long c = store.createNode("N", Properties.of().set("name", "C"));
				// The direct edge is closed, so the only route open is the long way round.
				store.createRelationship(a, "R", c, Properties.of().set("open", false));
				store.createRelationship(a, "R", b, Properties.of().set("open", true));
				store.createRelationship(b, "R", c, Properties.of().set("open", true));
				txn.commit();
			}
			assertEquals(List.of(1L), Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R*..5]-(c:N {name:'C'})) RETURN length(p) AS len").column("len"));
			assertEquals(List.of(2L), Cypher.query(store, "MATCH p = shortestPath("
				+ "(a:N {name:'A'})-[:R* {open: true}]-(c:N {name:'C'})) RETURN length(p) AS len").column("len"),
				"a closed edge must not shorten the route");
		}
	}

	// ---------------------------------------------------------------- against the enumeration

	/**
	 * The search against the walk it replaces, over every pair in the graph.
	 *
	 * <p>
	 * {@code MATCH p = (a)-[*]-(b)} enumerates every route depth-first — a different algorithm reaching the same
	 * question — so the minimum of its lengths is what {@code shortestPath} must report, and the number of its routes
	 * at that length is what {@code allShortestPaths} must return. This is the assertion that would survive a rewrite
	 * of the search, and the one that catches a frontier which is merely nearly right.
	 * </p>
	 */
	@Test
	public void testTheShortestPathIsTheShortestOfEveryPath() throws Exception {
		try (GraphStore store = diamond()) {
			List<String> names = List.of("A", "B", "C", "D", "E", "F");
			int pairsWithARoute = 0;

			for (String from : names) {
				for (String to : names) {
					if (from.equals(to)) {
						continue;
					}
					List<Object> lengths = Cypher.query(store, "MATCH p = (a:N {name:'" + from + "'})"
						+ "-[:R*..6]-(b:N {name:'" + to + "'}) RETURN length(p) AS len ORDER BY len")
						.column("len");

					CypherResult shortest = Cypher.query(store, "MATCH p = shortestPath((a:N {name:'" + from + "'})"
						+ "-[:R*..6]-(b:N {name:'" + to + "'})) RETURN length(p) AS len");
					String pair = from + " to " + to;

					if (lengths.isEmpty()) {
						assertEquals(0, shortest.size(), pair + ": no route, so no shortest path");
						continue;
					}
					pairsWithARoute++;
					long minimum = (Long) lengths.get(0);
					assertEquals(List.of(minimum), shortest.column("len"), pair + ": shortest length");

					long minimal = lengths.stream().filter(length -> (Long) length == minimum).count();
					assertEquals(minimal, Cypher.query(store, "MATCH p = allShortestPaths((a:N {name:'" + from + "'})"
						+ "-[:R*..6]-(b:N {name:'" + to + "'})) RETURN p").size(),
						pair + ": routes of minimal length");
				}
			}
			assertTrue(pairsWithARoute >= 10, "only " + pairsWithARoute + " pairs had a route at all; this test "
				+ "would pass vacuously on a graph that was not connected");
		}
	}

	// ---------------------------------------------------------------- what is refused

	@Test
	public void testAPatternWithMoreThanOneRelationshipIsRefused() throws Exception {
		try (GraphStore store = diamond()) {
			CypherSyntaxException thrown = assertThrows(CypherSyntaxException.class,
				() -> Cypher.query(store, "MATCH p = shortestPath((a)-[:R*..5]-(b)-[:R]-(c)) RETURN p"));
			assertTrue(thrown.getMessage().contains("exactly one relationship"), thrown.getMessage());
			assertTrue(thrown.getMessage().contains("shortestPath"), "the message names the construct");
		}
	}

	@Test
	public void testAMinimumAboveOneIsRefused() throws Exception {
		try (GraphStore store = diamond()) {
			CypherSyntaxException thrown = assertThrows(CypherSyntaxException.class,
				() -> Cypher.query(store, "MATCH p = shortestPath((a)-[:R*2..5]-(b)) RETURN p"));
			assertTrue(thrown.getMessage().contains("minimum length of 0 or 1"), thrown.getMessage());

			// Zero and one are both allowed, and the difference between them is tested above.
			Cypher.query(store, "MATCH p = shortestPath((a:N {name:'A'})-[:R*0..5]-(b:N {name:'D'})) RETURN p");
			Cypher.query(store, "MATCH p = shortestPath((a:N {name:'A'})-[:R*1..5]-(b:N {name:'D'})) RETURN p");
		}
	}

	@Test
	public void testItIsRefusedInCreate() throws Exception {
		try (GraphStore store = diamond()) {
			CypherSyntaxException thrown = assertThrows(CypherSyntaxException.class,
				() -> Cypher.query(store, "CREATE p = shortestPath((a)-[:R*..5]-(b))"));
			assertTrue(thrown.getMessage().contains("CREATE cannot take a shortest-path pattern"),
				thrown.getMessage());
		}
	}

	/**
	 * In an expression rather than in a pattern.
	 *
	 * <p>
	 * It parses as a function call there and its argument is a pattern, so without a message of its own the error is
	 * about the arrow inside the parentheses — a syntax error pointing at valid syntax, in the wrong place.
	 * </p>
	 */
	@Test
	public void testItIsRefusedInAnExpression() throws Exception {
		try (GraphStore store = diamond()) {
			CypherSyntaxException thrown = assertThrows(CypherSyntaxException.class,
				() -> Cypher.query(store, "MATCH (a:N) RETURN shortestPath((a)-[:R*..5]-(a))"));
			assertTrue(thrown.getMessage().contains("belongs in a MATCH pattern"), thrown.getMessage());
		}
	}

	/** The names are ordinary identifiers, so a graph is still free to use them for its own properties. */
	@Test
	public void testTheNameIsNotReservedOutsideAPattern() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("N", Properties.of().set("shortestPath", "not a search"));
				txn.commit();
			}
			assertEquals(List.of("not a search"),
				Cypher.query(store, "MATCH (n:N) RETURN n.shortestPath AS x").column("x"));
		}
	}
}
