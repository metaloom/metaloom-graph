package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.parse.CypherParser;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * {@code MERGE} — match the whole pattern, or build the whole pattern.
 *
 * <p>
 * The clause exists to make a script idempotent, so most of what is worth testing is what happens on the
 * <em>second</em> run rather than the first. A MERGE that quietly creates duplicates imports perfectly once and doubles
 * the graph afterwards, which is why {@link #testTheSameMergeTwiceCreatesOnce} and the corpus's whole-dump idempotence
 * check matter more here than any single-statement assertion.
 * </p>
 *
 * @see <a href="../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@Tag("unit")
public class MergeTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore people() throws Exception {
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			store.createNode("Person", Properties.of().set("name", "Ada").set("age", 36L));
			store.createNode("Person", Properties.of().set("name", "Alan"));
			store.createNode("City", Properties.of().set("name", "London"));
			txn.commit();
		}
		return store;
	}

	private static long countNodes(GraphStore store) {
		long[] count = { 0 };
		store.forEachNode(id -> count[0]++);
		return count[0];
	}

	private static long countRelationships(GraphStore store) {
		long[] count = { 0 };
		store.forEachNode(id -> count[0] += store.neighbours(id, io.metaloom.graph.core.api.Direction.OUT).size());
		return count[0];
	}

	// ---------------------------------------------------------------- match or create

	@Test
	public void testAPatternThatMatchesNothingIsCreated() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store,
				"MERGE (n:Person {name: 'Grace'}) RETURN n.name AS name");
			assertEquals(List.of("Grace"), result.column("name"));
			assertEquals(1, result.stats().nodesCreated());
			assertEquals(4, countNodes(store));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAPatternThatMatchesIsNotCreatedAgain() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MERGE (n:Person {name: 'Ada'}) RETURN n.age AS age");
			assertEquals(List.of(36L), result.column("age"), "the existing node, with the properties it already had");
			assertEquals(0, result.stats().nodesCreated());
			assertEquals(3, countNodes(store));
		}
	}

	@Test
	public void testAnInlineMapIsAnEqualityPredicateAndNotJustALabelCheck() throws Exception {
		try (GraphStore store = people()) {
			Cypher.query(store, "MERGE (n:Person {name: 'Grace'})");
			// Alan is a :Person and does not match {name:'Grace'}, so the merge above created rather than matched.
			assertEquals(4, countNodes(store));
			assertEquals(1, Cypher.query(store, "MATCH (n:Person {name:'Grace'}) RETURN n").size());
		}
	}

	@Test
	public void testAMergeThatMatchesSeveralNodesReturnsAllOfThem() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MERGE (n:Person) RETURN n.name AS name ORDER BY name");
			assertEquals(List.of("Ada", "Alan"), result.column("name"),
				"MERGE matched, so every match is a row — it does not pick one");
			assertEquals(0, result.stats().nodesCreated());
		}
	}

	/**
	 * The same MERGE twice in one query, which is the property the whole clause exists for.
	 *
	 * <p>
	 * It works because rows are processed in order and each match reads the store as it is <em>now</em>: the second row
	 * finds what the first created. That depends on a writer reading the current mapping rather than a fixed view, so
	 * this is as much a test of the transaction model as of the clause.
	 * </p>
	 */
	@Test
	public void testTheSameMergeTwiceCreatesOnce() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store,
				"UNWIND ['Grace', 'Grace', 'Grace'] AS name MERGE (n:Person {name: name}) RETURN count(n) AS rows");
			assertEquals(List.of(3L), result.column("rows"), "three rows in, three rows out");
			assertEquals(1, result.stats().nodesCreated(), "and one node created");
			assertEquals(4, countNodes(store));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** 36 and 36.0 are one Cypher value, so a MERGE spelling it the other way must find the node, not duplicate it. */
	@Test
	public void testMergeMatchesAnIntegerWrittenAsAFloat() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MERGE (n:Person {age: 36.0}) RETURN n.name AS name");
			assertEquals(List.of("Ada"), result.column("name"));
			assertEquals(0, result.stats().nodesCreated());
			assertEquals(3, countNodes(store));
		}
	}

	@Test
	public void testMergingAcrossTwoQueriesIsAlsoIdempotent() throws Exception {
		try (GraphStore store = people()) {
			Cypher.query(store, "MERGE (n:Person {name: 'Grace'})");
			CypherResult second = Cypher.query(store, "MERGE (n:Person {name: 'Grace'}) RETURN n");
			assertEquals(0, second.stats().nodesCreated());
			assertEquals(4, countNodes(store));
		}
	}

	// ---------------------------------------------------------------- the whole-pattern rule

	/**
	 * The rule that surprises people, asserted rather than merely documented.
	 *
	 * <p>
	 * MERGE has no notion of matching <em>part</em> of a pattern. With an {@code :A} and a {@code :B} in the store but
	 * no relationship between them, the pattern as a whole does not match — so a second {@code :A} and a second
	 * {@code :B} are created along with the relationship. This is correct openCypher and a classic way to double a
	 * graph by accident.
	 * </p>
	 */
	@Test
	public void testAPatternMatchesEntirelyOrIsBuiltEntirely() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("A", Properties.of());
				store.createNode("B", Properties.of());
				txn.commit();
			}
			CypherResult result = Cypher.query(store, "MERGE (a:A)-[:R]->(b:B) RETURN a");
			assertEquals(2, result.stats().nodesCreated(),
				"the relationship was missing, so the whole pattern was built");
			assertEquals(1, result.stats().relationshipsCreated());
			assertEquals(4, countNodes(store));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** And the way to say what was almost certainly meant: bind the endpoints first. */
	@Test
	public void testBindingTheEndpointsFirstMergesOnlyTheRelationship() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("A", Properties.of());
				store.createNode("B", Properties.of());
				txn.commit();
			}
			CypherResult result = Cypher.query(store, "MATCH (a:A) MATCH (b:B) MERGE (a)-[:R]->(b) RETURN a");
			assertEquals(0, result.stats().nodesCreated());
			assertEquals(1, result.stats().relationshipsCreated());
			assertEquals(2, countNodes(store));

			// And again: now the relationship exists, so there is nothing left to do.
			CypherResult again = Cypher.query(store, "MATCH (a:A) MATCH (b:B) MERGE (a)-[:R]->(b) RETURN a");
			assertEquals(0, again.stats().relationshipsCreated());
			assertEquals(1, countRelationships(store));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAMergedRelationshipIsMaterialisedAndCanBeGivenProperties() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("A", Properties.of());
				store.createNode("B", Properties.of());
				txn.commit();
			}
			CypherResult result = Cypher.query(store,
				"MATCH (a:A) MATCH (b:B) MERGE (a)-[r:R]->(b) ON CREATE SET r.weight = 7 RETURN r");
			CypherRelationship relationship = result.row(0).relationship("r");
			assertNotNull(relationship.id(), "MERGE materialises, exactly as CREATE does, so the edge has an id");
			assertEquals(7L, Cypher.query(store, "MATCH ()-[r:R]->() RETURN r.weight AS w").row(0).get("w"));
		}
	}

	/**
	 * An undirected MERGE, which is a question rather than an omission.
	 *
	 * <p>
	 * {@code MERGE (a)-[:R]-(b)} asks whether an edge exists <em>either way</em>; only if none does is one created, and
	 * openCypher creates it left to right. CREATE has no such question to ask, which is why an arrowless relationship
	 * is refused there and accepted here.
	 * </p>
	 */
	@Test
	public void testAnUndirectedMergeMatchesEitherWayAndCreatesLeftToRight() throws Exception {
		try (GraphStore store = stores.open()) {
			long[] ids = new long[2];
			try (Transaction txn = store.begin()) {
				ids[0] = store.createNode("A", Properties.of());
				ids[1] = store.createNode("B", Properties.of());
				// Pointing B to A, which an undirected pattern must find.
				store.createRelationship(ids[1], "R", ids[0], Properties.of());
				txn.commit();
			}
			CypherResult matched = Cypher.query(store, "MATCH (a:A) MATCH (b:B) MERGE (a)-[:R]-(b) RETURN a");
			assertEquals(0, matched.stats().relationshipsCreated(), "the edge exists, pointing the other way");

			try (GraphStore fresh = GraphStore.open(stores.path().resolve("undirected"), TempStore.options())) {
				try (Transaction txn = fresh.begin()) {
					fresh.createNode("A", Properties.of());
					fresh.createNode("B", Properties.of());
					txn.commit();
				}
				Cypher.query(fresh, "MATCH (a:A) MATCH (b:B) MERGE (a)-[:R]-(b)");
				assertEquals(1, Cypher.query(fresh, "MATCH (:A)-[r:R]->(:B) RETURN r").size(),
					"with nothing to match, the new relationship points left to right");
			}
		}
	}

	// ---------------------------------------------------------------- ON CREATE and ON MATCH

	@Test
	public void testOnCreateRunsOnlyForACreatedPattern() throws Exception {
		try (GraphStore store = people()) {
			Cypher.query(store, "MERGE (n:Person {name: 'Ada'}) ON CREATE SET n.age = 99");
			assertEquals(36L, Cypher.query(store, "MATCH (n:Person {name:'Ada'}) RETURN n.age AS age").row(0).get("age"),
				"Ada was matched, so ON CREATE must not have run");

			Cypher.query(store, "MERGE (n:Person {name: 'Grace'}) ON CREATE SET n.age = 45");
			assertEquals(45L,
				Cypher.query(store, "MATCH (n:Person {name:'Grace'}) RETURN n.age AS age").row(0).get("age"));
		}
	}

	@Test
	public void testOnMatchRunsOnlyForAMatchedPattern() throws Exception {
		try (GraphStore store = people()) {
			Cypher.query(store, "MERGE (n:Person {name: 'Ada'}) ON MATCH SET n.seen = true");
			assertEquals(true,
				Cypher.query(store, "MATCH (n:Person {name:'Ada'}) RETURN n.seen AS seen").row(0).get("seen"));

			Cypher.query(store, "MERGE (n:Person {name: 'Grace'}) ON MATCH SET n.seen = true");
			assertEquals(null,
				Cypher.query(store, "MATCH (n:Person {name:'Grace'}) RETURN n.seen AS seen").row(0).get("seen"),
				"Grace was created, so ON MATCH must not have run");
		}
	}

	@Test
	public void testBothBranchesOnTheSameClause() throws Exception {
		try (GraphStore store = people()) {
			String merge = "MERGE (n:Person {name: 'Grace'}) "
				+ "ON CREATE SET n.origin = 'created', n.visits = 1 "
				+ "ON MATCH SET n.origin = 'matched' "
				+ "RETURN n.origin AS origin, n.visits AS visits";

			CypherResult first = Cypher.query(store, merge);
			assertEquals(List.of("created"), first.column("origin"));
			assertEquals(List.of(1L), first.column("visits"), "several assignments in one ON CREATE all apply");

			CypherResult second = Cypher.query(store, merge);
			assertEquals(List.of("matched"), second.column("origin"));
			assertEquals(List.of(1L), second.column("visits"), "and ON CREATE left the earlier value alone");
		}
	}

	/** The row a MERGE emits shows what the branch just wrote, not what the element was when it was bound. */
	@Test
	public void testTheReturnedNodeShowsWhatTheBranchWrote() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store,
				"MERGE (n:Person {name: 'Ada'}) ON MATCH SET n.age = 37 RETURN n.age AS age");
			assertEquals(List.of(37L), result.column("age"), "the binding was refreshed after the write");
		}
	}

	@Test
	public void testAMergedVariableIsVisibleToLaterClauses() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store,
				"MERGE (c:City {name: 'London'}) MERGE (p:Person {name: 'Grace'}) "
					+ "MERGE (p)-[:LIVES_IN]->(c) RETURN c.name AS city");
			assertEquals(List.of("London"), result.column("city"));
			assertEquals(1, result.stats().nodesCreated(), "London existed; Grace did not");
			assertEquals(1, result.stats().relationshipsCreated());
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- shape and refusals

	@Test
	public void testMergeCountsAsAWriteBeforeItRuns() {
		assertTrue(CypherParser.parse("MERGE (n:Person {name: 'Ada'})").isWrite(),
			"the servers refuse a write on the event loop by asking the tree, so this has to be true without "
				+ "executing anything");
		assertTrue(CypherParser.parse("MATCH (n) MERGE (m:Copy) RETURN m").isWrite());
	}

	@Test
	public void testSeveralPatternsAreRefusedWithTheirOwnMessage() {
		CypherSyntaxException thrown = assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MERGE (a:A), (b:B)"));
		assertTrue(thrown.getMessage().contains("MERGE takes one pattern"), thrown.getMessage());
	}

	@Test
	public void testAPatternThatCannotBeBuiltIsRefused() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MATCH (a) MERGE (a)-[:R*1..3]->(b)")).getMessage()
				.contains("variable-length"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MATCH (a) MERGE (a)-[r]->(b)")).getMessage()
				.contains("needs a type on every relationship"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MATCH (a) MERGE (a)-[:R|S]->(b)")).getMessage()
				.contains("one type rather than the choice"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MERGE p = shortestPath((a)-[:R*..5]-(b))")).getMessage()
				.contains("shortest-path pattern"));
	}

	@Test
	public void testOnNeedsCreateOrMatchAndThenSet() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MERGE (a:A) ON DELETE SET a.x = 1")).getMessage()
				.contains("Expected CREATE or MATCH after ON"));
		assertThrows(CypherSyntaxException.class, () -> CypherParser.parse("MERGE (a:A) ON CREATE a.x = 1"));
	}

	@Test
	public void testAMergeQueryNeedsNoReturn() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MERGE (n:Person {name: 'Grace'})");
			assertTrue(result.isEmpty(), "no RETURN, so no rows — the result is what it changed");
			assertEquals(1, result.stats().nodesCreated());
			assertTrue(result.stats().containsUpdates());
		}
	}
}
