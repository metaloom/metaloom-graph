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
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotStats;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.StoreClosedException;
import io.metaloom.graph.core.api.StoreMetrics;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.VerifyOptions;
import io.metaloom.graph.core.catalog.Catalog;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
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
	private final IndexCatalog indexes;
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
	private boolean closed;

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
		this.properties = new PropertyStore(segmentSets.get(Kind.PROP), blobs);
		this.nodes = new NodeStore(segmentSets.get(Kind.NODE), properties);
		this.rels = new RelStore(segmentSets.get(Kind.REL));
		this.adjacency = new AdjacencyStore(segmentSets.get(Kind.ADJ), nodes);
		this.indexes = new IndexCatalog(segmentSets.get(Kind.INDEX), catalog);

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
			manifest = Manifest.open(path, options.pageSize(), options.segmentCapacity());
			Geometry geometry = manifest.geometry();
			catalog = new Catalog(path);
			for (Kind kind : new Kind[] { Kind.NODE, Kind.REL, Kind.ADJ, Kind.PROP, Kind.BLOB, Kind.INDEX }) {
				sets.put(kind, new SegmentSet(path, kind, geometry));
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
		checkOpen();
		if (writerLock.isHeldByCurrentThread() && currentTransaction != null) {
			unit.run();
			return;
		}
		// A maintenance scan is long and rare, so it takes the lock outright rather than gambling on optimism.
		Snapshot snapshot = transactions.holder().current();
		long stamp = applyLock.readLock();
		try {
			if (snapshot != null) {
				snapshot.exclusionHeld(true);
			}
			unit.run();
		} finally {
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
			checkUnique(keyed, ElementId.NULL);
			long nodeId = nodes.create(labelIds, ElementId.NULL);
			if (props != null && !props.isEmpty()) {
				nodes.propBlockId(nodeId, properties.store(keyed, nodeId));
			}
			indexes.onNodeCreated(nodeId, labelIds, keyed);
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

	/**
	 * Rejects a write that would put two live elements on the same value of a unique key.
	 * <p>
	 * The index answers this in one probe, and the candidates it returns are verified against the stored property
	 * before anything is refused — the index is keyed on a value hash, so an unverified conflict could be a collision
	 * between two different values. Refusing a legitimate write is as bad as accepting an illegitimate one.
	 */
	private void checkUnique(Map<Integer, Object> properties, long excluding) {
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
				if (java.util.Objects.deepEquals(stored, normalise(entry.getValue()))) {
					throw new ConstraintViolationException(catalog.key(entry.getKey()), entry.getValue(), other);
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
			Map<Integer, Object> before = properties.read(old);
			Map<Integer, Object> after = toKeyIds(props);
			nodes.propBlockId(nodeId, properties.store(after, nodeId));
			properties.free(old);
			checkUnique(after, nodeId);
			indexes.onPropertiesChanged(nodeId, before, after);
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
			long entry = PackedEdge.ofRelationship(relId);
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
		long entry = PackedEdge.ofRelationship(relationshipId);
		if (nodes.exists(fromId)) {
			adjacency.remove(fromId, entry);
			nodes.degreeDelta(fromId, -1, 0);
		}
		if (nodes.exists(toId)) {
			if (toId != fromId) {
				adjacency.remove(toId, entry);
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

	@Override
	public List<Edge> neighbours(long nodeId, Direction direction, Set<String> types) {
		return read(() -> {
			requireNode(nodeId);
			Set<Integer> typeIds = resolveTypes(types);
			List<Edge> result = new ArrayList<>();
			for (long packed : adjacency.edges(nodeId)) {
				materialise(nodeId, packed, direction, typeIds, result);
			}
			return result;
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
				for (long packed : adjacency.edges(nodeId)) {
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
		// runRead rather than read: a scan is long, so it takes the latch outright instead of gambling on an
		// optimistic pass it would usually lose, and it must not be run twice — the consumer has side effects.
		try {
			runRead(() -> segmentSets.get(Kind.NODE).forEachLiveId(consumer));
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
				if (java.util.Objects.deepEquals(stored, normalise(value))) {
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
	 * self-loop. A materialised entry costs one extra 64 byte read, which is the right place to pay it since its
	 * properties live there too.
	 */
	private void materialise(long fromNodeId, long packed, Direction direction, Set<Integer> typeIds,
		List<Edge> out) {
		if (PackedEdge.isMaterialised(packed)) {
			long relId = liveRelationshipId(PackedEdge.relationshipId(packed));
			if (ElementId.isNull(relId)) {
				return;
			}
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
		int typeId = PackedEdge.typeId(packed);
		if (typeIds != null && !typeIds.contains(typeId)) {
			return;
		}
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
			catalog.force();
			long lsn = wal.checkpoint();
			manifest.checkpoint(lsn, lsn, nextTxnId);
			manifest.force();
			wal.trim(lsn);
			lastCheckpointLsn = lsn;
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

		Compactor.Result result = new Compactor(segmentSets, nodes, rels, adjacency, properties, blobs, indexes, this)
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

	@Override
	public void verify(VerifyLevel level, VerifyOptions options) {
		checkOpen();
		long[] stamp = { applyLock.readLock() };
		try {
			for (SegmentSet set : segmentSets.values()) {
				set.verify();
			}
			if (level == VerifyLevel.FULL) {
				List<String> violations = new InvariantChecker(nodes, rels, adjacency, properties, blobs, segmentSets)
					.check(options, pacer(options, stamp));
				if (!violations.isEmpty()) {
					throw new GraphStoreException("Structural invariants violated (" + violations.size()
						+ "):\n  " + String.join("\n  ", violations));
				}
			}
		} finally {
			applyLock.unlockRead(stamp[0]);
		}
	}

	/**
	 * Drops the read latch between batches so a writer waiting behind a long verification waits for a batch instead.
	 * The scan therefore no longer sees a single instant; {@link VerifyOptions} documents what that costs.
	 */
	private InvariantChecker.Pacer pacer(VerifyOptions options, long[] stamp) {
		if (!options.isThrottled()) {
			return InvariantChecker.Pacer.NONE;
		}
		return () -> {
			applyLock.unlockRead(stamp[0]);
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
			// Retaken unconditionally, including on interrupt: the caller's finally block will unlock it.
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
