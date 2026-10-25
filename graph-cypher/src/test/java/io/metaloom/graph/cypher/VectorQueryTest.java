package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;

/**
 * {@code CALL vector.query(...) YIELD node, score} — the query surface for the vector index.
 *
 * <p>
 * The test that matters most is {@link #testTheGraphPredicateIsAppliedToTheResults}. It is the reason this index lives
 * inside the engine rather than beside it: an external vector index knows nothing about the graph, so it has to
 * over-fetch and let the caller filter, with no way to know how far to over-fetch. Here the {@code MATCH} after the
 * call sees the node already bound and verifies it, which costs no planner change at all.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
@Tag("unit")
public class VectorQueryTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;
	private long east;
	private long north;

	@BeforeEach
	public void seed() throws Exception {
		store = stores.open(GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(3)
			.build());
		try (Transaction txn = store.begin()) {
			east = store.createNode(List.of("Doc"),
				Properties.of().set("name", "east").set("embedding", new float[] { 1f, 0f, 0f }));
			long eastish = store.createNode(List.of("Doc"),
				Properties.of().set("name", "east-ish").set("embedding", new float[] { 0.9f, 0.1f, 0f }));
			north = store.createNode(List.of("Doc"),
				Properties.of().set("name", "north").set("embedding", new float[] { 0f, 1f, 0f }));
			long tag = store.createNode(List.of("Tag"), Properties.of().set("name", "important"));
			// Only north carries the tag, and it is the *furthest* — so a query that filters after searching has to
			// look past the nearer ones to find it.
			store.createEdge(north, "TAGGED", tag);
			store.createEdge(eastish, "OTHER", tag);
			txn.commit();
		}
		store.createVectorIndex(VectorIndexDefinition.of("Doc", "embedding", 3, VectorMetric.COSINE));
	}

	private CypherResult run(String query) {
		return Cypher.query(store, query, Map.of("q", new float[] { 1f, 0f, 0f }));
	}

	@Test
	public void testCallYieldsNodesAndScores() {
		CypherResult result = run("CALL vector.query('Doc', 'embedding', $q, 2) YIELD node, score "
			+ "RETURN node.name AS name, score");
		assertEquals(List.of("east", "east-ish"), result.column("name"));
		assertEquals(1.0, (Double) result.column("score").get(0), 1e-6);
	}

	/** Yielding only the node is legal; the score is simply not bound. */
	@Test
	public void testYieldingOnlyTheNode() {
		CypherResult result = run("CALL vector.query('Doc', 'embedding', $q, 1) YIELD node RETURN node.name AS name");
		assertEquals(List.of("east"), result.column("name"));
	}

	/**
	 * The combined query, and the whole argument for building this here: nearest neighbours <em>that also satisfy a
	 * graph predicate</em>. The node is already bound when the MATCH runs, so the pattern is verified rather than
	 * scanned.
	 */
	@Test
	public void testTheGraphPredicateIsAppliedToTheResults() {
		CypherResult result = run("CALL vector.query('Doc', 'embedding', $q, 3) YIELD node, score "
			+ "MATCH (node)-[:TAGGED]->(t:Tag) RETURN node.name AS name, t.name AS tag");
		assertEquals(List.of("north"), result.column("name"),
			"the nearest two have no TAGGED edge, so only the third survives the predicate");
		assertEquals(List.of("important"), result.column("tag"));
	}

	@Test
	public void testTheResultsCanBeFilteredAndOrderedLikeAnyOtherRows() {
		CypherResult result = run("CALL vector.query('Doc', 'embedding', $q, 3) YIELD node, score "
			+ "WITH node, score WHERE score > 0.5 RETURN node.name AS name ORDER BY name");
		assertEquals(List.of("east", "east-ish"), result.column("name"));
	}

	@Test
	public void testCallIsNoLongerRefusedButAnUnknownProcedureIs() {
		// The parser used to refuse every CALL by name. Now it parses, and the *procedure* is what is unknown.
		CypherTypeException unknown = assertThrows(CypherTypeException.class,
			() -> run("CALL db.labels() YIELD label RETURN label"));
		assertTrue(unknown.getMessage().contains("vector.query"), "and it names what does exist: "
			+ unknown.getMessage());
	}

	/**
	 * A call whose results are bound to nothing has nowhere to put them, and this build has no implicit result table.
	 * Accepting it would make a query that looks like it does something do nothing.
	 */
	@Test
	public void testYieldIsRequired() {
		CypherSyntaxException missing = assertThrows(CypherSyntaxException.class,
			() -> run("CALL vector.query('Doc', 'embedding', $q, 2) RETURN 1"));
		assertTrue(missing.getMessage().contains("YIELD"), missing.getMessage());
	}

	@Test
	public void testWrongArity() {
		assertTrue(assertThrows(CypherTypeException.class,
			() -> run("CALL vector.query('Doc', 'embedding') YIELD node RETURN node")).getMessage()
				.contains("4 arguments"));
	}

	/** An undeclared index fails by name rather than returning nothing, all the way up through the query layer. */
	@Test
	public void testQueryingAnUndeclaredIndexFails() {
		// A type error, not the engine's IllegalStateException: the server reports the latter as its own failure.
		assertTrue(assertThrows(CypherTypeException.class,
			() -> run("CALL vector.query('Doc', 'missing', $q, 2) YIELD node RETURN node")).getMessage()
				.contains("No vector index"));
	}

	/** An empty vector, or one of the wrong length, is a mistake in the query and refused as one. */
	@Test
	public void testAQueryVectorTheIndexCannotTakeIsATypeError() {
		assertTrue(assertThrows(CypherTypeException.class,
			() -> run("CALL vector.query('Doc', 'embedding', [], 2) YIELD node RETURN node")).getMessage()
				.contains("at least one element"));
		assertTrue(assertThrows(CypherTypeException.class,
			() -> run("CALL vector.query('Doc', 'embedding', [1.0], 2) YIELD node RETURN node")).getMessage()
				.contains("dimensions"));
	}

	@Test
	public void testTheCallCanBeCombinedWithTheDistanceFunctions() {
		CypherResult result = run("CALL vector.query('Doc', 'embedding', $q, 3) YIELD node "
			+ "RETURN node.name AS name, vector.cosine(node.embedding, $q) AS recomputed ORDER BY recomputed DESC");
		assertEquals(List.of("east", "east-ish", "north"), result.column("name"));
		assertEquals(1.0, (Double) result.column("recomputed").get(0), 1e-6,
			"the index's score and a recomputed one agree");
	}

	@Test
	public void testTheNodesAreRealNodes() {
		CypherResult result = run("CALL vector.query('Doc', 'embedding', $q, 1) YIELD node RETURN id(node) AS id");
		assertEquals(List.of(east), result.column("id"));
		assertEquals(1, result.column("id").size());
		assertTrue(north != east);
	}
}
