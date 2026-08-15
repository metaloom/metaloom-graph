package io.metaloom.graph.core.txn;

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
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.StaleIdException;

/**
 * The visibility matrix of [TRANSACTIONS.md §9](../../../../../../../../spec/TRANSACTIONS.md): for each mutation kind,
 * a view opened before it must not see it, and a view opened after it must.
 *
 * <p>
 * The cases are deliberately spread across every kind of state the engine holds — record fields, property blocks,
 * adjacency, degree counters, the free-slot bitmap and the secondary indexes — because those are stored in six
 * different structures and the whole claim of the page-versioning design is that <b>one</b> mechanism covers all of
 * them. A test that only checked node properties would pass just as well against a design that only versioned nodes.
 * </p>
 */
@Tag("unit")
public class VisibilityTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testACreateIsInvisibleToAnEarlierView() throws Exception {
		try (GraphStore store = stores.open()) {
			long before = store.createNode("Before");

			try (ReadTransaction view = store.beginRead()) {
				assertTrue(store.nodeExists(before), "the view must see what existed when it began");

				long after = store.createNode("After");

				assertFalse(store.nodeExists(after), "a node created after the view began must be invisible to it");
				assertTrue(store.nodeExists(before), "and the rest of the store must be unchanged");
				assertEquals(1, store.nodesByLabel("Before").size());
				assertEquals(0, store.nodesByLabel("After").size(),
					"the label index must be read as of the view too, not as it stands now");
			}

			assertEquals(1, store.nodesByLabel("After").size(), "and current reads see it once the view is closed");
		}
	}

	@Test
	public void testAnUpdateIsInvisibleToAnEarlierView() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("name", "before").set("age", 30L));

			try (ReadTransaction view = store.beginRead()) {
				store.setNodeProperties(node, Properties.of().set("name", "after").set("age", 31L));

				assertEquals("before", store.nodeProperty(node, "name"), "the view must see the value it began with");
				assertEquals(30L, store.nodeProperty(node, "age"));
				assertEquals("before", store.nodeProperties(node).get("name"),
					"reading the whole block must agree with reading one key");
			}

			assertEquals("after", store.nodeProperty(node, "name"));
		}
	}

	@Test
	public void testADeleteIsInvisibleToAnEarlierView() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Doomed", Properties.of().set("k", "v"));

			try (ReadTransaction view = store.beginRead()) {
				store.deleteNode(node);

				assertTrue(store.nodeExists(node), "a node deleted after the view began must still exist for it");
				assertEquals("Doomed", store.nodeLabel(node));
				assertEquals("v", store.nodeProperty(node, "k"),
					"its properties must survive too — the block is a different segment kind");
			}

			assertFalse(store.nodeExists(node));
		}
	}

	/**
	 * The case that separates page versioning from record versioning.
	 *
	 * <p>
	 * A delete frees the slot, which clears a bit in the free-slot bitmap — a structure with no {@code xmin} field and
	 * no version pointer. If only records were versioned, the old view would resolve the id, find the bitmap saying
	 * "not allocated" and raise {@link StaleIdException} on a node it is entitled to see.
	 * </p>
	 */
	@Test
	public void testAFreedSlotIsStillAllocatedForAnEarlierView() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Doomed");

			try (ReadTransaction view = store.beginRead()) {
				store.deleteNode(node);
				// Force the slot to be handed out again, which advances the generation in place.
				for (int i = 0; i < 8; i++) {
					store.createNode("Replacement");
				}

				assertTrue(store.nodeExists(node), "the bitmap must be read as of the view");
				assertEquals("Doomed", store.nodeLabel(node),
					"and the record itself, whose generation has since been advanced and whose bytes were reused");
			}
		}
	}

	@Test
	public void testAnEdgeIsInvisibleToAnEarlierView() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("A");
			long b = store.createNode("B");
			long c = store.createNode("C");
			store.createEdge(a, "KNOWS", b);

			try (ReadTransaction view = store.beginRead()) {
				store.createEdge(a, "KNOWS", c);

				List<Edge> edges = store.neighbours(a, Direction.OUT);
				assertEquals(1, edges.size(), "the view must see one edge, not the one added underneath it");
				assertEquals(b, edges.get(0).neighbourId());
				assertEquals(1, store.degreeOut(a), "the degree counter lives in the node record and must agree");
			}

			assertEquals(2, store.neighbours(a, Direction.OUT).size());
		}
	}

	/**
	 * Adjacency at a degree that forces a block, so the view is reading an {@code ADJ} segment page rather than the two
	 * inline slots in the node record.
	 */
	@Test
	public void testBlockAdjacencyIsVersioned() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub = store.createNode("Hub");
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					store.createEdge(hub, "LINK", store.createNode("Leaf"));
				}
				txn.commit();
			}

			try (ReadTransaction view = store.beginRead()) {
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < 200; i++) {
						store.createEdge(hub, "LINK", store.createNode("Leaf"));
					}
					txn.commit();
				}

				assertEquals(200, store.neighbours(hub, Direction.OUT).size(),
					"the adjacency block must be reconstructed as of the view");
				assertEquals(200, store.degreeOut(hub));
			}

			assertEquals(400, store.neighbours(hub, Direction.OUT).size());
		}
	}

	@Test
	public void testTraversalIsStableAcrossManyCommits() throws Exception {
		try (GraphStore store = stores.open()) {
			long root = store.createNode("Root");
			long middle = store.createNode("Middle");
			long leaf = store.createNode("Leaf");
			store.createEdge(root, "TO", middle);
			store.createEdge(middle, "TO", leaf);

			try (ReadTransaction view = store.beginRead()) {
				List<Edge> first = store.traverse(root, Direction.OUT, Set.of("TO"), 5);
				assertEquals(2, first.size());

				for (int i = 0; i < 50; i++) {
					store.createEdge(leaf, "TO", store.createNode("Extra"));
				}

				List<Edge> second = store.traverse(root, Direction.OUT, Set.of("TO"), 5);
				assertEquals(first, second, "the same traversal inside one view must return the same answer");
			}

			assertEquals(52, store.traverse(root, Direction.OUT, Set.of("TO"), 5).size());
		}
	}

	@Test
	public void testReadCommittedSeesEveryCommit() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("name", "before"));

			try (ReadTransaction view = store.beginRead(IsolationLevel.READ_COMMITTED)) {
				assertEquals(IsolationLevel.READ_COMMITTED, view.level());
				assertEquals("before", store.nodeProperty(node, "name"));

				store.setNodeProperties(node, Properties.of().set("name", "after"));

				assertEquals("after", store.nodeProperty(node, "name"),
					"read committed is defined by seeing the latest commit, which is the point of choosing it");
				assertEquals(0, store.snapshotStats().retainedBytes(),
					"and it must cost nothing, because nothing has to be kept for it");
			}
		}
	}

	@Test
	public void testAWriterSeesItsOwnUncommittedWrites() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("name", "before"));

			try (ReadTransaction view = store.beginRead()) {
				try (Transaction txn = store.begin()) {
					store.setNodeProperties(node, Properties.of().set("name", "after"));
					assertEquals("after", store.nodeProperty(node, "name"),
						"read-your-writes outranks the snapshot on the writing thread");
					txn.rollback();
				}
				assertEquals("before", store.nodeProperty(node, "name"));
			}
		}
	}

	@Test
	public void testAViewOpenedAfterTheCommitSeesIt() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("name", "before"));
			store.setNodeProperties(node, Properties.of().set("name", "after"));

			try (ReadTransaction view = store.beginRead()) {
				assertEquals("after", store.nodeProperty(node, "name"));
				assertEquals(0, store.snapshotStats().retainedBytes(),
					"a view that has nothing to undo retains nothing");
			}
		}
	}

	@Test
	public void testNestedViewsAreRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			try (ReadTransaction view = store.beginRead()) {
				GraphStoreException e = assertThrows(GraphStoreException.class, store::beginRead);
				assertTrue(e.getMessage().contains("already open"), e.getMessage());
			}
			// And the refusal did not damage the holder: a fresh view still opens.
			try (ReadTransaction view = store.beginRead()) {
				assertNotNull(view);
			}
		}
	}

	@Test
	public void testAViewIsConfinedToTheThreadThatOpenedIt() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("k", "before"));
			try (ReadTransaction view = store.beginRead()) {
				store.setNodeProperties(node, Properties.of().set("k", "after"));
				assertEquals("before", store.nodeProperty(node, "k"));

				// Another thread has no view of its own, so it reads current data rather than borrowing this one.
				java.util.concurrent.atomic.AtomicReference<Object> seen = new java.util.concurrent.atomic.AtomicReference<>();
				Thread other = new Thread(() -> seen.set(store.nodeProperty(node, "k")));
				other.start();
				other.join();
				assertEquals("after", seen.get(), "a view must not leak onto a thread that did not open it");

				// And using the handle itself from elsewhere is refused rather than silently corrupting its cache.
				java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
				Thread stranger = new Thread(() -> ((Snapshot) view).checkAlive());
				stranger.setUncaughtExceptionHandler((t, e) -> thrown.set(e));
				stranger.start();
				stranger.join();
				assertNotNull(thrown.get(), "using a view from another thread must fail");
				assertTrue(thrown.get() instanceof GraphStoreException, String.valueOf(thrown.get()));
				assertTrue(thrown.get().getMessage().contains("cannot be used from"), thrown.get().getMessage());
			}
		}
	}

	@Test
	public void testClosingIsIdempotentAndReleasesRetention() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("name", "before"));

			ReadTransaction view = store.beginRead();
			store.setNodeProperties(node, Properties.of().set("name", "after"));
			assertEquals("before", store.nodeProperty(node, "name"));
			assertTrue(store.snapshotStats().retainedBytes() > 0, "an old view must be holding something");

			view.close();
			view.close();
			assertFalse(view.isOpen());
			assertEquals(0, store.snapshotStats().openViews());

			// Retention is released by the next commit, which is when vacuum runs.
			store.createNode("Trigger");
			assertEquals(0, store.snapshotStats().retainedBytes(), "a closed view must not hold memory");
			assertEquals("after", store.nodeProperty(node, "name"));
		}
	}
}
