package io.metaloom.graph.core.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.prop.ValueCodec;

/**
 * The second differential harness: the storage layer against a model, over the operations and interleavings
 * {@link ReferenceModelTest} does not reach.
 *
 * <p>
 * {@code ReferenceModelTest} holds one label per node, one value type per key and one compaction at the end. This one
 * changes all three, because those are the places a page-level storage engine keeps its sharp edges:
 * </p>
 * <ul>
 * <li><b>Labels</b>: up to six per node, which crosses the three inline labels into the overflow block, and removal of
 * any of them including the last.</li>
 * <li><b>Values</b>: one key ({@code v}) cycles through every value type the codec has — integers, doubles, booleans,
 * the empty string, strings either side of the 255 byte inline limit, multi-extent blobs, byte arrays, timestamps,
 * UUIDs, lists (with blobs and vectors nested inside them), element references, vectors of the store's dimension
 * (which go to {@code VEC} records) and of another (which go to blobs), and an explicit null.</li>
 * <li><b>Shape</b>: self-loops, parallel edges, and a hub whose degree crosses the adjacency tiers, deleted with its
 * edges materialised.</li>
 * <li><b>Declarations</b>: a store-wide unique constraint violated on purpose inside explicit transactions that then
 * commit, a label-scoped one declared and dropped partway, composite indexes declared and dropped, and a vector index
 * whose member set is compared against the model.</li>
 * <li><b>Time</b>: snapshot views opened on another thread and compared, batches later, against the model <em>as of
 * the view</em>; clean reopens, crashes, vacuum, reclaim, crashes in the middle of a compaction, and compaction
 * several times interleaved with the writes. The log is kept to small segments so it rolls over throughout.</li>
 * </ul>
 *
 * <p>
 * Ids do not survive a compaction, so the model is keyed by a {@code uid} property on every node and a {@code ruid}
 * on every relationship, and the harness keeps the map from those to store ids — rebuilt after each compaction from
 * the store itself, through the unique index and the adjacency, which is what an application holding ids across a
 * compaction has to do.
 * </p>
 *
 * <p>
 * <b>Every run prints its seed.</b> {@link #testReplayASpecificSeed()} re-runs one, and takes
 * {@code -Dstoragemodel.seed=} and {@code -Dstoragemodel.batches=} so a failure from the slow sweep is reproducible
 * without editing the file.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 4
 */
@Tag("unit")
public class StorageModelTest {

	static final int DIMS = 4;
	static final String[] LABELS = { "Person", "Asset", "Face", "Tag", "Place", "Event", "Topic", "Group" };
	static final String[] TYPES = { "OWNS", "DEPICTS", "TAGGED", "LINKS" };
	static final String[] KEYS = { "uid", "name", "v", "vec", "email", "score" };
	/** Nodes created first and never deleted: element references point at them, and the first one is the hub. */
	static final int ANCHORS = 4;
	/** How many of the last operations a failure prints: {@code -Dstoragemodel.trace=}. */
	static final int TRACE_LINES = Integer.getInteger("storagemodel.trace", 400);

	static GraphStoreOptions options() {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(DIMS)
			// Small log segments, so a run rolls the log over many times and recovery reads across the joins.
			.walSegmentBytes(1024 * 1024)
			.build();
	}

	@Test
	public void testRandomWorkloadMatchesTheModel() throws Exception {
		run(20261024L, 24, 25);
	}

	@Test
	public void testSecondSeedMatchesTheModel() throws Exception {
		run(7351L, 16, 25);
	}

	/**
	 * A hook for reproducing a failure: {@code -Dstoragemodel.seed=<seed>}, or paste the seed here. A seed from
	 * {@link #testManySeeds()} also needs {@code -Dstoragemodel.batches=40 -Dstoragemodel.ops=35}.
	 */
	@Test
	public void testReplayASpecificSeed() throws Exception {
		long seed = Long.getLong("storagemodel.seed", 4242L);
		int batches = Integer.getInteger("storagemodel.batches", 4);
		run(seed, batches, Integer.getInteger("storagemodel.ops", 30));
	}

	/** Many seeds, for the slow profile: {@code mvn test -Pslow -Dtest=StorageModelTest -Dstoragemodel.seeds=200}. */
	@Test
	@Tag("slow")
	public void testManySeeds() throws Exception {
		int seeds = Integer.getInteger("storagemodel.seeds", 40);
		long base = Long.getLong("storagemodel.base", System.nanoTime());
		for (int i = 0; i < seeds; i++) {
			run(base + i, 40, 35);
		}
	}

	// ---------------------------------------------------------------- the world: model plus id maps

	/** The model, and the map between its uids and the store's ids. Copied whole at every transaction boundary. */
	static final class World {
		GraphModel model = new GraphModel();
		Map<Long, Long> nodeIds = new HashMap<>();
		Map<Long, Long> relIds = new HashMap<>();
		List<Long> nodeUids = new ArrayList<>();
		List<Long> relUids = new ArrayList<>();

		World copy() {
			World copy = new World();
			copy.model = model.copy();
			copy.nodeIds = new HashMap<>(nodeIds);
			copy.relIds = new HashMap<>(relIds);
			copy.nodeUids = new ArrayList<>(nodeUids);
			copy.relUids = new ArrayList<>(relUids);
			return copy;
		}

		Map<Long, Long> uidOfNode() {
			Map<Long, Long> inverse = new HashMap<>();
			nodeIds.forEach((uid, id) -> inverse.put(id, uid));
			return inverse;
		}

		Map<Long, Long> uidOfRel() {
			Map<Long, Long> inverse = new HashMap<>();
			relIds.forEach((uid, id) -> inverse.put(id, uid));
			return inverse;
		}
	}

	/** Declarations the model has to know about, because they change which writes are refused. */
	static final class Declarations {
		boolean emailUnique;
		boolean vectorIndex;
		Set<String> composites = new LinkedHashSet<>();

		Declarations copy() {
			Declarations copy = new Declarations();
			copy.emailUnique = emailUnique;
			copy.vectorIndex = vectorIndex;
			copy.composites = new LinkedHashSet<>(composites);
			return copy;
		}
	}

	/** One run's mutable harness state. */
	final class Run {
		final long seed;
		final Random random;
		final Path dir;
		GraphStore store;
		World committed = new World();
		Declarations declared = new Declarations();
		long nextUid = 1;
		int operation;
		String phase = "start";

		/** The view held on the viewer thread, the world as of that view, and when it was opened. */
		ReadTransaction view;
		World viewWorld;
		int viewOpenedAt;

		Run(long seed, Path dir) {
			this.seed = seed;
			this.random = new Random(seed);
			this.dir = dir;
		}

		/** The last operations, printed on failure: a seed reproduces a run, this says what the run did. */
		final java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();

		String where() {
			return "seed=" + seed + " op=" + operation + " phase=" + phase;
		}

		void trace(String what) {
			if (trace.size() == TRACE_LINES) {
				trace.removeFirst();
			}
			trace.addLast(operation + " [" + phase + "] " + what);
		}
	}

	private final ExecutorService viewer = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "storage-model-viewer");
		thread.setDaemon(true);
		return thread;
	});

	@AfterEach
	public void stopViewer() {
		viewer.shutdownNow();
	}

	// ---------------------------------------------------------------- the harness

	private void run(long seed, int batches, int opsPerBatch) throws Exception {
		System.out.println("StorageModelTest seed=" + seed + " batches=" + batches);
		Path dir = Files.createTempDirectory("graphstore-storagemodel-");
		Run run = new Run(seed, dir);
		boolean failed = false;
		run.store = GraphStore.open(dir, options());
		try {
			setUp(run);
			for (int batch = 0; batch < batches; batch++) {
				run.phase = "batch " + batch;
				batch(run, opsPerBatch);
				compareAll(run.store, run.committed, run.where() + " after commit");
				run.store.verify(VerifyLevel.FULL);
				compareView(run, false);
				event(run, batch);
			}
			closeView(run);
			run.phase = "final compaction";
			compact(run);
		} catch (Throwable t) {
			failed = true;
			run.trace.forEach(line -> System.err.println("  " + line));
			System.err.println("StorageModelTest FAILED: " + run.where() + ", store kept at " + dir
				+ " (replay with -Dstoragemodel.seed=" + seed + ")");
			throw t;
		} finally {
			try {
				closeView(run);
			} catch (Throwable ignored) {
				// The original failure is the interesting one.
			}
			try {
				run.store.close();
			} catch (IOException | RuntimeException ignored) {
				// An abandoned store is already closed.
			}
			if (!failed) {
				deleteRecursively(dir);
			}
		}
	}

	private void setUp(Run run) throws IOException {
		run.store.createUniqueConstraint("uid");
		Transaction txn = run.store.begin();
		World world = run.committed.copy();
		for (int i = 0; i < ANCHORS; i++) {
			createNode(run, world, List.of(LABELS[i % LABELS.length]));
		}
		txn.commit();
		run.committed = world;
	}

	/**
	 * One batch: an explicit transaction committed or rolled back, or a run of auto-commit statements. The model of a
	 * rolled-back batch is discarded with it.
	 */
	private void batch(Run run, int ops) throws IOException {
		int mode = run.random.nextInt(10);
		World pending = run.committed.copy();
		if (mode < 2) {
			// Auto-commit: every statement is its own transaction, so a refused one is gone and the next one runs.
			run.trace("auto-commit batch");
			for (int i = 0; i < ops; i++) {
				World before = pending.copy();
				try {
					applyOne(run, pending);
				} catch (ConstraintViolationException expected) {
					pending = before;
				}
			}
			run.committed = pending;
			return;
		}
		boolean rollback = mode < 4;
		// A quarter at SERIALIZABLE, which alone in the store must behave exactly as SNAPSHOT does.
		boolean serializable = run.random.nextInt(4) == 0;
		run.trace((rollback ? "begin (will roll back)" : "begin") + (serializable ? " serializable" : ""));
		Transaction txn = serializable ? run.store.begin(IsolationLevel.SERIALIZABLE) : run.store.begin();
		try {
			for (int i = 0; i < ops; i++) {
				applyOne(run, pending);
				if (run.random.nextInt(8) == 0) {
					// Read-your-writes, through the transaction's own overlay.
					compareSome(run.store, pending, run.where() + " inside the transaction", run.random);
				}
			}
			if (rollback) {
				txn.rollback();
			} else {
				txn.commit();
				run.committed = pending;
			}
		} finally {
			txn.close();
		}
	}

	/** Something between batches: a declaration, a view, a crash, a compaction. */
	private void event(Run run, int batch) throws Exception {
		Random random = run.random;
		int roll = random.nextInt(100);
		if (batch == 2 && !run.declared.vectorIndex) {
			declareVectorIndex(run);
		} else if (batch == 4 || roll < 6) {
			declareEmailUnique(run);
		} else if (roll < 9 && run.declared.emailUnique) {
			run.phase = "drop :Person.email unique";
			run.store.dropIndex("Person", "email");
			run.declared.emailUnique = false;
			run.declared.composites.remove("Person.email");
		} else if (roll < 14) {
			String[] pair = { LABELS[random.nextInt(3)], random.nextBoolean() ? "name" : "v" };
			String key = pair[0] + "." + pair[1];
			run.phase = "composite " + key;
			if (run.declared.composites.contains(key)) {
				run.store.dropIndex(pair[0], pair[1]);
				run.declared.composites.remove(key);
				if (key.equals("Person.email")) {
					run.declared.emailUnique = false;
				}
			} else if (!key.equals("Person.email")) {
				run.store.createIndex(pair[0], pair[1]);
				run.declared.composites.add(key);
			}
		} else if (roll < 26) {
			if (run.view == null) {
				openView(run);
			} else {
				compareView(run, true);
				closeView(run);
			}
		} else if (roll < 32) {
			run.phase = "checkpoint";
			run.store.checkpoint();
		} else if (roll < 40) {
			closeView(run);
			run.phase = "clean reopen";
			run.store.close();
			run.store = GraphStore.open(run.dir, options());
		} else if (roll < 50) {
			closeView(run);
			run.phase = "crash";
			((io.metaloom.graph.core.store.GraphStoreImpl) run.store).abandon();
			run.store = GraphStore.open(run.dir, options());
		} else if (roll < 54) {
			closeView(run);
			run.phase = "crash inside a compaction";
			crashInsideACompaction(run);
		} else if (roll < 58) {
			closeView(run);
			run.phase = "compaction";
			compact(run);
		} else if (roll < 62) {
			closeView(run);
			run.phase = "reclaim";
			run.store.reclaim();
		} else if (roll < 64 && run.declared.vectorIndex) {
			run.phase = "drop vector index";
			run.store.dropVectorIndex("Face", "vec");
			run.declared.vectorIndex = false;
		} else if (roll < 68 && !run.declared.vectorIndex) {
			declareVectorIndex(run);
		}
		run.trace("event done");
		run.phase = "after event at batch " + batch;
		compareAll(run.store, run.committed, run.where());
		run.store.verify(VerifyLevel.FULL);
	}

	/**
	 * Declares the vector index, which refuses — and must leave nothing behind — when a labelled node already holds
	 * something under the key that is not a vector of the declared dimension. Later writes of the same are accepted.
	 */
	private void declareVectorIndex(Run run) throws IOException {
		run.phase = "declare vector index";
		boolean expectRefusal = false;
		for (long uid : run.committed.model.nodesByLabel("Face")) {
			Object vec = run.committed.model.properties(uid).get("vec");
			expectRefusal |= vec != null && !(vec instanceof float[] vector && vector.length == DIMS);
		}
		try {
			run.store.createVectorIndex(VectorIndexDefinition.of("Face", "vec", DIMS, VectorMetric.EUCLIDEAN));
			assertTrue(!expectRefusal, run.where() + ": a vector index was declared over a node it cannot cover");
			run.declared.vectorIndex = true;
		} catch (IllegalArgumentException e) {
			assertTrue(expectRefusal, run.where() + ": vector index refused over data it can cover: " + e);
			assertTrue(run.store.vectorIndexes().isEmpty(), run.where() + ": a refused declaration was kept");
		}
	}

	private void declareEmailUnique(Run run) throws IOException {
		if (run.declared.emailUnique) {
			return;
		}
		run.phase = "declare :Person.email unique";
		boolean expectRefusal = duplicateEmailAmongPersons(run.committed.model);
		try {
			run.store.createUniqueConstraint("Person", "email");
			assertTrue(!expectRefusal, run.where() + ": a constraint was declared over data that already broke it");
			run.declared.emailUnique = true;
			run.declared.composites.add("Person.email");
		} catch (ConstraintViolationException e) {
			assertTrue(expectRefusal, run.where() + ": declaration refused over data that satisfies it: " + e);
		}
	}

	// ---------------------------------------------------------------- views

	private void openView(Run run) throws Exception {
		run.phase = "open view";
		World world = run.committed.copy();
		ReadTransaction view = onViewer(() -> run.store.beginRead());
		run.view = view;
		run.viewWorld = world;
		run.viewOpenedAt = run.operation;
		// Compared at once, so a view that was wrong from the start is told apart from one that drifted.
		compareView(run, true);
	}

	private void compareView(Run run, boolean always) throws Exception {
		if (run.view == null || (!always && run.random.nextInt(3) != 0)) {
			return;
		}
		String where = run.where() + " through a view opened at op " + run.viewOpenedAt;
		World world = run.viewWorld;
		GraphStore store = run.store;
		onViewer(() -> {
			compareAll(store, world, where, false);
			return null;
		});
	}

	private void closeView(Run run) throws Exception {
		if (run.view == null) {
			return;
		}
		ReadTransaction view = run.view;
		run.view = null;
		run.viewWorld = null;
		onViewer(() -> {
			view.close();
			return null;
		});
	}

	private <T> T onViewer(java.util.concurrent.Callable<T> work) throws Exception {
		Future<T> future = viewer.submit(work);
		try {
			return future.get(60, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof Error error) {
				throw error;
			}
			if (e.getCause() instanceof Exception exception) {
				throw exception;
			}
			throw e;
		}
	}

	// ---------------------------------------------------------------- compaction

	/**
	 * Compacts, then rebuilds the uid maps from the store: every node through the unique index on {@code uid}, every
	 * relationship through its endpoints' adjacency. The comparison afterwards is then against ids the store chose.
	 */
	private void compact(Run run) throws IOException {
		run.store.compact(CompactionOptions.always());
		run.store.verify(VerifyLevel.FULL);
		World world = run.committed;
		Map<Long, Long> nodes = new HashMap<>();
		for (long uid : world.nodeUids) {
			List<Long> found = run.store.nodesByProperty("uid", uid);
			assertEquals(1, found.size(), run.where() + ": node uid " + uid + " after compaction resolves to " + found);
			nodes.put(uid, found.get(0));
		}
		Map<Long, Long> rels = new HashMap<>();
		for (long uid : world.nodeUids) {
			for (Edge edge : run.store.neighbours(nodes.get(uid), Direction.OUT)) {
				if (edge.isMaterialised()) {
					Object ruid = run.store.relationshipProperties(edge.relationshipId()).get("ruid");
					assertTrue(ruid instanceof Long, run.where() + ": relationship without a ruid after compaction");
					Long previous = rels.put((Long) ruid, edge.relationshipId());
					assertTrue(previous == null || previous == edge.relationshipId(),
						run.where() + ": two relationships carry ruid " + ruid);
				}
			}
		}
		assertEquals(new TreeSet<>(world.relUids), new TreeSet<>(rels.keySet()),
			run.where() + ": relationships found after compaction");
		world.nodeIds = nodes;
		world.relIds = rels;
		compareAll(run.store, world, run.where() + " after compaction");
	}

	/**
	 * A compaction that dies partway — between two of its units, or inside one after its work and before its commit —
	 * followed by a crash. Every batch is one transaction, so the store must come back as "the first k batches
	 * happened" for some k: the same graph, under ids that may or may not have moved. The uid maps are rebuilt by
	 * {@link #compact}, which then finishes the job.
	 */
	private void crashInsideACompaction(Run run) throws IOException {
		var impl = (io.metaloom.graph.core.store.GraphStoreImpl) run.store;
		int failAt = 1 + run.random.nextInt(12);
		boolean inside = run.random.nextBoolean();
		run.trace("compaction to fail at unit " + failAt + (inside ? " inside it" : " before it"));
		CompactionOptions options = CompactionOptions.builder()
			.livenessThreshold(1.0)
			.maxRelocationsPerTransaction(8 + run.random.nextInt(40))
			.build();
		try {
			new io.metaloom.graph.core.maintenance.Compactor(impl.segmentSets(), impl.nodeStore(), impl.relStore(),
				impl.adjacencyStore(), impl.propertyStore(), impl.blobStore(), impl.vectorStore(), impl.indexCatalog(),
				new FailingRunner(impl, failAt, inside)).compact(options);
		} catch (SimulatedCrash expected) {
			// The point of the event.
		}
		impl.abandon();
		run.store = GraphStore.open(run.dir, options());
		compact(run);
	}

	private static final class SimulatedCrash extends RuntimeException {

		private static final long serialVersionUID = 1L;
	}

	/** The compactor's runner, failing at the chosen unit. Checkpoints are let through: they are real here. */
	private static final class FailingRunner implements io.metaloom.graph.core.maintenance.MaintenanceRunner {

		private final io.metaloom.graph.core.maintenance.MaintenanceRunner delegate;
		private final int failAt;
		private final boolean inside;
		private int units;

		FailingRunner(io.metaloom.graph.core.maintenance.MaintenanceRunner delegate, int failAt, boolean inside) {
			this.delegate = delegate;
			this.failAt = failAt;
			this.inside = inside;
		}

		@FunctionalInterface
		private interface Step {
			void accept(Unit unit) throws IOException;
		}

		private void fallible(Unit unit, Step step) throws IOException {
			if (++units < failAt) {
				step.accept(unit);
				return;
			}
			if (!inside) {
				throw new SimulatedCrash();
			}
			step.accept(() -> {
				unit.run();
				throw new SimulatedCrash();
			});
		}

		@Override
		public void runWrite(Unit unit) throws IOException {
			fallible(unit, delegate::runWrite);
		}

		@Override
		public void runExclusive(Unit unit) throws IOException {
			fallible(unit, delegate::runExclusive);
		}

		@Override
		public void runUnlogged(Unit unit) throws IOException {
			fallible(unit, delegate::runUnlogged);
		}

		@Override
		public void runRead(Unit unit) throws IOException {
			delegate.runRead(unit);
		}

		@Override
		public void runCheckpoint() throws IOException {
			delegate.runCheckpoint();
		}
	}

	// ---------------------------------------------------------------- operations

	private void applyOne(Run run, World world) throws IOException {
		run.operation++;
		Random random = run.random;
		GraphStore store = run.store;
		GraphModel model = world.model;
		int roll = random.nextInt(100);
		List<Long> deletable = world.nodeUids.subList(Math.min(ANCHORS, world.nodeUids.size()), world.nodeUids.size());
		if (world.nodeUids.size() < 8 || roll < 18) {
			List<String> labels = new ArrayList<>();
			int count = 1 + random.nextInt(random.nextInt(5) == 0 ? 6 : 2);
			while (labels.size() < count) {
				String label = LABELS[random.nextInt(LABELS.length)];
				if (!labels.contains(label)) {
					labels.add(label);
				}
			}
			Map<String, Object> props = randomNodeProperties(run, world, run.nextUid);
			if (refusedByConstraints(run, world, -1, labels, props)) {
				run.trace("createNode refused " + labels + " " + describe(props));
				expectViolation(run, () -> store.createNode(labels, toProperties(props, world)));
				return;
			}
			createNode(run, world, labels, props);
		} else if (roll < 32) {
			long uid = pick(random, world.nodeUids);
			Map<String, Object> props = mutate(run, world, uid, new LinkedHashMap<>(model.properties(uid)));
			long id = world.nodeIds.get(uid);
			if (refusedByConstraints(run, world, uid, model.labels(uid), props)) {
				run.trace("setNodeProperties refused uid=" + uid + " " + describe(props));
				expectViolation(run, () -> store.setNodeProperties(id, toProperties(props, world)));
				return;
			}
			run.trace("setNodeProperties uid=" + uid + " " + describe(props));
			store.setNodeProperties(id, toProperties(props, world));
			model.setProperties(uid, props);
		} else if (roll < 34) {
			// A deliberate store-wide unique violation: another node's uid.
			long uid = pick(random, world.nodeUids);
			long other = pick(random, world.nodeUids);
			if (uid != other) {
				Map<String, Object> props = new LinkedHashMap<>(model.properties(uid));
				props.put("uid", other);
				long id = world.nodeIds.get(uid);
				run.trace("setNodeProperties uid=" + uid + " to the uid of " + other);
				expectViolation(run, () -> store.setNodeProperties(id, toProperties(props, world)));
			}
		} else if (roll < 40) {
			long uid = pick(random, world.nodeUids);
			String label = LABELS[random.nextInt(LABELS.length)];
			long id = world.nodeIds.get(uid);
			List<String> after = new ArrayList<>(model.labels(uid));
			if (!after.contains(label)) {
				after.add(label);
			}
			if (refusedByConstraints(run, world, uid, after, model.properties(uid))) {
				run.trace("addNodeLabel refused uid=" + uid + " " + label);
				expectViolation(run, () -> store.addNodeLabel(id, label));
				return;
			}
			run.trace("addNodeLabel uid=" + uid + " " + label);
			assertEquals(model.addLabel(uid, label), store.addNodeLabel(id, label),
				run.where() + ": addNodeLabel disagreed about whether it added " + label);
		} else if (roll < 45) {
			long uid = pick(random, world.nodeUids);
			List<String> held = model.labels(uid);
			// Any label, including the first and the last: a node may end up with none.
			String label = held.isEmpty() || random.nextInt(5) == 0 ? LABELS[random.nextInt(LABELS.length)]
				: held.get(random.nextInt(held.size()));
			run.trace("removeNodeLabel uid=" + uid + " " + label);
			assertEquals(model.removeLabel(uid, label), store.removeNodeLabel(world.nodeIds.get(uid), label),
				run.where() + ": removeNodeLabel disagreed about " + label);
		} else if (roll < 57) {
			long from = endpoint(random, world);
			long to = random.nextInt(8) == 0 ? from : endpoint(random, world);
			String type = TYPES[random.nextInt(TYPES.length)];
			int times = random.nextInt(6) == 0 ? 2 + random.nextInt(3) : 1;
			run.trace("createEdge " + from + " -" + type + "-> " + to + " x" + times);
			for (int i = 0; i < times; i++) {
				store.createEdge(world.nodeIds.get(from), type, world.nodeIds.get(to));
				model.createEdge(from, type, to);
			}
		} else if (roll < 63 && !model.edges().isEmpty()) {
			GraphModel.Edge edge = model.edges().get(random.nextInt(model.edges().size()));
			run.trace("deleteEdge " + edge);
			assertTrue(store.deleteEdge(world.nodeIds.get(edge.from()), edge.type(), world.nodeIds.get(edge.to())),
				run.where() + ": the store did not have edge " + edge);
			model.deleteEdge(edge.from(), edge.type(), edge.to());
		} else if (roll < 75) {
			long from = endpoint(random, world);
			long to = random.nextInt(8) == 0 ? from : endpoint(random, world);
			String type = TYPES[random.nextInt(TYPES.length)];
			long ruid = run.nextUid++;
			Map<String, Object> props = new LinkedHashMap<>();
			props.put("ruid", ruid);
			if (random.nextBoolean()) {
				props.put("v", randomValue(run, world));
			}
			run.trace("createRelationship ruid=" + ruid + " " + from + " -" + type + "-> " + to + " " + describe(props));
			long id = store.createRelationship(world.nodeIds.get(from), type, world.nodeIds.get(to),
				toProperties(props, world));
			model.createRelationship(ruid, from, type, to, props);
			world.relIds.put(ruid, id);
			world.relUids.add(ruid);
		} else if (roll < 81 && !world.relUids.isEmpty()) {
			long ruid = pick(random, world.relUids);
			Map<String, Object> props = new LinkedHashMap<>(model.relationship(ruid).properties());
			if (random.nextInt(3) == 0) {
				props.remove("v");
			} else {
				props.put("v", randomValue(run, world));
			}
			run.trace("setRelationshipProperties ruid=" + ruid + " " + describe(props));
			store.setRelationshipProperties(world.relIds.get(ruid), toProperties(props, world));
			model.setRelationshipProperties(ruid, props);
		} else if (roll < 86 && !world.relUids.isEmpty()) {
			long ruid = world.relUids.remove(random.nextInt(world.relUids.size()));
			run.trace("deleteRelationship ruid=" + ruid);
			store.deleteRelationship(world.relIds.remove(ruid));
			model.deleteRelationship(ruid);
		} else if (roll < 93 && deletable.size() > 4) {
			// Now and then the busiest deletable node, so a delete walks a materialised supernode chain.
			long uid = random.nextInt(4) == 0 ? busiest(model, deletable) : pick(random, deletable);
			world.nodeUids.remove(uid);
			run.trace("deleteNode uid=" + uid + " degree " + (model.degreeOut(uid) + model.degreeIn(uid)));
			store.deleteNode(world.nodeIds.remove(uid));
			Set<Long> gone = new TreeSet<>();
			for (long ruid : world.relUids) {
				GraphModel.Relationship rel = model.relationship(ruid);
				if (rel.from() == uid || rel.to() == uid) {
					gone.add(ruid);
				}
			}
			world.relUids.removeAll(gone);
			gone.forEach(world.relIds::remove);
			model.deleteNode(uid);
		} else {
			// A burst onto the hub, which is what drives its adjacency through the tiers.
			long hub = world.nodeUids.get(0);
			int count = 20 + random.nextInt(60);
			run.trace("hub burst of " + count);
			for (int i = 0; i < count; i++) {
				long other = pick(random, world.nodeUids);
				String type = TYPES[random.nextInt(TYPES.length)];
				if (random.nextBoolean()) {
					store.createEdge(world.nodeIds.get(other), type, world.nodeIds.get(hub));
					model.createEdge(other, type, hub);
				} else {
					store.createEdge(world.nodeIds.get(hub), type, world.nodeIds.get(other));
					model.createEdge(hub, type, other);
				}
			}
		}
	}

	private long createNode(Run run, World world, List<String> labels) throws IOException {
		return createNode(run, world, labels, randomNodeProperties(run, world, run.nextUid));
	}

	private long createNode(Run run, World world, List<String> labels, Map<String, Object> props) throws IOException {
		long uid = run.nextUid++;
		assertEquals(uid, props.get("uid"));
		run.trace("createNode uid=" + uid + " " + labels + " " + describe(props));
		long id = run.store.createNode(labels, toProperties(props, world));
		world.model.createNode(uid, labels.get(0), props);
		for (int i = 1; i < labels.size(); i++) {
			world.model.addLabel(uid, labels.get(i));
		}
		world.nodeIds.put(uid, id);
		world.nodeUids.add(uid);
		return uid;
	}

	@FunctionalInterface
	private interface Write {
		void run() throws IOException;
	}

	/**
	 * The write must be refused, and refusing it must leave the transaction exactly as it was: the caller catches the
	 * violation and carries on, which is the ordinary way to use a constraint.
	 */
	private void expectViolation(Run run, Write write) throws IOException {
		try {
			write.run();
			fail(run.where() + ": a write that breaks a unique constraint was accepted");
		} catch (ConstraintViolationException expected) {
			if (!run.store.hasWriteTransaction()) {
				// Auto-commit: the batch loop discards the statement's model changes.
				throw expected;
			}
		}
	}

	/** Whether the model says this node state would break a declared constraint. */
	private boolean refusedByConstraints(Run run, World world, long self, List<String> labels,
		Map<String, Object> props) {
		GraphModel model = world.model;
		for (long other : model.nodeIds()) {
			if (other == self) {
				continue;
			}
			Map<String, Object> theirs = model.properties(other);
			if (Objects.equals(theirs.get("uid"), props.get("uid"))) {
				return true;
			}
			if (run.declared.emailUnique && labels.contains("Person") && model.labels(other).contains("Person")
				&& props.get("email") != null && Objects.equals(theirs.get("email"), props.get("email"))) {
				return true;
			}
		}
		return false;
	}

	private boolean duplicateEmailAmongPersons(GraphModel model) {
		Set<Object> seen = new java.util.HashSet<>();
		for (long uid : model.nodesByLabel("Person")) {
			Object email = model.properties(uid).get("email");
			if (email != null && !seen.add(email)) {
				return true;
			}
		}
		return false;
	}

	private static long pick(Random random, List<Long> from) {
		return from.get(random.nextInt(from.size()));
	}

	/** An edge endpoint: the hub a fifth of the time, otherwise any live node. */
	private static long endpoint(Random random, World world) {
		return random.nextInt(5) == 0 ? world.nodeUids.get(0) : pick(random, world.nodeUids);
	}

	private static long busiest(GraphModel model, List<Long> candidates) {
		long best = candidates.get(0);
		for (long uid : candidates) {
			if (model.degreeOut(uid) + model.degreeIn(uid) > model.degreeOut(best) + model.degreeIn(best)) {
				best = uid;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- values

	private Map<String, Object> randomNodeProperties(Run run, World world, long uid) {
		Random random = run.random;
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("uid", uid);
		if (random.nextInt(4) != 0) {
			props.put("name", random.nextInt(8) == 0 ? "" : "n" + random.nextInt(50));
		}
		if (random.nextBoolean()) {
			props.put("v", randomValue(run, world));
		}
		if (random.nextInt(3) == 0) {
			props.put("vec", vector(random, random.nextInt(6) == 0 ? DIMS + 3 : DIMS));
		}
		if (random.nextInt(3) == 0) {
			props.put("email", "e" + random.nextInt(60));
		}
		if (random.nextInt(4) == 0) {
			props.put("score", random.nextInt(10) / 4.0);
		}
		return props;
	}

	/** Changes one thing about a property map: a key's type, its presence, or its value. */
	private Map<String, Object> mutate(Run run, World world, long uid, Map<String, Object> props) {
		Random random = run.random;
		String key = KEYS[1 + random.nextInt(KEYS.length - 1)];
		switch (random.nextInt(4)) {
			case 0 -> props.remove(key);
			case 1 -> props.put(key, randomValue(run, world));
			default -> {
				Map<String, Object> fresh = randomNodeProperties(run, world, uid);
				fresh.forEach((k, v) -> {
					if (random.nextBoolean()) {
						props.put(k, v);
					}
				});
			}
		}
		props.put("uid", uid);
		return props;
	}

	/** Every value type the codec has, weighted toward the ones with a boundary in them. */
	private Object randomValue(Run run, World world) {
		Random random = run.random;
		return switch (random.nextInt(20)) {
			case 0 -> (long) random.nextInt(5);
			case 1 -> random.nextBoolean() ? Long.MAX_VALUE : Long.MIN_VALUE;
			case 2 -> random.nextInt(4) / 2.0;
			case 3 -> random.nextBoolean();
			case 4 -> "";
			case 5 -> "s" + random.nextInt(5);
			// Either side of the 255 byte inline limit.
			case 6 -> "x".repeat(254 + random.nextInt(3));
			case 7 -> "y".repeat(300 + random.nextInt(2000));
			// Several page-sized blob extents.
			case 8 -> "z".repeat(9000 + random.nextInt(20000));
			case 9 -> bytes(random, random.nextBoolean() ? random.nextInt(8) : 250 + random.nextInt(600));
			case 10 -> Instant.ofEpochSecond(1_700_000_000L + random.nextInt(1000), random.nextInt(1000) * 1000L);
			case 11 -> new UUID(random.nextInt(4), random.nextInt(4));
			case 12 -> List.of((long) random.nextInt(3), "l" + random.nextInt(3));
			case 13 -> List.of("w".repeat(400), vector(random, DIMS), vector(random, DIMS + 1));
			case 14 -> world.nodeUids.isEmpty() ? "no-anchor-yet"
				: new AnchorRef(world.nodeUids.get(random.nextInt(Math.min(ANCHORS, world.nodeUids.size()))));
			case 15, 16 -> vector(random, DIMS);
			case 17 -> vector(random, DIMS + 2);
			case 18 -> List.of();
			default -> null;
		};
	}

	/** A small pool of vectors, so that several nodes share one and an equality lookup returns a set. */
	private static float[] vector(Random random, int dims) {
		float base = random.nextInt(5);
		float[] vector = new float[dims];
		for (int i = 0; i < dims; i++) {
			vector[i] = base + i * 0.5f;
		}
		return vector;
	}

	private static byte[] bytes(Random random, int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			bytes[i] = (byte) (random.nextInt(3) + i);
		}
		return bytes;
	}

	/**
	 * A reference to an anchor node, as the model holds it: by uid, so that it still names the right node after a
	 * compaction has moved it. It becomes an {@link ValueCodec.ElementRef} carrying the anchor's current id on its way
	 * into the store.
	 */
	record AnchorRef(long uid) {
	}

	private static Properties toProperties(Map<String, Object> props, World world) {
		Properties result = Properties.of();
		props.forEach((key, value) -> result.set(key,
			value instanceof AnchorRef ref ? new ValueCodec.ElementRef(world.nodeIds.get(ref.uid())) : value));
		return result;
	}

	// ---------------------------------------------------------------- comparison

	/** Everything observable, against the model. Runs on whichever thread the caller is on, and so through its view. */
	static void compareAll(GraphStore store, World world, String where) {
		compareAll(store, world, where, true);
	}

	/**
	 * @param counts
	 *            whether to compare {@code stats()} too. Not through a view: the live counts are the store's counters,
	 *            not something a view reconstructs, so a view sees today's numbers over yesterday's records.
	 */
	static void compareAll(GraphStore store, World world, String where, boolean counts) {
		GraphModel model = world.model;
		Map<Long, Long> uidOfNode = world.uidOfNode();
		Map<Long, Long> uidOfRel = world.uidOfRel();
		for (long uid : model.nodeIds()) {
			compareNode(store, world, uidOfNode, uidOfRel, uid, where);
		}
		for (long ruid : model.relationshipIds()) {
			compareRelationship(store, world, uidOfNode, ruid, where);
		}
		for (String label : LABELS) {
			assertEquals(model.nodesByLabel(label), uids(store.nodesByLabel(label), uidOfNode),
				where + ": the label index for " + label);
		}
		for (String key : KEYS) {
			for (Object value : sampleValues(model, key)) {
				assertEquals(modelByProperty(model, key, value), uids(store.nodesByProperty(key, value), uidOfNode),
					where + ": the property index for " + key + "=" + describe(value));
				for (String label : List.of("Person", "Asset", "Face")) {
					Set<Long> expected = new TreeSet<>();
					for (long uid : modelByProperty(model, key, value)) {
						if (model.labels(uid).contains(label)) {
							expected.add(uid);
						}
					}
					assertEquals(expected, uids(store.nodesByLabelAndProperty(label, key, value), uidOfNode),
						where + ": the composite lookup for :" + label + "." + key + "=" + describe(value));
				}
			}
		}
		compareVectors(store, world, uidOfNode, where);
		if (!counts) {
			return;
		}
		assertEquals(model.nodeIds().size(), store.stats().nodeCount(), where + ": live node count");
		assertEquals(model.relationshipIds().size(), store.stats().relationshipCount(),
			where + ": live relationship count");
	}

	/** A handful of nodes, for the read-your-writes check inside a transaction. */
	static void compareSome(GraphStore store, World world, String where, Random random) {
		Map<Long, Long> uidOfNode = world.uidOfNode();
		Map<Long, Long> uidOfRel = world.uidOfRel();
		List<Long> uids = new ArrayList<>(world.model.nodeIds());
		for (int i = 0; i < 4 && !uids.isEmpty(); i++) {
			compareNode(store, world, uidOfNode, uidOfRel, uids.get(random.nextInt(uids.size())), where);
		}
	}

	private static void compareNode(GraphStore store, World world, Map<Long, Long> uidOfNode,
		Map<Long, Long> uidOfRel, long uid, String where) {
		GraphModel model = world.model;
		long id = world.nodeIds.get(uid);
		String node = where + ": node uid=" + uid;
		assertTrue(store.nodeExists(id), node + " is missing");
		assertEquals(model.labels(uid), store.nodeLabels(id), node + " labels");
		assertEquals(model.label(uid), store.nodeLabel(id), node + " first label");
		assertEquals(canonical(model.properties(uid), world.nodeIds),
			canonical(store.nodeProperties(id).asMap(), null), node + " properties");
		assertEquals(model.degreeOut(uid), store.degreeOut(id), node + " degree_out");
		assertEquals(model.degreeIn(uid), store.degreeIn(id), node + " degree_in");
		List<String> expected = new ArrayList<>();
		for (GraphModel.Edge edge : model.edges()) {
			if (edge.from() == uid) {
				expected.add("OUT:" + edge.type() + ":" + edge.to());
			}
			if (edge.to() == uid) {
				expected.add("IN:" + edge.type() + ":" + edge.from());
			}
		}
		for (long ruid : model.relationshipIds()) {
			GraphModel.Relationship rel = model.relationship(ruid);
			if (rel.from() == uid) {
				expected.add("OUT:" + rel.type() + ":" + rel.to() + "#r" + ruid);
			}
			if (rel.to() == uid) {
				expected.add("IN:" + rel.type() + ":" + rel.from() + "#r" + ruid);
			}
		}
		expected.sort(null);
		List<String> actual = new ArrayList<>();
		for (Edge edge : store.neighbours(id, Direction.BOTH)) {
			actual.add(edge.direction() + ":" + edge.type() + ":" + uidOfNode.getOrDefault(edge.neighbourId(), -1L)
				+ (edge.isMaterialised() ? "#r" + uidOfRel.getOrDefault(edge.relationshipId(), -1L) : ""));
		}
		actual.sort(null);
		assertEquals(expected, actual, node + " neighbours");
	}

	private static void compareRelationship(GraphStore store, World world, Map<Long, Long> uidOfNode, long ruid,
		String where) {
		GraphModel.Relationship expected = world.model.relationship(ruid);
		long id = world.relIds.get(ruid);
		String rel = where + ": relationship ruid=" + ruid;
		assertTrue(store.relationshipExists(id), rel + " is missing");
		assertEquals(expected.type(), store.relationshipType(id), rel + " type");
		assertEquals(expected.from(), uidOfNode.get(store.relationshipFrom(id)), rel + " from");
		assertEquals(expected.to(), uidOfNode.get(store.relationshipTo(id)), rel + " to");
		assertEquals(canonical(expected.properties(), world.nodeIds),
			canonical(store.relationshipProperties(id).asMap(), null), rel + " properties");
	}

	/**
	 * The exact scan against the model's own answer, and the declared index's answer against the scan's member set:
	 * the index may rank approximately, but it must never return a node that is not a member.
	 */
	private static void compareVectors(GraphStore store, World world, Map<Long, Long> uidOfNode, String where) {
		GraphModel model = world.model;
		Set<Long> members = new TreeSet<>();
		for (long uid : model.nodesByLabel("Face")) {
			if (model.properties(uid).get("vec") instanceof float[] vector && vector.length == DIMS) {
				members.add(uid);
			}
		}
		float[] query = { 1f, 1.5f, 2f, 2.5f };
		Set<Long> scanned = new TreeSet<>();
		for (Neighbour neighbour : store.nearestByScan("Face", "vec", query, 1000, VectorMetric.EUCLIDEAN)) {
			scanned.add(uidOfNode.getOrDefault(neighbour.nodeId(), -1L));
		}
		assertEquals(members, scanned, where + ": nearestByScan member set");
		List<VectorIndexDefinition> declared = store.vectorIndexes();
		if (declared.isEmpty()) {
			return;
		}
		try {
			Set<Long> indexed = new TreeSet<>();
			for (Neighbour neighbour : store.nearest("Face", "vec", query, members.size() + 5, null)) {
				indexed.add(uidOfNode.getOrDefault(neighbour.nodeId(), -1L));
			}
			assertTrue(members.containsAll(indexed), where + ": the vector index returned non-members "
				+ diff(indexed, members) + " (members " + members + ")");
			assertEquals(members, indexed, where + ": the vector index lost members");
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static Set<Long> diff(Set<Long> a, Set<Long> b) {
		Set<Long> result = new TreeSet<>(a);
		result.removeAll(b);
		return result;
	}

	private static Set<Long> uids(List<Long> ids, Map<Long, Long> uidOfNode) {
		Set<Long> result = new TreeSet<>();
		for (long id : ids) {
			result.add(uidOfNode.getOrDefault(id, -id));
		}
		return result;
	}

	private static Set<Long> modelByProperty(GraphModel model, String key, Object value) {
		Set<Long> result = new TreeSet<>();
		Object wanted = canonicalValue(value, null);
		for (long uid : model.nodeIds()) {
			Map<String, Object> props = model.properties(uid);
			if (props.containsKey(key) && Objects.equals(canonicalValue(props.get(key), null), wanted)) {
				result.add(uid);
			}
		}
		return result;
	}

	/** A few values present in the model, so the index comparison covers hits, plus one miss. */
	private static Set<Object> sampleValues(GraphModel model, String key) {
		Map<Object, Object> values = new LinkedHashMap<>();
		for (long uid : model.nodeIds()) {
			Object value = model.properties(uid).get(key);
			if (value != null && !(value instanceof AnchorRef) && values.size() < 5) {
				values.putIfAbsent(canonicalValue(value, null), value);
			}
		}
		Set<Object> result = new LinkedHashSet<>(values.values());
		result.add("absent-value-that-nothing-has");
		return result;
	}

	/**
	 * A comparable form of a property map. Arrays compare by content, integers are widened as the store widens them,
	 * and an element reference is compared by what it names — through the uid map on the model side, as the raw id on
	 * the store side — so a compaction that moved the target is judged on whether it rewrote the reference.
	 */
	private static Map<String, Object> canonical(Map<String, Object> props, Map<Long, Long> nodeIds) {
		Map<String, Object> result = new TreeMap<>();
		props.forEach((key, value) -> result.put(key, canonicalValue(value, nodeIds)));
		return result;
	}

	private static Object canonicalValue(Object value, Map<Long, Long> nodeIds) {
		if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
			return ((Number) value).longValue();
		}
		if (value instanceof float[] vector) {
			return "f32" + Arrays.toString(vector);
		}
		if (value instanceof byte[] bytes) {
			return "bytes" + Arrays.toString(bytes);
		}
		if (value instanceof AnchorRef ref) {
			return "ref:" + (nodeIds == null ? "uid" + ref.uid() : nodeIds.get(ref.uid()));
		}
		if (value instanceof ValueCodec.ElementRef ref) {
			return "ref:" + ref.id();
		}
		if (value instanceof List<?> list) {
			List<Object> result = new ArrayList<>(list.size());
			for (Object element : list) {
				result.add(canonicalValue(element, nodeIds));
			}
			return result;
		}
		return value;
	}

	private static String describe(Map<String, Object> props) {
		Map<String, String> result = new TreeMap<>();
		props.forEach((key, value) -> result.put(key, describe(value)));
		return result.toString();
	}

	private static String describe(Object value) {
		String text = String.valueOf(canonicalValue(value, null));
		String type = value == null ? "" : ":" + value.getClass().getSimpleName();
		return (text.length() > 60 ? text.substring(0, 60) + "...(" + text.length() + ")" : text) + type;
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
