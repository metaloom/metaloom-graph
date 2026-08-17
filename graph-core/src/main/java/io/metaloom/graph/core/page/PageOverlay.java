package io.metaloom.graph.core.page;

import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.graph.core.id.Kind;

/**
 * The private page copies of an in-flight transaction, each paired with the image it was copied from.
 * <p>
 * A writer never touches the mapped file. The first write to a page copies that page into the overlay, and every
 * subsequent read and write of the page inside the transaction goes to the copy. At commit the redo log is made
 * durable and only then are the copies applied to the mapping; a rollback simply discards them.
 * <p>
 * This is what makes recovery redo-only: uncommitted data is structurally incapable of reaching the data files, so
 * there is nothing to undo. The cost is that a transaction is bounded by memory, which is the standard no-steal
 * trade-off.
 *
 * <h2>Why the base image is kept</h2>
 *
 * <p>
 * With one writer at a time the commit could work out what it had changed by diffing the copy against the mapping:
 * nothing else could have moved in between. <b>With concurrent writers that is wrong, and wrong in the worst way.</b>
 * Writer A copies a page, writer B commits a change to a different record on it, and A's diff against the new mapping
 * then contains B's bytes as they were <em>before</em> B — so applying A's diff silently reverts B. Last-writer-wins
 * across a whole page is exactly the defect that kept concurrent writers out of this engine.
 * </p>
 *
 * <p>
 * Keeping the image the copy was made from removes it. The diff is {@code copy vs base}, which is precisely the bytes
 * <em>this</em> transaction wrote and nothing else, so two writers on one page compose by union rather than by
 * overwrite. It also hands conflict detection its input for free: those same runs are the transaction's write set, and
 * asking whether anything else changed those bytes since the base was taken is one walk of the page's version chain.
 * The cost is a second page of heap per touched page.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 5
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 6
 */
public final class PageOverlay {

	/** A private copy of one page, and the image it diverged from. */
	public static final class Page {

		private final MemorySegment copy;
		private final MemorySegment base;
		private final boolean rebased;

		Page(MemorySegment copy, MemorySegment base, boolean rebased) {
			this.copy = copy;
			this.base = base;
			this.rebased = rebased;
		}

		public MemorySegment copy() {
			return copy;
		}

		/** The page as this transaction found it. The commit diffs against this, never against the live mapping. */
		public MemorySegment base() {
			return base;
		}

		/**
		 * True when the base was taken from the live mapping at commit time rather than from the transaction's view.
		 *
		 * <p>
		 * Only the metadata regions are rebased, and only by the journal replay in
		 * {@link io.metaloom.graph.core.txn.MetaJournal}. Their contents merge as logical operations — set this bit,
		 * add this delta — rather than as bytes, so by the time they reach the overlay they are already resolved
		 * against everything committed since and there is nothing left for conflict detection to find. Checking them
		 * would report a conflict between two transactions that merely allocated slots in the same 64-slot word, which
		 * is most pairs of writers.
		 * </p>
		 */
		public boolean isRebased() {
			return rebased;
		}
	}

	/** Insertion ordered so that commit applies pages in the order they were first written. */
	private final Map<Long, Page> pages = new LinkedHashMap<>();

	private final int pageSize;

	public PageOverlay(int pageSize) {
		this.pageSize = pageSize;
	}

	public static long key(Kind kind, int segmentNumber, int pageIndex) {
		return ((long) kind.code() << 56) | ((long) segmentNumber << 32) | (pageIndex & 0xFFFFFFFFL);
	}

	public static Kind kindOf(long key) {
		return Kind.byCode((int) (key >>> 56));
	}

	public static int segmentOf(long key) {
		return (int) ((key >>> 32) & 0xFFFFFF);
	}

	public static int pageOf(long key) {
		return (int) key;
	}

	/** The overlay copy for a page, or null if the transaction has not written to it. */
	public MemorySegment peek(long key) {
		Page page = pages.get(key);
		return page == null ? null : page.copy;
	}

	/**
	 * Returns the overlay copy, creating it from {@code source} on first write.
	 *
	 * @param source
	 *            the page as this transaction is entitled to see it — its snapshot's image, which is the mapped page
	 *            itself whenever nothing has superseded it
	 */
	public MemorySegment forWrite(long key, MemorySegment source) {
		Page existing = pages.get(key);
		if (existing != null) {
			return existing.copy;
		}
		return create(key, source, false).copy;
	}

	/**
	 * Returns a copy whose base is {@code mappedPage} as it stands right now, discarding any earlier copy of the page.
	 *
	 * <p>
	 * The journal replay uses this and nothing else does. It runs under the commit lock, so "right now" is a fixed
	 * instant, and rebasing there is what lets a metadata change be expressed as an operation on the newest state
	 * rather than as bytes remembered from an older one.
	 * </p>
	 */
	public MemorySegment forWriteRebased(long key, MemorySegment mappedPage) {
		Page existing = pages.get(key);
		if (existing != null && existing.rebased) {
			return existing.copy;
		}
		return create(key, mappedPage, true).copy;
	}

	private Page create(long key, MemorySegment source, boolean rebased) {
		MemorySegment copy = MemorySegment.ofArray(new byte[pageSize]);
		MemorySegment base = MemorySegment.ofArray(new byte[pageSize]);
		MemorySegment.copy(source, 0, copy, 0, pageSize);
		MemorySegment.copy(source, 0, base, 0, pageSize);
		Page page = new Page(copy, base, rebased);
		pages.put(key, page);
		return page;
	}

	public Map<Long, Page> pages() {
		return pages;
	}

	public int size() {
		return pages.size();
	}

	public boolean isEmpty() {
		return pages.isEmpty();
	}

	public void clear() {
		pages.clear();
	}
}
