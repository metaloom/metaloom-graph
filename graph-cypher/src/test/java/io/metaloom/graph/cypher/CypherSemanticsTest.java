package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;

/**
 * Semantic edge cases against openCypher, each one a defect this engine had and answered without throwing.
 *
 * <p>
 * Every case here returned a plausible wrong value — a string where a list belonged, a node where nothing should have
 * matched, a second MERGE node, a column holding another column's value — or failed with an exception type no caller
 * could classify. They are grouped by the layer that was wrong rather than by the clause that exposed them.
 * </p>
 */
@Tag("unit")
public class CypherSemanticsTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static Object value(GraphStore store, String expression) {
		return Cypher.query(store, "RETURN " + expression + " AS v").row(0).get("v");
	}

	private static long count(GraphStore store, String query) {
		return Cypher.query(store, query).row(0).longValue("c");
	}

	private static List<Object> column(GraphStore store, String query) {
		return Cypher.query(store, query).column("v");
	}

	// ---------------------------------------------------------------- lists and strings under +

	/** A list on either side of {@code +} makes it list concatenation, even when the other side is a string. */
	@Test
	public void testPlusWithAListIsListConcatenation() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(List.of(1L, 2L, "x"), value(store, "[1, 2] + 'x'"));
			assertEquals(List.of("x", 1L, 2L), value(store, "'x' + [1, 2]"));
			assertEquals(List.of(1L, 2L), value(store, "1 + [2]"));
			assertEquals(List.of(1L, List.of(2L)), value(store, "[1] + [[2]]"));
			assertEquals("ab", value(store, "'a' + 'b'"));
			assertNull(value(store, "null + [1]"));
		}
	}

	// ---------------------------------------------------------------- numbers

	/** Integers and floats compare exactly, not after rounding the integer to a double. */
	@Test
	public void testIntegerAndFloatCompareExactly() throws Exception {
		try (GraphStore store = stores.open()) {
			// 2^53 + 1 has no double; rounding it first made it equal to 2^53.
			assertEquals(Boolean.FALSE, value(store, "9007199254740993 = 9007199254740992.0"));
			assertEquals(Boolean.TRUE, value(store, "9007199254740993 > 9007199254740992.0"));
			assertEquals(Boolean.TRUE, value(store, "9007199254740992 = 9007199254740992.0"));
			assertEquals(Boolean.TRUE, value(store, "9223372036854775807 < 9223372036854775808.0"));
			assertEquals(Boolean.TRUE, value(store, "-9223372036854775808 = -9223372036854775808.0"));
			assertEquals(Boolean.TRUE, value(store, "1 = 1.0"));
			assertEquals(Boolean.TRUE, value(store, "1 < 1.5"));
			assertEquals(Boolean.FALSE, value(store, "1 = 0.0 / 0.0"));
			// The same rule must hold where two-valued equality is used, or DISTINCT and = disagree.
			assertEquals(2L, count(store,
				"UNWIND [9007199254740993, 9007199254740992.0] AS x WITH DISTINCT x RETURN count(*) AS c"));
			assertEquals(Boolean.FALSE, value(store, "9007199254740993 IN [9007199254740992.0]"));
		}
	}

	/** A sort over integers and floats near 2^53 must be a total order, whichever way the values happen to arrive. */
	@Test
	public void testMixedNumericSortIsATotalOrder() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Object> sorted = column(store, "UNWIND [9007199254740993, 9007199254740992.0, 9007199254740992, "
				+ "9007199254740994.0, 9007199254740993] AS v RETURN v ORDER BY v");
			assertEquals(Arrays.asList(9007199254740992.0, 9007199254740992L, 9007199254740993L, 9007199254740993L,
				9007199254740994.0).toString(), sorted.toString());
			// Within the equal pair the order is the input's (a stable sort), so only check it is sorted.
			for (int i = 1; i < sorted.size(); i++) {
				assertTrue(new java.math.BigDecimal(sorted.get(i - 1).toString())
					.compareTo(new java.math.BigDecimal(sorted.get(i).toString())) <= 0, sorted.toString());
			}
		}
	}

	/** {@code round} of a float that is already integral, or too large for a long, or NaN, is that float. */
	@Test
	public void testRoundKeepsLargeAndNonFiniteValues() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(1e300, value(store, "round(1e300)"));
			assertEquals(-1e19, value(store, "round(-1e19)"));
			assertTrue(Double.isNaN((Double) value(store, "round(0.0 / 0.0)")), "round(NaN) is NaN, not 0.0");
			assertEquals(Double.POSITIVE_INFINITY, value(store, "round(1.0 / 0)"));
			assertEquals(3.0, value(store, "round(2.5)"));
			assertEquals(3.0, value(store, "round(3)"));
			assertEquals(4503599627370497.0, value(store, "round(4503599627370497.0)"));
		}
	}

	/** {@code toInteger} of a string that holds a float truncates it, as it would the float itself. */
	@Test
	public void testToIntegerParsesAFloatString() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(2L, value(store, "toInteger('2.7')"));
			assertEquals(-2L, value(store, "toInteger('-2.7')"));
			assertEquals(1000L, value(store, "toInteger('1e3')"));
			assertEquals(12L, value(store, "toInteger(' 12 ')"));
			assertNull(value(store, "toInteger('twelve')"));
			assertNull(value(store, "toInteger('1e400')"), "out of range is not a number it can return");
			// Java's parser accepts a type suffix and hex floats; a Cypher string holding them is not a number.
			assertNull(value(store, "toFloat('1.5f')"));
			assertNull(value(store, "toFloat('0x1p3')"));
			assertNull(value(store, "toInteger('2d')"));
			assertEquals(1.5, value(store, "toFloat('1.5')"));
			assertEquals(-0.5, value(store, "toFloat('-.5')"));
		}
	}

	/** {@code toBoolean} takes an integer, as Neo4j 5's does: zero is false, anything else true. */
	@Test
	public void testToBooleanTakesAnInteger() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(Boolean.FALSE, value(store, "toBoolean(0)"));
			assertEquals(Boolean.TRUE, value(store, "toBoolean(-3)"));
			assertEquals(Boolean.TRUE, value(store, "toBoolean('TRUE')"));
			assertNull(value(store, "toBoolean('yes')"));
			assertThrows(CypherTypeException.class, () -> value(store, "toBoolean(1.5)"));
		}
	}

	// ---------------------------------------------------------------- strings are code points

	/** Length, substring and split count code points, so they can never cut a character in half. */
	@Test
	public void testStringFunctionsCountCodePoints() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(1L, value(store, "size('😀')"));
			assertEquals(2L, value(store, "length('a😀')"));
			assertEquals("x", value(store, "substring('😀x', 1)"));
			assertEquals("😀", value(store, "substring('a😀x', 1, 1)"));
			assertEquals("x😀a", value(store, "reverse('a😀x')"));
			assertEquals(List.of("a", "😀", "b"), value(store, "split('a😀b', '')"));
			assertEquals(List.of("a", "b", "c"), value(store, "split('abc', '')"), "no trailing empty element");
			assertEquals(List.of("a", "", "b"), value(store, "split('a,,b', ',')"));
			assertThrows(CypherTypeException.class, () -> value(store, "substring('😀', 2)"));
		}
	}

	/** A string with half a surrogate pair has no UTF-8 form, so the query is refused before the store sees it. */
	@Test
	public void testALoneSurrogateIsRefusedAsCypherRatherThanByTheStore() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:S)");
			CypherSyntaxException syntax = assertThrows(CypherSyntaxException.class,
				() -> Cypher.query(store, "MATCH (n:S) SET n.x = '\\uD83D' RETURN n"));
			assertTrue(syntax.getMessage().contains("surrogate"), syntax.getMessage());
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "RETURN '\uD83D' AS v"));
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "MATCH (n:`\uDE00`) RETURN n"));
			// A pair written as two escapes is one character and is fine.
			assertEquals("😀", value(store, "'\\uD83D\\uDE00'"));
			// A parameter can carry one too, and must be refused as a type error rather than escape as the store's.
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (n:S) SET n.x = $v", Map.of("v", "\uD83D")));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (n:S) SET n += $m", Map.of("m", Map.of("\uDE00", 1L))));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (n:S) SET n.x = [$v]", Map.of("v", "a\uD83D")));
			assertEquals(0L, count(store, "MATCH (n:S) WHERE n.x IS NOT NULL RETURN count(*) AS c"));
		}
	}

	// ---------------------------------------------------------------- the lexer and literals

	/** Only ASCII digits make a number; other scripts' digits are not silently read as one. */
	@Test
	public void testNonAsciiDigitsAreASyntaxErrorRatherThanANumberFormatException() throws Exception {
		try (GraphStore store = stores.open()) {
			// U+0663, ARABIC-INDIC DIGIT THREE: Long.parseLong accepted it and Double.parseDouble threw.
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "RETURN ٣ AS v"));
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "RETURN ٣.٣ AS v"));
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "RETURN 1e٣ AS v"));
		}
	}

	/** A float literal too large for a double is refused, not silently read as Infinity. */
	@Test
	public void testAFloatLiteralOutOfRangeIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "RETURN 1e999 AS v"));
			assertEquals(1e308, value(store, "1e308"));
			assertEquals(0.0, value(store, "1e-999"), "underflow to zero is a rounding, not an overflow");
		}
	}

	// ---------------------------------------------------------------- comparison and equality, three-valued

	/** Comparing lists element by element meets the same nulls and type mismatches as comparing their elements. */
	@Test
	public void testListComparisonIsNullWhenAnElementIsIncomparable() throws Exception {
		try (GraphStore store = stores.open()) {
			assertNull(value(store, "[1] < ['a']"));
			assertNull(value(store, "[1, null] < [1, 2]"));
			assertEquals(Boolean.TRUE, value(store, "[1, null] < [2, null]"), "an earlier element still decides");
			assertEquals(Boolean.TRUE, value(store, "[1, 2] < [1, 3]"));
			assertEquals(Boolean.TRUE, value(store, "[1] < [1, 0]"));
			assertEquals(Boolean.FALSE, value(store, "[2] <= [1, 0]"));
		}
	}

	/** {@code IN} and the simple {@code CASE} use {@code =}, so a null nested in a list is as unknown there too. */
	@Test
	public void testInAndCaseUseThreeValuedEquality() throws Exception {
		try (GraphStore store = stores.open()) {
			assertNull(value(store, "[1, null] IN [[1, null]]"));
			assertNull(value(store, "[1, null] IN [[1, 2]]"));
			assertEquals(Boolean.FALSE, value(store, "[1, null] IN [[2, 2]]"));
			assertEquals(Boolean.TRUE, value(store, "[1, 2] IN [[1, null], [1, 2]]"));
			assertEquals("ne", value(store, "CASE [1, null] WHEN [1, null] THEN 'eq' ELSE 'ne' END"));
			assertEquals("eq", value(store, "CASE [1, 2] WHEN [1, 2.0] THEN 'eq' ELSE 'ne' END"));
		}
	}

	// ---------------------------------------------------------------- ordering across types

	/**
	 * Values of different types sort in openCypher's orderability: map, node, relationship, list, path, string, boolean,
	 * number, and null last. {@code min} and {@code max} use the same order.
	 */
	@Test
	public void testUnlikeTypesSortInOrderabilityOrder() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Object> sorted = column(store,
				"UNWIND [1, 'a', true, [1], {a: 1}, null, 1.5] AS v RETURN v ORDER BY v");
			assertEquals(Arrays.asList(Map.of("a", 1L), List.of(1L), "a", true, 1L, 1.5, null), sorted);
			Map<String, Object> extremes = Cypher.query(store,
				"UNWIND [1, 'a', true, [1], null, 1.5] AS x RETURN min(x) AS mn, max(x) AS mx").row(0).asMap();
			assertEquals(List.of(1L), extremes.get("mn"));
			assertEquals(1.5, extremes.get("mx"));
			// The examples from the Neo4j manual's min() and max(): strings before numbers.
			assertEquals("1", Cypher.query(store,
				"UNWIND [1, 'a', null, 0.2, 'b', '1', '99'] AS x RETURN min(x) AS v").row(0).get("v"));
			assertEquals(1L, Cypher.query(store,
				"UNWIND [1, 'a', null, 0.2, 'b', '1', '99'] AS x RETURN max(x) AS v").row(0).get("v"));
		}
	}

	/** Nodes order by identity, so {@code ORDER BY n} is deterministic and agrees with {@code ORDER BY id(n)}. */
	@Test
	public void testNodesOrderByIdentity() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "UNWIND range(1, 5) AS i CREATE (:O {i: i})");
			assertEquals(Cypher.query(store, "MATCH (n:O) RETURN n.i AS v ORDER BY id(n) DESC").column("v"),
				Cypher.query(store, "MATCH (n:O) RETURN n.i AS v ORDER BY n DESC").column("v"));
		}
	}

	// ---------------------------------------------------------------- projections

	/** Two columns with one name cannot both be returned: the second silently overwrote the first. */
	@Test
	public void testDuplicateColumnNamesAreRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "RETURN 1 AS a, 2 AS a"));
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "UNWIND [1] AS x RETURN x, x"));
			assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, "WITH 1 AS x, 2 AS x RETURN x"));
			assertThrows(CypherTypeException.class, () -> Cypher.query(store, "WITH 1 AS x RETURN *, 2 AS x"));
			assertEquals(List.of("x", "y"), Cypher.query(store, "WITH 1 AS x RETURN *, 2 AS y").columns());
		}
	}

	/** SKIP and LIMIT take a non-negative integer; a float is refused rather than truncated. */
	@Test
	public void testSkipAndLimitMustBeIntegers() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(CypherTypeException.class, () -> Cypher.query(store, "UNWIND [1, 2, 3] AS x RETURN x LIMIT 1.5"));
			assertThrows(CypherTypeException.class, () -> Cypher.query(store, "UNWIND [1, 2, 3] AS x RETURN x SKIP 0.5"));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "UNWIND [1, 2, 3] AS x WITH x LIMIT 1.0 RETURN x"));
			assertEquals(1, Cypher.query(store, "UNWIND [1, 2, 3] AS x RETURN x LIMIT $n", Map.of("n", 1)).size());
		}
	}

	/** After aggregation, ORDER BY may name a projected expression by its text as well as by its alias. */
	@Test
	public void testOrderByAProjectedExpressionAfterAggregation() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "UNWIND [3, 1, 2, 1] AS a CREATE (:G {age: a})");
			assertEquals(List.of(3L, 2L, 1L), Cypher.query(store,
				"MATCH (n:G) RETURN n.age AS v, count(*) AS c ORDER BY n.age DESC").column("v"));
			assertEquals(List.of(1L, 2L, 3L), Cypher.query(store,
				"MATCH (n:G) WITH n.age AS v, count(*) AS c ORDER BY n.age RETURN v").column("v"));
		}
	}

	// ---------------------------------------------------------------- what is not built is refused by name

	/** CYPHER.md §5.5 promises a by-name refusal for each feature it lists as not built. */
	@Test
	public void testUnbuiltExpressionsAreRefusedByName() throws Exception {
		try (GraphStore store = stores.open()) {
			assertRefusedNaming(store, "MATCH (n) RETURN n {.name} AS v", "Map projections");
			assertRefusedNaming(store, "RETURN [1, 2, 3][1..2] AS v", "List slicing");
			// List comprehensions and EXISTS { … } were on this list; they are built now and read as what they are.
			assertEquals(List.of(2L, 4L), value(store, "[x IN [1, 2] | x * 2]"));
			assertEquals(false, value(store, "EXISTS { MATCH (n)-->() }"));
			// The ordinary readings of the same tokens still parse.
			assertEquals(List.of(true), value(store, "[1 IN [1, 2]]"));
			assertEquals(true, value(store, "exists(1)"));
		}
	}

	private static void assertRefusedNaming(GraphStore store, String query, String feature) {
		CypherSyntaxException refused = assertThrows(CypherSyntaxException.class, () -> Cypher.query(store, query));
		assertTrue(refused.getMessage().contains(feature) && refused.getMessage().contains("not supported"),
			query + " -> " + refused.getMessage());
	}

	// ---------------------------------------------------------------- variables that are already bound

	/** A pattern variable bound to null matches nothing: the null is not "unbound", and must not be rebound. */
	@Test
	public void testAPatternVariableBoundToNullMatchesNothing() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (a:N {name: 'a'})-[:R]->(b:N {name: 'b'})-[:R]->(:N {name: 'c'})");
			assertEquals(0L, count(store, "UNWIND [null] AS n MATCH (n) RETURN count(*) AS c"));
			assertEquals(0L, count(store, "UNWIND [null] AS b MATCH (a)-->(b) RETURN count(*) AS c"));
			assertEquals(0L, count(store, "MATCH (a {name: 'a'}) WITH a, null AS b MATCH (a)-->(b) RETURN count(*) AS c"));
			assertEquals(0L, count(store,
				"UNWIND [null] AS b MATCH p = shortestPath((a {name: 'a'})-[*]-(b)) RETURN count(*) AS c"));
			// And the streaming path agrees.
			List<List<Object>> streamed = new java.util.ArrayList<>();
			Cypher.stream(store, "UNWIND [null] AS n MATCH (n) RETURN n", Map.of(), streamed::add);
			assertEquals(List.of(), streamed);
		}
	}

	/** A variable bound to something that is not a node cannot be used as one. */
	@Test
	public void testAPatternVariableBoundToAValueIsATypeError() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:N)-[:R]->(:N)");
			assertThrows(CypherTypeException.class, () -> Cypher.query(store, "WITH 1 AS n MATCH (n) RETURN n"));
			assertThrows(CypherTypeException.class, () -> Cypher.query(store, "WITH 1 AS b MATCH (a)-->(b) RETURN b"));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "WITH 1 AS p MATCH p = (a)-->(b) RETURN p"));
		}
	}

	/** UNWIND, LOAD CSV and YIELD introduce a variable; naming one already in scope is refused, not shadowed. */
	@Test
	public void testRebindingAVariableInScopeIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:N {name: 'a'})-[:R]->(:N {name: 'b'})");
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (n:N) UNWIND [1, 2] AS n RETURN n"));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "WITH 1 AS x UNWIND [2] AS x RETURN x"));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (a:N) CREATE (a)-[a:R]->(:N)"));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (a:N)-[r]->(b) CREATE (a)-[r:R]->(b)"));
			// After a WITH that drops it the name is free again.
			assertEquals(List.of(1L, 2L), Cypher.query(store,
				"MATCH (n:N {name: 'a'}) WITH 0 AS z UNWIND [1, 2] AS n RETURN n").column("n"));
			assertEquals(1L, count(store, "MATCH ()-[r]->() RETURN count(*) AS c"), "the refused CREATEs made nothing");
		}
	}

	// ---------------------------------------------------------------- relationships

	/** An undirected pattern crosses a self-loop once: it is one relationship, not one per endpoint. */
	@Test
	public void testAnUndirectedPatternMatchesASelfLoopOnce() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (a:L)-[:R]->(a)");
			assertEquals(1L, count(store, "MATCH (a:L)-[r]->(a) RETURN count(*) AS c"));
			assertEquals(1L, count(store, "MATCH (a:L)-[r]-(a) RETURN count(*) AS c"));
			assertEquals(1L, count(store, "MATCH ()-[r]-() RETURN count(*) AS c"));
			assertEquals(1L, count(store, "MATCH (a:L)-[r*1..3]-(b) RETURN count(*) AS c"));
			try (var txn = store.begin()) {
				long node = store.nodesByLabel("L").get(0);
				store.createEdge(node, "LIGHT", node);
				txn.commit();
			}
			assertEquals(1L, count(store, "MATCH ()-[r:LIGHT]-() RETURN count(*) AS c"),
				"a lightweight self-loop is one relationship too");
		}
	}

	// ---------------------------------------------------------------- writes

	/** MERGE on a null property can never match what it creates, so every run would add a node. It is refused. */
	@Test
	public void testMergeOnANullPropertyIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(CypherTypeException.class, () -> Cypher.query(store, "MERGE (n:M {k: null})"));
			assertThrows(CypherTypeException.class, () -> Cypher.query(store, "MERGE (n:M {k: $k})",
				java.util.Collections.singletonMap("k", null)));
			Cypher.query(store, "CREATE (:A), (:B)");
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (a:A), (b:B) MERGE (a)-[:R {x: null}]->(b)"));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (a:A) MERGE (a)-[:R]->(:C {y: null})"));
			assertEquals(0L, count(store, "MATCH (n:M) RETURN count(*) AS c"));
			assertEquals(0L, count(store, "MATCH ()-[r:R]->() RETURN count(*) AS c"));
			assertEquals(0L, count(store, "MATCH (n:C) RETURN count(*) AS c"));
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}

	/** A write refreshes every binding in the row, and a binding — or a list element — may be null. */
	@Test
	public void testAWriteOverARowHoldingNullsRefreshesIt() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:W {a: 0})");
			// Found by CypherFuzzTest: the refresh after a write switched over each value, and a switch over null throws.
			Map<String, Object> row = Cypher.query(store,
				"MATCH (n:W) WITH n, null AS x, [n, null] AS l SET n.a = 1 RETURN x, l[0].a AS a").row(0).asMap();
			assertNull(row.get("x"));
			assertEquals(1L, row.get("a"));
			assertEquals(1L, count(store, "MATCH (n:W) WITH n, null AS x REMOVE n.a RETURN count(*) AS c"));
			assertEquals(1L, count(store, "MATCH (n:W) WITH n, [null] AS x SET n:Seen RETURN count(*) AS c"));
		}
	}

	/**
	 * {@code SET x = {…}} with x null is a no-op, as {@code SET x.k = …} and {@code SET x += {…}} already were. Found by
	 * CypherFuzzTest: the replace form switched over its target before looking at it, and a switch over null throws.
	 */
	@Test
	public void testReplacingThePropertiesOfNullIsANoOp() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:Z {k: 1})");
			assertEquals(1L, count(store, "MATCH (n:Z) WITH n, null AS x SET x = {k: 2} RETURN count(*) AS c"));
			assertEquals(1L, count(store, "MATCH (n:Z) WITH n, null AS x SET x += {k: 2}, x.k = 3 RETURN count(*) AS c"));
			assertEquals(1L, count(store, "MATCH (n:Z) WITH n, null AS x SET x:L REMOVE x:Z, x.k RETURN count(*) AS c"));
			assertEquals(1L, count(store, "MATCH (n:Z {k: 1}) RETURN count(*) AS c"), "and n was not touched");
			assertEquals(1L, count(store, "MERGE (n:Z) ON MATCH SET n = null RETURN count(*) AS c"));
			assertEquals(List.of(), Cypher.query(store, "MATCH (n:Z) RETURN keys(n) AS v").column("v").get(0),
				"SET n = null clears n, as SET n = {} does");
		}
	}

	/** A node this query deleted can still be held in a row, but not expanded or re-read: a type error says so. */
	@Test
	public void testUsingANodeThisQueryDeletedIsATypeError() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:D)-[:R]->(:D)");
			// Found by CypherFuzzTest: the engine's "No live node" escaped as a store failure.
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "CREATE (n) DELETE n WITH n MATCH (n)-->() RETURN n"));
			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (a:D)-[r]->(b) DETACH DELETE a RETURN startNode(r) AS s"));
			assertEquals(2L, count(store, "MATCH (n:D) RETURN count(*) AS c"), "both were rolled back");
			// What the row already holds is still there to return, as before.
			assertEquals(1L, count(store, "CREATE (n:Gone {x: 1}) DELETE n RETURN n.x AS c"));
		}
	}

	/**
	 * {@code [1, 2] = [1.0, 2.0]}, and the index keys each list by its own types: probing for one missed the other, so
	 * an inline map lost rows and MERGE created a duplicate. Found by CypherDifferentialTest.
	 */
	@Test
	public void testAListOfNumbersMatchesItsFloatTwinThroughTheIndex() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:T {k: [1, 2]}), (:T {k: [1.0, 2.0]})");
			assertEquals(2L, count(store, "MATCH (n:T {k: [1, 2]}) RETURN count(*) AS c"));
			assertEquals(2L, count(store, "MATCH (n:T {k: [1.0, 2.0]}) RETURN count(*) AS c"));
			store.createIndex("T", "k");
			assertEquals(2L, count(store, "MATCH (n:T {k: [1.0, 2]}) RETURN count(*) AS c"));
			Cypher.query(store, "MERGE (n:T {k: [2.0, 1.0]})");
			Cypher.query(store, "MERGE (n:U {k: [3, 4]})");
			Cypher.query(store, "MERGE (n:U {k: [3.0, 4.0]})");
			assertEquals(1L, count(store, "MATCH (n:U) RETURN count(*) AS c"), "MERGE matched its twin");
		}
	}

	/** Each item of one SET sees the items before it, as it would if they were written as separate SET clauses. */
	@Test
	public void testSetItemsApplyInOrder() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:S {x: 0})");
			Map<String, Object> row = Cypher.query(store,
				"MATCH (a:S) SET a.x = 1, a.y = a.x + 1 RETURN a.x AS x, a.y AS y").row(0).asMap();
			assertEquals(Map.of("x", 1L, "y", 2L), row);
			row = Cypher.query(store, "MATCH (a:S) SET a += {z: 5}, a.w = a.z * 2 RETURN a.w AS w").row(0).asMap();
			assertEquals(Map.of("w", 10L), row);
			row = Cypher.query(store, "MATCH (a:S) SET a:Tagged, a.tagged = a:Tagged RETURN a.tagged AS t")
				.row(0).asMap();
			assertEquals(Map.of("t", true), row);
			// Through a second variable naming the same node, and within a list.
			row = Cypher.query(store,
				"MATCH (a:S) WITH a, a AS b, [a] AS l SET a.q = 7, a.r = b.q, a.s = l[0].q RETURN a.r AS r, a.s AS s")
				.row(0).asMap();
			assertEquals(Map.of("r", 7L, "s", 7L), row);
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}
}
