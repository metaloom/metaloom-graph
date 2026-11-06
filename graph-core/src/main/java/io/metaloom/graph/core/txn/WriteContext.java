package io.metaloom.graph.core.txn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.page.PageOverlay;

/**
 * Everything one in-flight write transaction owns, published to the segment layer for the thread that is running it.
 *
 * <p>
 * With a single writer this was one field — the overlay — and a thread-owner check. Concurrent writers need the rest of
 * it alongside, and need it to be strictly per-transaction: the view the writer reads through, the metadata operations
 * it has queued, and the slots it has reserved out of the shared allocator but not yet committed. Anything left in
 * shared state for the duration would be another writer's problem.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 6
 */
public final class WriteContext {

	/** One allocation or free that has not been committed yet: which segment, which slot, how many. */
	public record SlotRun(long segmentKey, int slot, int count) {
	}

	private final long txnId;
	private final PageOverlay overlay;
	private final MetaJournal journal = new MetaJournal();
	private final Snapshot snapshot;
	private final IsolationLevel level;

	private final List<SlotRun> reserved = new ArrayList<>();
	private final List<SlotRun> freed = new ArrayList<>();

	/**
	 * Slots this transaction freed and may hand straight back to itself, keyed by kind and run length.
	 *
	 * <p>
	 * A free does not reach the shared allocator until the transaction commits — a slot on the shared ring is one the
	 * allocator will give out, and giving out one whose delete has not committed would let a rollback restore a record
	 * somebody else now owns. But a transaction that frees a structure and immediately rebuilds it is the normal case,
	 * not an exotic one: it is what {@code IndexCatalog.resetTrees} does, and what every property block that changes
	 * size does. Without this the rebuild takes fresh slots off the bump cursor and the high water mark ratchets up
	 * once per rebuild, for the life of the store.
	 * </p>
	 *
	 * <p>
	 * Safe because these slots are the transaction's own: it held them, it freed them, and it takes them back before
	 * anybody else can see either event.
	 * </p>
	 */
	private final Map<Long, Deque<SlotRun>> reusable = new HashMap<>();

	/** Slots freed and then taken back within this transaction, so the allocator can tell them from live ones. */
	private final Set<Long> freedHere = new HashSet<>();

	/**
	 * Where each segment's high water mark stood before this transaction first pushed it up.
	 *
	 * <p>
	 * The mark is shared and moves the moment a slot is reserved, because two writers each bumping a private copy would
	 * both claim the same slot. That makes it the one piece of allocator state a rollback cannot simply discard — and
	 * leaving it raised broke a property this engine holds to: a rolled-back transaction and a crash in the middle of
	 * the same transaction must reach byte-identical stores, and a mark left high is a difference in
	 * {@code storageBytes}. So the value is remembered and put back, but only when nothing has been committed or
	 * reserved above it in the meantime.
	 * </p>
	 */
	private final Map<Long, Long> highWaterBefore = new HashMap<>();

	/**
	 * True while the commit is folding the journal back into the live metadata.
	 *
	 * <p>
	 * It flips two things at once, and they belong together. The metadata classes stop routing through the journal, so
	 * the same {@code allocate}/{@code liveCountDelta} calls that queued an operation now perform it; and the overlay
	 * rebases the pages they touch onto the mapping as it stands under the commit lock, so the operation lands on top
	 * of every writer that committed since this one began. Outside the commit lock it is always false.
	 * </p>
	 */
	private boolean replaying;

	/**
	 * Pages this transaction has read, tracked only for {@link IsolationLevel#SERIALIZABLE}.
	 *
	 * <p>
	 * Null at every other level, and deliberately so: recording a page key on every read costs a hash insert on the
	 * engine's hottest path, and snapshot isolation has no use for it. Validating it at commit is what turns
	 * first-committer-wins into serializability, because a transaction that decided anything from a page somebody else
	 * has since changed did not run at any single instant.
	 * </p>
	 */
	private final Set<Long> readSet;

	/**
	 * How many times this transaction has changed something: a page written into the overlay, or a slot reserved or
	 * freed. Together with {@link MetaJournal#changes()} it answers one question — did a statement that failed get as
	 * far as changing the transaction? — and nothing else reads it.
	 */
	private long mutations;

	/** Mutations {@link #forgive forgiven}, subtracted from the count. */
	private long forgiven;

	/** Why this transaction may no longer commit, or null while it still may. */
	private Throwable rollbackOnly;

	public WriteContext(long txnId, int pageSize, Snapshot snapshot, IsolationLevel level) {
		this.txnId = txnId;
		this.overlay = new PageOverlay(pageSize);
		this.snapshot = snapshot;
		this.level = level;
		this.readSet = level == IsolationLevel.SERIALIZABLE ? new HashSet<>() : null;
	}

	public long txnId() {
		return txnId;
	}

	public PageOverlay overlay() {
		return overlay;
	}

	public MetaJournal journal() {
		return journal;
	}

	/** The view this writer reads through. Never null: a writer that read the live mapping could not be isolated. */
	public Snapshot snapshot() {
		return snapshot;
	}

	public IsolationLevel level() {
		return level;
	}

	public boolean tracksReads() {
		return readSet != null;
	}

	public boolean isReplaying() {
		return replaying;
	}

	public void replaying(boolean value) {
		this.replaying = value;
	}

	public void read(long pageKey) {
		if (readSet != null) {
			readSet.add(pageKey);
		}
	}

	/**
	 * Byte ranges this transaction's changes were decided from and must not have been overwritten by a commit since its
	 * snapshot, at <b>every</b> isolation level: {@code {pageKey, offset, length}}.
	 *
	 * <p>
	 * Not a read set. A read set guards what the <i>caller</i> decided from, and {@code SNAPSHOT} deliberately does not
	 * keep one. This guards what the <i>engine</i> decided from: a node's record, read by {@code deleteNode} to know
	 * which index entries to remove and by {@code addNodeLabel} to know the node is there. Two such operations on one
	 * node can write disjoint bytes — the delete the slot's generation and the allocation bitmap, the label change the
	 * label fields — so first-committer-wins does not see them meet, both commit, and the index keeps an entry for a
	 * node that is gone. Validating the record's whole range makes the second of them a conflict whatever bytes each
	 * wrote. {@code DeleteRaceTest} pins both commit orders.
	 * </p>
	 */
	private List<long[]> guarded;

	/** {@code offset} and {@code length} within the page, the shape every other run here has. */
	public void guard(long pageKey, int offset, int length) {
		if (replaying) {
			return;
		}
		if (guarded == null) {
			guarded = new ArrayList<>();
		}
		guarded.add(new long[] { pageKey, offset, length });
	}

	public List<long[]> guarded() {
		return guarded == null ? List.of() : guarded;
	}

	public Set<Long> readSet() {
		return readSet == null ? Set.of() : readSet;
	}

	/**
	 * Labels (and relationship types, which share their dictionary) and property keys this transaction looked up and
	 * did not find, tracked only for {@link IsolationLevel#SERIALIZABLE}.
	 *
	 * <p>
	 * The other half of the read set. A lookup that misses answers "nothing carries this name" without reading any page
	 * — {@code nodesByLabel}, {@code nodeProperty}, {@code nodeHasLabel} all return on the miss — so a concurrent
	 * transaction that introduces the name and commits leaves no trace in {@link #readSet()}. The commit refuses the
	 * transaction when any of these names has come to exist since.
	 * </p>
	 */
	private Set<String> missedLabels;
	private Set<String> missedKeys;

	public void missed(boolean label, String name) {
		if (readSet == null || replaying) {
			return;
		}
		if (label) {
			if (missedLabels == null) {
				missedLabels = new HashSet<>();
			}
			missedLabels.add(name);
		} else {
			if (missedKeys == null) {
				missedKeys = new HashSet<>();
			}
			missedKeys.add(name);
		}
	}

	public Set<String> missedLabels() {
		return missedLabels == null ? Set.of() : missedLabels;
	}

	public Set<String> missedKeys() {
		return missedKeys == null ? Set.of() : missedKeys;
	}

	/** Records where the mark stood, the first time this transaction raises it for a segment. */
	public void noteHighWater(long segmentKey, long before) {
		highWaterBefore.putIfAbsent(segmentKey, before);
	}

	public Map<Long, Long> highWaterBefore() {
		return highWaterBefore;
	}

	/** Called by the segment layer for every page slice handed out for writing. */
	public void mutated() {
		mutations++;
	}

	/**
	 * A count that moves whenever the transaction changes. Compared before and after a statement, so it only has to be
	 * monotonic — and it errs towards moving: a page slice taken for writing counts whether or not a byte of it changes.
	 */
	public long mutations() {
		return mutations + journal.changes() - forgiven;
	}

	/**
	 * Stops counting everything changed since {@code mark}, a value {@link #mutations()} returned.
	 *
	 * <p>
	 * For writes a refusal makes on purpose before refusing. A unique check claims the index leaves its value would
	 * land in — a version bump, so two writers racing for one value are certain to conflict — and then refuses; that
	 * claim is harmless to commit and must not turn an up-front refusal into a rollback-only transaction.
	 * </p>
	 */
	public void forgive(long mark) {
		forgiven += mutations() - mark;
	}

	/**
	 * Marks the transaction as unable to commit, because a statement failed after it had changed it.
	 *
	 * <p>
	 * There is no statement-level undo — the overlay holds one copy of each page, not one per statement — so the part
	 * of a failed statement that already ran cannot be taken out again. Committing it would make half a statement
	 * durable: a node with no index entries, a blob nothing points at. Refusing the commit is the only safe answer
	 * left, and the first cause is kept, because it is the one the caller needs to see.
	 * </p>
	 */
	public void rollbackOnly(Throwable cause) {
		if (rollbackOnly == null) {
			rollbackOnly = cause;
		}
	}

	/** Why this transaction is rollback-only, or null if it is not. */
	public Throwable rollbackOnlyCause() {
		return rollbackOnly;
	}

	public void reserved(long segmentKey, int slot, int count) {
		mutations++;
		reserved.add(new SlotRun(segmentKey, slot, count));
	}

	public void freed(long segmentKey, int slot, int count) {
		mutations++;
		SlotRun run = new SlotRun(segmentKey, slot, count);
		freed.add(run);
		reusable.computeIfAbsent(reuseKey(segmentKey, count), k -> new ArrayDeque<>()).addLast(run);
		for (int i = 0; i < count; i++) {
			freedHere.add(slotKey(segmentKey, slot + i));
		}
	}

	/** A run of {@code count} slots of this kind that the transaction freed and has not taken back, or null. */
	public SlotRun takeReusable(int kindCode, int count) {
		Deque<SlotRun> queue = reusable.get(((long) kindCode << 32) | (count & 0xFFFFFFFFL));
		return queue == null ? null : queue.pollFirst();
	}

	/** True when this transaction is the one that freed the slot, so it may claim it back before the commit. */
	public boolean freedHere(long segmentKey, int slot) {
		return freedHere.contains(slotKey(segmentKey, slot));
	}

	/**
	 * Orders the reusable runs by position so a rebuild packs against the bottom of the segment.
	 *
	 * @see io.metaloom.graph.core.segment.SegmentSet#packFreeRing()
	 */
	public void packReusable() {
		for (Deque<SlotRun> queue : reusable.values()) {
			if (queue.size() < 2) {
				continue;
			}
			List<SlotRun> sorted = new ArrayList<>(queue);
			sorted.sort(Comparator.comparingLong(SlotRun::segmentKey).thenComparingInt(SlotRun::slot));
			queue.clear();
			queue.addAll(sorted);
		}
	}

	private static long reuseKey(long segmentKey, int count) {
		return (segmentKey & 0xFFFFFFFF00000000L) | (count & 0xFFFFFFFFL);
	}

	private static long slotKey(long segmentKey, int slot) {
		return (segmentKey << 24) ^ slot;
	}

	/** Slots taken out of the shared allocator by this transaction and not yet visible to anybody else. */
	public List<SlotRun> reservedSlots() {
		return reserved;
	}

	/** Slots this transaction released. They only reach the free rings if it commits. */
	public List<SlotRun> freedSlots() {
		return freed;
	}
}
