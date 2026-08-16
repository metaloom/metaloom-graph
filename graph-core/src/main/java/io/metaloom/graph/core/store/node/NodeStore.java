package io.metaloom.graph.core.store.node;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.store.prop.PropertyStore;
import io.metaloom.graph.core.util.Layouts;

/**
 * The 64 byte node record: exactly one cache line, 128 per 8 KiB page, never straddling a page.
 *
 * <pre>
 *  0  4  label_id            the first label; 0 = none
 *  4  2  gen
 *  6  1  flags
 *  7  1  inline_edge_count
 *  8  8  prop_block_id
 * 16  8  adj_head_id
 * 24  4  label_id_2          second label, 0 = absent
 * 28  4  label_id_3          third label, 0 = absent
 * 32  8  label_overflow_id   PROP block holding labels four and beyond, or NULL
 * 40  8  inline_edge[0]
 * 48  8  inline_edge[1]
 * 56  4  degree_out
 * 60  4  degree_in
 * </pre>
 *
 * The proof of concept spent 1080 bytes on the same information, 1024 of it an array of property offsets of which at
 * most 32 entries could ever be used.
 *
 * <h2>Where the label set came from</h2>
 *
 * <p>
 * Offsets 24 to 39 held {@code version_ptr} and {@code xmin}, the record-level MVCC fields of
 * [TRANSACTIONS.md §5.2](../../../../../../../../../spec/TRANSACTIONS.md). Phase 5 resolved visibility per <em>page</em>
 * instead, so both were written as zero and read by nothing. Reusing them costs no bytes: the record is still 64, the
 * stride is unchanged, and the space-efficiency gates are unaffected.
 * </p>
 *
 * <p>
 * <b>It also means format version 3 needs no data migration.</b> {@link ElementId#NULL} is zero and both fields were
 * written as zero, so every node in every version 2 store already decodes as exactly one label — which is what it has.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 5
 */
public final class NodeStore {

	public static final int RECORD_BYTES = 64;
	public static final int MAX_INLINE_EDGES = 2;

	/** Labels held in the record itself. Beyond this the set spills into a {@code PROP} block. */
	public static final int MAX_INLINE_LABELS = 3;

	public static final int FLAG_LIVE = 1;
	public static final int FLAG_HAS_ADJ_BLOCKS = 2;
	/** The node carries more labels than fit inline, and {@code label_overflow_id} names the rest. */
	public static final int FLAG_HAS_LABEL_OVERFLOW = 4;
	public static final int FLAG_DENSE = 8;

	/** The key id under which the overflow block stores its label list. Its own block, so it collides with nothing. */
	private static final int OVERFLOW_KEY = 1;

	private static final int OFF_LABEL_ID = 0;
	private static final int OFF_FLAGS = 6;
	private static final int OFF_INLINE_COUNT = 7;
	private static final int OFF_PROP_BLOCK = 8;
	private static final int OFF_ADJ_HEAD = 16;
	private static final int OFF_LABEL_ID_2 = 24;
	private static final int OFF_LABEL_ID_3 = 28;
	private static final int OFF_LABEL_OVERFLOW = 32;
	private static final int OFF_INLINE_EDGE_0 = 40;
	private static final int OFF_DEGREE_OUT = 56;
	private static final int OFF_DEGREE_IN = 60;

	private final SegmentSet segments;

	/**
	 * Where a label set too large for the record goes.
	 * <p>
	 * A property block rather than a bespoke structure, so that allocation, the cell-run header, the generation, the
	 * orphan checker and compaction's reference rewriting all work on it unchanged. The alternative — a raw run in the
	 * {@code PROP} segment — would have had to reimplement every one of those, and get each one right.
	 */
	private final PropertyStore overflow;

	public NodeStore(SegmentSet segments, PropertyStore overflow) {
		if (segments.kind() != Kind.NODE) {
			throw new IllegalArgumentException("NodeStore requires a NODE segment set");
		}
		if (segments.stride() != RECORD_BYTES) {
			throw new IllegalStateException("NODE stride is " + segments.stride() + ", expected " + RECORD_BYTES);
		}
		this.segments = segments;
		this.overflow = overflow;
	}

	public long create(int labelId, long propBlockId) throws IOException {
		return create(labelId == 0 ? new int[0] : new int[] { labelId }, propBlockId);
	}

	/**
	 * Creates a node carrying every label given, in order. Duplicates are rejected by the caller, not here.
	 *
	 * <p>
	 * The labels are written into the same record acquisition as everything else rather than through
	 * {@link #labels(long, int[])}. Doing it the tidy way cost a second {@code mutable()} per create — one extra
	 * counted record write on the hottest write path in the engine, which {@code ReadBudgetTest} failed on
	 * immediately. That test exists for exactly this.
	 * </p>
	 */
	public long create(int[] labelIds, long propBlockId) throws IOException {
		long id = segments.allocate();
		MemorySegment record = segments.mutable(id);
		record.set(Layouts.BYTE, OFF_INLINE_COUNT, (byte) 0);
		record.set(Layouts.LONG, OFF_PROP_BLOCK, propBlockId);
		record.set(Layouts.LONG, OFF_ADJ_HEAD, ElementId.NULL);
		record.set(Layouts.BYTE, OFF_FLAGS, (byte) writeLabels(record, id, labelIds, FLAG_LIVE));
		segments.touched(id);
		return id;
	}

	/**
	 * Writes a label set into an already-acquired record and returns the flags it implies.
	 * <p>
	 * The caller sets the flags byte, because this may have to add {@link #FLAG_HAS_LABEL_OVERFLOW} to it.
	 */
	private int writeLabels(MemorySegment record, long id, int[] labelIds, int flags) throws IOException {
		int count = labelIds == null ? 0 : labelIds.length;
		record.set(Layouts.INT, OFF_LABEL_ID, count > 0 ? labelIds[0] : 0);
		record.set(Layouts.INT, OFF_LABEL_ID_2, count > 1 ? labelIds[1] : 0);
		record.set(Layouts.INT, OFF_LABEL_ID_3, count > 2 ? labelIds[2] : 0);
		if (count <= MAX_INLINE_LABELS) {
			record.set(Layouts.LONG, OFF_LABEL_OVERFLOW, ElementId.NULL);
			return flags & ~FLAG_HAS_LABEL_OVERFLOW;
		}
		List<Object> extra = new ArrayList<>(count - MAX_INLINE_LABELS);
		for (int i = MAX_INLINE_LABELS; i < count; i++) {
			extra.add((long) labelIds[i]);
		}
		// Allocates in the PROP segment, which is a different segment set: the NODE record slice held above stays
		// valid across it.
		record.set(Layouts.LONG, OFF_LABEL_OVERFLOW, overflow.store(Map.of(OVERFLOW_KEY, extra), id));
		return flags | FLAG_HAS_LABEL_OVERFLOW;
	}

	// ---------------------------------------------------------------- labels

	/**
	 * Every label the node carries, in the order it was given.
	 * <p>
	 * The common case — one label — reads three ints out of the record already in cache and allocates a one-element
	 * array. Nothing is touched outside the record unless the node has more than {@link #MAX_INLINE_LABELS}.
	 */
	public int[] labels(long id) {
		MemorySegment record = record(id);
		int first = record.get(Layouts.INT, OFF_LABEL_ID);
		if (first == 0) {
			return new int[0];
		}
		int second = record.get(Layouts.INT, OFF_LABEL_ID_2);
		int third = record.get(Layouts.INT, OFF_LABEL_ID_3);
		int inline = second == 0 ? 1 : third == 0 ? 2 : 3;
		int flags = Byte.toUnsignedInt(record.get(Layouts.BYTE, OFF_FLAGS));
		if ((flags & FLAG_HAS_LABEL_OVERFLOW) == 0) {
			return inline == 1 ? new int[] { first }
				: inline == 2 ? new int[] { first, second } : new int[] { first, second, third };
		}
		int[] extra = readOverflow(record.get(Layouts.LONG, OFF_LABEL_OVERFLOW));
		int[] all = new int[MAX_INLINE_LABELS + extra.length];
		all[0] = first;
		all[1] = second;
		all[2] = third;
		System.arraycopy(extra, 0, all, MAX_INLINE_LABELS, extra.length);
		return all;
	}

	/** Replaces the node's label set, freeing or reallocating the overflow block as the new size requires. */
	public void labels(long id, int[] labelIds) throws IOException {
		MemorySegment record = mutable(id);
		int flags = Byte.toUnsignedInt(record.get(Layouts.BYTE, OFF_FLAGS));
		if ((flags & FLAG_HAS_LABEL_OVERFLOW) != 0) {
			// Whatever the new set is, the old spill is not part of it. Freed before the new one is allocated, so a
			// node that stays over the inline limit does not hold two blocks at once.
			overflow.free(record.get(Layouts.LONG, OFF_LABEL_OVERFLOW));
		}
		record.set(Layouts.BYTE, OFF_FLAGS, (byte) writeLabels(record, id, labelIds, flags));
		segments.touched(id);
	}

	public boolean hasLabel(long id, int labelId) {
		if (labelId == 0) {
			return false;
		}
		for (int held : labels(id)) {
			if (held == labelId) {
				return true;
			}
		}
		return false;
	}

	/** The overflow block, or {@link ElementId#NULL}. Compaction and the orphan checker both need to see it. */
	public long labelOverflowId(long id) {
		MemorySegment record = record(id);
		return (Byte.toUnsignedInt(record.get(Layouts.BYTE, OFF_FLAGS)) & FLAG_HAS_LABEL_OVERFLOW) == 0
			? ElementId.NULL
			: record.get(Layouts.LONG, OFF_LABEL_OVERFLOW);
	}

	/** Repoints the overflow block after compaction has relocated it. */
	public void labelOverflowId(long id, long blockId) {
		mutable(id).set(Layouts.LONG, OFF_LABEL_OVERFLOW, blockId);
		segments.touched(id);
	}

	/** Releases the overflow block, if any. Part of the delete cascade. */
	public void freeLabelOverflow(long id) {
		long block = labelOverflowId(id);
		if (!ElementId.isNull(block)) {
			overflow.free(block);
		}
	}

	private int[] readOverflow(long blockId) {
		Object stored = overflow.read(blockId, OVERFLOW_KEY);
		if (!(stored instanceof List<?> list)) {
			throw new PageCorruptException("Label overflow block " + ElementId.toString(blockId)
				+ " does not hold a label list");
		}
		int[] extra = new int[list.size()];
		for (int i = 0; i < extra.length; i++) {
			if (!(list.get(i) instanceof Number number)) {
				throw new PageCorruptException("Label overflow block " + ElementId.toString(blockId)
					+ " holds a non-numeric label at " + i);
			}
			extra[i] = number.intValue();
		}
		return extra;
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
		return removeInlineEdge(id, packedEdge, -1L);
	}

	/**
	 * Removes the first inline edge that matches {@code packedEdge} through {@code mask}, keeping the remaining
	 * entries packed.
	 * <p>
	 * The mask is how a caller removes an entry it can only partly name — the adjacency store matches a materialised
	 * entry by the relationship it points at while ignoring the type cached beside it. The layout of the fields stays
	 * the adjacency store's business; this only compares.
	 */
	public boolean removeInlineEdge(long id, long packedEdge, long mask) {
		long probe = packedEdge & mask;
		MemorySegment record = mutable(id);
		int count = Byte.toUnsignedInt(record.get(Layouts.BYTE, OFF_INLINE_COUNT));
		for (int i = 0; i < count; i++) {
			if ((record.get(Layouts.LONG, OFF_INLINE_EDGE_0 + 8L * i) & mask) == probe) {
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

	public SegmentSet segments() {
		return segments;
	}
}
