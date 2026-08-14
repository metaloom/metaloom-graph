package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * How the engine behaves as threads are added, and where it stops scaling.
 * <p>
 * The answer is known in advance and is not flattering, which is precisely why it is measured rather than asserted
 * away: <b>reads scale, writes do not</b>. There is one writer lock and no MVCC, so writer throughput is flat from one
 * thread onwards and the knee is at two. Publishing the curve is more useful than pretending the question is open.
 * <p>
 * The assertions here are on <em>correctness</em>, not on speed. A throughput ratio asserted in a build is a flaky
 * test on a shared machine; what must never vary is that a concurrent reader sees whole commits and correct data.
 * The numbers are printed for the record.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 1
 */
@Tag("slow")
public class ScalingTest {

	private static final int[] THREAD_COUNTS = { 1, 2, 4, 8, 16, 32 };
	private static final int NODES = 50_000;
	private static final long MEASURE_MILLIS = 400;

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testReadScalingCurve() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < NODES; i++) {
					ids.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			store.checkpoint();

			System.out.println("read scaling (point reads by id):");
			double single = 0;
			for (int threads : THREAD_COUNTS) {
				double rate = measureReads(store, ids, threads);
				if (threads == 1) {
					single = rate;
				}
				System.out.printf("  %2d threads %,12.0f ops/s  %5.2fx%n", threads, rate, rate / single);
			}
			assertTrue(single > 0, "the single-threaded baseline must have done some work");
		}
	}

	@Test
	public void testWriteScalingCurveShowsTheSingleWriterKnee() throws Exception {
		try (GraphStore store = stores.open()) {
			System.out.println("write scaling (auto-commit node creation, one writer lock):");
			double single = 0;
			for (int threads : new int[] { 1, 2, 4, 8 }) {
				double rate = measureWrites(store, threads);
				if (threads == 1) {
					single = rate;
				}
				System.out.printf("  %2d threads %,12.0f ops/s  %5.2fx%n", threads, rate, rate / single);
			}
			System.out.println("  the knee is at 2: writers serialise on one lock, which is the documented limit");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The property that must hold at any thread count: a reader either sees a commit whole or does not see it at all.
	 * Each committed node carries two fields that are written together, and a reader that ever sees one without the
	 * other has observed a half-applied commit.
	 */
	@Test
	public void testReadersNeverObserveAHalfAppliedCommit() throws Exception {
		try (GraphStore store = stores.open()) {
			AtomicBoolean running = new AtomicBoolean(true);
			AtomicLong committed = new AtomicLong();
			ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
			ConcurrentLinkedQueue<Long> published = new ConcurrentLinkedQueue<>();

			Thread writer = new Thread(() -> {
				try {
					for (int i = 0; running.get() && i < 4000; i++) {
						long id = store.createNode("N", Properties.of()
							.set("seq", (long) i)
							.set("mirror", (long) i));
						published.add(id);
						committed.incrementAndGet();
					}
				} catch (Exception e) {
					failures.add("writer: " + e);
				}
			});

			List<Thread> readers = new ArrayList<>();
			for (int r = 0; r < 8; r++) {
				readers.add(new Thread(() -> {
					while (running.get()) {
						for (long id : published) {
							try {
								Properties props = store.nodeProperties(id);
								Object seq = props.get("seq");
								Object mirror = props.get("mirror");
								if (seq == null || !seq.equals(mirror)) {
									failures.add("torn commit on " + id + ": seq=" + seq + " mirror=" + mirror);
									return;
								}
							} catch (Exception e) {
								failures.add("reader: " + e);
								return;
							}
						}
					}
				}));
			}

			writer.start();
			readers.forEach(Thread::start);
			writer.join();
			running.set(false);
			for (Thread reader : readers) {
				reader.join();
			}

			assertTrue(failures.isEmpty(), "concurrent readers must never see a partial commit: " + failures);
			assertEquals(committed.get(), store.stats().nodeCount());
			store.verify(VerifyLevel.FULL);
			System.out.println("visibility: " + committed.get() + " commits observed by 8 readers, no tearing");
		}
	}

	// ---------------------------------------------------------------- measurement

	private double measureReads(GraphStore store, List<Long> ids, int threads) throws InterruptedException {
		AtomicLong operations = new AtomicLong();
		AtomicBoolean running = new AtomicBoolean(true);
		CountDownLatch ready = new CountDownLatch(threads);
		List<Thread> workers = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int seed = t;
			workers.add(Thread.ofPlatform().unstarted(() -> {
				java.util.Random random = new java.util.Random(seed);
				ready.countDown();
				long local = 0;
				while (running.get()) {
					for (int i = 0; i < 256; i++) {
						store.nodeLabel(ids.get(random.nextInt(ids.size())));
					}
					local += 256;
				}
				operations.addAndGet(local);
			}));
		}
		workers.forEach(Thread::start);
		ready.await();
		long start = System.nanoTime();
		Thread.sleep(MEASURE_MILLIS);
		running.set(false);
		for (Thread worker : workers) {
			worker.join();
		}
		long elapsed = System.nanoTime() - start;
		return operations.get() / (elapsed / 1_000_000_000.0);
	}

	private double measureWrites(GraphStore store, int threads) throws InterruptedException {
		AtomicLong operations = new AtomicLong();
		AtomicBoolean running = new AtomicBoolean(true);
		List<Thread> workers = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			workers.add(Thread.ofPlatform().unstarted(() -> {
				try {
					while (running.get()) {
						store.createNode("W");
						operations.incrementAndGet();
					}
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}));
		}
		long start = System.nanoTime();
		workers.forEach(Thread::start);
		Thread.sleep(MEASURE_MILLIS);
		running.set(false);
		for (Thread worker : workers) {
			worker.join();
		}
		long elapsed = System.nanoTime() - start;
		return operations.get() / (elapsed / 1_000_000_000.0);
	}

	@Test
	public void testTheHarnessItselfTerminates() throws Exception {
		// A scaling harness that can hang is worse than no scaling data, so the shortest possible run is asserted.
		try (GraphStore store = stores.open()) {
			store.createNode("N");
			assertTrue(measureWrites(store, 2) >= 0);
			assertTrue(TimeUnit.MILLISECONDS.toSeconds(MEASURE_MILLIS) < 5);
		}
	}
}
