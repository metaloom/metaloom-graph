package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * What happens to several readers of different ages when the retention budget runs out.
 *
 * <p>
 * {@code LongRunningReaderTest} already covers one reader against a budget too small. The interesting case is more
 * than one, because the store then has to <em>choose</em>, and the choice is the whole design: abandon the oldest
 * first, because that is what frees the most, and because a reader that has been open longest is the one already least
 * likely to still be wanted.
 * </p>
 *
 * <p>
 * Two things have to hold together, and only the second is really about correctness. Abandonment must follow age — if
 * a view is still alive, every view older than it must be too, or the store is dropping readers it did not need to.
 * And the views that survive must still be <b>right</b>, not merely un-abandoned: giving a reader a wrong answer is
 * the failure that this whole mechanism exists to avoid, and {@code SnapshotTooOldException} is the honest
 * alternative to it.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 5.1
 */
@Tag("unit")
public class SnapshotBudgetUnderLoadTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** Small enough that a few thousand commits will exhaust it. */
	private static final long BUDGET_BYTES = 512 * 1024;

	private static final int VIEWS = 6;

	@Test
	public void testTheOldestViewsAreAbandonedFirstAndTheRestStayCorrect() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.retainedVersionBytes(BUDGET_BYTES)
			.build();

		try (GraphStore store = stores.open(options)) {
			List<Long> nodes = new ArrayList<>();
			for (int i = 0; i < 24; i++) {
				nodes.add(store.createNode("N", Properties.of().set("n", 0L)));
			}

			// One thread per view: a view is thread-confined and beginRead() refuses to nest, so six views need six
			// threads whatever else the test wants. They are opened oldest first, each with more commits behind it
			// than the last, so their ages are ordered and the value each is entitled to see is known.
			List<Holder> views = new ArrayList<>();
			for (int v = 0; v < VIEWS; v++) {
				long value = v * 100L;
				for (long id : nodes) {
					store.setNodeProperties(id, Properties.of().set("n", value));
				}
				Holder holder = new Holder(store, nodes.get(0), value, v);
				holder.openAndWait();
				views.add(holder);
			}

			// Now spend the budget. Each commit retains a before-image for every view still open.
			int commits = 0;
			for (int round = 0; round < 400 && store.snapshotStats().abandonedViews() == 0; round++) {
				for (long id : nodes) {
					store.setNodeProperties(id, Properties.of().set("n", 900_000L + round));
					commits++;
				}
			}

			long abandoned = store.snapshotStats().abandonedViews();
			assertTrue(abandoned > 0, "the budget of " + BUDGET_BYTES + " bytes was never exhausted after " + commits
				+ " commits, so nothing was tested");

			// Ask every view, oldest first, what it can still do.
			List<Boolean> alive = new ArrayList<>();
			for (Holder holder : views) {
				alive.add(holder.probe());
			}

			// Abandonment must be a prefix of the age order: no live view older than an abandoned one.
			boolean seenAlive = false;
			for (int v = 0; v < VIEWS; v++) {
				if (alive.get(v)) {
					seenAlive = true;
				} else if (seenAlive) {
					throw new AssertionError("view " + v + " was abandoned while an older view was left alive"
						+ " (alive by age, oldest first: " + alive + "), so the store is not abandoning oldest first");
				}
			}
			assertTrue(alive.contains(Boolean.FALSE), "no view was abandoned, so the budget did not bite");
			System.out.println("[budget] abandoned=" + abandoned + " alive by age (oldest first)=" + alive
				+ " after " + commits + " commits");

			for (Holder holder : views) {
				holder.closeAndJoin();
			}
			assertEquals(0, store.snapshotStats().openViews(), "closing an abandoned view must still leave no view open");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * One view and the thread that owns it.
	 *
	 * <p>
	 * A view belongs to the thread that opened it — the reconstruction cache under it is deliberately unsynchronised —
	 * so every interaction with it has to be handed back to that thread rather than performed from the test's.
	 * </p>
	 */
	private static final class Holder {

		private final GraphStore store;
		private final long node;
		private final long expected;
		private final int index;
		private final Thread thread;

		private final java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
		private final java.util.concurrent.CountDownLatch probeNow = new java.util.concurrent.CountDownLatch(1);
		private final java.util.concurrent.CountDownLatch probed = new java.util.concurrent.CountDownLatch(1);
		private final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
			new java.util.concurrent.atomic.AtomicReference<>();
		private volatile boolean alive;

		Holder(GraphStore store, long node, long expected, int index) {
			this.store = store;
			this.node = node;
			this.expected = expected;
			this.index = index;
			this.thread = Thread.ofPlatform().name("view-" + index).unstarted(this::run);
		}

		private void run() {
			try (ReadTransaction view = store.beginRead()) {
				opened.countDown();
				probeNow.await();
				try {
					Object actual = store.nodeProperty(node, "n");
					assertEquals(expected, actual, "view " + index + " survived the budget but returned a stale or"
						+ " wrong value, which is the one outcome the retention mechanism exists to prevent");
					alive = true;
				} catch (SnapshotTooOldException e) {
					alive = false;
				}
			} catch (Throwable e) {
				failure.compareAndSet(null, e);
			} finally {
				opened.countDown();
				probed.countDown();
			}
		}

		void openAndWait() throws InterruptedException {
			thread.start();
			assertTrue(opened.await(30, java.util.concurrent.TimeUnit.SECONDS),
				"view " + index + " never opened");
			rethrow();
		}

		boolean probe() throws InterruptedException {
			probeNow.countDown();
			assertTrue(probed.await(30, java.util.concurrent.TimeUnit.SECONDS), "view " + index + " never probed");
			rethrow();
			return alive;
		}

		void closeAndJoin() throws InterruptedException {
			probeNow.countDown();
			thread.join(30_000);
			rethrow();
		}

		private void rethrow() {
			Throwable e = failure.get();
			if (e instanceof AssertionError error) {
				throw error;
			}
			if (e != null) {
				throw new AssertionError("view " + index + " failed", e);
			}
		}
	}
}
