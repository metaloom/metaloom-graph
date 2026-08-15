package io.metaloom.graph.core.api;

import java.util.EnumSet;
import java.util.Set;

import io.metaloom.graph.core.id.Kind;

/**
 * What a compaction run is allowed to do.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 9
 */
public final class CompactionOptions {

	/** Kinds worth compacting by default. {@code UNDO} has no records yet and {@code NULL} is not a store. */
	public static final Set<Kind> DEFAULT_KINDS =
		EnumSet.of(Kind.ADJ, Kind.PROP, Kind.BLOB, Kind.REL, Kind.NODE, Kind.INDEX);

	private final Set<Kind> kinds;
	private final double livenessThreshold;
	private final double indexFillThreshold;
	private final int maxRelocationsPerTransaction;
	private final boolean defragmentAdjacency;
	private final int adjacencyFillThreshold;
	private final boolean retireEmptySegments;
	private final long pauseBetweenBatchesMillis;

	private CompactionOptions(Set<Kind> kinds, double livenessThreshold, double indexFillThreshold,
		int maxRelocationsPerTransaction, boolean defragmentAdjacency, int adjacencyFillThreshold,
		boolean retireEmptySegments, long pauseBetweenBatchesMillis) {
		this.kinds = EnumSet.copyOf(kinds);
		this.livenessThreshold = livenessThreshold;
		this.indexFillThreshold = indexFillThreshold;
		this.maxRelocationsPerTransaction = maxRelocationsPerTransaction;
		this.defragmentAdjacency = defragmentAdjacency;
		this.adjacencyFillThreshold = adjacencyFillThreshold;
		this.retireEmptySegments = retireEmptySegments;
		this.pauseBetweenBatchesMillis = pauseBetweenBatchesMillis;
	}

	public static CompactionOptions defaults() {
		return builder().build();
	}

	/** Compacts regardless of how dense a kind already is. What the tests and an explicit operator request use. */
	public static CompactionOptions always() {
		return builder().livenessThreshold(1.0).indexFillThreshold(1.0).build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public Set<Kind> kinds() {
		return kinds;
	}

	/** A kind is compacted only when its live slots are a smaller fraction of its high water marks than this. */
	public double livenessThreshold() {
		return livenessThreshold;
	}

	/**
	 * Entries per B+tree page, as a fraction of the fan-out, below which the secondary indexes are rebuilt.
	 * <p>
	 * The default is deliberately well under half. A B+tree built by ordinary inserts splits nodes down the middle and
	 * so settles at about fifty percent full by construction, and rebuilding a healthy index on every run would be pure
	 * waste. Only an index that churn has left emptier than it was ever built is worth the rebuild.
	 */
	public double indexFillThreshold() {
		return indexFillThreshold;
	}

	/**
	 * Records relocated per transaction. Each batch is one commit, so this bounds both the memory a compaction holds
	 * and how long a foreground writer can be kept waiting.
	 */
	public int maxRelocationsPerTransaction() {
		return maxRelocationsPerTransaction;
	}

	public boolean defragmentAdjacency() {
		return defragmentAdjacency;
	}

	/** A chain of two or more blocks holding fewer than this many entries per block is rebuilt. */
	public int adjacencyFillThreshold() {
		return adjacencyFillThreshold;
	}

	public boolean retireEmptySegments() {
		return retireEmptySegments;
	}

	/** Pause between batches, which is what keeps a long compaction from starving foreground writers. */
	public long pauseBetweenBatchesMillis() {
		return pauseBetweenBatchesMillis;
	}

	public static final class Builder {

		private Set<Kind> kinds = DEFAULT_KINDS;
		private double livenessThreshold = 0.5;
		private double indexFillThreshold = 0.25;
		private int maxRelocationsPerTransaction = 4096;
		private boolean defragmentAdjacency = true;
		private int adjacencyFillThreshold = 4096;
		private boolean retireEmptySegments = true;
		private long pauseBetweenBatchesMillis = 0;

		public Builder kinds(Set<Kind> kinds) {
			this.kinds = kinds;
			return this;
		}

		public Builder kinds(Kind... kinds) {
			Set<Kind> set = EnumSet.noneOf(Kind.class);
			for (Kind kind : kinds) {
				set.add(kind);
			}
			this.kinds = set;
			return this;
		}

		public Builder livenessThreshold(double livenessThreshold) {
			this.livenessThreshold = livenessThreshold;
			return this;
		}

		public Builder indexFillThreshold(double indexFillThreshold) {
			this.indexFillThreshold = indexFillThreshold;
			return this;
		}

		public Builder maxRelocationsPerTransaction(int maxRelocationsPerTransaction) {
			this.maxRelocationsPerTransaction = maxRelocationsPerTransaction;
			return this;
		}

		public Builder defragmentAdjacency(boolean defragmentAdjacency) {
			this.defragmentAdjacency = defragmentAdjacency;
			return this;
		}

		public Builder adjacencyFillThreshold(int adjacencyFillThreshold) {
			this.adjacencyFillThreshold = adjacencyFillThreshold;
			return this;
		}

		public Builder retireEmptySegments(boolean retireEmptySegments) {
			this.retireEmptySegments = retireEmptySegments;
			return this;
		}

		public Builder pauseBetweenBatchesMillis(long pauseBetweenBatchesMillis) {
			this.pauseBetweenBatchesMillis = pauseBetweenBatchesMillis;
			return this;
		}

		public CompactionOptions build() {
			return new CompactionOptions(kinds, livenessThreshold, indexFillThreshold, maxRelocationsPerTransaction,
				defragmentAdjacency, adjacencyFillThreshold, retireEmptySegments, pauseBetweenBatchesMillis);
		}
	}
}
