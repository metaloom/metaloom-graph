package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Commits keep their pace while other threads scan and walk cursors back to back.
 *
 * <p>
 * Scans and cursors hold the apply latch shared for their whole length, and a commit needs it exclusively for its
 * apply. {@link java.util.concurrent.locks.StampedLock} lets a reader in whenever no writer <em>holds</em> the lock,
 * queued or not, so a few threads whose holds overlap keep the committer out indefinitely — and every other writer is
 * queued behind the commit lock it is holding. Measured before the fix, on this store: 127 commits a second alone, 3
 * with four scanning threads, single commits waiting 900 ms. Nothing failed and nothing deadlocked; the writers simply
 * stopped.
 * </p>
 *
 * <p>
 * The assertion is relative, so it does not depend on how fast this machine forces the log: with the scanners running,
 * commits must keep at least a quarter of the rate they reach alone. Before the fix the ratio was about one fortieth.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 8
 */
@Tag("unit")
public class ScanWriterStarvationTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final long WINDOW_NANOS = 1_000_000_000L;

	@Test
	public void testCommitsKeepTheirPaceBesideContinuousScansAndCursors() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 2_000; i++) {
					store.createEdge(hub, "HAS", store.createNode("Item"));
				}
				txn.commit();
			}

			long alone = commitsWithin(store, WINDOW_NANOS);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicLong reads = new AtomicLong();
			List<Thread> threads = new ArrayList<>();
			for (int s = 0; s < 4; s++) {
				boolean cursors = s % 2 == 1;
				Runnable reader = () -> {
					try {
						while (!stop.get()) {
							if (cursors) {
								try (NeighbourCursor cursor = store.neighbourCursor(hub, Direction.OUT, null)) {
									while (cursor.next()) {
										cursor.edge();
									}
								}
							} else {
								store.forEachNode(id -> {
								});
							}
							reads.incrementAndGet();
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				};
				threads.add(s < 2 ? Thread.ofPlatform().name("scanner-" + s).start(reader)
					: Thread.ofVirtual().name("scanner-" + s).start(reader));
			}
			long besideScans;
			try {
				Thread.sleep(50);
				besideScans = commitsWithin(store, WINDOW_NANOS);
			} finally {
				stop.set(true);
				for (Thread thread : threads) {
					thread.join(30_000);
				}
			}
			if (failure.get() != null) {
				throw new AssertionError("a scanner failed", failure.get());
			}
			System.out.println("[starvation] alone=" + alone + "/s besideScans=" + besideScans + "/s reads="
				+ reads.get());
			assertTrue(reads.get() > 100, "the scanners barely ran (" + reads.get() + "), so nothing was contended");
			assertTrue(besideScans * 4 >= alone, "commits fell from " + alone + " a second to " + besideScans
				+ " while other threads scanned: the scans' read holds overlap and keep every commit out");
			store.verify(VerifyLevel.FULL);
		}
	}

	private static long commitsWithin(GraphStore store, long nanos) throws Exception {
		long commits = 0;
		long end = System.nanoTime() + nanos;
		while (System.nanoTime() < end) {
			store.createNode("W");
			commits++;
		}
		return commits;
	}
}
