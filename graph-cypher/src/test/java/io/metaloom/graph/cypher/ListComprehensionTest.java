package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.parse.CypherParser;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;

/**
 * List comprehensions and the four quantifiers, {@code all}, {@code any}, {@code none} and {@code single}.
 *
 * <p>
 * The quantifiers are three-valued, and that is where they go wrong silently: {@code any(x IN [null, 1] WHERE x > 0)}
 * is true because a known element decides it, but {@code all} over the same list is null, because the null element
 * might have failed.
 * </p>
 */
@Tag("unit")
public class ListComprehensionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static Object value(GraphStore store, String expression) {
		return Cypher.query(store, "RETURN " + expression + " AS v").column("v").get(0);
	}

	// ---------------------------------------------------------------- comprehensions

	@Test
	public void testTheThreeShapes() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(List.of(2L, 4L, 6L), value(store, "[x IN [1, 2, 3] | x * 2]"));
			assertEquals(List.of(2L, 3L), value(store, "[x IN [1, 2, 3] WHERE x > 1]"));
			assertEquals(List.of(20L, 30L), value(store, "[x IN [1, 2, 3] WHERE x > 1 | x * 10]"));
			assertEquals(List.of(1L, 2L), value(store, "[x IN [1, 2]]"), "no WHERE and no |: the list itself");
		}
	}

	@Test
	public void testANullPredicateDropsTheElementAndANullListIsNull() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(List.of(3L), value(store, "[x IN [null, 1, 3] WHERE x > 2]"));
			assertNull(value(store, "[x IN null | x]"));
			assertEquals(Arrays.asList(null, 2L), value(store, "[x IN [null, 1] | x + 1]"));
		}
	}

	@Test
	public void testTheVariableIsLocal() throws Exception {
		try (GraphStore store = stores.open()) {
			// The inner x shadows the outer one inside the brackets, and the outer is untouched after them.
			assertEquals(List.of(List.of(11L, 12L), 5L), Cypher.query(store,
				"WITH 5 AS x RETURN [x IN [1, 2] | x + 10] AS inner, x").values().get(0));
			assertEquals(List.of(List.of(11L, 12L)), Cypher.query(store,
				"WITH 10 AS y RETURN [x IN [1, 2] | x + y] AS v").column("v"), "the outer scope is visible");
			assertThrows(CypherTypeException.class, () -> Cypher.query(store,
				"RETURN [x IN [1] | x] AS v, x AS leaked"));
		}
	}

	@Test
	public void testOverAnAggregate() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "UNWIND [3, 1, 2] AS a CREATE (:N {a: a})");
			assertEquals(List.of(List.of(30L, 10L, 20L)), Cypher.query(store,
				"MATCH (n:N) RETURN [x IN collect(n.a) | x * 10] AS v").column("v"));
			assertEquals(List.of(true), Cypher.query(store,
				"MATCH (n:N) RETURN all(x IN collect(n.a) WHERE x > 0) AS v").column("v"));
		}
	}

	@Test
	public void testNestedAndOverNodes() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(List.of(List.of(1L, 2L), List.of(2L, 4L)),
				value(store, "[x IN [1, 2] | [y IN [1, 2] | x * y]]"));
			Cypher.query(store, "CREATE (:M {n: 'a'})-[:R]->(:M {n: 'b'})");
			assertEquals(List.of(List.of("a", "b")), Cypher.query(store,
				"MATCH p = (:M)-->(:M) RETURN [n IN nodes(p) | n.n] AS v").column("v"));
		}
	}

	@Test
	public void testANonListIsAnError() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(CypherTypeException.class, () -> value(store, "[x IN 5 | x]"));
		}
	}

	@Test
	public void testAMalformedComprehensionIsASyntaxError() {
		CypherSyntaxException error = assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("RETURN [x IN [1] , 2] AS v"));
		assertTrue(error.getMessage().contains("list comprehension"), error.getMessage());
	}

	// ---------------------------------------------------------------- quantifiers

	@Test
	public void testTheQuantifiersOverKnownValues() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(true, value(store, "all(x IN [1, 2] WHERE x > 0)"));
			assertEquals(false, value(store, "all(x IN [1, -2] WHERE x > 0)"));
			assertEquals(true, value(store, "any(x IN [1, -2] WHERE x < 0)"));
			assertEquals(false, value(store, "any(x IN [1, 2] WHERE x < 0)"));
			assertEquals(true, value(store, "none(x IN [1, 2] WHERE x < 0)"));
			assertEquals(false, value(store, "none(x IN [1, -2] WHERE x < 0)"));
			assertEquals(true, value(store, "single(x IN [1, -2] WHERE x < 0)"));
			assertEquals(false, value(store, "single(x IN [-1, -2] WHERE x < 0)"));
			assertEquals(false, value(store, "single(x IN [1, 2] WHERE x < 0)"));
			// Keywords are case-insensitive, and the three that are not keywords are matched case-insensitively too.
			assertEquals(true, value(store, "ALL(x IN [1] WHERE x = 1) AND Any(x IN [1] WHERE x = 1)"));
		}
	}

	@Test
	public void testTheQuantifiersOverAnEmptyList() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(true, value(store, "all(x IN [] WHERE x > 0)"));
			assertEquals(false, value(store, "any(x IN [] WHERE x > 0)"));
			assertEquals(true, value(store, "none(x IN [] WHERE x > 0)"));
			assertEquals(false, value(store, "single(x IN [] WHERE x > 0)"));
			assertNull(value(store, "any(x IN null WHERE x > 0)"));
		}
	}

	/** A null predicate is an unknown element, which decides nothing the known ones have not already decided. */
	@Test
	public void testTheQuantifiersAreThreeValued() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(true, value(store, "any(x IN [null, 1] WHERE x > 0)"));
			assertNull(value(store, "any(x IN [null, -1] WHERE x > 0)"));
			assertEquals(false, value(store, "all(x IN [null, -1] WHERE x > 0)"));
			assertNull(value(store, "all(x IN [null, 1] WHERE x > 0)"));
			assertEquals(false, value(store, "none(x IN [null, 1] WHERE x > 0)"));
			assertNull(value(store, "none(x IN [null, -1] WHERE x > 0)"));
			assertNull(value(store, "single(x IN [null, 1] WHERE x > 0)"));
			assertEquals(false, value(store, "single(x IN [null, 1, 2] WHERE x > 0)"));
		}
	}

	@Test
	public void testAQuantifierFiltersNodes() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:Q {name: 'a', xs: [1, 2, 3]}), (:Q {name: 'b', xs: [1, -1]})");
			assertEquals(List.of("a"), Cypher.query(store,
				"MATCH (n:Q) WHERE all(x IN n.xs WHERE x > 0) RETURN n.name AS n").column("n"));
		}
	}

	@Test
	public void testAQuantifierNeedsItsWhere() {
		CypherSyntaxException error = assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("RETURN any(x IN [1]) AS v"));
		assertTrue(error.getMessage().contains("needs a predicate"), error.getMessage());
	}

	@Test
	public void testANonBooleanPredicateIsAnError() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(CypherTypeException.class, () -> value(store, "any(x IN [1] WHERE x + 1)"));
		}
	}
}
