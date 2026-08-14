package io.metaloom.graph.core.segment;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.metaloom.graph.core.api.OutOfSegmentsException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.id.StaleIdException;
import io.metaloom.graph.core.metrics.IoCounters;
import io.metaloom.graph.core.page.OverlayHolder;
import io.metaloom.graph.core.util.Layouts;

/**
 * All segments of one kind, plus the slot allocator.
 * <p>
 * Allocation has three layers, cheapest first: a bump cursor over never-used slots, a FIFO ring of recently freed
 * slots, and a bitmap scan. FIFO rather than LIFO reuse is deliberate: it rotates through the whole segment before any
 * one slot is reused, which is what keeps the 12 bit generation counter a useful dangling-id detector.
 * <p>
 * Reads go through {@link #resolve}, writes through {@link #mutable}. The split matters: only the write path copies a
 * page into the transaction overlay.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 8
 */
public final class SegmentSet implements AutoCloseable {

	/** Bound on the in-memory free ring; beyond this, frees are only recorded in the bitmap. */
	private static final int FREE_RING_CAPACITY = 1 << 16;

	private final Kind kind;
	private final Geometry geometry;
	private final Path dir;

	private final List<SegmentFile> segments = new ArrayList<>();
	private final Deque<Long> freeRing = new ArrayDeque<>();
	/** Segregated free lists for multi-slot runs, keyed by run length. */
	private final Map<Integer, Deque<Long>> freeRuns = new HashMap<>();

	private final int slotsPerSegment;
	private final int slotsPerPage;
	private final int genOffset;

	/** Per segment scan cursor for {@link #allocateLowest}, valid only while nothing is freed. */
	private int[] lowestFreeHint = new int[8];

	private OverlayHolder overlays;
	private IoCounters counters;

	public SegmentSet(Path storeDir, Kind kind, Geometry geometry) throws IOException {
		this.kind = kind;
		this.geometry = geometry;
		this.dir = storeDir.resolve("seg").resolve(kind.dirName());
		this.slotsPerSegment = geometry.slotsPerSegment(kind);
		this.slotsPerPage = geometry.recordsPerPage(kind);
		this.genOffset = kind.genOffset();

		if (Files.isDirectory(dir)) {
			try (Stream<Path> files = Files.list(dir)) {
				long count = files.filter(p -> p.getFileName().toString().endsWith(".seg")).count();
				for (int i = 0; i < count; i++) {
					segments.add(SegmentFile.open(dir, kind, i, geometry));
				}
			}
		}
	}

	public Kind kind() {
		return kind;
	}

	public Geometry geometry() {
		return geometry;
	}

	public int stride() {
		return geometry.stride(kind);
	}

	public int slotsPerPage() {
		return slotsPerPage;
	}

	public int segmentCount() {
		return segments.size();
	}

	public SegmentFile segment(int number) {
		if (number < 0 || number >= segments.size()) {
			throw new IllegalArgumentException("No " + kind + " segment " + number);
		}
		return segments.get(number);
	}

	public List<SegmentFile> segments() {
		return segments;
	}

	// ---------------------------------------------------------------- transaction plumbing

	/** Routes access through the store's overlay holder, which is thread-scoped. */
	public void overlays(OverlayHolder overlays) {
		this.overlays = overlays;
		for (SegmentFile segment : segments) {
			segment.overlays(overlays);
		}
	}

	/** Points every segment, present and future, at the store's counters. */
	public void counters(IoCounters counters) {
		this.counters = counters;
		for (SegmentFile segment : segments) {
			segment.counters(counters);
		}
	}

	/** Discards in-memory allocator state after a rollback; the bitmap on disk is authoritative. */
	public void rollbackState() {
		for (SegmentFile segment : segments) {
			segment.superblock().reload();
		}
		freeRing.clear();
		freeRuns.clear();
		clearHints();
	}

	/** Re-reads every header after a log replay, which may have written a newer copy into the other A/B slot. */
	public void afterRecovery() {
		for (SegmentFile segment : segments) {
			segment.superblock().selectActive();
		}
		freeRing.clear();
		freeRuns.clear();
		clearHints();
	}

	// ---------------------------------------------------------------- segments

	private SegmentFile activeSegment() throws IOException {
		if (segments.isEmpty()) {
			return addSegment();
		}
		return segments.get(segments.size() - 1);
	}

	private SegmentFile addSegment() throws IOException {
		int number = segments.size();
		if (number > ElementId.MAX_SEGMENT) {
			throw new OutOfSegmentsException(kind);
		}
		SegmentFile segment = SegmentFile.open(dir, kind, number, geometry);
		if (counters != null) {
			segment.counters(counters);
		}
		// The fresh superblock is written outside any overlay and made durable immediately: a segment file whose
		// header never reached disk would fail to open after a crash.
		segment.force();
		segment.overlays(overlays);
		segments.add(segment);
		return segment;
	}

	// ---------------------------------------------------------------- allocation

	/** Allocates one slot and returns its id, with a generation that never reads as zero. */
	public long allocate() throws IOException {
		Long reused;
		while ((reused = freeRing.pollFirst()) != null) {
			if (isReusable(reused, 1)) {
				return claim(segmentOf(reused), slotOf(reused), 1);
			}
			// The ring is a cache in front of the bitmap and the bitmap is the authority. Compaction allocates
			// straight out of the bitmap, so it can take a slot the ring still remembers; handing that slot out
			// again would give two records the same id.
		}
		SegmentFile segment = activeSegment();
		long high = segment.superblock().highWaterSlot();
		if (high >= slotsPerSegment) {
			// The bump cursor is exhausted: try to recycle before adding a segment.
			int scanned = segment.freeSlots().nextFree(0);
			if (scanned >= 0) {
				return claim(segment, scanned, 1);
			}
			segment.superblock().sealed(true);
			segment = addSegment();
			high = 0;
		}
		segment.superblock().highWaterSlot(high + 1);
		return claim(segment, (int) high, 1);
	}

	/** Allocates {@code count} consecutive slots that do not straddle a page boundary. */
	public long allocateRun(int count) throws IOException {
		if (count <= 0) {
			throw new IllegalArgumentException("count must be positive");
		}
		if (count > slotsPerPage) {
			throw new IllegalArgumentException(
				"A run of " + count + " " + kind + " slots exceeds the " + slotsPerPage + " slots of one page");
		}
		if (count == 1) {
			return allocate();
		}
		Deque<Long> ring = freeRuns.get(count);
		if (ring != null) {
			Long reused;
			while ((reused = ring.pollFirst()) != null) {
				if (isReusable(reused, count)) {
					return claim(segmentOf(reused), slotOf(reused), count);
				}
			}
		}
		SegmentFile segment = activeSegment();
		long high = segment.superblock().highWaterSlot();
		long inPage = high % slotsPerPage;
		if (inPage + count > slotsPerPage) {
			// Skip the page tail rather than straddle. The skipped slots go on the free ring.
			long skip = slotsPerPage - inPage;
			for (long i = 0; i < skip; i++) {
				pushFree(segment.segmentNumber(), (int) (high + i));
			}
			high += skip;
		}
		if (high + count > slotsPerSegment) {
			int scanned = segment.freeSlots().nextFreeRun(0, count, slotsPerPage);
			if (scanned >= 0) {
				return claim(segment, scanned, count);
			}
			segment.superblock().sealed(true);
			segment = addSegment();
			high = 0;
		}
		segment.superblock().highWaterSlot(high + count);
		return claim(segment, (int) high, count);
	}

	/** True when every slot of a ring entry is still free according to the bitmap, which is the authority. */
	private boolean isReusable(long packed, int count) {
		int number = (int) (packed >>> 32);
		if (number >= segments.size()) {
			return false;
		}
		FreeSlotBitmap bitmap = segments.get(number).freeSlots();
		int slot = slotOf(packed);
		if (slot + count > slotsPerSegment) {
			return false;
		}
		for (int i = 0; i < count; i++) {
			if (bitmap.isAllocated(slot + i)) {
				return false;
			}
		}
		return true;
	}

	private long claim(SegmentFile segment, int slot, int count) {
		FreeSlotBitmap bitmap = segment.freeSlots();
		for (int i = 0; i < count; i++) {
			if (bitmap.isAllocated(slot + i)) {
				// Two records at one id is the worst corruption this engine can produce, so it fails loudly rather
				// than quietly overwriting.
				throw new IllegalStateException("Double allocation of " + kind + " segment " + segment.segmentNumber()
					+ " slot " + (slot + i));
			}
			bitmap.allocate(slot + i);
		}
		// Read the generation before clearing: free() left the advanced value in the record, and zeroing first would
		// wipe it and make every reused slot look like generation 1.
		int gen = readGen(segment, slot);
		gen = gen == 0 ? 1 : gen;

		MemorySegment run = segment.recordsForWrite(slot, count);
		run.fill((byte) 0);
		writeGen(segment, slot, gen);

		segment.superblock().liveCountDelta(count);
		int page = geometry.pageOfSlot(kind, slot);
		segment.pageMeta().liveCountDelta(page, count);
		segment.pageMeta().bumpEpoch(page);
		segment.markDirty(slot);
		return ElementId.encode(kind, segment.segmentNumber(), slot, gen & ElementId.MAX_GEN);
	}

	public void free(long id) {
		free(id, 1);
	}

	/** Frees a run of consecutive slots, advancing the generation so outstanding ids become detectably stale. */
	public void free(long id, int count) {
		SegmentFile segment = segmentFor(id);
		int slot = ElementId.slot(id);
		checkGen(segment, id);

		FreeSlotBitmap bitmap = segment.freeSlots();
		for (int i = 0; i < count; i++) {
			bitmap.free(slot + i);
		}
		int gen = readGen(segment, slot);
		writeGen(segment, slot, (gen % ElementId.MAX_GEN) + 1);

		clearHints();
		segment.superblock().liveCountDelta(-count);
		segment.superblock().freeCountDelta(count);
		int page = geometry.pageOfSlot(kind, slot);
		segment.pageMeta().liveCountDelta(page, -count);
		segment.pageMeta().bumpEpoch(page);
		segment.markDirty(slot);

		if (count == 1) {
			pushFree(segment.segmentNumber(), slot);
		} else {
			Deque<Long> ring = freeRuns.computeIfAbsent(count, k -> new ArrayDeque<>());
			if (ring.size() < FREE_RING_CAPACITY) {
				ring.addLast(pack(segment.segmentNumber(), slot));
			}
		}
	}

	private void pushFree(int segmentNumber, int slot) {
		if (freeRing.size() < FREE_RING_CAPACITY) {
			freeRing.addLast(pack(segmentNumber, slot));
		}
	}

	// ---------------------------------------------------------------- compaction

	/**
	 * Claims the lowest free run of {@code count} slots that lies strictly below {@code (limitSegment, limitSlot)}, or
	 * {@link ElementId#NULL} when there is none.
	 * <p>
	 * This is the allocator compaction uses, and it is deliberately different from {@link #allocate()}: the ordinary
	 * path is optimised for speed and only ever looks at the free ring and the active segment, whereas this one scans
	 * segments in ascending order so that a record always moves <em>downwards</em>. Moving downwards is what makes the
	 * pass terminate and what lets the high water mark come down afterwards.
	 */
	public long allocateLowest(int count, int limitSegment, int limitSlot) {
		if (count <= 0 || count > slotsPerPage) {
			throw new IllegalArgumentException("count " + count + " outside 1.." + slotsPerPage);
		}
		for (int number = 0; number < segments.size() && number <= limitSegment; number++) {
			SegmentFile segment = segments.get(number);
			int high = (int) Math.min(segment.superblock().highWaterSlot(), slotsPerSegment);
			int limit = number == limitSegment ? Math.min(high, limitSlot) : high;
			if (limit < count) {
				continue;
			}
			int from = hintFor(number);
			int slot = count == 1
				? segment.freeSlots().nextFree(from, limit)
				: segment.freeSlots().nextFreeRun(from, limit, count, slotsPerPage);
			if (slot >= 0) {
				// Everything below the claimed slot is now allocated, so the next scan can start here. Without the
				// hint a relocation pass rescans the whole bitmap from zero for every single record it moves.
				lowestFreeHint[number] = slot;
				return claim(segment, slot, count);
			}
			lowestFreeHint[number] = limit;
		}
		return ElementId.NULL;
	}

	private int hintFor(int segmentNumber) {
		if (lowestFreeHint.length <= segmentNumber) {
			lowestFreeHint = java.util.Arrays.copyOf(lowestFreeHint, Math.max(segmentNumber + 1,
				lowestFreeHint.length * 2));
		}
		return lowestFreeHint[segmentNumber];
	}

	/** Invalidates the relocation scan cursors. Anything that frees a slot may have opened one below a cursor. */
	private void clearHints() {
		java.util.Arrays.fill(lowestFreeHint, 0);
	}

	/**
	 * Copies a record, or a run of them, from one slot to another and repairs the destination generation.
	 * <p>
	 * The generation is not part of the payload being moved: the destination slot carries its own, which the freshly
	 * claimed slot already holds and which a verbatim copy would overwrite. Getting this wrong would make every
	 * relocated record answer to a stale id.
	 */
	public void relocate(long fromId, long toId, int count) {
		SegmentFile destination = segment(ElementId.segment(toId));
		int destinationSlot = ElementId.slot(toId);
		int gen = readGen(destination, destinationSlot);

		MemorySegment source = resolveRun(fromId, count);
		MemorySegment target = mutableRun(toId, count);
		MemorySegment.copy(source, 0, target, 0, (long) stride() * count);
		writeGen(destination, destinationSlot, gen);

		int page = geometry.pageOfSlot(kind, destinationSlot);
		destination.pageMeta().bumpEpoch(page);
		destination.markDirty(destinationSlot);
	}

	/**
	 * Pulls every segment's high water mark down to its topmost live slot and recomputes the free counter from the
	 * bitmap. Returns the number of slots the marks came down by.
	 * <p>
	 * The high water mark is what {@link #storageBytes()} and every full scan are bounded by, so lowering it is where
	 * the space compaction actually shows up.
	 */
	public long shrinkHighWater() {
		long reclaimed = 0;
		for (SegmentFile segment : segments) {
			int high = (int) Math.min(segment.superblock().highWaterSlot(), slotsPerSegment);
			int top = segment.freeSlots().highestAllocatedBelow(high) + 1;
			if (top < high) {
				segment.superblock().highWaterSlot(top);
				reclaimed += high - top;
			}
			segment.superblock().freeCount(top - segment.freeSlots().countAllocated(top));
		}
		// Ring entries may name slots above the new mark, which allocate() would happily hand out again.
		freeRing.clear();
		freeRuns.clear();
		clearHints();
		return reclaimed;
	}

	/**
	 * Closes and unlinks trailing segments that hold nothing, keeping at least one. Only trailing segments can go:
	 * segment numbers are part of every id, so a gap in the middle would silently renumber everything above it.
	 *
	 * @return the number of files removed
	 */
	public int retireEmptySegments() throws IOException {
		int retired = 0;
		while (segments.size() > 1) {
			SegmentFile last = segments.get(segments.size() - 1);
			if (last.superblock().liveCount() != 0 || last.superblock().highWaterSlot() != 0) {
				break;
			}
			segments.remove(segments.size() - 1);
			Path file = last.path();
			last.abandon();
			Files.deleteIfExists(file);
			retired++;
		}
		if (retired > 0) {
			freeRing.clear();
			freeRuns.clear();
			clearHints();
		}
		return retired;
	}

	/** Live slots as a fraction of the slots below the high water marks. 1.0 means perfectly dense. */
	public double liveness() {
		long high = highWaterTotal();
		return high == 0 ? 1.0 : (double) liveCount() / high;
	}

	public long highWaterTotal() {
		long total = 0;
		for (SegmentFile segment : segments) {
			total += segment.superblock().highWaterSlot();
		}
		return total;
	}

	// ---------------------------------------------------------------- access

	/** Resolves an id to its record slice for reading, validating the generation. */
	public MemorySegment resolve(long id) {
		return resolve(id, false);
	}

	/** Resolves an id to its record slice for writing. */
	public MemorySegment mutable(long id) {
		return resolve(id, true);
	}

	/**
	 * The segment an id names, as a stale-id failure rather than an argument error when the segment is gone. Retiring
	 * an empty segment can shrink the set, and an id held from before that is stale, not malformed.
	 */
	private SegmentFile segmentFor(long id) {
		// The kind is checked here rather than through ElementId.expect, which throws IllegalArgumentException. These
		// ids are frequently pointers read out of a page, so a wrong kind means "this store is damaged", not "the
		// caller made a mistake", and it has to arrive as one of the engine's own exceptions.
		if (ElementId.kindCode(id) != kind.code()) {
			throw new StaleIdException(id, "not a " + kind + " id");
		}
		int number = ElementId.segment(id);
		if (number >= segments.size()) {
			throw new StaleIdException(id, "segment " + number + " no longer exists");
		}
		return segments.get(number);
	}

	private MemorySegment resolve(long id, boolean forWrite) {
		SegmentFile segment = segmentFor(id);
		int slot = ElementId.slot(id);
		if (slot >= slotsPerSegment) {
			throw new StaleIdException(id, "slot beyond segment capacity");
		}
		if (!segment.freeSlots().isAllocated(slot)) {
			throw new StaleIdException(id, "slot is not allocated");
		}
		if (forWrite) {
			checkGen(segment, id);
			segment.markDirty(slot);
			return segment.recordForWrite(slot);
		}
		// The generation is read out of the slice that is about to be returned rather than through a second
		// acquisition. It is the same page either way, but a read by id is the engine's hottest path and its whole
		// claim is that it costs exactly one record touch, which is only true if it is written that way.
		MemorySegment record = segment.record(slot);
		int stored = Short.toUnsignedInt(record.get(Layouts.SHORT, genOffset)) & ElementId.MAX_GEN;
		if (stored != ElementId.gen(id)) {
			throw new StaleIdException(id, "generation is " + stored + " on disk");
		}
		return record;
	}

	public MemorySegment resolveRun(long id, int count) {
		SegmentFile segment = segmentFor(id);
		// As in resolve(): the generation lives in the first record of the run, so the slice being returned already
		// contains it and a second acquisition is pure waste on the property read path.
		MemorySegment run = segment.records(ElementId.slot(id), count);
		int stored = Short.toUnsignedInt(run.get(Layouts.SHORT, genOffset)) & ElementId.MAX_GEN;
		if (stored != ElementId.gen(id)) {
			throw new StaleIdException(id, "generation is " + stored + " on disk");
		}
		return run;
	}

	/**
	 * The record run from an id's slot to the end of its page.
	 * <p>
	 * A variable-length block declares its own length in its header, which used to mean acquiring the head record to
	 * read the length and then acquiring the run to read the payload. Since a block never straddles a page, the run to
	 * the page end is always safe to take and always contains the whole block, so one acquisition does both.
	 */
	public MemorySegment resolveRunToPageEnd(long id) {
		int slot = ElementId.slot(id);
		return resolveRun(id, slotsPerPage - slot % slotsPerPage);
	}

	public MemorySegment mutableRun(long id, int count) {
		SegmentFile segment = segmentFor(id);
		checkGen(segment, id);
		segment.markDirty(ElementId.slot(id));
		return segment.recordsForWrite(ElementId.slot(id), count);
	}

	/** True if the id still names a live record. Never throws for a stale id. */
	public boolean isLive(long id) {
		if (ElementId.isNull(id) || ElementId.kindCode(id) != kind.code()) {
			return false;
		}
		int segmentNumber = ElementId.segment(id);
		if (segmentNumber >= segments.size()) {
			return false;
		}
		SegmentFile segment = segments.get(segmentNumber);
		int slot = ElementId.slot(id);
		if (slot >= slotsPerSegment || !segment.freeSlots().isAllocated(slot)) {
			return false;
		}
		return (readGen(segment, slot) & ElementId.MAX_GEN) == ElementId.gen(id);
	}

	/** Calls the consumer with the id of every live slot. The bitmap is the authority on what is live. */
	public void forEachLiveId(java.util.function.LongConsumer consumer) {
		for (SegmentFile segment : segments) {
			long high = segment.superblock().highWaterSlot();
			FreeSlotBitmap bitmap = segment.freeSlots();
			for (int slot = 0; slot < high; slot++) {
				if (bitmap.isAllocated(slot)) {
					int gen = readGen(segment, slot) & ElementId.MAX_GEN;
					consumer.accept(ElementId.encode(kind, segment.segmentNumber(), slot, gen));
				}
			}
		}
	}

	/**
	 * Every live id, materialised.
	 * <p>
	 * A maintenance sweep needs to iterate while it mutates, which {@link #forEachLiveId} cannot support: freeing a
	 * slot during the callback would change the bitmap under the scan. Eight bytes per live record is the price.
	 */
	public long[] liveIds() {
		long[][] buffer = { new long[Math.max(16, (int) Math.min(liveCount(), 1 << 20))] };
		int[] written = { 0 };
		forEachLiveId(id -> {
			if (written[0] == buffer[0].length) {
				buffer[0] = java.util.Arrays.copyOf(buffer[0], buffer[0].length * 2);
			}
			buffer[0][written[0]++] = id;
		});
		return java.util.Arrays.copyOf(buffer[0], written[0]);
	}

	/** The generation currently stored in a slot, or 0. Used to rebuild a full id from a 48 bit location. */
	public int generationAt(int segmentNumber, int slot) {
		if (segmentNumber >= segments.size() || slot >= slotsPerSegment) {
			return 0;
		}
		SegmentFile segment = segments.get(segmentNumber);
		if (!segment.freeSlots().isAllocated(slot)) {
			return 0;
		}
		return readGen(segment, slot) & ElementId.MAX_GEN;
	}

	private void checkGen(SegmentFile segment, long id) {
		int stored = readGen(segment, ElementId.slot(id)) & ElementId.MAX_GEN;
		if (stored != ElementId.gen(id)) {
			throw new StaleIdException(id, "generation is " + stored + " on disk");
		}
	}

	private int readGen(SegmentFile segment, int slot) {
		return Short.toUnsignedInt(segment.record(slot).get(Layouts.SHORT, genOffset));
	}

	private void writeGen(SegmentFile segment, int slot, int gen) {
		segment.recordForWrite(slot).set(Layouts.SHORT, genOffset, (short) gen);
	}

	/** Marks the page holding the slot as changed. */
	public void touched(long id) {
		SegmentFile segment = segmentFor(id);
		int page = geometry.pageOfSlot(kind, ElementId.slot(id));
		segment.pageMeta().bumpEpoch(page);
		segment.markDirty(ElementId.slot(id));
	}

	private static long pack(int segmentNumber, int slot) {
		return ((long) segmentNumber << 32) | (slot & 0xFFFFFFFFL);
	}

	private SegmentFile segmentOf(long packed) {
		return segment((int) (packed >>> 32));
	}

	private static int slotOf(long packed) {
		return (int) packed;
	}

	// ---------------------------------------------------------------- lifecycle

	public long liveCount() {
		long total = 0;
		for (SegmentFile segment : segments) {
			total += segment.superblock().liveCount();
		}
		return total;
	}

	public long recordBytes() {
		return liveCount() * stride();
	}

	/**
	 * Bytes this kind has actually committed: the metadata regions of every segment plus everything up to each
	 * segment's high water mark. The nominal file size is much larger and sparse.
	 */
	public long storageBytes() {
		long total = 0;
		for (SegmentFile segment : segments) {
			total += geometry.dataStart(kind) + segment.superblock().highWaterSlot() * stride();
		}
		return total;
	}

	public void verify() {
		for (SegmentFile segment : segments) {
			segment.flushChecksums();
			segment.verifyChecksums();
			long allocated = segment.freeSlots().countAllocated();
			if (allocated != segment.superblock().liveCount()) {
				throw new IllegalStateException("Segment " + segment.path() + " accounting mismatch: bitmap says "
					+ allocated + " allocated, superblock says " + segment.superblock().liveCount());
			}
		}
	}

	public void force() throws IOException {
		for (SegmentFile segment : segments) {
			segment.force();
		}
	}

	/** Returns the blocks of every all-free page to the filesystem. Returns the number of pages punched. */
	public int reclaim() throws IOException {
		int punched = 0;
		for (SegmentFile segment : segments) {
			punched += segment.reclaimEmptyPages();
		}
		return punched;
	}

	/** Releases every segment without flushing, as a crash would. */
	public void abandon() throws IOException {
		for (SegmentFile segment : segments) {
			segment.abandon();
		}
		segments.clear();
	}

	@Override
	public void close() throws IOException {
		IOException first = null;
		for (SegmentFile segment : segments) {
			try {
				segment.close();
			} catch (IOException e) {
				first = first == null ? e : first;
			}
		}
		segments.clear();
		if (first != null) {
			throw first;
		}
	}
}
