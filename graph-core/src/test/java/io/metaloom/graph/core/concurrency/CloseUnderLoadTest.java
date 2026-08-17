package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * Closing a store while other threads are using it.
 *
 * <p>
 * The engine maps its segment files from an {@code Arena.ofShared()} and unmaps them on close, while the read path
 * deliberately takes no lock at all — {@code tryOptimisticRead} and a validate afterwards. Closing therefore pulls a
 * mapping out from under threads that are actively dereferencing it. The shared arena makes that memory-safe rather
 * than a segfault, but memory-safe is not the same as well-behaved: what a caller must get is the engine's own
 * vocabulary for "this store is closed", not a raw {@link IllegalStateException} from the foreign-memory layer that it
 * cannot tell apart from a bug.
 * </p>
 *
 * <p>
 * This is the same complaint {@code CrashMatrixTest} already fixed once for recovery, where an untyped
 * {@code IllegalStateException} left a caller unable to distinguish "this store is damaged" from "this code is wrong".
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 6
 */
@Tag("unit")
public class CloseUnderLoadTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int THREADS = 6;

	/**
	 * An orderly {@code close()} takes the writer lock, so it can never land inside a commit. Everything it interrupts
	 * is a reader, and every one of them must be told in the engine's own vocabulary.
	 */
	@Test
	public void testCloseUnderLoadFailsInTheEnginesOwnVocabulary() throws Exception {
		Outcome outcome = runAndClose(false);
		if (!outcome.unexpected().isEmpty()) {
			AssertionError error = new AssertionError(outcome.unexpected().size()
				+ " thread(s) failed with something other than a GraphStoreException when the store closed underneath"
				+ " them. A caller cannot tell these apart from a defect in its own code. Outcomes: "
				+ outcome.summary());
			outcome.unexpected().forEach(error::addSuppressed);
			throw error;
		}
	}

	/**
	 * {@code abandon()} is the crash hook — it drops the mappings with no checkpoint, no flush and <b>no writer
	 * lock</b>, because that is what a process being killed does. A thread racing it can therefore see anything,
	 * including a raw {@code IllegalArgumentException} from a segment list that has already been torn down, and that is
	 * faithful rather than wrong: taking the writer lock here would make {@code abandon()} wait politely for the
	 * in-flight commit and quietly weaken every crash test in the suite that depends on it being abrupt.
	 *
	 * <p>
	 * So the assertion is the one {@code spec/TESTING.md} section 6 actually specifies for a crash: no hang, and the
	 * store opens clean afterwards with its committed work intact.
	 * </p>
	 */
	@Test
	public void testAbandonUnderLoadLeavesARecoverableStore() throws Exception {
		Outcome outcome = runAndClose(true);
		System.out.println("[close-under-load] abandon tolerated: " + outcome.summary());

		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL);
			List<Long> recovered = new ArrayList<>();
			reopened.forEachNode(recovered::add);
			assertTrue(recovered.size() >= 200,
				"the 200 nodes committed before the crash must all have survived it; found " + recovered.size());
		}
	}

	private record Outcome(Map<String, Long> summary, List<Throwable> unexpected) {
	}

	private Outcome runAndClose(boolean abandon) throws Exception {
		GraphStore store = stores.open();
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			ids.add(store.createNode("N", Properties.of().set("i", (long) i)));
		}
		for (int i = 1; i < ids.size(); i++) {
			store.createEdge(ids.get(i - 1), "NEXT", ids.get(i));
		}

		Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
		List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
		AtomicBoolean stop = new AtomicBoolean();
		AtomicLong operations = new AtomicLong();
		CountDownLatch running = new CountDownLatch(THREADS);
		List<Thread> workers = new ArrayList<>();

		for (int t = 0; t < THREADS; t++) {
			final int index = t;
			workers.add(Thread.ofPlatform().name("close-load-" + t).start(() -> {
				java.util.Random rnd = new java.util.Random(4242L + index);
				running.countDown();
				while (!stop.get()) {
					try {
						long id = ids.get(rnd.nextInt(ids.size()));
						switch (index % 3) {
							case 0 -> store.nodeProperties(id);
							case 1 -> store.neighbours(id, Direction.OUT);
							default -> store.setNodeProperties(id, Properties.of().set("i", (long) rnd.nextInt(1000)));
						}
						operations.incrementAndGet();
						record(outcomes, "ok");
					} catch (GraphStoreException e) {
						// The engine's own vocabulary. StoreClosedException is the expected member of it.
						record(outcomes, e.getClass().getSimpleName());
						return;
					} catch (Throwable e) {
						record(outcomes, "UNEXPECTED:" + e.getClass().getName());
						unexpected.add(e);
						return;
					}
				}
			}));
		}

		running.await();
		// Long enough that every thread is inside the read path rather than still starting up.
		Thread.sleep(120);
		assertTrue(operations.get() > 0, "no work ran before the close, so the race was never set up");

		if (abandon) {
			((GraphStoreImpl) store).abandon();
		} else {
			store.close();
		}
		stop.set(true);

		for (Thread worker : workers) {
			worker.join(30_000);
		}
		List<String> stuck = workers.stream().filter(Thread::isAlive).map(Thread::getName).toList();
		assertTrue(stuck.isEmpty(), "threads still running after the store closed: " + stuck);

		System.out.println("[close-under-load] abandon=" + abandon + " outcomes=" + summarise(outcomes));
		return new Outcome(summarise(outcomes), unexpected);
	}

	private static void record(Map<String, LongAdder> outcomes, String key) {
		outcomes.computeIfAbsent(key, k -> new LongAdder()).increment();
	}

	private static Map<String, Long> summarise(Map<String, LongAdder> outcomes) {
		Map<String, Long> totals = new java.util.TreeMap<>();
		outcomes.forEach((k, v) -> totals.put(k, v.sum()));
		return totals;
	}
}
