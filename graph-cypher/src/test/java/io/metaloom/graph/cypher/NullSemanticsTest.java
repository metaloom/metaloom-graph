package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Cypher's three-valued logic, on its own.
 *
 * <p>
 * This has a test class to itself because it is where a Cypher implementation is most likely to be quietly wrong.
 * Getting null semantics wrong does not throw and does not corrupt anything: it silently returns rows the query did not
 * ask for, or drops rows it did. A user would have to already suspect the engine to notice.
 * </p>
 *
 * <p>
 * The rules being held down, none of which match Java's:
 * </p>
 *
 * <ul>
 * <li>{@code null = null} is null, not true.</li>
 * <li>Every comparison with null is null.</li>
 * <li>AND and OR are Kleene: a decisive operand wins even when the other is unknown.</li>
 * <li>WHERE keeps a row only on TRUE, so null behaves as false there — but only there.</li>
 * <li>{@code IN} over a list containing null can be null rather than false.</li>
 * <li>Aggregates skip nulls; {@code count(*)} does not, because it counts rows.</li>
 * <li>DISTINCT and ORDER BY need a total order, so they treat nulls as equal to each other.</li>
 * </ul>
 */
@Tag("unit")
public class NullSemanticsTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** Evaluates a scalar expression with no graph involved. */
	private Object value(GraphStore store, String expression) {
		return Cypher.query(store, "RETURN " + expression + " AS v").row(0).get("v");
	}

	private Object value(GraphStore store, String expression, Map<String, Object> parameters) {
		return Cypher.query(store, "RETURN " + expression + " AS v", parameters).row(0).get("v");
	}

	@Test
	public void testEqualityWithNullIsNull() throws Exception {
		try (GraphStore store = stores.open()) {
			assertNull(value(store, "null = null"), "null = null is null, not true");
			assertNull(value(store, "null <> null"));
			assertNull(value(store, "1 = null"));
			assertNull(value(store, "null = 1"));
			assertEquals(Boolean.TRUE, value(store, "1 = 1"));
			assertEquals(Boolean.FALSE, value(store, "1 = 2"));
		}
	}

	@Test
	public void testOrderingComparisonsWithNullAreNull() throws Exception {
		try (GraphStore store = stores.open()) {
			for (String operator : List.of("<", "<=", ">", ">=")) {
				assertNull(value(store, "null " + operator + " 1"), "null " + operator + " 1");
				assertNull(value(store, "1 " + operator + " null"), "1 " + operator + " null");
			}
		}
	}

	@Test
	public void testKleeneAnd() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(Boolean.FALSE, value(store, "false AND null"), "false decides it, whatever the other side");
			assertEquals(Boolean.FALSE, value(store, "null AND false"));
			assertNull(value(store, "true AND null"), "true does not decide an AND");
			assertNull(value(store, "null AND true"));
			assertNull(value(store, "null AND null"));
			assertEquals(Boolean.TRUE, value(store, "true AND true"));
		}
	}

	@Test
	public void testKleeneOr() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(Boolean.TRUE, value(store, "true OR null"), "true decides it");
			assertEquals(Boolean.TRUE, value(store, "null OR true"));
			assertNull(value(store, "false OR null"), "false does not decide an OR");
			assertNull(value(store, "null OR false"));
			assertNull(value(store, "null OR null"));
			assertEquals(Boolean.FALSE, value(store, "false OR false"));
		}
	}

	@Test
	public void testNotAndXorPropagateNull() throws Exception {
		try (GraphStore store = stores.open()) {
			assertNull(value(store, "NOT null"));
			assertNull(value(store, "null XOR true"));
			assertEquals(Boolean.TRUE, value(store, "NOT false"));
			assertEquals(Boolean.TRUE, value(store, "true XOR false"));
		}
	}

	@Test
	public void testArithmeticWithNullIsNull() throws Exception {
		try (GraphStore store = stores.open()) {
			assertNull(value(store, "1 + null"));
			assertNull(value(store, "null * 3"));
			assertNull(value(store, "-null"));
			assertNull(value(store, "null / 2"));
		}
	}

	/**
	 * {@code IN} is the subtle one: an unmatched needle against a list containing null is <b>null</b>, because the null
	 * might have been the value being looked for. A match found is still true regardless.
	 */
	@Test
	public void testInWithNulls() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(Boolean.TRUE, value(store, "1 IN [1, 2]"));
			assertEquals(Boolean.FALSE, value(store, "3 IN [1, 2]"));
			assertEquals(Boolean.TRUE, value(store, "1 IN [1, null]"), "a match is a match");
			assertNull(value(store, "3 IN [1, null]"), "no match, but the null might have been a 3");
			assertNull(value(store, "null IN [1, 2]"));
			assertEquals(Boolean.FALSE, value(store, "null IN []"), "nothing is in an empty list, not even null");
		}
	}

	@Test
	public void testStringPredicatesWithNull() throws Exception {
		try (GraphStore store = stores.open()) {
			assertNull(value(store, "null STARTS WITH 'a'"));
			assertNull(value(store, "'abc' STARTS WITH null"));
			assertNull(value(store, "null CONTAINS 'a'"));
			assertEquals(Boolean.TRUE, value(store, "'abc' CONTAINS 'b'"));
		}
	}

	/** The one place the third value collapses: a WHERE that is null drops the row, exactly as false does. */
	@Test
	public void testWhereTreatsNullAsFalse() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("P", Properties.of().set("name", "has").set("age", 30L));
				store.createNode("P", Properties.of().set("name", "lacks"));
				txn.commit();
			}

			assertEquals(List.of("has"),
				Cypher.query(store, "MATCH (n:P) WHERE n.age > 20 RETURN n.name").column("n.name"),
				"the node with no age yields null, and null does not pass a filter");
			assertEquals(List.of("lacks"),
				Cypher.query(store, "MATCH (n:P) WHERE n.age IS NULL RETURN n.name").column("n.name"));
			assertEquals(0,
				Cypher.query(store, "MATCH (n:P) WHERE NOT n.age > 20 RETURN n.name").size(),
				"NOT null is null, so negating does not rescue the row — this is the classic surprise");
		}
	}

	@Test
	public void testAggregatesSkipNullsButCountStarDoesNot() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("P", Properties.of().set("age", 10L));
				store.createNode("P", Properties.of().set("age", 20L));
				store.createNode("P");
				txn.commit();
			}

			CypherResult result = Cypher.query(store,
				"MATCH (n:P) RETURN count(*) AS rows, count(n.age) AS ages, avg(n.age) AS mean, "
					+ "sum(n.age) AS total, min(n.age) AS lo, max(n.age) AS hi, collect(n.age) AS ages2");
			assertEquals(3L, result.row(0).longValue("rows"));
			assertEquals(2L, result.row(0).longValue("ages"));
			assertEquals(15.0, result.row(0).doubleValue("mean"), 1e-9, "30 over 2, not over 3");
			assertEquals(30L, result.row(0).longValue("total"));
			assertEquals(10L, result.row(0).longValue("lo"));
			assertEquals(20L, result.row(0).longValue("hi"));
			assertEquals(List.of(10L, 20L), result.row(0).get("ages2"), "collect drops the null");
		}
	}

	@Test
	public void testSumOfNothingIsZeroButAverageOfNothingIsNull() throws Exception {
		try (GraphStore store = stores.open()) {
			CypherResult result = Cypher.query(store,
				"MATCH (n:Nonexistent) RETURN count(*) AS c, sum(n.x) AS s, avg(n.x) AS a, collect(n.x) AS l");
			assertEquals(1, result.size(), "an ungrouped aggregate over nothing still produces one row");
			assertEquals(0L, result.row(0).longValue("c"));
			assertEquals(0L, result.row(0).longValue("s"), "summing nothing is zero");
			assertNull(result.row(0).get("a"), "averaging nothing is unknown, not zero");
			assertEquals(List.of(), result.row(0).get("l"));
		}
	}

	/** DISTINCT and ORDER BY need a total order, so unlike comparison they must treat two nulls as the same. */
	@Test
	public void testDistinctAndOrderTreatNullsAsEqual() throws Exception {
		try (GraphStore store = stores.open()) {
			CypherResult distinct = Cypher.query(store, "UNWIND [1, null, 1, null] AS x RETURN DISTINCT x");
			assertEquals(2, distinct.size(), "one 1 and one null, even though null = null is null");

			CypherResult ordered = Cypher.query(store, "UNWIND [3, null, 1] AS x RETURN x ORDER BY x");
			assertEquals(Arrays.asList(1L, 3L, null), ordered.column("x"), "nulls sort last ascending");

			CypherResult reversed = Cypher.query(store, "UNWIND [3, null, 1] AS x RETURN x ORDER BY x DESC");
			assertEquals(Arrays.asList(null, 3L, 1L), reversed.column("x"));
		}
	}

	@Test
	public void testPropertyOfNullIsNullRatherThanAnError() throws Exception {
		try (GraphStore store = stores.open()) {
			assertNull(value(store, "null.name"), "reading through a null is null, not a failure");
			assertNull(value(store, "$missing.name", Collections.singletonMap("missing", null)));
		}
	}

	@Test
	public void testCoalesceStopsAtTheFirstNonNull() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals("fallback", value(store, "coalesce(null, null, 'fallback', 'unused')"));
			assertNull(value(store, "coalesce(null, null)"));
		}
	}

	@Test
	public void testFunctionsPropagateNull() throws Exception {
		try (GraphStore store = stores.open()) {
			for (String call : List.of("toUpper(null)", "trim(null)", "abs(null)", "size(null)", "head(null)",
				"toInteger(null)", "labels(null)", "type(null)")) {
				assertNull(value(store, call), call + " should be null");
			}
			assertEquals(Boolean.FALSE, value(store, "exists(null)"), "exists is the one that answers rather than propagates");
			assertNull(value(store, "toInteger('not a number')"), "a failed coercion is null, not an error");
		}
	}

	@Test
	public void testShortCircuitProtectsTheRightHandSide() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("P", Properties.of().set("name", "only"));
				txn.commit();
			}
			// The guard has to actually prevent the right side from running, or a query written defensively still
			// fails on the data it was written to defend against.
			CypherResult result = Cypher.query(store,
				"MATCH (n:P) WHERE n.age IS NOT NULL AND n.age > 10 RETURN n.name");
			assertTrue(result.isEmpty());
		}
	}
}
