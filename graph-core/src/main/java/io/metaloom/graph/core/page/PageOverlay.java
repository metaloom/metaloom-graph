package io.metaloom.graph.core.page;

import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.util.LongObjectMap;

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

		/**
		 * The byte window {@code [writtenFrom, writtenTo)} covering every slice of the copy handed out for writing.
		 * Nothing outside it can differ from the base, so the commit's diff only has to look inside it — a metadata
		 * page typically changes one eight-byte bitmap word or one page-metadata entry, and diffing the whole page for
		 * it was a third of a small commit.
		 */
		private int writtenFrom = Integer.MAX_VALUE;
		private int writtenTo;

		Page(MemorySegment copy, MemorySegment base, boolean rebased) {
			this.copy = copy;
			this.base = base;
			this.rebased = rebased;
		}

		public MemorySegment copy() {
			return copy;
		}

		/**
		 * A slice of the copy for writing, widening the written window to cover it. Every write to an overlay page must
		 * come through here: bytes written any other way lie outside the window, and the commit would not see them.
		 */
		public MemorySegment writable(long offset, long length) {
			MemorySegment slice = copy.asSlice(offset, length);
			int from = (int) offset;
			int to = (int) (offset + length);
			if (from < writtenFrom) {
				writtenFrom = from;
			}
			if (to > writtenTo) {
				writtenTo = to;
			}
			return slice;
		}

		/** The first byte any write may have changed, or the page size when nothing was written. */
		public int writtenFrom() {
			return Math.min(writtenFrom, (int) copy.byteSize());
		}

		/** One past the last byte any write may have changed. */
		public int writtenTo() {
			return writtenTo;
		}

		/**
		 * The page as this transaction found it. The commit diffs against this, never against the live mapping — with
		 * one exception that is the same thing: a {@linkplain #isRebased rebased} page was found in the mapping under
		 * the commit lock, and its base <em>is</em> that mapped page, valid until the commit applies.
		 */
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

	/**
	 * Insertion ordered so that commit applies pages in the order they were first written. Primitive keys: this is
	 * consulted on every record a transaction reads or writes, and a boxed {@code Long} per lookup was half of what a
	 * bulk load allocated.
	 */
	private final LongObjectMap<Page> pages = new LongObjectMap<>();

	/**
	 * The page the last lookup found. Consecutive record touches land on the same page far more often than not — a
	 * record and its generation, a block and its header, a run of slots being claimed — so this answers most lookups
	 * without probing the table at all.
	 */
	private long lastKey;
	private Page lastPage;

	private final int pageSize;

	/**
	 * Page buffers this overlay borrowed from the calling thread's {@link #POOL}, given back by {@link #clear}.
	 *
	 * <h2>Why some page buffers are recycled</h2>
	 *
	 * <p>
	 * A freshly allocated page is cold: the young generation cycles through far more memory than any cache holds, so
	 * filling a new eight KiB array is eight KiB of cache misses — measured at about 1.4 µs per page for a copy and its
	 * base, against a small commit's dozen-odd pages. A buffer reused from the last commit is still in cache.
	 * </p>
	 *
	 * <p>
	 * <b>Only buffers nobody outside the overlay can be holding are recycled</b>: a data page's base, which nothing but
	 * the commit's diff ever reads, and a rebased page's copy, which only the journal replay and the commit read, both
	 * under the commit lock. A data page's copy is never recycled — {@link io.metaloom.graph.core.segment.SegmentFile}
	 * hands out slices of it to every read the transaction makes, and nothing bounds how long a caller keeps one.
	 * </p>
	 */
	private java.util.ArrayList<byte[]> borrowed;

	/** At most this many buffers per thread are kept: enough for a small commit's metadata, no more. */
	private static final int POOL_LIMIT = 32;

	private static final ThreadLocal<java.util.ArrayDeque<byte[]>> POOL =
		ThreadLocal.withInitial(java.util.ArrayDeque::new);

	public PageOverlay(int pageSize) {
		this.pageSize = pageSize;
	}

	/** A page buffer with unspecified contents, which the caller must overwrite entirely. */
	private byte[] borrow() {
		byte[] buffer = POOL.get().pollFirst();
		if (buffer == null || buffer.length != pageSize) {
			buffer = new byte[pageSize];
		}
		if (borrowed == null) {
			borrowed = new java.util.ArrayList<>();
		}
		borrowed.add(buffer);
		return buffer;
	}

	private void giveBack() {
		if (borrowed == null || borrowed.isEmpty()) {
			return;
		}
		java.util.ArrayDeque<byte[]> pool = POOL.get();
		for (byte[] buffer : borrowed) {
			if (pool.size() >= POOL_LIMIT) {
				break;
			}
			pool.addFirst(buffer);
		}
		borrowed.clear();
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
		Page page = find(key);
		return page == null ? null : page.copy;
	}

	/** The overlay page for a key, or null if the transaction has not written to it. */
	public Page find(long key) {
		Page last = lastPage;
		if (last != null && lastKey == key) {
			return last;
		}
		Page page = pages.get(key);
		if (page != null) {
			lastKey = key;
			lastPage = page;
		}
		return page;
	}

	/**
	 * Returns the overlay page, creating it from {@code source} on first write. Write to it through
	 * {@link Page#writable} only.
	 *
	 * @param source
	 *            the page as this transaction is entitled to see it — its snapshot's image, which is the mapped page
	 *            itself whenever nothing has superseded it
	 */
	public Page forWrite(long key, MemorySegment source) {
		Page existing = find(key);
		if (existing != null) {
			return existing;
		}
		return create(key, source, false);
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
	public Page forWriteRebased(long key, MemorySegment mappedPage) {
		// mappedPage must be the mapping itself, not an image of it: it becomes the page's base by reference.
		Page existing = find(key);
		if (existing != null && existing.rebased) {
			return existing;
		}
		return create(key, mappedPage, true);
	}

	private Page create(long key, MemorySegment source, boolean rebased) {
		// A rebased page's copy is recycled; a data page's never is. See #borrowed.
		byte[] image = rebased ? borrow() : new byte[pageSize];
		MemorySegment copy = MemorySegment.ofArray(image);
		MemorySegment.copy(source, 0, copy, 0, pageSize);
		// A rebased page's base is the mapped page itself, not a copy of it. The rebase happens under the commit lock,
		// and the commit diffs the metadata pages straight after the fold, before anything is applied — so for the
		// whole time the base is read, nothing can change the mapping. A copy would be eight KiB allocated, filled and
		// thrown away per metadata page per commit, and a small commit touches nine or so of them.
		MemorySegment base;
		if (rebased) {
			base = source;
		} else {
			byte[] baseImage = borrow();
			System.arraycopy(image, 0, baseImage, 0, pageSize);
			base = MemorySegment.ofArray(baseImage);
		}
		Page page = new Page(copy, base, rebased);
		pages.put(key, page);
		lastKey = key;
		lastPage = page;
		return page;
	}

	public int size() {
		return pages.size();
	}

	/** The key of the {@code i}-th page in the order the transaction first wrote them. */
	public long keyAt(int i) {
		return pages.keyAt(i);
	}

	/** The {@code i}-th page in the order the transaction first wrote them. */
	public Page pageAt(int i) {
		return pages.valueAt(i);
	}

	public boolean isEmpty() {
		return pages.isEmpty();
	}

	/**
	 * Discards every page. Called once the transaction is over — committed or rolled back — and on the thread that ran
	 * it: the recycled buffers go back to that thread's pool, so nothing may read a base or a rebased copy after this.
	 */
	public void clear() {
		pages.clear();
		lastPage = null;
		giveBack();
	}
}
