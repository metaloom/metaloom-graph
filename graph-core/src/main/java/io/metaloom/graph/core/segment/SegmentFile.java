package io.metaloom.graph.core.segment;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.metrics.IoCounters;
import io.metaloom.graph.core.nativelib.NativeLib;
import io.metaloom.graph.core.page.OverlayHolder;
import io.metaloom.graph.core.page.PageOverlay;
import io.metaloom.graph.core.txn.Snapshot;
import io.metaloom.graph.core.txn.SnapshotHolder;
import io.metaloom.graph.core.util.Crc;
import io.metaloom.graph.core.util.Layouts;

/**
 * One {@code .seg} file: mapped once, sliced many times.
 * <p>
 * The whole file is mapped a single time from a store-scoped arena. This is the central fix over the proof of concept,
 * which opened a {@link FileChannel} and created a fresh mapping for every single record access and therefore needed
 * {@code vm.max_map_count} raised into the millions.
 * <p>
 * Every access goes through {@link #readSlice} or {@link #writeSlice}, which route through the active transaction's
 * {@link PageOverlay} when there is one. The whole file — superblocks, bitmap, page metadata and data — is treated as
 * a uniform array of pages for that purpose, so metadata mutations are as transactional as record mutations.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 3
 */
public final class SegmentFile implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(SegmentFile.class);

	private final Path path;
	private final Kind kind;
	private final int segmentNumber;
	private final Geometry geometry;

	private final FileChannel channel;
	private final Arena arena;
	private final MemorySegment mapped;

	/**
	 * The engine used for advice only, never for reading or writing — those go through the mapping directly.
	 * <p>
	 * Stateless and shared: {@code prefetch} holds nothing between calls, so one instance serves every segment file.
	 */
	private static final io.metaloom.graph.core.io.IoEngine PREFETCH = new io.metaloom.graph.core.io.MmapIoEngine();
	private final Superblock superblock;
	private final FreeSlotBitmap freeSlots;
	private final PageMetadataArray pageMeta;

	private final int stride;
	private final int pageSize;
	private final long dataStart;
	private final BitSet dirtyPages;

	/** Publishes the active transaction's overlay, but only to the thread that owns it. */
	private OverlayHolder overlays;

	/** Publishes the calling thread's open read view, if it has one. Null until a store wires one in. */
	private SnapshotHolder snapshots;

	/** Never null: a segment with no store behind it counts into a throwaway instance rather than branching. */
	private IoCounters counters = new IoCounters();
	private final int kindCode;

	private SegmentFile(Path path, Kind kind, int segmentNumber, Geometry geometry, FileChannel channel,
		Arena arena, MemorySegment mapped, boolean fresh) {
		this.path = path;
		this.kind = kind;
		this.segmentNumber = segmentNumber;
		this.geometry = geometry;
		this.channel = channel;
		this.arena = arena;
		this.mapped = mapped;
		this.stride = geometry.stride(kind);
		this.pageSize = geometry.pageSize();
		this.dataStart = geometry.dataStart(kind);
		this.dirtyPages = new BitSet(geometry.pagesPerSegment(kind));
		this.kindCode = kind.code();

		this.superblock = fresh
			? Superblock.create(this, geometry, kind, segmentNumber)
			: Superblock.open(this, geometry, kind, segmentNumber);
		this.freeSlots = new FreeSlotBitmap(this, geometry.fsbStart(kind), geometry.slotsPerSegment(kind));
		this.pageMeta = new PageMetadataArray(this, geometry.pmaStart(kind), geometry.pagesPerSegment(kind));
	}

	/**
	 * Opens the segment, creating it sparsely if it does not exist. The nominal file size is the full segment size; the
	 * file is sparse so an empty segment occupies almost nothing on disk.
	 */
	public static SegmentFile open(Path dir, Kind kind, int segmentNumber, Geometry geometry) throws IOException {
		Files.createDirectories(dir);
		Path path = dir.resolve(String.format("%s-%06d.seg", kind.dirName(), segmentNumber));
		boolean fresh = !Files.exists(path);
		long size = geometry.fileBytes(kind);

		FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
			StandardOpenOption.WRITE);
		try {
			if (channel.size() < size) {
				// Extends sparsely: no blocks are allocated until a page is actually written.
				channel.truncate(0);
				channel.position(size - 1);
				channel.write(java.nio.ByteBuffer.wrap(new byte[1]));
			}
			Arena arena = Arena.ofShared();
			MemorySegment mapped = channel.map(MapMode.READ_WRITE, 0, size, arena);
			if (fresh) {
				logger.debug("Created {} segment {} at {} ({} bytes nominal)", kind, segmentNumber, path, size);
			}
			return new SegmentFile(path, kind, segmentNumber, geometry, channel, arena, mapped, fresh);
		} catch (RuntimeException | IOException e) {
			channel.close();
			throw e;
		}
	}

	// ---------------------------------------------------------------- overlay routing

	public void overlays(OverlayHolder overlays) {
		this.overlays = overlays;
	}

	public void snapshots(SnapshotHolder snapshots) {
		this.snapshots = snapshots;
	}

	public void counters(IoCounters counters) {
		this.counters = counters;
	}

	public IoCounters counters() {
		return counters;
	}

	private PageOverlay overlay() {
		return overlays == null ? null : overlays.current();
	}

	private int pageIndexOf(long fileOffset) {
		return (int) (fileOffset / pageSize);
	}

	/** The mapped page at a whole-file page index. Bypasses the overlay by design: commit diffs against it. */
	public MemorySegment mappedPage(int pageIndex) {
		return mapped.asSlice((long) pageIndex * pageSize, pageSize);
	}

	/**
	 * Advises the kernel that a record's bytes are about to be read.
	 * <p>
	 * Advisory and best effort: a range outside the mapping, or a kernel that declines, is a false rather than a
	 * failure. The overlay is deliberately not consulted — a page a transaction has already copied is in memory by
	 * definition, and there is nothing to fetch.
	 */
	public boolean prefetchRecord(long fileOffset, long length) {
		if (fileOffset < 0 || length <= 0 || fileOffset + length > mapped.byteSize()) {
			return false;
		}
		return PREFETCH.prefetch(mapped, fileOffset, length);
	}

	/**
	 * A slice for reading. Inside a transaction this returns the private copy of the page when the transaction has
	 * already written to it, so a writer always sees its own uncommitted changes.
	 */
	public MemorySegment readSlice(long fileOffset, long length) {
		PageOverlay overlay = overlay();
		if (overlay != null) {
			// This thread is the writer. It reads its own uncommitted pages, and otherwise the mapping as it stands
			// now — never through a read view, even if the same thread has one open.
			//
			// That is not a convenience, it is the only safe answer. A writer decides what to write from what it
			// reads: allocating a slot, appending to an adjacency block, splitting an index page. Reading any of that
			// through a view fixed in the past would compute the new state from stale input and overwrite everything
			// committed since, which is a lost update the engine has no way to detect afterwards.
			int pageIndex = pageIndexOf(fileOffset);
			MemorySegment page = overlay.peek(PageOverlay.key(kind, segmentNumber, pageIndex));
			if (page != null) {
				return page.asSlice(fileOffset - (long) pageIndex * pageSize, length);
			}
			return mapped.asSlice(fileOffset, length);
		}
		// A view fixed before the last commit reads a reconstruction; everything else reads the mapping untouched.
		Snapshot snapshot = snapshots == null ? null : snapshots.versioned();
		if (snapshot != null) {
			int pageIndex = pageIndexOf(fileOffset);
			MemorySegment page = snapshot.pageAsOf(PageOverlay.key(kind, segmentNumber, pageIndex),
				() -> mappedPage(pageIndex), pageSize);
			if (page != null) {
				return page.asSlice(fileOffset - (long) pageIndex * pageSize, length);
			}
		}
		return mapped.asSlice(fileOffset, length);
	}

	/**
	 * A slice for writing. Inside a transaction this copies the page into the overlay on first touch and returns a
	 * slice of the copy, so the mapped file is never modified before the commit is durable.
	 */
	public MemorySegment writeSlice(long fileOffset, long length) {
		int pageIndex = pageIndexOf(fileOffset);
		PageOverlay overlay = overlay();
		if (overlay != null) {
			MemorySegment page = overlay.forWrite(PageOverlay.key(kind, segmentNumber, pageIndex),
				mappedPage(pageIndex));
			return page.asSlice(fileOffset - (long) pageIndex * pageSize, length);
		}
		markDirtyPage(pageIndex);
		return mapped.asSlice(fileOffset, length);
	}

	/**
	 * Copies a committed page image into the mapping. Called by the commit apply step and by recovery.
	 */
	public void applyPage(int pageIndex, MemorySegment source, int sourceOffset, int destOffsetInPage, int length) {
		MemorySegment.copy(source, sourceOffset, mapped, (long) pageIndex * pageSize + destOffsetInPage, length);
		counters.pageApplied(length);
		markDirtyPage(pageIndex);
	}

	private void markDirtyPage(int wholeFilePageIndex) {
		long dataPage = wholeFilePageIndex - dataStart / pageSize;
		if (dataPage >= 0 && dataPage < geometry.pagesPerSegment(kind)) {
			dirtyPages.set((int) dataPage);
		}
	}

	// ---------------------------------------------------------------- accessors

	public Path path() {
		return path;
	}

	public Kind kind() {
		return kind;
	}

	public int segmentNumber() {
		return segmentNumber;
	}

	public Superblock superblock() {
		return superblock;
	}

	public FreeSlotBitmap freeSlots() {
		return freeSlots;
	}

	public PageMetadataArray pageMeta() {
		return pageMeta;
	}

	public Geometry geometry() {
		return geometry;
	}

	/**
	 * The record slice for a slot, for reading.
	 * <p>
	 * This is the one place a record read is counted. Metadata reads — superblock, bitmap, page metadata — go through
	 * {@link #readSlice} directly and are deliberately not counted: the budgets are about how many <em>records</em> an
	 * operation has to touch, which is what turns into device reads once a store outgrows memory.
	 */
	public MemorySegment record(int slot) {
		counters.recordRead(kindCode);
		return readSlice(dataStart + (long) slot * stride, stride);
	}

	/** The record slice for a slot, for writing. */
	public MemorySegment recordForWrite(int slot) {
		counters.recordWrite(kindCode);
		return writeSlice(dataStart + (long) slot * stride, stride);
	}

	/** A run of consecutive records, used by variable length property blocks. Never crosses a page. */
	public MemorySegment records(int slot, int count) {
		counters.recordRead(kindCode);
		return readSlice(dataStart + (long) slot * stride, (long) stride * count);
	}

	public MemorySegment recordsForWrite(int slot, int count) {
		counters.recordWrite(kindCode);
		return writeSlice(dataStart + (long) slot * stride, (long) stride * count);
	}

	/** The whole-file page index of a data page. */
	public int filePageOfDataPage(int dataPage) {
		return (int) (dataStart / pageSize) + dataPage;
	}

	/**
	 * Marks the data page holding the slot as changed. The checksum itself is recomputed at write-back time rather
	 * than per record write: a 64 byte write must not cost a CRC over 8 KiB.
	 */
	public void markDirty(int slot) {
		dirtyPages.set(geometry.pageOfSlot(kind, slot));
	}

	/** Data pages written but not yet forced. Reported by {@code store.metrics()} as the unflushed backlog. */
	public int dirtyPageCount() {
		return dirtyPages.cardinality();
	}

	/** Recomputes the checksum of every data page changed since the last flush. */
	public void flushChecksums() {
		for (int page = dirtyPages.nextSetBit(0); page >= 0; page = dirtyPages.nextSetBit(page + 1)) {
			pageMeta.checksum(page, Crc.crc32c(mapped, dataStart + (long) page * pageSize, pageSize));
		}
		dirtyPages.clear();
	}

	/** Verifies every page that has at least one live record. Used by {@code store.verify()}. */
	public void verifyChecksums() {
		int pages = geometry.pagesPerSegment(kind);
		for (int page = 0; page < pages; page++) {
			if (pageMeta.liveCount(page) == 0 && pageMeta.checksum(page) == 0) {
				continue;
			}
			int actual = Crc.crc32c(mapped, dataStart + (long) page * pageSize, pageSize);
			if (actual != pageMeta.checksum(page)) {
				throw new PageCorruptException("Checksum mismatch in " + path + " page " + page
					+ " (slots " + (long) page * geometry.recordsPerPage(kind) + ".."
					+ ((long) (page + 1) * geometry.recordsPerPage(kind) - 1) + ")");
			}
		}
	}

	/**
	 * Returns the blocks of every all-free data page to the filesystem. No id breaks: the pages stay addressable and
	 * read back as zeros, which is a valid empty page (invariant I6). This is the cheap half of space reclamation and
	 * should be preferred over compaction, which does break ids.
	 *
	 * <p>
	 * <b>The generation counters on the page are saved before they are destroyed.</b> A punched page reads as zeros,
	 * and the generation of each slot lives inside its record — so without this the counters restart at 1 and an id
	 * from before the delete becomes byte-for-byte identical to one handed out afterwards. That is worse than an id
	 * being broken: the stale id resolves, silently, to somebody else's record. The floor goes in the page metadata
	 * array, which is a separate region and is not punched.
	 * </p>
	 *
	 * @return the number of pages punched
	 */
	public int reclaimEmptyPages() throws IOException {
		if (!NativeLib.isAvailable()) {
			return 0;
		}
		force();
		int punched = 0;
		int pages = geometry.pagesPerSegment(kind);
		long highWaterPage = superblock.highWaterSlot() / geometry.recordsPerPage(kind);
		for (int page = 0; page < pages && page <= highWaterPage; page++) {
			if (pageMeta.liveCount(page) != 0 || pageMeta.checksum(page) == 0) {
				continue;
			}
			// Read before punching, obviously, but also before the punch is attempted: a floor raised for a page that
			// then failed to punch costs one wasted generation and nothing else, while the reverse loses the history.
			pageMeta.raiseGenFloor(page, highestGenOnPage(page));
			if (NativeLib.punchHole(path, dataStart + (long) page * pageSize, pageSize)) {
				// The page now reads as zeros, so its checksum has to follow or verify() would fail.
				pageMeta.checksum(page, Crc.crc32c(mapped, dataStart + (long) page * pageSize, pageSize));
				counters.pagePunched();
				punched++;
			}
		}
		// The locking fix has landed, so this force() is now safe and is here for durability: a punch takes effect
		// immediately and the metadata describing it does not, so crashing in between would lose both the checksum
		// fix-up above and the generation floor.
		//
		// It could not be here before. force() calls flushChecksums(), which walks `dirtyPages` and then clears it,
		// and Compactor used to call reclaim() outside runWrite() — so this ran on the compaction thread while a
		// foreground writer was setting dirty marks, discarded them, and left that writer's pages with checksums that
		// no longer matched their bytes. Compactor now wraps reclaim() and retireEmptySegments() in runWrite(), so
		// every caller that reaches flushChecksums() holds the writer lock and there is no second thread to race.
		//
		// Measured, because this is exactly the kind of claim that should not be taken on faith: with the writer held
		// open across the whole compaction, CompactionTest#testCompactionInterleavesWithForegroundWrites fails 8 runs
		// in 10 against the pre-fix engine with this force() present, and 0 in 12 with the locking fix.
		force();
		return punched;
	}

	/**
	 * The largest generation any slot on this page carries.
	 *
	 * <p>
	 * Every slot, not the live ones — there are none, the page is all-free — and the counter of a freed slot is
	 * exactly what has to survive. A record kind with no generation field would read whatever sits at that offset,
	 * which is why this is driven by {@link Kind#genOffset()} rather than by a per-store assumption.
	 * </p>
	 */
	private int highestGenOnPage(int page) {
		int recordsPerPage = geometry.recordsPerPage(kind);
		int firstSlot = page * recordsPerPage;
		int genOffset = kind.genOffset();
		int highest = 0;
		for (int i = 0; i < recordsPerPage; i++) {
			int gen = Short.toUnsignedInt(record(firstSlot + i).get(Layouts.SHORT, genOffset));
			if (gen > highest) {
				highest = gen;
			}
		}
		return highest;
	}

	/** Flushes the mapping and the file metadata. */
	public void force() throws IOException {
		flushChecksums();
		superblock.flush();
		mapped.force();
		channel.force(true);
		counters.dataForced();
	}

	/** Releases the mapping and the descriptor without flushing, as a crash would. */
	public void abandon() throws IOException {
		arena.close();
		channel.close();
	}

	@Override
	public void close() throws IOException {
		try {
			force();
		} finally {
			arena.close();
			channel.close();
		}
	}
}
