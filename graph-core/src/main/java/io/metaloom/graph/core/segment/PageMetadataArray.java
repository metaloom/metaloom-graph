package io.metaloom.graph.core.segment;

import io.metaloom.graph.core.txn.MetaJournal;
import io.metaloom.graph.core.util.Layouts;

/**
 * Out-of-line per-page metadata: 32 bytes per data page.
 * <p>
 * The metadata is deliberately not stored inside the page. Keeping data pages as pure record arrays is what makes
 * {@code dataStart + slot * stride} exact (invariants I3 and I4).
 *
 * <pre>
 *  0  8  page_lsn
 *  8  8  epoch
 * 16  4  checksum
 * 20  2  live_count
 * 22  2  flags
 * 24  4  free_hint
 * 28  2  gen_floor
 * 30  2  reserved
 * </pre>
 *
 * <p>
 * <b>{@code gen_floor} is here rather than in the page because the page is exactly what gets destroyed.</b> Hole
 * punching returns an all-free page's blocks to the filesystem, after which it reads as zeros — including the
 * generation counter inside every record on it, which is the engine's dangling-id detector. The metadata array is a
 * separate region and is never punched, so it is the one place a floor can survive.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 3.3
 */
public final class PageMetadataArray {

	static final int ENTRY_BYTES = Geometry.PMA_ENTRY_BYTES;

	private static final int OFF_LSN = 0;
	private static final int OFF_EPOCH = 8;
	private static final int OFF_CHECKSUM = 16;
	private static final int OFF_LIVE_COUNT = 20;
	private static final int OFF_FLAGS = 22;
	private static final int OFF_FREE_HINT = 24;
	private static final int OFF_GEN_FLOOR = 28;

	private final SegmentFile file;
	private final long base;
	private final int pageCount;

	PageMetadataArray(SegmentFile file, long base, int pageCount) {
		this.file = file;
		this.base = base;
		this.pageCount = pageCount;
	}

	public int pageCount() {
		return pageCount;
	}

	private long offset(int page, int field) {
		if (page < 0 || page >= pageCount) {
			throw new IndexOutOfBoundsException("page " + page + " outside " + pageCount);
		}
		return base + (long) page * ENTRY_BYTES + field;
	}

	/**
	 * The journal this page metadata is currently being written through, or null when writes go straight to the page.
	 *
	 * <p>
	 * Null outside a transaction — recovery, checkpointing, verification — and null again during the commit's replay,
	 * which is what makes the same methods perform the operations they queued.
	 * </p>
	 */
	private MetaJournal journal() {
		return file.journal();
	}

	public long lsn(int page) {
		long stored = file.readSlice(offset(page, OFF_LSN), 8).get(Layouts.LONG, 0);
		MetaJournal journal = journal();
		return journal == null ? stored : journal.pageLsnOver(file.segmentKey(), page, stored);
	}

	public void lsn(int page, long lsn) {
		MetaJournal journal = journal();
		if (journal != null) {
			journal.pageLsn(file.segmentKey(), page, lsn);
			return;
		}
		file.writeSlice(offset(page, OFF_LSN), 8).set(Layouts.LONG, 0, lsn);
	}

	public long epoch(int page) {
		long stored = file.readSlice(offset(page, OFF_EPOCH), 8).get(Layouts.LONG, 0);
		MetaJournal journal = journal();
		return journal == null ? stored : journal.pageEpochOver(file.segmentKey(), page, stored);
	}

	/** Bumped by every writer before and after it mutates the page; readers use it for optimistic reads. */
	public void bumpEpoch(int page) {
		MetaJournal journal = journal();
		if (journal != null) {
			journal.pageEpochBump(file.segmentKey(), page);
			return;
		}
		bumpEpoch(page, 1);
	}

	/** Adds several bumps at once. The commit replay uses it to fold a transaction's worth into one write. */
	public void bumpEpoch(int page, long times) {
		long off = offset(page, OFF_EPOCH);
		long value = file.readSlice(off, 8).get(Layouts.LONG, 0);
		file.writeSlice(off, 8).set(Layouts.LONG, 0, value + times);
	}

	public int checksum(int page) {
		int stored = file.readSlice(offset(page, OFF_CHECKSUM), 4).get(Layouts.INT, 0);
		MetaJournal journal = journal();
		return journal == null ? stored : journal.pageChecksumOver(file.segmentKey(), page, stored);
	}

	public void checksum(int page, int value) {
		MetaJournal journal = journal();
		if (journal != null) {
			journal.pageChecksum(file.segmentKey(), page, value);
			return;
		}
		file.writeSlice(offset(page, OFF_CHECKSUM), 4).set(Layouts.INT, 0, value);
	}

	public int liveCount(int page) {
		int stored = Short.toUnsignedInt(file.readSlice(offset(page, OFF_LIVE_COUNT), 2).get(Layouts.SHORT, 0));
		MetaJournal journal = journal();
		return journal == null ? stored : journal.pageLiveCountOver(file.segmentKey(), page, stored);
	}

	public void liveCountDelta(int page, int delta) {
		MetaJournal journal = journal();
		if (journal != null) {
			journal.pageLiveCountDelta(file.segmentKey(), page, delta);
			return;
		}
		long off = offset(page, OFF_LIVE_COUNT);
		int value = Short.toUnsignedInt(file.readSlice(off, 2).get(Layouts.SHORT, 0)) + delta;
		if (value < 0) {
			throw new IllegalStateException("Page " + page + " live count would go negative");
		}
		file.writeSlice(off, 2).set(Layouts.SHORT, 0, (short) value);
	}

	public int flags(int page) {
		int stored = Short.toUnsignedInt(file.readSlice(offset(page, OFF_FLAGS), 2).get(Layouts.SHORT, 0));
		MetaJournal journal = journal();
		return journal == null ? stored : journal.pageFlagsOver(file.segmentKey(), page, stored);
	}

	public void flags(int page, int value) {
		MetaJournal journal = journal();
		if (journal != null) {
			journal.pageFlags(file.segmentKey(), page, value);
			return;
		}
		file.writeSlice(offset(page, OFF_FLAGS), 2).set(Layouts.SHORT, 0, (short) value);
	}

	public int freeHint(int page) {
		int stored = file.readSlice(offset(page, OFF_FREE_HINT), 4).get(Layouts.INT, 0);
		MetaJournal journal = journal();
		return journal == null ? stored : journal.pageFreeHintOver(file.segmentKey(), page, stored);
	}

	public void freeHint(int page, int value) {
		MetaJournal journal = journal();
		if (journal != null) {
			journal.pageFreeHint(file.segmentKey(), page, value);
			return;
		}
		file.writeSlice(offset(page, OFF_FREE_HINT), 4).set(Layouts.INT, 0, value);
	}

	/**
	 * The generation no slot on this page may be handed out below.
	 *
	 * <p>
	 * Zero on a page that has never been punched, which is every page in a store with no native layer — the record's
	 * own counter is then the whole story and this costs a read that always returns zero.
	 * </p>
	 */
	public int genFloor(int page) {
		int stored = Short.toUnsignedInt(file.readSlice(offset(page, OFF_GEN_FLOOR), 2).get(Layouts.SHORT, 0));
		MetaJournal journal = journal();
		return journal == null ? stored : journal.pageGenFloorOver(file.segmentKey(), page, stored);
	}

	public void raiseGenFloor(int page, int value) {
		int capped = Math.min(value, 0xFFFF);
		if (capped <= genFloor(page)) {
			return;
		}
		MetaJournal journal = journal();
		if (journal != null) {
			journal.pageGenFloor(file.segmentKey(), page, capped);
			return;
		}
		file.writeSlice(offset(page, OFF_GEN_FLOOR), 2).set(Layouts.SHORT, 0, (short) capped);
	}
}
