package io.metaloom.graph.core.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.CompactionReport;
import io.metaloom.graph.core.api.CompositeIndex;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ScanOptions;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotStats;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.TransactionConflictException;
import io.metaloom.graph.core.api.StoreClosedException;
import io.metaloom.graph.core.api.StoreMetrics;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorIndexState;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.Vectors;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.VerifyOptions;
import io.metaloom.graph.core.catalog.Catalog;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.index.HnswIndex;
import io.metaloom.graph.core.index.IndexCatalog;
import io.metaloom.graph.core.maintenance.Compactor;
import io.metaloom.graph.core.maintenance.MaintenanceRunner;
import io.metaloom.graph.core.metrics.IoCounters;
import io.metaloom.graph.core.nativelib.NativeLib;
import io.metaloom.graph.core.txn.MetaJournal;
import io.metaloom.graph.core.txn.WriteContext;
import io.metaloom.graph.core.txn.WriteContextHolder;
import io.metaloom.graph.core.page.PageOverlay;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.segment.SegmentFile;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.segment.Superblock;
import io.metaloom.graph.core.store.adj.AdjacencyStore;
import io.metaloom.graph.core.store.adj.PackedEdge;
import io.metaloom.graph.core.store.adj.TypeFilter;
import io.metaloom.graph.core.store.blob.BlobStore;
import io.metaloom.graph.core.store.node.NodeStore;
import io.metaloom.graph.core.store.prop.PropertyStore;
import io.metaloom.graph.core.store.vec.VectorRecord;
import io.metaloom.graph.core.store.vec.VectorStore;
import io.metaloom.graph.core.store.rel.RelStore;
import io.metaloom.graph.core.txn.PageVersions;
import io.metaloom.graph.core.txn.Snapshot;
import io.metaloom.graph.core.txn.TransactionImpl;
import io.metaloom.graph.core.txn.TransactionManager;
import io.metaloom.graph.core.wal.WalManager;

/**
 * Wires the stores together and owns the store lifecycle, the transaction boundary and the log.
 *
 * @see <a href="../../../../../../../../spec/ARCHITECTURE.md">spec/ARCHITECTURE.md</a>
 */
public final class GraphStoreImpl implements GraphStore, TransactionImpl.Completion, MaintenanceRunner,
	MetaJournal.Target {

	private static final Logger logger = LoggerFactory.getLogger(GraphStoreImpl.class);

	private final Path path;
	/** What this open was asked for. Only the per-open settings are read back; the manifest owns the rest. */
	private final GraphStoreOptions openedWith;
	private final Geometry geometry;
	private final Manifest manifest;
	private final Catalog catalog;
	private final Map<Kind, SegmentSet> segmentSets;
	private final NodeStore nodes;
	private final RelStore rels;
	private final AdjacencyStore adjacency;
	private final PropertyStore properties;
	private final BlobStore blobs;
	/** Null unless the store was created with vector storage — see GraphStoreOptions.vectorDimensions. */
	private final VectorStore vectors;
	private final IndexCatalog indexes;
	/** Null unless the store has vector storage. The ANN structure over the vector records. */
	private final HnswIndex hnsw;
	private final WalManager wal;
	private final WriteContextHolder contexts = new WriteContextHolder();
	private final TransactionManager transactions;
	private final IoCounters counters;

	private final FileChannel lockChannel;
	private final FileLock lock;

	/**
	 * Writers hold this shared for the length of a transaction; maintenance takes it exclusively.
	 *
	 * <p>
	 * It is what replaced the single writer lock, and the change of shape is the change of design. The old lock made a
	 * transaction the unit of mutual exclusion between writers, which is what {@code TRANSACTIONS.md §6} could
	 * correctly say left nothing for conflict detection to detect. This one excludes nobody from anybody except the
	 * operations that genuinely cannot run beside a writer — {@code verify}, {@code checkpoint}, {@code reclaim},
	 * compaction, {@code close} — each of which reaches past the transaction mechanism rather than through it.
	 * </p>
	 *
	 * <p>
	 * Not fair, but a queued exclusive acquisition does block new shared ones, which is what stops a stream of short
	 * writers starving a checkpoint forever. Reentrant in the direction maintenance needs: a compaction batch holds it
	 * exclusively and then opens a transaction, which takes it shared, and {@link ReentrantReadWriteLock} permits that
	 * downgrade.
	 * </p>
	 */
	private final ReentrantReadWriteLock writeGate = new ReentrantReadWriteLock();

	/**
	 * Takes {@link #writeGate} exclusively, refusing instead when this thread's own open transaction holds it shared.
	 * A read hold cannot be upgraded, so the acquisition would wait for the caller forever — holding back every
	 * {@code begin()} queued behind it.
	 */
	private void lockGateExclusively(String operation) {
		if (writeGate.getReadHoldCount() > 0 && !writeGate.isWriteLockedByCurrentThread()) {
			throw new GraphStoreException(operation + " cannot run inside a write transaction on the same thread;"
				+ " commit or roll it back first");
		}
		writeGate.writeLock().lock();
	}

	/**
	 * The commit point, and the only thing about a write transaction that is still serialised.
	 *
	 * <p>
	 * Held across four steps that have to be one instant: folding the transaction's metadata journal onto the live
	 * state, validating its write set against what has committed since it began, appending its redo records, and
	 * copying them into the mapping. Validation and the append in particular cannot be separated — a conflict decided
	 * against one LSN and logged against another is a conflict check that proves nothing.
	 * </p>
	 */
	private final ReentrantLock commitLock = new ReentrantLock();

	/** How many times an auto-commit mutation re-runs itself after losing a conflict. See {@link #write}. */
	private static final int AUTO_COMMIT_ATTEMPTS = 64;

	/**
	 * How often an auto-commit write gave up on optimism and took the write gate exclusively.
	 *
	 * <p>
	 * Not in {@link StoreMetrics}, which is the durability and I/O surface. This is here because it is the one number
	 * that distinguishes "the retry loop absorbed some contention", which is the design working, from "the retry loop
	 * could not and the engine serialised writers to finish the statement", which is a workload that wants an explicit
	 * transaction or a less contended index. A test asserts it moved; an operator reading a rising count is being told
	 * where their write throughput went.
	 * </p>
	 */
	private final LongAdder autoCommitEscalations = new LongAdder();

	/**
	 * How often an auto-commit write lost a conflict and re-ran itself optimistically.
	 *
	 * <p>
	 * The companion to {@link #autoCommitEscalations}, and the number that says whether a workload met any contention
	 * at all. Escalation is starvation past {@link #AUTO_COMMIT_ATTEMPTS} attempts in a row, which is rare by design
	 * and emergent rather than scheduled: whether a given run reaches it depends on how the machine happened to
	 * interleave its writers, so it is the wrong thing to read — or to assert — as evidence that writers collided. A
	 * lost conflict is the direct evidence, it is counted here, and six writers over one hot index leaf produce
	 * hundreds of them on any machine.
	 * </p>
	 */
	private final LongAdder autoCommitRetries = new LongAdder();

	/**
	 * Held for writing only while a commit copies its pages into the mapping, so a reader never observes half of a
	 * commit.
	 * <p>
	 * A {@link StampedLock} rather than a read-write lock, because the ordinary read path takes this on <em>every</em>
	 * operation and a shared reader count is a contended cache line. Acquiring a read lock writes to it; a thousand
	 * cores acquiring it write to the same line a thousand times and reads get slower as threads are added. An
	 * optimistic stamp is a plain read of a version counter and adds nothing to the line, so the fast path costs
	 * nothing beyond a load and a comparison.
	 */
	private final StampedLock applyLock = new StampedLock();

	/**
	 * Commits waiting for {@link #applyLock} exclusively — at most one, because the wait happens under the commit lock.
	 *
	 * <p>
	 * {@link StampedLock} has no writer preference: a reader acquires whenever the write bit is clear, whether or not a
	 * writer is queued. That is harmless for the short read holds, and starvation for the long ones. A scan or a cursor
	 * holds the latch through a {@link ReadLease} for its whole length, and with a few scanning threads their holds
	 * overlap continuously, so the write bit is never clear for the committer to set: measured on 2,000 nodes, commits
	 * fell from 127 a second to 3 with four threads looping {@code forEachNode}, with single commits waiting most of a
	 * second — and every other writer queued behind the commit lock with it. A lease therefore yields to a waiting
	 * apply before it takes the latch; see {@link #enterApplyShared(boolean)}.
	 * </p>
	 */
	private final java.util.concurrent.atomic.AtomicInteger applyWaiters =
		new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * The longest a new lease defers to a waiting apply before taking the latch anyway. A backstop, not the expected
	 * case: an apply copies a commit's runs and is over in microseconds once the readers ahead of it drain.
	 */
	private static final long LEASE_YIELD_MAX_NANOS = 50_000_000L;

	/** The apply latch, exclusively, announced first so that new leases stand aside. */
	private long applyWriteLock() {
		applyWaiters.incrementAndGet();
		try {
			return applyLock.writeLock();
		} finally {
			applyWaiters.decrementAndGet();
		}
	}

	/**
	 * One thread's shared hold on {@link #applyLock}: how many scopes on the thread are inside it, and the single real
	 * stamp under all of them.
	 *
	 * <p>
	 * <b>The latch is taken once per thread, however deeply the scopes nest.</b> A {@link StampedLock} read hold is not
	 * reentrant: a second {@code readLock()} from a thread that already holds one is an ordinary acquisition, and when
	 * its fast path loses a race with another reader it queues — behind a commit already waiting for the latch
	 * exclusively, which is waiting for the hold this same thread still has. Nesting is not exotic: a mutation calls the
	 * store's own public reads ({@code setNodeProperties} on a vector-indexed node asks {@code nodeLabels}), a scan
	 * callback reads, a cursor is opened while another is open, and {@code begin()} is called with a cursor open.
	 * {@code NestedApplyLatchTest} wedged the store every run before this, with a commit parked in {@code writeLock} and
	 * every other writer queued behind the commit lock.
	 * </p>
	 */
	private static final class ApplyHold {

		int depth;
		long stamp;
	}

	private final ThreadLocal<ApplyHold> applyHold = ThreadLocal.withInitial(ApplyHold::new);

	/**
	 * Enters the calling thread's shared hold, acquiring the latch only for the outermost scope.
	 *
	 * @param yieldToApply
	 *            whether a new outermost hold stands aside for a waiting apply first — true for the long holds of
	 *            {@link ReadLease}, which are what starve a commit; see {@link #applyWaiters}. Never for a nested
	 *            scope, whose thread already holds the latch the apply is waiting for
	 * @return the hold, to be passed back to {@link #exitApplyShared}
	 */
	private ApplyHold enterApplyShared(boolean yieldToApply) {
		ApplyHold hold = applyHold.get();
		if (hold.depth == 0) {
			if (yieldToApply) {
				yieldToWaitingApply();
			}
			hold.stamp = applyLock.readLock();
		}
		hold.depth++;
		return hold;
	}

	/** Leaves one scope of a shared hold, releasing the latch with the outermost. */
	private void exitApplyShared(ApplyHold hold) {
		if (--hold.depth == 0) {
			long stamp = hold.stamp;
			hold.stamp = 0;
			applyLock.unlockRead(stamp);
		}
	}

	/**
	 * Waits, briefly, for a commit that is waiting for the apply latch. Bounded, so a waiting apply this thread cannot
	 * see the end of costs latency rather than progress.
	 */
	private void yieldToWaitingApply() {
		if (applyWaiters.get() == 0) {
			return;
		}
		long deadline = System.nanoTime() + LEASE_YIELD_MAX_NANOS;
		for (int spins = 0; applyWaiters.get() != 0; spins++) {
			if (spins < 128) {
				Thread.onSpinWait();
			} else if (System.nanoTime() - deadline > 0) {
				return;
			} else {
				java.util.concurrent.locks.LockSupport.parkNanos(10_000);
			}
		}
	}

	/**
	 * The write transaction running on each thread, if any.
	 *
	 * <p>
	 * One field sufficed while one writer at a time held a lock; with several in flight the question "is there a write
	 * transaction" only has an answer per thread. Removed rather than nulled when a transaction ends, so a pooled
	 * thread does not carry a dead handle into its next task.
	 * </p>
	 */
	private final ThreadLocal<TransactionImpl> currentTransaction = new ThreadLocal<>();

	/**
	 * How many {@link ReadLease}s the calling thread holds. A commit waits for the apply latch exclusively and the
	 * latch is not reentrant, so a commit on a thread that still holds a lease — a write inside a scan callback, a
	 * commit with a cursor open — would wait for itself while holding the commit lock, and every writer behind it.
	 */
	private final ThreadLocal<int[]> heldLeases = ThreadLocal.withInitial(() -> new int[1]);

	/**
	 * The LSN of the newest commit whose pages are in the mapping, which is what a new read view is fixed at.
	 * <p>
	 * Written under the apply lock and read without it, so it is volatile. It is deliberately not {@code durableLsn}:
	 * durability and visibility are different instants, and a view must be fixed at what it can actually see.
	 */
	private volatile long appliedLsn;

	private final AtomicLong nextTxnId = new AtomicLong();
	private volatile long lastCheckpointLsn;
	/**
	 * Volatile because {@code checkOpen()} reads it on every operation from every thread, while {@code close()} writes
	 * it under the writer lock — which readers never take. In practice the surrounding lock traffic publishes it, and
	 * {@code CloseUnderLoadTest} does not catch the difference; it is volatile so that the guarantee does not rest on
	 * that happening to be true.
	 */
	private volatile boolean closed;

	private GraphStoreImpl(Path path, Geometry geometry, Manifest manifest, Catalog catalog,
		Map<Kind, SegmentSet> segmentSets, WalManager wal, FileChannel lockChannel, FileLock lock,
		IoCounters counters, long retainedVersionBytes, GraphStoreOptions openedWith) {
		this.openedWith = openedWith;
		this.path = path;
		this.counters = counters;
		this.transactions = new TransactionManager(retainedVersionBytes);
		this.geometry = geometry;
		this.manifest = manifest;
		this.catalog = catalog;
		this.segmentSets = segmentSets;
		this.wal = wal;
		this.lockChannel = lockChannel;
		this.lock = lock;
		this.nextTxnId.set(manifest.nextTxnId());
		// Everything the log had is already in the mapping by the time the store is constructed — recovery ran first.
		// A view opened before the first commit of this session must therefore be fixed here, not at zero, or it would
		// consider every page it reads to predate it.
		this.appliedLsn = wal.lastCommitLsn();

		// Order matters: the property store backs the node store's label overflow, so it has to exist first.
		this.blobs = new BlobStore(segmentSets.get(Kind.BLOB));
		SegmentSet vecSegments = segmentSets.get(Kind.VEC);
		this.vectors = vecSegments == null ? null : new VectorStore(vecSegments, manifest.vectorDimensions());
		this.properties = new PropertyStore(segmentSets.get(Kind.PROP), blobs, vectors);
		this.nodes = new NodeStore(segmentSets.get(Kind.NODE), properties);
		this.rels = new RelStore(segmentSets.get(Kind.REL));
		this.adjacency = new AdjacencyStore(segmentSets.get(Kind.ADJ), nodes);
		this.indexes = new IndexCatalog(segmentSets.get(Kind.INDEX), catalog);
		this.hnsw = vectors == null ? null : new HnswIndex(vectors, indexes);
		if (hnsw != null) {
			properties.onVectorRelease(this::releaseVectorMember);
		}

		for (SegmentSet set : segmentSets.values()) {
			set.writeContexts(contexts);
			set.snapshots(transactions.holder());
			set.counters(counters);
		}
		wal.counters(counters);
		// A miss is a read that touches no page; a SERIALIZABLE writer remembers it so the commit can validate it.
		catalog.observeMisses((label, name) -> {
			WriteContext context = contexts.current();
			if (context != null) {
				context.missed(label, name);
			}
		});
	}

	public static GraphStoreImpl open(Path path, GraphStoreOptions options) throws IOException {
		Files.createDirectories(path);

		FileChannel lockChannel = FileChannel.open(path.resolve("LOCK"), StandardOpenOption.CREATE,
			StandardOpenOption.READ, StandardOpenOption.WRITE);
		FileLock lock;
		try {
			lock = lockChannel.tryLock();
		} catch (OverlappingFileLockException e) {
			lockChannel.close();
			throw new GraphStoreException("Store at " + path + " is already open in this JVM", e);
		}
		if (lock == null) {
			lockChannel.close();
			throw new GraphStoreException("Store at " + path + " is locked by another process");
		}

		Manifest manifest = null;
		Catalog catalog = null;
		WalManager wal = null;
		Map<Kind, SegmentSet> sets = new EnumMap<>(Kind.class);
		IoCounters counters = new IoCounters();
		try {
			int vectorDimensions = options.vectorDimensions();
			manifest = Manifest.open(path, options.pageSize(), options.segmentCapacity(),
				VectorRecord.strideFor(vectorDimensions, options.pageSize()), vectorDimensions);
			Geometry geometry = manifest.geometry();
			catalog = new Catalog(path);
			for (Kind kind : new Kind[] { Kind.NODE, Kind.REL, Kind.ADJ, Kind.PROP, Kind.BLOB, Kind.INDEX }) {
				sets.put(kind, new SegmentSet(path, kind, geometry));
			}
			// Vector storage exists only if the store was created with it, and the manifest is the authority — not the
			// options, which may not mention it on a reopen.
			//
			// It has to be built HERE, before recovery below. A committed VEC page image is replayed by looking its
			// kind up in this map, so a set that is missing turns a perfectly ordinary recovery into
			// "Recovery references VEC segment 0", which reads as corruption and refuses to open the store at all.
			if (geometry.isEnabled(Kind.VEC)) {
				sets.put(Kind.VEC, new SegmentSet(path, Kind.VEC, geometry));
			}

			for (SegmentSet set : sets.values()) {
				set.counters(counters);
			}
			// Redo every committed transaction that had not reached the data files, before anything reads them.
			long recoveryStart = System.nanoTime();
			long resumeLsn = WalManager.recover(path, options.walSegmentBytes(), manifest.checkpointLsn(), sets);
			for (SegmentSet set : sets.values()) {
				set.afterRecovery();
			}
			if (resumeLsn != manifest.checkpointLsn()) {
				counters.recovered(System.nanoTime() - recoveryStart, counters.snapshot().pageApplies());
			}
			wal = new WalManager(path, options.walSegmentBytes(), resumeLsn, sets, options.walSyncMode(),
				options.walSyncIntervalMillis(), options.ioEngine());

			logger.debug("Opened graph store {} with {} at LSN {}", path, geometry, resumeLsn);
			GraphStoreImpl store = new GraphStoreImpl(path, geometry, manifest, catalog, sets, wal, lockChannel, lock,
				counters, options.retainedVersionBytes(), options);
			if (resumeLsn != manifest.checkpointLsn()) {
				// A recovery happened: checkpoint immediately so the next open replays nothing.
				store.checkpoint();
			}
			return store;
		} catch (RuntimeException | IOException e) {
			if (wal != null) {
				wal.close();
			}
			for (SegmentSet set : sets.values()) {
				try {
					set.close();
				} catch (IOException ignored) {
					// The original failure is the interesting one.
				}
			}
			if (catalog != null) {
				catalog.close();
			}
			if (manifest != null) {
				manifest.close();
			}
			lock.release();
			lockChannel.close();
			throw e;
		}
	}

	private void checkOpen() {
		if (closed) {
			throw new StoreClosedException("Store at " + path + " is closed");
		}
	}

	// ---------------------------------------------------------------- transactions

	@Override
	public Transaction begin() {
		return begin(IsolationLevel.SNAPSHOT);
	}

	@Override
	public Transaction begin(IsolationLevel level) {
		checkOpen();
		if (level == IsolationLevel.READ_COMMITTED) {
			// A write transaction cannot read the live mapping and still be a transaction. Its own writes are in its
			// overlay, but everything else would move underneath it between one statement and the next, and it would
			// then decide what to write from a state that no longer holds — which is the lost update the whole design
			// is built to make impossible. Refused rather than silently upgraded, so a caller asking for it finds out.
			throw new IllegalArgumentException("A write transaction cannot run at READ_COMMITTED; use SNAPSHOT, or"
				+ " SERIALIZABLE if what it reads must be validated too. READ_COMMITTED is a read-view level.");
		}
		// Refused rather than allowed to nest. Nesting used to succeed and then lose data silently: the inner
		// transaction publishes its own overlay, and committing it clears that overlay while the outer transaction is
		// still live, after which every remaining write in the outer transaction goes straight to the mapping —
		// unlogged, and past the point where a rollback could take it back. A caller that wants to join an existing
		// transaction asks with hasWriteTransaction() and simply does not call begin().
		TransactionImpl open = currentTransaction.get();
		if (open != null) {
			throw new GraphStoreException("A write transaction (id " + open.id()
				+ ") is already open on thread '" + Thread.currentThread().getName()
				+ "'. Nested write transactions are not supported: commit or roll back the open one first, or test"
				+ " hasWriteTransaction() and join it instead of beginning another.");
		}
		// Shared, for the length of the transaction. It excludes maintenance, not other writers.
		writeGate.readLock().lock();
		try {
			checkOpen();
			// Under the apply read latch so that the LSN and the registration land together, exactly as beginRead()
			// must: a commit slipping between them would fix the writer at an LSN whose before-images — the input to
			// its own conflict detection — nobody was asked to keep.
			Snapshot snapshot;
			ApplyHold hold = enterApplyShared(false);
			try {
				snapshot = transactions.beginWrite(level, appliedLsn, System.nanoTime());
			} finally {
				exitApplyShared(hold);
			}
			snapshot.guard(this::underApplyReadLock);
			TransactionImpl txn = new TransactionImpl(nextTxnId.getAndIncrement(), geometry.pageSize(), snapshot, level,
				this);
			contexts.set(txn.context());
			currentTransaction.set(txn);
			return txn;
		} catch (RuntimeException e) {
			writeGate.readLock().unlock();
			throw e;
		}
	}

	/**
	 * Runs a page reconstruction with the apply step excluded, for a writer that has no outer frame to retry in.
	 *
	 * @see Snapshot.ExclusionGuard
	 */
	private java.lang.foreign.MemorySegment underApplyReadLock(
		java.util.function.Supplier<java.lang.foreign.MemorySegment> work) {
		ApplyHold hold = enterApplyShared(false);
		try {
			return work.get();
		} finally {
			exitApplyShared(hold);
		}
	}

	@Override
	public long commit(TransactionImpl txn) throws IOException {
		return uninterruptible(() -> commitInternal(txn));
	}

	/**
	 * Runs a durable write with this thread's interrupt status held back, and restores it afterwards.
	 *
	 * <p>
	 * {@link java.nio.channels.FileChannel} is an {@link java.nio.channels.InterruptibleChannel}: reaching one with the
	 * interrupt flag set makes the JDK <b>close the channel</b> rather than fail the single call. The WAL channel is
	 * shared by the whole store, so one interrupted writer used to leave every subsequent commit — on every thread —
	 * failing with {@code ClosedChannelException}, and {@code close()} failing the same way on the way out. One
	 * cancelled task bricked the store for the life of the process, which is not a cost cancellation is allowed to
	 * have. Callers interrupt for ordinary reasons: {@code shutdownNow()}, {@code Future.cancel(true)}, a query
	 * deadline.
	 * </p>
	 *
	 * <p>
	 * Masking is not swallowing: the flag is restored before returning, so the caller's cancellation is still
	 * delivered — just not in the middle of making a transaction durable. <b>The residual window is an interrupt that
	 * arrives from another thread while the force is already in flight</b>; closing that one needs the log writes moved
	 * onto a thread nobody else can interrupt, which is a larger change than this.
	 * </p>
	 */
	private <T> T uninterruptible(IoWork<T> work) throws IOException {
		boolean interrupted = Thread.interrupted();
		try {
			return work.run();
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@FunctionalInterface
	private interface IoWork<T> {
		T run() throws IOException;
	}

	/**
	 * The commit point.
	 *
	 * <p>
	 * Five steps under one lock, in an order that is not negotiable. The journal is folded first, because until it is
	 * the transaction's allocations and counter moves are not expressed as bytes at all and there is nothing to log.
	 * Validation comes next, against the version chains as they stand at that instant. Then the dictionary, because
	 * ids interned by this transaction are embedded in the records it is about to make durable. Then the log and the
	 * fsync, which is the barrier. Only then the mapping.
	 * </p>
	 *
	 * <p>
	 * Validating before logging rather than after is what makes a conflict cost nothing: a refused transaction has
	 * written no log record and touched no data file, so it is over the instant it is refused.
	 * </p>
	 */
	private long commitInternal(TransactionImpl txn) throws IOException {
		WriteContext context = txn.context();
		boolean committed = false;
		try {
			if (heldLeases.get()[0] > 0) {
				// Inside the try, so the transaction is still ended — rolled back — and its gate hold released.
				throw new GraphStoreException("Cannot commit while this thread holds an open cursor or is inside a scan"
					+ " callback: the commit would wait for the thread's own read latch. Close the cursor, or collect"
					+ " the ids and write after the scan. The transaction has been rolled back.");
			}
			commitLock.lock();
			try {
				// The data pages first, and validation before anything else is touched. Folding first and validating
				// afterwards was wrong in a way that took a while to see: the fold moves the shared superblock
				// counters, so a conflict raised after it left the live count advanced over a bitmap whose bits were
				// then discarded with the overlay — verify() reporting an accounting mismatch on a store where nothing
				// had gone wrong except a transaction losing a race.
				List<WalManager.PageDiff> diffs = diffPages(context, false);
				validate(txn, context, diffs);

				Map<Long, long[]> headers = captureHeaders(context);
				boolean folded = false;
				try {
					fold(context);
					folded = true;
					diffs.addAll(diffPages(context, true));
					// Dictionary ids are embedded in the records being committed, so they must be durable first.
					catalog.force();
					WalManager.CommitBatch batch = wal.logCommit(txn.id(), diffs);
					long stamp = applyWriteLock();
					try {
						retainVersions(batch, context);
						wal.apply(batch);
						if (batch.commitLsn() > appliedLsn) {
							appliedLsn = batch.commitLsn();
						}
					} catch (RuntimeException | Error e) {
						// Durable in the log, rolled back in memory below: the two now disagree, and a later commit
						// reusing this transaction's slots would be replayed on top of it. No further commits until a
						// reopen lets recovery settle it. A failure inside logCommit has already done the same.
						wal.poison(e);
						throw e;
					} finally {
						applyLock.unlockWrite(stamp);
					}
					committed = true;
					return batch.commitLsn();
				} finally {
					if (folded && !committed) {
						// The log or the dictionary failed after the fold. The overlay goes with the rollback, but the
						// header counters are Java fields that the fold has already moved, and nothing else would put
						// them back.
						restoreHeaders(headers);
					}
				}
			} finally {
				commitLock.unlock();
			}
		} finally {
			long lsn = endTransaction(txn, committed);
			if (committed) {
				// Outside both locks: reclamation only reads the active set and unlinks dead chain entries, and it can
				// walk every versioned page, which is far too long to hold either the commit lock or readers out for.
				// After endTransaction, so that this transaction's own view no longer pins the horizon.
				transactions.vacuum(lsn);
			}
		}
	}

	/**
	 * The runs this transaction changed, on either the metadata pages or everything else.
	 *
	 * <p>
	 * Split by region because the two halves are settled at different points in the commit: the data pages are final
	 * when the transaction stops writing, and the metadata pages do not exist until {@link #fold} builds them.
	 * </p>
	 */
	private List<WalManager.PageDiff> diffPages(WriteContext context, boolean metadata) {
		List<WalManager.PageDiff> diffs = new ArrayList<>();
		PageOverlay overlay = context.overlay();
		for (int i = 0, n = overlay.size(); i < n; i++) {
			long key = overlay.keyAt(i);
			if (isMetadataPage(key) != metadata) {
				continue;
			}
			PageOverlay.Page page = overlay.pageAt(i);
			// Only the window the transaction was handed for writing can differ from the base.
			List<int[]> runs = WalManager.changedRuns(page.base(), page.copy(), page.writtenFrom(), page.writtenTo());
			assert sameRuns(runs, WalManager.changedRuns(page.base(), page.copy())) : "bytes of "
				+ describe(key, -1, -1) + " were changed outside every slice handed out for writing";
			if (!runs.isEmpty()) {
				diffs.add(new WalManager.PageDiff(key, page, runs));
			}
		}
		return diffs;
	}

	/** For the assertion in {@link #diffPages}: the windowed diff must find exactly what the whole-page diff finds. */
	private static boolean sameRuns(List<int[]> a, List<int[]> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (!java.util.Arrays.equals(a.get(i), b.get(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a page holds the segment header, the free-slot bitmap or the page metadata array rather than records.
	 *
	 * <p>
	 * Decided from the geometry rather than from whether the overlay happened to rebase it, so that a metadata page
	 * that reached the overlay by some other route — {@code force()} inside a maintenance transaction writes the
	 * superblock directly — is still recognised. The regions are whole pages, which
	 * {@link Geometry} guarantees by aligning each of them up, so a page is never half one and half the other.
	 * </p>
	 */
	private boolean isMetadataPage(long pageKey) {
		Kind kind = PageOverlay.kindOf(pageKey);
		return PageOverlay.pageOf(pageKey) < geometry.dataStart(kind) / geometry.pageSize();
	}

	/**
	 * Folds the transaction's metadata journal onto the live state.
	 *
	 * <p>
	 * Under the commit lock, which is the only place "the live state" is a fixed instant. The replay rebases each
	 * metadata page onto the mapping as it stands and applies the transaction's operations — set this bit, add this
	 * delta — on top, so they carry every writer that committed since this one began instead of the image it happened
	 * to read.
	 * </p>
	 */
	private void fold(WriteContext context) {
		MetaJournal journal = context.journal();
		if (journal.isEmpty()) {
			return;
		}
		context.replaying(true);
		try {
			journal.replay(this);
			// The headers last, because the fold above is what gives them their final counters. Every segment, not
			// only the journalled ones: a counter moved outside a transaction — by recovery, or by a segment created
			// mid-transaction — is still owed a durable header.
			for (SegmentSet set : segmentSets.values()) {
				for (SegmentFile segment : set.segments()) {
					if (segment.superblock().counterDirty()) {
						segment.superblock().flush();
					}
				}
			}
		} finally {
			context.replaying(false);
		}
	}

	private Map<Long, long[]> captureHeaders(WriteContext context) {
		Map<Long, long[]> captured = new HashMap<>();
		MetaJournal journal = context.journal();
		for (int i = 0, n = journal.segmentCount(); i < n; i++) {
			long segmentKey = journal.segmentKeyAt(i);
			SegmentFile segment = journalSegment(segmentKey);
			if (segment != null) {
				captured.put(segmentKey, segment.superblock().captureCounters());
			}
		}
		return captured;
	}

	private void restoreHeaders(Map<Long, long[]> captured) {
		for (Map.Entry<Long, long[]> entry : captured.entrySet()) {
			SegmentFile segment = journalSegment(entry.getKey());
			if (segment != null) {
				segment.superblock().restoreCounters(entry.getValue());
			}
		}
	}

	/**
	 * Refuses the commit if anything it depends on has changed since it began.
	 *
	 * <p>
	 * <b>Write set, byte by byte.</b> Every run the transaction is about to write is checked against the page's version
	 * chain for an overlapping run committed after its snapshot. Byte ranges rather than pages, because a page holds a
	 * hundred and twenty-eight nodes and two writers touching different ones have not met.
	 * </p>
	 *
	 * <p>
	 * <b>Except the slots it allocated itself.</b> Those bytes were dead — the allocator handed the slot to this
	 * transaction and to nobody else — so whatever a previous occupant left there cannot be something another
	 * transaction is depending on. Without this exemption, reusing a slot freed since the snapshot would conflict every
	 * time, and a store that deletes anything would stop being able to insert concurrently.
	 * </p>
	 *
	 * <p>
	 * <b>Read set, page by page, at SERIALIZABLE only.</b> See {@link IsolationLevel#SERIALIZABLE}.
	 * </p>
	 */
	private void validate(TransactionImpl txn, WriteContext context, List<WalManager.PageDiff> diffs) {
		long since = context.snapshot().snapshotLsn();
		PageVersions versions = transactions.versions();
		Map<Long, List<int[]>> owned = ownedRanges(context);
		for (WalManager.PageDiff diff : diffs) {
			if (isMetadataPage(diff.key())) {
				// Already merged as operations rather than as bytes. See PageOverlay.Page#isRebased.
				continue;
			}
			List<int[]> exempt = owned.get(diff.key());
			for (int[] run : diff.runs()) {
				for (int[] part : outside(run, exempt)) {
					long conflict = versions.conflictingLsn(diff.key(), part[0], part[1], since);
					if (conflict > 0) {
						throw new TransactionConflictException(txn.id(), diff.key(), since, conflict, false,
							describe(diff.key(), part[0], part[1]));
					}
				}
			}
		}
		if (!context.tracksReads()) {
			return;
		}
		for (long key : context.readSet()) {
			long conflict = versions.changedSince(key, since);
			if (conflict > 0) {
				throw new TransactionConflictException(txn.id(), key, since, conflict, true,
					describe(key, -1, -1));
			}
		}
		// The reads that touched no page: a name the dictionary did not have. Refused if it has it now, whoever added
		// it. The dictionary is not versioned, so which commit introduced the name is not known and the newest applied
		// LSN stands in for it. Coarse in one direction only — a transaction that missed a name and then introduced it
		// itself is refused once, and its retry finds the name and validates normally — and never a wrong answer.
		// Copies, because the lookups below report misses back into the same sets.
		for (String label : List.copyOf(context.missedLabels())) {
			if (catalog.lookupLabel(label) != 0) {
				throw new TransactionConflictException(txn.id(), 0, since, appliedLsn, true,
					"the label or relationship type '" + label + "', unknown when this transaction looked it up,");
			}
		}
		for (String key : List.copyOf(context.missedKeys())) {
			if (catalog.lookupKey(key) != 0) {
				throw new TransactionConflictException(txn.id(), 0, since, appliedLsn, true,
					"the property key '" + key + "', unknown when this transaction looked it up,");
			}
		}
	}

	/** Which bytes of which pages hold slots this transaction allocated, and therefore exclusively owns. */
	private Map<Long, List<int[]>> ownedRanges(WriteContext context) {
		if (context.reservedSlots().isEmpty()) {
			return Map.of();
		}
		Map<Long, List<int[]>> owned = new HashMap<>();
		int pageSize = geometry.pageSize();
		for (WriteContext.SlotRun run : context.reservedSlots()) {
			Kind kind = Kind.byCode(MetaJournal.kindCodeOf(run.segmentKey()));
			int segmentNumber = MetaJournal.segmentNumberOf(run.segmentKey());
			long offset = geometry.byteOffset(kind, run.slot());
			int length = geometry.stride(kind) * run.count();
			int pageIndex = (int) (offset / pageSize);
			int inPage = (int) (offset % pageSize);
			// A run never straddles a page — invariant I1 — so one entry always covers it.
			owned.computeIfAbsent(PageOverlay.key(kind, segmentNumber, pageIndex), k -> new ArrayList<>())
				.add(new int[] { inPage, inPage + length });
		}
		return owned;
	}

	/** The parts of {@code run} not covered by any exempt range. */
	private List<int[]> outside(int[] run, List<int[]> exempt) {
		if (exempt == null || exempt.isEmpty()) {
			return List.of(run);
		}
		List<int[]> parts = new ArrayList<>();
		int cursor = run[0];
		int end = run[0] + run[1];
		List<int[]> sorted = new ArrayList<>(exempt);
		sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int[] range : sorted) {
			if (range[1] <= cursor || range[0] >= end) {
				continue;
			}
			if (range[0] > cursor) {
				parts.add(new int[] { cursor, range[0] - cursor });
			}
			cursor = Math.max(cursor, range[1]);
			if (cursor >= end) {
				return parts;
			}
		}
		parts.add(new int[] { cursor, end - cursor });
		return parts;
	}

	private String describe(long pageKey, int offset, int length) {
		Kind kind = PageOverlay.kindOf(pageKey);
		String where = kind + " segment " + PageOverlay.segmentOf(pageKey) + " page " + PageOverlay.pageOf(pageKey);
		return offset < 0 ? where : where + " bytes " + offset + ".." + (offset + length - 1);
	}

	// ------------------------------------------------ MetaJournal.Target: the commit's fold of the metadata journal

	/**
	 * The segment a journal entry names, or null when it has been retired since.
	 *
	 * <p>
	 * Only empty trailing segments are retired, and only under the exclusive gate, so a null here means the entry has
	 * nothing left to place rather than that something has gone wrong.
	 * </p>
	 */
	private SegmentFile journalSegment(long segmentKey) {
		SegmentSet set = segmentSets.get(Kind.byCode(MetaJournal.kindCodeOf(segmentKey)));
		if (set == null) {
			return null;
		}
		int number = MetaJournal.segmentNumberOf(segmentKey);
		return number < set.segmentCount() ? set.segment(number) : null;
	}

	@Override
	public boolean has(long segmentKey) {
		return journalSegment(segmentKey) != null;
	}

	@Override
	public void allocate(long segmentKey, int slot) {
		journalSegment(segmentKey).freeSlots().allocate(slot);
	}

	@Override
	public void free(long segmentKey, int slot) {
		journalSegment(segmentKey).freeSlots().free(slot);
	}

	@Override
	public void pageLiveCountDelta(long segmentKey, int page, int delta) {
		journalSegment(segmentKey).pageMeta().liveCountDelta(page, delta);
	}

	@Override
	public void pageEpochBump(long segmentKey, int page, long times) {
		journalSegment(segmentKey).pageMeta().bumpEpoch(page, times);
	}

	@Override
	public void pageGenFloor(long segmentKey, int page, int value) {
		journalSegment(segmentKey).pageMeta().raiseGenFloor(page, value);
	}

	@Override
	public void pageFreeHint(long segmentKey, int page, int value) {
		journalSegment(segmentKey).pageMeta().freeHint(page, value);
	}

	@Override
	public void pageChecksum(long segmentKey, int page, int value) {
		journalSegment(segmentKey).pageMeta().checksum(page, value);
	}

	@Override
	public void pageFlags(long segmentKey, int page, int value) {
		journalSegment(segmentKey).pageMeta().flags(page, value);
	}

	@Override
	public void pageLsn(long segmentKey, int page, long value) {
		journalSegment(segmentKey).pageMeta().lsn(page, value);
	}

	@Override
	public void header(long segmentKey, MetaJournal.SegmentEntry entry) {
		Superblock superblock = journalSegment(segmentKey).superblock();
		if (entry.liveDelta() != 0) {
			superblock.liveCountDelta(entry.liveDelta());
		}
		if (entry.freeAbsolute() != Long.MIN_VALUE) {
			superblock.freeCount(entry.freeAbsolute());
		} else if (entry.freeDelta() != 0) {
			superblock.freeCountDelta(entry.freeDelta());
		}
		if (entry.sealedTo() != null) {
			superblock.sealed(entry.sealedTo());
		}
		if (entry.lastLsn() >= 0) {
			superblock.lastLsn(entry.lastLsn());
		}
		// The header itself is rewritten by the caller's flush pass, once, after every entry has been folded.
		superblock.markCounterDirty();
	}

	/**
	 * Keeps what this commit is about to overwrite, on behalf of readers that began before it.
	 *
	 * <p>
	 * Called with the apply lock held and <em>before</em> {@link WalManager#apply}, which is the only ordering that
	 * works: the before-image is by definition the mapping as it stands now, and a reader must never be able to observe
	 * the new mapping without the chain entry that undoes it.
	 * </p>
	 *
	 * <p>
	 * The guard is the whole performance story. With no snapshot open there is nothing to retain, so a store that never
	 * opens one never allocates a byte here and commit is exactly as fast as it was before MVCC existed. A write
	 * transaction registers a view of its own, so the guard has to ignore the committer's — otherwise the single-writer
	 * store, which is the one that used to pay nothing, would start retaining on its own behalf.
	 * </p>
	 */
	private void retainVersions(WalManager.CommitBatch batch, WriteContext context) {
		if (!transactions.needsVersionsBesides(context.snapshot().id())) {
			return;
		}
		PageVersions versions = transactions.versions();
		for (WalManager.PageWrite write : batch.writes()) {
			versions.record(PageOverlay.key(write.kind(), write.segmentNumber(), write.pageIndex()),
				batch.commitLsn(), write.mappedPage(), write.offset(), write.length());
		}
	}

	@Override
	public ReadTransaction beginRead() {
		return beginRead(IsolationLevel.SNAPSHOT);
	}

	@Override
	public ReadTransaction beginRead(IsolationLevel level) {
		checkOpen();
		// The read lock is what makes the pair (LSN, registration) atomic against a commit. Without it a commit could
		// land between reading the LSN and joining the active set, and the view would be fixed at an LSN whose
		// before-images nobody was asked to keep.
		ApplyHold hold = enterApplyShared(false);
		try {
			return transactions.begin(level, appliedLsn, System.nanoTime());
		} finally {
			exitApplyShared(hold);
		}
	}

	@Override
	public boolean hasReadTransaction() {
		Snapshot snapshot = transactions.holder().current();
		return snapshot != null && snapshot.isOpen();
	}

	@Override
	public boolean hasWriteTransaction() {
		// Deliberately the same test {@link #write} uses to decide whether to join or to open one, so a caller asking
		// the question and a mutation answering it for itself can never disagree.
		return currentTransaction.get() != null;
	}

	@Override
	public SnapshotStats snapshotStats() {
		checkOpen();
		PageVersions versions = transactions.versions();
		return new SnapshotStats(transactions.activeCount(), transactions.oldestActiveLsn(appliedLsn),
			transactions.oldestActiveAgeNanos(System.nanoTime()) / 1_000_000L, versions.retainedBytes(),
			versions.entryCount(), versions.pageCount(), transactions.abandonedCount());
	}

	@Override
	public <T> T inTransaction(TransactionalWork<T> work, int attempts) throws IOException {
		checkOpen();
		if (attempts < 1) {
			throw new IllegalArgumentException("attempts must be at least 1");
		}
		return inTransaction(work, attempts, IsolationLevel.SNAPSHOT);
	}

	@Override
	public <T> T inTransaction(TransactionalWork<T> work, int attempts, IsolationLevel level) throws IOException {
		checkOpen();
		if (attempts < 1) {
			throw new IllegalArgumentException("attempts must be at least 1");
		}
		GraphStoreException last = null;
		for (int attempt = 1; attempt <= attempts; attempt++) {
			try (Transaction txn = begin(level)) {
				T result = work.run();
				txn.commit();
				return result;
			} catch (SnapshotTooOldException | ConstraintViolationException e) {
				// Retrying either of these repeats the same failure: one is about a reader that no longer exists, the
				// other about data that has not changed. Neither is contention.
				throw e;
			} catch (GraphStoreException e) {
				last = e;
				logger.debug("Transaction attempt {} of {} failed, retrying", attempt, attempts, e);
			}
		}
		throw last;
	}

	@Override
	public void rollback(TransactionImpl txn) {
		endTransaction(txn, false);
	}

	/**
	 * Ends a transaction, one way or the other, and gives back everything it was holding.
	 *
	 * <p>
	 * A rollback is now entirely local: discard the overlay, discard the journal, hand the reserved slots back. It used
	 * to also reload every superblock from the mapping and empty the allocator's free rings, which was right when a
	 * rollback could only be undoing the one transaction there was — and is destructive now, because those are shared
	 * and hold other writers' work.
	 * </p>
	 *
	 * @return the commit LSN when it committed, otherwise the store's current applied LSN
	 */
	private long endTransaction(TransactionImpl txn, boolean committed) {
		WriteContext context = txn.context();
		try {
			// "Alone" means no read view is open and this is the only writer, which is what lets a rollback put the
			// high water marks back — see SegmentSet#rollbackState. Asked before the view is closed, so the writer
			// counted here is this one.
			boolean alone = !committed && transactions.activeCount() == 0 && transactions.activeWriterCount() == 1;
			for (SegmentSet set : segmentSets.values()) {
				if (committed) {
					set.commitState(context);
				} else {
					set.rollbackState(context, alone);
				}
			}
			context.overlay().clear();
			context.journal().clear();
		} finally {
			contexts.clear();
			currentTransaction.remove();
			// Closing the view releases the horizon this transaction was pinning, so it must happen before the vacuum
			// that follows a commit.
			context.snapshot().close();
			writeGate.readLock().unlock();
		}
		return appliedLsn;
	}

	/** Runs the operation inside the caller's transaction if there is one, otherwise in an auto-commit transaction. */
	private <T> T write(WriteOp<T> op) throws IOException {
		checkOpen();
		TransactionImpl open = currentTransaction.get();
		if (open != null) {
			// Inside the caller's transaction a failed statement has no boundary of its own to roll back to. One that
			// failed before changing anything — a refusal checked up front — leaves the transaction exactly as it was
			// and usable. One that failed partway has left part of itself in the overlay, and the only safe thing left
			// is to make sure that part is never committed.
			return joined(open, () -> underApply(open, op));
		}
		// An auto-commit mutation retries its own conflicts rather than raising them.
		//
		// This is the difference between an explicit transaction and a single statement, and it matters more than it
		// looks. A caller who opened a transaction has a unit of work to reconsider, so a conflict is news; a caller
		// who called setNodeProperties has nothing to reconsider, and re-running the one statement against a newer
		// state is exactly what they meant. Raising it would make every mutation on a store with two writers a checked
		// failure — and the commonest conflict is not even between the callers' data, it is two inserts landing in one
		// B+tree leaf, which no caller can restructure their way out of.
		//
		// Retrying optimistically is the fast path and almost always the only one taken. It is not, on its own, a
		// guarantee of progress, and the reasoning that said it was is worth recording because it was wrong: "a
		// conflict has a winner, so each attempt runs with one fewer competitor" describes a queue that drains, and
		// writers in a loop are not that. The winner commits and immediately comes back with its next write, so the
		// number of competitors is constant and a loser's chance of winning never improves.
		//
		// That matters because the window a conflict is decided in is the one between this transaction's snapshot and
		// its turn at the commit lock, and every writer contending for one hot B+tree leaf invalidates every snapshot
		// older than its commit. Six writers on one index leaf starve one of their number past sixty-four attempts
		// often enough to measure, and past two hundred and fifty-six as well: it is not a bound that was set too low,
		// it is an unbounded wait wearing a bound. Making the commit lock fair makes it strictly worse, because FIFO
		// order guarantees that everybody ahead of the loser commits before it does.
		return asStatement(txn -> underApply(txn, op));
	}

	@Override
	public <T> T inStatement(TransactionalWork<T> work) throws IOException {
		checkOpen();
		if (currentTransaction.get() != null) {
			// Already inside somebody's unit of work, so this is not a statement and its conflicts are not the
			// engine's to absorb: absorbing them here would re-run the caller's work inside their transaction and
			// commit it out from under them.
			throw new IllegalStateException("inStatement() cannot run inside an open transaction: the conflict it "
				+ "absorbs belongs to whoever opened it. Call the work directly, or commit first.");
		}
		return asStatement(txn -> work.run());
	}

	@Override
	public <T> T inOpenTransaction(TransactionalWork<T> work) throws IOException {
		checkOpen();
		TransactionImpl open = currentTransaction.get();
		if (open == null) {
			throw new IllegalStateException("inOpenTransaction() needs a write transaction open on this thread: it "
				+ "joins one, and has none to join. Begin one, or use inStatement() for work that is its own statement.");
		}
		// No apply hold of its own: the work is made of public calls, and each of them takes one.
		return joined(open, work::run);
	}

	/**
	 * Runs one statement inside the caller's transaction, making the transaction rollback-only if the statement fails
	 * after it has started changing it.
	 */
	private static <T> T joined(TransactionImpl open, WriteOp<T> op) throws IOException {
		WriteContext context = open.context();
		long before = context.mutations();
		try {
			return op.run();
		} catch (RuntimeException | IOException | Error e) {
			if (context.mutations() != before) {
				context.rollbackOnly(e);
			}
			throw e;
		}
	}

	/**
	 * One statement, retried optimistically and then run with every other writer excluded.
	 *
	 * <p>
	 * Both callers are the same shape and were the same defect: {@link #write} for a single mutation and
	 * {@link #inStatement} for a Cypher write query, each of which used to end in a raised conflict that its caller
	 * had no way to act on.
	 * </p>
	 */
	private <T> T asStatement(StatementWork<T> work) throws IOException {
		TransactionConflictException conflict = null;
		for (int attempt = 0; attempt < AUTO_COMMIT_ATTEMPTS; attempt++) {
			try (Transaction txn = begin()) {
				T result = work.run((TransactionImpl) txn);
				txn.commit();
				return result;
			} catch (TransactionConflictException e) {
				conflict = e;
				autoCommitRetries.increment();
				backOff(attempt);
			}
		}
		return runExclusively(work, conflict);
	}

	/** One statement's body, given the transaction opened for it. */
	@FunctionalInterface
	private interface StatementWork<T> {
		T run(TransactionImpl txn) throws IOException;
	}

	/**
	 * The last resort of a statement: run it with no other writer in flight.
	 *
	 * <p>
	 * Holding the write gate exclusively is what turns "retry and hope" into a guarantee. Nothing else can commit
	 * between this transaction's snapshot and its validation, so there is no window for a conflict to be decided in
	 * and the attempt cannot lose one. The gate is then taken shared again by {@code begin()} inside it, which is the
	 * downgrade {@link #writeGate} is a {@link ReentrantReadWriteLock} for and the same sequence compaction runs; the
	 * commit lock is acquired underneath it in the usual order, so this adds no new lock ordering.
	 * </p>
	 *
	 * <p>
	 * It is deliberately the exception and not the rule. Excluding every other writer for the duration of one
	 * statement is the throughput of a single-writer engine, which is precisely what the optimistic attempts above
	 * exist to avoid paying — but a caller who wrote one statement has nothing to reconsider and no seam to retry at,
	 * so the alternative is not a slower write, it is a failed one.
	 * </p>
	 */
	private <T> T runExclusively(StatementWork<T> work, TransactionConflictException conflict) throws IOException {
		logger.debug("Statement lost {} conflicts, taking the write gate exclusively", AUTO_COMMIT_ATTEMPTS, conflict);
		autoCommitEscalations.increment();
		writeGate.writeLock().lock();
		try (Transaction txn = begin()) {
			T result = work.run((TransactionImpl) txn);
			txn.commit();
			return result;
		} finally {
			writeGate.writeLock().unlock();
		}
	}

	/**
	 * Waits a little longer after each lost conflict.
	 *
	 * <p>
	 * Yielding was not enough, and the reason is worth keeping. A conflict against a transaction that is still running
	 * — the loser found the resource reserved rather than overwritten — is resolved by that transaction finishing, and
	 * a durable commit takes milliseconds where a failed attempt takes microseconds. Four writers racing to create the
	 * one index catalog page burned all sixty-four of their attempts inside the winner's first commit and then raised a
	 * conflict on a store where the only thing wrong was that somebody else was ahead of them.
	 * </p>
	 */
	private void backOff(int attempt) {
		if (attempt < 4) {
			Thread.yield();
			return;
		}
		long ceiling = Math.min(5_000_000L, 50_000L << Math.min(attempt - 4, 7));
		// Jittered, because the losers of one round are otherwise scheduled to collide again. Everything contending
		// for a hot index leaf loses at the same instant — the winner's commit invalidates every snapshot taken before
		// it at once — so an unjittered delay hands them all the same wake-up time and reproduces the pile-up that
		// caused the backoff a fixed number of times in a row.
		long nanos = ceiling / 2 + java.util.concurrent.ThreadLocalRandom.current().nextLong(ceiling / 2 + 1);
		java.util.concurrent.locks.LockSupport.parkNanos(nanos);
	}

	/**
	 * Runs a read, optimistically first.
	 * <p>
	 * The commit apply step is the only thing a reader has to be excluded from, and it is rare and short. So the read
	 * runs without any lock at all and then asks whether an apply happened while it was running; if one did, the
	 * result is discarded and the read is repeated under a real lock. An exception from the optimistic attempt is
	 * treated the same way, because a read of a page in the middle of being applied can produce nonsense — and every
	 * parser was hardened to fail rather than hang or over-allocate on nonsense, which is what makes this safe.
	 * <p>
	 * The operation must be free of side effects, which is what {@code read} means; it can and does run twice.
	 */
	/**
	 * The calling thread's write transaction, or null. The context holder is published and cleared together with
	 * {@link #currentTransaction}, so when it says no thread is writing the thread-local need not be consulted: that is
	 * one lookup saved on every read made while no transaction is open anywhere.
	 */
	private TransactionImpl openTransaction() {
		return contexts.anyPublished() ? currentTransaction.get() : null;
	}

	private <T> T read(Supplier<T> op) {
		checkOpen();
		TransactionImpl open = openTransaction();
		if (open != null) {
			// Inside a write transaction the overlay and the transaction's own view give the consistent answer, and the
			// read must not be routed through a read view the same thread happens to also have open. Checked first, so
			// a thread holding both is not made to answer for the reader's liveness while it is writing. The latch is
			// taken for the same reason a mutation takes it — see underApply.
			Snapshot writerView = open.snapshot();
			ApplyHold hold = enterApplyShared(false);
			writerView.exclusionHeld(true);
			try {
				return op.get();
			} finally {
				writerView.exclusionHeld(false);
				exitApplyShared(hold);
			}
		}
		Snapshot snapshot = transactions.holder().current();
		if (snapshot != null) {
			// Once per operation rather than once per page: a view the horizon has passed is wrong everywhere, not
			// only on the pages it has yet to touch.
			snapshot.checkAlive();
		}
		long stamp = applyLock.tryOptimisticRead();
		if (stamp != 0) {
			try {
				T result = op.get();
				if (applyLock.validate(stamp)) {
					return stillAlive(snapshot, result);
				}
			} catch (Snapshot.NeedsExclusionException e) {
				// A page has to be rebuilt for this view, and rebuilding reads the mapping and the version chains
				// together. Fall through to the locked attempt rather than take a lock on every read for the
				// possibility.
			} catch (RuntimeException e) {
				if (applyLock.validate(stamp)) {
					// Nothing was applied underneath, so the failure is real rather than a torn read.
					throw e;
				}
			}
		}
		ApplyHold hold = enterApplyShared(false);
		try {
			if (snapshot != null) {
				snapshot.exclusionHeld(true);
			}
			return stillAlive(snapshot, op.get());
		} finally {
			if (snapshot != null) {
				snapshot.exclusionHeld(false);
			}
			exitApplyShared(hold);
		}
	}

	/**
	 * The result, provided the view it was read through is still alive. Checking once before the operation is not
	 * enough: the retention budget can abandon a view mid-operation, and vacuum then deletes chains it needed without
	 * taking the apply latch, so the optimistic validation does not notice and the pages read after that came from the
	 * mapping — newer than the view. Vacuum raises the horizon before it removes anything, and the horizon only rises,
	 * so a check afterwards catches every such read.
	 */
	private static <T> T stillAlive(Snapshot snapshot, T result) {
		if (snapshot != null) {
			snapshot.checkAlive();
		}
		return result;
	}

	@FunctionalInterface
	private interface WriteOp<T> {
		T run() throws IOException;
	}

	/**
	 * Runs one operation of a write transaction with the commit apply step excluded.
	 *
	 * <p>
	 * <b>The writer lock used to do this by accident, and it was load-bearing.</b> One writer at a time meant no commit
	 * could be applying while another transaction was reading, so a writer could read the mapping freely. Concurrent
	 * writers removed that, and the hole it left is narrow and silent: a writer checks the version chain for a page,
	 * finds nothing newer than its snapshot, and reads the mapping — and in between, a commit publishes its
	 * before-image and copies its bytes in. The writer then sees the new bytes with no chain entry telling it to undo
	 * them. It surfaced as a reader being told the generation of a property block it had just resolved was stale.
	 * </p>
	 *
	 * <p>
	 * Ordinary reads solve this optimistically — run without a lock, ask afterwards whether an apply happened, repeat
	 * if it did — which a mutation cannot do, because repeating it is not free of side effects. So a mutation takes the
	 * latch for its own short duration instead. It is held per operation, never for the transaction: a commit waits for
	 * one statement, not for a unit of work.
	 * </p>
	 */
	private <T> T underApply(TransactionImpl txn, WriteOp<T> op) throws IOException {
		Snapshot snapshot = txn.snapshot();
		ApplyHold hold = enterApplyShared(false);
		// Tells the snapshot the apply step is excluded, so a page it has to rebuild is rebuilt here rather than
		// through the guard, which would take the same non-reentrant latch a second time.
		snapshot.exclusionHeld(true);
		try {
			return op.run();
		} finally {
			snapshot.exclusionHeld(false);
			exitApplyShared(hold);
		}
	}

	@Override
	public void runWrite(MaintenanceRunner.Unit unit) throws IOException {
		write(() -> {
			unit.run();
			return null;
		});
	}

	@Override
	public void runExclusive(MaintenanceRunner.Unit unit) throws IOException {
		lockGateExclusively("A maintenance unit");
		try {
			// Every unit, not once per run: beginRead() does not take the gate, so a view can open between two batches,
			// and the next one would relocate, free or unmap something that view is reading.
			refuseWhileViewsAreOpen("compact()");
			write(() -> {
				unit.run();
				return null;
			});
		} finally {
			writeGate.writeLock().unlock();
		}
	}

	@Override
	public void runUnlogged(MaintenanceRunner.Unit unit) throws IOException {
		lockGateExclusively("A maintenance unit");
		try {
			// Under the gate, and per unit rather than once per compaction: beginRead() does not take the gate, so a
			// view can open at any point of a long run.
			refuseWhileViewsAreOpen("compact()");
			// An unlogged unit writes the data files outside the log, so the replay origin has to be past every
			// record first. checkpointBeforeUnloggedWrites() explains why; reclaim() follows the same rule.
			checkpoint();
			uninterruptible(() -> {
				unit.run();
				return null;
			});
		} finally {
			writeGate.writeLock().unlock();
		}
	}

	@Override
	public void runCheckpoint() throws IOException {
		checkpoint();
	}

	@Override
	public void runRead(MaintenanceRunner.Unit unit) throws IOException {
		// A maintenance scan is long and rare, so it takes the lock outright rather than gambling on optimism.
		ReadLease lease = new ReadLease();
		try {
			unit.run();
		} finally {
			lease.close();
		}
	}

	/**
	 * A read view held outright, for as long as the holder needs it.
	 * <p>
	 * {@link #read} is the right shape for an operation that fits in one call: it runs without a lock and repeats
	 * itself if a commit landed underneath. Some reads cannot be repeated — a scan whose consumer has side effects, a
	 * cursor that outlives the call that made it — and those take the latch instead. This is that acquisition, in a
	 * form that can be handed to something with its own lifetime.
	 * <p>
	 * Inside the caller's own write transaction it acquires nothing: the overlay is already a consistent view, and the
	 * writer taking a read latch against itself is at best pointless.
	 */
	private final class ReadLease implements AutoCloseable {

		private final Snapshot snapshot;
		/** The opening thread's hold, kept so the lease releases what it took even if closed from elsewhere. */
		private final ApplyHold hold;
		private boolean held;

		ReadLease() {
			checkOpen();
			TransactionImpl open = openTransaction();
			if (open != null) {
				// The writer's own view, and the latch with it: a scan inside a write transaction is exposed to a
				// concurrent commit's apply exactly as a single read is.
				this.snapshot = open.snapshot();
				this.hold = enterApplyShared(true);
				this.held = true;
				heldLeases.get()[0]++;
				this.snapshot.exclusionHeld(true);
				return;
			}
			Snapshot current = transactions.holder().current();
			if (current != null) {
				current.checkAlive();
			}
			this.snapshot = current;
			this.hold = enterApplyShared(true);
			this.held = true;
			heldLeases.get()[0]++;
			if (snapshot != null) {
				snapshot.exclusionHeld(true);
			}
		}

		@Override
		public void close() {
			if (!held) {
				return;
			}
			// Cleared first: a cursor may be closed twice, and unlocking a stamp twice corrupts the lock rather than
			// throwing.
			held = false;
			heldLeases.get()[0]--;
			if (snapshot != null) {
				snapshot.exclusionHeld(false);
			}
			exitApplyShared(hold);
		}
	}

	// ---------------------------------------------------------------- nodes

	@Override
	public long createNode(String label) throws IOException {
		return createNode(label, null);
	}

	@Override
	public long createNode(String label, Properties props) throws IOException {
		return createNode(label == null ? List.of() : List.of(label), props);
	}

	@Override
	public long createNode(List<String> labels) throws IOException {
		return createNode(labels, null);
	}

	@Override
	public long createNode(List<String> labels, Properties props) throws IOException {
		return write(() -> {
			int[] labelIds = internLabels(labels);
			Map<Integer, Object> keyed = props == null ? null : toKeyIds(props);
			checkUnique(labelIds, keyed, ElementId.NULL);
			long nodeId = nodes.create(labelIds, ElementId.NULL);
			if (props != null && !props.isEmpty()) {
				nodes.propBlockId(nodeId, properties.store(keyed, nodeId));
			}
			indexes.onNodeCreated(nodeId, labelIds, keyed);
			maintainVectorMembership(nodeId);
			return nodeId;
		});
	}

	/**
	 * Interns a label list, preserving order and dropping duplicates.
	 * <p>
	 * Duplicates are dropped rather than refused because {@code :Person:Person} is a caller's redundancy, not a
	 * corruption — but storing it twice would put two index entries on one node and make a delete leave one behind.
	 */
	private int[] internLabels(List<String> labels) throws IOException {
		if (labels == null || labels.isEmpty()) {
			return new int[0];
		}
		int[] ids = new int[labels.size()];
		int count = 0;
		for (String label : labels) {
			if (label == null) {
				throw new IllegalArgumentException("A null label at position " + count + " of " + labels);
			}
			int id = catalog.internLabel(label);
			boolean seen = false;
			for (int i = 0; i < count; i++) {
				seen |= ids[i] == id;
			}
			if (!seen) {
				ids[count++] = id;
			}
		}
		return count == ids.length ? ids : java.util.Arrays.copyOf(ids, count);
	}

	@Override
	public List<String> nodeLabels(long nodeId) {
		return read(() -> {
			int[] ids = nodes.labels(nodeId);
			List<String> names = new ArrayList<>(ids.length);
			for (int id : ids) {
				names.add(catalog.label(id));
			}
			return names;
		});
	}

	@Override
	public boolean nodeHasLabel(long nodeId, String label) {
		return read(() -> {
			int labelId = catalog.lookupLabel(label);
			return labelId != 0 && nodes.hasLabel(nodeId, labelId);
		});
	}

	@Override
	public boolean addNodeLabel(long nodeId, String label) throws IOException {
		if (label == null) {
			throw new IllegalArgumentException("A null label for node " + ElementId.toString(nodeId));
		}
		return write(() -> {
			// Read first, so a stale or freed id is refused before a dictionary id is handed out. Interning is not
			// transactional — the dictionary is appended and flushed immediately — so a label interned for a write that
			// then fails stays in the dictionary for the life of the store.
			int[] before = nodes.labels(nodeId);
			int labelId = catalog.internLabel(label);
			if (holdsLabel(before, labelId)) {
				return false;
			}
			int[] after = java.util.Arrays.copyOf(before, before.length + 1);
			after[before.length] = labelId;
			// A label-scoped unique constraint can be broken by the label rather than by the value: two nodes may
			// legitimately share an email until the second one is told it is a :Person. Checked before the write, so a
			// refusal leaves nothing behind — the same ordering createNode has and setNodeProperties does not.
			if (indexes.hasUniqueComposite(labelId)) {
				checkUnique(after, properties.read(nodes.propBlockId(nodeId)), nodeId);
			}
			nodes.labels(nodeId, after);
			indexes.onLabelsChanged(nodeId, before, after, () -> properties.read(nodes.propBlockId(nodeId)));
			maintainVectorMembership(nodeId);
			return true;
		});
	}

	@Override
	public boolean removeNodeLabel(long nodeId, String label) throws IOException {
		if (label == null) {
			throw new IllegalArgumentException("A null label for node " + ElementId.toString(nodeId));
		}
		return write(() -> {
			int[] before = nodes.labels(nodeId);
			// lookupLabel rather than internLabel: removing a label the store has never seen must not mint a dictionary
			// id for it. Ids are never reclaimed, so that would be a permanent entry describing something that never
			// existed.
			int labelId = catalog.lookupLabel(label);
			if (labelId == 0 || !holdsLabel(before, labelId)) {
				return false;
			}
			int[] after = new int[before.length - 1];
			int at = 0;
			for (int held : before) {
				if (held != labelId) {
					after[at++] = held;
				}
			}
			nodes.labels(nodeId, after);
			indexes.onLabelsChanged(nodeId, before, after, () -> properties.read(nodes.propBlockId(nodeId)));
			maintainVectorMembership(nodeId);
			return true;
		});
	}

	private static boolean holdsLabel(int[] labelIds, int labelId) {
		for (int held : labelIds) {
			if (held == labelId) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Rejects a write that would put two live elements on the same value of a unique key.
	 * <p>
	 * The index answers this in one probe, and the candidates it returns are verified against the stored property
	 * before anything is refused — the index is keyed on a value hash, so an unverified conflict could be a collision
	 * between two different values. Refusing a legitimate write is as bad as accepting an illegitimate one.
	 */
	private void checkUnique(int[] labelIds, Map<Integer, Object> properties, long excluding) {
		if (properties == null || properties.isEmpty()) {
			return;
		}
		// The check writes — it claims the index leaves its values would land in, so that two writers racing for one
		// value conflict — and those claims are safe to commit. They must not count as the statement having changed
		// the transaction, or every caught violation would leave it rollback-only (see write()).
		TransactionImpl open = currentTransaction.get();
		long mark = open == null ? 0 : open.context().mutations();
		try {
			checkUniqueClaimed(labelIds, properties, excluding);
		} finally {
			if (open != null) {
				open.context().forgive(mark);
			}
		}
	}

	private void checkUniqueClaimed(int[] labelIds, Map<Integer, Object> properties, long excluding) {
		for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
			if (entry.getValue() == null || !indexes.isUnique(entry.getKey())) {
				continue;
			}
			for (long other : indexes.conflicts(entry.getKey(), entry.getValue(), excluding)) {
				if (!nodes.exists(other)) {
					continue;
				}
				Object stored = this.properties.read(nodes.propBlockId(other), entry.getKey());
				if (IndexCatalog.valueEquals(stored, normalise(entry.getValue()))) {
					throw new ConstraintViolationException(catalog.key(entry.getKey()), entry.getValue(), other);
				}
			}
		}
		// The label-scoped half, over the declared composite indexes. The probe is the composite tree, so the
		// candidates are already narrowed to nodes carrying the label — but the label is still verified per candidate,
		// because the key is a hash of the triple and a collision would otherwise refuse a legitimate write.
		for (IndexCatalog.Composite composite : indexes.composites()) {
			if (!composite.unique() || !holdsLabel(labelIds, composite.labelId())) {
				continue;
			}
			Object value = properties.get(composite.keyId());
			if (value == null) {
				continue;
			}
			for (long other : indexes.compositeConflicts(composite.labelId(), composite.keyId(), value, excluding)) {
				if (!nodes.exists(other) || !nodes.hasLabel(other, composite.labelId())) {
					continue;
				}
				Object stored = this.properties.read(nodes.propBlockId(other), composite.keyId());
				if (IndexCatalog.valueEquals(stored, normalise(value))) {
					throw new ConstraintViolationException(catalog.label(composite.labelId()),
						catalog.key(composite.keyId()), value, other);
				}
			}
		}
	}

	/**
	 * Runs a declaration — an index or constraint created or dropped — with every other writer excluded.
	 *
	 * <p>
	 * A declaration and an ordinary write meet nowhere on disk. The declaring scan reads its own snapshot, which cannot
	 * hold a concurrent writer's uncommitted node; that writer's maintenance hook reads the declaration lists as of
	 * <em>its</em> snapshot, which cannot hold the declaration. The two write disjoint bytes, so conflict detection has
	 * nothing to find and both commit: an index that permanently misses a node, or a unique constraint declared over a
	 * duplicate. Holding the write gate exclusively waits out every open write transaction before the scan and keeps new
	 * ones out until the declaration has committed, so each writer is wholly before it or wholly after. Declarations
	 * are rare, and a vector index's build — the long part — runs after the gate is released.
	 * </p>
	 *
	 * <p>
	 * <b>Inside the caller's own transaction it cannot do that</b>: the caller already holds the gate shared, and a
	 * read-write lock does not upgrade. It joins that transaction, as every other mutation does, and is isolated only
	 * as well as the transaction is — which against concurrent writers is not at all. Declare outside a transaction
	 * when other writers are running.
	 * </p>
	 */
	private <T> T declare(WriteOp<T> op) throws IOException {
		checkOpen();
		if (currentTransaction.get() != null) {
			return write(op);
		}
		writeGate.writeLock().lock();
		try {
			return write(op);
		} finally {
			writeGate.writeLock().unlock();
		}
	}

	/**
	 * Declares a property key unique across live nodes.
	 * <p>
	 * Checks the existing data first and refuses rather than declaring something that is already false.
	 */
	@Override
	public void createUniqueConstraint(String key) throws IOException {
		declare(() -> {
			int keyId = catalog.internKey(key);
			// Keyed the way the index compares, or a vector — a fresh array on every read — never meets its twin.
			Map<IndexCatalog.ValueKey, Long> seen = new java.util.HashMap<>();
			for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
				Object value = properties.read(nodes.propBlockId(nodeId), keyId);
				if (value == null) {
					continue;
				}
				Long previous = seen.put(new IndexCatalog.ValueKey(value), nodeId);
				if (previous != null) {
					throw new ConstraintViolationException(key, value, previous);
				}
			}
			indexes.addUniqueConstraint(keyId);
			return null;
		});
	}

	@Override
	public List<String> uniqueConstraints() {
		return read(() -> {
			int[] keys = indexes.uniqueKeys();
			List<String> names = new ArrayList<>(keys.length);
			for (int keyId : keys) {
				names.add(catalog.key(keyId));
			}
			return names;
		});
	}

	// ---------------------------------------------------------------- declared composite indexes

	@Override
	public void createIndex(String label, String key) throws IOException {
		declare(() -> {
			int labelId = catalog.internLabel(label);
			int keyId = catalog.internKey(key);
			if (indexes.addCompositeIndex(labelId, keyId, false)) {
				backfillComposite(labelId, keyId);
			}
			return null;
		});
	}

	/**
	 * Declares a label-scoped unique constraint, which is a composite index plus a flag.
	 * <p>
	 * The existing data is scanned first and the declaration refused if it is already false, exactly as the store-wide
	 * form does — but only over the nodes carrying the label, which is the whole difference between the two.
	 */
	@Override
	public void createUniqueConstraint(String label, String key) throws IOException {
		declare(() -> {
			int labelId = catalog.internLabel(label);
			int keyId = catalog.internKey(key);
			// Keyed the way the index compares, or a vector — a fresh array on every read — never meets its twin.
			Map<IndexCatalog.ValueKey, Long> seen = new java.util.HashMap<>();
			for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
				if (!nodes.hasLabel(nodeId, labelId)) {
					continue;
				}
				Object value = properties.read(nodes.propBlockId(nodeId), keyId);
				if (value == null) {
					continue;
				}
				Long previous = seen.put(new IndexCatalog.ValueKey(value), nodeId);
				if (previous != null) {
					throw new ConstraintViolationException(label, key, value, previous);
				}
			}
			if (indexes.addCompositeIndex(labelId, keyId, true)) {
				backfillComposite(labelId, keyId);
			}
			return null;
		});
	}

	/**
	 * Indexes every live node that already carries the label and the key.
	 * <p>
	 * A scan, like {@code createUniqueConstraint}'s: a declaration that only covered writes made after it would be an
	 * index that silently answers with a subset, which is worse than no index at all. It runs in the declaring
	 * transaction, so a rollback takes the declaration and its entries together.
	 */
	private void backfillComposite(int labelId, int keyId) throws IOException {
		for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
			if (!nodes.hasLabel(nodeId, labelId)) {
				continue;
			}
			Object value = properties.read(nodes.propBlockId(nodeId), keyId);
			if (value != null) {
				indexes.addCompositeEntry(nodeId, labelId, keyId, value);
			}
		}
	}

	/**
	 * Drops a declaration and every entry it owns.
	 * <p>
	 * The entries go first, by the same scan the backfill runs — which is the honest cost of the operation and the
	 * reason it is a call rather than a flag. Dropping the <b>last</b> declaration is cheaper than that: the tree has
	 * no other pair left in it, so it is freed whole.
	 * <p>
	 * <b>A label-scoped unique constraint is dropped with its index</b>, because in this engine they are one
	 * declaration and not two ({@link #createUniqueConstraint(String, String)}). That is deliberate rather than
	 * incidental: the alternative is a constraint that cannot be removed at all, which is worse than one that says
	 * plainly what removing it does.
	 */
	@Override
	public void dropIndex(String label, String key) throws IOException {
		declare(() -> {
			int labelId = catalog.lookupLabel(label);
			int keyId = catalog.lookupKey(key);
			if (labelId == 0 || keyId == 0 || !indexes.hasComposite(labelId, keyId)) {
				throw new IllegalArgumentException("No index on :" + label + "." + key);
			}
			// The entries first, while the declaration is still there to say which pair they belong to. Skipped when
			// this is the last declaration: removeCompositeIndex frees the whole tree, which is the same result for
			// the cost of freeing its pages instead of reading every labelled node.
			if (indexes.composites().size() > 1) {
				for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
					if (!nodes.hasLabel(nodeId, labelId)) {
						continue;
					}
					Object value = properties.read(nodes.propBlockId(nodeId), keyId);
					if (value != null) {
						indexes.removeCompositeEntry(nodeId, labelId, keyId, value);
					}
				}
			}
			indexes.removeCompositeIndex(labelId, keyId);
			return null;
		});
	}

	@Override
	public GraphStoreOptions options() {
		// Rebuilt from the manifest rather than from the options this store was opened with: the manifest is the
		// authority on the three settings that were fixed at creation, and an open may well have passed different ones
		// (the geometry check refuses a mismatch, but a reopen with defaults is normal and must not be reported).
		return GraphStoreOptions.builder()
			.pageSize(geometry.pageSize())
			.segmentCapacity(geometry.segmentCapacity())
			.vectorDimensions(manifest.vectorDimensions())
			.walSegmentBytes(openedWith.walSegmentBytes())
			.walSyncMode(openedWith.walSyncMode())
			.walSyncIntervalMillis(openedWith.walSyncIntervalMillis())
			.retainedVersionBytes(openedWith.retainedVersionBytes())
			.ioEngine(openedWith.ioEngine())
			.build();
	}

	@Override
	public List<CompositeIndex> compositeIndexes() {
		return read(indexes::compositeIndexes);
	}

	@Override
	public boolean hasIndex(String label, String key) {
		return read(() -> {
			int labelId = catalog.lookupLabel(label);
			int keyId = catalog.lookupKey(key);
			return labelId != 0 && keyId != 0 && indexes.hasComposite(labelId, keyId);
		});
	}

	@Override
	public List<Long> nodesByLabelAndProperty(String label, String key, Object value) {
		return read(() -> {
			int labelId = catalog.lookupLabel(label);
			int keyId = catalog.lookupKey(key);
			if (labelId == 0 || keyId == 0 || value == null) {
				return List.<Long> of();
			}
			// One probe when a composite index is declared; otherwise the property probe, filtered by the label. Both
			// return the same answer — the declaration only decides how many candidates are read to produce it.
			List<Long> candidates = indexes.hasComposite(labelId, keyId)
				? indexes.nodesByLabelAndProperty(labelId, keyId, value)
				: indexes.nodesByProperty(key, value);
			List<Long> confirmed = new ArrayList<>(candidates.size());
			for (long nodeId : candidates) {
				if (!nodes.exists(nodeId) || !nodes.hasLabel(nodeId, labelId)) {
					continue;
				}
				Object stored = properties.read(nodes.propBlockId(nodeId), keyId);
				if (IndexCatalog.valueEquals(stored, normalise(value))) {
					confirmed.add(nodeId);
				}
			}
			return confirmed;
		});
	}

	@Override
	public List<Neighbour> nearestByScan(String label, String key, float[] query, int k, VectorMetric metric) {
		if (query == null || query.length == 0) {
			throw new IllegalArgumentException("A vector search needs a query vector");
		}
		if (k <= 0) {
			throw new IllegalArgumentException("k must be positive, got " + k);
		}
		java.util.Objects.requireNonNull(metric, "metric");
		checkOpen();
		int keyId = catalog.lookupKey(key);
		if (keyId == 0) {
			// The key has never been written, so no node can carry it. Not an error: refusing would make a store that
			// simply has no vectors yet fail rather than answer.
			return List.of();
		}
		// A bounded max-heap on the ranking key: the worst kept candidate is at the head, so admitting one is a
		// compare and a swap rather than a sort of every node in the store.
		//
		// The initial capacity is capped rather than taken from k. k is a caller's number, and `new
		// PriorityQueue<>(Integer.MAX_VALUE)` allocates the whole backing array up front for a store that may hold
		// three nodes — the same unbounded-allocation shape the format parsers are hardened against.
		// Ties break on the lower node id rather than on whichever the scan happened to reach first. Equal distances
		// are not a corner case here — a corpus with any repeated vector produces them — and this is the answer an
		// approximate index gets compared against, so it has to be the same answer every run.
		java.util.Comparator<Ranked> nearestFirst =
			java.util.Comparator.comparingDouble(Ranked::rank).thenComparingLong(Ranked::nodeId);
		java.util.PriorityQueue<Ranked> best = new java.util.PriorityQueue<>(Math.min(k, 1024),
			nearestFirst.reversed());
		java.util.function.LongPredicate candidate = nodeId -> {
			Object stored = properties.read(nodes.propBlockId(nodeId), keyId);
			if (stored instanceof float[] vector && vector.length == query.length) {
				// One distance computation per candidate, not two: the rank is a sign flip away from the score, and
				// this is the loop the whole operation is.
				double score = metric.score(query, vector);
				double rank = metric.higherIsNearer() ? -score : score;
				if (best.size() < k) {
					best.add(new Ranked(nodeId, rank, score));
				} else {
					Ranked worst = best.peek();
					if (rank < worst.rank() || (rank == worst.rank() && nodeId < worst.nodeId())) {
						best.poll();
						best.add(new Ranked(nodeId, rank, score));
					}
				}
			}
			return true;
		};
		try {
			// runRead rather than read, for the reason forEachNodeWhile gives: a scan is long, so it takes the latch
			// outright instead of gambling on an optimistic pass it would usually lose — and taking it once means the
			// whole scan sees one state of the store rather than a different one per candidate.
			runRead(() -> {
				if (label == null) {
					segmentSets.get(Kind.NODE).forEachLiveIdWhile(candidate);
				} else {
					for (long nodeId : indexes.nodesByLabel(label)) {
						if (nodes.exists(nodeId)) {
							candidate.test(nodeId);
						}
					}
				}
			});
		} catch (IOException e) {
			throw new GraphStoreException("Vector scan over " + (label == null ? "every node" : ":" + label)
				+ " failed", e);
		}
		List<Ranked> ordered = new ArrayList<>(best);
		ordered.sort(nearestFirst);
		List<Neighbour> result = new ArrayList<>(ordered.size());
		for (Ranked ranked : ordered) {
			result.add(new Neighbour(ranked.nodeId(), ranked.score()));
		}
		return result;
	}

	/** One candidate mid-scan: the ranking key the heap orders on, and the natural score the caller gets back. */
	private record Ranked(long nodeId, double rank, double score) {
	}

	// ---------------------------------------------------------------- declared vector indexes

	@Override
	public void createVectorIndex(VectorIndexDefinition definition) throws IOException {
		if (vectors == null) {
			throw new IllegalStateException("This store has no vector storage. Create it with "
				+ "GraphStoreOptions.builder().vectorDimensions(" + definition.dimensions() + ")");
		}
		if (definition.dimensions() != vectors.dimensions()) {
			throw new IllegalArgumentException("This store holds vectors of " + vectors.dimensions()
				+ " dimensions, so an index over " + definition.dimensions() + " cannot be built on it");
		}
		declare(() -> {
			int labelId = catalog.internLabel(definition.label());
			int keyId = catalog.internKey(definition.key());
			int modelId = definition.model().isEmpty() ? 0 : catalog.internKey("vector.model:" + definition.model());
			int ord = indexes.addVectorIndex(definition, labelId, keyId, modelId);
			// The backfill, in the shape createUniqueConstraint uses: walk what is already there, refuse rather than
			// declare something that is already false, and only then let the hooks take over.
			long members = 0;
			for (long nodeId : indexes.nodesByLabel(definition.label())) {
				if (!nodes.exists(nodeId)) {
					continue;
				}
				members += declareVectorMember(nodeId, definition, ord);
			}
			indexes.vectorBuiltCount(ord, members);
			return null;
		});
		// The links are laid outside the declaring transaction, in batches. Declaring and building are separate
		// because they have utterly different costs: the declaration is a scan, and the build runs a search of the
		// graph so far for every member it adds. Keeping them in one transaction would hold the writer lock for the
		// whole of it — which is the cost spec/VECTOR.md §4.4 warns about — and would make a crash lose all of it.
		buildVectorIndex(definition.label(), definition.key());
	}

	/** A declared vector index's slot on the catalog page and its definition, read together. */
	private record VectorIndexDeclared(int ordinal, VectorIndexDefinition definition) {
	}

	/** The declaration of the index on {@code (label, key)}, or null; read as of the caller's view. */
	private VectorIndexDeclared vectorIndexDeclared(String label, String key) {
		return read(() -> {
			int ord = indexes.vectorIndexOrdinal(label, key);
			return ord < 0 ? null : new VectorIndexDeclared(ord, indexes.vectorIndexes().get(ord));
		});
	}

	@Override
	public void buildVectorIndex(String label, String key) throws IOException {
		checkOpen();
		VectorIndexDeclared declared = vectorIndexDeclared(label, key);
		if (declared == null) {
			throw new IllegalArgumentException("No vector index on :" + label + "." + key);
		}
		int ord = declared.ordinal();
		VectorIndexDefinition definition = declared.definition();
		if (hnsw == null) {
			return;
		}
		// A snapshot of the members. Taken once so the build has a fixed work list, and re-checked per batch because
		// an ordinary write may have deleted one in between.
		long[] members;
		List<Long> pending = new ArrayList<>();
		runRead(() -> {
			for (long vectorId : segmentSets.get(Kind.VEC).liveIds()) {
				if (vectors.indexOrdinal(vectorId) == ord
					&& (vectors.flags(vectorId) & VectorRecord.FLAG_LINKED) == 0) {
					pending.add(vectorId);
				}
			}
		});
		members = pending.stream().mapToLong(Long::longValue).toArray();

		int batch = 0;
		while (batch < members.length) {
			int end = Math.min(batch + VECTOR_BUILD_BATCH, members.length);
			int from = batch;

			// The expensive half, outside the writer lock entirely: one beam search per member, against a read view.
			HnswIndex.Plan[] plans = new HnswIndex.Plan[end - from];
			runRead(() -> {
				for (int i = from; i < end; i++) {
					long vectorId = members[i];
					if (vectors.exists(vectorId) && vectors.indexOrdinal(vectorId) == ord) {
						plans[i - from] = hnsw.plan(ord, definition, vectorId);
					}
				}
			});

			long[] applied = { 0 };
			runWrite(() -> {
				for (int i = from; i < end; i++) {
					long vectorId = members[i];
					// Re-checked rather than trusted: the plans were made before this transaction, and an ordinary
					// write may have deleted the node or moved it out of the index since.
					if (!vectors.exists(vectorId) || vectors.indexOrdinal(vectorId) != ord) {
						continue;
					}
					HnswIndex.Plan plan = plans[i - from];
					if (plan == null) {
						continue;
					}
					// A plan the graph has moved past is re-planned here, under the lock. That is the expensive path
					// and it is meant to be rare: it fires when the entry point appeared or vanished since the plan,
					// which in a build is essentially the first batch against an empty graph.
					if (!hnsw.apply(ord, definition, vectorId, plan)) {
						hnsw.insert(ord, definition, vectorId);
					}
					applied[0]++;
				}
				// In the same transaction as the links it counts, so a crash can never leave the mark ahead of them.
				indexes.vectorBuiltCount(ord, indexes.vectorBuiltCount(ord) + applied[0]);
			});
			batch = end;
		}
		// Only now is the structure complete, and only now will a query be answered from it. A state flipped before
		// the last batch would be an index that answers confidently with a subset.
		runWrite(() -> indexes.vectorIndexState(ord, VectorIndexState.READY));
	}

	/**
	 * Members planned against one read view and applied in one transaction.
	 *
	 * <p>
	 * <b>This number is a measured compromise between two costs that pull in opposite directions</b>, and both halves
	 * were measured before it was chosen ({@code VectorBuildBenchmark}, 100,000 members):
	 * </p>
	 *
	 * <ul>
	 * <li><b>Smaller batches cost throughput, and far more than expected.</b> The default sync mode is
	 * {@code FSYNC_ON_COMMIT}, so a batch is a durable commit. At one member per commit the build measured
	 * <b>130 inserts/s against 332</b> for the original — 21.3 hours against 8.4 for ten million — because the fsync
	 * added roughly 4.7 ms to a 3 ms insert. Doing the obvious correct thing per member is two and a half times
	 * slower, <em>and</em> worse for a competing writer, which waits on 95,000 tiny commits instead of a few hundred
	 * larger ones.</li>
	 * <li><b>Larger batches should cost recall.</b> Every member of a batch is planned against a graph containing none
	 * of the others, so within a batch nobody links to anybody, and HNSW's structure comes from each insert searching
	 * the graph the previous inserts built.</li>
	 * </ul>
	 *
	 * <p>
	 * <b>The second cost could not be measured, and that is stated rather than glossed.</b> {@code VectorRecallTest}
	 * reports recall@10 of 1.0 at batch 32, 128 and 512 alike — at 512 over its 1,500-vector corpus a third of the
	 * members are blind to each other per batch, and recall does not move. The test saturates, so it confirms there is
	 * no gross regression and cannot choose between these values. The choice is therefore made on the two numbers that
	 * do vary, and there 128 simply dominates 32: the same 250 ms worst-case stall — that figure turns out to be a
	 * floor set by something other than the batch — with 17 percent more throughput, which is slightly better than
	 * the original design managed while holding the lock for the search as well.
	 * </p>
	 *
	 * <p>
	 * <b>The empty-graph case is not a recall question but a correctness one</b>, and it is handled rather than
	 * tuned around: the members of the very first batch are all planned against an empty graph and would all
	 * think themselves the entry point, leaving all but one with no links at all. {@code HnswIndex.apply} refuses a
	 * plan whose view of "is there an entry point" no longer holds, and the build re-plans that member under the lock.
	 * </p>
	 */
	private static final int VECTOR_BUILD_BATCH = Integer.getInteger("graph.vector.build.batch", 128);

	/**
	 * Makes a node a member of one declared index, refusing a vector the index cannot hold.
	 * <p>
	 * Only the declaration scan calls this. Ordinary maintenance goes through
	 * {@link #maintainVectorMembership}, which cannot refuse anything — by then the write has already been accepted,
	 * and a node that does not qualify is simply not a member.
	 *
	 * @return 1 if the node became a member, 0 otherwise
	 */
	private long declareVectorMember(long nodeId, VectorIndexDefinition definition, int ord) {
		int keyId = catalog.lookupKey(definition.key());
		if (keyId == 0) {
			return 0;
		}
		long block = nodes.propBlockId(nodeId);
		Object stored = properties.read(block, keyId);
		if (stored == null) {
			return 0;
		}
		if (!(stored instanceof float[] vector) || vector.length != definition.dimensions()) {
			throw new IllegalArgumentException("Node " + ElementId.toString(nodeId) + " holds a "
				+ (stored instanceof float[] other ? other.length + " dimension vector" : "non-vector")
				+ " under " + definition.key() + ", so an index over " + definition.dimensions()
				+ " dimensions cannot cover it");
		}
		long vectorId = properties.ownedIdOf(block, keyId);
		if (ElementId.isNull(vectorId) || ElementId.kind(vectorId) != Kind.VEC) {
			// A vector of the store's own dimension always lands in a vector record, so this is a contradiction rather
			// than a case to handle.
			throw new IllegalStateException("Node " + ElementId.toString(nodeId) + " has a vector of the store's own "
				+ "dimension that is not held in a vector record");
		}
		vectors.indexOrdinal(vectorId, ord);
		return 1;
	}

	/**
	 * Brings a node's vector-index membership back in line with its labels and its properties.
	 *
	 * <p>
	 * Called from every write that can change either, which is why it recomputes rather than applying a delta: a
	 * membership is a function of {@code (labels, property)} and both halves move independently. Adding {@code :Person}
	 * to a node that already holds an embedding makes it a member; removing the label, or overwriting the vector with
	 * something of the wrong length, stops it being one. A delta-based version would need every caller to know which
	 * half it changed, and the one that got it wrong would leave a member that no longer qualifies — answering queries
	 * with a node the index has no business returning.
	 * </p>
	 *
	 * <p>
	 * It costs nothing at all until an index is declared, which is the common case.
	 * </p>
	 */
	private void maintainVectorMembership(long nodeId) {
		if (vectors == null) {
			return;
		}
		List<VectorIndexDefinition> declared = indexes.vectorIndexes();
		if (declared.isEmpty()) {
			return;
		}
		long block = nodes.propBlockId(nodeId);
		if (ElementId.isNull(block)) {
			return;
		}
		Set<String> labels = new HashSet<>(nodeLabels(nodeId));
		Map<Long, Integer> claims = new LinkedHashMap<>();
		for (int ord = 0; ord < declared.size(); ord++) {
			VectorIndexDefinition definition = declared.get(ord);
			if (definition == null || !labels.contains(definition.label())) {
				continue;
			}
			int keyId = catalog.lookupKey(definition.key());
			if (keyId == 0) {
				continue;
			}
			long vectorId = properties.ownedIdOf(block, keyId);
			if (!ElementId.isNull(vectorId) && ElementId.kind(vectorId) == Kind.VEC && vectors.exists(vectorId)) {
				claims.put(vectorId, ord);
			}
		}
		for (long ownedId : properties.ownedIds(block)) {
			if (ElementId.kind(ownedId) != Kind.VEC || !vectors.exists(ownedId)) {
				continue;
			}
			int wanted = claims.getOrDefault(ownedId, -1);
			int held = vectors.indexOrdinal(ownedId);
			if (held == wanted) {
				continue;
			}
			if (held >= 0) {
				// It is leaving an index. Repair the entry point before the record stops being a member, or a
				// subsequent search starts from a node the index no longer owns.
				VectorIndexDefinition leaving = declared.get(held);
				if (leaving != null && hnsw != null) {
					hnsw.remove(held, leaving, ownedId);
				}
				vectors.unlink(ownedId);
			}
			vectors.indexOrdinal(ownedId, wanted);
			if (wanted >= 0 && hnsw != null) {
				try {
					hnsw.insert(wanted, declared.get(wanted), ownedId);
				} catch (IOException e) {
					throw new GraphStoreException("Linking a vector into its index failed", e);
				}
			}
		}
	}

	/**
	 * Takes a vector record out of its index as its slot is freed. Only the entry point needs repair — stale links from
	 * neighbours fail the liveness check a search already makes — but a freed entry point leaves nowhere to start.
	 */
	private void releaseVectorMember(long vectorId) {
		int held = vectors.indexOrdinal(vectorId);
		if (held < 0) {
			return;
		}
		List<VectorIndexDefinition> declared = indexes.vectorIndexes();
		VectorIndexDefinition leaving = held < declared.size() ? declared.get(held) : null;
		if (leaving != null) {
			hnsw.remove(held, leaving, vectorId);
		}
	}

	@Override
	public void dropVectorIndex(String label, String key) throws IOException {
		declare(() -> {
			int ord = indexes.vectorIndexOrdinal(label, key);
			if (ord < 0) {
				throw new IllegalArgumentException("No vector index on :" + label + "." + key);
			}
			// Clear the membership stamp before the declaration, so no record is left claiming an ordinal that has
			// been reused by a later declaration.
			if (vectors != null) {
				for (long vectorId : segmentSets.get(Kind.VEC).liveIds()) {
					if (vectors.indexOrdinal(vectorId) == ord) {
						vectors.indexOrdinal(vectorId, -1);
						vectors.unlink(vectorId);
					}
				}
			}
			indexes.removeVectorIndex(ord);
			return null;
		});
	}

	@Override
	public List<VectorIndexDefinition> vectorIndexes() {
		return read(() -> {
			List<VectorIndexDefinition> declared = new ArrayList<>();
			for (VectorIndexDefinition definition : indexes.vectorIndexes()) {
				if (definition != null) {
					declared.add(definition);
				}
			}
			return declared;
		});
	}

	@Override
	public List<Neighbour> nearest(String label, String key, float[] query, int k,
		java.util.function.LongPredicate filter) throws IOException {
		if (query == null || query.length == 0) {
			throw new IllegalArgumentException("A vector search needs a query vector");
		}
		if (k <= 0) {
			throw new IllegalArgumentException("k must be positive, got " + k);
		}
		checkOpen();
		// Through read(), like every other read of a page: the declaration lives on the index catalog page, and read
		// bare it was neither excluded from a commit's apply nor rebuildable for a view — inside a view whose catalog
		// page had changed, the engine's internal retry signal escaped to the caller instead.
		VectorIndexDeclared declared = vectorIndexDeclared(label, key);
		if (declared == null) {
			throw new IllegalStateException("No vector index on :" + label + "." + key
				+ ". Declare one with createVectorIndex, or use nearestByScan for an exact answer without one.");
		}
		int ord = declared.ordinal();
		VectorIndexDefinition definition = declared.definition();
		if (definition.state() != VectorIndexState.READY) {
			throw new IllegalStateException("The vector index on :" + label + "." + key + " is "
				+ definition.state() + " and cannot answer yet");
		}
		if (query.length != definition.dimensions()) {
			throw new IllegalArgumentException("This index holds " + definition.dimensions()
				+ " dimension vectors, and the query has " + query.length);
		}
		return searchMembers(ord, definition, query, k, filter);
	}

	/**
	 * The member scan behind {@link #nearest}: exact, over the index's own members rather than the whole store.
	 * <p>
	 * It is what an approximate structure replaces, and until then it is what answers — which means the query surface
	 * and the filter semantics are already settled and tested by the time the structure arrives.
	 */
	private List<Neighbour> searchMembers(int ord, VectorIndexDefinition definition, float[] query, int k,
		java.util.function.LongPredicate filter) throws IOException {
		VectorMetric metric = definition.metric();
		List<Neighbour> result = new ArrayList<>();
		runRead(() -> {
			// The beam is widened for a filtered query. A filter is applied when admitting a result, never to steer
			// the walk, so a selective one simply needs more candidates examined to find its k — the alternative,
			// filtering what the walk visits, is what collapses recall.
			int ef = Math.max(definition.efConstruction() / 4, 4 * k);
			if (filter != null) {
				ef *= 4;
			}
			List<Long> found = hnsw.search(ord, definition, query, k,
				ef, filter == null ? null : vectorId -> {
					long ownerId = vectors.owner(vectorId);
					return nodes.exists(ownerId) && filter.test(ownerId);
				});
			for (long vectorId : found) {
				long ownerId = vectors.owner(vectorId);
				if (!nodes.exists(ownerId)) {
					continue;
				}
				double score = metric == VectorMetric.EUCLIDEAN
					? Vectors.euclidean(vectors.read(vectorId), query)
					: scoreFromDot(metric, vectors.dot(vectorId, query), vectors.norm(vectorId), query);
				result.add(new Neighbour(ownerId, score));
			}
		});
		return result;
	}

	/** Cosine from a dot product and the two norms — which is why the record carries its own norm. */
	private static double scoreFromDot(VectorMetric metric, double dot, double storedNorm, float[] query) {
		if (metric == VectorMetric.DOT) {
			return dot;
		}
		double queryNorm = Vectors.norm(query);
		return storedNorm == 0 || queryNorm == 0 ? 0 : dot / (storedNorm * queryNorm);
	}

	@Override
	public boolean nodeExists(long nodeId) {
		return read(() -> nodes.exists(nodeId));
	}

	@Override
	public String nodeLabel(long nodeId) {
		return read(() -> catalog.label(nodes.labelId(nodeId)));
	}

	@Override
	public Properties nodeProperties(long nodeId) {
		return read(() -> propertiesOf(nodes.propBlockId(nodeId)));
	}

	@Override
	public Object nodeProperty(long nodeId, String key) {
		return read(() -> {
			int keyId = catalog.lookupKey(key);
			return keyId == 0 ? null : properties.read(nodes.propBlockId(nodeId), keyId);
		});
	}

	@Override
	public void setNodeProperties(long nodeId, Properties props) throws IOException {
		write(() -> {
			long old = nodes.propBlockId(nodeId);
			int[] labelIds = nodes.labels(nodeId);
			Map<Integer, Object> before = properties.read(old);
			Map<Integer, Object> after = toKeyIds(props);
			// Before anything is written, as createNode and addNodeLabel do. Checked after the store, a caught
			// violation inside a transaction committed the refused value with the indexes still describing the old one.
			checkUnique(labelIds, after, nodeId);
			nodes.propBlockId(nodeId, properties.store(after, nodeId));
			properties.free(old);
			indexes.onPropertiesChanged(nodeId, labelIds, before, after);
			maintainVectorMembership(nodeId);
			return null;
		});
	}

	@Override
	public void deleteNode(long nodeId) throws IOException {
		write(() -> {
			// Every relationship touching the node goes first, on both endpoints, so no dangling adjacency survives.
			for (long edge : adjacency.edges(nodeId)) {
				if (PackedEdge.isMaterialised(edge)) {
					long relId = liveRelationshipId(PackedEdge.relationshipId(edge));
					if (!ElementId.isNull(relId)) {
						// Only the far end's entry: this node's chain is freed wholesale below, and removing its entries
						// one by one re-encoded its block once per relationship.
						deleteRelationshipInternal(relId, nodeId);
					}
				} else {
					long neighbour = liveNodeId(PackedEdge.neighbourId(edge));
					if (!ElementId.isNull(neighbour)) {
						int dir = PackedEdge.dir(edge);
						int mirrorDir = dir == PackedEdge.DIR_OUT ? PackedEdge.DIR_IN : PackedEdge.DIR_OUT;
						adjacency.remove(neighbour, PackedEdge.ofNeighbour(mirrorDir, PackedEdge.typeId(edge), nodeId));
						if (dir == PackedEdge.DIR_OUT) {
							nodes.degreeDelta(neighbour, 0, -1);
						} else {
							nodes.degreeDelta(neighbour, -1, 0);
						}
					}
				}
			}
			indexes.onNodeDeleted(nodeId, nodes.labels(nodeId), properties.read(nodes.propBlockId(nodeId)));
			adjacency.freeChain(nodeId);
			properties.free(nodes.propBlockId(nodeId));
			// Part of the same cascade as the property block: the overflow is a PROP block owned by this node.
			nodes.freeLabelOverflow(nodeId);
			nodes.free(nodeId);
			return null;
		});
	}

	@Override
	public int degreeOut(long nodeId) {
		return read(() -> nodes.degreeOut(nodeId));
	}

	@Override
	public int degreeIn(long nodeId) {
		return read(() -> nodes.degreeIn(nodeId));
	}

	// ---------------------------------------------------------------- edges

	@Override
	public void createEdge(long fromId, String type, long toId) throws IOException {
		write(() -> {
			requireNode(fromId);
			requireNode(toId);
			int typeId = catalog.internEdgeType(type);
			if (!Catalog.packable(typeId)) {
				// A type id beyond the 14 bit field cannot be a lightweight entry, so the edge escalates to a
				// materialised relationship, whose entry simply does not cache the type (RELATIONSHIP_STORAGE.md §3).
				createRelationshipInternal(fromId, typeId, toId, null);
				return null;
			}
			// Both endpoints are always updated. Linking only one side is what made the proof of concept incapable of
			// traversing backwards.
			adjacency.add(fromId, PackedEdge.ofNeighbour(PackedEdge.DIR_OUT, typeId, toId));
			adjacency.add(toId, PackedEdge.ofNeighbour(PackedEdge.DIR_IN, typeId, fromId));
			nodes.degreeDelta(fromId, 1, 0);
			nodes.degreeDelta(toId, 0, 1);
			return null;
		});
	}

	@Override
	public boolean deleteEdge(long fromId, String type, long toId) throws IOException {
		return write(() -> {
			int typeId = catalog.lookupLabel(type);
			if (typeId == 0) {
				return false;
			}
			if (!Catalog.packable(typeId)) {
				return deleteEscalatedEdge(fromId, typeId, toId);
			}
			if (!adjacency.remove(fromId, PackedEdge.ofNeighbour(PackedEdge.DIR_OUT, typeId, toId))) {
				return false;
			}
			adjacency.remove(toId, PackedEdge.ofNeighbour(PackedEdge.DIR_IN, typeId, fromId));
			nodes.degreeDelta(fromId, -1, 0);
			nodes.degreeDelta(toId, 0, -1);
			return true;
		});
	}

	@Override
	public long createRelationship(long fromId, String type, long toId) throws IOException {
		return createRelationship(fromId, type, toId, null);
	}

	/**
	 * Removes one edge that {@link #createEdge} had to escalate: a relationship of this type from {@code fromId} to
	 * {@code toId}. Any one of them satisfies the request, exactly as with two identical lightweight entries.
	 */
	private boolean deleteEscalatedEdge(long fromId, int typeId, long toId) throws IOException {
		for (long edge : adjacency.edges(fromId)) {
			if (!PackedEdge.isMaterialised(edge)) {
				continue;
			}
			long relId = liveRelationshipId(PackedEdge.relationshipId(edge));
			if (!ElementId.isNull(relId) && rels.typeId(relId) == typeId && rels.fromId(relId) == fromId
				&& rels.toId(relId) == toId) {
				deleteRelationshipInternal(relId);
				return true;
			}
		}
		return false;
	}

	@Override
	public long createRelationship(long fromId, String type, long toId, Properties props) throws IOException {
		return write(() -> {
			requireNode(fromId);
			requireNode(toId);
			return createRelationshipInternal(fromId, catalog.internEdgeType(type), toId, props);
		});
	}

	private long createRelationshipInternal(long fromId, int typeId, long toId, Properties props) throws IOException {
		long relId = rels.create(fromId, typeId, toId, ElementId.NULL);
		if (props != null && !props.isEmpty()) {
			rels.propBlockId(relId, properties.store(toKeyIds(props), relId));
		}
		// The entry caches the type, which is what lets a type-filtered traversal skip it without the random read
		// its record costs. Direction is not cacheable — see PackedEdge. A type beyond 14 bits is not cached at all.
		long entry = PackedEdge.ofRelationship(relId, typeId);
		adjacency.add(fromId, entry);
		if (toId != fromId) {
			// A materialised entry carries no direction of its own — it is resolved from the relationship's
			// endpoints — so the two copies of a self-loop would be byte-identical and both would read as
			// outgoing. A self-loop therefore gets one entry, and the traversal expands it into both directions.
			adjacency.add(toId, entry);
		}
		nodes.degreeDelta(fromId, 1, 0);
		nodes.degreeDelta(toId, 0, 1);
		return relId;
	}

	@Override
	public boolean relationshipExists(long relationshipId) {
		return read(() -> rels.exists(relationshipId));
	}

	@Override
	public String relationshipType(long relationshipId) {
		return read(() -> catalog.label(rels.typeId(relationshipId)));
	}

	@Override
	public long relationshipFrom(long relationshipId) {
		return read(() -> rels.fromId(relationshipId));
	}

	@Override
	public long relationshipTo(long relationshipId) {
		return read(() -> rels.toId(relationshipId));
	}

	@Override
	public Properties relationshipProperties(long relationshipId) {
		return read(() -> propertiesOf(rels.propBlockId(relationshipId)));
	}

	@Override
	public void setRelationshipProperties(long relationshipId, Properties props) throws IOException {
		write(() -> {
			long old = rels.propBlockId(relationshipId);
			rels.propBlockId(relationshipId, properties.store(toKeyIds(props), relationshipId));
			properties.free(old);
			return null;
		});
	}

	@Override
	public void deleteRelationship(long relationshipId) throws IOException {
		write(() -> {
			deleteRelationshipInternal(relationshipId);
			return null;
		});
	}

	private void deleteRelationshipInternal(long relationshipId) throws IOException {
		deleteRelationshipInternal(relationshipId, ElementId.NULL);
	}

	/**
	 * @param dyingNodeId
	 *            an endpoint that is itself being deleted, whose adjacency and degrees are therefore left alone, or
	 *            {@link ElementId#NULL}
	 */
	private void deleteRelationshipInternal(long relationshipId, long dyingNodeId) throws IOException {
		long fromId = rels.fromId(relationshipId);
		long toId = rels.toId(relationshipId);
		// Matched by the relationship it names rather than by its whole packed value: the type field of a materialised
		// entry is a cache, and a store written before the cache existed holds zero there where a fresh one holds the
		// type. Matching on the exact bits would leave those entries behind as dangling adjacency.
		if (fromId != dyingNodeId && nodes.exists(fromId)) {
			adjacency.removeMaterialised(fromId, relationshipId);
			nodes.degreeDelta(fromId, -1, 0);
		}
		if (toId != dyingNodeId && nodes.exists(toId)) {
			if (toId != fromId) {
				adjacency.removeMaterialised(toId, relationshipId);
			}
			// A self-loop's single entry is already gone; only its incoming counter is still owed.
			nodes.degreeDelta(toId, 0, -1);
		}
		properties.free(rels.propBlockId(relationshipId));
		rels.free(relationshipId);
	}

	// ---------------------------------------------------------------- traversal

	@Override
	public List<Edge> neighbours(long nodeId, Direction direction) {
		return neighbours(nodeId, direction, null);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A drain of {@link NeighbourWalk}, so the chain walk, the two filter levels and the entry-to-{@code Edge}
	 * resolution exist once rather than twice.
	 * <p>
	 * <b>It stays on the optimistic read path, and the walk it drains takes no lease.</b> That is the whole difference
	 * between this and {@link #neighbourCursor}: the list is built inside one call and re-running that call costs a
	 * discarded list, so it can gamble on there being no concurrent apply. A cursor handed back to a caller cannot be
	 * re-run, so it pays for the latch. Making this one take the latch too would turn every traversal in the engine
	 * into a writer-excluding read to buy nothing.
	 */
	@Override
	public List<Edge> neighbours(long nodeId, Direction direction, Set<String> types) {
		return read(() -> {
			requireNode(nodeId);
			List<Edge> result = new ArrayList<>();
			try (NeighbourWalk walk = new NeighbourWalk(nodeId, direction, resolveTypes(types), null)) {
				while (walk.next()) {
					result.add(walk.edge());
				}
			}
			return result;
		});
	}

	@Override
	public NeighbourCursor neighbourCursor(long nodeId, Direction direction, Set<String> types) {
		ReadLease lease = new ReadLease();
		try {
			requireNode(nodeId);
			return new NeighbourWalk(nodeId, direction, resolveTypes(types), lease);
		} catch (RuntimeException e) {
			// The cursor never came into existence, so nothing will ever close it.
			lease.close();
			throw e;
		}
	}

	/**
	 * The streaming form of {@link #neighbours}: one adjacency entry pulled at a time, resolved into the API edges it
	 * stands for.
	 * <p>
	 * <b>The pending buffer is not incidental.</b> {@link #materialise} appends <em>zero</em> edges for an entry the
	 * type or direction filter rejects and <em>two</em> for a materialised self-loop, which is both an outgoing and an
	 * incoming edge of the same node. A cursor that assumed one edge per entry would drop half of every self-loop, so
	 * the walk keeps what one entry produced and serves it before pulling the next.
	 *
	 * @param lease
	 *            the read view to release on close, or null when the caller holds one for the duration
	 */
	private final class NeighbourWalk implements NeighbourCursor {

		private final long nodeId;
		private final Direction direction;
		private final TypeFilter typeIds;
		private final ReadLease lease;
		private final io.metaloom.graph.core.store.adj.EdgeCursor entries;
		private final List<Edge> pending = new ArrayList<>(2);

		private int index;
		private Edge current;

		NeighbourWalk(long nodeId, Direction direction, TypeFilter typeIds, ReadLease lease) {
			this.nodeId = nodeId;
			this.direction = direction;
			this.typeIds = typeIds;
			this.lease = lease;
			this.entries = adjacency.cursor(nodeId, typeIds);
		}

		@Override
		public boolean next() {
			while (index == pending.size()) {
				pending.clear();
				index = 0;
				if (!entries.next()) {
					current = null;
					return false;
				}
				materialise(nodeId, entries.edge(), direction, typeIds, pending);
			}
			current = pending.get(index++);
			return true;
		}

		@Override
		public Edge edge() {
			if (current == null) {
				throw new IllegalStateException("The cursor is not positioned on an edge; call next() first");
			}
			return current;
		}

		@Override
		public void close() {
			if (lease != null) {
				lease.close();
			}
		}
	}

	/**
	 * Whether the two nodes are connected by any edge, in either direction and of any type.
	 * <p>
	 * A block whose recorded location range excludes the target is walked past without being decoded, and the walk
	 * stops at the first match. This is the read-path use of {@code min_location} / {@code max_location}
	 * (RELATIONSHIP_STORAGE.md §5.3); it prunes a chain rather than indexing it, so the cost is O(blocks).
	 */
	@Override
	public boolean hasEdge(long nodeId, long neighbourId) {
		return read(() -> {
			requireNode(nodeId);
			return adjacency.hasEdge(nodeId, neighbourId, packed -> {
				long relId = liveRelationshipId(PackedEdge.relationshipId(packed));
				if (ElementId.isNull(relId)) {
					return false;
				}
				// A materialised entry names its relationship, not its neighbour, so only the record says who is at
				// the other end. A self-loop matches when the caller asked about the node itself.
				return rels.fromId(relId) == neighbourId || rels.toId(relId) == neighbourId;
			});
		});
	}

	/**
	 * Whether a breadth-first traversal advises the kernel about its next frontier. Off by default; see
	 * {@link #traverse} and spec/GRAPH_BENCHMARK.md for the measurement that decided it.
	 */
	private static final boolean PREFETCH_TRAVERSAL =
		Boolean.getBoolean("metaloom.graph.prefetch.traversal");

	/** How many queued nodes are advised at a time when prefetching is on. */
	private static final int PREFETCH_DEPTH = Integer.getInteger("metaloom.graph.prefetch.depth", 64);

	@Override
	public List<Edge> traverse(long startNodeId, Direction direction, Set<String> types, int maxDepth) {
		return read(() -> {
			requireNode(startNodeId);
			TypeFilter typeIds = resolveTypes(types);
			List<Edge> result = new ArrayList<>();
			Set<Long> visited = new HashSet<>();
			visited.add(startNodeId);
			Deque<long[]> queue = new ArrayDeque<>();
			// Entries are {id, depth, sequence}: the sequence is the order of enqueueing, which is what lets the
			// prefetch below advise each id once instead of re-advising the whole window on every step.
			queue.add(new long[] { startNodeId, 0, 0 });
			long enqueued = 1;
			long advisedBelow = 1;
			// The frontier is the one thing a breadth-first walk knows in advance, so it is the one place a mapped data
			// path can get more than one read in flight: naming the pages of the nodes queued behind this one lets the
			// kernel fetch them while this level is still being decoded.
			//
			// It is off by default, and that is a measured decision rather than caution. Advising costs a syscall per
			// queued node, and on a store that fits in page cache there is no fault for it to overlap — so it is pure
			// overhead, measured at 1,967 ns per edge against 701 with it off. That is 2.8x slower, on the only case
			// this machine can produce: posix_fadvise evicts nothing on its filesystem, so the benefit is
			// unmeasurable here and only the cost is real. Enabling something whose cost is measured and whose
			// benefit is not would be exactly backwards.
			long[] frontier = PREFETCH_TRAVERSAL ? new long[PREFETCH_DEPTH] : null;
			while (!queue.isEmpty()) {
				long[] current = queue.poll();
				long nodeId = current[0];
				int depth = (int) current[1];
				if (depth >= maxDepth) {
					continue;
				}
				if (frontier != null) {
					// Only the ids newly inside the window. Re-advising all of it on every step cost one madvise per
					// queued id per visited node — about sixty-three of every sixty-four of them already advised.
					long windowEnd = current[2] + 1 + frontier.length;
					int ahead = 0;
					for (long[] queued : queue) {
						if (queued[2] >= windowEnd) {
							break;
						}
						if (queued[2] >= advisedBelow) {
							frontier[ahead++] = queued[0];
							advisedBelow = queued[2] + 1;
						}
					}
					if (ahead > 0) {
						nodes.segments().prefetch(frontier, ahead);
					}
				}
				for (long packed : adjacency.edges(nodeId, typeIds)) {
					int before = result.size();
					materialise(nodeId, packed, direction, typeIds, result);
					for (int i = before; i < result.size(); i++) {
						long neighbour = result.get(i).neighbourId();
						if (visited.add(neighbour)) {
							queue.add(new long[] { neighbour, depth + 1, enqueued++ });
						}
					}
				}
			}
			return result;
		});
	}

	@Override
	public void forEachNode(java.util.function.LongConsumer consumer) {
		forEachNodeWhile(id -> {
			consumer.accept(id);
			return true;
		});
	}

	@Override
	public boolean forEachNodeWhile(java.util.function.LongPredicate consumer) {
		return forEachNodeWhile(consumer, ScanOptions.uninterrupted());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The throttled form wraps the caller's predicate rather than changing the walk: every {@code batchSize} ids it
	 * closes the {@link ReadLease} and opens a new one, which is the whole of the pacing. {@code SegmentSet} needs no
	 * knowledge of it, and the id sequence is identical either way — the only difference is who may commit while it
	 * runs.
	 * </p>
	 *
	 * <p>
	 * <b>Reopening the lease is what makes this safe rather than merely faster.</b> A lease taken inside a read view
	 * re-registers the exclusion the version machinery needs, so the reconstruction path is available in every batch;
	 * and because the bitmap is read through the same snapshot as everything else, the batches agree about which nodes
	 * exist. See {@link ScanOptions}.
	 * </p>
	 */
	@Override
	public boolean forEachNodeWhile(java.util.function.LongPredicate consumer, ScanOptions options) {
		ScanOptions scan = options == null ? ScanOptions.uninterrupted() : options;
		try {
			boolean[] complete = { true };
			if (!scan.isThrottled()) {
				// runRead rather than read: a scan is long, so it takes the latch outright instead of gambling on an
				// optimistic pass it would usually lose, and it must not be run twice — the consumer has side effects.
				runRead(() -> complete[0] = segmentSets.get(Kind.NODE).forEachLiveIdWhile(consumer));
				return complete[0];
			}
			checkOpen();
			ReadLease[] lease = { new ReadLease() };
			int[] sinceYield = { 0 };
			try {
				complete[0] = segmentSets.get(Kind.NODE).forEachLiveIdWhile(id -> {
					if (!consumer.test(id)) {
						return false;
					}
					if (++sinceYield[0] >= scan.batchSize()) {
						sinceYield[0] = 0;
						lease[0].close();
						// The window in which a writer can apply. Everything the scan is entitled to see is fixed by
						// the caller's snapshot, so nothing that commits here can change the answer.
						pause(scan.pauseMicrosPerBatch());
						lease[0] = new ReadLease();
					}
					return true;
				});
			} finally {
				lease[0].close();
			}
			return complete[0];
		} catch (IOException e) {
			throw new GraphStoreException("Node scan failed", e);
		}
	}

	/** Sleeps between batches, if asked. Interruption ends the pause rather than the scan. */
	private static void pause(long micros) {
		if (micros <= 0) {
			return;
		}
		try {
			Thread.sleep(java.time.Duration.ofNanos(micros * 1_000));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public List<Long> nodesByLabel(String label) {
		return read(() -> indexes.nodesByLabel(label));
	}

	@Override
	public List<Long> nodesByProperty(String key, Object value) {
		return read(() -> {
			List<Long> candidates = indexes.nodesByProperty(key, value);
			// The index is keyed on a value hash, so candidates are verified against the stored property.
			List<Long> confirmed = new ArrayList<>(candidates.size());
			int keyId = catalog.lookupKey(key);
			for (long nodeId : candidates) {
				if (!nodes.exists(nodeId)) {
					continue;
				}
				Object stored = properties.read(nodes.propBlockId(nodeId), keyId);
				if (IndexCatalog.valueEquals(stored, normalise(value))) {
					confirmed.add(nodeId);
				}
			}
			return confirmed;
		});
	}

	private static Object normalise(Object value) {
		if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
			return ((Number) value).longValue();
		}
		return value;
	}

	/**
	 * Appends the API edges one adjacency entry stands for, which is usually one and is two for a materialised
	 * self-loop. A surviving materialised entry costs one extra 64 byte read, which is the right place to pay it since
	 * its properties live there too.
	 * <p>
	 * <b>The type filter runs first, off the entry's own bits.</b> That is what keeps a type-filtered traversal over a
	 * supernode from paying a random read per edge for a query that may match none of them: the read is now paid per
	 * <em>surviving candidate</em> rather than per entry. An entry that caches no type still falls through to the
	 * record, which is what every entry of an older store does.
	 */
	private void materialise(long fromNodeId, long packed, Direction direction, TypeFilter typeIds,
		List<Edge> out) {
		if (PackedEdge.excludedByType(packed, typeIds)) {
			return;
		}
		if (PackedEdge.isMaterialised(packed)) {
			long relId = liveRelationshipId(PackedEdge.relationshipId(packed));
			if (ElementId.isNull(relId)) {
				return;
			}
			// Still checked against the record: an entry that cached no type has not been filtered yet, and one that
			// did is being confirmed against the authority its cache stands in for. One resolve for all three fields.
			java.lang.foreign.MemorySegment rel = rels.view(relId);
			int typeId = RelStore.typeId(rel);
			if (typeIds != null && !typeIds.admits(typeId)) {
				return;
			}
			long relFrom = RelStore.fromId(rel);
			long relTo = RelStore.toId(rel);
			if (relFrom == relTo) {
				// One stored entry, two edges: the node is both endpoints, so it has an outgoing and an incoming
				// edge and the direction filter has to be applied to each of them separately.
				if (direction.matches(PackedEdge.DIR_OUT)) {
					out.add(new Edge(relTo, catalog.label(typeId), Direction.OUT, relId));
				}
				if (direction.matches(PackedEdge.DIR_IN)) {
					out.add(new Edge(relFrom, catalog.label(typeId), Direction.IN, relId));
				}
				return;
			}
			boolean outgoing = relFrom == fromNodeId;
			if (!direction.matches(outgoing ? PackedEdge.DIR_OUT : PackedEdge.DIR_IN)) {
				return;
			}
			long neighbour = outgoing ? relTo : relFrom;
			out.add(new Edge(neighbour, catalog.label(typeId), outgoing ? Direction.OUT : Direction.IN, relId));
			return;
		}
		int dirCode = PackedEdge.dir(packed);
		if (!direction.matches(dirCode)) {
			return;
		}
		// The type filter has already run above: a lightweight entry always carries its own type.
		int typeId = PackedEdge.typeId(packed);
		long neighbour = liveNodeId(PackedEdge.neighbourId(packed));
		if (ElementId.isNull(neighbour)) {
			return;
		}
		out.add(new Edge(neighbour, catalog.label(typeId),
			dirCode == PackedEdge.DIR_OUT ? Direction.OUT : Direction.IN, ElementId.NULL));
	}

	/** Recovers the full id, including its generation, from a 48 bit adjacency location. */
	private long liveNodeId(long idWithoutGen) {
		int gen = segmentSets.get(Kind.NODE)
			.generationAt(ElementId.segment(idWithoutGen), ElementId.slot(idWithoutGen));
		return gen == 0 ? ElementId.NULL : ElementId.withGen(idWithoutGen, gen);
	}

	private long liveRelationshipId(long idWithoutGen) {
		int gen = segmentSets.get(Kind.REL)
			.generationAt(ElementId.segment(idWithoutGen), ElementId.slot(idWithoutGen));
		return gen == 0 ? ElementId.NULL : ElementId.withGen(idWithoutGen, gen);
	}

	/** Resolved once per query into a bitset, so the per-entry test boxes and hashes nothing. */
	private TypeFilter resolveTypes(Set<String> types) {
		if (types == null || types.isEmpty()) {
			return null;
		}
		List<Integer> ids = new ArrayList<>(types.size());
		for (String type : types) {
			int id = catalog.lookupLabel(type);
			if (id != 0) {
				ids.add(id);
			}
		}
		return TypeFilter.of(ids);
	}

	private void requireNode(long nodeId) {
		if (!nodes.exists(nodeId)) {
			throw new GraphStoreException("No live node for id " + ElementId.toString(nodeId));
		}
	}

	// ---------------------------------------------------------------- properties

	private Map<Integer, Object> toKeyIds(Properties props) {
		Map<Integer, Object> result = new LinkedHashMap<>();
		if (props != null) {
			for (Map.Entry<String, Object> entry : props.asMap().entrySet()) {
				result.put(catalog.internKey(entry.getKey()), entry.getValue());
			}
		}
		return result;
	}

	/** A block's properties by name, decoded straight into the result rather than through a map keyed by id. */
	private Properties propertiesOf(long blockId) {
		Properties props = Properties.of();
		properties.read(blockId, (keyId, value) -> {
			String key = catalog.key(keyId);
			if (key != null) {
				props.set(key, value);
			}
		});
		return props;
	}

	// ---------------------------------------------------------------- maintenance

	@Override
	public void checkpoint() throws IOException {
		checkOpen();
		// Exclusive: a checkpoint forces every data file and trims the log, and a commit landing in the middle of that
		// would be durable in the mapping and gone from the log at once.
		lockGateExclusively("checkpoint()");
		long started = System.nanoTime();
		try {
			// Same reason as commit(): an interrupt reaching the channel closes it for everybody.
			uninterruptible(() -> {
				catalog.force();
				long lsn = wal.checkpoint();
				manifest.checkpoint(lsn, lsn, nextTxnId.get());
				manifest.force();
				wal.trim(lsn);
				lastCheckpointLsn = lsn;
				return null;
			});
		} finally {
			counters.checkpointed(System.nanoTime() - started);
			writeGate.writeLock().unlock();
		}
	}

	/**
	 * Moves the replay origin past every log record, before a maintenance step writes the data files outside the log.
	 *
	 * <p>
	 * Redo recovery replays byte runs, and a run is a <em>difference</em>: the bytes a commit changed against the page
	 * as it stood. Punching a page, raising its generation floor, fixing up its checksum and the superblock flip that
	 * every {@code SegmentFile.force()} performs are all writes no record describes. A commit after one of them logs
	 * its difference against a base that already contains it, so replaying the older records from before it puts back
	 * bytes that the newer records, being differences, never mention again. That was measured: a live count that
	 * disagreed with its bitmap after a crash, and a segment whose two superblock copies both came back with bad
	 * checksums, which does not open at all — from three inserts, one {@code reclaim()} and two deletes.
	 * </p>
	 *
	 * <p>
	 * Checkpointing first leaves nothing older to replay: recovery starts after the unlogged writes, and every record
	 * it does replay was computed against them. Called with the write gate held, so no commit can land in between. A
	 * checkpoint forces every data file, which the punch was about to do anyway.
	 * </p>
	 */
	private void checkpointBeforeUnloggedWrites() throws IOException {
		if (!NativeLib.isAvailable()) {
			// Nothing is punched without it, and the superblock flips happen only on the way to a punch.
			return;
		}
		checkpoint();
	}

	/**
	 * Refuses a maintenance operation that would destroy state an open read view is entitled to see.
	 *
	 * <p>
	 * Both callers below reach past the page-versioning mechanism rather than through it, and neither failure would be
	 * noisy:
	 * </p>
	 *
	 * <ul>
	 * <li><b>{@code reclaim()}</b> punches a hole in every all-free page, which makes the filesystem serve zeros for
	 * it. Reconstruction starts from the mapping and applies the runs that commits overwrote; nothing overwrote the
	 * bytes a hole zeroed, so the view would read zeros and report a store full of empty records. <b>Silent</b>, and
	 * indistinguishable from data loss.</li>
	 * <li><b>{@code compact()}</b> relocates records and unlinks emptied segment files. A view holding a page in a
	 * retired segment loses the mapping underneath it.</li>
	 * </ul>
	 *
	 * <p>
	 * Both are rare, deliberate, offline-ish operations — compaction already invalidates every id a caller holds — so
	 * refusing while a reader is open costs nothing and removes a class of silent wrong answers. The reader is named in
	 * the message, because "some reader somewhere" is not actionable.
	 * </p>
	 */
	private void refuseWhileViewsAreOpen(String operation) {
		SnapshotStats stats = snapshotStats();
		if (stats.openViews() > 0) {
			throw new GraphStoreException(operation + " cannot run while " + stats.openViews()
				+ " read view(s) are open; the oldest has been open for " + stats.oldestOpenAgeMillis()
				+ " ms at LSN " + stats.oldestOpenLsn() + ". Close them first.");
		}
	}

	@Override
	public int reclaim() throws IOException {
		checkOpen();
		lockGateExclusively("reclaim()");
		try {
			// Under the gate rather than before it: with concurrent writers, checking first and taking the lock second
			// leaves a window in which a view opens in between, which is exactly the case the refusal exists for.
			refuseWhileViewsAreOpen("reclaim()");
			checkpointBeforeUnloggedWrites();
			int punched = 0;
			for (SegmentSet set : segmentSets.values()) {
				punched += set.reclaim();
			}
			return punched;
		} finally {
			writeGate.writeLock().unlock();
		}
	}

	@Override
	public CompactionReport compact() throws IOException {
		return compact(CompactionOptions.defaults());
	}

	@Override
	public CompactionReport compact(CompactionOptions options) throws IOException {
		checkOpen();
		refuseWhileViewsAreOpen("compact()");
		long startedAt = System.nanoTime();
		long storageBefore = stats().storageBytes();
		double livenessBefore = liveness(options.kinds());

		Compactor.Result result = new Compactor(segmentSets, nodes, rels, adjacency, properties, blobs, vectors,
			indexes, this)
			.compact(options);

		counters.compacted(result.relocated.values().stream().mapToLong(Long::longValue).sum());
		// Ids moved, so the log must not be able to replay a pre-compaction image over the new layout.
		checkpoint();
		CompactionReport report = new CompactionReport(
			Map.copyOf(result.relocated),
			result.batches,
			result.adjacencyBlocksFreed,
			result.indexPagesFreed,
			result.slotsReclaimed,
			result.segmentsRetired,
			result.pagesPunched,
			livenessBefore,
			liveness(options.kinds()),
			storageBefore,
			stats().storageBytes(),
			(System.nanoTime() - startedAt) / 1_000_000);
		logger.debug("Compacted {}: {}", path, report);
		return report;
	}

	/** Live slots over slots below the high water marks, across the given kinds. 1.0 means perfectly dense. */
	private double liveness(Set<Kind> kinds) {
		long live = 0;
		long high = 0;
		for (Kind kind : kinds) {
			SegmentSet set = segmentSets.get(kind);
			if (set != null) {
				live += set.liveCount();
				high += set.highWaterTotal();
			}
		}
		return high == 0 ? 1.0 : (double) live / high;
	}

	@Override
	public void verify() {
		verify(VerifyLevel.CHECKSUMS);
	}

	@Override
	public void verify(VerifyLevel level) {
		verify(level, VerifyOptions.consistentSnapshot());
	}

	/**
	 * Verifies the store.
	 * <p>
	 * <b>Takes the writer lock, not merely the apply latch.</b> The apply latch excludes only the instant a commit
	 * copies its pages into the mapping; it does not exclude a write transaction that is still open. Two of the things
	 * verification looks at disagree for exactly as long as one is:
	 * <ul>
	 * <li>{@link SegmentSet#verify()} compares the free-slot bitmap against the superblock's live count. The bitmap is
	 * written through the page overlay and so is still the pre-transaction image, while {@code liveCountDelta} has
	 * already moved the in-memory superblock. Mid-transaction the two differ by exactly what the transaction has
	 * allocated, and verification reported that as an accounting mismatch — corruption on a healthy store.</li>
	 * <li>It then reaches {@code SegmentFile.flushChecksums}, which walks and clears the dirty-page {@code BitSet} that
	 * the writer sets under the writer lock. An unsynchronised {@link java.util.BitSet} shared between two threads is a
	 * data race whichever way it resolves.</li>
	 * </ul>
	 * Reporting corruption that is not there is worse than missing some: it sends somebody hunting. So verification
	 * waits for the writer rather than racing it, which is also what {@code checkpoint()} already does for the same
	 * flush.
	 */
	@Override
	public void verify(VerifyLevel level, VerifyOptions options) {
		checkOpen();
		lockGateExclusively("verify()");
		try {
			// Reentrant: a caller verifying from inside its own maintenance scope still holds it, and must not have it
			// released underneath by the throttling pacer.
			boolean ownsWriterLock = writeGate.getWriteHoldCount() == 1;
			long[] stamp = { applyLock.readLock() };
			try {
				for (SegmentSet set : segmentSets.values()) {
					set.verify();
				}
				if (level == VerifyLevel.FULL) {
					List<String> violations = new InvariantChecker(nodes, rels, adjacency, properties, blobs, vectors, segmentSets)
						.withIndexes(indexes)
						.check(options, pacer(options, stamp, ownsWriterLock));
					if (!violations.isEmpty()) {
						throw new GraphStoreException("Structural invariants violated (" + violations.size()
							+ "):\n  " + String.join("\n  ", violations));
					}
				}
			} finally {
				applyLock.unlockRead(stamp[0]);
			}
		} finally {
			writeGate.writeLock().unlock();
		}
	}

	/**
	 * Drops both locks between batches so a writer waiting behind a long verification waits for a batch instead.
	 * The scan therefore no longer sees a single instant; {@link VerifyOptions} documents what that costs.
	 * <p>
	 * The writer lock is released in the same window as the read latch, because holding it is now the thing a writer
	 * is actually waiting for — throttling that released only the latch would no longer let anybody through.
	 */
	private InvariantChecker.Pacer pacer(VerifyOptions options, long[] stamp, boolean ownsWriterLock) {
		if (!options.isThrottled()) {
			return InvariantChecker.Pacer.NONE;
		}
		return () -> {
			applyLock.unlockRead(stamp[0]);
			if (ownsWriterLock) {
				writeGate.writeLock().unlock();
			}
			boolean interrupted = false;
			try {
				if (options.pauseMicrosPerBatch() > 0) {
					Thread.sleep(java.time.Duration.ofNanos(options.pauseMicrosPerBatch() * 1000));
				} else {
					Thread.yield();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				interrupted = true;
			}
			// Retaken unconditionally, including on interrupt: the caller's finally blocks will release both.
			if (ownsWriterLock) {
				writeGate.writeLock().lock();
			}
			stamp[0] = applyLock.readLock();
			return !interrupted;
		};
	}

	@Override
	public StoreMetrics metrics() {
		checkOpen();
		int unflushed = 0;
		for (SegmentSet set : segmentSets.values()) {
			for (SegmentFile segment : set.segments()) {
				unflushed += segment.dirtyPageCount();
			}
		}
		IoStats io = counters.snapshot();
		return new StoreMetrics(io,
			Math.max(0, wal.nextLsn() - wal.durableLsn()),
			Math.max(0, wal.nextLsn() - lastCheckpointLsn),
			unflushed,
			io.checkpoints() == 0 ? 0 : io.checkpointNanos() / 1_000_000.0 / io.checkpoints(),
			io.recoveryNanos() / 1_000_000.0,
			NativeLib.describe());
	}

	/** The live counters. Tests subtract two snapshots to cost an operation; production wants {@link #metrics()}. */
	public IoCounters counters() {
		return counters;
	}

	/** How many auto-commit writes have had to take the write gate exclusively to finish. See {@link #runExclusively}. */
	public long autoCommitEscalations() {
		return autoCommitEscalations.sum();
	}

	/** How many auto-commit attempts have lost a conflict and been re-run. See {@link #asStatement}. */
	public long autoCommitRetries() {
		return autoCommitRetries.sum();
	}

	/**
	 * How many optimistic attempts a statement makes before it takes the write gate exclusively.
	 *
	 * <p>
	 * Exposed so that a test can schedule the escalation instead of waiting for a machine to produce it, and so an
	 * operator reading {@link #autoCommitEscalations()} can see what a single escalation cost in lost attempts.
	 * </p>
	 */
	public static int autoCommitAttempts() {
		return AUTO_COMMIT_ATTEMPTS;
	}

	@Override
	public StoreStats stats() {
		checkOpen();
		int segmentFiles = 0;
		long recordBytes = 0;
		long storageBytes = dictionaryBytes();
		for (SegmentSet set : segmentSets.values()) {
			segmentFiles += set.segmentCount();
			recordBytes += set.recordBytes();
			storageBytes += set.storageBytes();
		}
		return new StoreStats(
			segmentSets.get(Kind.NODE).liveCount(),
			segmentSets.get(Kind.REL).liveCount(),
			segmentSets.get(Kind.ADJ).liveCount(),
			segmentSets.get(Kind.PROP).liveCount(),
			segmentSets.get(Kind.BLOB).liveCount(),
			segmentSets.get(Kind.INDEX).liveCount(),
			catalog.labelCount(),
			catalog.keyCount(),
			segmentFiles,
			recordBytes,
			storageBytes);
	}

	/** Dictionary files are dense, so their real size is their reported size. */
	private long dictionaryBytes() {
		try (Stream<Path> files = Files.list(path.resolve("dict"))) {
			return files.mapToLong(file -> {
				try {
					return Files.size(file);
				} catch (IOException e) {
					return 0L;
				}
			}).sum();
		} catch (IOException e) {
			return 0L;
		}
	}

	public Geometry geometry() {
		return geometry;
	}

	public Catalog catalog() {
		return catalog;
	}

	public SegmentSet segments(Kind kind) {
		return segmentSets.get(kind);
	}

	public Map<Kind, SegmentSet> segmentSets() {
		return segmentSets;
	}

	public NodeStore nodeStore() {
		return nodes;
	}

	/** The vector records, or null in a store created without vector storage. */
	public VectorStore vectorStore() {
		return vectors;
	}

	public RelStore relStore() {
		return rels;
	}

	public AdjacencyStore adjacencyStore() {
		return adjacency;
	}

	public PropertyStore propertyStore() {
		return properties;
	}

	public BlobStore blobStore() {
		return blobs;
	}

	public IndexCatalog indexCatalog() {
		return indexes;
	}

	public WalManager wal() {
		return wal;
	}

	public Path path() {
		return path;
	}

	/**
	 * Releases operating system resources <b>without</b> checkpointing or flushing, as a process crash would. Test
	 * hook: it is what makes an in-process recovery test a real one.
	 */
	public void abandon() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		wal.close();
		for (SegmentSet set : segmentSets.values()) {
			set.abandon();
		}
		manifest.abandon();
		catalog.close();
		lock.release();
		lockChannel.close();
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		// The writer lock, so close can never run beside a transaction that is half applied. A commit copies its pages
		// into the mapping under the apply lock and then reclaims; unmapping a segment part way through that is not a
		// recoverable state, it is a segfault. Taking the lock waits for the in-flight write instead.
		//
		// A caller closing from inside its own transaction holds the gate shared, and a read hold does not upgrade:
		// the acquisition used to wait for the caller forever. That transaction cannot commit after the store is gone,
		// so it is rolled back first, which releases the hold.
		TransactionImpl open = currentTransaction.get();
		if (open != null) {
			open.rollback();
		}
		writeGate.writeLock().lock();
		try {
			// A store being closed on an interrupted thread — the shutdownNow() case — must still be able to write its
			// final checkpoint.
			uninterruptible(() -> {
				closeUnderWriterLock();
				return null;
			});
		} finally {
			writeGate.writeLock().unlock();
		}
	}

	private void closeUnderWriterLock() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		// Retained versions are heap, not files, so nothing about them survives the store. Releasing them here rather
		// than waiting for the store to be collected means a closed store holds no memory it does not need.
		transactions.clear();
		IOException first = null;
		try {
			catalog.force();
			long lsn = wal.checkpoint();
			manifest.checkpoint(lsn, lsn, nextTxnId.get());
			// Durable before the trim, as in checkpoint(): a kill between the two would otherwise leave a manifest
			// naming a checkpoint whose log segment had already been deleted.
			manifest.force();
			wal.trim(lsn);
		} catch (IOException e) {
			first = e;
		}
		try {
			wal.close();
		} catch (IOException e) {
			first = first == null ? e : first;
		}
		for (SegmentSet set : segmentSets.values()) {
			try {
				set.close();
			} catch (IOException e) {
				first = first == null ? e : first;
			}
		}
		try {
			catalog.close();
		} catch (IOException e) {
			first = first == null ? e : first;
		}
		try {
			manifest.close();
		} catch (IOException e) {
			first = first == null ? e : first;
		}
		try {
			lock.release();
		} finally {
			lockChannel.close();
		}
		if (first != null) {
			throw first;
		}
	}
}
