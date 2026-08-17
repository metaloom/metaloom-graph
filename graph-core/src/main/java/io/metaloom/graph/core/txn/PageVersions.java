package io.metaloom.graph.core.txn;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The before-images that let an old reader reconstruct a page the writer has since changed.
 *
 * <h2>Why this is not the {@code UndoStore} of the spec</h2>
 *
 * <p>
 * [TRANSACTIONS.md §5](../../../../../../../../spec/TRANSACTIONS.md) specifies undo as 64-byte records in an on-disk
 * {@code UNDO} segment, chained per element through {@code version_ptr}, with {@code xmin} on each record resolving
 * visibility. That design belongs to the ARIES steal/no-force engine the spec was written against. <b>This engine is
 * no-steal</b> — [reports/PHASE_STATUS.md §1](../../../../../../../../spec/reports/PHASE_STATUS.md) — and the
 * difference changes the answer:
 * </p>
 *
 * <ul>
 * <li><b>Undo does not need to be durable.</b> Uncommitted data structurally cannot reach a data file, so recovery is
 * redo-only and there is nothing for a persisted undo record to serve. After a crash there are no live snapshots
 * either. Undo here is needed exactly as long as the oldest open reader, which is a process-lifetime concern, so it
 * lives in memory and costs no log record, no segment kind and no format version.</li>
 * <li><b>Record-granular undo cannot cover what a reader actually reads.</b> A traversal touches adjacency blocks, a
 * lookup touches index pages, and every one of those touches the free-slot bitmap and the page metadata array. None of
 * those has an {@code xmin}. Versioning the <em>page</em> covers the whole engine uniformly through one choke point —
 * {@code SegmentFile.readSlice} — instead of requiring every store, the allocator, the B+tree and the catalog each to
 * grow their own visibility rule.</li>
 * </ul>
 *
 * <p>
 * The {@code xmin} and {@code version_ptr} fields stay in the record layouts, written as zero. They are not needed by
 * this mechanism and the format does not change either way.
 * </p>
 *
 * <h2>Shape</h2>
 *
 * <p>
 * One chain per page, newest first, each entry recording the bytes a commit overwrote:
 * </p>
 *
 * <pre>
 * page 7 ── (superseded at 940, offset 128, 64 bytes) ── (at 902, offset 4096, 8 bytes) ── null
 * </pre>
 *
 * <p>
 * To read page 7 as of LSN 910: start from the mapping as it is now, then apply every entry whose LSN is greater than
 * 910, newest first. Older entries win where the ranges overlap, which is what walking newest-first achieves. An entry
 * at or below the snapshot LSN describes a change the snapshot is entitled to see, so the walk stops there.
 * </p>
 *
 * <p>
 * <b>The chains are immutable lists with a volatile head.</b> Appending publishes a new head; a reader walking an older
 * head sees a consistent suffix and never a half-linked node. Appends happen only under the store's apply lock, so
 * there is one appender.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 5
 */
public final class PageVersions {

	/** One overwritten byte run, and the chain link to the run overwritten before it. */
	public static final class Version {

		final long supersededAt;
		final int offset;
		final byte[] before;

		/**
		 * The next older entry, or null.
		 * <p>
		 * Not final because vacuum truncates the tail by nulling a link. A reader already walking into the truncated
		 * tail simply stops early, which is safe: vacuum only ever cuts below the oldest active snapshot, and a
		 * snapshot registering concurrently takes a newer LSN than that, so no live reader can need what was cut.
		 */
		volatile Version older;

		Version(long supersededAt, int offset, byte[] before, Version older) {
			this.supersededAt = supersededAt;
			this.offset = offset;
			this.before = before;
			this.older = older;
		}

		public long supersededAt() {
			return supersededAt;
		}
	}

	/**
	 * One page's chain, with the LSN of its <em>oldest</em> entry alongside its newest.
	 *
	 * <p>
	 * The oldest LSN is what makes reclamation cheap, and it is not an optimisation that can be left out. Vacuum runs
	 * after every commit and can only cut entries at or below the oldest open view. While a view is pinned there are
	 * none — every entry was created after it — so without this field vacuum walks every chain to its tail on every
	 * commit to discover that, which is O(entries) per commit and therefore quadratic over a run. Measured: a reader
	 * held open across sixty thousand writes did not finish in minutes. With it, vacuum answers in O(1).
	 * </p>
	 */
	private static final class Chain {

		volatile Version head;
		volatile long oldest;

		Chain(Version head, long oldest) {
			this.head = head;
			this.oldest = oldest;
		}
	}

	private final ConcurrentHashMap<Long, Chain> chains = new ConcurrentHashMap<>();
	private final AtomicLong retainedBytes = new AtomicLong();
	private final AtomicLong entries = new AtomicLong();

	/** The oldest LSN any snapshot can still be reconstructed at; below this the before-images are gone. */
	private volatile long horizonLsn;

	/** The smallest {@code oldest} across every chain, so vacuum can decline in one comparison. */
	private volatile long oldestRetainedLsn = Long.MAX_VALUE;

	/** The newest chain entry for a page, or null when the mapping is the only version there has ever been. */
	public Version head(long pageKey) {
		Chain chain = chains.get(pageKey);
		return chain == null ? null : chain.head;
	}

	public long horizonLsn() {
		return horizonLsn;
	}

	public long retainedBytes() {
		return retainedBytes.get();
	}

	public long entryCount() {
		return entries.get();
	}

	public int pageCount() {
		return chains.size();
	}

	/**
	 * Records the bytes a commit is about to overwrite.
	 *
	 * <p>
	 * Must be called <em>before</em> the mapping is modified and under the same exclusion as the modification itself,
	 * so that the pair (mapping, chains) is never observed half-updated.
	 * </p>
	 *
	 * @param pageKey
	 *            the page, as {@link io.metaloom.graph.core.page.PageOverlay#key}
	 * @param commitLsn
	 *            the commit that is overwriting these bytes
	 * @param mappedPage
	 *            the page as it currently stands in the mapping
	 * @param offset
	 *            the first byte within the page that the commit changes
	 * @param length
	 *            how many bytes it changes
	 */
	public void record(long pageKey, long commitLsn, MemorySegment mappedPage, int offset, int length) {
		byte[] before = new byte[length];
		MemorySegment.copy(mappedPage, offset, MemorySegment.ofArray(before), 0, length);
		chains.compute(pageKey, (key, chain) -> {
			if (chain == null) {
				return new Chain(new Version(commitLsn, offset, before, null), commitLsn);
			}
			chain.head = new Version(commitLsn, offset, before, chain.head);
			return chain;
		});
		if (commitLsn < oldestRetainedLsn) {
			// Only reachable for the first entry of the first chain: commit LSNs increase, so every later entry is
			// younger than something already retained.
			oldestRetainedLsn = commitLsn;
		}
		retainedBytes.addAndGet(length);
		entries.incrementAndGet();
	}

	/**
	 * Reconstructs a page as it stood at {@code snapshotLsn}, or returns null when the mapping already is that page.
	 *
	 * <p>
	 * Returning null on the common path is the point: a reader whose snapshot is younger than every change to a page —
	 * which is every page a short reader touches, and most pages a long one touches — gets the mapped page itself, with
	 * no copy and no allocation. Reconstruction happens only where the reader and the writer have actually diverged.
	 * </p>
	 *
	 * @param mappedPage
	 *            the page as it currently stands; copied only if reconstruction is needed
	 * @param pageSize
	 *            the size of that page
	 */
	public MemorySegment reconstruct(long pageKey, long snapshotLsn, MemorySegment mappedPage, int pageSize) {
		Version head = head(pageKey);
		if (head == null || head.supersededAt <= snapshotLsn) {
			return null;
		}
		byte[] image = new byte[pageSize];
		MemorySegment rebuilt = MemorySegment.ofArray(image);
		MemorySegment.copy(mappedPage, 0, rebuilt, 0, pageSize);
		for (Version version = head; version != null && version.supersededAt > snapshotLsn; version = version.older) {
			MemorySegment.copy(MemorySegment.ofArray(version.before), 0, rebuilt, version.offset,
				version.before.length);
		}
		return rebuilt;
	}

	/**
	 * The LSN of the first commit after {@code sinceLsn} that overwrote any of {@code [offset, offset + length)} on
	 * this page, or -1 when nothing did.
	 *
	 * <p>
	 * This is write-write conflict detection, and it costs one chain walk that stops as soon as it reaches an entry the
	 * transaction is entitled to see. The chains already hold exactly the right information: an entry says "this byte
	 * range was overwritten at this LSN", which is the question a committing transaction is asking about every run it
	 * is about to write.
	 * </p>
	 *
	 * <p>
	 * Byte ranges rather than whole pages, because whole pages would make two writers touching unrelated records in one
	 * page a conflict — and a page holds a hundred and twenty-eight nodes.
	 * </p>
	 */
	public long conflictingLsn(long pageKey, int offset, int length, long sinceLsn) {
		Chain chain = chains.get(pageKey);
		if (chain == null) {
			return -1;
		}
		int end = offset + length;
		for (Version version = chain.head; version != null && version.supersededAt > sinceLsn;
			version = version.older) {
			if (version.offset < end && offset < version.offset + version.before.length) {
				return version.supersededAt;
			}
		}
		return -1;
	}

	/**
	 * The LSN of the newest commit after {@code sinceLsn} that changed anything on this page, or -1.
	 *
	 * <p>
	 * The read-set half of {@link io.metaloom.graph.core.api.IsolationLevel#SERIALIZABLE} validation. Page granularity
	 * here rather than byte, and deliberately: a read is not recorded as a range — recording one per read would cost
	 * more than the isolation is worth — so the page is what the transaction can be said to have depended on.
	 * </p>
	 */
	public long changedSince(long pageKey, long sinceLsn) {
		Chain chain = chains.get(pageKey);
		if (chain == null) {
			return -1;
		}
		Version head = chain.head;
		return head != null && head.supersededAt > sinceLsn ? head.supersededAt : -1;
	}

	/**
	 * Drops every entry no snapshot at or after {@code oldestActiveLsn} can need, and returns the bytes reclaimed.
	 *
	 * <p>
	 * An entry superseded at L is needed only by snapshots strictly older than L, so once the oldest live snapshot has
	 * reached L the entry is dead. Chains are cut rather than rebuilt: the surviving prefix is shared with any reader
	 * currently walking it.
	 * </p>
	 */
	public long vacuum(long oldestActiveLsn) {
		if (oldestActiveLsn > horizonLsn) {
			horizonLsn = oldestActiveLsn;
		}
		if (chains.isEmpty() || oldestRetainedLsn > oldestActiveLsn) {
			// Nothing retained is old enough to cut. This is the state the store is in for the whole life of a pinned
			// view — every entry was created after it began — and answering in one comparison is what keeps a long
			// reader from making every commit walk the accumulated chains.
			return 0;
		}

		long reclaimed = 0;
		long oldestSurviving = Long.MAX_VALUE;
		for (java.util.Map.Entry<Long, Chain> entry : chains.entrySet()) {
			Chain chain = entry.getValue();
			Version head = chain.head;
			if (head.supersededAt <= oldestActiveLsn) {
				// The whole chain is dead, including the head. Conditional removal, because a commit may have
				// prepended a newer entry to the same page since the head was read.
				if (chains.remove(entry.getKey(), chain)) {
					reclaimed += drop(head);
					continue;
				}
				head = chain.head;
			}
			if (chain.oldest > oldestActiveLsn) {
				oldestSurviving = Math.min(oldestSurviving, chain.oldest);
				continue;
			}
			// Find the last entry the horizon still needs and cut everything below it.
			Version last = head;
			while (last.older != null && last.older.supersededAt > oldestActiveLsn) {
				last = last.older;
			}
			Version dead = last.older;
			if (dead != null) {
				last.older = null;
				reclaimed += drop(dead);
			}
			chain.oldest = last.supersededAt;
			oldestSurviving = Math.min(oldestSurviving, chain.oldest);
		}
		oldestRetainedLsn = oldestSurviving;
		retainedBytes.addAndGet(-reclaimed);
		return reclaimed;
	}

	/** Discards everything. Used when the last reader closes, and at store close. */
	public long clear() {
		long reclaimed = retainedBytes.getAndSet(0);
		chains.clear();
		entries.set(0);
		oldestRetainedLsn = Long.MAX_VALUE;
		return reclaimed;
	}

	private long drop(Version from) {
		long bytes = 0;
		for (Version version = from; version != null;) {
			bytes += version.before.length;
			entries.decrementAndGet();
			Version next = version.older;
			version.older = null;
			version = next;
		}
		return bytes;
	}
}
