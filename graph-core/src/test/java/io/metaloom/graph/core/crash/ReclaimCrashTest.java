package io.metaloom.graph.core.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.nativelib.NativeLib;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * {@code reclaim()} followed by ordinary commits, then a crash.
 *
 * <p>
 * Reclaim changes the segment files outside the log: it punches pages, raises generation floors and fixes up page
 * checksums in the page metadata array, and — through {@code SegmentFile.force()} — rewrites the A/B superblock copies.
 * None of that is a log record. Redo recovery replays byte runs from the last checkpoint, and every run a later commit
 * logs is the difference against a base that already includes those unlogged writes. Replaying the older runs over the
 * reclaimed files therefore puts back bytes that the later runs, being differences, never mention again.
 * </p>
 *
 * <p>
 * Found by {@code StorageModelTest} once it ran with the native layer: a superblock whose live count disagreed with its
 * bitmap after recovery, and — in a search over short sequences — a store whose <b>both</b> superblock copies came back
 * with bad checksums, which does not open at all. Three nodes, one reclaim and two deletes are enough.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 6
 */
@Tag("unit")
public class ReclaimCrashTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testCommitsAfterAReclaimSurviveACrash() throws Exception {
		assumeTrue(NativeLib.isAvailable(), "without the native layer reclaim() does nothing");
		GraphStore store = stores.open();
		List<Long> nodes = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			nodes.add(store.createNode("A"));
		}
		store.checkpoint();
		for (int i = 0; i < 3; i++) {
			long node = store.createNode("N");
			nodes.add(node);
			for (int e = 0; e < 10; e++) {
				store.createEdge(node, "E", nodes.get(e % 6));
			}
		}
		store.reclaim();
		store.deleteNode(nodes.remove(nodes.size() - 1));
		store.deleteNode(nodes.remove(nodes.size() - 1));
		((GraphStoreImpl) store).abandon();

		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL);
			assertEquals(nodes.size(), reopened.stats().nodeCount());
			for (long node : nodes) {
				assertTrue(reopened.nodeExists(node));
			}
		}
	}

	/**
	 * The same shape, several times over: reclaims interleaved with writes, then a crash, then a store that verifies.
	 * Before the fix seeds 0, 3 and 5 of these failed — 3 with both superblock copies unreadable.
	 */
	@Test
	public void testRandomReclaimsBetweenWritesSurviveACrash() throws Exception {
		runSeeds(0, 6);
	}

	/** Many more of them, for the slow profile. */
	@Test
	@Tag("slow")
	public void testManyRandomReclaimsBetweenWritesSurviveACrash() throws Exception {
		runSeeds(6, Integer.getInteger("reclaimcrash.seeds", 300));
	}

	private void runSeeds(long from, long to) throws Exception {
		assumeTrue(NativeLib.isAvailable(), "without the native layer reclaim() does nothing");
		for (long seed = from; seed < to; seed++) {
			java.nio.file.Path dir = stores.path().resolve("seed-" + seed);
			Random random = new Random(seed);
			GraphStore store = GraphStore.open(dir, TempStore.options());
			List<Long> nodes = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				nodes.add(store.createNode("A"));
			}
			store.checkpoint();
			for (int step = 0; step < 30; step++) {
				switch (random.nextInt(5)) {
					case 0 -> {
						long node = store.createNode("N");
						nodes.add(node);
						for (int e = 0; e < 10; e++) {
							store.createEdge(node, "E", nodes.get(e % 6));
						}
					}
					case 1 -> {
						long node = nodes.get(random.nextInt(nodes.size()));
						for (int e = 0; e < 20; e++) {
							store.createEdge(node, "E", nodes.get(e % 6));
						}
					}
					case 2 -> {
						if (nodes.size() > 7) {
							store.deleteNode(nodes.remove(6 + random.nextInt(nodes.size() - 6)));
						}
					}
					case 3 -> store.reclaim();
					default -> {
						long node = nodes.get(random.nextInt(nodes.size()));
						store.createEdge(node, "F", node);
					}
				}
			}
			store.verify(VerifyLevel.FULL);
			((GraphStoreImpl) store).abandon();
			try (GraphStore reopened = GraphStore.open(dir, TempStore.options())) {
				reopened.verify(VerifyLevel.FULL);
				assertEquals(nodes.size(), reopened.stats().nodeCount(), "seed " + seed);
			} catch (RuntimeException e) {
				throw new AssertionError("seed " + seed + ": " + e.getMessage(), e);
			}
		}
	}
}
