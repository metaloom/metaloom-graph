package io.metaloom.graph.core.segment;

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
 * 28  4  reserved
 * </pre>
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

	public long lsn(int page) {
		return file.readSlice(offset(page, OFF_LSN), 8).get(Layouts.LONG, 0);
	}

	public void lsn(int page, long lsn) {
		file.writeSlice(offset(page, OFF_LSN), 8).set(Layouts.LONG, 0, lsn);
	}

	public long epoch(int page) {
		return file.readSlice(offset(page, OFF_EPOCH), 8).get(Layouts.LONG, 0);
	}

	/** Bumped by every writer before and after it mutates the page; readers use it for optimistic reads. */
	public void bumpEpoch(int page) {
		long off = offset(page, OFF_EPOCH);
		long value = file.readSlice(off, 8).get(Layouts.LONG, 0);
		file.writeSlice(off, 8).set(Layouts.LONG, 0, value + 1);
	}

	public int checksum(int page) {
		return file.readSlice(offset(page, OFF_CHECKSUM), 4).get(Layouts.INT, 0);
	}

	public void checksum(int page, int value) {
		file.writeSlice(offset(page, OFF_CHECKSUM), 4).set(Layouts.INT, 0, value);
	}

	public int liveCount(int page) {
		return Short.toUnsignedInt(file.readSlice(offset(page, OFF_LIVE_COUNT), 2).get(Layouts.SHORT, 0));
	}

	public void liveCountDelta(int page, int delta) {
		long off = offset(page, OFF_LIVE_COUNT);
		int value = Short.toUnsignedInt(file.readSlice(off, 2).get(Layouts.SHORT, 0)) + delta;
		if (value < 0) {
			throw new IllegalStateException("Page " + page + " live count would go negative");
		}
		file.writeSlice(off, 2).set(Layouts.SHORT, 0, (short) value);
	}

	public int flags(int page) {
		return Short.toUnsignedInt(file.readSlice(offset(page, OFF_FLAGS), 2).get(Layouts.SHORT, 0));
	}

	public void flags(int page, int value) {
		file.writeSlice(offset(page, OFF_FLAGS), 2).set(Layouts.SHORT, 0, (short) value);
	}

	public int freeHint(int page) {
		return file.readSlice(offset(page, OFF_FREE_HINT), 4).get(Layouts.INT, 0);
	}

	public void freeHint(int page, int value) {
		file.writeSlice(offset(page, OFF_FREE_HINT), 4).set(Layouts.INT, 0, value);
	}
}
