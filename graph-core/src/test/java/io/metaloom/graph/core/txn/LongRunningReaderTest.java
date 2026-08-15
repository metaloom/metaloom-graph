package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotStats;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * A reader held open across a long run of writes: [TRANSACTIONS.md §9](../../../../../../../../spec/TRANSACTIONS.md)'s
 * {@code LongRunningReaderTest}. The view must stay stable, and the memory it costs must be <b>bounded and reported</b>
 * rather than merely finite.
 *
 * <p>
 * The classic MVCC failure mode is not incorrectness, it is that version storage grows quietly until something else
 * fails. So the assertions here are as much about {@link SnapshotStats} being truthful as about the data being right —
 * an engine that grows unboundedly while reporting nothing is the thing to prevent.
 * </p>
 */
@Tag("slow")
public class LongRunningReaderTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int NODES = 2_000;
	private static final int WRITES = 60_000;
	private static final long RETAINED_BUDGET = 1L << 30;

	/**
	 * One reader, sixty thousand commits underneath it, and a view that never moves.
	 *
	 * <p>
	 * The reader re-reads a fixed sample throughout rather than once at the start and once at the end, so a view that
	 * drifted at any point during the run is caught at that point rather than averaged away.
	 * </p>
	 */
	@Test
	public void testAViewSurvivesSixtyThousandCommits() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.retainedVersionBytes(RETAINED_BUDGET)
			.build();

		try (GraphStore store = stores.open(options)) {
			List<Long> nodes = new ArrayList<>(NODES);
			for (int i = 0; i < NODES; i++) {
				nodes.add(store.createNode("Person", Properties.of().set("n", 0L).set("i", i)));
			}

			Random random = new Random(20260814L);
			List<Long> sample = new ArrayList<>();
			for (int i = 0; i < 64; i++) {
				sample.add(nodes.get(random.nextInt(NODES)));
			}

			long peakRetained = 0;
			try (ReadTransaction view = store.beginRead()) {
				for (long node : sample) {
					assertEquals(0L, store.nodeProperty(node, "n"), "the view must start where it says it does");
				}

				for (int write = 1; write <= WRITES; write++) {
					long target = nodes.get(random.nextInt(NODES));
					store.setNodeProperties(target, Properties.of().set("n", (long) write).set("i", 0));

					if (write % 2_000 == 0) {
						for (long node : sample) {
							assertEquals(0L, store.nodeProperty(node, "n"),
								"the view moved at write " + write);
						}
						peakRetained = Math.max(peakRetained, store.snapshotStats().retainedBytes());
					}
				}

				SnapshotStats stats = store.snapshotStats();
				assertEquals(1, stats.openViews());
				assertEquals(view.snapshotLsn(), stats.oldestOpenLsn());
				assertTrue(stats.oldestOpenAgeMillis() >= 0);
				assertEquals(0, stats.abandonedViews(), "the budget was ample; nothing should have been given up");

				// Retention is bounded by the budget, and the reader's own reconstruction is bounded by what it
				// read — two different things, and only the second is small.
				//
				// Measured: 200.6 MiB peak over 990,899 entries on 392 pages, against 192 KiB rebuilt by the reader.
				// The gap is the point. Entries accumulate per overwritten byte run per commit, so a page every
				// commit touches — the segment metadata page above all — collects one entry per commit whether or
				// not any reader will ever need more than the oldest. Collapsing those is the obvious next
				// optimisation and is not done; see spec/TRANSACTIONS.md section 5.1.
				assertTrue(peakRetained < RETAINED_BUDGET,
					"retention must stay inside the configured budget, peaked at " + peakRetained);
				assertTrue(view.retainedBytes() < 8L * 1024 * 1024,
					"the reader rebuilt only the pages it read, was " + view.retainedBytes());

				System.out.printf("long reader: %d writes, peak retained %.1f MiB, reader rebuilt %.1f KiB, "
					+ "%d entries over %d pages%n", WRITES, peakRetained / 1048576.0,
					view.retainedBytes() / 1024.0, stats.retainedEntries(), stats.versionedPages());
			}

			// Everything comes back once the view is gone.
			store.createNode("Trigger");
			assertEquals(0, store.snapshotStats().retainedBytes(),
				"closing the reader must release every byte it was holding");
		}
	}

	/**
	 * The same run against a budget far too small for it: the reader must be abandoned, not served wrong data, and the
	 * store must keep working afterwards.
	 */
	@Test
	public void testABudgetedReaderIsAbandonedCleanlyUnderLoad() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.retainedVersionBytes(64 * 1024)
			.build();

		try (GraphStore store = stores.open(options)) {
			List<Long> nodes = new ArrayList<>();
			for (int i = 0; i < 200; i++) {
				nodes.add(store.createNode("Person", Properties.of().set("n", 0L)));
			}

			Random random = new Random(7L);
			SnapshotTooOldException abandoned = null;
			int survivedWrites = 0;

			try (ReadTransaction view = store.beginRead()) {
				for (int write = 1; write <= 20_000 && abandoned == null; write++) {
					store.setNodeProperties(nodes.get(random.nextInt(nodes.size())),
						Properties.of().set("n", (long) write));
					survivedWrites = write;
					try {
						assertEquals(0L, store.nodeProperty(nodes.get(0), "n"),
							"while alive the view must answer as of its own LSN");
					} catch (SnapshotTooOldException e) {
						abandoned = e;
					}
				}
			}

			assertTrue(abandoned != null, "a 64 KiB budget must not survive 20,000 commits");
			assertTrue(store.snapshotStats().abandonedViews() > 0);
			System.out.printf("budgeted reader: abandoned after %d writes at horizon LSN %d%n",
				survivedWrites, abandoned.horizonLsn());

			// The store is unharmed: the writer never noticed, and a new reader works.
			AtomicReference<Throwable> failed = new AtomicReference<>();
			Thread fresh = new Thread(() -> {
				try (ReadTransaction view = store.beginRead()) {
					store.nodeProperty(nodes.get(0), "n");
					List<Edge> edges = store.neighbours(nodes.get(0), Direction.OUT);
					assertTrue(edges.isEmpty());
				} catch (Throwable t) {
					failed.set(t);
				}
			});
			fresh.start();
			fresh.join();
			assertNull(failed.get(), String.valueOf(failed.get()));
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}
}
