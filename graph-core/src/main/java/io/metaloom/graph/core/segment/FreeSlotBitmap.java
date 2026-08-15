package io.metaloom.graph.core.segment;

import io.metaloom.graph.core.util.Layouts;

/**
 * One bit per slot, 1 meaning allocated. This is the durable free list; the in-memory free rings in {@link SegmentSet}
 * are only a cache in front of it.
 * <p>
 * Access goes through {@link SegmentFile#readSlice}/{@link SegmentFile#writeSlice}, so bitmap changes made inside a
 * transaction land in the page overlay and are committed or discarded with everything else.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 3.2
 */
public final class FreeSlotBitmap {

	private final SegmentFile file;
	private final long base;
	private final int slotCapacity;

	FreeSlotBitmap(SegmentFile file, long base, int slotCapacity) {
		this.file = file;
		this.base = base;
		this.slotCapacity = slotCapacity;
	}

	private long wordOffset(int slot) {
		return base + (((long) slot >>> 6) << 3);
	}

	private long readWord(int slot) {
		return file.readSlice(wordOffset(slot), 8).get(Layouts.LONG, 0);
	}

	private void writeWord(int slot, long value) {
		file.writeSlice(wordOffset(slot), 8).set(Layouts.LONG, 0, value);
	}

	public boolean isAllocated(int slot) {
		checkSlot(slot);
		return (readWord(slot) & (1L << (slot & 63))) != 0;
	}

	public void allocate(int slot) {
		checkSlot(slot);
		writeWord(slot, readWord(slot) | (1L << (slot & 63)));
	}

	public void free(int slot) {
		checkSlot(slot);
		writeWord(slot, readWord(slot) & ~(1L << (slot & 63)));
	}

	/** Returns the first free slot at or after {@code from}, or -1. Scans 64 slots per step. */
	public int nextFree(int from) {
		return nextFree(from, slotCapacity);
	}

	/**
	 * Returns the first free slot in {@code [from, limit)}, or -1.
	 * <p>
	 * The bounded form is what compaction uses: a destination is only interesting below the source position and below
	 * the high water mark, and scanning the full 24 bit slot space of a nearly empty segment would dominate the cost of
	 * relocating a record.
	 */
	public int nextFree(int from, int limit) {
		int end = Math.min(limit, slotCapacity);
		for (int slot = Math.max(0, from); slot < end;) {
			int wordIndex = slot >>> 6;
			long word = readWord(slot);
			long masked = ~word & (-1L << (slot & 63));
			if (masked != 0) {
				int candidate = (wordIndex << 6) + Long.numberOfTrailingZeros(masked);
				return candidate < end ? candidate : -1;
			}
			slot = (wordIndex + 1) << 6;
		}
		return -1;
	}

	/**
	 * Returns the first slot of a run of {@code length} free slots at or after {@code from} that does not cross a page
	 * boundary, or -1.
	 */
	public int nextFreeRun(int from, int length, int slotsPerPage) {
		return nextFreeRun(from, slotCapacity, length, slotsPerPage);
	}

	/** As {@link #nextFreeRun(int, int, int)} but bounded above by {@code limit}. */
	public int nextFreeRun(int from, int limit, int length, int slotsPerPage) {
		int end = Math.min(limit, slotCapacity);
		int slot = Math.max(0, from);
		while (slot + length <= end) {
			int candidate = nextFree(slot, end);
			if (candidate < 0) {
				return -1;
			}
			int inPage = candidate % slotsPerPage;
			if (inPage + length > slotsPerPage) {
				// Would straddle a page: restart at the next page boundary (invariant I1).
				slot = candidate - inPage + slotsPerPage;
				continue;
			}
			boolean ok = true;
			for (int i = 1; i < length; i++) {
				if (isAllocated(candidate + i)) {
					ok = false;
					slot = candidate + i + 1;
					break;
				}
			}
			if (ok) {
				return candidate + length <= end ? candidate : -1;
			}
		}
		return -1;
	}

	public long countAllocated() {
		return countAllocated(slotCapacity);
	}

	/** Allocated slots in {@code [0, limit)}. Word at a time, with the tail word masked. */
	public long countAllocated(int limit) {
		int end = Math.min(limit, slotCapacity);
		long count = 0;
		int fullWords = end >>> 6;
		for (int w = 0; w < fullWords; w++) {
			count += Long.bitCount(file.readSlice(base + ((long) w << 3), 8).get(Layouts.LONG, 0));
		}
		int tail = end & 63;
		if (tail != 0) {
			long word = file.readSlice(base + ((long) fullWords << 3), 8).get(Layouts.LONG, 0);
			count += Long.bitCount(word & ((1L << tail) - 1));
		}
		return count;
	}

	/**
	 * The highest allocated slot strictly below {@code limit}, or -1 when the region is empty. Compaction uses it to
	 * pull the high water mark down once the live records have been relocated.
	 */
	public int highestAllocatedBelow(int limit) {
		int end = Math.min(limit, slotCapacity);
		if (end <= 0) {
			return -1;
		}
		int wordIndex = (end - 1) >>> 6;
		int tail = end & 63;
		long mask = tail == 0 ? -1L : (1L << tail) - 1;
		for (int w = wordIndex; w >= 0; w--) {
			long word = file.readSlice(base + ((long) w << 3), 8).get(Layouts.LONG, 0);
			if (w == wordIndex) {
				word &= mask;
			}
			if (word != 0) {
				return (w << 6) + (63 - Long.numberOfLeadingZeros(word));
			}
		}
		return -1;
	}

	private void checkSlot(int slot) {
		if (slot < 0 || slot >= slotCapacity) {
			throw new IndexOutOfBoundsException("slot " + slot + " outside capacity " + slotCapacity);
		}
	}
}
