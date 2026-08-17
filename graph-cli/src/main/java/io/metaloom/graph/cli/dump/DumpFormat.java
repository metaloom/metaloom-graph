package io.metaloom.graph.cli.dump;

/**
 * The constants both halves of the dump format agree on.
 *
 * <pre>
 * Header    magic "MLGDUMP\0", u16 dumpVersion, string sourceDatabase, u64 takenAtEpochMillis,
 *           u64 snapshotLsn, u32 pageSize, u64 segmentCapacity, u32 vectorDimensions
 * -- checksum covers everything from here --
 * Schema    varInt count, then {string label, string key, u8 unique} per composite index
 *           varInt count, then {string key} per store-wide unique constraint
 *           varInt count, then {string label, string key, u32 dims, u8 metric, string model,
 *                               u32 m, u32 efConstruction} per vector index
 * Nodes     varInt count, then per node: varInt labelCount, strings; varInt propertyCount,
 *           then {string key, value} pairs. Dump ids are the walk order, 0..n-1, and implicit.
 * Edges     varInt count, then per edge: varInt fromDumpId, varInt toDumpId, string type,
 *           u8 materialised, and when materialised: varInt propertyCount and its pairs
 * Trailer   u64 nodeCount, u64 materialisedCount, u64 lightweightCount, u64 crc32c
 * </pre>
 *
 * <h2>Dump ids are dense and implicit</h2>
 *
 * <p>
 * A node is identified in the edge section by its position in the node section, {@code 0..n-1}, and that position is
 * never written down — it is the order. Two things fall out. The file is smaller, because a real {@code ElementId} is
 * packed with a kind in the high bits and lands around 2^60, where a varint costs nine bytes and a dense index costs
 * one to three. And the restore's id map becomes a {@code long[]} indexed by dump id rather than a hash map: eight
 * bytes per node, no hashing, no spill, sized from the count in the trailer.
 * </p>
 *
 * <p>
 * <b>Element ids are therefore not preserved across a restore</b>, which is the price of the whole approach and is
 * stated everywhere it matters. A restored node holds the same labels and properties and sits in the same shape of
 * graph; it does not have the same id, because a fresh store allocates its own slots.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/BACKUP.md">spec/BACKUP.md</a>
 */
public final class DumpFormat {

	/** {@code MLGDUMP\0} — eight bytes, so the section after it starts 8-aligned. */
	public static final byte[] MAGIC = { 'M', 'L', 'G', 'D', 'U', 'M', 'P', 0 };

	/**
	 * The dump format's own version, which moves independently of the store's.
	 *
	 * <p>
	 * That independence is the point of having a separate codec at all: a store format bump does not invalidate
	 * existing dumps, and a change to what a dump records does not touch the engine.
	 * </p>
	 */
	public static final int VERSION = 1;

	private DumpFormat() {
	}
}
