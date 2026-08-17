package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * An open {@link NeighbourCursor} holds a read latch, and a commit needs that latch exclusively.
 *
 * <p>
 * So a cursor nobody closes does not merely leak — it <b>stops every writer in the process</b>, with no error
 * anywhere. That is a sharp edge on an ordinary-looking API: {@code neighbourCursor()} returns something that must be
 * closed for the store to keep working, not merely to keep it tidy.
 * </p>
 *
 * <p>
 * These cases pin the behaviour rather than call it a defect. Holding the latch is what makes a cursor a stable
 * traversal rather than a sequence of unrelated reads, and the alternative — a cursor that can see its own adjacency
 * chain change under it — is worse. What the tests establish is that the cost is exactly "until close", that closing
 * really does release it, and that closing twice is safe, because a stamped lock unlocked twice is corrupted rather
 * than merely wrong.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 8
 */
@Tag("unit")
public class CursorLeaseLivenessTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/**
	 * The load-bearing case: a writer blocked behind an open cursor, and released the instant it closes. Both halves
	 * matter — the first is the hazard, the second is the proof that it is bounded.
	 */
	@Test
	public void testAnOpenCursorHoldsWritersAndClosingReleasesThem() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> nodes = seed(store, 40);

			CountDownLatch committed = new CountDownLatch(1);
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread writer;

			try (NeighbourCursor cursor = store.neighbourCursor(nodes.get(0), Direction.OUT, null)) {
				assertTrue(cursor.next(), "the seeded node should have at least one neighbour");

				writer = Thread.ofPlatform().name("blocked-writer").start(() -> {
					try {
						store.createNode("Late", Properties.of());
						committed.countDown();
					} catch (Throwable e) {
						failure.set(e);
					}
				});

				// The writer must NOT get through while the cursor is open. A short wait, because this is asserting an
				// absence and the cost of the assertion is paid on every run.
				assertFalse(committed.await(400, TimeUnit.MILLISECONDS),
					"a writer committed while a cursor held the read latch, which means the cursor is not actually"
						+ " giving its traversal a stable view");
			}

			assertTrue(committed.await(30, TimeUnit.SECONDS),
				"the writer was still blocked after the cursor closed, so the latch was leaked and every future write"
					+ " in this process would block forever");
			writer.join(30_000);
			if (failure.get() != null) {
				throw new AssertionError("the blocked writer failed once released", failure.get());
			}
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Closing twice must be a no-op. Unlocking a {@code StampedLock} stamp twice corrupts it rather than throwing. */
	@Test
	public void testClosingACursorTwiceIsSafe() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> nodes = seed(store, 10);

			NeighbourCursor cursor = store.neighbourCursor(nodes.get(0), Direction.OUT, null);
			cursor.next();
			cursor.close();
			cursor.close();

			// If the second close had corrupted the latch, this write would hang or fail.
			store.createNode("After", Properties.of());
			assertEquals(0, store.snapshotStats().openViews(), "no view should be left open");
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Cursors opened and closed promptly from several threads must not interfere with each other or with writers. */
	@Test
	public void testManyShortLivedCursorsDoNotWedgeTheStore() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> nodes = seed(store, 40);

			Hammer.named("cursors")
				.threads(6)
				.opsPerThread(120)
				.op("cursor", 3, rnd -> {
					long id = nodes.get(rnd.nextInt(nodes.size()));
					try (NeighbourCursor cursor = store.neighbourCursor(id, Direction.OUT, null)) {
						int seen = 0;
						while (cursor.next() && seen < 16) {
							cursor.edge();
							seen++;
						}
					}
				})
				.op("write", 2, rnd -> store.setNodeProperties(nodes.get(rnd.nextInt(nodes.size())),
					Properties.of().set("n", (long) rnd.nextInt(1000))))
				.op("edge", 1, rnd -> {
					long from = nodes.get(rnd.nextInt(nodes.size()));
					long to = nodes.get(rnd.nextInt(nodes.size()));
					if (from != to) {
						store.createEdge(from, "MORE", to);
					}
				})
				.run()
				.assertRan("cursor", "write", "edge");

			assertEquals(0, store.snapshotStats().openViews(), "a view was left open by a cursor");
			store.verify(VerifyLevel.FULL);
		}
	}

	private List<Long> seed(GraphStore store, int count) throws Exception {
		List<Long> nodes = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < count; i++) {
				nodes.add(store.createNode("N", Properties.of().set("n", (long) i)));
			}
			for (int i = 1; i < count; i++) {
				store.createEdge(nodes.get(0), "NEXT", nodes.get(i));
			}
			txn.commit();
		}
		return nodes;
	}
}
