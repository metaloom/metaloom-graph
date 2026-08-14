package io.metaloom.graph.core.store.adj;

import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.util.Layouts;

/**
 * One page of adjacency, holding a compressed sorted region and a raw tail.
 *
 * <pre>
 * Header, 64 bytes:
 *  0  8  node_id
 *  8  8  next_block_id
 * 16  8  xmin
 * 24  4  sorted_bytes
 * 28  2  tail_count
 * 30  2  gen
 * 32  4  sorted_count
 * 36  1  flags
 * 37  3  reserved
 * 40  8  min_location
 * 48  8  max_location
 * 56  8  type_bitmap
 *
 * Page layout:
 * [ header ][ compressed sorted region ][ free ][ raw tail entries, 8 bytes each, growing downwards ]
 * </pre>
 *
 * Appends go to the raw tail, which costs one 8 byte write. When the tail meets the sorted region the block
 * recompresses everything into the sorted region, which typically reclaims most of the page; only when compression can
 * no longer make room is the block full. This is what lets a page hold thousands of edges rather than the 1016 a raw
 * layout would fit.
 *
 * @see <a href="../../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> section 5
 */
public final class AdjacencyBlock {

	public static final int HEADER_BYTES = 64;
	public static final int ENTRY_BYTES = 8;

	public static final int FLAG_FULL = 1;

	private static final int OFF_NODE_ID = 0;
	private static final int OFF_NEXT = 8;
	private static final int OFF_XMIN = 16;
	private static final int OFF_SORTED_BYTES = 24;
	private static final int OFF_TAIL_COUNT = 28;
	private static final int OFF_SORTED_COUNT = 32;
	private static final int OFF_FLAGS = 36;
	private static final int OFF_MIN_LOCATION = 40;
	private static final int OFF_MAX_LOCATION = 48;
	private static final int OFF_TYPE_BITMAP = 56;

	private final MemorySegment page;
	private final int pageSize;

	public AdjacencyBlock(MemorySegment page, int pageSize) {
		this.page = page;
		this.pageSize = pageSize;
	}

	public void initialise(long nodeId) {
		page.set(Layouts.LONG, OFF_NODE_ID, nodeId);
		page.set(Layouts.LONG, OFF_NEXT, ElementId.NULL);
		page.set(Layouts.LONG, OFF_XMIN, 0L);
		page.set(Layouts.INT, OFF_SORTED_BYTES, 0);
		page.set(Layouts.SHORT, OFF_TAIL_COUNT, (short) 0);
		page.set(Layouts.INT, OFF_SORTED_COUNT, 0);
		page.set(Layouts.INT, OFF_FLAGS, 0);
		page.set(Layouts.LONG, OFF_MIN_LOCATION, Long.MAX_VALUE);
		page.set(Layouts.LONG, OFF_MAX_LOCATION, Long.MIN_VALUE);
		page.set(Layouts.LONG, OFF_TYPE_BITMAP, 0L);
	}

	public long nodeId() {
		return page.get(Layouts.LONG, OFF_NODE_ID);
	}

	/** Repoints the owning node. Compaction is the only caller. */
	public void nodeId(long id) {
		page.set(Layouts.LONG, OFF_NODE_ID, id);
	}

	public long nextBlockId() {
		return page.get(Layouts.LONG, OFF_NEXT);
	}

	public void nextBlockId(long id) {
		page.set(Layouts.LONG, OFF_NEXT, id);
	}

	public int sortedBytes() {
		return page.get(Layouts.INT, OFF_SORTED_BYTES);
	}

	public int sortedCount() {
		return page.get(Layouts.INT, OFF_SORTED_COUNT);
	}

	public int tailCount() {
		return Short.toUnsignedInt(page.get(Layouts.SHORT, OFF_TAIL_COUNT));
	}

	public int entryCount() {
		return sortedCount() + tailCount();
	}

	public boolean isFull() {
		return (page.get(Layouts.INT, OFF_FLAGS) & FLAG_FULL) != 0;
	}

	public long minLocation() {
		return page.get(Layouts.LONG, OFF_MIN_LOCATION);
	}

	public long maxLocation() {
		return page.get(Layouts.LONG, OFF_MAX_LOCATION);
	}

	public long typeBitmap() {
		return page.get(Layouts.LONG, OFF_TYPE_BITMAP);
	}

	/** A Bloom-style filter over relationship types: a false positive is fine, a false negative is corruption. */
	public static long typeBit(int typeId) {
		return 1L << (typeId & 63);
	}

	public boolean mayContainType(int typeId) {
		return (typeBitmap() & typeBit(typeId)) != 0;
	}

	/** True when no entry in this block can reference the given location. */
	public boolean mayContainLocation(long location) {
		return location >= minLocation() && location <= maxLocation();
	}

	public int freeBytes() {
		return pageSize - HEADER_BYTES - sortedBytes() - tailCount() * ENTRY_BYTES;
	}

	/** Appends one entry to the raw tail. Returns false if there is no room; the caller then compacts or moves on. */
	public boolean appendRaw(long edge) {
		if (freeBytes() < ENTRY_BYTES) {
			return false;
		}
		int tail = tailCount();
		if (tail >= 0xFFFF) {
			return false;
		}
		page.set(Layouts.LONG, pageSize - (long) (tail + 1) * ENTRY_BYTES, edge);
		page.set(Layouts.SHORT, OFF_TAIL_COUNT, (short) (tail + 1));
		observe(edge);
		return true;
	}

	private void observe(long edge) {
		long location = PackedEdge.location(edge);
		if (location < minLocation()) {
			page.set(Layouts.LONG, OFF_MIN_LOCATION, location);
		}
		if (location > maxLocation()) {
			page.set(Layouts.LONG, OFF_MAX_LOCATION, location);
		}
		page.set(Layouts.LONG, OFF_TYPE_BITMAP, typeBitmap() | typeBit(PackedEdge.typeId(edge)));
	}

	/**
	 * Reads every live entry of this block, sorted region first.
	 * <p>
	 * The three counts in the header are untrusted: they are read straight out of a page. {@code sorted_count} is a
	 * full int, so a flipped byte can ask for an array of two billion longs, and adding it to the tail count can
	 * overflow into a negative size. Both are checked against what a page could possibly hold before anything is
	 * allocated.
	 */
	public long[] readAll() {
		int sorted = sortedCount();
		int tail = tailCount();
		int bytes = sortedBytes();
		int payloadBytes = pageSize - HEADER_BYTES;
		if (bytes < 0 || bytes > payloadBytes) {
			throw new PageCorruptException("Adjacency block declares a " + bytes
				+ " byte compressed region, which does not fit a " + pageSize + " byte page");
		}
		// The sorted region is compressed, so the number of entries it decodes to is far larger than the number of
		// raw entries the page would hold — that is the whole point of the codec. The real bound is one byte per
		// entry, which is the smallest a delta can encode to.
		if (sorted < 0 || sorted > bytes) {
			throw new PageCorruptException("Adjacency block declares " + sorted + " sorted entries in a " + bytes
				+ " byte region, which cannot encode more than one entry per byte");
		}
		int tailCapacity = (payloadBytes - bytes) / ENTRY_BYTES;
		if (tail > tailCapacity) {
			throw new PageCorruptException("Adjacency block declares " + tail + " raw tail entries, but only "
				+ tailCapacity + " fit beside its " + bytes + " byte compressed region");
		}
		long[] result = new long[sorted + tail];
		int written = 0;
		if (sorted > 0) {
			byte[] encoded = new byte[bytes];
			MemorySegment.copy(page, HEADER_BYTES, MemorySegment.ofArray(encoded), 0, bytes);
			written = EdgeCodec.decode(encoded, 0, bytes, result);
			if (written != sorted) {
				throw new PageCorruptException(
					"Adjacency block declares " + sorted + " sorted entries but decoded " + written);
			}
		}
		for (int i = 0; i < tail; i++) {
			result[written++] = page.get(Layouts.LONG, pageSize - (long) (i + 1) * ENTRY_BYTES);
		}
		return result;
	}

	/**
	 * Re-encodes every entry into the sorted region and clears the tail. Returns false when the compressed form does
	 * not fit, which means the block cannot hold more and should be marked full.
	 */
	public boolean compact() {
		return rewrite(readAll());
	}

	/** Replaces the block's contents. Returns false if the given entries do not fit. */
	public boolean rewrite(long[] entries) {
		long[] live = entries;
		int count = 0;
		for (long edge : entries) {
			if (!PackedEdge.isTombstone(edge)) {
				count++;
			}
		}
		if (count != entries.length) {
			live = new long[count];
			int i = 0;
			for (long edge : entries) {
				if (!PackedEdge.isTombstone(edge)) {
					live[i++] = edge;
				}
			}
		}
		byte[] encoded = EdgeCodec.encode(live, live.length);
		if (HEADER_BYTES + encoded.length > pageSize) {
			return false;
		}
		MemorySegment.copy(MemorySegment.ofArray(encoded), 0, page, HEADER_BYTES, encoded.length);
		page.set(Layouts.INT, OFF_SORTED_BYTES, encoded.length);
		page.set(Layouts.INT, OFF_SORTED_COUNT, live.length);
		page.set(Layouts.SHORT, OFF_TAIL_COUNT, (short) 0);
		page.set(Layouts.LONG, OFF_MIN_LOCATION, Long.MAX_VALUE);
		page.set(Layouts.LONG, OFF_MAX_LOCATION, Long.MIN_VALUE);
		page.set(Layouts.LONG, OFF_TYPE_BITMAP, 0L);
		for (long edge : live) {
			observe(edge);
		}
		return true;
	}

	public void markFull() {
		page.set(Layouts.INT, OFF_FLAGS, page.get(Layouts.INT, OFF_FLAGS) | FLAG_FULL);
	}
}
