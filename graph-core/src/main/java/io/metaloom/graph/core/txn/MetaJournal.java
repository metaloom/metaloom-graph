package io.metaloom.graph.core.txn;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A write transaction's pending changes to the segment metadata regions, held as operations rather than as bytes.
 *
 * <h2>Why the metadata cannot go through the overlay</h2>
 *
 * <p>
 * Everything else a transaction writes is a record, and records compose: two writers touching one page write disjoint
 * byte runs, and applying both by union is exactly right. The metadata regions are the opposite case. The free-slot
 * bitmap packs sixty-four slots into one word, the superblock holds counters that every writer moves, and the page
 * metadata array holds a live count and an epoch per page. Two writers allocating adjacent slots write <em>the same
 * bytes</em>, and no byte-level merge of two independently derived images of that word is correct: one of them is
 * always describing a world in which the other's allocation never happened.
 * </p>
 *
 * <p>
 * Treating it as a conflict instead would be sound and useless — a bump allocator hands out consecutive slots, so
 * nearly every pair of concurrent inserts would land in one word and one of them would abort. The metadata is not
 * contended data, it is a resource ledger, and a ledger merges by replaying entries.
 * </p>
 *
 * <h2>What is recorded</h2>
 *
 * <p>
 * Bitmap changes as set/clear masks per 64-slot word, superblock counters as deltas, page metadata as deltas and
 * monotonic raises. Reads inside the transaction apply the journal over whatever the transaction's view shows, so a
 * writer sees its own allocations; {@link #replay} folds it into the live state under the commit lock, where "the live
 * state" is a fixed instant and the result is the union of every committed writer's ledger.
 * </p>
 *
 * <p>
 * <b>The high water mark is deliberately not here.</b> It is reserved from shared state at allocation time, because two
 * writers that each bumped a private copy of it would both claim the same slot. It is recorded only as
 * {@link SegmentEntry#headerTouched}, which tells the commit that the superblock has to be rewritten.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 6
 */
public final class MetaJournal {

	/** Unset sentinel for the fields that carry an absolute value rather than a delta. */
	public static final int NO_HINT = Integer.MIN_VALUE;

	/** Pending changes to one page's metadata entry. */
	public static final class PageEntry {

		int liveDelta;
		long epochBumps;
		int genFloor = -1;
		int freeHint = NO_HINT;
		int checksum = NO_HINT;
		int flags = NO_HINT;
		long lsn = -1;
	}

	/** Pending changes to one segment: its header counters, its bitmap and its page metadata. */
	public static final class SegmentEntry {

		long liveDelta;
		long freeDelta;
		/** Set outright by compaction, which recomputes the free count from the bitmap rather than tracking it. */
		long freeAbsolute = Long.MIN_VALUE;
		Boolean sealedTo;
		long lastLsn = -1;
		boolean headerTouched;

		final Map<Integer, long[]> bitmapWords = new LinkedHashMap<>();
		final Map<Integer, PageEntry> pages = new LinkedHashMap<>();

		public boolean isEmpty() {
			return liveDelta == 0 && freeDelta == 0 && freeAbsolute == Long.MIN_VALUE && sealedTo == null
				&& lastLsn < 0 && !headerTouched && bitmapWords.isEmpty() && pages.isEmpty();
		}

		public long liveDelta() {
			return liveDelta;
		}

		public long freeDelta() {
			return freeDelta;
		}

		public long freeAbsolute() {
			return freeAbsolute;
		}

		public Boolean sealedTo() {
			return sealedTo;
		}

		public long lastLsn() {
			return lastLsn;
		}

		public Map<Integer, long[]> bitmapWords() {
			return bitmapWords;
		}

		public Map<Integer, PageEntry> pages() {
			return pages;
		}
	}


	/**
	 * What the replay writes through: the live metadata, reached by the segment layer, which owns the byte offsets.
	 *
	 * <p>
	 * Every method here is the ordinary metadata operation and nothing more. That is the point of doing the replay with
	 * {@link WriteContext#replaying(boolean)} set: the journal stops intercepting, so {@code allocate} allocates and
	 * {@code liveCountDelta} moves a counter, exactly as they did when the engine had one writer — only now against the
	 * mapping as it stands under the commit lock rather than against whatever this transaction started from.
	 * </p>
	 */
	public interface Target {

		/** True when the segment still exists; false when it was retired between the write and the commit. */
		boolean has(long segmentKey);

		void allocate(long segmentKey, int slot);

		void free(long segmentKey, int slot);

		void pageLiveCountDelta(long segmentKey, int page, int delta);

		void pageEpochBump(long segmentKey, int page, long times);

		void pageGenFloor(long segmentKey, int page, int value);

		void pageFreeHint(long segmentKey, int page, int value);

		void pageChecksum(long segmentKey, int page, int value);

		void pageFlags(long segmentKey, int page, int value);

		void pageLsn(long segmentKey, int page, long value);

		void header(long segmentKey, SegmentEntry entry);
	}

	private final Map<Long, SegmentEntry> segments = new LinkedHashMap<>();

	public static long segmentKey(int kindCode, int segmentNumber) {
		return ((long) kindCode << 32) | (segmentNumber & 0xFFFFFFFFL);
	}

	public static int kindCodeOf(long segmentKey) {
		return (int) (segmentKey >>> 32);
	}

	public static int segmentNumberOf(long segmentKey) {
		return (int) segmentKey;
	}

	public boolean isEmpty() {
		return segments.isEmpty();
	}

	public Map<Long, SegmentEntry> segments() {
		return segments;
	}

	public void clear() {
		segments.clear();
	}

	private SegmentEntry entry(long segmentKey) {
		return segments.computeIfAbsent(segmentKey, k -> new SegmentEntry());
	}

	private SegmentEntry peek(long segmentKey) {
		return segments.get(segmentKey);
	}

	private PageEntry pageEntry(long segmentKey, int page) {
		return entry(segmentKey).pages.computeIfAbsent(page, k -> new PageEntry());
	}

	private PageEntry peekPage(long segmentKey, int page) {
		SegmentEntry segment = peek(segmentKey);
		return segment == null ? null : segment.pages.get(page);
	}

	// ---------------------------------------------------------------- bitmap

	public void bitmapSet(long segmentKey, int slot) {
		long[] masks = entry(segmentKey).bitmapWords.computeIfAbsent(slot >>> 6, k -> new long[2]);
		long bit = 1L << (slot & 63);
		masks[0] |= bit;
		masks[1] &= ~bit;
	}

	public void bitmapClear(long segmentKey, int slot) {
		long[] masks = entry(segmentKey).bitmapWords.computeIfAbsent(slot >>> 6, k -> new long[2]);
		long bit = 1L << (slot & 63);
		masks[1] |= bit;
		masks[0] &= ~bit;
	}

	/** The word as this transaction sees it: what its view shows, plus its own allocations and frees. */
	public long bitmapWord(long segmentKey, int wordIndex, long viewWord) {
		SegmentEntry segment = peek(segmentKey);
		if (segment == null) {
			return viewWord;
		}
		long[] masks = segment.bitmapWords.get(wordIndex);
		return masks == null ? viewWord : (viewWord | masks[0]) & ~masks[1];
	}

	// ---------------------------------------------------------------- superblock counters

	public void liveCountDelta(long segmentKey, long delta) {
		SegmentEntry segment = entry(segmentKey);
		segment.liveDelta += delta;
		segment.headerTouched = true;
	}

	public void freeCountDelta(long segmentKey, long delta) {
		SegmentEntry segment = entry(segmentKey);
		segment.freeDelta += delta;
		segment.headerTouched = true;
	}

	public void freeCountAbsolute(long segmentKey, long value) {
		SegmentEntry segment = entry(segmentKey);
		segment.freeAbsolute = value;
		segment.freeDelta = 0;
		segment.headerTouched = true;
	}

	public void sealed(long segmentKey, boolean value) {
		SegmentEntry segment = entry(segmentKey);
		segment.sealedTo = value;
		segment.headerTouched = true;
	}

	public void lastLsn(long segmentKey, long lsn) {
		SegmentEntry segment = entry(segmentKey);
		segment.lastLsn = lsn;
		segment.headerTouched = true;
	}

	/** Records that the header must be rewritten at commit even though no counter in the journal moved. */
	public void headerTouched(long segmentKey) {
		entry(segmentKey).headerTouched = true;
	}

	public long liveCountOver(long segmentKey, long shared) {
		SegmentEntry segment = peek(segmentKey);
		return segment == null ? shared : shared + segment.liveDelta;
	}

	public long freeCountOver(long segmentKey, long shared) {
		SegmentEntry segment = peek(segmentKey);
		if (segment == null) {
			return shared;
		}
		return segment.freeAbsolute == Long.MIN_VALUE ? shared + segment.freeDelta : segment.freeAbsolute;
	}

	public boolean sealedOver(long segmentKey, boolean shared) {
		SegmentEntry segment = peek(segmentKey);
		return segment == null || segment.sealedTo == null ? shared : segment.sealedTo;
	}

	public long lastLsnOver(long segmentKey, long shared) {
		SegmentEntry segment = peek(segmentKey);
		return segment == null || segment.lastLsn < 0 ? shared : segment.lastLsn;
	}

	// ---------------------------------------------------------------- page metadata

	public void pageLiveCountDelta(long segmentKey, int page, int delta) {
		pageEntry(segmentKey, page).liveDelta += delta;
	}

	public void pageEpochBump(long segmentKey, int page) {
		pageEntry(segmentKey, page).epochBumps++;
	}

	public void pageGenFloor(long segmentKey, int page, int value) {
		PageEntry entry = pageEntry(segmentKey, page);
		entry.genFloor = Math.max(entry.genFloor, value);
	}

	public void pageFreeHint(long segmentKey, int page, int value) {
		pageEntry(segmentKey, page).freeHint = value;
	}

	public void pageChecksum(long segmentKey, int page, int value) {
		pageEntry(segmentKey, page).checksum = value;
	}

	public void pageFlags(long segmentKey, int page, int value) {
		pageEntry(segmentKey, page).flags = value;
	}

	public void pageLsn(long segmentKey, int page, long value) {
		pageEntry(segmentKey, page).lsn = value;
	}

	public int pageLiveCountOver(long segmentKey, int page, int viewValue) {
		PageEntry entry = peekPage(segmentKey, page);
		return entry == null ? viewValue : viewValue + entry.liveDelta;
	}

	public long pageEpochOver(long segmentKey, int page, long viewValue) {
		PageEntry entry = peekPage(segmentKey, page);
		return entry == null ? viewValue : viewValue + entry.epochBumps;
	}

	public int pageGenFloorOver(long segmentKey, int page, int viewValue) {
		PageEntry entry = peekPage(segmentKey, page);
		return entry == null ? viewValue : Math.max(viewValue, entry.genFloor);
	}

	public int pageFreeHintOver(long segmentKey, int page, int viewValue) {
		PageEntry entry = peekPage(segmentKey, page);
		return entry == null || entry.freeHint == NO_HINT ? viewValue : entry.freeHint;
	}

	public int pageChecksumOver(long segmentKey, int page, int viewValue) {
		PageEntry entry = peekPage(segmentKey, page);
		return entry == null || entry.checksum == NO_HINT ? viewValue : entry.checksum;
	}

	public int pageFlagsOver(long segmentKey, int page, int viewValue) {
		PageEntry entry = peekPage(segmentKey, page);
		return entry == null || entry.flags == NO_HINT ? viewValue : entry.flags;
	}

	public long pageLsnOver(long segmentKey, int page, long viewValue) {
		PageEntry entry = peekPage(segmentKey, page);
		return entry == null || entry.lsn < 0 ? viewValue : entry.lsn;
	}

	// ---------------------------------------------------------------- replay

	/**
	 * Folds every recorded operation into the live metadata.
	 *
	 * <p>
	 * Called under the commit lock and nowhere else. The target writes through pages rebased onto the mapping as it
	 * stands at that moment, so a delta lands on top of everything committed since this transaction began rather than
	 * on top of the image it happened to read.
	 * </p>
	 */
	public void replay(Target target) {
		for (Map.Entry<Long, SegmentEntry> segment : segments.entrySet()) {
			long segmentKey = segment.getKey();
			SegmentEntry entry = segment.getValue();
			if (entry.isEmpty() || !target.has(segmentKey)) {
				// A missing segment was retired between the write and the commit. Only empty trailing segments are
				// retired and only under exclusion, so there is nothing of this transaction's left to place.
				continue;
			}
			for (Map.Entry<Integer, long[]> word : entry.bitmapWords.entrySet()) {
				int firstSlot = word.getKey() << 6;
				long[] masks = word.getValue();
				for (long bits = masks[0]; bits != 0; bits &= bits - 1) {
					target.allocate(segmentKey, firstSlot + Long.numberOfTrailingZeros(bits));
				}
				for (long bits = masks[1]; bits != 0; bits &= bits - 1) {
					target.free(segmentKey, firstSlot + Long.numberOfTrailingZeros(bits));
				}
			}
			for (Map.Entry<Integer, PageEntry> page : entry.pages.entrySet()) {
				int index = page.getKey();
				PageEntry pageEntry = page.getValue();
				if (pageEntry.liveDelta != 0) {
					target.pageLiveCountDelta(segmentKey, index, pageEntry.liveDelta);
				}
				if (pageEntry.epochBumps != 0) {
					target.pageEpochBump(segmentKey, index, pageEntry.epochBumps);
				}
				if (pageEntry.genFloor > 0) {
					target.pageGenFloor(segmentKey, index, pageEntry.genFloor);
				}
				if (pageEntry.freeHint != NO_HINT) {
					target.pageFreeHint(segmentKey, index, pageEntry.freeHint);
				}
				if (pageEntry.checksum != NO_HINT) {
					target.pageChecksum(segmentKey, index, pageEntry.checksum);
				}
				if (pageEntry.flags != NO_HINT) {
					target.pageFlags(segmentKey, index, pageEntry.flags);
				}
				if (pageEntry.lsn >= 0) {
					target.pageLsn(segmentKey, index, pageEntry.lsn);
				}
			}
			if (entry.headerTouched) {
				target.header(segmentKey, entry);
			}
		}
	}
}
