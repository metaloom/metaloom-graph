package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.parse.CypherParser;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;

/**
 * {@code CASE}, in both of its forms.
 *
 * <p>
 * Two things about it are easy to get wrong in ways that throw nothing. The first is <b>laziness</b>: only the branch
 * that is taken may be evaluated, or a guard clause cannot guard anything. The second is <b>null</b>, where CASE
 * inherits the three-valued rules of §2 — an unmatched CASE with no ELSE is null, a null predicate is not true, and in
 * the simple form a null subject matches no WHEN at all, not even {@code WHEN null}.
 * </p>
 *
 * @see <a href="../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@Tag("unit")
public class CaseTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore scores() throws Exception {
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			store.createNode("S", Properties.of().set("name", "a").set("score", 95L).set("grade", "A"));
			store.createNode("S", Properties.of().set("name", "b").set("score", 85L).set("grade", "B"));
			store.createNode("S", Properties.of().set("name", "c").set("score", 40L).set("grade", "F"));
			// No score and no grade at all, which is how null gets into every expression below.
			store.createNode("S", Properties.of().set("name", "d"));
			txn.commit();
		}
		return store;
	}

	private List<Object> column(GraphStore store, String projection) {
		return Cypher.query(store, "MATCH (n:S) RETURN " + projection + " AS x ORDER BY n.name").column("x");
	}

	// ---------------------------------------------------------------- the two forms

	@Test
	public void testTheGenericFormTakesTheFirstTruePredicate() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of("high", "high", "low", "unknown"),
				column(store, "CASE WHEN n.score >= 80 THEN 'high' WHEN n.score >= 0 THEN 'low' "
					+ "ELSE 'unknown' END"),
				"the first branch wins, and the node with no score falls through to ELSE");
		}
	}

	@Test
	public void testTheSimpleFormComparesForEquality() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of(4L, 3L, 0L, 0L),
				column(store, "CASE n.grade WHEN 'A' THEN 4 WHEN 'B' THEN 3 ELSE 0 END"));
		}
	}

	@Test
	public void testWithNoElseAnUnmatchedCaseIsNull() throws Exception {
		try (GraphStore store = scores()) {
			List<Object> values = column(store, "CASE n.grade WHEN 'A' THEN 'top' END");
			assertEquals(4, values.size());
			assertEquals("top", values.get(0));
			assertEquals(null, values.get(1), "no branch matched and there is no ELSE");
			assertEquals(null, values.get(3));
		}
	}

	@Test
	public void testTheSimpleFormComparesUnderCypherEqualityRatherThanJavaEquality() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of("one"), Cypher.query(store,
				"RETURN CASE 1 WHEN 1.0 THEN 'one' ELSE 'other' END AS x").column("x"),
				"1 and 1.0 are the same Cypher value, so they select the same branch");
		}
	}

	// ---------------------------------------------------------------- laziness

	/**
	 * Only the branch that is taken is evaluated.
	 *
	 * <p>
	 * {@code size(1)} is a type error, so a CASE that evaluated every arm would throw here. This is what makes
	 * {@code CASE WHEN n.d = 0 THEN 0 ELSE n.x / n.d END} a usable guard rather than a decoration.
	 * </p>
	 */
	@Test
	public void testOnlyTheChosenBranchIsEvaluated() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of("safe"),
				Cypher.query(store, "RETURN CASE WHEN true THEN 'safe' ELSE size(1) END AS x").column("x"));
			assertEquals(List.of("safe"),
				Cypher.query(store, "RETURN CASE WHEN false THEN size(1) ELSE 'safe' END AS x").column("x"));
			// And the arm that never runs is not even reached when an earlier WHEN already matched.
			assertEquals(List.of("first"), Cypher.query(store,
				"RETURN CASE WHEN true THEN 'first' WHEN size(1) THEN 'second' END AS x").column("x"));
		}
	}

	// ---------------------------------------------------------------- null

	@Test
	public void testANullPredicateIsNotTrue() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of("no", "no", "no", "no"),
				column(store, "CASE WHEN n.missing THEN 'yes' ELSE 'no' END"),
				"an unknown predicate drops through exactly as false does");
		}
	}

	/**
	 * A null subject matches no WHEN, including {@code WHEN null}.
	 *
	 * <p>
	 * The simple form is equality, and null equals null is null rather than true. It reads wrong and it is right;
	 * {@code IS NULL} is how the question is actually asked.
	 * </p>
	 */
	@Test
	public void testANullSubjectMatchesNothing() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of("other"),
				Cypher.query(store, "RETURN CASE null WHEN null THEN 'null' ELSE 'other' END AS x").column("x"));
			assertEquals(List.of("absent"), Cypher.query(store,
				"MATCH (n:S {name:'d'}) RETURN CASE WHEN n.grade IS NULL THEN 'absent' ELSE n.grade END AS x")
				.column("x"));
		}
	}

	// ---------------------------------------------------------------- where it can appear

	@Test
	public void testInWhere() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of("a", "c"), Cypher.query(store,
				"MATCH (n:S) WHERE CASE WHEN n.score > 90 THEN true WHEN n.score < 50 THEN true ELSE false END "
					+ "RETURN n.name AS name ORDER BY name").column("name"));
		}
	}

	@Test
	public void testInAnAggregate() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of(2L), Cypher.query(store,
				"MATCH (n:S) RETURN sum(CASE WHEN n.score >= 80 THEN 1 ELSE 0 END) AS passes").column("passes"));
		}
	}

	/**
	 * The aggregate inside a branch rather than around the CASE, which is the other way round.
	 *
	 * <p>
	 * Rare, and legal. It matters because the two are told apart by the same predicate: once a projection item is
	 * classified as aggregating, the whole item is folded over the group rather than evaluated per row, and a CASE the
	 * fold did not understand would quietly evaluate {@code count(*)} against a single row.
	 * </p>
	 */
	@Test
	public void testAnAggregateInsideABranch() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of(4L), Cypher.query(store,
				"MATCH (n:S) RETURN CASE WHEN true THEN count(*) ELSE 0 END AS n").column("n"));
			assertEquals(List.of(0L), Cypher.query(store,
				"MATCH (n:S) RETURN CASE WHEN false THEN count(*) ELSE 0 END AS n").column("n"));
			// Grouped, so the fold has to run per group rather than over everything.
			assertEquals(List.of(1L, 1L, 1L, 1L), Cypher.query(store,
				"MATCH (n:S) RETURN n.name AS name, CASE WHEN true THEN count(*) ELSE 0 END AS c ORDER BY name")
				.column("c"));
		}
	}

	@Test
	public void testInOrderBy() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of("c", "a", "b", "d"), Cypher.query(store,
				"MATCH (n:S) RETURN n.name AS name "
					+ "ORDER BY CASE n.grade WHEN 'F' THEN 0 WHEN 'A' THEN 1 WHEN 'B' THEN 2 ELSE 3 END")
				.column("name"));
		}
	}

	/** The corpus shape: a CASE inside the property map of a CREATE, which is how {@code 03-social} is generated. */
	@Test
	public void testInsideACreatedPropertyMap() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "UNWIND range(0, 5) AS i CREATE (:P {i: i, "
				+ "language: CASE WHEN (i % 3) = 0 THEN 'en' WHEN (i % 3) = 1 THEN 'de' ELSE 'fr' END})");
			assertEquals(List.of("en", "de", "fr", "en", "de", "fr"),
				Cypher.query(store, "MATCH (p:P) RETURN p.language AS l ORDER BY p.i").column("l"));
		}
	}

	@Test
	public void testInSet() throws Exception {
		try (GraphStore store = scores()) {
			Cypher.query(store, "MATCH (n:S) SET n.band = CASE WHEN n.score >= 80 THEN 'pass' ELSE 'fail' END");
			assertEquals(List.of("pass", "pass", "fail", "fail"), column(store, "n.band"));
		}
	}

	@Test
	public void testNested() throws Exception {
		try (GraphStore store = scores()) {
			assertEquals(List.of("A+", "B", "F", "?"), column(store,
				"CASE WHEN n.grade IS NULL THEN '?' "
					+ "ELSE CASE n.grade WHEN 'A' THEN 'A+' ELSE n.grade END END"));
		}
	}

	// ---------------------------------------------------------------- refusals

	@Test
	public void testAMalformedCaseSaysWhatIsMissing() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("RETURN CASE WHEN true THEN 1 AS x")).getMessage().contains("END"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("RETURN CASE 1 ELSE 2 END AS x")).getMessage()
				.contains("at least one WHEN"));
		assertThrows(CypherSyntaxException.class, () -> CypherParser.parse("RETURN CASE WHEN true 1 END AS x"));
	}
}
