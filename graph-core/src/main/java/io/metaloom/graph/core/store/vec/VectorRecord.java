package io.metaloom.graph.core.store.vec;

import io.metaloom.graph.core.api.Kind;

/**
 * The layout of one {@code VEC} record: a header, the vector itself, and the approximate-nearest-neighbour links that
 * belong to it.
 *
 * <p>
 * <b>The vector and its links share a record because a search visits both together.</b> An ANN search's inner loop is
 * "read this candidate's vector, compute a distance, then follow its links to the next candidates" — so splitting them
 * across two records doubles the page reads of the only operation the kind exists for. Interleaving them makes a
 * visited node cost one read.
 * </p>
 *
 * <pre>
 *  0   8  owner_id       the element whose property block holds this vector, as a property block names its own
 *  8   4  dim            the true dimension, which may be less than the stride allows
 * 12   2  index_ord      which declared index owns this record
 * 14   1  top_level      highest ANN layer this record appears in
 * 15   1  flags          LINKED, TOMBSTONE
 * 16   4  norm           the vector's L2 norm, so cosine is one dot product rather than three passes
 * 20   4  reserved
 * 24   8  DO NOT WRITE   spans gen at offset 30, which belongs to the slot allocator
 * 32  32  link_count[16] live links per layer
 * 64   N  the vector, 4 bytes per dimension, 64 byte aligned
 *  ..  M  links, 8 bytes each: a VEC id per neighbour
 * </pre>
 *
 * <p>
 * Bytes 24 to 32 are unusable for the same reason they are unusable on an index page: {@code Kind.VEC} declares its
 * generation at offset 30, the slot allocator maintains it without knowing this layout, and a field written across it
 * makes every id naming the record stale on the next read. {@code IndexCatalog} paid for that lesson once.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
public final class VectorRecord {

	public static final int HEADER_BYTES = 64;

	public static final int OFF_OWNER_ID = 0;
	public static final int OFF_DIM = 8;
	public static final int OFF_INDEX_ORD = 12;
	public static final int OFF_TOP_LEVEL = 14;
	public static final int OFF_FLAGS = 15;
	public static final int OFF_NORM = 16;
	/** 16 unsigned shorts, filling the header to 64. */
	public static final int OFF_LINK_COUNTS = 32;

	/** This record's links are built and it is reachable in the ANN graph. Used to resume an interrupted build. */
	public static final int FLAG_LINKED = 1;
	/** The owning node is gone. Search skips it; the slot is reclaimed by a rebuild. */
	public static final int FLAG_TOMBSTONE = 2;

	/** Layers are numbered 0 to 15, which is far past what any reachable corpus size produces. */
	public static final int MAX_LEVELS = 16;

	/**
	 * Link slots every record must have room for: {@code M0 = 32} at layer 0 plus one upper layer of 16.
	 * <p>
	 * The stride is chosen when the store is created and can never change, so it has to reserve link space before any
	 * index is declared. Reserving too little makes a store that cannot hold an index at all; the floor here is the
	 * smallest that supports the default {@code M = 16} with a layer above the base.
	 */
	public static final int MIN_LINK_SLOTS = 48;

	private VectorRecord() {
	}

	/**
	 * The smallest legal stride that holds a vector of this many dimensions together with its links.
	 *
	 * <p>
	 * A stride has to divide the page, and pages are powers of two, so this rounds up to one. That means a dimension
	 * whose raw size is already a power of two — 512 floats is exactly 2 KiB — still lands in the next stride up,
	 * because the header and the links have to live somewhere. There is no arrangement that avoids it: an interleaved
	 * record is strictly larger than its vector.
	 * </p>
	 *
	 * @return a power of two, or 0 if no legal stride exists for this dimension at this page size
	 */
	public static int strideFor(int dimensions, int pageSize) {
		if (dimensions <= 0) {
			return 0;
		}
		long needed = HEADER_BYTES + 4L * dimensions + 8L * MIN_LINK_SLOTS;
		int stride = 128;
		while (stride < needed) {
			stride <<= 1;
		}
		return stride > pageSize ? 0 : stride;
	}

	/** The largest dimension a store with this stride can hold, which is what a declaration is checked against. */
	public static int maxDimensions(int stride) {
		return (stride - HEADER_BYTES - 8 * MIN_LINK_SLOTS) / 4;
	}

	/** Byte offset of the vector within the record. Always 64, and therefore 64 byte aligned. */
	public static int vectorOffset() {
		return HEADER_BYTES;
	}

	/** Byte offset of the link array: after the vector, rounded up so the 8 byte ids stay aligned. */
	public static int linkOffset(int dimensions) {
		return align8(HEADER_BYTES + 4 * dimensions);
	}

	/** How many links a record of this stride can hold at this dimension. */
	public static int linkSlots(int stride, int dimensions) {
		return (stride - linkOffset(dimensions)) / 8;
	}

	/**
	 * The highest layer a record can hold links for, given {@code m} links per upper layer and {@code 2m} at layer 0.
	 * A declaration whose parameters leave fewer than a couple of layers is refused: an ANN graph of one layer is a
	 * linked list.
	 */
	public static int maxLevel(int stride, int dimensions, int m) {
		int slots = linkSlots(stride, dimensions);
		int upper = (slots - 2 * m) / m;
		return Math.min(MAX_LEVELS - 1, Math.max(0, upper));
	}

	private static int align8(int value) {
		return (value + 7) & ~7;
	}

	/** Guards a caller that computed a stride some other way. */
	public static void requireLegalStride(int stride, int pageSize) {
		if (stride <= 0 || Integer.bitCount(stride) != 1) {
			throw new IllegalArgumentException("A vector stride must be a positive power of two, got " + stride);
		}
		if (pageSize % stride != 0) {
			throw new IllegalArgumentException("Vector stride " + stride + " does not divide page size " + pageSize);
		}
		if (stride < HEADER_BYTES + 8 * MIN_LINK_SLOTS) {
			throw new IllegalArgumentException("Vector stride " + stride + " has no room for a vector and its links");
		}
	}

	/** The kind these records live in, for callers that would otherwise import it just to assert. */
	public static Kind kind() {
		return Kind.VEC;
	}
}
