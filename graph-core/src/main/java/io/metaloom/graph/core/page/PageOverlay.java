package io.metaloom.graph.core.page;

import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.graph.core.id.Kind;

/**
 * The private page copies of an in-flight transaction.
 * <p>
 * A writer never touches the mapped file. The first write to a page copies that page into the overlay, and every
 * subsequent read and write of the page inside the transaction goes to the copy. At commit the redo log is made
 * durable and only then are the copies applied to the mapping; a rollback simply discards them.
 * <p>
 * This is what makes recovery redo-only: uncommitted data is structurally incapable of reaching the data files, so
 * there is nothing to undo. The cost is that a transaction is bounded by memory, which is the standard no-steal
 * trade-off.
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 5
 */
public final class PageOverlay {

	/** Insertion ordered so that commit applies pages in the order they were first written. */
	private final Map<Long, MemorySegment> pages = new LinkedHashMap<>();

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
		return pages.get(key);
	}

	/**
	 * Returns the overlay copy, creating it from the mapped page on first write.
	 */
	public MemorySegment forWrite(long key, MemorySegment mappedPage) {
		MemorySegment existing = pages.get(key);
		if (existing != null) {
			return existing;
		}
		MemorySegment copy = MemorySegment.ofArray(new byte[pageSize]);
		MemorySegment.copy(mappedPage, 0, copy, 0, pageSize);
		pages.put(key, copy);
		return copy;
	}

	public Map<Long, MemorySegment> pages() {
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
