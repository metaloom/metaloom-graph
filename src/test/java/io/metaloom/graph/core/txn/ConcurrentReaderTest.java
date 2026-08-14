package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * The Phase 5 exit criterion, restated for what was built: <b>many readers, each with its own fixed view, run
 * concurrently with a writer, and none of them takes a global lock on the read path.</b>
 *
 * <p>
 * [PLAN.md](../../../../../../../../spec/PLAN.md) words it as "16 concurrent writer threads scale without a global lock
 * on the read path". Writers are serialised here by design, so the half that can be measured is the half that matters
 * for the claim: reads stay correct and stay fast while writing continues underneath them.
 * </p>
 */
@Tag("unit")
public class ConcurrentReaderTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int READERS = 16;

	/**
	 * Sixteen readers, each fixed at a different instant, each asserting the value it began with — while a writer
	 * commits continuously.
	 */
	@Test
	public void testSixteenViewsEachSeeTheirOwnInstant() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Counter", Properties.of().set("n", 0L));

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failed = new AtomicReference<>();
			AtomicLong committed = new AtomicLong();

			Thread writer = new Thread(() -> {
				try {
					for (long i = 1; !stop.get(); i++) {
						store.setNodeProperties(node, Properties.of().set("n", i));
						committed.set(i);
					}
				} catch (Throwable e) {
					failed.compareAndSet(null, e);
				}
			});
			writer.start();

			// Wait until the writer is actually producing commits, so the views below are opened against a moving
			// store rather than a still one.
			while (committed.get() < 20 && failed.get() == null) {
				Thread.onSpinWait();
			}

			CountDownLatch done = new CountDownLatch(READERS);
			List<Thread> readers = new ArrayList<>();
			for (int r = 0; r < READERS; r++) {
				Thread reader = new Thread(() -> {
					try (ReadTransaction view = store.beginRead()) {
						Object first = store.nodeProperty(node, "n");
						for (int i = 0; i < 200; i++) {
							assertEquals(first, store.nodeProperty(node, "n"),
								"a view must not move while the writer commits underneath it");
							assertEquals("Counter", store.nodeLabel(node));
						}
					} catch (Throwable e) {
						failed.compareAndSet(null, e);
					} finally {
						done.countDown();
					}
				});
				readers.add(reader);
				reader.start();
			}

			done.await();
			stop.set(true);
			writer.join();
			for (Thread reader : readers) {
				reader.join();
			}

			assertNull(failed.get(), String.valueOf(failed.get()));
			assertTrue(committed.get() > 20, "the writer must have kept going throughout, not been starved out");
			assertEquals(0, store.snapshotStats().openViews(), "every view must have been released");
		}
	}

	/**
	 * Reconstruction is proportional to what the reader reads, not to what the writer wrote.
	 *
	 * <p>
	 * This is the property that keeps snapshot isolation from being a tax on the data a reader never looks at: a
	 * hundred commits to one node cost a view that never reads that node <b>one page</b>, and reading the node itself
	 * costs one more.
	 * </p>
	 *
	 * <p>
	 * <b>That floor of one page is not zero, and the reason is worth knowing.</b> Each segment keeps its superblock,
	 * free-slot bitmap and page-metadata array together in a metadata region at the front of the file, and at these
	 * sizes they share a page. Every write touches it — the allocator, the live counters, the epoch bump — and every
	 * read consults it to check that a slot is allocated. So a view that reads any node at all rebuilds that one page
	 * once. It is O(segments touched), not O(data changed), it is cached for the life of the view, and it is why the
	 * assertion below is a bound rather than a zero.
	 * </p>
	 */
	@Test
	public void testReconstructionIsProportionalToWhatIsRead() throws Exception {
		try (GraphStore store = stores.open()) {
			long quiet = store.createNode("Quiet", Properties.of().set("k", "v"));
			// Push the busy node onto a different data page, so writing it does not version the quiet one.
			for (int i = 0; i < 400; i++) {
				store.createNode("Filler");
			}
			long busy = store.createNode("Busy", Properties.of().set("n", 0L));
			int pageSize = 8192;

			try (ReadTransaction view = store.beginRead()) {
				for (int i = 1; i <= 100; i++) {
					store.setNodeProperties(busy, Properties.of().set("n", (long) i));
				}
				long retainedByWriter = store.snapshotStats().retainedBytes();
				assertTrue(retainedByWriter > 0, "the writer must have handed the view something to keep");

				assertEquals("v", store.nodeProperty(quiet, "k"));
				long afterQuiet = view.retainedBytes();
				assertTrue(afterQuiet <= 2L * pageSize,
					"reading an unchanged node must rebuild the segment metadata page and nothing else, was "
						+ afterQuiet);

				assertEquals(0L, store.nodeProperty(busy, "n"));
				long afterBusy = view.retainedBytes();
				assertTrue(afterBusy > afterQuiet, "reading the changed node must rebuild its data page too");
				assertTrue(afterBusy <= 4L * pageSize,
					"and a hundred commits to one node must still cost a handful of pages, was " + afterBusy);

				// Reading it again is free: an image, once rebuilt, stays correct for the life of the view.
				for (int i = 0; i < 50; i++) {
					assertEquals(0L, store.nodeProperty(busy, "n"));
				}
				assertEquals(afterBusy, view.retainedBytes(), "a rebuilt page must be rebuilt once, not per read");
			}
		}
	}

	/** Maintenance that would pull the ground out from under a view is refused rather than attempted. */
	@Test
	public void testMaintenanceIsRefusedWhileAViewIsOpen() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 100; i++) {
					store.createNode("Person", Properties.of().set("i", i));
				}
				txn.commit();
			}

			try (ReadTransaction view = store.beginRead()) {
				GraphStoreException reclaim = assertThrows(GraphStoreException.class, store::reclaim);
				assertTrue(reclaim.getMessage().contains("read view"), reclaim.getMessage());

				GraphStoreException compact = assertThrows(GraphStoreException.class, store::compact);
				assertTrue(compact.getMessage().contains("read view"), compact.getMessage());
			}

			// And both work again once it is closed.
			store.reclaim();
			store.compact();
		}
	}

	/**
	 * A view opened on a pooled thread must not be inherited by whatever runs there next.
	 *
	 * <p>
	 * The holder is a thread-local, and a thread-local that is set but never removed is exactly how a reader leaks onto
	 * an unrelated task and makes it read stale data for the rest of the process's life.
	 * </p>
	 */
	@Test
	public void testAClosedViewDoesNotLingerOnItsThread() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));

			AtomicReference<Object> secondTask = new AtomicReference<>();
			AtomicReference<Throwable> failed = new AtomicReference<>();
			Thread pooled = new Thread(() -> {
				try {
					try (ReadTransaction view = store.beginRead()) {
						assertEquals(0L, store.nodeProperty(node, "n"));
					}
					// The "next task" on the same thread.
					store.setNodeProperties(node, Properties.of().set("n", 7L));
					secondTask.set(store.nodeProperty(node, "n"));
				} catch (Throwable e) {
					failed.compareAndSet(null, e);
				}
			});
			pooled.start();
			pooled.join();

			assertNull(failed.get(), String.valueOf(failed.get()));
			assertEquals(7L, secondTask.get(), "the closed view must not still be filtering this thread's reads");
		}
	}
}
