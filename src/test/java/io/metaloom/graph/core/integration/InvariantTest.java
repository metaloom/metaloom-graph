package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The structural invariant checkers. A checker that never fires proves nothing, so each one is shown catching damage
 * that was deliberately introduced beneath the API.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 5
 */
@Tag("unit")
public class InvariantTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testAHealthyStorePasses() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				long previous = store.createNode("Root", Properties.of().set("i", 0));
				for (int i = 1; i < 400; i++) {
					long node = store.createNode("N", Properties.of().set("i", i));
					if (i % 3 == 0) {
						store.createRelationship(previous, "REL", node, Properties.of().set("w", i));
					} else {
						store.createEdge(previous, "EDGE", node);
					}
					previous = node;
				}
				txn.commit();
			}
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testDegreeDriftIsCaught() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long a = store.createNode("A");
			long b = store.createNode("B");
			store.createEdge(a, "T", b);
			store.verify(VerifyLevel.FULL);

			// Corrupt the counter beneath the API, as a bug in a mutation path would.
			try (Transaction txn = store.begin()) {
				impl.nodeStore().degreeDelta(a, 5, 0);
				txn.commit();
			}
			GraphStoreException failure = assertThrows(GraphStoreException.class,
				() -> store.verify(VerifyLevel.FULL));
			assertTrue(failure.getMessage().contains("degree_out"), failure.getMessage());
		}
	}

	@Test
	public void testMissingMirrorEntryIsCaught() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long a = store.createNode("A");
			long b = store.createNode("B");
			store.createEdge(a, "T", b);
			store.verify(VerifyLevel.FULL);

			// Remove only one side of the edge: exactly the bug the proof of concept shipped.
			try (Transaction txn = store.begin()) {
				int typeId = impl.catalog().lookupLabel("T");
				impl.adjacencyStore().remove(b,
					io.metaloom.graph.core.store.adj.PackedEdge.ofNeighbour(
						io.metaloom.graph.core.store.adj.PackedEdge.DIR_IN, typeId, a));
				impl.nodeStore().degreeDelta(b, 0, -1);
				txn.commit();
			}
			GraphStoreException failure = assertThrows(GraphStoreException.class,
				() -> store.verify(VerifyLevel.FULL));
			assertTrue(failure.getMessage().contains("mirror"), failure.getMessage());
		}
	}

	@Test
	public void testOrphanedPropertyBlockIsCaught() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long id = store.createNode("N", Properties.of().set("k", "v"));
			store.verify(VerifyLevel.FULL);

			// Drop the pointer without freeing the block: a leak the cascade rules exist to prevent.
			try (Transaction txn = store.begin()) {
				impl.nodeStore().propBlockId(id, io.metaloom.graph.core.id.ElementId.NULL);
				txn.commit();
			}
			GraphStoreException failure = assertThrows(GraphStoreException.class,
				() -> store.verify(VerifyLevel.FULL));
			assertTrue(failure.getMessage().contains("orphaned"), failure.getMessage());
		}
	}

	@Test
	public void testFullVerifyAfterHeavyChurn() throws Exception {
		try (GraphStore store = stores.open()) {
			long[] ids = new long[300];
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < ids.length; i++) {
					ids[i] = store.createNode("N", Properties.of().set("i", i));
				}
				for (int i = 1; i < ids.length; i++) {
					store.createEdge(ids[i - 1], "T", ids[i]);
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < ids.length; i += 3) {
					store.deleteNode(ids[i]);
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 100; i++) {
					store.createNode("Fresh", Properties.of().set("i", i));
				}
				txn.commit();
			}
			store.verify(VerifyLevel.FULL);
		}
	}
}
