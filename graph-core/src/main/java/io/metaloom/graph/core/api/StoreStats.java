package io.metaloom.graph.core.api;

/**
 * Introspection counters.
 * <p>
 * The byte counts are computed from the engine's own accounting rather than from filesystem block usage: the JDK does
 * not expose the allocated block count of a sparse file. The native {@code statx} binding of Phase 6 adds true on-disk
 * accounting; see spec/NATIVE_FFM.md section 2.
 *
 * @param nodeCount
 *            live node records
 * @param relationshipCount
 *            live relationship records (lightweight edges have none)
 * @param adjacencyCellCount
 *            live adjacency cells. A block is a run of one or more, so this is an upper bound on the block count and
 *            an exact measure of the space adjacency occupies
 * @param propertyCellCount
 *            live property cells
 * @param blobExtentCount
 *            live blob extents
 * @param indexPageCount
 *            live B+tree pages across the secondary indexes
 * @param labelCount
 *            distinct interned labels and relationship types
 * @param keyCount
 *            distinct interned property keys
 * @param segmentFileCount
 *            segment files across every kind
 * @param recordBytes
 *            bytes of live records, the pure payload
 * @param storageBytes
 *            bytes the store has actually committed: per segment, the metadata regions plus everything up to the high
 *            water mark, plus the dictionaries. Far below the nominal segment size, which is sparse.
 */
public record StoreStats(
	long nodeCount,
	long relationshipCount,
	long adjacencyCellCount,
	long propertyCellCount,
	long blobExtentCount,
	long indexPageCount,
	int labelCount,
	int keyCount,
	int segmentFileCount,
	long recordBytes,
	long storageBytes) {

	/** Committed bytes per node, the headline number for the space efficiency goal. */
	public double bytesPerNode() {
		return nodeCount == 0 ? 0 : (double) storageBytes / nodeCount;
	}
}
