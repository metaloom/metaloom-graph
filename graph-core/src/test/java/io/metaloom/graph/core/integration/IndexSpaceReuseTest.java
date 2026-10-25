package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The index gives its space back, rebuild after rebuild.
 *
 * <p>
 * Compaction rebuilds the B+trees wholesale — {@code resetTrees()} frees every page and the caller refills from the
 * live records — because relocating tree pages individually would mean fixing up a parent pointer, a sibling pointer
 * and possibly a root for each one. The freed pages go back to the allocator, so the arithmetic ought to be flat: free
 * N, allocate about N, high water mark unchanged.
 * </p>
 *
 * <p>
 * It was not. The free ring is FIFO, so the refill scattered itself across the freed range in the order the pages had
 * been walked; one live page landing near the top was enough to stop {@code shrinkHighWater()} trimming anything; and
 * the mark ratcheted up on every single rebuild. Measured on the soak workload: <b>57 live index pages sitting under a
 * high water mark of 252</b>, with `SoakTest` reporting bytes per element climbing from 346 to 606 and failing its
 * growth bound.
 * </p>
 *
 * <p>
 * <b>Why that was worse than it looked.</b> With hole punching available the wasted pages are all-free, so
 * {@code reclaim()} returns their blocks and real disk usage stays flat — the damage is confined to a metric. But
 * {@code reclaimEmptyPages()} returns immediately when the native library is absent, and CI runs the whole suite that
 * way. Without punching the ratchet is a genuine unbounded disk leak: the same workload grew the index segment from
 * 762 KB to 2.2 MB and was still climbing. So this test asserts the mark itself rather than the disk, because the mark
 * is the thing that is wrong in both configurations.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/INDEX.md">spec/INDEX.md</a>
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 1
 */
@Tag("unit")
public class IndexSpaceReuseTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int NODES = 2500;
	private static final int ROUNDS = 15;

	@Test
	public void testRepeatedRebuildsDoNotRatchetTheHighWaterMark() throws Exception {
		try (GraphStore store = stores.open()) {
			Random random = new Random(20260817L);
			List<Long> live = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < NODES; i++) {
					live.add(store.createNode("N", Properties.of().set("i", (long) i)));
				}
				txn.commit();
			}
			store.compact(CompactionOptions.always());

			long baselineMark = indexHighWater(store);
			long baselineStorage = store.stats().storageBytes();

			for (int round = 0; round < ROUNDS; round++) {
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < 400; i++) {
						live.add(store.createNode("N", Properties.of().set("i", (long) random.nextInt(100_000))));
						if (live.size() > NODES) {
							store.deleteNode(live.remove(random.nextInt(live.size())));
						}
						store.setNodeProperties(live.get(random.nextInt(live.size())),
							Properties.of().set("i", (long) random.nextInt(100_000)));
					}
					txn.commit();
				}
				store.reclaim();
				store.compact(CompactionOptions.always());
				live.clear();
				live.addAll(store.nodesByLabel("N"));
			}

			long mark = indexHighWater(store);
			long livePages = indexSet(store).liveCount();

			// The mark must stay in the neighbourhood of what is actually live. Before the fix it reached four times
			// the live count and kept going; the slack here is for the tree genuinely changing shape round to round,
			// not for a ratchet.
			assertTrue(mark <= livePages * 3 / 2 + 8,
				"the index high water mark has ratcheted away from what is live: mark=" + mark + " live=" + livePages
					+ " (baseline mark was " + baselineMark + "). Freed index pages are not being reused, so the"
					+ " segment extends on every rebuild and never comes back.");

			// And the user-visible number the soak watches must not have run away either.
			long storage = store.stats().storageBytes();
			assertTrue(storage < baselineStorage * 2,
				"committed bytes grew from " + baselineStorage + " to " + storage + " over " + ROUNDS
					+ " rebuilds on a live set that did not grow");

			store.verify(VerifyLevel.FULL);
			System.out.println("[index-reuse] after " + ROUNDS + " rebuilds: mark=" + mark + " live=" + livePages
				+ " storage=" + storage + " (baseline mark=" + baselineMark + " storage=" + baselineStorage + ")");
		}
	}

	private SegmentSet indexSet(GraphStore store) {
		return ((GraphStoreImpl) store).segmentSets().get(Kind.INDEX);
	}

	private long indexHighWater(GraphStore store) {
		long mark = 0;
		for (var segment : indexSet(store).segments()) {
			mark += segment.superblock().highWaterSlot();
		}
		return mark;
	}
}
