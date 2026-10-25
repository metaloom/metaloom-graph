package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.TransactionConflictException;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * A view abandoned by the retention budget <b>in the middle of</b> a scan or a cursor, rather than between operations.
 *
 * <p>
 * Vacuum runs after a commit, outside the apply latch, so holding the latch — which a scan and a cursor do, through a
 * read lease — does not keep it out. When the budget runs out it abandons the oldest view and cuts every chain that view
 * still needed; a page whose whole chain went then has no head at all, and the view's next read of it is the mapping,
 * which is newer than the view. {@code read()} checks the view again after the operation for exactly this reason. The
 * lease-based reads — {@code forEachNode}, {@code neighbourCursor}, the vector scans — have to give the same guarantee:
 * an abandoned view either says so with {@link SnapshotTooOldException} or answers as of its snapshot.
 * </p>
 *
 * <p>
 * The oracle is absolute rather than a repeat, because a repeat would agree with itself: the first read of a page
 * caches what it saw for the rest of the view, wrong or not. Every writer commit adds exactly one node and one hub edge
 * and reports its LSN, so the counts a view at LSN {@code L} is entitled to see follow from how many commits are at or
 * below {@code L}. Checked once everything has stopped, when that set is complete.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 5.1
 */
@Tag("unit")
public class AbandonedViewScanTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** Small enough that a few dozen commits behind an open view run it out. */
	private static final long BUDGET_BYTES = 8 * 1024;

	private static final long RUN_MILLIS = 2_000;

	private static final int SEEDED = 2_000;

	private record Seen(long lsn, long nodes, long hubEdges, String how) {
	}

	@Test
	public void testAnAbandonedViewNeverAnswersFromTheFuture() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.retainedVersionBytes(BUDGET_BYTES)
			// Durability is not under test, and an fsync per commit leaves too few commits behind each view for the
			// budget to run out while it is open.
			.walSyncMode(WalSyncMode.ASYNC)
			.build();

		try (GraphStore store = stores.open(options)) {
			long hub;
			long baseLsn;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < SEEDED; i++) {
					store.createEdge(hub, "HAS", store.createNode("Item"));
				}
				baseLsn = txn.commit();
			}

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicLong abandoned = new AtomicLong();
			ConcurrentSkipListSet<Long> commitLsns = new ConcurrentSkipListSet<>();
			ConcurrentLinkedQueue<Seen> seen = new ConcurrentLinkedQueue<>();
			List<Thread> threads = new ArrayList<>();

			for (int w = 0; w < 2; w++) {
				threads.add(Thread.ofPlatform().name("grower-" + w).start(() -> {
					try {
						while (!stop.get()) {
							// An explicit transaction, because the oracle needs each commit's LSN.
							try (Transaction txn = store.begin()) {
								store.createEdge(hub, "HAS", store.createNode("Item"));
								commitLsns.add(txn.commit());
							} catch (TransactionConflictException e) {
								// Both growers append to the hub's adjacency; the loser simply goes again.
							}
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				}));
			}
			for (int r = 0; r < 2; r++) {
				boolean virtual = r == 1;
				Runnable reader = () -> {
					try {
						while (!stop.get() && failure.get() == null) {
							ThreadLocalRandom rnd = ThreadLocalRandom.current();
							try (ReadTransaction view = store.beginRead()) {
								// Held open first so commits pile up behind it and the budget is nearly spent when the
								// scan starts — the abandonment then lands during the first touch of each page.
								LockSupport.parkNanos(rnd.nextLong(500_000, 8_000_000));
								if (rnd.nextBoolean()) {
									seen.add(new Seen(view.snapshotLsn(), countScan(store), -1, "forEachNode"));
								} else {
									seen.add(new Seen(view.snapshotLsn(), -1, countCursor(store, hub), "neighbourCursor"));
								}
							} catch (SnapshotTooOldException e) {
								abandoned.incrementAndGet();
							}
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				};
				threads.add(virtual ? Thread.ofVirtual().name("reader-" + r).start(reader)
					: Thread.ofPlatform().name("reader-" + r).start(reader));
			}

			Thread.sleep(RUN_MILLIS);
			stop.set(true);
			for (Thread thread : threads) {
				thread.join(30_000);
				if (thread.isAlive()) {
					throw new AssertionError(thread.getName() + " did not stop within 30 s");
				}
			}
			if (failure.get() != null) {
				throw new AssertionError("a worker failed", failure.get());
			}

			List<String> wrong = new ArrayList<>();
			for (Seen s : seen) {
				long behind = commitLsns.headSet(s.lsn(), true).size();
				long nodes = 1 + SEEDED + behind;
				long edges = SEEDED + behind;
				if ((s.nodes() >= 0 && s.nodes() != nodes) || (s.hubEdges() >= 0 && s.hubEdges() != edges)) {
					wrong.add(s.how() + " in a view at LSN " + s.lsn() + " saw " + Math.max(s.nodes(), s.hubEdges())
						+ " where the snapshot holds " + (s.nodes() >= 0 ? nodes : edges));
				}
			}
			System.out.println("[abandoned-scan] answered=" + seen.size() + " abandoned=" + abandoned.get()
				+ " commits=" + commitLsns.size() + " wrong=" + wrong.size() + " base=" + baseLsn);
			assertTrue(wrong.isEmpty(), wrong.size() + " of " + seen.size() + " answers came from after their view's"
				+ " snapshot without the view being told it was abandoned, e.g.\n  "
				+ String.join("\n  ", wrong.subList(0, Math.min(5, wrong.size()))));
			assertTrue(abandoned.get() > 0, "no view was abandoned, so the budget never ran out and nothing was tested");
			assertTrue(seen.size() > 0, "no view answered");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The same window, scheduled rather than hoped for.
	 *
	 * <p>
	 * A view opens; the hub then loses an edge in every adjacency block while vacuum is held off, so the retention budget
	 * is overdrawn with the view still alive. The view opens a cursor — the lease checks it is alive, which it is — and
	 * reads one entry. Only then does vacuum run: it abandons the view and, the view being the only one, cuts every
	 * chain. The cursor's remaining blocks have not been touched yet, so nothing of them is cached; they now have no
	 * chain, and the next read of each is the mapping, without the deleted edges.
	 * </p>
	 */
	@Test
	@org.junit.jupiter.api.Timeout(60)
	public void testACursorAbandonedMidWalkIsToldSo() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.retainedVersionBytes(4 * 1024)
			.build();
		int edges = 12_000;
		try (GraphStore store = stores.open(options)) {
			long hub;
			List<Long> items = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < edges; i++) {
					long item = store.createNode("Item");
					items.add(item);
					store.createEdge(hub, "HAS", item);
				}
				txn.commit();
			}
			TransactionManager transactions = field(store, "transactions", TransactionManager.class);
			java.util.concurrent.locks.ReentrantLock vacuumLock = field(transactions, "vacuumLock",
				java.util.concurrent.locks.ReentrantLock.class);

			java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
			java.util.concurrent.CountDownLatch openCursor = new java.util.concurrent.CountDownLatch(1);
			java.util.concurrent.CountDownLatch midWalk = new java.util.concurrent.CountDownLatch(1);
			java.util.concurrent.CountDownLatch finishWalk = new java.util.concurrent.CountDownLatch(1);
			AtomicReference<Object> outcome = new AtomicReference<>();
			Thread viewer = Thread.ofPlatform().name("viewer").start(() -> {
				try (ReadTransaction view = store.beginRead()) {
					opened.countDown();
					openCursor.await();
					long count = 0;
					try (NeighbourCursor cursor = store.neighbourCursor(hub, Direction.OUT, null)) {
						if (cursor.next()) {
							count++;
						}
						midWalk.countDown();
						finishWalk.await();
						while (cursor.next()) {
							count++;
						}
					}
					outcome.set(count);
				} catch (Throwable e) {
					outcome.set(e);
				} finally {
					midWalk.countDown();
				}
			});

			opened.await();
			// Vacuum is skipped while somebody else holds its lock, which is how the budget is overdrawn here without
			// the view being abandoned yet.
			java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
			java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
			Thread holder = Thread.ofPlatform().name("vacuum-holder").start(() -> {
				vacuumLock.lock();
				try {
					held.countDown();
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					vacuumLock.unlock();
				}
			});
			held.await();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < edges; i += 7) {
					store.deleteEdge(hub, "HAS", items.get(i));
				}
				txn.commit();
			}
			int deleted = (edges + 6) / 7;
			release.countDown();
			holder.join();
			assertTrue(store.snapshotStats().retainedBytes() > 4 * 1024, "the budget must be overdrawn: "
				+ store.snapshotStats());

			openCursor.countDown();
			midWalk.await();
			// The commit's own vacuum, run late: the view is over budget and the only one, so it is abandoned and every
			// chain is cut while its cursor is half way through.
			transactions.vacuum(field(store, "appliedLsn", Long.class));
			finishWalk.countDown();
			viewer.join(30_000);

			Object result = outcome.get();
			if (result instanceof Long count) {
				org.junit.jupiter.api.Assertions.assertEquals((long) edges, count, "the abandoned view's cursor"
					+ " returned " + count + " edges where its snapshot has " + edges + " — " + (edges - count)
					+ " of the " + deleted + " edges deleted after it began were missing, read from the mapping"
					+ " without the view being told it was abandoned");
			} else if (!(result instanceof SnapshotTooOldException)) {
				throw new AssertionError("the viewer failed with something other than SnapshotTooOldException",
					(Throwable) result);
			}
			System.out.println("[abandoned-cursor] outcome=" + result);
			store.verify(VerifyLevel.FULL);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
		java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (T) field.get(owner);
	}

	private static long countScan(GraphStore store) {
		long[] count = { 0 };
		store.forEachNode(id -> count[0]++);
		return count[0];
	}

	private static long countCursor(GraphStore store, long hub) {
		long count = 0;
		try (NeighbourCursor cursor = store.neighbourCursor(hub, Direction.OUT, null)) {
			while (cursor.next()) {
				count++;
			}
		}
		return count;
	}
}
