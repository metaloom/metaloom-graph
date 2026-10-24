package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

	/**
	 * A long blob must verify, and its length must not be what decides.
	 *
	 * <p>
	 * The blob chain checker bounded its hop count at {@code segmentCount() * slotsPerPage() + 1}. A BLOB extent is
	 * page sized, so {@code slotsPerPage()} is 1 and the bound was 2 on a single-segment store: every blob past about
	 * 16 KiB was reported as a chain that does not terminate, with its remaining extents reported as orphaned. Healthy
	 * data, flagged as corruption. Nothing caught it because no test wrote a blob and then ran {@code verify(FULL)}.
	 * </p>
	 *
	 * <p>
	 * The sizes below straddle the old bound deliberately: 8 KiB fits in one extent and always passed, and 200 KiB
	 * needs twenty-odd and never could.
	 * </p>
	 */
	/** A node the label tree has lost is never returned by a label scan, and only a full verify can say so. */
	@Test
	public void testAMissingLabelEntryIsCaught() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long id = store.createNode("Person", Properties.of().set("k", "v"));
			store.verify(VerifyLevel.FULL);

			// The label entry goes and the record keeps the label: a hook that forgot one direction.
			try (Transaction txn = store.begin()) {
				int labelId = impl.catalog().lookupLabel("Person");
				impl.indexCatalog().tree().remove(impl.indexCatalog().labelRootForTest(), labelId, id);
				txn.commit();
			}
			GraphStoreException failure = assertThrows(GraphStoreException.class,
				() -> store.verify(VerifyLevel.FULL));
			assertTrue(failure.getMessage().contains("missing from the label index"), failure.getMessage());
		}
	}

	/** An entry under a value the node does not hold is the dangling entry a missed delete or update leaves. */
	@Test
	public void testADanglingPropertyEntryIsCaught() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long id = store.createNode("Person", Properties.of().set("k", "v"));
			store.verify(VerifyLevel.FULL);

			try (Transaction txn = store.begin()) {
				long keyId = impl.catalog().lookupKey("k");
				long root = impl.indexCatalog().propertyRootForTest();
				// The key id with a hash no stored value has — what the tree holds after the value changed under it.
				long stale = (keyId << 32) | 0x5EEDL;
				assertTrue(impl.indexCatalog().tree().put(root, stale, id, id) == root);
				txn.commit();
			}
			GraphStoreException failure = assertThrows(GraphStoreException.class,
				() -> store.verify(VerifyLevel.FULL));
			assertTrue(failure.getMessage().contains("Property index entry"), failure.getMessage());
		}
	}

	/** A vector index whose walk starts outside it answers from the wrong graph, and no single record shows that. */
	@Test
	public void testAVectorEntryPointOutsideItsIndexIsCaught() throws Exception {
		io.metaloom.graph.core.api.GraphStoreOptions options = io.metaloom.graph.core.api.GraphStoreOptions.builder()
			.pageSize(io.metaloom.graph.core.segment.Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(3)
			.build();
		try (GraphStore store = stores.open(options)) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			store.createNode("Doc", Properties.of().set("embedding", new float[] { 1f, 0f, 0f }));
			long outsider = store.createNode("Other", Properties.of().set("embedding", new float[] { 0f, 1f, 0f }));
			store.createVectorIndex(io.metaloom.graph.core.api.VectorIndexDefinition.of("Doc", "embedding", 3,
				io.metaloom.graph.core.api.VectorMetric.COSINE));
			store.verify(VerifyLevel.FULL);

			try (Transaction txn = store.begin()) {
				int keyId = impl.catalog().lookupKey("embedding");
				long vectorId = impl.propertyStore().ownedIdOf(impl.nodeStore().propBlockId(outsider), keyId);
				impl.indexCatalog().vectorEntryPoint(0, vectorId, 0);
				txn.commit();
			}
			GraphStoreException failure = assertThrows(GraphStoreException.class,
				() -> store.verify(VerifyLevel.FULL));
			assertTrue(failure.getMessage().contains("not a live member"), failure.getMessage());
		}
	}

	@Test
	public void testAMultiExtentBlobVerifies() throws Exception {
		try (GraphStore store = stores.open()) {
			for (int size : new int[] { 8_000, 40_000, 200_000 }) {
				// Random, therefore incompressible: a compressible payload would collapse to one extent and the chain
				// this is about would never be built.
				byte[] payload = new byte[size];
				new java.util.Random(size).nextBytes(payload);

				long node = store.createNode("Holder", Properties.of().set("data", payload));
				store.verify(VerifyLevel.FULL);
				assertArrayEquals(payload, (byte[]) store.nodeProperty(node, "data"),
					"the blob must also read back intact at " + size + " bytes");

				store.deleteNode(node);
				store.verify(VerifyLevel.FULL);
			}
		}
	}
}
