package io.metaloom.graph.core.store.node;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.util.Layouts;

/**
 * The 64 byte node record: exactly one cache line, 128 per 8 KiB page, never straddling a page.
 *
 * <pre>
 *  0  4  label_id
 *  4  2  gen
 *  6  1  flags
 *  7  1  inline_edge_count
 *  8  8  prop_block_id
 * 16  8  adj_head_id
 * 24  8  version_ptr
 * 32  8  xmin
 * 40  8  inline_edge[0]
 * 48  8  inline_edge[1]
 * 56  4  degree_out
 * 60  4  degree_in
 * </pre>
 *
 * The proof of concept spent 1080 bytes on the same information, 1024 of it an array of property offsets of which at
 * most 32 entries could ever be used.
 *
 * @see <a href="../../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 5
 */
public final class NodeStore {

	public static final int RECORD_BYTES = 64;
	public static final int MAX_INLINE_EDGES = 2;

	public static final int FLAG_LIVE = 1;
	public static final int FLAG_HAS_ADJ_BLOCKS = 2;
	public static final int FLAG_DENSE = 8;

	private static final int OFF_LABEL_ID = 0;
	private static final int OFF_FLAGS = 6;
	private static final int OFF_INLINE_COUNT = 7;
	private static final int OFF_PROP_BLOCK = 8;
	private static final int OFF_ADJ_HEAD = 16;
	private static final int OFF_VERSION_PTR = 24;
	private static final int OFF_XMIN = 32;
	private static final int OFF_INLINE_EDGE_0 = 40;
	private static final int OFF_DEGREE_OUT = 56;
	private static final int OFF_DEGREE_IN = 60;

	private final SegmentSet segments;

	public NodeStore(SegmentSet segments) {
		if (segments.kind() != Kind.NODE) {
			throw new IllegalArgumentException("NodeStore requires a NODE segment set");
		}
		if (segments.stride() != RECORD_BYTES) {
			throw new IllegalStateException("NODE stride is " + segments.stride() + ", expected " + RECORD_BYTES);
		}
		this.segments = segments;
	}

	public long create(int labelId, long propBlockId) throws IOException {
		long id = segments.allocate();
		MemorySegment record = segments.mutable(id);
		record.set(Layouts.INT, OFF_LABEL_ID, labelId);
		record.set(Layouts.BYTE, OFF_FLAGS, (byte) FLAG_LIVE);
		record.set(Layouts.BYTE, OFF_INLINE_COUNT, (byte) 0);
		record.set(Layouts.LONG, OFF_PROP_BLOCK, propBlockId);
		record.set(Layouts.LONG, OFF_ADJ_HEAD, ElementId.NULL);
		record.set(Layouts.LONG, OFF_VERSION_PTR, ElementId.NULL);
		record.set(Layouts.LONG, OFF_XMIN, 0L);
		segments.touched(id);
		return id;
	}

	public void free(long id) {
		segments.free(id);
		segments.touched(id);
	}

	public boolean exists(long id) {
		return segments.isLive(id);
	}

	/** Read path: never copies a page into the transaction overlay. */
	private MemorySegment record(long id) {
		return segments.resolve(id);
	}

	/** Write path: copies the page into the transaction overlay on first touch. */
	private MemorySegment mutable(long id) {
		return segments.mutable(id);
	}

	public int labelId(long id) {
		return record(id).get(Layouts.INT, OFF_LABEL_ID);
	}

	public int flags(long id) {
		return Byte.toUnsignedInt(record(id).get(Layouts.BYTE, OFF_FLAGS));
	}

	public void flags(long id, int value) {
		mutable(id).set(Layouts.BYTE, OFF_FLAGS, (byte) value);
	}

	public long propBlockId(long id) {
		return record(id).get(Layouts.LONG, OFF_PROP_BLOCK);
	}

	public void propBlockId(long id, long blockId) {
		mutable(id).set(Layouts.LONG, OFF_PROP_BLOCK, blockId);
		segments.touched(id);
	}

	public long adjHeadId(long id) {
		return record(id).get(Layouts.LONG, OFF_ADJ_HEAD);
	}

	public void adjHeadId(long id, long blockId) {
		MemorySegment record = mutable(id);
		record.set(Layouts.LONG, OFF_ADJ_HEAD, blockId);
		int flags = Byte.toUnsignedInt(record.get(Layouts.BYTE, OFF_FLAGS));
		record.set(Layouts.BYTE, OFF_FLAGS, (byte) (ElementId.isNull(blockId)
			? flags & ~FLAG_HAS_ADJ_BLOCKS
			: flags | FLAG_HAS_ADJ_BLOCKS));
		segments.touched(id);
	}

	public int inlineEdgeCount(long id) {
		return Byte.toUnsignedInt(record(id).get(Layouts.BYTE, OFF_INLINE_COUNT));
	}

	public long inlineEdge(long id, int index) {
		if (index < 0 || index >= MAX_INLINE_EDGES) {
			throw new IndexOutOfBoundsException("inline edge " + index);
		}
		return record(id).get(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L * index);
	}

	/** Overwrites one inline edge in place. Used by compaction, which rewrites a location without changing the count. */
	public void setInlineEdge(long id, int index, long packedEdge) {
		if (index < 0 || index >= MAX_INLINE_EDGES) {
			throw new IndexOutOfBoundsException("inline edge " + index);
		}
		mutable(id).set(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L * index, packedEdge);
		segments.touched(id);
	}

	/** Appends an inline edge. Returns false when the inline slots are full and the caller must use a block. */
	public boolean addInlineEdge(long id, long packedEdge) {
		MemorySegment record = mutable(id);
		int count = Byte.toUnsignedInt(record.get(Layouts.BYTE, OFF_INLINE_COUNT));
		if (count >= MAX_INLINE_EDGES) {
			return false;
		}
		record.set(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L * count, packedEdge);
		record.set(Layouts.BYTE, OFF_INLINE_COUNT, (byte) (count + 1));
		segments.touched(id);
		return true;
	}

	/** Removes an inline edge by value, keeping the remaining entries packed. */
	public boolean removeInlineEdge(long id, long packedEdge) {
		MemorySegment record = mutable(id);
		int count = Byte.toUnsignedInt(record.get(Layouts.BYTE, OFF_INLINE_COUNT));
		for (int i = 0; i < count; i++) {
			if (record.get(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L * i) == packedEdge) {
				for (int j = i; j < count - 1; j++) {
					record.set(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L * j,
						record.get(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L * (j + 1)));
				}
				record.set(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L * (count - 1), 0L);
				record.set(Layouts.BYTE, OFF_INLINE_COUNT, (byte) (count - 1));
				segments.touched(id);
				return true;
			}
		}
		return false;
	}

	/** Clears the inline edges, used when they are migrated into an adjacency block. */
	public void clearInlineEdges(long id) {
		MemorySegment record = mutable(id);
		record.set(Layouts.LONG, OFF_INLINE_EDGE_0, 0L);
		record.set(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L, 0L);
		record.set(Layouts.BYTE, OFF_INLINE_COUNT, (byte) 0);
		segments.touched(id);
	}

	public int degreeOut(long id) {
		return record(id).get(Layouts.INT, OFF_DEGREE_OUT);
	}

	public int degreeIn(long id) {
		return record(id).get(Layouts.INT, OFF_DEGREE_IN);
	}

	public void degreeDelta(long id, int outDelta, int inDelta) {
		MemorySegment record = mutable(id);
		record.set(Layouts.INT, OFF_DEGREE_OUT, record.get(Layouts.INT, OFF_DEGREE_OUT) + outDelta);
		record.set(Layouts.INT, OFF_DEGREE_IN, record.get(Layouts.INT, OFF_DEGREE_IN) + inDelta);
		segments.touched(id);
	}

	public long xmin(long id) {
		return record(id).get(Layouts.LONG, OFF_XMIN);
	}

	public SegmentSet segments() {
		return segments;
	}
}
