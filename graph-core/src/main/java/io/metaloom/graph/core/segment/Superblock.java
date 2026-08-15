package io.metaloom.graph.core.segment;

import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.util.Crc;
import io.metaloom.graph.core.util.Layouts;

/**
 * The A/B double buffered segment header.
 * <p>
 * This is the one place the design uses Copy-on-Write verbatim: a writer builds the new header, writes it to the
 * currently inactive copy and issues a durability barrier. The active copy is whichever has the higher sequence and a
 * valid CRC, so a torn write can only ever damage the inactive one.
 * <p>
 * The counters are cached in Java fields and written out by {@link #flush()}. A rolled back transaction restores them
 * with {@link #reload()}.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 3.1
 */
public final class Superblock {

	public static final long MAGIC = 0x004745535347_4C4DL;
	public static final int FORMAT_VERSION = 2;

	private static final int OFF_MAGIC = 0;
	private static final int OFF_FORMAT_VERSION = 8;
	private static final int OFF_KIND = 12;
	private static final int OFF_SEGMENT_NUMBER = 16;
	private static final int OFF_PAGE_SIZE = 20;
	private static final int OFF_STRIDE = 24;
	private static final int OFF_SLOTS_CAPACITY = 32;
	private static final int OFF_DATA_START = 40;
	private static final int OFF_FSB_START = 48;
	private static final int OFF_PMA_START = 56;
	private static final int OFF_HIGH_WATER_SLOT = 64;
	private static final int OFF_LIVE_COUNT = 72;
	private static final int OFF_FREE_COUNT = 80;
	private static final int OFF_LAST_LSN = 88;
	private static final int OFF_SEQUENCE = 120;
	private static final int OFF_FLAGS = 128;
	private static final int OFF_CRC = Geometry.SUPERBLOCK_BYTES - 4;

	public static final long FLAG_SEALED = 1L;

	private final SegmentFile file;
	private final Geometry geometry;
	private final Kind kind;
	private final int segmentNumber;

	private int activeOffset;
	private long sequence;

	private long highWaterSlot;
	private long liveCount;
	private long freeCount;
	private long lastLsn;
	private long flags;
	/** True when a counter changed since the last flush, so commit knows whether the header must be rewritten. */
	private boolean counterDirty;

	private Superblock(SegmentFile file, Geometry geometry, Kind kind, int segmentNumber) {
		this.file = file;
		this.geometry = geometry;
		this.kind = kind;
		this.segmentNumber = segmentNumber;
	}

	static Superblock create(SegmentFile file, Geometry geometry, Kind kind, int segmentNumber) {
		Superblock sb = new Superblock(file, geometry, kind, segmentNumber);
		sb.activeOffset = Geometry.SUPERBLOCK_B_OFFSET;
		sb.flush();
		sb.flush();
		return sb;
	}

	static Superblock open(SegmentFile file, Geometry geometry, Kind kind, int segmentNumber) {
		Superblock sb = new Superblock(file, geometry, kind, segmentNumber);
		boolean validA = sb.isValid(Geometry.SUPERBLOCK_A_OFFSET);
		boolean validB = sb.isValid(Geometry.SUPERBLOCK_B_OFFSET);
		if (!validA && !validB) {
			throw new PageCorruptException("Both superblock copies of " + kind + " segment " + segmentNumber
				+ " are invalid");
		}
		long seqA = validA ? sb.readLong(Geometry.SUPERBLOCK_A_OFFSET, OFF_SEQUENCE) : Long.MIN_VALUE;
		long seqB = validB ? sb.readLong(Geometry.SUPERBLOCK_B_OFFSET, OFF_SEQUENCE) : Long.MIN_VALUE;
		sb.activeOffset = seqA >= seqB ? Geometry.SUPERBLOCK_A_OFFSET : Geometry.SUPERBLOCK_B_OFFSET;
		sb.reload();
		sb.validateGeometry();
		return sb;
	}

	private long readLong(int base, int field) {
		return file.readSlice(base + field, 8).get(Layouts.LONG, 0);
	}

	private int readInt(int base, int field) {
		return file.readSlice(base + field, 4).get(Layouts.INT, 0);
	}

	private boolean isValid(int base) {
		if (readLong(base, OFF_MAGIC) != MAGIC) {
			return false;
		}
		MemorySegment copy = file.readSlice(base, Geometry.SUPERBLOCK_BYTES);
		return copy.get(Layouts.INT, OFF_CRC) == Crc.crc32c(copy, 0, OFF_CRC);
	}

	/**
	 * Re-picks the active A/B copy and reloads it.
	 * <p>
	 * Recovery writes a newer header into whichever copy was inactive at commit time, so after a replay the in-memory
	 * choice of active copy is stale. Reloading without re-picking would keep reading the older counters while the
	 * bitmap already reflects the replayed allocations.
	 */
	public void selectActive() {
		boolean validA = isValid(Geometry.SUPERBLOCK_A_OFFSET);
		boolean validB = isValid(Geometry.SUPERBLOCK_B_OFFSET);
		if (!validA && !validB) {
			throw new PageCorruptException("Both superblock copies of " + kind + " segment " + segmentNumber
				+ " are invalid");
		}
		long seqA = validA ? readLong(Geometry.SUPERBLOCK_A_OFFSET, OFF_SEQUENCE) : Long.MIN_VALUE;
		long seqB = validB ? readLong(Geometry.SUPERBLOCK_B_OFFSET, OFF_SEQUENCE) : Long.MIN_VALUE;
		activeOffset = seqA >= seqB ? Geometry.SUPERBLOCK_A_OFFSET : Geometry.SUPERBLOCK_B_OFFSET;
		reload();
	}

	/** Re-reads the cached counters from the active copy. Used after a rollback. */
	public void reload() {
		counterDirty = false;
		sequence = readLong(activeOffset, OFF_SEQUENCE);
		highWaterSlot = readLong(activeOffset, OFF_HIGH_WATER_SLOT);
		liveCount = readLong(activeOffset, OFF_LIVE_COUNT);
		freeCount = readLong(activeOffset, OFF_FREE_COUNT);
		lastLsn = readLong(activeOffset, OFF_LAST_LSN);
		flags = readLong(activeOffset, OFF_FLAGS);
	}

	private void validateGeometry() {
		int version = readInt(activeOffset, OFF_FORMAT_VERSION);
		if (version != FORMAT_VERSION) {
			throw new FormatVersionException("Segment format version " + version + ", expected " + FORMAT_VERSION);
		}
		expect("kind", readInt(activeOffset, OFF_KIND), kind.code());
		expect("segment number", readInt(activeOffset, OFF_SEGMENT_NUMBER), segmentNumber);
		expect("page size", readInt(activeOffset, OFF_PAGE_SIZE), geometry.pageSize());
		expect("stride", readLong(activeOffset, OFF_STRIDE), geometry.stride(kind));
		expect("slot capacity", readLong(activeOffset, OFF_SLOTS_CAPACITY), geometry.slotsPerSegment(kind));
		expect("data start", readLong(activeOffset, OFF_DATA_START), geometry.dataStart(kind));
		expect("fsb start", readLong(activeOffset, OFF_FSB_START), geometry.fsbStart(kind));
		expect("pma start", readLong(activeOffset, OFF_PMA_START), geometry.pmaStart(kind));
	}

	private void expect(String what, long actual, long expected) {
		if (actual != expected) {
			throw new FormatVersionException("Segment " + kind + "-" + segmentNumber + " has " + what + " " + actual
				+ " but this store expects " + expected);
		}
	}

	/** Writes the current state into the inactive copy and flips to it. */
	public void flush() {
		int target = activeOffset == Geometry.SUPERBLOCK_A_OFFSET
			? Geometry.SUPERBLOCK_B_OFFSET
			: Geometry.SUPERBLOCK_A_OFFSET;
		MemorySegment copy = file.writeSlice(target, Geometry.SUPERBLOCK_BYTES);
		copy.fill((byte) 0);
		copy.set(Layouts.LONG, OFF_MAGIC, MAGIC);
		copy.set(Layouts.INT, OFF_FORMAT_VERSION, FORMAT_VERSION);
		copy.set(Layouts.INT, OFF_KIND, kind.code());
		copy.set(Layouts.INT, OFF_SEGMENT_NUMBER, segmentNumber);
		copy.set(Layouts.INT, OFF_PAGE_SIZE, geometry.pageSize());
		copy.set(Layouts.LONG, OFF_STRIDE, geometry.stride(kind));
		copy.set(Layouts.LONG, OFF_SLOTS_CAPACITY, geometry.slotsPerSegment(kind));
		copy.set(Layouts.LONG, OFF_DATA_START, geometry.dataStart(kind));
		copy.set(Layouts.LONG, OFF_FSB_START, geometry.fsbStart(kind));
		copy.set(Layouts.LONG, OFF_PMA_START, geometry.pmaStart(kind));
		copy.set(Layouts.LONG, OFF_HIGH_WATER_SLOT, highWaterSlot);
		copy.set(Layouts.LONG, OFF_LIVE_COUNT, liveCount);
		copy.set(Layouts.LONG, OFF_FREE_COUNT, freeCount);
		copy.set(Layouts.LONG, OFF_LAST_LSN, lastLsn);
		copy.set(Layouts.LONG, OFF_SEQUENCE, ++sequence);
		copy.set(Layouts.LONG, OFF_FLAGS, flags);
		copy.set(Layouts.INT, OFF_CRC, Crc.crc32c(copy, 0, OFF_CRC));
		activeOffset = target;
		counterDirty = false;
	}

	public long highWaterSlot() {
		return highWaterSlot;
	}

	public void highWaterSlot(long value) {
		this.highWaterSlot = value;
		this.counterDirty = true;
	}

	public long liveCount() {
		return liveCount;
	}

	public void liveCountDelta(long delta) {
		this.liveCount += delta;
		this.counterDirty = true;
	}

	public long freeCount() {
		return freeCount;
	}

	public void freeCountDelta(long delta) {
		this.freeCount += delta;
		this.counterDirty = true;
	}

	/** Sets the free count outright. Compaction recomputes it from the bitmap rather than tracking a delta. */
	public void freeCount(long value) {
		this.freeCount = value;
		this.counterDirty = true;
	}

	public long lastLsn() {
		return lastLsn;
	}

	public void lastLsn(long lsn) {
		this.lastLsn = lsn;
		this.counterDirty = true;
	}

	public boolean counterDirty() {
		return counterDirty;
	}

	public long sequence() {
		return sequence;
	}

	public boolean sealed() {
		return (flags & FLAG_SEALED) != 0;
	}

	public void sealed(boolean value) {
		flags = value ? flags | FLAG_SEALED : flags & ~FLAG_SEALED;
		this.counterDirty = true;
	}
}
