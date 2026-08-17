package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.VerifyOptions;
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Verification run against a store that is being written to at the same time.
 *
 * <p>
 * This is the test the parallel consistency work stands on, so it comes first: a consistency checker that is itself
 * unsafe to run concurrently cannot be used to prove anything about concurrency. {@link VerifyOptions#throttled()}
 * exists specifically so that verification can run against a live store, which makes this a supported use case rather
 * than an abuse of one.
 * </p>
 *
 * <p>
 * <b>The seam under test.</b> {@code SegmentFile.dirtyPages} is a plain {@link java.util.BitSet}. It is set by the
 * writer through {@code SegmentSet.markDirty} on the allocate, free and touch paths, which hold the <em>writer</em>
 * lock; and it is walked and then cleared by {@code SegmentFile.flushChecksums}, which {@code store.verify()} reaches
 * through {@code SegmentSet.verify} while holding only the <em>apply read</em> latch. Those two locks are independent,
 * so the two can run at the same time on the same BitSet. {@code checkpoint()} reaches the same flush down a third
 * path, under the writer lock.
 * </p>
 *
 * <p>
 * The same defect is already recorded for compaction in {@code spec/PROGRESS.md}, where it was reproduced as an
 * intermittent {@code CompactionTest} failure. These cases reach it through the public API instead.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 5
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 3
 */
@Tag("unit")
public class VerifyUnderLoadTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/**
	 * A verification that holds the latch for its whole scan sees one committed instant, so it must always be clean —
	 * and, more to the point, must leave the store's checksum bookkeeping exactly as it found it.
	 */
	@Test
	public void testVerifyConcurrentWithWritersLeavesTheStoreClean() throws Exception {
		AtomicLong verifications = new AtomicLong();
		try (GraphStore store = stores.open()) {
			seed(store, 64);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> verifyFailure = new AtomicReference<>();
			Thread verifier = Thread.ofPlatform().name("verifier").start(() -> {
				try {
					while (!stop.get()) {
						store.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
						verifications.incrementAndGet();
						pause();
					}
				} catch (Throwable e) {
					verifyFailure.compareAndSet(null, e);
				}
			});

			try {
				churn(store, "verify-writers").run().assertRan("createNode", "setProperty", "createEdge");
			} finally {
				stop.set(true);
				verifier.join(60_000);
			}

			if (verifyFailure.get() != null) {
				throw new AssertionError("verify() concurrent with writers reported a failure on a healthy store",
					verifyFailure.get());
			}
			assertTrue(verifications.get() > 0, "the verifier thread never completed a scan, so nothing was tested");

			// The store must still be internally consistent once everything is quiet, and must survive the round trip
			// through a checkpoint and a reopen. A dirty-page mark dropped by a concurrent verify shows up here, as a
			// page whose stored checksum no longer matches its bytes.
			store.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
			store.checkpoint();
			store.verify(VerifyLevel.CHECKSUMS, VerifyOptions.consistentSnapshot());
		}
		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
		}
	}

	/**
	 * {@code checkpoint()} flushes checksums under the writer lock; {@code verify()} flushes them under the apply read
	 * latch. Nothing orders the two against each other, and they walk and clear the same BitSet.
	 */
	@Test
	public void testVerifyConcurrentWithCheckpointKeepsChecksumsHonest() throws Exception {
		AtomicLong checkpoints = new AtomicLong();
		AtomicLong verifications = new AtomicLong();
		try (GraphStore store = stores.open()) {
			seed(store, 64);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failure = new AtomicReference<>();

			Thread verifier = Thread.ofPlatform().name("verifier").start(() -> {
				try {
					while (!stop.get()) {
						store.verify(VerifyLevel.CHECKSUMS, VerifyOptions.consistentSnapshot());
						verifications.incrementAndGet();
						pause();
					}
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				}
			});
			Thread checkpointer = Thread.ofPlatform().name("checkpointer").start(() -> {
				try {
					while (!stop.get()) {
						store.checkpoint();
						checkpoints.incrementAndGet();
						pause();
					}
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				}
			});

			try {
				churn(store, "checkpoint-writers").run().assertRan("setProperty");
			} finally {
				stop.set(true);
				verifier.join(60_000);
				checkpointer.join(60_000);
			}

			if (failure.get() != null) {
				throw new AssertionError("checkpoint() and verify() running together corrupted the checksum state",
					failure.get());
			}
			assertTrue(verifications.get() > 0 && checkpoints.get() > 0,
				"both a verify and a checkpoint must have completed; got verifications=" + verifications.get()
					+ " checkpoints=" + checkpoints.get());

			store.checkpoint();
			store.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
		}
		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
		}
	}

	/**
	 * Two verifications at once. Both take the apply <em>read</em> latch, which does not exclude the other, so both are
	 * inside {@code flushChecksums} walking and clearing one BitSet.
	 */
	@Test
	public void testConcurrentVerifiersDoNotCorruptTheDirtyPageSet() throws Exception {
		try (GraphStore store = stores.open()) {
			seed(store, 128);

			Hammer.named("concurrent-verify")
				.threads(4)
				.opsPerThread(40)
				.op("verifyFull", 1, rnd -> store.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot()))
				.op("verifyChecksums", 1, rnd -> store.verify(VerifyLevel.CHECKSUMS, VerifyOptions.consistentSnapshot()))
				.run()
				.assertRan("verifyFull", "verifyChecksums");

			store.checkpoint();
			store.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
		}
		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
		}
	}

	/**
	 * The throttled form drops the latch between batches, so it may legitimately report a violation for a state that
	 * never existed as a whole — {@link VerifyOptions} says so. What it may never do is hang, crash, or leave the store
	 * damaged, and that is all this asserts.
	 */
	@Test
	public void testThrottledVerifyMakesProgressUnderWriters() throws Exception {
		try (GraphStore store = stores.open()) {
			seed(store, 128);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicLong scans = new AtomicLong();
			AtomicReference<Throwable> unexpected = new AtomicReference<>();
			Thread verifier = Thread.ofPlatform().name("throttled-verifier").start(() -> {
				while (!stop.get()) {
					try {
						store.verify(VerifyLevel.FULL, VerifyOptions.throttled());
					} catch (io.metaloom.graph.core.api.GraphStoreException e) {
						// Allowed, and documented: a scan across several instants can describe a state that never
						// existed. A different exception type is not allowed.
					} catch (Throwable e) {
						unexpected.compareAndSet(null, e);
						return;
					}
					scans.incrementAndGet();
					pause();
				}
			});

			try {
				churn(store, "throttled-writers").run();
			} finally {
				stop.set(true);
				verifier.join(60_000);
			}

			if (verifier.isAlive()) {
				fail("a throttled verification did not finish after the writers stopped, which is the hang this "
					+ "test exists to catch");
			}
			if (unexpected.get() != null) {
				throw new AssertionError("throttled verify() failed with something other than a violation report",
					unexpected.get());
			}
			assertTrue(scans.get() > 0, "no throttled scan completed, so nothing was tested");

			store.checkpoint();
			store.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
		}
	}

	// ---------------------------------------------------------------- workload

	/**
	 * The write mix. Each thread only ever touches nodes it created itself, so a failure is a concurrency defect rather
	 * than two threads racing for the same id — which is a legitimate application-level error and would only add noise.
	 */
	private Hammer churn(GraphStore store, String name) {
		ThreadLocal<List<Long>> owned = ThreadLocal.withInitial(ArrayList::new);
		return Hammer.named(name)
			.threads(4)
			.opsPerThread(150)
			.op("createNode", 3, rnd -> owned.get().add(store.createNode("Churn", Properties.of()
				.set("n", (long) rnd.nextInt(1000))
				.set("tag", "v" + rnd.nextInt(100)))))
			.op("setProperty", 4, rnd -> {
				List<Long> mine = owned.get();
				if (!mine.isEmpty()) {
					long id = mine.get(rnd.nextInt(mine.size()));
					store.setNodeProperties(id, Properties.of().set("n", (long) rnd.nextInt(1000)));
				}
			})
			.op("createEdge", 2, rnd -> {
				List<Long> mine = owned.get();
				if (mine.size() >= 2) {
					store.createEdge(mine.get(rnd.nextInt(mine.size())), "LINKS", mine.get(rnd.nextInt(mine.size())));
				}
			})
			.op("read", 3, rnd -> {
				List<Long> mine = owned.get();
				if (!mine.isEmpty()) {
					store.nodeProperties(mine.get(rnd.nextInt(mine.size())));
				}
			});
	}

	/**
	 * Verification and checkpointing both hold the writer lock now, and {@link java.util.concurrent.locks.ReentrantLock}
	 * is unfair by default. A checker looping with no gap can therefore hand the lock straight back to itself and starve
	 * the writers this test needs to be running — which shows up as a watchdog timeout rather than as a wrong answer.
	 * The point is to interleave with the writers, not to outrun them.
	 */
	private void pause() {
		try {
			Thread.sleep(2);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void seed(GraphStore store, int nodes) throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < nodes; i++) {
			ids.add(store.createNode("Seed", Properties.of().set("i", (long) i)));
		}
		for (int i = 1; i < ids.size(); i++) {
			store.createEdge(ids.get(i - 1), "NEXT", ids.get(i));
		}
	}
}
