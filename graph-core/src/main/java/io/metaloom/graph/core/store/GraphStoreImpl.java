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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
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
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotStats;
import io.metaloom.graph.core.api.SnapshotTooOldException;
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
import io.metaloom.graph.core.page.OverlayHolder;
import io.metaloom.graph.core.page.PageOverlay;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.segment.SegmentFile;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.store.adj.AdjacencyStore;
import io.metaloom.graph.core.store.adj.PackedEdge;
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
public final class GraphStoreImpl implements GraphStore, TransactionImpl.Completion, MaintenanceRunner {

	private static final Logger logger = LoggerFactory.getLogger(GraphStoreImpl.class);

	private final Path path;
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
	private final OverlayHolder overlays = new OverlayHolder();
	private final TransactionManager transactions;
	private final IoCounters counters;

	private final FileChannel lockChannel;
	private final FileLock lock;

	/** One writer at a time. Readers never take it. */
	private final ReentrantLock writerLock = new ReentrantLock();
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

	private TransactionImpl currentTransaction;

	/**
	 * The LSN of the newest commit whose pages are in the mapping, which is what a new read view is fixed at.
	 * <p>
	 * Written under the apply lock and read without it, so it is volatile. It is deliberately not {@code durableLsn}:
	 * durability and visibility are different instants, and a view must be fixed at what it can actually see.
	 */
	private volatile long appliedLsn;

	private long nextTxnId;
	private long lastCheckpointLsn;
	/**
	 * Volatile because {@code checkOpen()} reads it on every operation from every thread, while {@code close()} writes
	 * it under the writer lock — which readers never take. In practice the surrounding lock traffic publishes it, and
	 * {@code CloseUnderLoadTest} does not catch the difference; it is volatile so that the guarantee does not rest on
	 * that happening to be true.
	 */
	private volatile boolean closed;

	private GraphStoreImpl(Path path, Geometry geometry, Manifest manifest, Catalog catalog,
		Map<Kind, SegmentSet> segmentSets, WalManager wal, FileChannel lockChannel, FileLock lock,
		IoCounters counters, long retainedVersionBytes) {
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
		this.nextTxnId = manifest.nextTxnId();
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

		for (SegmentSet set : segmentSets.values()) {
			set.overlays(overlays);
			set.snapshots(transactions.holder());
			set.counters(counters);
		}
		wal.counters(counters);
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
				counters, options.retainedVersionBytes());
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
		checkOpen();
		// Refused rather than allowed to nest. The writer lock is reentrant, so nesting used to succeed and then lose
		// data silently: the inner transaction publishes its own overlay, and committing it clears that overlay while
		// the outer transaction is still live, after which every remaining write in the outer transaction goes straight
		// to the mapping — unlogged, and past the point where a rollback could take it back. A caller that wants to join
		// an existing transaction asks with hasWriteTransaction() and simply does not call begin().
		if (writerLock.isHeldByCurrentThread() && currentTransaction != null) {
			throw new GraphStoreException("A write transaction (id " + currentTransaction.id()
				+ ") is already open on thread '" + Thread.currentThread().getName()
				+ "'. Nested write transactions are not supported: commit or roll back the open one first, or test"
				+ " hasWriteTransaction() and join it instead of beginning another.");
		}
		writerLock.lock();
		try {
			TransactionImpl txn = new TransactionImpl(nextTxnId++, geometry.pageSize(), this);
			overlays.set(txn.overlay());
			currentTransaction = txn;
			return txn;
		} catch (RuntimeException e) {
			writerLock.unlock();
			throw e;
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

	private long commitInternal(TransactionImpl txn) throws IOException {
		try {
			// Counters live in the segment headers; flushing them inside the transaction makes them part of the
			// atomic unit rather than a separate, unlogged write.
			for (SegmentSet set : segmentSets.values()) {
				for (SegmentFile segment : set.segments()) {
					if (segment.superblock().counterDirty()) {
						segment.superblock().flush();
					}
				}
			}
			// Dictionary ids are embedded in the records being committed, so they must be durable first.
			catalog.force();
			WalManager.CommitBatch batch = wal.logCommit(txn.id(), txn.overlay());
			long stamp = applyLock.writeLock();
			try {
				retainVersions(batch);
				wal.apply(batch);
				appliedLsn = batch.commitLsn();
			} finally {
				applyLock.unlockWrite(stamp);
			}
			// Outside the apply lock: reclamation only reads the active set and unlinks dead chain entries, and it can
			// walk every versioned page, which is far too long to hold readers out for.
			transactions.vacuum(batch.commitLsn());
			return batch.commitLsn();
		} finally {
			endTransaction();
		}
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
	 * opens one never allocates a byte here and commit is exactly as fast as it was before MVCC existed.
	 * </p>
	 */
	private void retainVersions(WalManager.CommitBatch batch) {
		if (!transactions.needsVersions()) {
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
		long stamp = applyLock.readLock();
		try {
			return transactions.begin(level, appliedLsn, System.nanoTime());
		} finally {
			applyLock.unlockRead(stamp);
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
		return writerLock.isHeldByCurrentThread() && currentTransaction != null;
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
		GraphStoreException last = null;
		for (int attempt = 1; attempt <= attempts; attempt++) {
			try (Transaction txn = begin()) {
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
		try {
			txn.overlay().clear();
			for (SegmentSet set : segmentSets.values()) {
				set.rollbackState();
			}
		} finally {
			endTransaction();
		}
	}

	private void endTransaction() {
		overlays.clear();
		currentTransaction = null;
		if (writerLock.isHeldByCurrentThread()) {
			writerLock.unlock();
		}
	}

	/** Runs the operation inside the caller's transaction if there is one, otherwise in an auto-commit transaction. */
	private <T> T write(WriteOp<T> op) throws IOException {
		checkOpen();
		if (writerLock.isHeldByCurrentThread() && currentTransaction != null) {
			return op.run();
		}
		try (Transaction txn = begin()) {
			T result = op.run();
			txn.commit();
			return result;
		}
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
	private <T> T read(Supplier<T> op) {
		checkOpen();
		Snapshot snapshot = transactions.holder().current();
		if (snapshot != null) {
			// Once per operation rather than once per page: a view the horizon has passed is wrong everywhere, not
			// only on the pages it has yet to touch.
			snapshot.checkAlive();
		}
		if (writerLock.isHeldByCurrentThread() && currentTransaction != null) {
			// Inside a write transaction the overlay already gives a consistent view.
			return op.get();
		}
		long stamp = applyLock.tryOptimisticRead();
		if (stamp != 0) {
			try {
				T result = op.get();
				if (applyLock.validate(stamp)) {
					return result;
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
		long readStamp = applyLock.readLock();
		try {
			if (snapshot != null) {
				snapshot.exclusionHeld(true);
			}
			return op.get();
		} finally {
			if (snapshot != null) {
				snapshot.exclusionHeld(false);
			}
			applyLock.unlockRead(readStamp);
		}
	}

	@FunctionalInterface
	private interface WriteOp<T> {
		T run() throws IOException;
	}

	@Override
	public void runWrite(MaintenanceRunner.Unit unit) throws IOException {
		write(() -> {
			unit.run();
			return null;
		});
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
		private final long stamp;
		private boolean held;

		ReadLease() {
			checkOpen();
			Snapshot current = transactions.holder().current();
			if (current != null) {
				current.checkAlive();
			}
			if (writerLock.isHeldByCurrentThread() && currentTransaction != null) {
				this.snapshot = null;
				this.stamp = 0;
				this.held = false;
				return;
			}
			this.snapshot = current;
			this.stamp = applyLock.readLock();
			this.held = true;
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
			if (snapshot != null) {
				snapshot.exclusionHeld(false);
			}
			applyLock.unlockRead(stamp);
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
	 * Declares a property key unique across live nodes.
	 * <p>
	 * Checks the existing data first and refuses rather than declaring something that is already false.
	 */
	@Override
	public void createUniqueConstraint(String key) throws IOException {
		write(() -> {
			int keyId = catalog.internKey(key);
			Map<Object, Long> seen = new java.util.HashMap<>();
			for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
				Object value = properties.read(nodes.propBlockId(nodeId), keyId);
				if (value == null) {
					continue;
				}
				Object normalised = value instanceof byte[] bytes ? java.util.Arrays.toString(bytes) : value;
				Long previous = seen.put(normalised, nodeId);
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
		write(() -> {
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
		write(() -> {
			int labelId = catalog.internLabel(label);
			int keyId = catalog.internKey(key);
			Map<Object, Long> seen = new java.util.HashMap<>();
			for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
				if (!nodes.hasLabel(nodeId, labelId)) {
					continue;
				}
				Object value = properties.read(nodes.propBlockId(nodeId), keyId);
				if (value == null) {
					continue;
				}
				Object normalised = value instanceof byte[] bytes ? java.util.Arrays.toString(bytes) : value;
				Long previous = seen.put(normalised, nodeId);
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
		write(() -> {
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
		write(() -> {
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

	@Override
	public void buildVectorIndex(String label, String key) throws IOException {
		int ord = indexes.vectorIndexOrdinal(label, key);
		if (ord < 0) {
			throw new IllegalArgumentException("No vector index on :" + label + "." + key);
		}
		VectorIndexDefinition definition = indexes.vectorIndexes().get(ord);
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

	@Override
	public void dropVectorIndex(String label, String key) throws IOException {
		write(() -> {
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
		int ord = indexes.vectorIndexOrdinal(label, key);
		if (ord < 0) {
			throw new IllegalStateException("No vector index on :" + label + "." + key
				+ ". Declare one with createVectorIndex, or use nearestByScan for an exact answer without one.");
		}
		VectorIndexDefinition definition = indexes.vectorIndexes().get(ord);
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
		return read(() -> fromKeyIds(properties.read(nodes.propBlockId(nodeId))));
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
			nodes.propBlockId(nodeId, properties.store(after, nodeId));
			properties.free(old);
			checkUnique(labelIds, after, nodeId);
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
						deleteRelationshipInternal(relId);
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

	@Override
	public long createRelationship(long fromId, String type, long toId, Properties props) throws IOException {
		return write(() -> {
			requireNode(fromId);
			requireNode(toId);
			int typeId = catalog.internEdgeType(type);
			long relId = rels.create(fromId, typeId, toId, ElementId.NULL);
			if (props != null && !props.isEmpty()) {
				rels.propBlockId(relId, properties.store(toKeyIds(props), relId));
			}
			// The entry caches the type, which is what lets a type-filtered traversal skip it without the random read
			// its record costs. Direction is not cacheable — see PackedEdge.
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
		});
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
		return read(() -> fromKeyIds(properties.read(rels.propBlockId(relationshipId))));
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
		long fromId = rels.fromId(relationshipId);
		long toId = rels.toId(relationshipId);
		// Matched by the relationship it names rather than by its whole packed value: the type field of a materialised
		// entry is a cache, and a store written before the cache existed holds zero there where a fresh one holds the
		// type. Matching on the exact bits would leave those entries behind as dangling adjacency.
		if (nodes.exists(fromId)) {
			adjacency.removeMaterialised(fromId, relationshipId);
			nodes.degreeDelta(fromId, -1, 0);
		}
		if (nodes.exists(toId)) {
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
		private final Set<Integer> typeIds;
		private final ReadLease lease;
		private final io.metaloom.graph.core.store.adj.EdgeCursor entries;
		private final List<Edge> pending = new ArrayList<>(2);

		private int index;
		private Edge current;

		NeighbourWalk(long nodeId, Direction direction, Set<Integer> typeIds, ReadLease lease) {
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
			Set<Integer> typeIds = resolveTypes(types);
			List<Edge> result = new ArrayList<>();
			Set<Long> visited = new HashSet<>();
			visited.add(startNodeId);
			Deque<long[]> queue = new ArrayDeque<>();
			queue.add(new long[] { startNodeId, 0 });
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
					int ahead = 0;
					for (long[] queued : queue) {
						if (ahead == frontier.length) {
							break;
						}
						frontier[ahead++] = queued[0];
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
							queue.add(new long[] { neighbour, depth + 1 });
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
		// runRead rather than read: a scan is long, so it takes the latch outright instead of gambling on an
		// optimistic pass it would usually lose, and it must not be run twice — the consumer has side effects.
		try {
			boolean[] complete = { true };
			runRead(() -> complete[0] = segmentSets.get(Kind.NODE).forEachLiveIdWhile(consumer));
			return complete[0];
		} catch (IOException e) {
			throw new GraphStoreException("Node scan failed", e);
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
	private void materialise(long fromNodeId, long packed, Direction direction, Set<Integer> typeIds,
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
			// did is being confirmed against the authority its cache stands in for.
			int typeId = rels.typeId(relId);
			if (typeIds != null && !typeIds.contains(typeId)) {
				return;
			}
			long relFrom = rels.fromId(relId);
			long relTo = rels.toId(relId);
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

	private Set<Integer> resolveTypes(Set<String> types) {
		if (types == null || types.isEmpty()) {
			return null;
		}
		Set<Integer> ids = new HashSet<>();
		for (String type : types) {
			int id = catalog.lookupLabel(type);
			if (id != 0) {
				ids.add(id);
			}
		}
		return ids;
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

	private Properties fromKeyIds(Map<Integer, Object> values) {
		Properties props = Properties.of();
		for (Map.Entry<Integer, Object> entry : values.entrySet()) {
			String key = catalog.key(entry.getKey());
			if (key != null) {
				props.set(key, entry.getValue());
			}
		}
		return props;
	}

	// ---------------------------------------------------------------- maintenance

	@Override
	public void checkpoint() throws IOException {
		checkOpen();
		writerLock.lock();
		long started = System.nanoTime();
		try {
			// Same reason as commit(): an interrupt reaching the channel closes it for everybody.
			uninterruptible(() -> {
				catalog.force();
				long lsn = wal.checkpoint();
				manifest.checkpoint(lsn, lsn, nextTxnId);
				manifest.force();
				wal.trim(lsn);
				lastCheckpointLsn = lsn;
				return null;
			});
		} finally {
			counters.checkpointed(System.nanoTime() - started);
			writerLock.unlock();
		}
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
		refuseWhileViewsAreOpen("reclaim()");
		writerLock.lock();
		try {
			int punched = 0;
			for (SegmentSet set : segmentSets.values()) {
				punched += set.reclaim();
			}
			return punched;
		} finally {
			writerLock.unlock();
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
		writerLock.lock();
		try {
			// Reentrant: a caller verifying from inside its own open transaction still holds it, and must not have it
			// released underneath by the throttling pacer.
			boolean ownsWriterLock = writerLock.getHoldCount() == 1;
			long[] stamp = { applyLock.readLock() };
			try {
				for (SegmentSet set : segmentSets.values()) {
					set.verify();
				}
				if (level == VerifyLevel.FULL) {
					List<String> violations = new InvariantChecker(nodes, rels, adjacency, properties, blobs, vectors, segmentSets)
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
			writerLock.unlock();
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
				writerLock.unlock();
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
				writerLock.lock();
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
		// recoverable state, it is a segfault. Taking the lock waits for the in-flight write instead — and because the
		// lock is reentrant, a caller closing from inside its own transaction is not deadlocked by this, it simply
		// closes a store whose transaction is about to be rolled back by the overlay being discarded.
		writerLock.lock();
		try {
			// A store being closed on an interrupted thread — the shutdownNow() case — must still be able to write its
			// final checkpoint.
			uninterruptible(() -> {
				closeUnderWriterLock();
				return null;
			});
		} finally {
			writerLock.unlock();
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
			manifest.checkpoint(lsn, lsn, nextTxnId);
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
