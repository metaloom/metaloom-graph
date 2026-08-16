package io.metaloom.graph.core.segment;

import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;

/**
 * Per-kind segment geometry, derived from the page size and the segment capacity.
 * <p>
 * This class is the single source of truth for the address function
 * {@code byteOffset = dataStart(kind) + slot * stride(kind)}. Everything it computes is a constant for the lifetime of
 * a store; the values are written into every segment superblock and validated on open.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 3
 */
public final class Geometry {

	/** Superblock A and B, 4 KiB each. */
	public static final int SUPERBLOCK_BYTES = 4096;
	public static final int SUPERBLOCK_A_OFFSET = 0;
	public static final int SUPERBLOCK_B_OFFSET = SUPERBLOCK_BYTES;
	public static final int HEADER_BYTES = 2 * SUPERBLOCK_BYTES;

	/** One 32 byte entry per data page. */
	public static final int PMA_ENTRY_BYTES = 32;

	public static final int DEFAULT_PAGE_SIZE = 8192;
	public static final long DEFAULT_SEGMENT_CAPACITY = 4L * 1024 * 1024 * 1024;

	/**
	 * Sized by what the id encoding can express, not by how many kinds exist today.
	 * <p>
	 * It was 8 by hand, and the population loop below skips a kind whose code is past the end. So adding a ninth kind
	 * was not a compile error and not a runtime error either: every table stayed zero for it, and the first read
	 * through it threw {@code ArrayIndexOutOfBoundsException} from deep inside the address function.
	 */
	private static final int KINDS = Kind.MAX_KINDS;

	private final int pageSize;
	private final long segmentCapacity;
	private final int vectorStride;

	private final int[] stride = new int[KINDS];
	private final int[] recordsPerPage = new int[KINDS];
	private final int[] slotsPerSegment = new int[KINDS];
	private final int[] pagesPerSegment = new int[KINDS];
	private final long[] fsbStart = new long[KINDS];
	private final long[] fsbBytes = new long[KINDS];
	private final long[] pmaStart = new long[KINDS];
	private final long[] pmaBytes = new long[KINDS];
	private final long[] dataStart = new long[KINDS];
	private final long[] dataBytes = new long[KINDS];
	private final long[] fileBytes = new long[KINDS];

	/** A store with no vector storage. Every kind's stride is then a property of the format alone. */
	public Geometry(int pageSize, long segmentCapacity) {
		this(pageSize, segmentCapacity, 0);
	}

	/**
	 * @param vectorStride
	 *            the stride of {@link Kind#VEC}, recorded in the manifest when the store was created, or <b>0 for a
	 *            store with no vector storage</b> — in which case that kind is absent rather than zero-sized, and
	 *            {@link #isEnabled} says so.
	 */
	public Geometry(int pageSize, long segmentCapacity, int vectorStride) {
		if (Integer.bitCount(pageSize) != 1 || pageSize < 4096 || pageSize > 65536) {
			throw new IllegalArgumentException("pageSize must be a power of two between 4096 and 65536: " + pageSize);
		}
		if (segmentCapacity <= 0 || segmentCapacity % pageSize != 0) {
			throw new IllegalArgumentException("segmentCapacity must be a positive multiple of pageSize: " + segmentCapacity);
		}
		if (vectorStride < 0) {
			throw new IllegalArgumentException("vectorStride cannot be negative: " + vectorStride);
		}
		this.pageSize = pageSize;
		this.segmentCapacity = segmentCapacity;
		this.vectorStride = vectorStride;

		for (Kind kind : Kind.values()) {
			int c = kind.code();
			if (c >= KINDS || kind == Kind.NULL) {
				continue;
			}
			int s = resolveStride(kind, pageSize, vectorStride);
			if (s == 0) {
				// A declared kind the store never asked for. Leaving its tables zero is the whole point: nothing
				// constructs a segment set for it, so nothing can reach an address computed from a zero stride.
				continue;
			}
			if (pageSize % s != 0) {
				// Invariant I1: the stride must divide the page size so that no record straddles a page.
				throw new IllegalArgumentException("Stride " + s + " of " + kind + " does not divide page size " + pageSize);
			}
			stride[c] = s;
			recordsPerPage[c] = pageSize / s;

			long slots = Math.min(ElementId.MAX_SLOT + 1L, segmentCapacity / s);
			slotsPerSegment[c] = (int) slots;
			long data = slots * s;
			if (data % pageSize != 0) {
				throw new IllegalArgumentException("Data region of " + kind + " is not a whole number of pages");
			}
			int pages = (int) (data / pageSize);
			pagesPerSegment[c] = pages;

			long fsb = alignUp((slots + 7) / 8, pageSize);
			long pma = alignUp((long) pages * PMA_ENTRY_BYTES, pageSize);
			// The two superblocks occupy 8 KiB; with pages larger than that the first region still has to start on a
			// page boundary, or invariant I2 (page aligned data start) fails.
			long regionStart = alignUp(HEADER_BYTES, pageSize);

			fsbStart[c] = regionStart;
			fsbBytes[c] = fsb;
			pmaStart[c] = regionStart + fsb;
			pmaBytes[c] = pma;
			dataStart[c] = regionStart + fsb + pma;
			dataBytes[c] = data;
			fileBytes[c] = dataStart[c] + data;
		}
	}

	private static int resolveStride(Kind kind, int pageSize, int vectorStride) {
		return switch (kind.strideSpec()) {
			case Kind.PAGE_SIZED -> pageSize;
			case Kind.DECLARED -> vectorStride;
			default -> kind.strideSpec();
		};
	}

	/**
	 * Whether this store has this kind at all. False only for a {@link Kind#DECLARED} kind the store was created
	 * without — today, {@link Kind#VEC} in a store with no vector storage.
	 */
	public boolean isEnabled(Kind kind) {
		return kind != Kind.NULL && kind.code() < KINDS && stride[kind.code()] != 0;
	}

	/** The stride of {@link Kind#VEC}, or 0 when this store has no vector storage. */
	public int vectorStride() {
		return vectorStride;
	}

	private static long alignUp(long value, long alignment) {
		return (value + alignment - 1) / alignment * alignment;
	}

	public int pageSize() {
		return pageSize;
	}

	public long segmentCapacity() {
		return segmentCapacity;
	}

	public int stride(Kind kind) {
		return stride[kind.code()];
	}

	public int recordsPerPage(Kind kind) {
		return recordsPerPage[kind.code()];
	}

	public int slotsPerSegment(Kind kind) {
		return slotsPerSegment[kind.code()];
	}

	public int pagesPerSegment(Kind kind) {
		return pagesPerSegment[kind.code()];
	}

	public long fsbStart(Kind kind) {
		return fsbStart[kind.code()];
	}

	public long fsbBytes(Kind kind) {
		return fsbBytes[kind.code()];
	}

	public long pmaStart(Kind kind) {
		return pmaStart[kind.code()];
	}

	public long pmaBytes(Kind kind) {
		return pmaBytes[kind.code()];
	}

	public long dataStart(Kind kind) {
		return dataStart[kind.code()];
	}

	public long dataBytes(Kind kind) {
		return dataBytes[kind.code()];
	}

	public long fileBytes(Kind kind) {
		return fileBytes[kind.code()];
	}

	/** The address function. Depends only on kind and slot, never on content. */
	public long byteOffset(Kind kind, int slot) {
		return dataStart[kind.code()] + (long) slot * stride[kind.code()];
	}

	/** Index of the page holding the given slot. */
	public int pageOfSlot(Kind kind, int slot) {
		return slot / recordsPerPage[kind.code()];
	}

	public int slotInPage(Kind kind, int slot) {
		return slot % recordsPerPage[kind.code()];
	}

	@Override
	public String toString() {
		return "Geometry[pageSize=" + pageSize + ", segmentCapacity=" + segmentCapacity
			+ (vectorStride == 0 ? "" : ", vectorStride=" + vectorStride) + "]";
	}
}
