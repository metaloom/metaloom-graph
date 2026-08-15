package io.metaloom.graph.core.api;

import io.metaloom.graph.core.segment.Geometry;

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
		long walSyncIntervalMillis, IoEngineKind ioEngine, long retainedVersionBytes) {
		this.retainedVersionBytes = retainedVersionBytes;
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
			DEFAULT_RETAINED_VERSION_BYTES);
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

	public static final class Builder {

		private int pageSize = Geometry.DEFAULT_PAGE_SIZE;
		private long segmentCapacity = Geometry.DEFAULT_SEGMENT_CAPACITY;
		private long walSegmentBytes = DEFAULT_WAL_SEGMENT_BYTES;
		private WalSyncMode walSyncMode = WalSyncMode.FSYNC_ON_COMMIT;
		private long walSyncIntervalMillis = 20;
		private IoEngineKind ioEngine = IoEngineKind.AUTO;
		private long retainedVersionBytes = DEFAULT_RETAINED_VERSION_BYTES;

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

		public GraphStoreOptions build() {
			return new GraphStoreOptions(pageSize, segmentCapacity, walSegmentBytes, walSyncMode,
				walSyncIntervalMillis, ioEngine, retainedVersionBytes);
		}
	}
}
