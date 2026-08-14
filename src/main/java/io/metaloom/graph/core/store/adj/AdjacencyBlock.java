package io.metaloom.graph.core.store.adj;

import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.util.Layouts;

/**
 * One adjacency block: a run of consecutive {@link io.metaloom.graph.core.id.Kind#ADJ_CELL_BYTES} cells holding a
 * compressed sorted region and a raw tail.
 *
 * <p>
 * A block spans one cell when it is created and doubles as it fills, up to a whole page. It never straddles a page,
 * so the run from its first cell to the end of its page is always safe to acquire — which is how the whole block is
 * read in a single acquisition despite its variable length.
 * </p>
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
 * 37  1  cells          -- allocation units this block spans; 0 marks a continuation cell
 * 38  2  reserved
 * 40  8  min_location
 * 48  8  max_location
 * 56  8  type_bitmap
 *
 * Page layout:
 * [ header ][ compressed sorted region ][ free ][ raw tail entries, 8 bytes each, growing downwards ]
 * </pre>
 *
 * Appends go to the raw tail, which costs one 8 byte write. When the tail meets the sorted region the block
 * recompresses everything, which typically reclaims most of the space; only when compression can no longer make room
 * is the block full — and then it either grows to a larger run or, at page size, chains. This is what lets a page hold
 * thousands of edges rather than the 1016 a raw layout would fit.
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
	private static final int OFF_CELLS = 37;
	private static final int OFF_MIN_LOCATION = 40;
	private static final int OFF_MAX_LOCATION = 48;
	private static final int OFF_TYPE_BITMAP = 56;

	private final MemorySegment page;
	private final int capacityBytes;

	/**
	 * @param page
	 *            the block's own bytes, exactly {@code capacityBytes} of them
	 * @param capacityBytes
	 *            {@code cells * Kind.ADJ_CELL_BYTES}
	 */
	public AdjacencyBlock(MemorySegment page, int capacityBytes) {
		this.page = page;
		this.capacityBytes = capacityBytes;
	}

	/** The declared cell count of a block, read from its first cell. Zero marks a continuation cell of a larger run. */
	public static int cellsOf(MemorySegment firstCell) {
		return Byte.toUnsignedInt(firstCell.get(Layouts.BYTE, OFF_CELLS));
	}

	public int cells() {
		return cellsOf(page);
	}

	public int capacityBytes() {
		return capacityBytes;
	}

	public void initialise(long nodeId, int cells) {
		page.set(Layouts.BYTE, OFF_CELLS, (byte) cells);
		page.set(Layouts.LONG, OFF_NODE_ID, nodeId);
		page.set(Layouts.LONG, OFF_NEXT, ElementId.NULL);
		page.set(Layouts.LONG, OFF_XMIN, 0L);
		page.set(Layouts.INT, OFF_SORTED_BYTES, 0);
		page.set(Layouts.SHORT, OFF_TAIL_COUNT, (short) 0);
		page.set(Layouts.INT, OFF_SORTED_COUNT, 0);
		// A byte, not an int: flags used to be written four bytes wide, which reached across the reserved bytes
		// beside it and would now erase the cell count on every initialise and every markFull.
		page.set(Layouts.BYTE, OFF_FLAGS, (byte) 0);
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
		return (page.get(Layouts.BYTE, OFF_FLAGS) & FLAG_FULL) != 0;
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
		return capacityBytes - HEADER_BYTES - sortedBytes() - tailCount() * ENTRY_BYTES;
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
		page.set(Layouts.LONG, capacityBytes - (long) (tail + 1) * ENTRY_BYTES, edge);
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
		int payloadBytes = capacityBytes - HEADER_BYTES;
		if (bytes < 0 || bytes > payloadBytes) {
			throw new PageCorruptException("Adjacency block declares a " + bytes
				+ " byte compressed region, which does not fit a " + capacityBytes + " byte block");
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
			// Decoded straight out of the mapped page. Measured against copying the region into a byte array first and
			// decoding that: the two are within noise of each other on block sizes from 400 entries up, and this one
			// does not allocate up to a page on every block read — which a walk does once per node it opens.
			written = EdgeCodec.decode(page, HEADER_BYTES, (long) HEADER_BYTES + bytes, result);
			if (written != sorted) {
				throw new PageCorruptException(
					"Adjacency block declares " + sorted + " sorted entries but decoded " + written);
			}
		}
		for (int i = 0; i < tail; i++) {
			result[written++] = page.get(Layouts.LONG, capacityBytes - (long) (i + 1) * ENTRY_BYTES);
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

	/**
	 * Replaces the block's contents. Returns false if the given entries do not fit, having changed nothing.
	 *
	 * <p>
	 * The entries are compacted in place, ordered, encoded, and copied in only once the result is known to fit. The
	 * fit check has to come before the page is touched: an encode that ran out of room half way would leave the
	 * compressed region overwritten and the header still describing what used to be there.
	 * </p>
	 *
	 * <p>
	 * <b>This method consumes {@code entries}</b>: it reorders them and squeezes out tombstones in place, and it does
	 * so before discovering whether they fit, so the array is disturbed even when this returns false. Callers that
	 * keep the array — {@link AdjacencyStore#rewriteBlock}, which retries with progressively fewer entries — must
	 * hand over a copy.
	 * </p>
	 */
	public boolean rewrite(long[] entries) {
		// Squeeze the tombstones out in place. The array belongs to the caller's read, which is finished with it.
		int count = 0;
		for (long edge : entries) {
			if (!PackedEdge.isTombstone(edge)) {
				entries[count++] = edge;
			}
		}
		EdgeCodec.sort(entries, count);
		byte[] encoded = EdgeCodec.encodeOrdered(entries, count);
		if (HEADER_BYTES + encoded.length > capacityBytes) {
			return false;
		}
		MemorySegment.copy(MemorySegment.ofArray(encoded), 0, page, HEADER_BYTES, encoded.length);
		page.set(Layouts.INT, OFF_SORTED_BYTES, encoded.length);
		page.set(Layouts.INT, OFF_SORTED_COUNT, count);
		page.set(Layouts.SHORT, OFF_TAIL_COUNT, (short) 0);
		page.set(Layouts.LONG, OFF_MIN_LOCATION, Long.MAX_VALUE);
		page.set(Layouts.LONG, OFF_MAX_LOCATION, Long.MIN_VALUE);
		page.set(Layouts.LONG, OFF_TYPE_BITMAP, 0L);
		for (int i = 0; i < count; i++) {
			observe(entries[i]);
		}
		return true;
	}

	public void markFull() {
		page.set(Layouts.BYTE, OFF_FLAGS, (byte) (page.get(Layouts.BYTE, OFF_FLAGS) | FLAG_FULL));
	}
}
