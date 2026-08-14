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

	private final int pageSize;
	private final long segmentCapacity;
	private final long walSegmentBytes;
	private final WalSyncMode walSyncMode;
	private final long walSyncIntervalMillis;

	private GraphStoreOptions(int pageSize, long segmentCapacity, long walSegmentBytes, WalSyncMode walSyncMode,
		long walSyncIntervalMillis) {
		this.pageSize = pageSize;
		this.segmentCapacity = segmentCapacity;
		this.walSegmentBytes = walSegmentBytes;
		this.walSyncMode = walSyncMode;
		this.walSyncIntervalMillis = walSyncIntervalMillis;
	}

	public static GraphStoreOptions defaults() {
		return new GraphStoreOptions(Geometry.DEFAULT_PAGE_SIZE, Geometry.DEFAULT_SEGMENT_CAPACITY,
			DEFAULT_WAL_SEGMENT_BYTES, WalSyncMode.FSYNC_ON_COMMIT, 20);
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

	public static final class Builder {

		private int pageSize = Geometry.DEFAULT_PAGE_SIZE;
		private long segmentCapacity = Geometry.DEFAULT_SEGMENT_CAPACITY;
		private long walSegmentBytes = DEFAULT_WAL_SEGMENT_BYTES;
		private WalSyncMode walSyncMode = WalSyncMode.FSYNC_ON_COMMIT;
		private long walSyncIntervalMillis = 20;

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

		public GraphStoreOptions build() {
			return new GraphStoreOptions(pageSize, segmentCapacity, walSegmentBytes, walSyncMode,
				walSyncIntervalMillis);
		}
	}
}
