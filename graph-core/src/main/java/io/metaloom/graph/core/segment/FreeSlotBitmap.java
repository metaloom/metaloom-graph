package io.metaloom.graph.core.segment;

import io.metaloom.graph.core.txn.MetaJournal;
import io.metaloom.graph.core.util.Layouts;

/**
 * One bit per slot, 1 meaning allocated. This is the durable free list; the in-memory free rings in {@link SegmentSet}
 * are only a cache in front of it.
 * <p>
 * Access goes through {@link SegmentFile#readSlice}/{@link SegmentFile#writeSlice}, so bitmap changes made inside a
 * transaction are committed or discarded with everything else.
 *
 * <h2>Three ways to read a word</h2>
 *
 * <p>
 * A bitmap word is asked three different questions and they do not have the same answer:
 * </p>
 *
 * <ul>
 * <li><b>What does this transaction see?</b> — its snapshot's image of the word, with its own allocations and frees
 * laid over it from the {@link MetaJournal}. This is what {@link #isAllocated} answers, and it is what makes a slot a
 * concurrent transaction allocated correctly invisible.</li>
 * <li><b>What is actually taken?</b> — the live mapping, ignoring both the snapshot and the journal. Only the allocator
 * asks this, and it must: handing out a slot somebody else committed since this transaction began would put two records
 * at one id, which is the worst corruption this engine can produce. See the {@code Live} methods below.</li>
 * <li><b>What is durable?</b> — the mapping again, at commit time, which is where the journal is replayed.</li>
 * </ul>
 *
 * <p>
 * Writes never go straight to a word while a transaction is open. Sixty-four slots share one word, so two writers
 * allocating adjacent slots write the same eight bytes from different starting images, and no byte-level merge of those
 * is right. They go to the journal as set/clear masks and are folded into the live word at commit.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 3.2
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 6
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

	private long wordOffsetOf(int wordIndex) {
		return base + ((long) wordIndex << 3);
	}

	/** The word as the calling transaction sees it: its view, plus whatever it has allocated or freed itself. */
	private long word(int wordIndex) {
		return file.journalledBitmapWord(wordOffsetOf(wordIndex), wordIndex);
	}

	/** The word as it stands in the mapping, ignoring every transaction. The allocator's question. */
	/** Sixty-four slots' bits as the calling transaction sees them, for a scan that walks a word at a time. */
	long wordAt(int wordIndex) {
		return word(wordIndex);
	}

	private long liveWord(int wordIndex) {
		return file.mappedSlice(wordOffsetOf(wordIndex), 8).get(Layouts.LONG, 0);
	}

	private void writeWord(int wordIndex, long value) {
		file.writeSlice(wordOffsetOf(wordIndex), 8).set(Layouts.LONG, 0, value);
	}

	public boolean isAllocated(int slot) {
		checkSlot(slot);
		return (word(slot >>> 6) & (1L << (slot & 63))) != 0;
	}

	/** Whether the slot is taken according to the mapping, which is the only authority the allocator may use. */
	public boolean isAllocatedLive(int slot) {
		checkSlot(slot);
		return (liveWord(slot >>> 6) & (1L << (slot & 63))) != 0;
	}

	public void allocate(int slot) {
		checkSlot(slot);
		MetaJournal journal = file.journal();
		if (journal != null) {
			journal.bitmapSet(file.segmentKey(), slot);
			return;
		}
		int wordIndex = slot >>> 6;
		writeWord(wordIndex, word(wordIndex) | (1L << (slot & 63)));
	}

	public void free(int slot) {
		checkSlot(slot);
		MetaJournal journal = file.journal();
		if (journal != null) {
			journal.bitmapClear(file.segmentKey(), slot);
			return;
		}
		int wordIndex = slot >>> 6;
		writeWord(wordIndex, word(wordIndex) & ~(1L << (slot & 63)));
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
		return nextFree(from, limit, false);
	}

	/** As {@link #nextFree(int, int)}, but over the live mapping. Only the allocator may use it. */
	public int nextFreeLive(int from, int limit) {
		return nextFree(from, limit, true);
	}

	private int nextFree(int from, int limit, boolean live) {
		int end = Math.min(limit, slotCapacity);
		for (int slot = Math.max(0, from); slot < end;) {
			int wordIndex = slot >>> 6;
			long value = live ? liveWord(wordIndex) : word(wordIndex);
			long masked = ~value & (-1L << (slot & 63));
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
		return nextFreeRun(from, limit, length, slotsPerPage, false);
	}

	/** As {@link #nextFreeRun(int, int, int, int)}, but over the live mapping. Only the allocator may use it. */
	public int nextFreeRunLive(int from, int limit, int length, int slotsPerPage) {
		return nextFreeRun(from, limit, length, slotsPerPage, true);
	}

	private int nextFreeRun(int from, int limit, int length, int slotsPerPage, boolean live) {
		int end = Math.min(limit, slotCapacity);
		int slot = Math.max(0, from);
		while (slot + length <= end) {
			int candidate = nextFree(slot, end, live);
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
				if (live ? isAllocatedLive(candidate + i) : isAllocated(candidate + i)) {
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
			count += Long.bitCount(word(w));
		}
		int tail = end & 63;
		if (tail != 0) {
			count += Long.bitCount(word(fullWords) & ((1L << tail) - 1));
		}
		return count;
	}

	/**
	 * The highest allocated slot strictly below {@code limit}, or -1 when the region is empty. Compaction uses it to
	 * pull the high water mark down once the live records have been relocated.
	 */
	public int highestAllocatedBelow(int limit) {
		return highestAllocatedBelow(limit, false);
	}

	/** As {@link #highestAllocatedBelow(int)}, over the live mapping. Only the allocator may use it. */
	public int highestAllocatedBelowLive(int limit) {
		return highestAllocatedBelow(limit, true);
	}

	private int highestAllocatedBelow(int limit, boolean live) {
		int end = Math.min(limit, slotCapacity);
		if (end <= 0) {
			return -1;
		}
		int wordIndex = (end - 1) >>> 6;
		int tail = end & 63;
		long mask = tail == 0 ? -1L : (1L << tail) - 1;
		for (int w = wordIndex; w >= 0; w--) {
			long value = live ? liveWord(w) : word(w);
			if (w == wordIndex) {
				value &= mask;
			}
			if (value != 0) {
				return (w << 6) + (63 - Long.numberOfLeadingZeros(value));
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
