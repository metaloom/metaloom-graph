package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.exec.CypherTypeException;

/**
 * The three vector distance functions, from the query language.
 *
 * <p>
 * They matter more than their size suggests: they are the exact answer an approximate index is measured against, and
 * they are the only way a query can say anything at all about a vector today. The interesting cases are not the
 * arithmetic — that is {@code VectorsTest}'s job — but the two conversions on the way in, since a vector reaches a
 * query either as a stored {@code float[]} that {@code GraphAccess.normalise} has already turned into a list of
 * doubles, or as a parameter that has not been converted at all.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
@Tag("unit")
public class VectorFunctionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;

	@BeforeEach
	public void seed() throws Exception {
		store = stores.open();
		try (Transaction txn = store.begin()) {
			store.createNode(List.of("Doc"),
				Properties.of().set("name", "east").set("embedding", new float[] { 1f, 0f, 0f }));
			store.createNode(List.of("Doc"),
				Properties.of().set("name", "north").set("embedding", new float[] { 0f, 1f, 0f }));
			store.createNode(List.of("Doc"),
				Properties.of().set("name", "east-ish").set("embedding", new float[] { 0.9f, 0.1f, 0f }));
			// No embedding at all, so every function must yield null for it rather than failing the query.
			store.createNode(List.of("Doc"), Properties.of().set("name", "unembedded"));
			txn.commit();
		}
	}

	private CypherResult run(String query) {
		return Cypher.query(store, query);
	}

	private CypherResult run(String query, Map<String, Object> parameters) {
		return Cypher.query(store, query, parameters);
	}

	@Test
	public void testTheThreeFunctionsOverLiteralLists() {
		assertEquals(1.0, (Double) run("RETURN vector.cosine([1.0, 0.0], [2.0, 0.0]) AS s").column("s").get(0), 1e-9,
			"cosine ignores magnitude");
		assertEquals(0.0, (Double) run("RETURN vector.cosine([1.0, 0.0], [0.0, 1.0]) AS s").column("s").get(0), 1e-9);
		assertEquals(-1.0, (Double) run("RETURN vector.cosine([1.0, 0.0], [-3.0, 0.0]) AS s").column("s").get(0), 1e-9);

		assertEquals(11.0, (Double) run("RETURN vector.dot([1.0, 2.0], [3.0, 4.0]) AS s").column("s").get(0), 1e-9);
		assertEquals(5.0, (Double) run("RETURN vector.l2([0.0, 0.0], [3.0, 4.0]) AS s").column("s").get(0), 1e-9);
	}

	/** A list of integers is what a literal like {@code [1, 2, 3]} actually produces. */
	@Test
	public void testAnIntegerListIsAVector() {
		assertEquals(11.0, (Double) run("RETURN vector.dot([1, 2], [3, 4]) AS s").column("s").get(0), 1e-9);
	}

	/**
	 * The round trip that matters: a stored {@code float[]} against a {@code float[]} parameter. The stored one has
	 * been through {@code GraphAccess.normalise} and is a list of doubles by the time the function sees it; the
	 * parameter has not been converted at all. Both have to work, and they have to agree.
	 */
	@Test
	public void testAStoredVectorAgainstAParameter() {
		CypherResult result = run(
			"MATCH (d:Doc) WHERE d.embedding IS NOT NULL "
				+ "RETURN d.name AS name, vector.cosine(d.embedding, $q) AS score ORDER BY score DESC",
			Map.of("q", new float[] { 1f, 0f, 0f }));
		assertEquals(List.of("east", "east-ish", "north"), result.column("name"),
			"nearest first, and the node with no embedding is excluded by the WHERE rather than by the function");
		assertEquals(1.0, (Double) result.column("score").get(0), 1e-6);

		// The same query with the parameter as a plain list must give the same answer.
		CypherResult asList = run(
			"MATCH (d:Doc) WHERE d.embedding IS NOT NULL "
				+ "RETURN d.name AS name, vector.cosine(d.embedding, $q) AS score ORDER BY score DESC",
			Map.of("q", List.of(1.0, 0.0, 0.0)));
		assertEquals(result.column("name"), asList.column("name"));
		assertEquals(result.column("score"), asList.column("score"));
	}

	/**
	 * The spelling worth documenting: projecting the score once in a WITH and ordering on the alias evaluates the
	 * distance once per row. Ordering on the call itself re-evaluates it on every comparison, because ORDER BY is
	 * sort-then-truncate and {@code Comparator.comparing} recomputes its key.
	 */
	@Test
	public void testTheTopKSpelling() {
		CypherResult result = run(
			"MATCH (d:Doc) WHERE d.embedding IS NOT NULL "
				+ "WITH d, vector.cosine(d.embedding, $q) AS score "
				+ "ORDER BY score DESC LIMIT 2 RETURN d.name AS name, score",
			Map.of("q", new float[] { 1f, 0f, 0f }));
		assertEquals(List.of("east", "east-ish"), result.column("name"));
	}

	@Test
	public void testANullVectorYieldsNull() {
		// The unembedded node reaches the function with a null argument and must not fail the query for the others.
		CypherResult result = run("MATCH (d:Doc) RETURN vector.cosine(d.embedding, $q) AS score ORDER BY d.name",
			Map.of("q", new float[] { 1f, 0f, 0f }));
		assertEquals(4, result.column("score").size());
		assertTrue(result.column("score").contains(null), "the node with no embedding scores null, not zero");
	}

	/**
	 * Two vectors of different lengths are not far apart, they are incomparable — and the overwhelmingly likely cause
	 * is a query vector written for a different model than the stored one. Returning a number for that would be the
	 * silent wrong answer this whole area exists to avoid.
	 */
	@Test
	public void testADimensionMismatchIsRefused() {
		CypherTypeException mismatch = assertThrows(CypherTypeException.class,
			() -> run("RETURN vector.cosine([1.0, 0.0], [1.0, 0.0, 0.0])"));
		assertTrue(mismatch.getMessage().contains("2 dimensions"), mismatch.getMessage());
		assertTrue(mismatch.getMessage().contains("3"), mismatch.getMessage());
	}

	@Test
	public void testAZeroVectorScoresZeroRatherThanNaN() {
		Double score = (Double) run("RETURN vector.cosine([0.0, 0.0], [1.0, 0.0]) AS s").column("s").get(0);
		assertEquals(0.0, score, 1e-9);
		assertNull(run("RETURN vector.cosine(null, [1.0, 0.0]) AS s").column("s").get(0));
	}

	@Test
	public void testAnUnknownVectorFunctionNamesItself() {
		CypherTypeException unknown = assertThrows(CypherTypeException.class,
			() -> run("RETURN vector.manhattan([1.0], [2.0])"));
		assertTrue(unknown.getMessage().contains("vector.manhattan"), unknown.getMessage());
		assertTrue(unknown.getMessage().contains("vector.cosine"), "and lists what does exist: " + unknown.getMessage());
	}

	@Test
	public void testWrongArity() {
		assertTrue(assertThrows(CypherTypeException.class, () -> run("RETURN vector.dot([1.0])")).getMessage()
			.contains("2 arguments"));
	}
}
