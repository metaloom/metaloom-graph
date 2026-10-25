package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.TransactionConflictException;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.StressRun;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * Anomaly checkers under concurrent load, each with an oracle the engine's own bookkeeping cannot satisfy by agreeing
 * with itself.
 *
 * <p>
 * {@code SnapshotIsolationTest} pins each anomaly down in a scheduled two-transaction form, and
 * {@code ParallelConsistencyTest} holds a conserved total at {@code SNAPSHOT}. These run the shapes those leave out
 * under real contention, on platform and virtual threads at once: an invariant that only {@code SERIALIZABLE} keeps;
 * an index that must agree with a full scan inside every snapshot; a supernode whose two descriptions of its edges —
 * the degree counter and the adjacency chain — and both endpoints' views of every edge must agree in every snapshot;
 * vector searches racing inserts and deletes; and monotonic reads plus read-your-writes across concurrent commits.
 * </p>
 *
 * <p>
 * Each case runs for about a second and a half, so the class belongs in the default suite. {@link StressRun} turns a
 * hang into a failure with a thread dump; {@code -Dgraph.stress.millis} and {@code -Dgraph.stress.seed} lengthen and
 * replay a run.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 3
 */
@Tag("unit")
public class IsolationAnomalyStressTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final long RUN_MILLIS = 1_500;

	/**
	 * Durability is not what these test, and an fsync per commit caps a run at a few hundred commits — too few
	 * interleavings to find anything. The ordering rules are the same in every sync mode.
	 */
	private static GraphStoreOptions async() {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.walSyncMode(io.metaloom.graph.core.api.WalSyncMode.ASYNC)
			.build();
	}

	// ---------------------------------------------------------------- write skew under SERIALIZABLE

	/**
	 * The on-call rota: at least one doctor must stay on call, and each transaction takes one off only after counting
	 * that two or more are on. Under snapshot isolation two of them can each count two and both leave, which is write
	 * skew; under {@code SERIALIZABLE} the invariant must survive any amount of contention.
	 *
	 * <p>
	 * The doctors are spread over separate record and property pages, so a refusal cannot come from two of them simply
	 * sharing a page — every refusal has to be earned by the read set.
	 * </p>
	 */
	@Test
	public void testSerializableKeepsAnInvariantThatSnapshotIsolationCanBreak() throws Exception {
		try (GraphStore store = stores.open(async())) {
			List<Long> doctors = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int d = 0; d < 6; d++) {
					doctors.add(store.createNode("Doctor", Properties.of().set("onCall", d < 3)));
					// More than a page of records and of property cells between two doctors.
					for (int s = 0; s < 260; s++) {
						store.createNode("Spacer", Properties.of().set("s", (long) s));
					}
				}
				txn.commit();
			}
			AtomicLong changes = new AtomicLong();
			AtomicLong audits = new AtomicLong();
			StressRun.named("on-call")
				.millis(RUN_MILLIS)
				.platform("rota", 4, rnd -> {
					rota(store, doctors, rnd.nextInt(doctors.size()), changes);
					return true;
				})
				.virtual("rota", 2, rnd -> {
					rota(store, doctors, rnd.nextInt(doctors.size()), changes);
					return true;
				})
				.platform("auditor", 2, rnd -> {
					try (ReadTransaction view = store.beginRead()) {
						int on = onCall(store, doctors);
						if (on < 1) {
							throw new AssertionError("a view at LSN " + view.snapshotLsn() + " found nobody on call");
						}
						audits.incrementAndGet();
					}
					return true;
				})
				.run();
			assertTrue(onCall(store, doctors) >= 1, "nobody is on call: write skew committed at SERIALIZABLE");
			assertTrue(changes.get() > 20, "only " + changes.get() + " rota changes committed");
			assertTrue(audits.get() > 10, "only " + audits.get() + " audits ran");
			System.out.println("[on-call] changes=" + changes.get() + " audits=" + audits.get());
			store.verify(VerifyLevel.FULL);
		}
	}

	private static void rota(GraphStore store, List<Long> doctors, int pick, AtomicLong changes) throws Exception {
		boolean changed = store.inTransaction(() -> {
			int on = onCall(store, doctors);
			long doctor = doctors.get(pick);
			boolean isOn = (Boolean) store.nodeProperty(doctor, "onCall");
			if (isOn && on >= 2) {
				store.setNodeProperties(doctor, Properties.of().set("onCall", false));
				return true;
			}
			if (!isOn) {
				store.setNodeProperties(doctor, Properties.of().set("onCall", true));
				return true;
			}
			return false;
		}, 1_000, IsolationLevel.SERIALIZABLE);
		if (changed) {
			changes.incrementAndGet();
		}
	}

	private static int onCall(GraphStore store, List<Long> doctors) {
		int on = 0;
		for (long doctor : doctors) {
			if ((Boolean) store.nodeProperty(doctor, "onCall")) {
				on++;
			}
		}
		return on;
	}

	// ---------------------------------------------------------------- index versus record

	/**
	 * Every index answer equals a full scan of the records <b>in the same snapshot</b>, while writers move nodes
	 * between property buckets, toggle a label, and create and delete nodes.
	 *
	 * <p>
	 * {@code verify(FULL)} checks the indexes against the records only on a quiescent store. Inside a view the index
	 * pages and the record pages are reconstructed independently, so an index entry committed in a different instant
	 * from its record — or a view that rebuilds one and not the other — shows up here as a disagreement.
	 * </p>
	 */
	@Test
	public void testIndexesAgreeWithAFullScanInEverySnapshot() throws Exception {
		try (GraphStore store = stores.open(async())) {
			store.createIndex("Item", "bucket");
			List<Long> seeded = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 300; i++) {
					seeded.add(store.createNode("Item", Properties.of().set("bucket", (long) (i % 8))));
				}
				txn.commit();
			}
			Set<Long> live = ConcurrentHashMap.newKeySet();
			live.addAll(seeded);
			AtomicLong audits = new AtomicLong();
			StressRun.named("index-vs-scan")
				.millis(RUN_MILLIS)
				.platform("mover", 2, rnd -> {
					long id = any(live, rnd);
					if (id != 0) {
						try {
							store.setNodeProperties(id, Properties.of().set("bucket", (long) rnd.nextInt(8)));
						} catch (io.metaloom.graph.core.api.GraphStoreException gone) {
							// Deleted by the churner between the pick and the write.
						}
					}
					return true;
				})
				.virtual("labeller", 2, rnd -> {
					long id = any(live, rnd);
					if (id != 0) {
						try {
							if (rnd.nextBoolean()) {
								store.addNodeLabel(id, "Hot");
							} else {
								store.removeNodeLabel(id, "Hot");
							}
						} catch (io.metaloom.graph.core.api.GraphStoreException gone) {
							// As above.
						}
					}
					return true;
				})
				.platform("churner", 1, rnd -> {
					if (rnd.nextBoolean() || live.size() < 100) {
						live.add(store.createNode("Item", Properties.of().set("bucket", (long) rnd.nextInt(8))));
					} else {
						long id = any(live, rnd);
						if (id != 0 && live.remove(id)) {
							store.deleteNode(id);
						}
					}
					return true;
				})
				.platform("auditor", 2, rnd -> {
					try (ReadTransaction view = store.beginRead()) {
						List<String> problems = indexProblems(store);
						if (!problems.isEmpty()) {
							throw new AssertionError("a view at LSN " + view.snapshotLsn() + " has indexes that"
								+ " disagree with its records:\n  " + String.join("\n  ", problems));
						}
						audits.incrementAndGet();
					}
					return true;
				})
				.run();
			assertTrue(audits.get() > 10, "only " + audits.get() + " audits ran");
			assertTrue(indexProblems(store).isEmpty(), "quiescent store: " + indexProblems(store));
			System.out.println("[index-vs-scan] audits=" + audits.get());
			store.verify(VerifyLevel.FULL);
		}
	}

	private static long any(Set<Long> live, java.util.Random rnd) {
		Object[] ids = live.toArray();
		return ids.length == 0 ? 0 : (Long) ids[rnd.nextInt(ids.length)];
	}

	private static List<String> indexProblems(GraphStore store) {
		List<Long> all = new ArrayList<>();
		store.forEachNode(all::add);
		Map<Long, Set<Long>> byBucket = new HashMap<>();
		Set<Long> items = new TreeSet<>();
		Set<Long> hot = new TreeSet<>();
		for (long id : all) {
			if (store.nodeHasLabel(id, "Item")) {
				items.add(id);
			}
			if (store.nodeHasLabel(id, "Hot")) {
				hot.add(id);
			}
			Object bucket = store.nodeProperty(id, "bucket");
			if (bucket != null) {
				byBucket.computeIfAbsent((Long) bucket, b -> new TreeSet<>()).add(id);
			}
		}
		List<String> problems = new ArrayList<>();
		compare(problems, "nodesByLabel(Item)", new TreeSet<>(store.nodesByLabel("Item")), items);
		compare(problems, "nodesByLabel(Hot)", new TreeSet<>(store.nodesByLabel("Hot")), hot);
		for (long b = 0; b < 8; b++) {
			Set<Long> expected = byBucket.getOrDefault(b, Set.of());
			compare(problems, "nodesByProperty(bucket=" + b + ")",
				new TreeSet<>(store.nodesByProperty("bucket", b)), expected);
			compare(problems, "nodesByLabelAndProperty(Item, bucket=" + b + ")",
				new TreeSet<>(store.nodesByLabelAndProperty("Item", "bucket", b)), expected);
		}
		return problems;
	}

	private static void compare(List<String> problems, String what, Set<Long> index, Set<Long> scan) {
		if (!index.equals(scan)) {
			Set<Long> extra = new TreeSet<>(index);
			extra.removeAll(scan);
			Set<Long> missing = new TreeSet<>(scan);
			missing.removeAll(index);
			problems.add(what + ": index has " + extra.size() + " the scan does not " + head(extra) + ", misses "
				+ missing.size() + " " + head(missing));
		}
	}

	private static String head(Set<Long> ids) {
		return ids.stream().limit(3).toList().toString();
	}

	// ---------------------------------------------------------------- supernode

	/**
	 * A hub gaining and losing edges from many threads at once, both lightweight edges and materialised
	 * relationships, in both directions. In every snapshot the hub's degree counters, its adjacency chain read two
	 * ways, and every leaf's view of the same edges must agree; at the end they must equal the model the writers kept.
	 */
	@Test
	public void testASupernodesTwoEndsAgreeInEverySnapshot() throws Exception {
		try (GraphStore store = stores.open(async())) {
			int writers = 6;
			int leavesPerWriter = 60;
			long hub;
			List<List<Long>> leaves = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int w = 0; w < writers; w++) {
					List<Long> mine = new ArrayList<>();
					for (int l = 0; l < leavesPerWriter; l++) {
						mine.add(store.createNode("Leaf"));
					}
					leaves.add(mine);
				}
				txn.commit();
			}
			List<Long> allLeaves = leaves.stream().flatMap(List::stream).toList();
			// Per writer, so each one's model is only touched by its own thread: leaf -> relationship id (0 for a
			// lightweight edge), for edges out of the hub; and the set of leaves with an edge into the hub.
			List<Map<Long, Long>> out = new ArrayList<>();
			List<Set<Long>> in = new ArrayList<>();
			for (int w = 0; w < writers; w++) {
				out.add(new HashMap<>());
				in.add(new HashSet<>());
			}
			AtomicLong audits = new AtomicLong();
			java.util.concurrent.atomic.AtomicInteger nextWriter = new java.util.concurrent.atomic.AtomicInteger();
			ThreadLocal<Integer> slot = ThreadLocal.withInitial(nextWriter::getAndIncrement);
			StressRun.Body writer = rnd -> {
				int w = slot.get();
				long leaf = leaves.get(w).get(rnd.nextInt(leavesPerWriter));
				Map<Long, Long> myOut = out.get(w);
				Set<Long> myIn = in.get(w);
				if (rnd.nextInt(3) == 0) {
					if (myIn.remove(leaf)) {
						store.deleteEdge(leaf, "IN", hub);
					} else {
						store.createEdge(leaf, "IN", hub);
						myIn.add(leaf);
					}
				} else if (myOut.containsKey(leaf)) {
					long rel = myOut.remove(leaf);
					if (rel == 0) {
						store.deleteEdge(hub, "OUT", leaf);
					} else {
						store.deleteRelationship(rel);
					}
				} else if (rnd.nextBoolean()) {
					store.createEdge(hub, "OUT", leaf);
					myOut.put(leaf, 0L);
				} else {
					myOut.put(leaf, store.createRelationship(hub, "OUT", leaf, Properties.of().set("w", (long) w)));
				}
				return true;
			};
			StressRun.named("supernode")
				.millis(RUN_MILLIS)
				.platform("writer", writers / 2, writer)
				.virtual("writer", writers - writers / 2, writer)
				.platform("auditor", 2, rnd -> {
					try (ReadTransaction view = store.beginRead()) {
						List<String> problems = supernodeProblems(store, hub, allLeaves);
						if (!problems.isEmpty()) {
							throw new AssertionError("a view at LSN " + view.snapshotLsn() + ":\n  "
								+ String.join("\n  ", problems));
						}
						audits.incrementAndGet();
					}
					return true;
				})
				.run();

			assertTrue(supernodeProblems(store, hub, allLeaves).isEmpty(), "quiescent store");
			Set<Long> expectedOut = new TreeSet<>();
			Set<Long> expectedIn = new TreeSet<>();
			for (int w = 0; w < writers; w++) {
				expectedOut.addAll(out.get(w).keySet());
				expectedIn.addAll(in.get(w));
			}
			Set<Long> actualOut = new TreeSet<>();
			for (Edge edge : store.neighbours(hub, Direction.OUT)) {
				assertTrue(actualOut.add(edge.neighbourId()), "two hub edges to one leaf: " + edge);
			}
			Set<Long> actualIn = new TreeSet<>();
			for (Edge edge : store.neighbours(hub, Direction.IN)) {
				assertTrue(actualIn.add(edge.neighbourId()), "two edges into the hub from one leaf: " + edge);
			}
			assertEquals(expectedOut, actualOut, "the hub's outgoing edges are not what the writers committed");
			assertEquals(expectedIn, actualIn, "the hub's incoming edges are not what the writers committed");
			assertTrue(audits.get() > 10, "only " + audits.get() + " audits ran");
			System.out.println("[supernode] out=" + actualOut.size() + " in=" + actualIn.size() + " audits="
				+ audits.get());
			store.verify(VerifyLevel.FULL);
		}
	}

	private static List<String> supernodeProblems(GraphStore store, long hub, List<Long> leaves) {
		List<String> problems = new ArrayList<>();
		List<Edge> out = store.neighbours(hub, Direction.OUT);
		List<Edge> in = store.neighbours(hub, Direction.IN);
		if (out.size() != store.degreeOut(hub)) {
			problems.add("hub degreeOut " + store.degreeOut(hub) + " but " + out.size() + " outgoing edges");
		}
		if (in.size() != store.degreeIn(hub)) {
			problems.add("hub degreeIn " + store.degreeIn(hub) + " but " + in.size() + " incoming edges");
		}
		int cursorCount = 0;
		try (NeighbourCursor cursor = store.neighbourCursor(hub, Direction.OUT, null)) {
			while (cursor.next()) {
				cursorCount++;
			}
		}
		if (cursorCount != out.size()) {
			problems.add("hub cursor walked " + cursorCount + " outgoing edges, neighbours() " + out.size());
		}
		Map<Long, Integer> outByLeaf = new HashMap<>();
		for (Edge edge : out) {
			outByLeaf.merge(edge.neighbourId(), 1, Integer::sum);
		}
		Map<Long, Integer> inByLeaf = new HashMap<>();
		for (Edge edge : in) {
			inByLeaf.merge(edge.neighbourId(), 1, Integer::sum);
		}
		for (long leaf : leaves) {
			int leafIn = store.degreeIn(leaf);
			int leafOut = store.degreeOut(leaf);
			if (leafIn != outByLeaf.getOrDefault(leaf, 0)) {
				problems.add("leaf " + leaf + " degreeIn " + leafIn + " but the hub has "
					+ outByLeaf.getOrDefault(leaf, 0) + " edges to it");
			}
			if (leafOut != inByLeaf.getOrDefault(leaf, 0)) {
				problems.add("leaf " + leaf + " degreeOut " + leafOut + " but the hub has "
					+ inByLeaf.getOrDefault(leaf, 0) + " edges from it");
			}
			if (problems.size() > 10) {
				break;
			}
		}
		return problems;
	}

	// ---------------------------------------------------------------- vectors

	/**
	 * Approximate searches racing inserts, deletes and overwrites of indexed vectors. Approximate is allowed to miss
	 * neighbours; it is not allowed to return a node the snapshot cannot see, a node that is not a member, or a score
	 * that is not the score of that node's vector in the same snapshot — which is what an index linked to a record
	 * from another instant would produce.
	 */
	@Test
	public void testVectorSearchesDuringInsertsAnswerFromTheirSnapshot() throws Exception {
		int dims = 8;
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(dims)
			.walSyncMode(io.metaloom.graph.core.api.WalSyncMode.ASYNC)
			.build();
		try (GraphStore store = stores.open(options)) {
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "emb", dims, VectorMetric.COSINE));
			Set<Long> live = ConcurrentHashMap.newKeySet();
			java.util.Random seedRnd = new java.util.Random(7);
			for (int i = 0; i < 100; i++) {
				live.add(store.createNode("Doc", Properties.of().set("emb", vector(seedRnd, dims))));
			}
			AtomicLong searches = new AtomicLong();
			StressRun.named("vectors")
				.millis(RUN_MILLIS)
				.platform("inserter", 2, rnd -> {
					live.add(store.createNode("Doc", Properties.of().set("emb", vector(rnd, dims))));
					return true;
				})
				.virtual("mutator", 2, rnd -> {
					long id = any(live, rnd);
					if (id == 0) {
						return true;
					}
					if (rnd.nextInt(3) == 0) {
						if (live.remove(id)) {
							store.deleteNode(id);
						}
					} else {
						try {
							store.setNodeProperties(id, Properties.of().set("emb", vector(rnd, dims)));
						} catch (io.metaloom.graph.core.api.GraphStoreException gone) {
							// Deleted by the other mutator between the pick and the write.
						}
					}
					return true;
				})
				.platform("searcher", 2, rnd -> {
					float[] query = vector(rnd, dims);
					try (ReadTransaction view = store.beginRead()) {
						List<Neighbour> found = store.nearest("Doc", "emb", query, 10, null);
						List<Neighbour> exact = store.nearestByScan("Doc", "emb", query, Integer.MAX_VALUE,
							VectorMetric.COSINE);
						Map<Long, Double> scores = new HashMap<>();
						for (Neighbour n : exact) {
							scores.put(n.nodeId(), n.score());
						}
						for (Neighbour n : found) {
							Double score = scores.get(n.nodeId());
							if (score == null) {
								throw new AssertionError("a view at LSN " + view.snapshotLsn() + " was offered "
									+ n + ", which is not a Doc with a vector in that snapshot");
							}
							if (Math.abs(score - n.score()) > 1e-5) {
								throw new AssertionError("a view at LSN " + view.snapshotLsn() + " scored " + n
									+ " but that node's vector in the same snapshot scores " + score);
							}
						}
						if (found.size() > Math.min(10, exact.size())) {
							throw new AssertionError("more results than members: " + found.size());
						}
						if (!exact.isEmpty() && found.isEmpty()) {
							throw new AssertionError("an index with " + exact.size() + " members found nothing");
						}
						searches.incrementAndGet();
					}
					return true;
				})
				.run();
			assertTrue(searches.get() > 10, "only " + searches.get() + " searches ran");
			System.out.println("[vectors] searches=" + searches.get() + " live=" + live.size());
			store.verify(VerifyLevel.FULL);
		}
	}

	private static float[] vector(java.util.Random rnd, int dims) {
		float[] v = new float[dims];
		for (int i = 0; i < dims; i++) {
			v[i] = rnd.nextFloat() * 2 - 1;
		}
		v[0] += 0.01f;
		return v;
	}

	// ---------------------------------------------------------------- monotonic reads, read your writes

	/**
	 * A counter only ever goes up. Successive views on one thread must never see it go down, nor their LSN; a
	 * read-committed read must never go down either; and a transaction must keep seeing its own write however many
	 * other commits land around it, while its snapshot of everything else stays put.
	 */
	@Test
	public void testReadsAreMonotonicAndWritesAreReadBack() throws Exception {
		try (GraphStore store = stores.open(async())) {
			long counter = store.createNode("Counter", Properties.of().set("n", 0L));
			long other = store.createNode("Other", Properties.of().set("n", 0L));
			List<Long> scratch = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				scratch.add(store.createNode("Scratch", Properties.of().set("v", 0L)));
			}
			AtomicLong increments = new AtomicLong();
			ThreadLocal<long[]> lastSeen = ThreadLocal.withInitial(() -> new long[] { -1, -1, -1 });
			StressRun.named("monotonic")
				.millis(RUN_MILLIS)
				.platform("incrementer", 2, rnd -> {
					store.inTransaction(() -> {
						long n = (Long) store.nodeProperty(counter, "n");
						store.setNodeProperties(counter, Properties.of().set("n", n + 1));
						return null;
					}, 1_000);
					store.setNodeProperties(other, Properties.of().set("n", rnd.nextLong()));
					increments.incrementAndGet();
					return true;
				})
				.virtual("viewer", 2, rnd -> {
					long[] last = lastSeen.get();
					try (ReadTransaction view = store.beginRead()) {
						long lsn = view.snapshotLsn();
						long n = (Long) store.nodeProperty(counter, "n");
						if (lsn < last[0]) {
							throw new AssertionError("view LSN went back from " + last[0] + " to " + lsn);
						}
						if (n < last[1]) {
							throw new AssertionError("the counter went back from " + last[1] + " to " + n
								+ " between two views on one thread");
						}
						if (lsn == last[0] && n != last[1]) {
							throw new AssertionError("two views at LSN " + lsn + " disagree: " + last[1] + " vs " + n);
						}
						Thread.yield();
						long again = (Long) store.nodeProperty(counter, "n");
						if (again != n) {
							throw new AssertionError("a view at LSN " + lsn + " read " + n + " then " + again);
						}
						last[0] = lsn;
						last[1] = n;
					} catch (SnapshotTooOldException e) {
						// Not under budget pressure here, but never a wrong answer.
					}
					long rc = (Long) store.nodeProperty(counter, "n");
					if (rc < last[2] || rc < last[1]) {
						throw new AssertionError("a read-committed read went back to " + rc + " after " + last[2]
							+ " / view " + last[1]);
					}
					last[2] = rc;
					return true;
				})
				.platform("reader-of-own-writes", 2, rnd -> {
					long mine = scratch.get(rnd.nextInt(scratch.size()));
					long value = rnd.nextLong();
					try (Transaction txn = store.begin()) {
						long counterBefore = (Long) store.nodeProperty(counter, "n");
						store.setNodeProperties(mine, Properties.of().set("v", value));
						for (int i = 0; i < 3; i++) {
							Thread.yield();
							Object seen = store.nodeProperty(mine, "v");
							if (!Long.valueOf(value).equals(seen)) {
								throw new AssertionError("a transaction wrote " + value + " and read back " + seen);
							}
							long counterNow = (Long) store.nodeProperty(counter, "n");
							if (counterNow != counterBefore) {
								throw new AssertionError("a write transaction's snapshot moved: " + counterBefore
									+ " then " + counterNow);
							}
						}
						txn.commit();
					} catch (TransactionConflictException e) {
						// Two of these picked the same scratch node; the loser's read-back was still checked.
					}
					return true;
				})
				.run();
			assertTrue(increments.get() > 10, "only " + increments.get() + " increments");
			assertEquals(increments.get(), (long) (Long) store.nodeProperty(counter, "n"),
				"an increment was lost or doubled");
			store.verify(VerifyLevel.FULL);
		}
	}
}
