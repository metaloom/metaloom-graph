package io.metaloom.graph.core.store.rel;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.util.Layouts;

/**
 * The 64 byte relationship record, materialised only for edges that carry properties or that need a stable external
 * id. Property-free edges exist purely as adjacency entries and cost 16 bytes in total.
 *
 * <pre>
 *  0  8  from_id
 *  8  8  to_id
 * 16  4  type_id
 * 20  2  gen
 * 22  1  flags
 * 23  1  reserved
 * 24  8  prop_block_id
 * 32  8  version_ptr
 * 40  8  xmin
 * 48  8  reserved
 * 56  8  reserved
 * </pre>
 *
 * @see <a href="../../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 6
 */
public final class RelStore {

	public static final int RECORD_BYTES = 64;

	public static final int FLAG_LIVE = 1;

	private static final int OFF_FROM = 0;
	private static final int OFF_TO = 8;
	private static final int OFF_TYPE_ID = 16;
	private static final int OFF_FLAGS = 22;
	private static final int OFF_PROP_BLOCK = 24;
	private static final int OFF_VERSION_PTR = 32;
	private static final int OFF_XMIN = 40;

	private final SegmentSet segments;

	public RelStore(SegmentSet segments) {
		if (segments.kind() != Kind.REL) {
			throw new IllegalArgumentException("RelStore requires a REL segment set");
		}
		if (segments.stride() != RECORD_BYTES) {
			throw new IllegalStateException("REL stride is " + segments.stride() + ", expected " + RECORD_BYTES);
		}
		this.segments = segments;
	}

	public long create(long fromId, int typeId, long toId, long propBlockId) throws IOException {
		long id = segments.allocate();
		MemorySegment record = segments.mutable(id);
		record.set(Layouts.LONG, OFF_FROM, fromId);
		record.set(Layouts.LONG, OFF_TO, toId);
		record.set(Layouts.INT, OFF_TYPE_ID, typeId);
		record.set(Layouts.BYTE, OFF_FLAGS, (byte) FLAG_LIVE);
		record.set(Layouts.LONG, OFF_PROP_BLOCK, propBlockId);
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

	private MemorySegment record(long id) {
		return segments.resolve(id);
	}

	public long fromId(long id) {
		return record(id).get(Layouts.LONG, OFF_FROM);
	}

	/**
	 * The record, resolved once, for a caller that wants several of its fields. Each id-taking accessor here is a record
	 * touch of its own, and a traversal resolving type and both endpoints that way paid three of them per edge.
	 */
	public MemorySegment view(long id) {
		return record(id);
	}

	public static long fromId(MemorySegment view) {
		return view.get(Layouts.LONG, OFF_FROM);
	}

	public static long toId(MemorySegment view) {
		return view.get(Layouts.LONG, OFF_TO);
	}

	public static int typeId(MemorySegment view) {
		return view.get(Layouts.INT, OFF_TYPE_ID);
	}

	public long toId(long id) {
		return record(id).get(Layouts.LONG, OFF_TO);
	}

	/** Repoints the {@code from} endpoint. Compaction is the only caller: an endpoint never changes otherwise. */
	public void fromId(long id, long nodeId) {
		segments.mutable(id).set(Layouts.LONG, OFF_FROM, nodeId);
		segments.touched(id);
	}

	/** Repoints the {@code to} endpoint. */
	public void toId(long id, long nodeId) {
		segments.mutable(id).set(Layouts.LONG, OFF_TO, nodeId);
		segments.touched(id);
	}

	public int typeId(long id) {
		return record(id).get(Layouts.INT, OFF_TYPE_ID);
	}

	public long propBlockId(long id) {
		return record(id).get(Layouts.LONG, OFF_PROP_BLOCK);
	}

	public void propBlockId(long id, long blockId) {
		segments.mutable(id).set(Layouts.LONG, OFF_PROP_BLOCK, blockId);
		segments.touched(id);
	}

	public long xmin(long id) {
		return record(id).get(Layouts.LONG, OFF_XMIN);
	}

	public SegmentSet segments() {
		return segments;
	}
}
