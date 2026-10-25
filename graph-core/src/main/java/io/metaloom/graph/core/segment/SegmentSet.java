package io.metaloom.graph.core.segment;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import io.metaloom.graph.core.api.OutOfSegmentsException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.id.StaleIdException;
import io.metaloom.graph.core.metrics.IoCounters;
import io.metaloom.graph.core.txn.MetaJournal;
import io.metaloom.graph.core.txn.SnapshotHolder;
import io.metaloom.graph.core.txn.WriteContext;
import io.metaloom.graph.core.txn.WriteContextHolder;
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

	private final List<SegmentFile> segments = new CopyOnWriteArrayList<>();
	private final Deque<Long> freeRing = new ArrayDeque<>();
	/** Segregated free lists for multi-slot runs, keyed by run length. */
	private final Map<Integer, Deque<Long>> freeRuns = new HashMap<>();

	/**
	 * Everything the allocator touches, and the only lock it takes.
	 *
	 * <p>
	 * Allocation is the one operation concurrent writers genuinely cannot do optimistically. Every other collision
	 * between two writers is detectable afterwards — they wrote the same bytes, so one of them aborts — but two writers
	 * handed the same slot have already put two records at one id, and there is no bitmap, checksum or invariant that
	 * can tell them apart later. So the handout is serialised, and it is the <em>only</em> thing that is: the lock is
	 * held for a bitmap scan and a record fill, not for the transaction.
	 * </p>
	 */
	private final ReentrantLock allocLock = new ReentrantLock();

	/**
	 * Slots reserved by transactions that have not committed, packed as {@code (segment << 32) | slot}.
	 *
	 * <p>
	 * The bitmap in the mapping only knows about slots that <em>have</em> committed, so on its own it would hand the
	 * same slot to two writers in flight at once. This is the difference. It is memory-only and its loss is harmless:
	 * after a crash there are no transactions in flight.
	 * </p>
	 */
	private final Map<Long, Long> reserved = new HashMap<>();

	private final int slotsPerSegment;
	private final int slotsPerPage;
	private final int genOffset;

	/** Per segment scan cursor for {@link #allocateLowest}, valid only while nothing is freed. */
	private int[] lowestFreeHint = new int[8];

	private WriteContextHolder contexts;
	private SnapshotHolder snapshots;
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
			} catch (RuntimeException | IOException e) {
				// The set never comes into existence, so nothing else will ever close the segments opened before the
				// one that failed.
				for (SegmentFile opened : segments) {
					try {
						opened.abandon();
					} catch (IOException suppressed) {
						e.addSuppressed(suppressed);
					}
				}
				segments.clear();
				throw e;
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

	/** Routes access through the store's write-context holder, which is thread-scoped. */
	public void writeContexts(WriteContextHolder contexts) {
		this.contexts = contexts;
		for (SegmentFile segment : segments) {
			segment.writeContexts(contexts);
		}
	}

	/** Routes reads through the store's snapshot holder, so an open view sees the store as it was when it began. */
	public void snapshots(SnapshotHolder snapshots) {
		this.snapshots = snapshots;
		for (SegmentFile segment : segments) {
			segment.snapshots(snapshots);
		}
	}

	/** The store's counters, or null before {@link #counters(IoCounters)} has wired them. */
	public IoCounters counters() {
		return counters;
	}

	/** Points every segment, present and future, at the store's counters. */
	public void counters(IoCounters counters) {
		this.counters = counters;
		for (SegmentFile segment : segments) {
			segment.counters(counters);
		}
	}

	/**
	 * Releases what a transaction reserved and never committed.
	 *
	 * <p>
	 * It used to reload every superblock from the mapping and empty the free rings, which was correct with one writer
	 * and is destructive with several: the counters it would restore are shared, and another writer's uncommitted
	 * changes are not in them to be restored. Under concurrency a rollback is purely local — the overlay and the
	 * journal are discarded, which is the whole of it — and the only shared state to give back is the slots this
	 * transaction took out of the allocator. They go on the free ring rather than being forgotten, because the bump
	 * cursor has already moved past them.
	 * </p>
	 */
	public void rollbackState(WriteContext context, boolean alone) {
		if (context == null) {
			return;
		}
		allocLock.lock();
		try {
			for (WriteContext.SlotRun run : context.reservedSlots()) {
				if (MetaJournal.kindCodeOf(run.segmentKey()) != kind.code()) {
					continue;
				}
				int number = MetaJournal.segmentNumberOf(run.segmentKey());
				for (int i = 0; i < run.count(); i++) {
					reserved.remove(pack(number, run.slot() + i));
				}
			}
			// Before the slots go back on the ring, not after. Lowering the mark and then offering the slots above it
			// hands the same slot out twice: once off the ring and once off the bump cursor on its way back up.
			if (alone) {
				lowerHighWater(context);
			}
			// Deduplicated: a transaction that turns one slot over repeatedly reserves it once per cycle, and two ring
			// entries naming one slot is two allocations of it after the next rollback.
			Set<Long> returned = new HashSet<>();
			for (WriteContext.SlotRun run : context.reservedSlots()) {
				if (MetaJournal.kindCodeOf(run.segmentKey()) != kind.code() || run.count() != 1) {
					continue;
				}
				int number = MetaJournal.segmentNumberOf(run.segmentKey());
				if (number < segments.size() && run.slot() < segments.get(number).superblock().highWaterSlot()
					&& returned.add(pack(number, run.slot()))) {
					pushFree(number, run.slot());
				}
			}
			clearHints();
		} finally {
			allocLock.unlock();
		}
	}

	/**
	 * Publishes what a committed transaction did to the allocator: its reservations are now real, and the slots it
	 * freed become reusable.
	 *
	 * <p>
	 * The frees are deferred to here rather than pushed when {@link #free} runs, and that is not tidiness. A slot on
	 * the ring is a slot the allocator will hand out; handing out one whose delete has not committed would let a
	 * rollback restore a record that somebody else is already using.
	 * </p>
	 */
	public void commitState(WriteContext context) {
		if (context == null) {
			return;
		}
		allocLock.lock();
		try {
			for (WriteContext.SlotRun run : context.reservedSlots()) {
				if (MetaJournal.kindCodeOf(run.segmentKey()) != kind.code()) {
					continue;
				}
				int number = MetaJournal.segmentNumberOf(run.segmentKey());
				for (int i = 0; i < run.count(); i++) {
					reserved.remove(pack(number, run.slot() + i));
				}
			}
			for (WriteContext.SlotRun run : context.freedSlots()) {
				if (MetaJournal.kindCodeOf(run.segmentKey()) != kind.code()) {
					continue;
				}
				int number = MetaJournal.segmentNumberOf(run.segmentKey());
				if (number >= segments.size() || segments.get(number).freeSlots().isAllocatedLive(run.slot())) {
					// Freed and then taken back by this same transaction. The mapping is the authority and it says the
					// slot is live, so offering it to the allocator would hand out a record that exists.
					continue;
				}
				if (run.count() == 1) {
					pushFree(number, run.slot());
				} else {
					Deque<Long> ring = freeRuns.computeIfAbsent(run.count(), k -> new ArrayDeque<>());
					if (ring.size() < FREE_RING_CAPACITY) {
						ring.addLast(pack(number, run.slot()));
					}
				}
			}
			clearHints();
		} finally {
			allocLock.unlock();
		}
	}

	/**
	 * Puts a rolled-back transaction's high water marks back, where that is still true.
	 *
	 * <p>
	 * <b>Only when this transaction is the only one in the store</b> — no read view open, no other writer. The mark
	 * bounds every scan, so lowering it hides whatever is above it, and above it is exactly where a record that another
	 * view is still entitled to see can be sitting. It also feeds the bump cursor, so lowering it under a free-ring
	 * entry naming the same slot hands that slot out twice. Alone, neither can happen, and alone is the case the
	 * property is about: a rolled-back transaction and a crash in the middle of the same transaction must reach
	 * byte-identical stores. Under contention the mark stays up, which is harmless — a mark above a free slot is the
	 * state an ordinary delete produces, and what {@code shrinkHighWater} exists to tidy.
	 * </p>
	 */
	private void lowerHighWater(WriteContext context) {
		for (Map.Entry<Long, Long> entry : context.highWaterBefore().entrySet()) {
			long segmentKey = entry.getKey();
			if (MetaJournal.kindCodeOf(segmentKey) != kind.code()) {
				continue;
			}
			int number = MetaJournal.segmentNumberOf(segmentKey);
			if (number >= segments.size()) {
				continue;
			}
			SegmentFile segment = segments.get(number);
			int before = (int) (long) entry.getValue();
			int current = (int) Math.min(segment.superblock().highWaterSlot(), slotsPerSegment);
			if (current <= before) {
				continue;
			}
			if (segment.freeSlots().highestAllocatedBelowLive(current) >= before) {
				continue;
			}
			boolean reservedAbove = false;
			for (int slot = before; slot < current && !reservedAbove; slot++) {
				reservedAbove = reserved.containsKey(pack(number, slot));
			}
			if (!reservedAbove) {
				segment.superblock().highWaterSlot(before);
				// Every ring entry above the new mark has to go with it. A slot named by the ring and also reachable
				// from the bump cursor is handed out twice — which is what shrinkHighWater() has always avoided by
				// emptying the caches outright, and what this had to learn the same way.
				purgeCachesAbove(number, before);
			}
		}
	}

	/**
	 * Claims one named slot, or {@link ElementId#NULL} when somebody already has it.
	 *
	 * <p>
	 * One caller: the index catalog page, which has to live at INDEX (0, 0) because that is where a reopen looks for
	 * it. It used to allocate ordinarily and assert that it had been given slot 0, which held only because a rollback
	 * emptied the free rings and reloaded the bump cursor from disk — so the very first allocation of a store really
	 * was the first slot. Concurrent writers recycle rolled-back slots instead of forgetting them, and the assertion
	 * started firing on stores where nothing was wrong. Asking for the slot is what the caller meant in the first
	 * place.
	 * </p>
	 */
	public long allocateAt(int segmentNumber, int slot) throws IOException {
		allocLock.lock();
		try {
			while (segments.size() <= segmentNumber) {
				addSegment();
			}
			SegmentFile segment = segments.get(segmentNumber);
			if (segment.freeSlots().isAllocated(slot) || segment.freeSlots().isAllocatedLive(slot)
				|| reserved.containsKey(pack(segmentNumber, slot))) {
				return ElementId.NULL;
			}
			freeRing.remove(pack(segmentNumber, slot));
			long high = segment.superblock().highWaterSlot();
			if (high <= slot) {
				noteHighWater(segment, high);
				segment.superblock().highWaterSlot(slot + 1L);
			}
			return claim(segment, slot, 1);
		} finally {
			allocLock.unlock();
		}
	}

	/** Forgets every cached free slot of one segment at or above {@code mark}. */
	private void purgeCachesAbove(int segmentNumber, int mark) {
		freeRing.removeIf(packed -> (int) (packed >>> 32) == segmentNumber && slotOf(packed) >= mark);
		for (Deque<Long> ring : freeRuns.values()) {
			ring.removeIf(packed -> (int) (packed >>> 32) == segmentNumber && slotOf(packed) >= mark);
		}
		clearHints();
	}

	/** Whether a slot is spoken for in the mapping or by a transaction that has not committed yet. */
	public boolean isTaken(int segmentNumber, int slot) {
		allocLock.lock();
		try {
			if (segmentNumber >= segments.size()) {
				return false;
			}
			return segments.get(segmentNumber).freeSlots().isAllocatedLive(slot)
				|| reserved.containsKey(pack(segmentNumber, slot));
		} finally {
			allocLock.unlock();
		}
	}

	/** Drops every in-memory allocator cache. The bitmap on disk is authoritative; this only forgets a shortcut. */
	public void resetAllocatorCaches() {
		allocLock.lock();
		try {
			freeRing.clear();
			freeRuns.clear();
			reserved.clear();
			clearHints();
		} finally {
			allocLock.unlock();
		}
	}

	/** Re-reads every header after a log replay, which may have written a newer copy into the other A/B slot. */
	public void afterRecovery() {
		for (SegmentFile segment : segments) {
			segment.superblock().selectActive();
		}
		resetAllocatorCaches();
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
		segment.writeContexts(contexts);
		segment.snapshots(snapshots);
		segments.add(segment);
		return segment;
	}

	// ---------------------------------------------------------------- allocation

	/** Allocates one slot and returns its id, with a generation that never reads as zero. */
	public long allocate() throws IOException {
		allocLock.lock();
		try {
			long own = reuseOwnFreed(1);
			if (own != ElementId.NULL) {
				return own;
			}
			List<Long> deferred = null;
			try {
				Long reused;
				while ((reused = freeRing.pollFirst()) != null) {
					if (!isReusable(reused, 1)) {
						// The ring is a cache in front of the bitmap and the bitmap is the authority. Compaction
						// allocates straight out of the bitmap, so it can take a slot the ring still remembers;
						// handing that slot out again would give two records the same id.
						continue;
					}
					if (!isFreeInView(reused, 1)) {
						deferred = defer(deferred, reused);
						continue;
					}
					return claim(segmentOf(reused), slotOf(reused), 1);
				}
			} finally {
				returnDeferred(deferred);
			}
			SegmentFile segment = activeSegment();
			long high = segment.superblock().highWaterSlot();
			if (high >= slotsPerSegment) {
				// The bump cursor is exhausted: try to recycle before adding a segment.
				int scanned = scanExhausted(segment, 1);
				if (scanned >= 0) {
					return claim(segment, scanned, 1);
				}
				segment.superblock().sealed(true);
				segment = addSegment();
				high = 0;
			}
			noteHighWater(segment, high);
			segment.superblock().highWaterSlot(high + 1);
			return claim(segment, (int) high, 1);
		} finally {
			allocLock.unlock();
		}
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
		allocLock.lock();
		try {
			long own = reuseOwnFreed(count);
			if (own != ElementId.NULL) {
				return own;
			}
			Deque<Long> ring = freeRuns.get(count);
			if (ring != null) {
				List<Long> deferred = null;
				try {
					Long reused;
					while ((reused = ring.pollFirst()) != null) {
						if (!isReusable(reused, count)) {
							continue;
						}
						if (!isFreeInView(reused, count)) {
							deferred = defer(deferred, reused);
							continue;
						}
						return claim(segmentOf(reused), slotOf(reused), count);
					}
				} finally {
					if (deferred != null) {
						for (Long entry : deferred) {
							ring.addLast(entry);
						}
					}
				}
			}
			SegmentFile segment = activeSegment();
			long high = segment.superblock().highWaterSlot();
			// The mark as it stood before this allocation, skip included: a rollback restores this, and recording it
			// after the skip left the mark raised by the skipped tail where a crash would have put it back.
			long before = high;
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
				int scanned = scanExhausted(segment, count);
				if (scanned >= 0) {
					return claim(segment, scanned, count);
				}
				segment.superblock().sealed(true);
				segment = addSegment();
				high = 0;
				before = 0;
			}
			noteHighWater(segment, before);
			segment.superblock().highWaterSlot(high + count);
			return claim(segment, (int) high, count);
		} finally {
			allocLock.unlock();
		}
	}

	/**
	 * Hands the calling transaction back a run it freed itself, or {@link ElementId#NULL} if it has none.
	 *
	 * <p>
	 * Tried before the shared ring, because a transaction that frees a structure and rebuilds it should reuse its own
	 * space rather than extend the segment. The freed run only reaches the shared ring at commit — see
	 * {@link WriteContext#takeReusable} for why — so without this the rebuild allocates off the bump cursor and the
	 * high water mark ratchets up once per rebuild, which is the defect {@code IndexSpaceReuseTest} pins down.
	 * </p>
	 *
	 * <p>
	 * <b>{@link Kind#INDEX} only, and the restriction is not caution.</b> Reusing a slot before the delete that freed
	 * it has committed means the record's generation counter is advanced in the transaction's overlay and not in the
	 * mapping, so the two disagree for as long as the transaction runs — and every other kind is reachable through an
	 * id held somewhere else while that is true. Enabling it for {@code PROP} was measured breaking the conserved-total
	 * invariant of {@code ParallelConsistencyTest} under four writers, as a reader resolving a property block it was
	 * entitled to see and being told its generation was stale. The index is the case that needs it and the case where
	 * it holds: {@code resetTrees()} frees and rebuilds the whole structure inside one transaction, and no id survives
	 * that rebuild to be confused by it — the same argument {@link #packFreeRing()} is limited by.
	 * </p>
	 */
	private long reuseOwnFreed(int count) {
		if (kind != Kind.INDEX) {
			return ElementId.NULL;
		}
		WriteContext context = contexts == null ? null : contexts.current();
		if (context == null) {
			return ElementId.NULL;
		}
		WriteContext.SlotRun run;
		while ((run = context.takeReusable(kind.code(), count)) != null) {
			int number = MetaJournal.segmentNumberOf(run.segmentKey());
			if (number >= segments.size()) {
				continue;
			}
			SegmentFile segment = segments.get(number);
			boolean free = true;
			for (int i = 0; i < count && free; i++) {
				// The transaction's own view, which already has its free in it. The shared reservation set is
				// deliberately not consulted: these slots are in it precisely when this transaction allocated them
				// itself earlier, and refusing them for that reason is what made a create-delete-create loop walk up
				// the segment instead of turning one slot over.
				free = !segment.freeSlots().isAllocated(run.slot() + i);
			}
			if (free) {
				return claim(segment, run.slot(), count);
			}
		}
		return ElementId.NULL;
	}

	/**
	 * True when every slot of a ring entry is free in the mapping and reserved by nobody.
	 *
	 * <p>
	 * The mapping rather than the caller's view, and this is the whole of why {@code isAllocatedLive} exists. A slot
	 * another transaction committed since this one began is invisible through the view — which is right for reading it
	 * and catastrophic for allocating it.
	 * </p>
	 */
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
			if (bitmap.isAllocatedLive(slot + i) || reserved.containsKey(pack(number, slot + i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * True when the slots are also free in the calling transaction's own view.
	 *
	 * <p>
	 * A slot can be free in the mapping and still hold a record this transaction is entitled to see: another writer
	 * deleted it after this one's snapshot. Allocating it would be safe for everybody else and wrong for the caller —
	 * it would overwrite a record its own reads are still supposed to return. So it is passed over and put back, not
	 * dropped: it becomes reusable again as soon as a transaction begins that cannot see the old record.
	 * </p>
	 */
	private boolean isFreeInView(long packed, int count) {
		int number = (int) (packed >>> 32);
		if (number >= segments.size()) {
			return false;
		}
		FreeSlotBitmap bitmap = segments.get(number).freeSlots();
		int slot = slotOf(packed);
		for (int i = 0; i < count; i++) {
			if (bitmap.isAllocated(slot + i)) {
				return false;
			}
		}
		return true;
	}

	private void noteHighWater(SegmentFile segment, long before) {
		WriteContext context = contexts == null ? null : contexts.current();
		if (context != null) {
			context.noteHighWater(segment.segmentKey(), before);
		}
	}

	private List<Long> defer(List<Long> deferred, long packed) {
		List<Long> list = deferred == null ? new ArrayList<>(4) : deferred;
		list.add(packed);
		return list;
	}

	private void returnDeferred(List<Long> deferred) {
		if (deferred == null) {
			return;
		}
		for (Long entry : deferred) {
			if (freeRing.size() < FREE_RING_CAPACITY) {
				freeRing.addLast(entry);
			}
		}
	}

	/** The first slot in {@code [from, limit)} that is neither allocated in the mapping nor reserved in flight. */
	/** Where the next scan of an exhausted segment starts, by segment number. A hint only; held under the alloc lock. */
	private final Map<Integer, Integer> exhaustedScanFrom = new HashMap<>();

	/**
	 * A free run in a segment whose bump cursor is exhausted, scanning from where the last such scan stopped and
	 * wrapping once.
	 *
	 * <p>
	 * The ring is empty after every reopen, so this is how a full segment is refilled, one allocation at a time.
	 * Starting each scan at slot 0 made that quadratic in the number of holes — measured at 15x slower than an ordinary
	 * create on 16,384 holes in one segment. The wrap keeps it complete: a slot freed below the cursor is still found.
	 * </p>
	 */
	private int scanExhausted(SegmentFile segment, int count) {
		int number = segment.segmentNumber();
		int from = Math.min(exhaustedScanFrom.getOrDefault(number, 0), slotsPerSegment);
		int found = count == 1 ? nextFreeUnreserved(segment, from, slotsPerSegment)
			: nextFreeRunUnreserved(segment, from, slotsPerSegment, count);
		if (found < 0 && from > 0) {
			// Runs that start below the cursor and reach past it are covered by the limit.
			int limit = (int) Math.min(slotsPerSegment, (long) from + count - 1);
			found = count == 1 ? nextFreeUnreserved(segment, 0, limit)
				: nextFreeRunUnreserved(segment, 0, limit, count);
		}
		if (found >= 0) {
			exhaustedScanFrom.put(number, found + count);
		}
		return found;
	}

	private int nextFreeUnreserved(SegmentFile segment, int from, int limit) {
		int number = segment.segmentNumber();
		for (int slot = from; slot >= 0 && slot < limit;) {
			int candidate = segment.freeSlots().nextFreeLive(slot, limit);
			if (candidate < 0) {
				return -1;
			}
			if (!reserved.containsKey(pack(number, candidate)) && !segment.freeSlots().isAllocated(candidate)) {
				return candidate;
			}
			slot = candidate + 1;
		}
		return -1;
	}

	private int nextFreeRunUnreserved(SegmentFile segment, int from, int limit, int count) {
		int number = segment.segmentNumber();
		for (int slot = from; slot >= 0 && slot + count <= limit;) {
			int candidate = segment.freeSlots().nextFreeRunLive(slot, limit, count, slotsPerPage);
			if (candidate < 0) {
				return -1;
			}
			boolean taken = false;
			for (int i = 0; i < count; i++) {
				if (reserved.containsKey(pack(number, candidate + i))
					|| segment.freeSlots().isAllocated(candidate + i)) {
					taken = true;
					break;
				}
			}
			if (!taken) {
				return candidate;
			}
			slot = candidate + 1;
		}
		return -1;
	}

	private long claim(SegmentFile segment, int slot, int count) {
		FreeSlotBitmap bitmap = segment.freeSlots();
		WriteContext context = contexts == null ? null : contexts.current();
		long segmentKey = segment.segmentKey();
		for (int i = 0; i < count; i++) {
			// Live rather than the caller's view, except for a slot this very transaction freed — which is still
			// allocated in the mapping, because its delete has not committed, and is nevertheless the transaction's to
			// take back. Nobody else can be racing for it: it was live until this transaction freed it, and it goes
			// into the reservation set below before the lock is released.
			boolean mine = context != null && context.freedHere(segmentKey, slot + i);
			if (bitmap.isAllocated(slot + i)
				|| (!mine && (reserved.containsKey(pack(segment.segmentNumber(), slot + i))
					|| bitmap.isAllocatedLive(slot + i)))) {
				// Two records at one id is the worst corruption this engine can produce, so it fails loudly rather
				// than quietly overwriting.
				throw new IllegalStateException("Double allocation of " + kind + " segment " + segment.segmentNumber()
					+ " slot " + (slot + i) + " (view=" + bitmap.isAllocated(slot + i)
					+ " live=" + bitmap.isAllocatedLive(slot + i)
					+ " reserved=" + reserved.containsKey(pack(segment.segmentNumber(), slot + i))
					+ " reservedBy=" + reserved.get(pack(segment.segmentNumber(), slot + i))
					+ " me=" + (context == null ? null : context.txnId())
					+ " freedHere=" + mine + " highWater=" + segment.superblock().highWaterSlot() + ")");
			}
			bitmap.allocate(slot + i);
		}
		if (context != null) {
			for (int i = 0; i < count; i++) {
				reserved.put(pack(segment.segmentNumber(), slot + i), context.txnId());
			}
			context.reserved(segmentKey, slot, count);
		}
		// Read the generation before clearing: free() left the advanced value in the record, and zeroing first would
		// wipe it and make every reused slot look like generation 1.
		//
		// From the mapping, not from this transaction's view, and that distinction is the whole correctness of a
		// reused slot. The counter is allocator state — like the bitmap — and the transaction that freed this slot may
		// have committed after this one's snapshot, so the view still shows the value from before the delete. Claiming
		// with that value hands out an id byte-for-byte identical to one already given away, and worse: the copy would
		// then match its base at those bytes, the commit's diff would not carry them, and the record would land under a
		// generation on disk that its own id does not match. That is the StaleIdException a reader gets one read later.
		int page = geometry.pageOfSlot(kind, slot);
		boolean freedByMe = context != null && context.freedHere(segmentKey, slot);
		int gen = freedByMe ? readGen(segment, slot) : readGenLive(segment, slot);
		if (gen == 0) {
			// Zero means the counter is gone, not that it was never set. Hole punching returns an all-free page's
			// blocks to the filesystem and the generation lives inside the record, so a punched page reads as zeros
			// and the page's floor is the only surviving memory of what these slots reached. Without it the slot
			// restarts at 1 and hands out an id byte-for-byte identical to one deleted earlier, which then resolves
			// to the new record instead of failing as stale.
			//
			// Only when it is zero, never as a maximum against a live counter. A floor that overrode a real value
			// would pin every slot on the page once the floor reached MAX_GEN: free() wraps the record to 1, the
			// maximum would put it straight back to 4095, and delete-then-recreate would return the same id every
			// time — a certainty rather than the one-in-4095 the wrap is documented to be.
			gen = segment.pageMeta().genFloor(page);
		}
		// The floor field is wider than the id can carry, so clamp rather than let encode() mask a large value down
		// to zero — which is NULL, and would be a genuinely confusing thing to hand out.
		gen = gen == 0 || gen > ElementId.MAX_GEN ? 1 : gen;

		MemorySegment run = segment.recordsForWrite(slot, count);
		run.fill((byte) 0);
		writeGen(segment, slot, gen);
		if (readGen(segment, slot) != gen) {
			// Unreachable: writeGen wrote it through the same overlay readGen reads. Asserted because getting it wrong
			// is invisible until a reader resolves the id and is told its generation is stale.
			throw new IllegalStateException("Generation " + gen + " did not take on " + kind + " slot " + slot);
		}

		segment.superblock().liveCountDelta(count);
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

		segment.superblock().liveCountDelta(-count);
		segment.superblock().freeCountDelta(count);
		int page = geometry.pageOfSlot(kind, slot);
		segment.pageMeta().liveCountDelta(page, -count);
		segment.pageMeta().bumpEpoch(page);
		segment.markDirty(slot);

		WriteContext context = contexts == null ? null : contexts.current();
		if (context != null) {
			// Held back until the commit. A slot on the free ring is a slot the allocator will hand out, and handing
			// out one whose delete has not committed would let a rollback restore a record somebody else now owns.
			context.freed(segment.segmentKey(), slot, count);
			return;
		}
		allocLock.lock();
		try {
			clearHints();
			if (count == 1) {
				pushFree(segment.segmentNumber(), slot);
			} else {
				Deque<Long> ring = freeRuns.computeIfAbsent(count, k -> new ArrayDeque<>());
				if (ring.size() < FREE_RING_CAPACITY) {
					ring.addLast(pack(segment.segmentNumber(), slot));
				}
			}
		} finally {
			allocLock.unlock();
		}
	}

	private void pushFree(int segmentNumber, int slot) {
		if (freeRing.size() < FREE_RING_CAPACITY) {
			freeRing.addLast(pack(segmentNumber, slot));
		}
	}

	/**
	 * Reorders the free ring so the lowest slots are handed out first, packing the next run of allocations against the
	 * bottom of the segment instead of scattering it over the whole freed range.
	 *
	 * <p>
	 * The ring is FIFO everywhere else, and deliberately so: rotating through the segment before reusing any one slot
	 * is what keeps the 12 bit generation counter a useful dangling-id detector. That argument does not apply to a kind
	 * whose ids never leave the engine, and FIFO costs real space when a caller frees a whole structure and immediately
	 * rebuilds it. The rebuild scatters itself across the freed range; one live record near the top is then enough to
	 * stop {@link #shrinkHighWater()} trimming anything; and the high water mark ratchets up for the life of the store.
	 * Measured on the index: 57 live pages sitting under a mark of 252.
	 * </p>
	 *
	 * <p>
	 * <b>Only safe for a kind whose ids are internal.</b> Never call it for NODE, REL or anything else an application
	 * can hold across the reuse — that is what the generation counter is protecting.
	 * </p>
	 */
	public void packFreeRing() {
		allocLock.lock();
		try {
			WriteContext context = contexts == null ? null : contexts.current();
			if (context != null) {
				// Inside a transaction its own freed runs are what the rebuild will actually draw on; the shared ring
				// is not reached until they run out.
				context.packReusable();
			}
			if (freeRing.size() < 2) {
				return;
			}
			List<Long> sorted = new ArrayList<>(freeRing);
			java.util.Collections.sort(sorted);
			freeRing.clear();
			freeRing.addAll(sorted);
		} finally {
			allocLock.unlock();
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
		allocLock.lock();
		try {
			for (int number = 0; number < segments.size() && number <= limitSegment; number++) {
				SegmentFile segment = segments.get(number);
				int high = (int) Math.min(segment.superblock().highWaterSlot(), slotsPerSegment);
				int limit = number == limitSegment ? Math.min(high, limitSlot) : high;
				if (limit < count) {
					continue;
				}
				int from = hintFor(number);
				int slot = count == 1
					? nextFreeUnreserved(segment, from, limit)
					: nextFreeRunUnreserved(segment, from, limit, count);
				if (slot >= 0) {
					// Everything below the claimed slot is now allocated, so the next scan can start here. Without the
					// hint a relocation pass rescans the whole bitmap from zero for every single record it moves.
					lowestFreeHint[number] = slot;
					return claim(segment, slot, count);
				}
				lowestFreeHint[number] = limit;
			}
			return ElementId.NULL;
		} finally {
			allocLock.unlock();
		}
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
		resetAllocatorCaches();
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
			resetAllocatorCaches();
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
	 * Asks the kernel to start fetching the pages these ids live on, without waiting for any of them.
	 *
	 * <p>
	 * This is the prefetch capability of {@link io.metaloom.graph.core.io.IoEngine#prefetch} applied to a set of
	 * records. On a memory mapped data path a reader never issues a read — it touches an address and takes a page
	 * fault — so the only way to have several of those in flight at once is to name the pages before the walk reaches
	 * them. A traversal knows its next frontier one level in advance, which is exactly the information needed.
	 * </p>
	 *
	 * <p>
	 * <b>Purely advisory, in both directions.</b> The kernel may ignore it; a page may be resident already; an id may
	 * be stale. None of those is an error and none changes what a later read returns, so every failure here is
	 * swallowed rather than reported. A prefetch that threw would turn a performance hint into a correctness hazard.
	 * </p>
	 *
	 * @return how many ids were successfully advised, which is diagnostic only
	 */
	public int prefetch(long[] ids, int count) {
		int advised = 0;
		for (int i = 0; i < count; i++) {
			long id = ids[i];
			if (ElementId.isNull(id) || ElementId.kindCode(id) != kind.code()) {
				continue;
			}
			try {
				int number = ElementId.segment(id);
				if (number >= segments.size()) {
					continue;
				}
				SegmentFile file = segments.get(number);
				if (file.prefetchRecord(geometry.byteOffset(kind, ElementId.slot(id)), geometry.stride(kind))) {
					advised++;
				}
			} catch (RuntimeException e) {
				// A stale or malformed id is not worth failing a hint over.
			}
		}
		return advised;
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

	/**
	 * True if the id names a live record <em>in the mapping</em> — committed, and therefore visible to everybody.
	 *
	 * <p>
	 * {@link #isLive} answers for the caller's own view, which is the right question almost everywhere and the wrong
	 * one for anything a thread intends to publish to other threads. Deliberately uncounted: it reads the mapping
	 * directly rather than through {@code record()}, because it is a question about existence rather than a record the
	 * caller's query had to touch, and the read budgets are about the latter.
	 * </p>
	 */
	public boolean isLiveInMapping(long id) {
		if (ElementId.isNull(id) || ElementId.kindCode(id) != kind.code()) {
			return false;
		}
		int segmentNumber = ElementId.segment(id);
		if (segmentNumber >= segments.size()) {
			return false;
		}
		SegmentFile segment = segments.get(segmentNumber);
		int slot = ElementId.slot(id);
		if (slot >= slotsPerSegment || !segment.freeSlots().isAllocatedLive(slot)) {
			return false;
		}
		int gen = Short.toUnsignedInt(segment.mappedSlice(geometry.byteOffset(kind, slot), stride())
			.get(Layouts.SHORT, genOffset)) & ElementId.MAX_GEN;
		return gen == ElementId.gen(id);
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

	/**
	 * The record slice for an id if it still names a live record, or null where {@link #isLive} would say false.
	 *
	 * <p>
	 * {@link #isLive} followed by {@link #resolve} is the same answer for twice the work: two bitmap reads and two
	 * record touches. A caller that tests liveness only in order to read the record — the vector index does it for
	 * every neighbour it considers — asks once here instead. The generation is read out of the returned slice, as
	 * {@link #resolve} does.
	 * </p>
	 */
	public MemorySegment resolveIfLive(long id) {
		if (ElementId.isNull(id) || ElementId.kindCode(id) != kind.code()) {
			return null;
		}
		int segmentNumber = ElementId.segment(id);
		if (segmentNumber >= segments.size()) {
			return null;
		}
		SegmentFile segment = segments.get(segmentNumber);
		int slot = ElementId.slot(id);
		if (slot >= slotsPerSegment || !segment.freeSlots().isAllocated(slot)) {
			return null;
		}
		MemorySegment record = segment.record(slot);
		int stored = Short.toUnsignedInt(record.get(Layouts.SHORT, genOffset)) & ElementId.MAX_GEN;
		return stored == ElementId.gen(id) ? record : null;
	}

	/** Calls the consumer with the id of every live slot. The bitmap is the authority on what is live. */
	public void forEachLiveId(java.util.function.LongConsumer consumer) {
		forEachLiveIdWhile(id -> {
			consumer.accept(id);
			return true;
		});
	}

	/**
	 * Every live id, until the predicate says stop.
	 *
	 * <p>
	 * The stopping form is what makes {@code LIMIT} cost what it should. Without it a query wanting ten rows out of ten
	 * million nodes still walks ten million bitmap bits and decodes ten million generations, and the limit is applied to
	 * a list that has already been built.
	 * </p>
	 *
	 * @return false if the predicate stopped the scan, true if it ran to the end
	 */
	public boolean forEachLiveIdWhile(java.util.function.LongPredicate consumer) {
		return scanLive(consumer, writing());
	}

	/**
	 * @param consumerMayWrite
	 *            whether the consumer can change this kind's bitmap during the scan, which only a consumer running
	 *            inside a write transaction can
	 */
	private boolean scanLive(java.util.function.LongPredicate consumer, boolean consumerMayWrite) {
		for (SegmentFile segment : segments) {
			int high = (int) Math.min(segment.superblock().highWaterSlot(), slotsPerSegment);
			FreeSlotBitmap bitmap = segment.freeSlots();
			// A word at a time rather than a bit at a time: one bitmap read covers sixty-four slots, and only the set
			// bits are visited. Reading the word once per slot was a third of a compaction, which lists every live id
			// of every kind several times over.
			for (int first = 0; first < high; first += 64) {
				int wordIndex = first >>> 6;
				long window = high - first >= 64 ? -1L : (1L << (high - first)) - 1;
				long bits = bitmap.wordAt(wordIndex) & window;
				while (bits != 0) {
					int bit = Long.numberOfTrailingZeros(bits);
					int slot = first + bit;
					int gen = readGen(segment, slot) & ElementId.MAX_GEN;
					if (!consumer.test(ElementId.encode(kind, segment.segmentNumber(), slot, gen))) {
						return false;
					}
					// The slots above this one. A consumer inside a write transaction can allocate or free in this very
					// word, and the bit-at-a-time walk this replaced saw that, so the word is read again for it. Any
					// other change is another transaction's commit, which the scan's view either hides or — for a
					// throttled scan of the live mapping — races with whichever way the bitmap is read.
					long above = bit == 63 ? 0 : -1L << (bit + 1);
					bits = (consumerMayWrite ? bitmap.wordAt(wordIndex) & window : bits) & above;
				}
			}
		}
		return true;
	}

	/** Whether the calling thread has a write transaction open, and so can change the bitmap mid-scan. */
	private boolean writing() {
		return contexts != null && contexts.current() != null;
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
		// The collector writes nothing to the store, so the scan need not re-read a word after each id it yields.
		scanLive(id -> {
			if (written[0] == buffer[0].length) {
				buffer[0] = java.util.Arrays.copyOf(buffer[0], buffer[0].length * 2);
			}
			buffer[0][written[0]++] = id;
			return true;
		}, false);
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

	/** The generation stored in the mapping, past every transaction. The allocator's read; see {@link #claim}. */
	private int readGenLive(SegmentFile segment, int slot) {
		return Short.toUnsignedInt(segment.mappedSlice(geometry.byteOffset(kind, slot), stride())
			.get(Layouts.SHORT, genOffset));
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
