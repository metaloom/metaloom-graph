package io.metaloom.graph.core.api;

import java.util.Map;


/**
 * What one compaction run actually did.
 * <p>
 * Compaction is the only operation in the engine that invalidates ids, so it reports rather than logs: a caller holding
 * ids across a compaction needs to know that records moved, and how many.
 *
 * @param relocated
 *            records moved, per kind
 * @param batches
 *            transactions the run committed, one per batch
 * @param adjacencyBlocksFreed
 *            blocks given back by rebuilding churned adjacency chains
 * @param indexPagesFreed
 *            B+tree pages given back by rebuilding the secondary indexes
 * @param slotsReclaimed
 *            slots the high water marks came down by, which is what shrinks every full scan
 * @param segmentsRetired
 *            segment files unlinked because they ended up empty
 * @param pagesPunched
 *            all-free pages whose blocks were returned to the filesystem afterwards
 * @param livenessBefore
 *            live slots over high water slots across the compacted kinds, before
 * @param livenessAfter
 *            the same fraction after
 * @param storageBytesBefore
 *            {@link StoreStats#storageBytes()} before
 * @param storageBytesAfter
 *            {@link StoreStats#storageBytes()} after
 * @param durationMillis
 *            wall clock, pauses included
 */
public record CompactionReport(
	Map<Kind, Long> relocated,
	int batches,
	long adjacencyBlocksFreed,
	long indexPagesFreed,
	long slotsReclaimed,
	int segmentsRetired,
	int pagesPunched,
	double livenessBefore,
	double livenessAfter,
	long storageBytesBefore,
	long storageBytesAfter,
	long durationMillis) {

	public long totalRelocated() {
		return relocated.values().stream().mapToLong(Long::longValue).sum();
	}

	public long storageBytesReclaimed() {
		return storageBytesBefore - storageBytesAfter;
	}

	@Override
	public String toString() {
		return "CompactionReport[relocated=" + relocated + ", batches=" + batches
			+ ", adjacencyBlocksFreed=" + adjacencyBlocksFreed + ", indexPagesFreed=" + indexPagesFreed
			+ ", slotsReclaimed=" + slotsReclaimed + ", segmentsRetired=" + segmentsRetired
			+ ", pagesPunched=" + pagesPunched
			+ ", liveness=" + String.format("%.3f -> %.3f", livenessBefore, livenessAfter)
			+ ", storageBytes=" + storageBytesBefore + " -> " + storageBytesAfter
			+ ", " + durationMillis + " ms]";
	}
}
