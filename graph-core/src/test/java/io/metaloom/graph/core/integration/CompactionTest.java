package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.CompactionReport;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * Compaction: the one operation that moves records and therefore breaks ids.
 * <p>
 * Every test here is built the same way, because the interesting property is not "it got smaller" but "nothing was lost
 * or misresolved". A store is churned down to a low liveness, compacted, and then rebuilt from the outside: survivors
 * are re-found through the label index rather than through the ids the test was holding, and the whole graph is
 * compared against a plain in-memory model. {@link VerifyLevel#FULL} is asserted afterwards, which is the
 * {@code NoDanglingRefs} safety net the plan calls for.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 9
 */
@Tag("unit")
public class CompactionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	// ---------------------------------------------------------------- the headline property

	@Test
	public void testChurnedStoreBecomesDenseAgain() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> all = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 4000; i++) {
					all.add(store.createNode(i % 10 == 0 ? "Keep" : "Drop", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < all.size(); i++) {
					if (i % 10 != 0) {
						store.deleteNode(all.get(i));
					}
				}
				txn.commit();
			}
			store.checkpoint();

			GraphStoreImpl impl = (GraphStoreImpl) store;
			double before = impl.segments(Kind.NODE).liveness();
			assertTrue(before < 0.2, "the churn should have left the node segments sparse, was " + before);

			CompactionReport report = store.compact(CompactionOptions.always());

			double after = impl.segments(Kind.NODE).liveness();
			assertTrue(after > 0.9, "compaction must reach 90% liveness, reached " + after + " (" + report + ")");
			assertTrue(report.totalRelocated() > 0, "records must actually have moved: " + report);
			assertTrue(report.storageBytesReclaimed() > 0, "committed bytes must have come down: " + report);

			// Everything that survived is still there, found without any of the ids the test was holding.
			List<Long> survivors = store.nodesByLabel("Keep");
			assertEquals(400, survivors.size());
			Set<Integer> found = new TreeSet<>();
			for (long id : survivors) {
				found.add(store.nodeProperties(id).getInt("i"));
			}
			assertEquals(400, found.size());
			for (int value : found) {
				assertEquals(0, value % 10, "only the multiples of ten were kept");
			}
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The dangerous failure mode is not a lost id but a <em>reused</em> one. An id held across a compaction must either
	 * still name the same record or name nothing at all, and never a different record.
	 */
	@Test
	public void testHeldIdsAreNeverMisresolved() throws Exception {
		try (GraphStore store = stores.open()) {
			Map<Long, Integer> expected = new HashMap<>();
			List<Long> doomed = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 2000; i++) {
					long id = store.createNode("N", Properties.of().set("i", i));
					if (i % 4 == 0) {
						expected.put(id, i);
					} else {
						doomed.add(id);
					}
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (long id : doomed) {
					store.deleteNode(id);
				}
				txn.commit();
			}

			CompactionReport report = store.compact(CompactionOptions.always());
			assertTrue(report.totalRelocated() > 0, "nothing moved, the test proves nothing: " + report);

			int stale = 0;
			for (Map.Entry<Long, Integer> entry : expected.entrySet()) {
				if (!store.nodeExists(entry.getKey())) {
					stale++;
					continue;
				}
				assertEquals(entry.getValue(), store.nodeProperties(entry.getKey()).getInt("i"),
					"id " + entry.getKey() + " survived compaction but now names a different record");
			}
			assertTrue(stale > 0, "with " + report.totalRelocated() + " records moved, some held ids must be stale");
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- every reference class

	@Test
	public void testEveryReferenceClassSurvives() throws Exception {
		try (GraphStore store = stores.open()) {
			// A payload well past the 255 byte inline limit, so it escapes into the blob store and the property block
			// holds a blob id that compaction has to rewrite.
			String longText = "x".repeat(4000);
			Model model = new Model();

			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 600; i++) {
					String label = i % 3 == 0 ? "Keep" : "Drop";
					long id = store.createNode(label, Properties.of()
						.set("i", i)
						.set("text", longText + i));
					model.add(id, label, i);
				}
				// Lightweight edges, materialised relationships with properties, and a supernode.
				List<Long> keeps = model.keeps();
				for (int i = 1; i < keeps.size(); i++) {
					store.createEdge(keeps.get(i - 1), "NEXT", keeps.get(i));
					model.edge(keeps.get(i - 1), keeps.get(i), "NEXT");
				}
				for (int i = 1; i < keeps.size(); i++) {
					store.createRelationship(keeps.get(0), "HUB", keeps.get(i),
						Properties.of().set("weight", i * 1.5));
					model.edge(keeps.get(0), keeps.get(i), "HUB");
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (long id : model.drops()) {
					store.deleteNode(id);
				}
				txn.commit();
			}
			model.dropAll();
			store.verify(VerifyLevel.FULL);

			store.compact(CompactionOptions.always());
			store.verify(VerifyLevel.FULL);

			List<Long> survivors = store.nodesByLabel("Keep");
			assertEquals(model.keeps().size(), survivors.size());

			// Rebuild the graph from the outside and compare it to the model, keyed on the "i" property rather than on
			// any id, since every id is suspect after a compaction.
			Map<Integer, Set<String>> actual = new HashMap<>();
			for (long id : survivors) {
				Properties props = store.nodeProperties(id);
				int i = props.getInt("i");
				assertEquals(longText + i, props.getString("text"), "the blob escape must survive relocation");
				Set<String> edges = new HashSet<>();
				for (Edge edge : store.neighbours(id, Direction.BOTH)) {
					edges.add(edge.type() + ":" + store.nodeProperties(edge.neighbourId()).getInt("i"));
				}
				actual.put(i, edges);
			}
			assertEquals(model.adjacencyByValue(), actual, "the graph must be identical after compaction");
		}
	}

	@Test
	public void testRelationshipPropertiesSurviveRelocation() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> nodes = new ArrayList<>();
			List<Long> doomed = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 400; i++) {
					nodes.add(store.createNode("N", Properties.of().set("i", i)));
				}
				for (int i = 1; i < nodes.size(); i++) {
					long rel = store.createRelationship(nodes.get(0), "R", nodes.get(i),
						Properties.of().set("weight", i));
					if (i % 2 == 0) {
						doomed.add(rel);
					}
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (long rel : doomed) {
					store.deleteRelationship(rel);
				}
				txn.commit();
			}

			store.compact(CompactionOptions.always());
			store.verify(VerifyLevel.FULL);

			// The hub keeps every odd numbered relationship, with its weight intact.
			List<Long> hubs = store.nodesByProperty("i", 0);
			assertEquals(1, hubs.size());
			Set<Integer> weights = new TreeSet<>();
			for (Edge edge : store.neighbours(hubs.get(0), Direction.OUT)) {
				weights.add(store.relationshipProperties(edge.relationshipId()).getInt("weight"));
			}
			assertEquals(200, weights.size(), "every surviving relationship must still be reachable");
			for (int weight : weights) {
				assertEquals(1, weight % 2, "only the odd weights were kept");
			}
		}
	}

	// ---------------------------------------------------------------- adjacency and segments

	@Test
	public void testAdjacencyChainsAreDefragmented() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub;
			List<Long> spokes = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 40000; i++) {
					long spoke = store.createNode("Spoke", Properties.of().set("i", i));
					store.createEdge(hub, "E", spoke);
					spokes.add(spoke);
				}
				txn.commit();
			}
			GraphStoreImpl impl = (GraphStoreImpl) store;
			int blocksBefore = impl.adjacencyStore().blockCount(hub);
			assertTrue(blocksBefore >= 2, "the hub needs a real chain for this test, had " + blocksBefore);

			try (Transaction txn = store.begin()) {
				for (int i = 0; i < spokes.size(); i++) {
					if (i % 20 != 0) {
						store.deleteNode(spokes.get(i));
					}
				}
				txn.commit();
			}

			store.compact(CompactionOptions.always());
			store.verify(VerifyLevel.FULL);

			long survivingHub = store.nodesByLabel("Hub").get(0);
			int blocksAfter = impl.adjacencyStore().blockCount(survivingHub);
			assertTrue(blocksAfter < blocksBefore,
				"a chain emptied to 5% must shrink: " + blocksBefore + " -> " + blocksAfter);
			assertEquals(2000, store.neighbours(survivingHub, Direction.OUT).size(),
				"and it must still hold every surviving edge");
		}
	}

	/**
	 * Several consecutive blocks of one chain relocated in the same batch.
	 *
	 * <p>
	 * The reference rewrite reached blocks by walking chains from the node heads, and skipped every source. A
	 * destination is reached only through its predecessor, so when the predecessor moved too its copy was never visited
	 * and its next pointer still named the next block's source — which the batch then frees. The defragmentation test
	 * above cannot see it: it empties the hub first, so its chain is down to a block or two before anything moves.
	 * </p>
	 */
	@Test
	public void testConsecutiveChainBlocksRelocatedTogetherStayLinked() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			List<Long> fillers = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				long sink = store.createNode("Sink");
				for (int i = 0; i < 256; i++) {
					long filler = store.createNode("Filler");
					for (int j = 0; j < 48; j++) {
						store.createEdge(filler, "F", sink);
					}
					fillers.add(filler);
				}
				txn.commit();
			}
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 30000; i++) {
					store.createEdge(hub, "E", store.createNode("Spoke"));
				}
				txn.commit();
			}
			int blocks = impl.adjacencyStore().blockCount(hub);
			assertTrue(blocks >= 4, "the hub needs a long chain for this test, had " + blocks);

			// Frees the low adjacency cells, so the hub's blocks are what compaction moves down into them.
			try (Transaction txn = store.begin()) {
				for (long filler : fillers) {
					store.deleteNode(filler);
				}
				txn.commit();
			}

			CompactionReport report = store.compact(CompactionOptions.always());
			assertTrue(report.relocated().getOrDefault(Kind.ADJ, 0L) > 0, "adjacency must have moved: " + report);
			store.verify(VerifyLevel.FULL);
			long survivingHub = store.nodesByLabel("Hub").get(0);
			assertEquals(30000, store.neighbours(survivingHub, Direction.OUT).size(),
				"every edge must still be reachable through the relocated chain");
		}
	}

	/**
	 * A property that names another element is rewritten when that element moves, and the property index has to follow
	 * it: the index is keyed on the value's hash, and the value just changed under it. The reference sweep rewrote the
	 * payload and left the entry keyed on the old id, so the lookup by the new value missed the holder and the old entry
	 * dangled for good.
	 */
	@Test
	public void testAnIndexedReferenceFollowsTheNodeItNames() throws Exception {
		try (GraphStore store = stores.open()) {
			long holder = store.createNode("Holder");
			List<Long> fillers = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 2000; i++) {
					fillers.add(store.createNode("Filler"));
				}
				txn.commit();
			}
			long target = store.createNode("Target");
			store.setNodeProperties(holder,
				Properties.of().set("friend", new io.metaloom.graph.core.store.prop.ValueCodec.ElementRef(target)));
			try (Transaction txn = store.begin()) {
				for (long filler : fillers) {
					store.deleteNode(filler);
				}
				txn.commit();
			}

			// No index rebuild: a rebuild from the records would repair the entry and hide the defect.
			store.compact(CompactionOptions.builder().livenessThreshold(1.0).indexFillThreshold(0.0).build());
			store.verify(VerifyLevel.FULL);

			long movedHolder = store.nodesByLabel("Holder").get(0);
			long movedTarget = store.nodesByLabel("Target").get(0);
			assertNotEquals(target, movedTarget, "the target must have moved for this test to mean anything");
			assertEquals(List.of(movedHolder), store.nodesByProperty("friend",
				new io.metaloom.graph.core.store.prop.ValueCodec.ElementRef(movedTarget)),
				"the holder must be found by the reference as it now reads");
			assertTrue(store.nodesByProperty("friend",
				new io.metaloom.graph.core.store.prop.ValueCodec.ElementRef(target)).isEmpty(),
				"and not by the id the target no longer has");
		}
	}

	@Test
	public void testEmptiedSegmentsAreRetired() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			List<Long> ids = new ArrayList<>();
			// The test segment capacity is 8 MiB, so 131072 node slots. Fill past one segment.
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 150000; i++) {
					ids.add(store.createNode("N"));
				}
				txn.commit();
			}
			assertTrue(impl.segments(Kind.NODE).segmentCount() >= 2,
				"the fixture must produce more than one node segment");

			try (Transaction txn = store.begin()) {
				for (int i = 20000; i < ids.size(); i++) {
					store.deleteNode(ids.get(i));
				}
				txn.commit();
			}
			store.checkpoint();

			CompactionReport report = store.compact(CompactionOptions.always());
			assertEquals(1, impl.segments(Kind.NODE).segmentCount(),
				"the emptied segment must have been unlinked: " + report);
			assertTrue(report.segmentsRetired() >= 1, report.toString());
			assertEquals(20000, store.stats().nodeCount());
			store.verify(VerifyLevel.FULL);

			// And the store must still be usable, including allocating into the segment that is left.
			long fresh = store.createNode("Fresh", Properties.of().set("k", "v"));
			assertEquals("v", store.nodeProperties(fresh).getString("k"));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testUnderfullIndexPagesAreReclaimed() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 30000; i++) {
					ids.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			long indexPagesBefore = impl.segments(Kind.INDEX).liveCount();

			// Delete almost everything. The B+tree does not merge underfull nodes, so every one of those pages is
			// still allocated afterwards while holding almost nothing.
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < ids.size(); i++) {
					if (i % 50 != 0) {
						store.deleteNode(ids.get(i));
					}
				}
				txn.commit();
			}
			assertEquals(indexPagesBefore, impl.segments(Kind.INDEX).liveCount(),
				"deleting keys must not free a single index page, which is what makes the rebuild necessary");

			CompactionReport report = store.compact(CompactionOptions.always());
			assertTrue(report.indexPagesFreed() > 0, "the underfull index must have been rebuilt: " + report);
			assertTrue(impl.segments(Kind.INDEX).liveCount() < indexPagesBefore,
				"and the rebuild must leave fewer pages: " + indexPagesBefore + " -> "
					+ impl.segments(Kind.INDEX).liveCount());

			// The rebuilt index must answer exactly as before.
			List<Long> survivors = store.nodesByLabel("N");
			assertEquals(600, survivors.size());
			for (long id : survivors) {
				int value = store.nodeProperties(id).getInt("i");
				assertEquals(0, value % 50);
				assertEquals(List.of(id), store.nodesByProperty("i", value),
					"the property index must still find the node by value");
			}
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- policy

	@Test
	public void testDenseStoreIsLeftAlone() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 500; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}
			CompactionReport report = store.compact();
			assertEquals(0, report.totalRelocated(),
				"nothing should move in a store that was never churned: " + report);
			assertEquals(500, store.stats().nodeCount());
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testCompactionSurvivesAReopen() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 2000; i++) {
					ids.add(store.createNode(i % 5 == 0 ? "Keep" : "Drop", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < ids.size(); i++) {
					if (i % 5 != 0) {
						store.deleteNode(ids.get(i));
					}
				}
				txn.commit();
			}
			store.compact(CompactionOptions.always());
		}
		try (GraphStore reopened = stores.open()) {
			assertEquals(400, reopened.stats().nodeCount());
			List<Long> survivors = reopened.nodesByLabel("Keep");
			assertEquals(400, survivors.size(), "the rebuilt index must survive the reopen");
			for (long id : survivors) {
				assertEquals(0, reopened.nodeProperties(id).getInt("i") % 5);
			}
			reopened.verify(VerifyLevel.FULL);

			// A second compaction of an already dense store must be a no-op rather than churn.
			CompactionReport second = reopened.compact(CompactionOptions.always());
			assertNotEquals(null, second);
			assertEquals(400, reopened.stats().nodeCount());
			reopened.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testCompactionInterleavesWithForegroundWrites() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 3000; i++) {
					ids.add(store.createNode(i % 3 == 0 ? "Keep" : "Drop", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < ids.size(); i++) {
					if (i % 3 != 0) {
						store.deleteNode(ids.get(i));
					}
				}
				txn.commit();
			}

			// Compaction takes the writer lock one batch at a time, so a writer on another thread makes progress
			// while it runs rather than blocking for the whole pass.
			List<Throwable> failures = new ArrayList<>();
			AtomicBoolean stop = new AtomicBoolean();
			AtomicInteger made = new AtomicInteger();
			Thread writer = new Thread(() -> {
				try {
					// Writes for the *whole* compaction, reclaim phase included, rather than a fixed 200.
					//
					// This is the difference between the test passing for the right reason and passing by accident.
					// Two hundred nodes finish in a few milliseconds, long before compaction reaches the reclaim
					// phase at the end of the pass — so the original loop left the very interleaving this test is
					// named for untested. With the writer held open across the whole call, the pre-fix engine fails
					// this 8 times in 10 with a checksum that disagrees with its page; with the writer stopping
					// early it fails none.
					while (!stop.get()) {
						store.createNode("Concurrent", Properties.of().set("j", made.getAndIncrement()));
					}
				} catch (Throwable e) {
					failures.add(e);
				}
			});
			writer.start();
			store.compact(CompactionOptions.builder()
				.livenessThreshold(1.0)
				.maxRelocationsPerTransaction(64)
				.build());
			stop.set(true);
			writer.join();

			assertTrue(failures.isEmpty(), "a foreground writer must not fail during a compaction: " + failures);
			assertEquals(made.get(), store.nodesByLabel("Concurrent").size(),
				"every node the concurrent writer acknowledged must have survived the compaction");
			assertEquals(1000, store.nodesByLabel("Keep").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- model

	/** The graph as plain Java objects, keyed on the {@code i} property so that no id survives into an assertion. */
	private static final class Model {

		private final Map<Long, String> labels = new HashMap<>();
		private final Map<Long, Integer> values = new HashMap<>();
		private final Map<Long, Set<String>> edges = new HashMap<>();

		void add(long id, String label, int value) {
			labels.put(id, label);
			values.put(id, value);
			edges.put(id, new HashSet<>());
		}

		void edge(long from, long to, String type) {
			edges.get(from).add(type + ":" + values.get(to));
			edges.get(to).add(type + ":" + values.get(from));
		}

		List<Long> keeps() {
			return labels.entrySet().stream().filter(e -> e.getValue().equals("Keep")).map(Map.Entry::getKey)
				.sorted(java.util.Comparator.comparing(values::get)).toList();
		}

		List<Long> drops() {
			return labels.entrySet().stream().filter(e -> e.getValue().equals("Drop")).map(Map.Entry::getKey).toList();
		}

		void dropAll() {
			for (long id : drops()) {
				labels.remove(id);
				edges.remove(id);
			}
		}

		Map<Integer, Set<String>> adjacencyByValue() {
			Map<Integer, Set<String>> result = new HashMap<>();
			for (Map.Entry<Long, Set<String>> entry : edges.entrySet()) {
				result.put(values.get(entry.getKey()), entry.getValue());
			}
			return result;
		}
	}

	@Test
	public void testCompactionReportIsHonest() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 1000; i++) {
					ids.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (int i = 500; i < ids.size(); i++) {
					store.deleteNode(ids.get(i));
				}
				txn.commit();
			}
			CompactionReport report = store.compact(CompactionOptions.always());
			assertTrue(report.batches() > 0, report.toString());
			assertTrue(report.livenessAfter() >= report.livenessBefore(), report.toString());
			assertTrue(report.storageBytesAfter() <= report.storageBytesBefore(), report.toString());
			assertFalse(report.toString().isEmpty());
			// Deleting the top half leaves the survivors already dense, so the slots come back from the high water
			// mark rather than from moving anything.
			assertTrue(report.slotsReclaimed() > 0, report.toString());
		}
	}
}
