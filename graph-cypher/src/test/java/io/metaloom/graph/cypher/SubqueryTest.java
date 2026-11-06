package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.parse.CypherParser;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;

/**
 * {@code CALL { … }} and the three subquery expressions, {@code EXISTS}, {@code COUNT} and {@code COLLECT}.
 *
 * <p>
 * The scoping rules are the part to get right. A {@code CALL} body sees only what it imports, so a {@code MATCH (n)}
 * inside it binds a new {@code n} unless {@code n} came in; an expression subquery sees everything, so the same
 * {@code MATCH (n)} is a join on the outer {@code n}.
 * </p>
 */
@Tag("unit")
public class SubqueryTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore people() throws Exception {
		GraphStore store = stores.open();
		Cypher.query(store, "CREATE (a:P {name: 'ann', age: 40})-[:KNOWS]->(b:P {name: 'bob', age: 30}), "
			+ "(a)-[:KNOWS]->(c:P {name: 'cid', age: 20}), (b)-[:KNOWS]->(c), (:P {name: 'dan', age: 50})");
		return store;
	}

	// ---------------------------------------------------------------- CALL { … }

	@Test
	public void testACorrelatedCallRunsOncePerRow() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MATCH (p:P) CALL { WITH p MATCH (p)-[:KNOWS]->(f) "
				+ "RETURN count(f) AS friends } RETURN p.name AS p, friends ORDER BY p");
			assertEquals(List.of("ann", "bob", "cid", "dan"), result.column("p"));
			// An aggregate in the body counts the body's rows per outer row, so a node with no friend still gets 0.
			assertEquals(List.of(2L, 1L, 0L, 0L), result.column("friends"));
		}
	}

	@Test
	public void testTheScopeClauseImportsToo() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MATCH (p:P) CALL (p) { MATCH (p)-[:KNOWS]->(f) "
				+ "RETURN f.name AS f } RETURN p.name AS p, f ORDER BY p, f");
			assertEquals(List.of("ann", "ann", "bob"), result.column("p"));
			assertEquals(List.of("bob", "cid", "cid"), result.column("f"));
			assertEquals(4L, Cypher.query(store, "MATCH (p:P) CALL (*) { RETURN p.name AS n } RETURN count(n) AS c")
				.column("c").get(0));
		}
	}

	@Test
	public void testAnUnimportedNameIsANewVariable() throws Exception {
		try (GraphStore store = people()) {
			// No import: the MATCH (p) inside ranges over every P again, so each of the four outer rows meets four.
			assertEquals(16L, Cypher.query(store, "MATCH (p:P) CALL { MATCH (p:P) RETURN p.name AS inner } "
				+ "RETURN count(*) AS c").column("c").get(0));
		}
	}

	@Test
	public void testAnUncorrelatedCallJoinsEveryRow() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "UNWIND [1, 2] AS x CALL { MATCH (p:P) RETURN max(p.age) AS "
				+ "oldest } RETURN x, oldest");
			assertEquals(List.of(1L, 2L), result.column("x"));
			assertEquals(List.of(50L, 50L), result.column("oldest"));
		}
	}

	@Test
	public void testAUnionBody() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "CALL { MATCH (p:P) WHERE p.age > 45 RETURN p.name AS n "
				+ "UNION MATCH (p:P) WHERE p.age < 25 RETURN p.name AS n } RETURN n ORDER BY n");
			assertEquals(List.of("cid", "dan"), result.column("n"));
		}
	}

	@Test
	public void testAUnitSubqueryWritesAndPassesRowsThrough() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MATCH (p:P) CALL { WITH p CREATE (:Copy {of: p.name}) } "
				+ "RETURN count(*) AS c");
			assertEquals(List.of(4L), result.column("c"));
			assertEquals(4L, result.stats().nodesCreated());
			assertEquals(4L, Cypher.query(store, "MATCH (c:Copy) RETURN count(c) AS c").column("c").get(0));
		}
	}

	@Test
	public void testAWritingBodyMakesTheQueryAWrite() {
		Ast.Query query = CypherParser.parse("MATCH (p) CALL { WITH p SET p.seen = true } RETURN p");
		assertTrue(query.isWrite());
		assertEquals(EnumSet.of(Privilege.MATCH, Privilege.SET), query.requiredPrivileges());
		assertFalse(CypherParser.parse("MATCH (p) CALL { WITH p RETURN 1 AS one } RETURN p").isWrite());
		assertTrue(CypherParser.parse("CALL { LOAD CSV FROM 'x' AS row RETURN row } RETURN row").readsFiles());
	}

	@Test
	public void testAReturnedNameMayNotShadowAnOuterOne() throws Exception {
		try (GraphStore store = people()) {
			CypherTypeException clash = assertThrows(CypherTypeException.class, () -> Cypher.query(store,
				"MATCH (p:P) CALL { MATCH (q:P) RETURN q.name AS p } RETURN p"));
			assertTrue(clash.getMessage().contains("already defined"), clash.getMessage());
		}
	}

	@Test
	public void testAnImportMustExist() throws Exception {
		try (GraphStore store = people()) {
			CypherTypeException missing = assertThrows(CypherTypeException.class, () -> Cypher.query(store,
				"MATCH (p:P) CALL (q) { RETURN 1 AS one } RETURN one"));
			assertTrue(missing.getMessage().contains("'q'"), missing.getMessage());
		}
	}

	@Test
	public void testInTransactionsIsRefusedByName() {
		CypherSyntaxException refused = assertThrows(CypherSyntaxException.class, () -> CypherParser.parse(
			"MATCH (p) CALL { WITH p DELETE p } IN TRANSACTIONS"));
		assertTrue(refused.getMessage().contains("IN TRANSACTIONS"), refused.getMessage());
	}

	// ---------------------------------------------------------------- EXISTS / COUNT / COLLECT

	@Test
	public void testExistsWithAPatternBody() throws Exception {
		try (GraphStore store = people()) {
			assertEquals(List.of("ann", "bob"), Cypher.query(store,
				"MATCH (p:P) WHERE EXISTS { (p)-[:KNOWS]->() } RETURN p.name AS n ORDER BY n").column("n"));
			assertEquals(List.of("cid", "dan"), Cypher.query(store,
				"MATCH (p:P) WHERE NOT EXISTS { (p)-[:KNOWS]->() } RETURN p.name AS n ORDER BY n").column("n"));
		}
	}

	@Test
	public void testExistsWithAMatchAndWhere() throws Exception {
		try (GraphStore store = people()) {
			assertEquals(List.of("ann"), Cypher.query(store, "MATCH (p:P) WHERE EXISTS { MATCH (p)-[:KNOWS]->(f) "
				+ "WHERE f.age < p.age - 15 } RETURN p.name AS n").column("n"));
			// A whole query body, ending in RETURN, which is answered by whether it returns a row.
			assertEquals(List.of("ann", "bob"), Cypher.query(store, "MATCH (p:P) WHERE EXISTS { MATCH (p)-->(f) "
				+ "WITH f WHERE f.name = 'cid' RETURN f } RETURN p.name AS n ORDER BY n").column("n"));
		}
	}

	@Test
	public void testCountAndCollect() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MATCH (p:P) RETURN p.name AS n, "
				+ "COUNT { (p)-[:KNOWS]->() } AS out, "
				+ "COLLECT { MATCH (p)-[:KNOWS]->(f) RETURN f.name ORDER BY f.name } AS friends ORDER BY n");
			assertEquals(List.of(2L, 1L, 0L, 0L), result.column("out"));
			assertEquals(List.of(List.of("bob", "cid"), List.of("cid"), List.of(), List.of()),
				result.column("friends"));
		}
	}

	@Test
	public void testSubqueryExpressionsWorkInAProjectionAfterAggregation() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MATCH (p:P) WITH count(p) AS people "
				+ "RETURN people, COUNT { MATCH (q:P) WHERE q.age > 25 } AS older");
			assertEquals(List.of(4L), result.column("people"));
			assertEquals(List.of(3L), result.column("older"));
		}
	}

	@Test
	public void testExpressionSubqueriesMayNotWrite() {
		for (String query : List.of("MATCH (p) WHERE EXISTS { MATCH (p) SET p.x = 1 } RETURN p",
			"RETURN COUNT { CREATE (n) } AS c", "RETURN EXISTS { LOAD CSV FROM 'x' AS r RETURN r } AS e")) {
			CypherSyntaxException refused = assertThrows(CypherSyntaxException.class, () -> CypherParser.parse(query),
				query);
			assertTrue(refused.getMessage().contains("cannot write"), refused.getMessage());
		}
	}

	@Test
	public void testCollectNeedsOneReturnedColumn() {
		assertThrows(CypherSyntaxException.class, () -> CypherParser.parse(
			"RETURN COLLECT { MATCH (n) RETURN n.a, n.b } AS c"));
		assertThrows(CypherSyntaxException.class, () -> CypherParser.parse("RETURN COLLECT { (n)-->() } AS c"));
	}

	@Test
	public void testTheFunctionsOfTheSameNamesStillWork() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store,
				"MATCH (p:P) RETURN count(*) AS c, collect(p.name)[0] IS NOT NULL AS some, exists(p.age) AS e LIMIT 1");
			assertEquals(4L, result.row(0).get("c"));
		}
	}

	@Test
	public void testExistsStopsAtTheFirstRow() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (hub:H) WITH hub UNWIND range(1, 2000) AS i CREATE (hub)-[:E]->(:L)");
			assertEquals(List.of(true), Cypher.query(store, "MATCH (h:H) RETURN EXISTS { (h)-[:E]->() } AS e")
				.column("e"));
			assertEquals(List.of(2000L), Cypher.query(store, "MATCH (h:H) RETURN COUNT { (h)-[:E]->() } AS e")
				.column("e"));
		}
	}

	@Test
	public void testSubqueryNestingIsBounded() {
		StringBuilder query = new StringBuilder("RETURN ");
		for (int i = 0; i < CypherParser.MAX_SUBQUERY_NESTING + 1; i++) {
			query.append("EXISTS { MATCH (n) WHERE ");
		}
		query.append("true");
		query.append(" }".repeat(CypherParser.MAX_SUBQUERY_NESTING + 1)).append(" AS deep");
		CypherSyntaxException refused = assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse(query.toString()));
		assertTrue(refused.getMessage().contains("nested"), refused.getMessage());
	}

	@Test
	public void testParametersReachTheBody() throws Exception {
		try (GraphStore store = people()) {
			assertEquals(List.of("dan"), Cypher.query(store, "MATCH (p:P) WHERE EXISTS { MATCH (q:P) WHERE "
				+ "q.name = p.name AND q.age > $min } RETURN p.name AS n", Map.of("min", 45)).column("n"));
		}
	}
}
