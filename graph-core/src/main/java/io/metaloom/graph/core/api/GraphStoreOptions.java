package io.metaloom.graph.core.api;

import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.vec.VectorRecord;

/**
 * Store configuration. Options that affect the on-disk format are recorded in the manifest and refused on reopen if
 * they conflict.
 *
 * @see <a href="../../../../../../../../spec/ARCHITECTURE.md">spec/ARCHITECTURE.md</a> section 8
 */
public final class GraphStoreOptions {

	public static final long DEFAULT_WAL_SEGMENT_BYTES = 64L * 1024 * 1024;

	/** Retained superseded page content past which the oldest open read views are abandoned. */
	public static final long DEFAULT_RETAINED_VERSION_BYTES = 256L * 1024 * 1024;

	private final int pageSize;
	private final long segmentCapacity;
	private final IoEngineKind ioEngine;
	private final long walSegmentBytes;
	private final WalSyncMode walSyncMode;
	private final long walSyncIntervalMillis;
	private final long retainedVersionBytes;
	private final int vectorDimensions;

	/**
	 * Which {@link io.metaloom.graph.core.io.IoEngine} the store uses.
	 * <p>
	 * The data path is memory mapped whichever is chosen; what this selects is how the <b>write-ahead log</b> reaches
	 * the device. See spec/NATIVE_FFM.md section 5.
	 */
	public enum IoEngineKind {
		/** Buffered positional writes through {@code FileChannel}. Works everywhere. */
		MMAP,
		/** {@code O_DIRECT}, so the log stops filling the page cache with bytes nothing will read again. */
		DIRECT,
		/** {@link #DIRECT} where the platform and filesystem support it, {@link #MMAP} otherwise. */
		AUTO
	}

	private GraphStoreOptions(int pageSize, long segmentCapacity, long walSegmentBytes, WalSyncMode walSyncMode,
		long walSyncIntervalMillis, IoEngineKind ioEngine, long retainedVersionBytes, int vectorDimensions) {
		this.retainedVersionBytes = retainedVersionBytes;
		this.vectorDimensions = vectorDimensions;
		this.pageSize = pageSize;
		this.segmentCapacity = segmentCapacity;
		this.walSegmentBytes = walSegmentBytes;
		this.walSyncMode = walSyncMode;
		this.walSyncIntervalMillis = walSyncIntervalMillis;
		this.ioEngine = ioEngine;
	}

	public IoEngineKind ioEngine() {
		return ioEngine;
	}

	public static GraphStoreOptions defaults() {
		return new GraphStoreOptions(Geometry.DEFAULT_PAGE_SIZE, Geometry.DEFAULT_SEGMENT_CAPACITY,
			DEFAULT_WAL_SEGMENT_BYTES, WalSyncMode.FSYNC_ON_COMMIT, 20, IoEngineKind.AUTO,
			DEFAULT_RETAINED_VERSION_BYTES, 0);
	}

	public static Builder builder() {
		return new Builder();
	}

	public int pageSize() {
		return pageSize;
	}

	public long segmentCapacity() {
		return segmentCapacity;
	}

	/** Size of one preallocated write-ahead log segment file. */
	public long walSegmentBytes() {
		return walSegmentBytes;
	}

	public WalSyncMode walSyncMode() {
		return walSyncMode;
	}

	/** Upper bound on how much recent work {@link WalSyncMode#GROUP_COMMIT} may discard. */
	public long walSyncIntervalMillis() {
		return walSyncIntervalMillis;
	}

	/**
	 * How much superseded page content the store keeps so that open {@link IsolationLevel#SNAPSHOT} views can still be
	 * reconstructed.
	 * <p>
	 * Nothing is retained while no such view is open, so this budget is only ever reached by a reader that stays open
	 * across a lot of writing. Past it the oldest views are abandoned and fail with
	 * {@link SnapshotTooOldException} — see spec/TRANSACTIONS.md section 5.1.
	 */
	public long retainedVersionBytes() {
		return retainedVersionBytes;
	}

	/**
	 * The vector dimension this store holds, or 0 for a store with no vector storage.
	 * <p>
	 * Fixed at creation, like the page size, and for a related reason: {@code Geometry} derives the whole {@code VEC}
	 * layout from it, and crash recovery has to be able to build that segment set <b>before</b> it replays anything,
	 * so the number cannot live anywhere that is itself recovered.
	 */
	public int vectorDimensions() {
		return vectorDimensions;
	}

	public static final class Builder {

		private int pageSize = Geometry.DEFAULT_PAGE_SIZE;
		private long segmentCapacity = Geometry.DEFAULT_SEGMENT_CAPACITY;
		private long walSegmentBytes = DEFAULT_WAL_SEGMENT_BYTES;
		private WalSyncMode walSyncMode = WalSyncMode.FSYNC_ON_COMMIT;
		private long walSyncIntervalMillis = 20;
		private IoEngineKind ioEngine = IoEngineKind.AUTO;
		private long retainedVersionBytes = DEFAULT_RETAINED_VERSION_BYTES;
		private int vectorDimensions = 0;

		/** Fixed at store creation. 4096 suits random-heavy loads, 16384 scan-heavy ones. */
		public Builder pageSize(int pageSize) {
			this.pageSize = pageSize;
			return this;
		}

		/**
		 * Maximum data bytes of one segment file. Smaller values make segment rollover happen sooner, which is what
		 * the tests use; production stores want the 4 GiB default.
		 */
		public Builder segmentCapacity(long segmentCapacity) {
			this.segmentCapacity = segmentCapacity;
			return this;
		}

		/** Smaller values make log rotation reachable in tests. */
		public Builder walSegmentBytes(long walSegmentBytes) {
			this.walSegmentBytes = walSegmentBytes;
			return this;
		}

		/** Durability of a commit. Anything other than the default trades recent commits for throughput. */
		public Builder walSyncMode(WalSyncMode walSyncMode) {
			this.walSyncMode = walSyncMode;
			return this;
		}

		public Builder walSyncIntervalMillis(long walSyncIntervalMillis) {
			this.walSyncIntervalMillis = walSyncIntervalMillis;
			return this;
		}

		/** Defaults to {@link IoEngineKind#AUTO}. */
		public Builder ioEngine(IoEngineKind ioEngine) {
			this.ioEngine = ioEngine;
			return this;
		}

		/**
		 * Budget for retained snapshot versions. Small values make the abandonment path reachable in tests; a store
		 * that never opens a snapshot never allocates any of it.
		 */
		public Builder retainedVersionBytes(long retainedVersionBytes) {
			this.retainedVersionBytes = retainedVersionBytes;
			return this;
		}

		/**
		 * Gives the store vector storage of exactly this dimension, fixed for its lifetime.
		 * <p>
		 * A vector property of this length is then stored as a {@code VEC} record — one page read to fetch, and room
		 * in the same record for the links an ANN index will later hang off it. A vector of any <em>other</em> length
		 * still goes to the blob store, because it cannot fit the fixed record.
		 * </p>
		 * <p>
		 * Zero, the default, means no vector storage at all: no segment directory, no format change, and every vector
		 * property behaves exactly as it did before.
		 * </p>
		 */
		public Builder vectorDimensions(int vectorDimensions) {
			this.vectorDimensions = vectorDimensions;
			return this;
		}

		public GraphStoreOptions build() {
			if (vectorDimensions < 0) {
				throw new IllegalArgumentException("vectorDimensions cannot be negative: " + vectorDimensions);
			}
			if (vectorDimensions > 0 && VectorRecord.strideFor(vectorDimensions, pageSize) == 0) {
				throw new IllegalArgumentException("A vector of " + vectorDimensions + " dimensions needs "
					+ (VectorRecord.HEADER_BYTES + 4 * vectorDimensions + 8 * VectorRecord.MIN_LINK_SLOTS)
					+ " bytes with its links, which does not fit a " + pageSize
					+ " byte page. Raise pageSize, or lower the dimension.");
			}
			return new GraphStoreOptions(pageSize, segmentCapacity, walSegmentBytes, walSyncMode,
				walSyncIntervalMillis, ioEngine, retainedVersionBytes, vectorDimensions);
		}
	}
}
