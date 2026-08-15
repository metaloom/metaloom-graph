package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * The four write clauses, end to end against a real store.
 *
 * <p>
 * Every test finishes with {@code verify(FULL)}. The invariant checkers are an oracle entirely outside the query
 * layer — bidirectional edges, degree counters, no orphan property blocks, no dangling references — so a clause that
 * produces a plausible-looking result while leaving the store subtly wrong fails here rather than three phases later.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@Tag("unit")
public class CypherWriteTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;
	private long alice;
	private long bob;

	@BeforeEach
	public void seed() throws Exception {
		store = stores.open();
		try (Transaction txn = store.begin()) {
			alice = store.createNode(List.of("Person", "Employee"),
				Properties.of().set("name", "Alice").set("age", 34L));
			bob = store.createNode(List.of("Person"), Properties.of().set("name", "Bob"));
			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L));
			txn.commit();
		}
	}

	private CypherResult run(String query) {
		return Cypher.query(store, query);
	}

	private CypherResult run(String query, Map<String, Object> parameters) {
		return Cypher.query(store, query, parameters);
	}

	private void verify() throws Exception {
		store.verify(VerifyLevel.FULL);
		store.close();
	}

	// ---------------------------------------------------------------- CREATE

	@Test
	public void testCreateANode() throws Exception {
		CypherResult result = run("CREATE (n:Person:Author {name: 'Ada', born: 1815})");

		assertTrue(result.isEmpty(), "a query with no RETURN produces no rows");
		assertEquals(List.of(), result.columns());
		assertEquals(1, result.stats().nodesCreated());
		assertEquals(2, result.stats().labelsAdded());
		assertEquals(2, result.stats().propertiesSet());
		assertTrue(result.stats().containsUpdates());

		List<Long> found = store.nodesByLabel("Author");
		assertEquals(1, found.size(), "and it is indexed under every label, not just the first");
		assertEquals("Ada", store.nodeProperty(found.get(0), "name"));
		assertEquals(1815L, store.nodeProperty(found.get(0), "born"));
		assertEquals(List.of("Person", "Author"), store.nodeLabels(found.get(0)));
		verify();
	}

	@Test
	public void testCreateReturnsWhatItCreated() throws Exception {
		CypherResult result = run("CREATE (n:City {name: 'Bonn'}) RETURN n, n.name AS name");

		assertEquals(1, result.size());
		CypherNode node = result.row(0).node("n");
		assertEquals(List.of("City"), node.labels());
		assertEquals("Bonn", node.property("name"), "the returned value must carry what was written");
		assertEquals("Bonn", result.row(0).get("name"));
		assertTrue(store.nodeExists(node.id()));
		verify();
	}

	@Test
	public void testCreateARelationshipBetweenMatchedNodes() throws Exception {
		CypherResult result = run("MATCH (a:Person {name: 'Alice'}), (b:Person {name: 'Bob'}) "
			+ "CREATE (a)-[r:MANAGES {since: 2021}]->(b) RETURN r");

		assertEquals(1, result.stats().relationshipsCreated());
		assertEquals(1, result.stats().propertiesSet());
		assertEquals(0, result.stats().nodesCreated(), "both ends were already there");

		CypherRelationship created = result.row(0).relationship("r");
		assertTrue(created.isMaterialised(), "CREATE always makes a relationship with an id, never a lightweight edge");
		assertEquals("MANAGES", created.type());
		assertEquals(alice, created.startId());
		assertEquals(bob, created.endId());
		assertEquals(2021L, store.relationshipProperties(created.id()).getLong("since"));
		verify();
	}

	@Test
	public void testCreateRunsOncePerRow() throws Exception {
		CypherResult result = run("MATCH (p:Person) CREATE (p)-[:HAS_PROFILE]->(:Profile)");

		assertEquals(2, result.stats().nodesCreated(), "one profile per person");
		assertEquals(2, result.stats().relationshipsCreated());
		assertEquals(2, store.nodesByLabel("Profile").size());
		verify();
	}

	/**
	 * The eagerness property: a CREATE cannot feed the MATCH that drives it.
	 *
	 * <p>
	 * Other engines insert an explicit Eager operator to stop this from looping forever. Here it falls out of the
	 * executor materialising each clause, and this test is what says so.
	 * </p>
	 */
	@Test
	public void testCreateDoesNotFeedTheMatchDrivingIt() throws Exception {
		CypherResult result = run("MATCH (n:Person) CREATE (:Person {copy: true})");

		assertEquals(2, result.stats().nodesCreated(), "two people existed, so two copies — not an endless loop");
		assertEquals(4, store.nodesByLabel("Person").size());
		verify();
	}

	@Test
	public void testCreateASelfRelationshipAndAPath() throws Exception {
		CypherResult result = run("MATCH (a:Person {name: 'Alice'}) CREATE p = (a)-[:MENTORS]->(a) RETURN length(p)");

		assertEquals(1, result.stats().relationshipsCreated());
		assertEquals(1L, result.row(0).get("length(p)"));
		verify();
	}

	@Test
	public void testCreateTakesParameters() throws Exception {
		CypherResult result = run("CREATE (n:Person {name: $name, age: $age}) RETURN n.name AS name",
			Map.of("name", "Grace", "age", 45L));

		assertEquals("Grace", result.row(0).get("name"));
		assertEquals(45L, store.nodeProperty(store.nodesByProperty("name", "Grace").get(0), "age"));
		verify();
	}

	@Test
	public void testANullPropertyIsNotStoredAtAll() throws Exception {
		run("CREATE (n:Person {name: 'Nobody', age: null})");

		long node = store.nodesByProperty("name", "Nobody").get(0);
		assertFalse(store.nodeProperties(node).has("age"),
			"a null property is absent rather than present-and-null, because Cypher has no third state");
		verify();
	}

	@Test
	public void testADuplicateLabelInACreatePatternIsCountedOnce() throws Exception {
		CypherResult result = run("CREATE (n:Tag:Tag {name: 'once'})");

		assertEquals(1, result.stats().labelsAdded(), "the engine drops the duplicate, so the counter must too");
		assertEquals(List.of("Tag"), store.nodeLabels(store.nodesByProperty("name", "once").get(0)));
		verify();
	}

	@Test
	public void testCreateRefusesToRedescribeABoundVariable() throws Exception {
		CypherTypeException thrown = assertThrows(CypherTypeException.class,
			() -> run("MATCH (a:Person) CREATE (a:Robot)"));
		assertTrue(thrown.getMessage().contains("already bound"), thrown.getMessage());
		assertEquals(0, store.nodesByLabel("Robot").size());
		verify();
	}

	// ---------------------------------------------------------------- SET

	@Test
	public void testSetAProperty() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Alice'}) SET n.age = 35");

		assertEquals(1, result.stats().propertiesSet());
		assertEquals(35L, store.nodeProperty(alice, "age"));
		assertEquals("Alice", store.nodeProperty(alice, "name"), "the other properties must survive");
		verify();
	}

	/**
	 * The rebinding rule. Without it this returns the age the node had before the SET — a wrong answer that throws
	 * nothing, which is the whole reason the rule exists.
	 */
	@Test
	public void testAReturnedNodeShowsTheValueJustSet() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Alice'}) SET n.age = 35 RETURN n, n.age AS age");

		assertEquals(35L, result.row(0).get("age"), "the projection must see the new value");
		assertEquals(35L, result.row(0).node("n").property("age"), "and so must the node value itself");
		verify();
	}

	@Test
	public void testSetNullRemovesTheProperty() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Alice'}) SET n.age = null RETURN n");

		assertEquals(1, result.stats().propertiesSet(), "a removal is a change");
		assertFalse(store.nodeProperties(alice).has("age"));
		assertNull(result.row(0).node("n").property("age"));
		verify();
	}

	@Test
	public void testSetReplacesAndPlusEqualsMerges() throws Exception {
		run("MATCH (n:Person {name: 'Alice'}) SET n += {city: 'Bonn'}");
		assertEquals("Alice", store.nodeProperty(alice, "name"), "+= keeps what was there");
		assertEquals("Bonn", store.nodeProperty(alice, "city"));

		run("MATCH (n:Person {name: 'Alice'}) SET n = {name: 'Alice', role: 'Lead'}");
		assertEquals("Lead", store.nodeProperty(alice, "role"));
		assertFalse(store.nodeProperties(alice).has("city"), "= replaces the whole map");
		assertFalse(store.nodeProperties(alice).has("age"));
		verify();
	}

	@Test
	public void testSetSeveralPropertiesOfOneNode() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Bob'}) SET n.age = 28, n.city = 'Berlin'");

		assertEquals(2, result.stats().propertiesSet());
		assertEquals(28L, store.nodeProperty(bob, "age"));
		assertEquals("Berlin", store.nodeProperty(bob, "city"));
		verify();
	}

	@Test
	public void testWritingTheSameValueCountsAsNothing() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Alice'}) SET n.name = 'Alice'");

		assertEquals(0, result.stats().propertiesSet(), "nothing differs, so nothing was set");
		assertFalse(result.stats().containsUpdates());
		verify();
	}

	@Test
	public void testSetALabel() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Bob'}) SET n:Employee:Contractor RETURN n");

		assertEquals(2, result.stats().labelsAdded());
		assertEquals(List.of("Person", "Employee", "Contractor"), store.nodeLabels(bob));
		assertEquals(List.of("Person", "Employee", "Contractor"), result.row(0).node("n").labels(),
			"the returned node must carry the labels it was just given");
		assertEquals(2, store.nodesByLabel("Employee").size(), "and the index must have both");
		verify();
	}

	@Test
	public void testSetAPropertyOnARelationship() throws Exception {
		CypherResult result = run("MATCH ()-[r:KNOWS]->() SET r.since = 2020, r.strength = 'close' RETURN r");

		assertEquals(2, result.stats().propertiesSet());
		assertEquals(2020L, result.row(0).relationship("r").property("since"));
		assertEquals("close", result.row(0).relationship("r").property("strength"));
		verify();
	}

	@Test
	public void testSetOnALightweightEdgeSaysWhy() throws Exception {
		try (Transaction txn = store.begin()) {
			store.createEdge(alice, "LIKES", bob);
			txn.commit();
		}

		CypherTypeException thrown = assertThrows(CypherTypeException.class,
			() -> run("MATCH ()-[r:LIKES]->() SET r.when = 2024"));
		assertTrue(thrown.getMessage().contains("lightweight edge"), thrown.getMessage());
		verify();
	}

	@Test
	public void testAPropertyCannotHoldANode() throws Exception {
		CypherTypeException thrown = assertThrows(CypherTypeException.class,
			() -> run("MATCH (a:Person), (b:Person) SET a.friend = b"));
		assertTrue(thrown.getMessage().contains("must be a number"), thrown.getMessage());
		verify();
	}

	// ---------------------------------------------------------------- REMOVE

	@Test
	public void testRemoveAPropertyAndALabel() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Alice'}) REMOVE n.age, n:Employee RETURN n");

		assertEquals(1, result.stats().propertiesSet());
		assertEquals(1, result.stats().labelsRemoved());
		assertFalse(store.nodeProperties(alice).has("age"));
		assertEquals(List.of("Person"), store.nodeLabels(alice));
		assertEquals(List.of(), store.nodesByLabel("Employee"), "the index must lose the entry too");
		assertEquals(List.of("Person"), result.row(0).node("n").labels());
		verify();
	}

	@Test
	public void testRemovingWhatIsNotThereChangesNothing() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Bob'}) REMOVE n.nothing, n:Nowhere");

		assertFalse(result.stats().containsUpdates());
		verify();
	}

	// ---------------------------------------------------------------- DELETE

	@Test
	public void testDeleteARelationship() throws Exception {
		CypherResult result = run("MATCH ()-[r:KNOWS]->() DELETE r");

		assertEquals(1, result.stats().relationshipsDeleted());
		assertEquals(0, store.degreeOut(alice));
		assertEquals(0, store.degreeIn(bob));
		assertTrue(store.nodeExists(alice), "the endpoints stay");
		verify();
	}

	@Test
	public void testDeleteALightweightEdge() throws Exception {
		try (Transaction txn = store.begin()) {
			store.createEdge(alice, "LIKES", bob);
			txn.commit();
		}

		CypherResult result = run("MATCH ()-[r:LIKES]->() DELETE r");

		assertEquals(1, result.stats().relationshipsDeleted(), "an edge with no id is named by its endpoints");
		assertEquals(0, run("MATCH ()-[r:LIKES]->() RETURN r").size());
		verify();
	}

	@Test
	public void testDeleteANodeWithNoRelationships() throws Exception {
		long lonely;
		try (Transaction txn = store.begin()) {
			lonely = store.createNode("Ghost");
			txn.commit();
		}

		CypherResult result = run("MATCH (n:Ghost) DELETE n");

		assertEquals(1, result.stats().nodesDeleted());
		assertFalse(store.nodeExists(lonely));
		assertEquals(List.of(), store.nodesByLabel("Ghost"));
		verify();
	}

	@Test
	public void testDeletingAConnectedNodeIsRefusedAndNamesTheFix() throws Exception {
		CypherTypeException thrown = assertThrows(CypherTypeException.class,
			() -> run("MATCH (n:Person {name: 'Alice'}) DELETE n"));

		assertTrue(thrown.getMessage().contains("DETACH DELETE"), thrown.getMessage());
		assertTrue(store.nodeExists(alice), "and nothing was deleted");
		assertTrue(store.nodeExists(bob));
		verify();
	}

	@Test
	public void testDetachDeleteTakesTheRelationshipsWithIt() throws Exception {
		CypherResult result = run("MATCH (n:Person {name: 'Alice'}) DETACH DELETE n");

		assertEquals(1, result.stats().nodesDeleted());
		assertEquals(1, result.stats().relationshipsDeleted(), "the KNOWS edge went with it and is counted");
		assertFalse(store.nodeExists(alice));
		assertTrue(store.nodeExists(bob));
		assertEquals(0, store.degreeIn(bob), "and bob's side of the adjacency was repaired");
		verify();
	}

	/** Relationships are deleted before nodes within one clause, so the order they are written in does not matter. */
	@Test
	public void testDeletingANodeAndItsRelationshipInEitherOrder() throws Exception {
		run("MATCH (a:Person {name: 'Alice'})-[r:KNOWS]->() DELETE a, r");

		assertFalse(store.nodeExists(alice));
		assertTrue(store.nodeExists(bob));
		verify();
	}

	@Test
	public void testDeletingTwiceIsNotAnError() throws Exception {
		try (Transaction txn = store.begin()) {
			store.createNode("Ghost");
			store.createNode("Ghost");
			txn.commit();
		}

		CypherResult result = run("MATCH (a:Ghost), (b:Ghost) DELETE a, b");

		assertEquals(2, result.stats().nodesDeleted(), "four rows, two nodes, each deleted once");
		assertEquals(List.of(), store.nodesByLabel("Ghost"));
		verify();
	}

	@Test
	public void testWritingToADeletedNodeSaysSo() throws Exception {
		try (Transaction txn = store.begin()) {
			store.createNode("Ghost");
			txn.commit();
		}

		CypherTypeException thrown = assertThrows(CypherTypeException.class,
			() -> run("MATCH (n:Ghost) DELETE n SET n.x = 1"));
		assertTrue(thrown.getMessage().contains("already deleted"), thrown.getMessage());
		verify();
	}

	@Test
	public void testDeleteNullIsANoOp() throws Exception {
		CypherResult result = run("MATCH (n:Person) DELETE n.missing");

		assertFalse(result.stats().containsUpdates());
		assertTrue(store.nodeExists(alice));
		verify();
	}

	// ---------------------------------------------------------------- reads still work

	@Test
	public void testAReadQueryReportsNoUpdates() throws Exception {
		CypherResult result = run("MATCH (n:Person) RETURN n.name AS name ORDER BY name");

		assertEquals(List.of("Alice", "Bob"), result.column("name"));
		assertFalse(result.stats().containsUpdates());
		assertEquals(CypherStats.NONE, result.stats());
		verify();
	}
}
