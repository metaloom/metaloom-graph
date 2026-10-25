package io.metaloom.graph.core.store.vec;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.api.Vectors;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.util.Layouts;

/**
 * Fixed-size vector records: one per stored vector, addressed arithmetically like every other record.
 *
 * <p>
 * This is what a vector property becomes when the store was created with vector storage of its dimension. The
 * alternative it replaces is the blob store, and the comparison is not close: a blob extent is page sized, so a
 * 768-dimension vector costs 8 KiB there and 4 KiB here, and reaching it costs a property block read plus a blob chain
 * walk rather than one slice at a computed offset.
 * </p>
 *
 * <p>
 * <b>The vector is never copied to be compared.</b> {@link #distanceTo} reads it in place out of the mapped segment,
 * because an ANN search computes hundreds of distances per query and materialising a {@code float[]} for each one
 * allocates more than it computes.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
public final class VectorStore {

	private final SegmentSet segments;
	private final int stride;
	private final int dimensions;

	public VectorStore(SegmentSet segments, int dimensions) {
		if (segments.kind() != Kind.VEC) {
			throw new IllegalArgumentException("VectorStore requires a VEC segment set");
		}
		this.segments = segments;
		this.stride = segments.stride();
		this.dimensions = dimensions;
		VectorRecord.requireLegalStride(stride, stride * segments.slotsPerPage());
		if (dimensions <= 0 || dimensions > VectorRecord.maxDimensions(stride)) {
			throw new IllegalArgumentException("A stride of " + stride + " holds at most "
				+ VectorRecord.maxDimensions(stride) + " dimensions, not " + dimensions);
		}
	}

	/** The one dimension this store can hold. A vector of any other length belongs in the blob store. */
	public int dimensions() {
		return dimensions;
	}

	public boolean accepts(float[] vector) {
		return vector != null && vector.length == dimensions;
	}

	public SegmentSet segments() {
		return segments;
	}

	/**
	 * Allocates a record and writes the vector into it. The links are left empty for an index to fill in later.
	 *
	 * @param ownerId
	 *            the element whose property block will point at this record. It is a back-pointer of exactly the kind
	 *            a property block keeps, and it exists for the same two reasons: compaction has to repoint it when the
	 *            owner moves, and a verify has to be able to tell an owned record from an orphaned one.
	 */
	public long store(float[] vector, long ownerId) throws IOException {
		if (!accepts(vector)) {
			throw new IllegalArgumentException("This store holds " + dimensions + " dimensions, not "
				+ (vector == null ? "null" : vector.length));
		}
		long id = segments.allocate();
		MemorySegment record = segments.mutable(id);
		// claim() zero-fills, so every header field that means "nothing yet" — flags, link counts, top level — is
		// already correct. Only the fields with a non-zero meaning are written.
		record.set(Layouts.LONG, VectorRecord.OFF_OWNER_ID, ownerId);
		record.set(Layouts.INT, VectorRecord.OFF_DIM, dimensions);
		record.set(Layouts.FLOAT, VectorRecord.OFF_NORM, (float) Vectors.norm(vector));
		int base = VectorRecord.vectorOffset();
		for (int i = 0; i < dimensions; i++) {
			record.set(Layouts.FLOAT, base + 4L * i, vector[i]);
		}
		segments.touched(id);
		return id;
	}

	/** Materialises the vector. Prefer {@link #distanceTo} where only a distance is wanted. */
	public float[] read(long vectorId) {
		return read(segments.resolve(vectorId), vectorId);
	}

	/**
	 * The dot product of a stored vector with a query, read in place.
	 * <p>
	 * The accumulator is a {@code double} for the same reason it is in {@link Vectors}: 768 products summed in a
	 * {@code float} lose enough of the tail that two vectors which should tie order by rounding error instead.
	 */
	public double dot(long vectorId, float[] query) {
		return dot(segments.resolve(vectorId), vectorId, query);
	}

	/** The stored vector's precomputed L2 norm, which makes cosine one dot product instead of three passes. */
	public double norm(long vectorId) {
		return segments.resolve(vectorId).get(Layouts.FLOAT, VectorRecord.OFF_NORM);
	}

	// ------------------------------------------------- record-level reads, for the ANN walk

	/**
	 * The record of a live vector claimed by index {@code ordinal}, or null for anything else — the membership test
	 * and the record the distance is then computed from, in one resolve.
	 *
	 * <p>
	 * The beam search used to ask "is it live", "which index claims it", "what is its norm" and "what is its dot
	 * product" as four separate resolves of the same record, each a bitmap read and a record touch. The returned slice
	 * is only good until the caller next writes: read from it straight away.
	 * </p>
	 */
	public MemorySegment memberRecord(long vectorId, int ordinal) {
		MemorySegment record = segments.resolveIfLive(vectorId);
		if (record == null
			|| Short.toUnsignedInt(record.get(Layouts.SHORT, VectorRecord.OFF_INDEX_ORD)) - 1 != ordinal) {
			return null;
		}
		return record;
	}

	/** {@link #norm(long)}, from a record already in hand. */
	public static double norm(MemorySegment record) {
		return record.get(Layouts.FLOAT, VectorRecord.OFF_NORM);
	}

	/** {@link #dot(long, float[])}, from a record already in hand. */
	public double dot(MemorySegment record, long vectorId, float[] query) {
		int dim = checkedDimension(record, vectorId);
		if (query.length != dim) {
			throw new IllegalArgumentException("Query of " + query.length + " dimensions against a stored " + dim);
		}
		int base = VectorRecord.vectorOffset();
		double sum = 0;
		for (int i = 0; i < dim; i++) {
			sum += (double) record.get(Layouts.FLOAT, base + 4L * i) * query[i];
		}
		return sum;
	}

	/** {@link #read(long)}, from a record already in hand. */
	public float[] read(MemorySegment record, long vectorId) {
		int dim = checkedDimension(record, vectorId);
		float[] vector = new float[dim];
		int base = VectorRecord.vectorOffset();
		for (int i = 0; i < dim; i++) {
			vector[i] = record.get(Layouts.FLOAT, base + 4L * i);
		}
		return vector;
	}

	public long owner(long vectorId) {
		return segments.resolve(vectorId).get(Layouts.LONG, VectorRecord.OFF_OWNER_ID);
	}

	/** Repoints the owning element. Compaction is the only caller, exactly as with a property block's owner. */
	public void owner(long vectorId, long ownerId) {
		segments.mutable(vectorId).set(Layouts.LONG, VectorRecord.OFF_OWNER_ID, ownerId);
		segments.touched(vectorId);
	}

	/**
	 * Which declared index claims this record, or -1 for none.
	 * <p>
	 * Stored as {@code ordinal + 1} so that zero — what a freshly claimed record is filled with — means "no index".
	 * Every field in this engine has to read correctly out of a zeroed page, and an ordinal is zero-based.
	 */
	public int indexOrdinal(long vectorId) {
		return Short.toUnsignedInt(segments.resolve(vectorId).get(Layouts.SHORT, VectorRecord.OFF_INDEX_ORD)) - 1;
	}

	public void indexOrdinal(long vectorId, int ordinal) {
		segments.mutable(vectorId).set(Layouts.SHORT, VectorRecord.OFF_INDEX_ORD, (short) (ordinal + 1));
		segments.touched(vectorId);
	}

	/** Whether a search must skip this record. Set when its owner is gone but the record still routes traffic. */
	public boolean tombstoned(long vectorId) {
		return (flags(vectorId) & VectorRecord.FLAG_TOMBSTONE) != 0;
	}

	public int flags(long vectorId) {
		return Byte.toUnsignedInt(segments.resolve(vectorId).get(Layouts.BYTE, VectorRecord.OFF_FLAGS));
	}

	public void flags(long vectorId, int flags) {
		segments.mutable(vectorId).set(Layouts.BYTE, VectorRecord.OFF_FLAGS, (byte) flags);
		segments.touched(vectorId);
	}

	// ---------------------------------------------------------------- the ANN links

	public int topLevel(long vectorId) {
		return Byte.toUnsignedInt(segments.resolve(vectorId).get(Layouts.BYTE, VectorRecord.OFF_TOP_LEVEL));
	}

	public void topLevel(long vectorId, int level) {
		segments.mutable(vectorId).set(Layouts.BYTE, VectorRecord.OFF_TOP_LEVEL, (byte) level);
		segments.touched(vectorId);
	}

	public int linkCount(long vectorId, int level) {
		return Short.toUnsignedInt(segments.resolve(vectorId)
			.get(Layouts.SHORT, VectorRecord.OFF_LINK_COUNTS + 2L * level));
	}

	/** The neighbours this record links to at one layer, at the default link budget. */
	public long[] links(long vectorId, int level) {
		return links(vectorId, level, io.metaloom.graph.core.api.VectorIndexDefinition.DEFAULT_M);
	}

	/** The neighbours this record links to at one layer. */
	public long[] links(long vectorId, int level, int m) {
		// One resolve for the count and the links: they are the same record.
		MemorySegment record = segments.resolve(vectorId);
		int count = Short.toUnsignedInt(record.get(Layouts.SHORT, VectorRecord.OFF_LINK_COUNTS + 2L * level));
		long base = linkBase(level, m);
		long[] links = new long[count];
		for (int i = 0; i < count; i++) {
			links[i] = record.get(Layouts.LONG, base + 8L * i);
		}
		return links;
	}

	/**
	 * Replaces a layer's neighbour list.
	 * <p>
	 * The list is truncated to what the layer has room for rather than overflowing into the next layer's slots. The
	 * budget was fixed when the store was created and there is nowhere else to put them; silently corrupting the layer
	 * above would be the alternative.
	 */
	public void links(long vectorId, int level, int m, long[] links) {
		int capacity = capacityAt(level, m);
		int count = Math.min(links.length, capacity);
		MemorySegment record = segments.mutable(vectorId);
		long base = linkBase(level, m);
		for (int i = 0; i < count; i++) {
			record.set(Layouts.LONG, base + 8L * i, links[i]);
		}
		record.set(Layouts.SHORT, VectorRecord.OFF_LINK_COUNTS + 2L * level, (short) count);
		segments.touched(vectorId);
	}

	/** Links per node at a layer: {@code 2m} at the base, {@code m} above it, as HNSW prescribes. */
	public int capacityAt(int level, int m) {
		int wanted = level == 0 ? 2 * m : m;
		int available = VectorRecord.linkSlots(stride, dimensions) - (level == 0 ? 0 : 2 * m + (level - 1) * m);
		return Math.max(0, Math.min(wanted, available));
	}

	/** The highest layer this store's records have room for at this link budget. */
	public int maxLevel(int m) {
		return VectorRecord.maxLevel(stride, dimensions, m);
	}

	private long linkBase(int level, int m) {
		return VectorRecord.linkOffset(dimensions) + 8L * (level == 0 ? 0 : 2 * m + (level - 1) * m);
	}

	/**
	 * Takes a record out of every ANN graph: no links, no layer, not linked.
	 * <p>
	 * Owed whenever a record stops being a member, and the reason is {@code FLAG_LINKED}. Insertion treats that flag
	 * as "already in the graph" and returns early, so a record that leaves an index and keeps the flag is silently
	 * skipped by the <em>next</em> index's build — which then has no entry point and answers every query with nothing.
	 */
	public void unlink(long vectorId) {
		MemorySegment record = segments.mutable(vectorId);
		record.set(Layouts.BYTE, VectorRecord.OFF_TOP_LEVEL, (byte) 0);
		record.set(Layouts.BYTE, VectorRecord.OFF_FLAGS,
			(byte) (flags(vectorId) & ~VectorRecord.FLAG_LINKED));
		for (int level = 0; level < VectorRecord.MAX_LEVELS; level++) {
			record.set(Layouts.SHORT, VectorRecord.OFF_LINK_COUNTS + 2L * level, (short) 0);
		}
		segments.touched(vectorId);
	}

	public void free(long vectorId) {
		segments.free(vectorId);
	}

	/** The record slice for a vector id, for the record-level reads. */
	public MemorySegment record(long vectorId) {
		return segments.resolve(vectorId);
	}

	public boolean exists(long vectorId) {
		return segments.isLive(vectorId);
	}

	public long liveCount() {
		return segments.liveCount();
	}

	/**
	 * The dimension as the record declares it, checked against what this store can hold.
	 * <p>
	 * The field comes off a data page and is therefore untrusted; unchecked it is a {@code new float[dim]} away from
	 * being a heap-exhaustion oracle, which is the same defect the blob-backed path had.
	 */
	private int checkedDimension(MemorySegment record, long vectorId) {
		int dim = record.get(Layouts.INT, VectorRecord.OFF_DIM);
		if (dim != dimensions) {
			throw new PageCorruptException("Vector record " + ElementId.toString(vectorId) + " declares " + dim
				+ " dimensions in a store of " + dimensions);
		}
		return dim;
	}
}
