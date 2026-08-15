package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotStats;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * Reclamation: versions must be kept exactly as long as some open view needs them, and not one commit longer.
 *
 * <p>
 * Both halves matter and they fail in opposite directions. Reclaiming early is a correctness bug that surfaces as a
 * reader seeing the wrong data — the anomaly the whole mechanism exists to prevent. Reclaiming late is a memory leak
 * that surfaces as an out-of-memory error hours into a workload, blamed on something else.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 5.1
 */
@Tag("unit")
public class VacuumTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testNothingIsRetainedWithNoOpenView() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));
			for (int i = 1; i <= 200; i++) {
				store.setNodeProperties(node, Properties.of().set("n", (long) i));
			}

			SnapshotStats stats = store.snapshotStats();
			assertEquals(0, stats.retainedBytes(), "a store nobody is reading must retain nothing at all");
			assertEquals(0, stats.retainedEntries());
			assertEquals(0, stats.versionedPages());
			assertEquals(0, stats.openViews());
		}
	}

	@Test
	public void testReadCommittedViewsDoNotHoldBackReclamation() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));

			try (ReadTransaction view = store.beginRead(IsolationLevel.READ_COMMITTED)) {
				for (int i = 1; i <= 100; i++) {
					store.setNodeProperties(node, Properties.of().set("n", (long) i));
				}
				assertEquals(0, store.snapshotStats().retainedBytes(),
					"a view that does not fix what it reads asks nothing to be kept");
				assertEquals(0, store.snapshotStats().openViews(), "and does not count as an open view");
			}
		}
	}

	@Test
	public void testAnOpenViewHoldsVersionsAndClosingReleasesThem() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));

			long retainedWhileOpen;
			try (ReadTransaction view = store.beginRead()) {
				for (int i = 1; i <= 100; i++) {
					store.setNodeProperties(node, Properties.of().set("n", (long) i));
				}
				retainedWhileOpen = store.snapshotStats().retainedBytes();
				assertTrue(retainedWhileOpen > 0, "an open view must hold the versions it needs");
				assertEquals(1, store.snapshotStats().openViews());
				assertEquals(0L, store.nodeProperty(node, "n"), "and must still see what it began with");
			}

			// Reclamation runs at commit, so it takes one commit to observe.
			store.createNode("Trigger");
			assertEquals(0, store.snapshotStats().retainedBytes(), "closing the last view must release everything");
			assertEquals(100L, store.nodeProperty(node, "n"));
		}
	}

	@Test
	public void testTheOldestViewSetsTheHorizon() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));

			// Two views at different LSNs, on different threads because a view is thread-confined.
			try (ReadTransaction older = store.beginRead()) {
				store.setNodeProperties(node, Properties.of().set("n", 1L));

				long retainedForOlderOnly = store.snapshotStats().retainedBytes();
				assertTrue(retainedForOlderOnly > 0);

				Object[] seenByNewer = new Object[1];
				Thread newer = new Thread(() -> {
					try (ReadTransaction view = store.beginRead()) {
						seenByNewer[0] = store.nodeProperty(node, "n");
					}
				});
				newer.start();
				newer.join();

				assertEquals(1L, seenByNewer[0], "the newer view began after the update and must see it");
				assertEquals(0L, store.nodeProperty(node, "n"), "the older view must not");
			}
		}
	}

	@Test
	public void testAPinnedViewHoldsVersionsThatALaterViewDoesNotNeed() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));

			try (ReadTransaction pinned = store.beginRead()) {
				for (int i = 1; i <= 50; i++) {
					store.setNodeProperties(node, Properties.of().set("n", (long) i));
				}
				long retained = store.snapshotStats().retainedBytes();
				assertTrue(retained > 0);

				// A newer view opens and closes; the pinned one must keep everything it needs.
				Thread transient_ = new Thread(() -> {
					try (ReadTransaction view = store.beginRead()) {
						assertEquals(50L, store.nodeProperty(node, "n"));
					}
				});
				transient_.start();
				transient_.join();

				store.setNodeProperties(node, Properties.of().set("n", 51L));
				assertTrue(store.snapshotStats().retainedBytes() >= retained,
					"a short view closing must not reclaim what the pinned one still needs");
				assertEquals(0L, store.nodeProperty(node, "n"));
			}
		}
	}

	@Test
	public void testTheHorizonAdvancesToTheOldestOpenView() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));
			try (ReadTransaction view = store.beginRead()) {
				store.setNodeProperties(node, Properties.of().set("n", 1L));
				SnapshotStats stats = store.snapshotStats();
				assertEquals(view.snapshotLsn(), stats.oldestOpenLsn(),
					"with one view open, the horizon is that view");
			}
			store.createNode("Trigger");
			assertTrue(store.snapshotStats().oldestOpenLsn() > 0,
				"with none open, the horizon is the present rather than zero");
		}
	}

	/**
	 * Past the budget the store abandons the oldest views rather than growing.
	 *
	 * <p>
	 * The budget here is tiny so the path is reachable in a unit test; the default is 256 MiB. What is being asserted
	 * is that the failure is <b>the exception and not the wrong answer</b> — an abandoned view must never quietly fall
	 * back to reading current data.
	 * </p>
	 */
	@Test
	public void testAnOverBudgetViewIsAbandonedRatherThanServedWrongly() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.retainedVersionBytes(16 * 1024)
			.build();

		try (GraphStore store = stores.open(options)) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));

			ReadTransaction view = store.beginRead();
			assertEquals(0L, store.nodeProperty(node, "n"));

			SnapshotTooOldException failure = null;
			long written = 0;
			for (int i = 1; i <= 2000 && failure == null; i++) {
				store.setNodeProperties(node, Properties.of().set("n", (long) i));
				written = i;
				try {
					Object seen = store.nodeProperty(node, "n");
					assertEquals(0L, seen, "while the view is alive it must keep answering as of its own LSN");
				} catch (SnapshotTooOldException e) {
					failure = e;
				}
			}

			assertTrue(failure != null, "the budget must eventually be enforced");
			assertEquals(view.snapshotLsn(), failure.snapshotLsn());
			assertTrue(failure.horizonLsn() > failure.snapshotLsn(),
				"the horizon must have moved past the view, which is why it failed");
			assertTrue(store.snapshotStats().abandonedViews() > 0, "and the store must report having done it");

			// Every subsequent operation fails the same way rather than intermittently.
			assertThrows(SnapshotTooOldException.class, () -> store.nodeProperty(node, "n"));
			assertThrows(SnapshotTooOldException.class, () -> store.nodeExists(node));

			view.close();
			assertEquals(written, store.nodeProperty(node, "n"), "and a fresh read after closing works normally");
		}
	}

	@Test
	public void testVersionsSurviveALargeTransaction() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub = store.createNode("Hub");
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 100; i++) {
					store.createEdge(hub, "LINK", store.createNode("Leaf"));
				}
				txn.commit();
			}

			try (ReadTransaction view = store.beginRead()) {
				// One transaction touching many pages at once, rather than many small ones.
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < 500; i++) {
						store.createEdge(hub, "LINK", store.createNode("Leaf"));
					}
					txn.commit();
				}
				assertEquals(100, store.neighbours(hub, io.metaloom.graph.core.api.Direction.OUT).size(),
					"a single large commit must be undone as a whole for the view");
				assertTrue(store.snapshotStats().versionedPages() > 1,
					"and it should have touched more than one page, or this asserts nothing");
			}
		}
	}
}
