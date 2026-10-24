package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.StaleIdException;

/**
 * End to end behaviour of the engine.
 *
 * @see <a href="../../../../../../../../spec/API.md">spec/API.md</a>
 */
@Tag("unit")
public class GraphStoreTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testCreateAndReadNode() throws Exception {
		long id;
		try (GraphStore store = stores.open()) {
			id = store.createNode("Person", Properties.of()
				.set("name", "Wes Anderson")
				.set("born", 1969)
				.set("active", true));

			assertTrue(store.nodeExists(id));
			assertEquals("Person", store.nodeLabel(id));
			Properties props = store.nodeProperties(id);
			assertEquals("Wes Anderson", props.getString("name"));
			assertEquals(1969, props.getInt("born"));
			assertTrue(props.getBoolean("active"));
			store.checkpoint();
		}

		// The same id must resolve after a reopen: nothing about it depends on process state.
		try (GraphStore store = stores.open()) {
			assertTrue(store.nodeExists(id));
			assertEquals("Person", store.nodeLabel(id));
			assertEquals("Wes Anderson", store.nodeProperties(id).getString("name"));
		}
	}

	@Test
	public void testLightweightEdgeAndTraversal() throws Exception {
		try (GraphStore store = stores.open()) {
			long person = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));
			long vehicle = store.createNode("Vehicle", Properties.of().set("name", "VW Beetle"));
			store.createEdge(person, "OWNS", vehicle);

			List<Edge> out = store.neighbours(person, Direction.OUT);
			assertEquals(1, out.size());
			assertEquals(vehicle, out.get(0).neighbourId());
			assertEquals("OWNS", out.get(0).type());
			assertFalse(out.get(0).isMaterialised(), "A property free edge needs no relationship record");

			// Both endpoints are linked, so the reverse direction works too.
			List<Edge> in = store.neighbours(vehicle, Direction.IN);
			assertEquals(1, in.size());
			assertEquals(person, in.get(0).neighbourId());

			assertEquals(0, store.neighbours(person, Direction.IN).size());
			assertEquals(1, store.degreeOut(person));
			assertEquals(1, store.degreeIn(vehicle));
		}
	}

	@Test
	public void testMaterialisedRelationship() throws Exception {
		long relId;
		long person;
		long vehicle;
		try (GraphStore store = stores.open()) {
			person = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));
			vehicle = store.createNode("Vehicle", Properties.of().set("name", "VW Beetle"));
			relId = store.createRelationship(person, "DROVE", vehicle,
				Properties.of().set("since", 2019).set("note", "relName"));

			assertTrue(store.relationshipExists(relId));
			assertEquals("DROVE", store.relationshipType(relId));
			assertEquals(person, store.relationshipFrom(relId));
			assertEquals(vehicle, store.relationshipTo(relId));
			assertEquals("relName", store.relationshipProperties(relId).getString("note"));

			List<Edge> out = store.neighbours(person, Direction.OUT);
			assertEquals(1, out.size());
			assertTrue(out.get(0).isMaterialised());
			assertEquals(relId, out.get(0).relationshipId());
			assertEquals(vehicle, out.get(0).neighbourId());

			List<Edge> in = store.neighbours(vehicle, Direction.IN);
			assertEquals(1, in.size());
			assertEquals(person, in.get(0).neighbourId());
			store.checkpoint();
		}

		try (GraphStore store = stores.open()) {
			assertEquals("DROVE", store.relationshipType(relId));
			assertEquals(person, store.relationshipFrom(relId));
			assertEquals(vehicle, store.relationshipTo(relId));
		}
	}

	@Test
	public void testTypeFiltering() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("A");
			long b = store.createNode("B");
			long c = store.createNode("C");
			store.createEdge(a, "OWNS", b);
			store.createEdge(a, "LIKES", c);

			assertEquals(2, store.neighbours(a, Direction.OUT).size());
			assertEquals(1, store.neighbours(a, Direction.OUT, Set.of("OWNS")).size());
			assertEquals(b, store.neighbours(a, Direction.OUT, Set.of("OWNS")).get(0).neighbourId());
			assertEquals(0, store.neighbours(a, Direction.OUT, Set.of("NOPE")).size());
		}
	}

	@Test
	public void testBreadthFirstTraversal() throws Exception {
		try (GraphStore store = stores.open()) {
			long root = store.createNode("Root", Properties.of().set("name", "Root"));
			int width = 4;
			int depth = 2;
			buildTree(store, root, width, depth);

			// 4 children plus 4 x 4 grandchildren.
			assertEquals(width + width * width, store.traverse(root, Direction.OUT, null, depth).size());
			assertEquals(width, store.traverse(root, Direction.OUT, null, 1).size());
			assertEquals(0, store.traverse(root, Direction.OUT, null, 0).size());
		}
	}

	private void buildTree(GraphStore store, long parent, int width, int depth) throws Exception {
		if (depth <= 0) {
			return;
		}
		for (int i = 0; i < width; i++) {
			long child = store.createNode("Node", Properties.of().set("name", "n" + i + "_d" + depth));
			store.createEdge(parent, "HAS", child);
			buildTree(store, child, width, depth - 1);
		}
	}

	@Test
	public void testDeleteEdge() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("A");
			long b = store.createNode("B");
			store.createEdge(a, "OWNS", b);
			assertTrue(store.deleteEdge(a, "OWNS", b));
			assertEquals(0, store.neighbours(a, Direction.OUT).size());
			assertEquals(0, store.neighbours(b, Direction.IN).size(), "The mirror entry must go too");
			assertEquals(0, store.degreeOut(a));
			assertEquals(0, store.degreeIn(b));
			assertFalse(store.deleteEdge(a, "OWNS", b));
		}
	}

	@Test
	public void testDeleteNodeCascades() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("A", Properties.of().set("name", "a"));
			long b = store.createNode("B", Properties.of().set("name", "b"));
			long c = store.createNode("C", Properties.of().set("name", "c"));
			store.createEdge(a, "OWNS", b);
			long rel = store.createRelationship(c, "DROVE", a, Properties.of().set("k", "v"));

			store.deleteNode(a);

			assertFalse(store.nodeExists(a));
			assertFalse(store.relationshipExists(rel), "A relationship must not outlive its endpoint");
			assertEquals(0, store.neighbours(b, Direction.IN).size(), "No dangling adjacency may survive");
			assertEquals(0, store.neighbours(c, Direction.OUT).size());
			assertEquals(0, store.degreeIn(b));
			assertEquals(0, store.degreeOut(c));
			store.verify();
		}
	}

	@Test
	public void testStaleIdIsDetected() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("Person");
			store.deleteNode(id);
			assertFalse(store.nodeExists(id));
			assertThrows(StaleIdException.class, () -> store.nodeLabel(id));
		}
	}

	/**
	 * Labels and relationship types share one dictionary, and a packed adjacency entry carries only 14 bits of type. A
	 * type beyond that is still legal — it escalates to a materialised relationship whose entry does not cache the type
	 * (RELATIONSHIP_STORAGE.md §3) — rather than being refused once a store has seen 16,383 distinct names.
	 */
	@Test
	public void testATypeBeyondFourteenBitsEscalatesToAMaterialisedEdge() throws Exception {
		try (GraphStore store = stores.open()) {
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < io.metaloom.graph.core.store.adj.PackedEdge.MAX_TYPE; i++) {
					store.createNode("L" + i);
				}
				txn.commit();
			}
			long a = store.createNode("A");
			long b = store.createNode("B");

			long rel = store.createRelationship(a, "BIG", b, Properties.of().set("w", 1));
			assertEquals("BIG", store.relationshipType(rel));
			store.createEdge(a, "BIGGER", b);

			assertEquals(1, store.neighbours(a, Direction.OUT, Set.of("BIG")).size());
			List<Edge> bigger = store.neighbours(a, Direction.OUT, Set.of("BIGGER"));
			assertEquals(1, bigger.size(), "a property-free edge of an unpackable type is still an edge");
			assertEquals("BIGGER", bigger.get(0).type());
			assertEquals(b, bigger.get(0).neighbourId());
			assertEquals(1, store.neighbours(b, Direction.IN, Set.of("BIGGER")).size());
			assertEquals(2, store.degreeOut(a));
			assertEquals(2, store.degreeIn(b));

			assertTrue(store.deleteEdge(a, "BIGGER", b), "and deleteEdge finds it by endpoints and type");
			assertFalse(store.deleteEdge(a, "BIGGER", b));
			assertEquals(0, store.neighbours(a, Direction.OUT, Set.of("BIGGER")).size());
			assertEquals(1, store.degreeOut(a));
			assertEquals(1, store.degreeIn(b));
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}

	@Test
	public void testUnknownNodeIsRejected() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("A");
			long bogus = a + (1L << 12) * 5000;
			assertThrows(GraphStoreException.class, () -> store.createEdge(a, "OWNS", bogus));
		}
	}

	@Test
	public void testPropertyUpdate() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("Person", Properties.of().set("name", "First"));
			store.setNodeProperties(id, Properties.of().set("name", "Second").set("extra", 7));
			Properties props = store.nodeProperties(id);
			assertEquals("Second", props.getString("name"));
			assertEquals(7, props.getInt("extra"));
			assertEquals(2, props.size());
			store.verify();
		}
	}

	@Test
	public void testStoreIsLockedWhileOpen() throws Exception {
		try (GraphStore first = stores.open()) {
			assertNotNull(first);
			assertThrows(GraphStoreException.class, () -> stores.open().close());
		}
	}

	@Test
	public void testVerifyPassesOnAPopulatedStore() throws Exception {
		try (GraphStore store = stores.open()) {
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					long a = store.createNode("A", Properties.of().set("i", i));
					long b = store.createNode("B", Properties.of().set("i", i));
					store.createEdge(a, "OWNS", b);
					store.createRelationship(b, "BACK", a, Properties.of().set("w", i * 0.5));
				}
				txn.commit();
			}
			store.checkpoint();
			store.verify();
		}
	}
}
