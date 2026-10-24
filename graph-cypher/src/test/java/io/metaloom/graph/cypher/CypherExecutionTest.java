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
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * Each clause of the accepted subset, end to end against a real store.
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@Tag("unit")
public class CypherExecutionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;
	private long alice;
	private long bob;
	private long carol;

	@BeforeEach
	public void seed() throws Exception {
		store = stores.open();
		try (Transaction txn = store.begin()) {
			alice = store.createNode(List.of("Person", "Employee"),
				Properties.of().set("name", "Alice").set("age", 34L).set("city", "Bonn"));
			bob = store.createNode(List.of("Person"),
				Properties.of().set("name", "Bob").set("age", 28L).set("city", "Bonn"));
			carol = store.createNode(List.of("Person"),
				Properties.of().set("name", "Carol").set("city", "Berlin"));
			long widget = store.createNode(List.of("Product"), Properties.of().set("name", "Widget"));

			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L));
			store.createEdge(bob, "KNOWS", carol);
			store.createEdge(alice, "BOUGHT", widget);
			txn.commit();
		}
	}

	private CypherResult run(String query) {
		return Cypher.query(store, query);
	}

	private CypherResult run(String query, Map<String, Object> parameters) {
		return Cypher.query(store, query, parameters);
	}

	// ---------------------------------------------------------------- MATCH and RETURN

	@Test
	public void testMatchByLabel() throws Exception {
		CypherResult result = run("MATCH (n:Person) RETURN n.name ORDER BY n.name");
		assertEquals(List.of("n.name"), result.columns(), "an unaliased column is named after its source text");
		assertEquals(List.of("Alice", "Bob", "Carol"), result.column("n.name"));
		store.close();
	}

	@Test
	public void testMatchEverything() throws Exception {
		assertEquals(4, run("MATCH (n) RETURN n").size(), "no label means a full scan, including the Product");
		store.close();
	}

	@Test
	public void testASecondLabelIsMatchable() throws Exception {
		assertEquals(List.of("Alice"), run("MATCH (n:Employee) RETURN n.name").column("n.name"));
		assertEquals(List.of("Alice"),
			run("MATCH (n:Person:Employee) RETURN n.name").column("n.name"),
			"both labels must be required, not either");
		store.close();
	}

	@Test
	public void testReturnsNodesNotIds() throws Exception {
		CypherNode node = run("MATCH (n:Product) RETURN n").row(0).node("n");
		assertEquals(List.of("Product"), node.labels());
		assertEquals("Widget", node.property("name"));
		assertTrue(node.id() != 0);
		store.close();
	}

	@Test
	public void testInlinePropertyPattern() throws Exception {
		assertEquals(List.of("Alice"),
			run("MATCH (n:Person {city: 'Bonn', name: 'Alice'}) RETURN n.name").column("n.name"));
		assertEquals(0, run("MATCH (n {name: 'Nobody'}) RETURN n").size());
		store.close();
	}

	@Test
	public void testWhereWithParameters() throws Exception {
		CypherResult result = run("MATCH (n:Person) WHERE n.age >= $min RETURN n.name ORDER BY n.name",
			Map.of("min", 30));
		assertEquals(List.of("Alice"), result.column("n.name"));
		store.close();
	}

	@Test
	public void testAMissingParameterIsNamed() throws Exception {
		CypherTypeException e = assertThrows(CypherTypeException.class,
			() -> run("MATCH (n) WHERE n.age > $missing RETURN n"));
		assertTrue(e.getMessage().contains("$missing"), e.getMessage());
		store.close();
	}

	// ---------------------------------------------------------------- relationships

	@Test
	public void testExpandOutgoing() throws Exception {
		CypherResult result = run("MATCH (a:Person {name:'Alice'})-[:KNOWS]->(b) RETURN b.name");
		assertEquals(List.of("Bob"), result.column("b.name"));
		store.close();
	}

	@Test
	public void testDirectionMatters() throws Exception {
		assertEquals(0, run("MATCH (a {name:'Bob'})-[:KNOWS]->(b {name:'Alice'}) RETURN b").size());
		assertEquals(1, run("MATCH (a {name:'Bob'})<-[:KNOWS]-(b {name:'Alice'}) RETURN b").size());
		assertEquals(2, run("MATCH (a {name:'Bob'})-[:KNOWS]-(b) RETURN b").size(),
			"an undirected pattern sees both sides");
		store.close();
	}

	@Test
	public void testBindingARelationship() throws Exception {
		CypherResult result = run("MATCH ()-[r:KNOWS]->() RETURN r.since, type(r) ORDER BY r.since");
		assertEquals(2, result.size());
		// The lightweight edge has no properties, so its `since` is null and sorts last.
		assertEquals(java.util.Arrays.asList(2019L, null), result.column("r.since"));
		assertEquals(List.of("KNOWS", "KNOWS"), result.column("type(r)"));
		store.close();
	}

	@Test
	public void testALightweightEdgeHasNoId() throws Exception {
		CypherResult result = run("MATCH (a {name:'Bob'})-[r:KNOWS]->(b {name:'Carol'}) RETURN r, id(r)");
		CypherRelationship relationship = result.row(0).relationship("r");
		assertNull(relationship.id(), "a lightweight edge has no record and therefore no id");
		assertNull(result.row(0).get("id(r)"));
		assertEquals("KNOWS", relationship.type());
		assertTrue(relationship.properties().isEmpty());
		store.close();
	}

	@Test
	public void testMultipleTypesInOnePattern() throws Exception {
		CypherResult result = run("MATCH (a {name:'Alice'})-[:KNOWS|BOUGHT]->(b) RETURN b.name ORDER BY b.name");
		assertEquals(List.of("Bob", "Widget"), result.column("b.name"));
		store.close();
	}

	// ---------------------------------------------------------------- variable length

	@Test
	public void testVariableLengthPath() throws Exception {
		CypherResult result = run(
			"MATCH (a:Person {name:'Alice'})-[:KNOWS*1..2]->(b) RETURN b.name ORDER BY b.name");
		assertEquals(List.of("Bob", "Carol"), result.column("b.name"));
		store.close();
	}

	@Test
	public void testZeroLengthPathBindsTheStartNode() throws Exception {
		CypherResult result = run("MATCH (a:Person {name:'Alice'})-[:KNOWS*0..1]->(b) RETURN b.name ORDER BY b.name");
		assertEquals(List.of("Alice", "Bob"), result.column("b.name"),
			"a zero-length match reaches the start node itself");

		CypherResult paths = run("MATCH p = (a:Person {name:'Alice'})-[:KNOWS*0..1]->(b) "
			+ "RETURN length(p) AS hops, size(nodes(p)) AS nodes ORDER BY hops");
		assertEquals(List.of(0L, 1L), paths.column("hops"));
		assertEquals(List.of(1L, 2L), paths.column("nodes"), "a path of no hops holds its start node once");
		store.close();
	}

	@Test
	public void testBoundPath() throws Exception {
		CypherResult result = run("MATCH p = (a {name:'Alice'})-[:KNOWS*1..2]->(b {name:'Carol'}) RETURN p, length(p)");
		assertEquals(1, result.size());
		CypherPath path = result.row(0).path("p");
		assertEquals(2, path.length(), "two hops: Alice to Bob to Carol");
		assertEquals(3, path.nodes().size());
		assertEquals("Alice", path.start().property("name"));
		assertEquals("Carol", path.end().property("name"));
		assertEquals(2L, result.row(0).longValue("length(p)"));
		store.close();
	}

	// ---------------------------------------------------------------- WITH, UNWIND, aggregation

	@Test
	public void testAggregationGroupsByTheNonAggregates() throws Exception {
		CypherResult result = run("MATCH (n:Person) RETURN n.city, count(*) AS people ORDER BY n.city");
		assertEquals(List.of("Berlin", "Bonn"), result.column("n.city"));
		assertEquals(List.of(1L, 2L), result.column("people"));
		store.close();
	}

	@Test
	public void testCountStarCountsRowsAndCountOfAValueSkipsNulls() throws Exception {
		CypherResult result = run("MATCH (n:Person) RETURN count(*) AS rows, count(n.age) AS ages");
		assertEquals(3L, result.row(0).longValue("rows"));
		assertEquals(2L, result.row(0).longValue("ages"), "Carol has no age, so it is not counted");
		store.close();
	}

	@Test
	public void testAverageDividesByTheNonNullCount() throws Exception {
		CypherResult result = run("MATCH (n:Person) RETURN avg(n.age) AS mean, sum(n.age) AS total");
		assertEquals(31.0, result.row(0).doubleValue("mean"), 0.0001, "62 over 2, not over 3");
		assertEquals(62L, result.row(0).longValue("total"));
		store.close();
	}

	@Test
	public void testCollectAndDistinct() throws Exception {
		CypherResult result = run("MATCH (n:Person) RETURN collect(n.city) AS every, collect(DISTINCT n.city) AS uniq");
		assertEquals(3, ((List<?>) result.row(0).get("every")).size());
		assertEquals(2, ((List<?>) result.row(0).get("uniq")).size());
		store.close();
	}

	@Test
	public void testWithChainsAndFilters() throws Exception {
		CypherResult result = run("""
			MATCH (n:Person)
			WITH n.city AS city, count(*) AS people
			WHERE people > 1
			RETURN city, people
			""");
		assertEquals(List.of("Bonn"), result.column("city"));
		assertEquals(List.of(2L), result.column("people"));
		store.close();
	}

	@Test
	public void testUnwind() throws Exception {
		CypherResult result = run("UNWIND [3, 1, 2] AS x RETURN x ORDER BY x");
		assertEquals(List.of(3L, 1L, 2L).size(), result.size());
		assertEquals(List.of(1L, 2L, 3L), result.column("x"));
		store.close();
	}

	@Test
	public void testUnwindOfNullProducesNoRows() throws Exception {
		assertEquals(0, run("UNWIND $nothing AS x RETURN x",
			java.util.Collections.singletonMap("nothing", null)).size());
		store.close();
	}

	@Test
	public void testDistinctSkipLimitAndOrder() throws Exception {
		assertEquals(List.of("Berlin", "Bonn"),
			run("MATCH (n:Person) RETURN DISTINCT n.city ORDER BY n.city").column("n.city"));
		assertEquals(List.of("Bob"),
			run("MATCH (n:Person) RETURN n.name ORDER BY n.name SKIP 1 LIMIT 1").column("n.name"));
		store.close();
	}

	@Test
	public void testOrderByDescendingPutsNullsLast() throws Exception {
		CypherResult result = run("MATCH (n:Person) RETURN n.name, n.age ORDER BY n.age DESC");
		// Descending reverses the ascending order, in which null sorts last — so it leads here.
		assertEquals(List.of("Carol", "Alice", "Bob"), result.column("n.name"));
		store.close();
	}

	@Test
	public void testReturnStarNamesEveryBoundVariable() throws Exception {
		CypherResult result = run("MATCH (a:Person {name:'Alice'})-[r:KNOWS]->(b) RETURN *");
		assertEquals(List.of("a", "r", "b"), result.columns(),
			"and none of the anonymous bindings the matcher invents for ()");
		store.close();
	}

	@Test
	public void testUnionDeduplicatesAndUnionAllDoesNot() throws Exception {
		assertEquals(1, run("MATCH (n:Person {name:'Alice'}) RETURN n.name "
			+ "UNION MATCH (n:Employee) RETURN n.name").size());
		assertEquals(2, run("MATCH (n:Person {name:'Alice'}) RETURN n.name "
			+ "UNION ALL MATCH (n:Employee) RETURN n.name").size());
		store.close();
	}

	// ---------------------------------------------------------------- expressions and functions

	@Test
	public void testExpressionsAndFunctions() throws Exception {
		CypherResult result = run("""
			MATCH (n:Person {name:'Alice'})
			RETURN toUpper(n.name) AS shout,
			       n.age * 2 AS doubled,
			       labels(n) AS labels,
			       size(labels(n)) AS labelCount,
			       coalesce(n.nickname, n.name) AS display,
			       n.name STARTS WITH 'Al' AS prefixed
			""");
		assertEquals("ALICE", result.row(0).get("shout"));
		assertEquals(68L, result.row(0).longValue("doubled"));
		assertEquals(List.of("Person", "Employee"), result.row(0).get("labels"));
		assertEquals(2L, result.row(0).longValue("labelCount"));
		assertEquals("Alice", result.row(0).get("display"));
		assertEquals(Boolean.TRUE, result.row(0).get("prefixed"));
		store.close();
	}

	@Test
	public void testAnUnknownFunctionSaysWhatExists() throws Exception {
		CypherTypeException e = assertThrows(CypherTypeException.class,
			() -> run("MATCH (n) RETURN frobnicate(n)"));
		assertTrue(e.getMessage().contains("frobnicate"), e.getMessage());
		assertTrue(e.getMessage().contains("Available"), e.getMessage());
		store.close();
	}

	@Test
	public void testInAndListLiterals() throws Exception {
		assertEquals(List.of("Alice", "Bob"),
			run("MATCH (n:Person) WHERE n.city IN ['Bonn'] RETURN n.name ORDER BY n.name").column("n.name"));
		store.close();
	}

	@Test
	public void testIsNull() throws Exception {
		assertEquals(List.of("Carol"),
			run("MATCH (n:Person) WHERE n.age IS NULL RETURN n.name").column("n.name"));
		assertEquals(2, run("MATCH (n:Person) WHERE n.age IS NOT NULL RETURN n.name").size());
		store.close();
	}

	@Test
	public void testAggregateOutsideAProjectionIsRefused() throws Exception {
		CypherTypeException e = assertThrows(CypherTypeException.class,
			() -> run("MATCH (n) WHERE count(*) > 1 RETURN n"));
		assertTrue(e.getMessage().contains("aggregating"), e.getMessage());
		store.close();
	}

	@Test
	public void testAnAggregateInsideAnyExpression() throws Exception {
		CypherResult result = run("MATCH (n:Person) RETURN size(collect(n.name)) AS s, count(*) > 0 AS some, "
			+ "collect(n.name)[0] IS NOT NULL AS first, {c: count(*)} AS m, toString(count(*)) AS text");
		assertEquals(3L, result.row(0).get("s"));
		assertEquals(Boolean.TRUE, result.row(0).get("some"));
		assertEquals(Boolean.TRUE, result.row(0).get("first"));
		assertEquals(Map.of("c", 3L), result.row(0).get("m"));
		assertEquals("3", result.row(0).get("text"));

		CypherResult grouped = run("MATCH (n:Person) RETURN n.city AS city, size(collect(n.name)) AS s ORDER BY city");
		assertEquals(List.of("Berlin", "Bonn"), grouped.column("city"));
		assertEquals(List.of(1L, 2L), grouped.column("s"));
		store.close();
	}

	// ---------------------------------------------------------------- scoping and uniqueness

	/** WITH is a projection, so what it leaves out is out of scope afterwards, on both execution paths. */
	@Test
	public void testWithDropsWhatItDoesNotProject() throws Exception {
		String query = "MATCH (a:Person {name:'Alice'})-->(b) WITH a MATCH (a)-->(b) RETURN b.name";
		assertEquals(4, run(query).size(), "b is a fresh variable in the second MATCH, not a join on the first");
		assertEquals(4, run(query + " ORDER BY b.name").size(), "and the materialised path agrees");
		assertEquals(4L, run("MATCH (a:Person {name:'Alice'})-->(b) WITH a MATCH (a)-->(b) RETURN count(*) AS c")
			.row(0).get("c"));

		CypherTypeException e = assertThrows(CypherTypeException.class,
			() -> run("MATCH (a:Person {name:'Alice'})-->(b) WITH a RETURN b"));
		assertTrue(e.getMessage().contains("not defined"), e.getMessage());
		assertThrows(CypherTypeException.class,
			() -> run("MATCH (a:Person {name:'Alice'})-->(b) WITH a RETURN b ORDER BY a.name"));
		assertEquals(List.of("Alice", "Alice"),
			run("MATCH (a:Person {name:'Alice'})-->(b) WITH a ORDER BY b.name RETURN a.name").column("a.name"),
			"ORDER BY in a WITH may still read what the WITH is about to drop");
		store.close();
	}

	/** Relationship uniqueness spans the whole MATCH, not one comma-separated part of it. */
	@Test
	public void testOneRelationshipIsNotMatchedTwiceAcrossPatternParts() throws Exception {
		assertEquals(2L, run("MATCH (a)-[r1:KNOWS]->(b), (c)-[r2:KNOWS]->(d) RETURN count(*) AS c").row(0).get("c"),
			"two KNOWS edges make two ordered pairs of distinct edges, not four");
		assertEquals(2L, run("MATCH ()-[:KNOWS]->(), ()-[:KNOWS]->() RETURN count(*) AS c").row(0).get("c"),
			"and anonymous relationships count too");
		store.close();
	}

	@Test
	public void testABoundRelationshipVariableIsAJoin() throws Exception {
		CypherResult result = run("MATCH ()-[r:KNOWS]->() WITH r MATCH (a)-[r]->(b) RETURN a.name, b.name "
			+ "ORDER BY a.name");
		assertEquals(List.of("Alice", "Bob"), result.column("a.name"));
		assertEquals(List.of("Bob", "Carol"), result.column("b.name"));
		store.close();
	}

	@Test
	public void testStartNodeAndEndNodeAreNodes() throws Exception {
		CypherResult result = run("MATCH (a:Person {name:'Alice'})-[r:KNOWS]->(b) "
			+ "RETURN startNode(r) = a AS same, endNode(r).name AS far");
		assertEquals(Boolean.TRUE, result.row(0).get("same"));
		assertEquals("Bob", result.row(0).get("far"));
		assertEquals(List.of("Carol"),
			run("MATCH (:Person {name:'Bob'})-[r:KNOWS]->() RETURN endNode(r).name AS far").column("far"),
			"a lightweight edge has endpoints even though it has no id");
		store.close();
	}

	// ---------------------------------------------------------------- numbers

	/** The index is keyed on the stored type, and Cypher equality is not, so 34 and 34.0 must find the same node. */
	@Test
	public void testAnIntegralFloatFindsAnIntegerAndBack() throws Exception {
		try (Transaction txn = store.begin()) {
			store.createNode(List.of("Weighed"), Properties.of().set("kg", 30.0d));
			txn.commit();
		}
		assertEquals(List.of("Alice"), run("MATCH (n:Person {age: 34.0}) RETURN n.name").column("n.name"));
		assertEquals(List.of("Alice"), run("MATCH (n {age: 34.0}) RETURN n.name").column("n.name"));
		assertEquals(1, run("MATCH (n {kg: 30}) RETURN n").size());
		assertEquals(1, run("MATCH (n {kg: $kg}) RETURN n", Map.of("kg", 30L)).size());
		assertEquals(0, run("MATCH (n {age: 34.5}) RETURN n").size());
		store.close();
	}

	@Test
	public void testIntegerOverflowIsAnErrorNotAWrap() throws Exception {
		for (String expression : List.of("9223372036854775807 + 1", "-9223372036854775807 - 2",
			"9223372036854775807 * 2", "-(-9223372036854775807 - 1)", "(-9223372036854775807 - 1) / -1",
			"abs(-9223372036854775807 - 1)")) {
			assertThrows(CypherTypeException.class, () -> run("RETURN " + expression + " AS v"), expression);
		}
		assertThrows(CypherTypeException.class,
			() -> run("UNWIND [9223372036854775807, 1] AS x RETURN sum(x) AS s"));
		store.close();
	}

	@Test
	public void testHugeBoundsAreClampedRatherThanTruncated() throws Exception {
		assertEquals(List.of(2L, 3L),
			run("UNWIND [3, 1, 2] AS x RETURN x ORDER BY x SKIP 1 LIMIT 9223372036854775807").column("x"));
		assertEquals("bc", run("RETURN substring('abc', 1, 2147483647) AS v").row(0).get("v"));
		assertEquals("abc", run("RETURN substring('abc', 0, 4294967296) AS v").row(0).get("v"));
		assertThrows(CypherTypeException.class, () -> run("RETURN substring('abc', 4294967297) AS v"));
		store.close();
	}

	@Test
	public void testRangeAtTheEdgeOfTheIntegers() throws Exception {
		assertEquals(List.of(9223372036854775806L, 9223372036854775807L),
			run("RETURN range(9223372036854775806, 9223372036854775807) AS v").row(0).get("v"));
		assertEquals(List.of(-9223372036854775807L - 1),
			run("RETURN range(-9223372036854775807 - 1, -9223372036854775807 - 1, -5) AS v").row(0).get("v"));
		store.close();
	}

	/** The parser's depth limit is set so that whatever it accepts, the evaluator can walk. */
	@Test
	public void testTheDeepestAcceptedExpressionEvaluates() throws Exception {
		assertEquals(900L, run("RETURN 1" + " + 1".repeat(899) + " AS v").row(0).get("v"));
		assertEquals(1L, run("RETURN " + "(".repeat(190) + "1" + ")".repeat(190) + " AS v").row(0).get("v"));
		store.close();
	}

	@Test
	public void testAnEnormousRangeIsRefusedRatherThanBuilt() throws Exception {
		CypherTypeException e = assertThrows(CypherTypeException.class,
			() -> run("UNWIND range(1, 2000000000) AS x RETURN count(*) AS c"));
		assertTrue(e.getMessage().contains("range()"), e.getMessage());
		store.close();
	}

	// ---------------------------------------------------------------- equality and ordering

	@Test
	public void testGroupingUsesCypherEquality() throws Exception {
		CypherResult result = run("UNWIND [1, 1.0, 2] AS x RETURN x, count(*) AS c");
		assertEquals(2, result.size(), "1 and 1.0 are one group, as DISTINCT already says");
		assertEquals(List.of(2L, 1L), result.column("c"));
		assertEquals(2, run("UNWIND [1, 1.0, 2] AS x RETURN DISTINCT x").size());
		store.close();
	}

	@Test
	public void testFloatComparisonsAreIeeeNotATotalOrder() throws Exception {
		assertEquals(Boolean.FALSE, run("RETURN -0.0 < 0.0 AS v").row(0).get("v"));
		assertEquals(Boolean.TRUE, run("RETURN -0.0 <= 0.0 AS v").row(0).get("v"));
		assertEquals(Boolean.FALSE, run("RETURN 0.0 / 0.0 > 1 AS v").row(0).get("v"));
		assertEquals(Boolean.FALSE, run("RETURN 0.0 / 0.0 < 1 AS v").row(0).get("v"));
		assertEquals(Boolean.FALSE, run("RETURN 0.0 / 0.0 >= 0.0 / 0.0 AS v").row(0).get("v"));
		store.close();
	}
}
