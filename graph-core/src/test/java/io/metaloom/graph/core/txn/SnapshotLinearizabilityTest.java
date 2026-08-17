package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
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
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.ModelHistory;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.property.GraphModel;

/**
 * A read view must show exactly the state the store was in at the LSN it was fixed at.
 *
 * <p>
 * The rest of {@code txn/} asserts named anomalies: no dirty read, no phantom, no lost update. This asserts the
 * property those are all instances of, and it can, because of two things that are true together here. Writers are
 * serialised, so the store has one history rather than a partial order of interleavings; and {@code snapshot_ts} is
 * the LSN, so a view names its own position in that history. The correct answer for a view at LSN {@code L} is
 * therefore computable, not merely constrainable — and anything other than that answer is a defect, including the
 * kinds nobody wrote a test for.
 * </p>
 *
 * <p>
 * The comparison is whole-state rather than sampled: every visible node, its labels, its properties, its out-degree
 * and its enumerated adjacency. The graph is kept small so that this is affordable on every single read, because an
 * oracle that only checks the node it suspects is an oracle that agrees with its author.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 3
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 4
 */
@Tag("unit")
public class SnapshotLinearizabilityTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final long SEED = 20260817L;
	private static final int POOL = 24;
	private static final int READERS = 4;

	/**
	 * Readers open views at arbitrary instants while a writer commits continuously. Each view is checked against the
	 * model state at its own LSN.
	 */
	@Test
	public void testEveryViewMatchesTheModelAtItsOwnLsn() throws Exception {
		try (GraphStore store = stores.open()) {
			ModelHistory history = new ModelHistory();
			GraphModel model = new GraphModel();
			List<Long> pool = new ArrayList<>();

			// A committed starting point, so a reader can never open a view before the history begins.
			seed(store, model, history, pool);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicLong checks = new AtomicLong();
			AtomicLong skipped = new AtomicLong();
			CountDownLatch ready = new CountDownLatch(READERS);

			List<Thread> readers = new ArrayList<>();
			for (int r = 0; r < READERS; r++) {
				final int index = r;
				readers.add(Thread.ofPlatform().name("linearizability-reader-" + r).start(() -> {
					Random rnd = new Random(SEED * 7 + index);
					ready.countDown();
					try {
						while (!stop.get()) {
							try (ReadTransaction view = store.beginRead()) {
								long lsn = view.snapshotLsn();
								if (!history.awaitRecorded(lsn, 5_000)) {
									skipped.incrementAndGet();
									continue;
								}
								GraphModel expected = history.stateAt(lsn);
								if (expected == null) {
									skipped.incrementAndGet();
									continue;
								}
								// Sometimes hold the view open across further commits before reading, so that what is
								// being checked is the view's fixedness and not just a fast read.
								if (rnd.nextInt(4) == 0) {
									Thread.sleep(1);
								}
								List<String> problems = ModelHistory.differences(store, expected);
								if (!problems.isEmpty()) {
									throw new AssertionError("view at LSN " + lsn + " disagreed with the committed"
										+ " history in " + problems.size() + " place(s):\n  "
										+ String.join("\n  ", problems));
								}
								checks.incrementAndGet();
							}
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				}));
			}

			ready.await();
			try {
				// One writer: writes are serialised anyway, and a single writer is what makes the history a line.
				writer(store, model, history, pool).run().assertRan("createNode", "setProperty");
			} finally {
				stop.set(true);
				for (Thread reader : readers) {
					reader.join(60_000);
				}
			}

			if (failure.get() != null) {
				throw new AssertionError("a snapshot view did not match the committed history", failure.get());
			}
			assertTrue(checks.get() > 20, "only " + checks.get() + " views were checked (" + skipped.get()
				+ " skipped), which is too few to have tested anything");
			System.out.println("[linearizability] views checked=" + checks.get() + " skipped=" + skipped.get());

			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * One view, held open across many commits, must still match the instant it began at — the same property, but with
	 * the version chains actually doing work rather than the mapping happening to be right already.
	 */
	@Test
	public void testAHeldViewStillMatchesItsInstantAfterManyCommits() throws Exception {
		try (GraphStore store = stores.open()) {
			ModelHistory history = new ModelHistory(4096);
			GraphModel model = new GraphModel();
			List<Long> pool = new ArrayList<>();
			seed(store, model, history, pool);

			try (ReadTransaction view = store.beginRead()) {
				long lsn = view.snapshotLsn();
				assertTrue(history.awaitRecorded(lsn, 5_000), "the seed state should already be recorded");
				GraphModel expected = history.stateAt(lsn);

				Random rnd = new Random(SEED);
				for (int i = 0; i < 300; i++) {
					// Committed on this same thread, which is allowed: a writer reads the current mapping, never its
					// own view. That rule is exactly what this case would catch the loss of.
					commitOne(store, model, history, pool, rnd);
					if (i % 25 == 0) {
						List<String> problems = ModelHistory.differences(store, expected);
						assertTrue(problems.isEmpty(), "after " + (i + 1) + " later commits the held view at LSN "
							+ lsn + " drifted:\n  " + String.join("\n  ", problems));
					}
				}

				List<String> problems = ModelHistory.differences(store, expected);
				assertTrue(problems.isEmpty(), "the held view drifted by the end:\n  " + String.join("\n  ", problems));
			}

			// And with the view closed, the store shows the newest state rather than the held one.
			List<String> problems = ModelHistory.differences(store, model);
			assertTrue(problems.isEmpty(), "with no view open the store should show the latest committed state:\n  "
				+ String.join("\n  ", problems));
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- workload

	private Hammer writer(GraphStore store, GraphModel model, ModelHistory history, List<Long> pool) {
		Random rnd = new Random(SEED);
		return Hammer.named("linearizability-writer")
			.threads(1)
			.opsPerThread(400)
			.op("createNode", 3, r -> commit(store, model, history, () -> createNode(store, model, pool, rnd)))
			.op("setProperty", 4, r -> commit(store, model, history, () -> setProperty(store, model, pool, rnd)))
			.op("createEdge", 2, r -> commit(store, model, history, () -> createEdge(store, model, pool, rnd)))
			.op("deleteNode", 1, r -> commit(store, model, history, () -> deleteNode(store, model, pool, rnd)));
	}

	private void commitOne(GraphStore store, GraphModel model, ModelHistory history, List<Long> pool, Random rnd)
		throws Exception {
		switch (rnd.nextInt(4)) {
			case 0 -> commit(store, model, history, () -> createNode(store, model, pool, rnd));
			case 1 -> commit(store, model, history, () -> createEdge(store, model, pool, rnd));
			case 2 -> commit(store, model, history, () -> deleteNode(store, model, pool, rnd));
			default -> commit(store, model, history, () -> setProperty(store, model, pool, rnd));
		}
	}

	@FunctionalInterface
	private interface Mutation {
		/** Applies to the store and returns the matching change to make to the model, or null for a no-op. */
		Runnable apply() throws Exception;
	}

	/**
	 * Runs one mutation as one transaction and records the resulting state against its commit LSN.
	 * <p>
	 * The model is advanced <em>after</em> the commit and before the record, so the state filed under a commit LSN is
	 * exactly the state a reader fixed at that LSN is entitled to see.
	 */
	private void commit(GraphStore store, GraphModel model, ModelHistory history, Mutation mutation) throws Exception {
		Runnable modelChange;
		long lsn;
		try (Transaction txn = store.begin()) {
			modelChange = mutation.apply();
			lsn = txn.commit();
		}
		if (modelChange != null) {
			modelChange.run();
		}
		history.record(lsn, model);
	}

	private Runnable createNode(GraphStore store, GraphModel model, List<Long> pool, Random rnd) throws Exception {
		if (pool.size() >= POOL) {
			return null;
		}
		String label = "L" + rnd.nextInt(3);
		long value = rnd.nextInt(1000);
		long id = store.createNode(label, Properties.of().set("n", value));
		return () -> {
			model.createNode(id, label, Map.of("n", value));
			pool.add(id);
		};
	}

	private Runnable setProperty(GraphStore store, GraphModel model, List<Long> pool, Random rnd) throws Exception {
		if (pool.isEmpty()) {
			return null;
		}
		long id = pool.get(rnd.nextInt(pool.size()));
		long value = rnd.nextInt(1000);
		store.setNodeProperties(id, Properties.of().set("n", value));
		return () -> model.setProperties(id, Map.of("n", value));
	}

	private Runnable createEdge(GraphStore store, GraphModel model, List<Long> pool, Random rnd) throws Exception {
		if (pool.size() < 2) {
			return null;
		}
		long from = pool.get(rnd.nextInt(pool.size()));
		long to = pool.get(rnd.nextInt(pool.size()));
		if (from == to) {
			return null;
		}
		store.createEdge(from, "LINKS", to);
		return () -> model.createEdge(from, "LINKS", to);
	}

	private Runnable deleteNode(GraphStore store, GraphModel model, List<Long> pool, Random rnd) throws Exception {
		if (pool.size() <= 4) {
			return null;
		}
		long id = pool.remove(rnd.nextInt(pool.size()));
		store.deleteNode(id);
		return () -> model.deleteNode(id);
	}

	private void seed(GraphStore store, GraphModel model, ModelHistory history, List<Long> pool) throws Exception {
		Random rnd = new Random(SEED);
		for (int i = 0; i < 8; i++) {
			commit(store, model, history, () -> createNode(store, model, pool, rnd));
		}
		for (int i = 0; i < 6; i++) {
			commit(store, model, history, () -> createEdge(store, model, pool, rnd));
		}
	}
}
