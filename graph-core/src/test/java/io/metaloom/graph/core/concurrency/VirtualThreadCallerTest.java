package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Callers on virtual threads.
 *
 * <p>
 * The engine's own server deliberately does not use them — a page fault on a mapped segment blocks the carrier, so a
 * pool of virtual threads over mmap'd I/O can stall every carrier at once, and {@code WireServer} runs its queries on
 * a Vert.x {@code WorkerExecutor} for exactly that reason. But {@code GraphStore} is public API on Java 25, so
 * applications <em>will</em> call it from virtual threads whatever this project does, and two things in the engine are
 * per-thread rather than per-store: {@code SnapshotHolder} is a {@link ThreadLocal}, and read views are confined to
 * the thread that opened them.
 * </p>
 *
 * <p>
 * A {@code ThreadLocal} on a virtual thread is per-virtual-thread, which is the behaviour the engine needs, but it
 * also means a thousand concurrent callers is a thousand live views rather than a pool-sized handful — so this checks
 * that views are accounted for and released exactly as they are on platform threads. It also checks the store simply
 * <b>makes progress</b>: the writer lock is a {@code ReentrantLock} and the dictionary interns under
 * {@code synchronized}, neither of which pins a carrier on this release, but "does not deadlock under a thousand
 * virtual callers" is worth an assertion rather than an assumption.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 8
 */
@Tag("unit")
public class VirtualThreadCallerTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int CALLERS = 500;

	@Test
	public void testManyVirtualThreadCallersMakeProgressAndLeaveNoViewBehind() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> nodes = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 32; i++) {
					nodes.add(store.createNode("N", Properties.of().set("n", (long) i)));
				}
				txn.commit();
			}

			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicLong writes = new AtomicLong();
			AtomicLong reads = new AtomicLong();
			CountDownLatch go = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(CALLERS);
			List<Thread> callers = new ArrayList<>();

			for (int c = 0; c < CALLERS; c++) {
				final int index = c;
				callers.add(Thread.ofVirtual().name("vcaller-" + c).start(() -> {
					try {
						go.await();
						if (index % 3 == 0) {
							// Every writer here contends for the one writer lock from a virtual thread.
							store.setNodeProperties(nodes.get(index % nodes.size()),
								Properties.of().set("n", (long) index));
							writes.incrementAndGet();
						} else {
							// A view per virtual thread, which is what the ThreadLocal makes it.
							try (ReadTransaction view = store.beginRead()) {
								assertTrue(view.isOpen(), "a view opened on a virtual thread should be open");
								for (long id : nodes) {
									store.nodeProperty(id, "n");
								}
								reads.incrementAndGet();
							}
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					} finally {
						done.countDown();
					}
				}));
			}

			go.countDown();
			assertTrue(done.await(60, TimeUnit.SECONDS),
				"only " + (CALLERS - done.getCount()) + " of " + CALLERS + " virtual callers finished within 60s,"
					+ " which is the carrier-starvation failure this test exists to catch");

			for (Thread caller : callers) {
				caller.join(10_000);
			}
			if (failure.get() != null) {
				throw new AssertionError("a virtual-thread caller failed", failure.get());
			}

			assertTrue(writes.get() > 0 && reads.get() > 0,
				"both paths must have run; writes=" + writes.get() + " reads=" + reads.get());
			assertEquals(0, store.snapshotStats().openViews(),
				"every view opened on a virtual thread must have been released with it");
			store.verify(VerifyLevel.FULL);
			System.out.println("[virtual-threads] callers=" + CALLERS + " writes=" + writes.get()
				+ " reads=" + reads.get());
		}
	}

	/**
	 * A view is confined to the thread that opened it, and a virtual thread is still a thread. Handing one to another
	 * caller has to be refused rather than tolerated: the reconstruction cache beneath it is an unsynchronised
	 * {@code HashMap}, so sharing a view corrupts it in a way that surfaces later as a wrong answer.
	 */
	@Test
	public void testAViewIsStillConfinedToItsVirtualThread() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("N", Properties.of().set("n", 1L));

			AtomicReference<ReadTransaction> shared = new AtomicReference<>();
			AtomicReference<Throwable> outcome = new AtomicReference<>();
			CountDownLatch opened = new CountDownLatch(1);
			CountDownLatch probed = new CountDownLatch(1);

			Thread owner = Thread.ofVirtual().name("view-owner").start(() -> {
				try (ReadTransaction view = store.beginRead()) {
					shared.set(view);
					opened.countDown();
					probed.await();
				} catch (Throwable e) {
					outcome.compareAndSet(null, e);
				}
			});

			assertTrue(opened.await(30, TimeUnit.SECONDS), "the owning virtual thread never opened a view");

			Thread intruder = Thread.ofVirtual().name("view-intruder").start(() -> {
				try {
					// Not this thread's view. Reading through it must be refused.
					shared.get().snapshotLsn();
					store.nodeProperty(1L, "n");
				} catch (Throwable e) {
					outcome.compareAndSet(null, e);
				}
			});
			intruder.join(30_000);
			probed.countDown();
			owner.join(30_000);

			assertEquals(0, store.snapshotStats().openViews(), "the view should have closed with its owner");
			store.verify(VerifyLevel.FULL);
		}
	}
}
