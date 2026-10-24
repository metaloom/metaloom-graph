package io.metaloom.graph.core.index;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.util.Layouts;

/**
 * A persistent B+tree over {@code INDEX} pages, keyed on a 128 bit key and holding an 8 byte value.
 * <p>
 * Every node is exactly one page, addressed by an {@code INDEX} id, so the same arithmetic addressing as everything
 * else applies and a node lookup is a slice rather than a probe. Index ids are internal and never handed to an
 * application, which is why this is the one structure allowed to relocate pages freely.
 *
 * <pre>
 * Node header, 40 bytes:
 *  0  4  entry_count
 *  4  1  leaf
 *  5  3  reserved
 *  8  8  next_leaf        leaf chaining for range scans
 * 16  4  version          bumped by every change to the page, and by a unique check's claim
 * 20 10  reserved
 * 30  2  gen
 * 32  8  leftmost_child   internal nodes only
 * 40  N  entries
 * </pre>
 *
 * A leaf entry is {@code (k1, k2, value)}; an internal entry is {@code (k1, k2, child)} meaning "keys at or above this
 * separator live in that child". Deletion removes the entry without rebalancing: an underfull node is still correct,
 * and merging is a Phase 8 concern.
 *
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 3
 */
public final class BTree {

	static final int HEADER_BYTES = 40;
	static final int ENTRY_BYTES = 24;

	private static final int OFF_COUNT = 0;
	private static final int OFF_LEAF = 4;
	private static final int OFF_NEXT_LEAF = 8;
	/**
	 * Bumped by every transaction that changes the page, so that any two that change it concurrently conflict.
	 *
	 * <p>
	 * Conflict detection compares bytes, and a B+tree page is the one structure here whose changes <em>move</em>
	 * bytes. Updating a value removes one entry and inserts another, often in one leaf: the count comes out where it
	 * started, and the shifted entries between the two positions differ from their old images only where their bytes
	 * happen to differ — neighbouring keys share their high bytes, so the changed runs are fragments with gaps. A second
	 * writer's fragments can fall into those gaps, validation finds no overlap, and the merged page interleaves two
	 * different shifts: entries lost, entries duplicated, and {@code nodesByProperty} silently missing a node
	 * ({@code IndexLeafMergeTest}). One field every change writes makes the page the unit of conflict, which is what
	 * a structure that shifts its contents needs. Clear of the generation at 30, which the slot allocator owns.
	 * </p>
	 */
	private static final int OFF_VERSION = 16;
	private static final int OFF_LEFTMOST = 32;

	private final SegmentSet segments;
	private final int fanout;

	public BTree(SegmentSet segments) {
		if (segments.kind() != Kind.INDEX) {
			throw new IllegalArgumentException("BTree requires an INDEX segment set");
		}
		this.segments = segments;
		this.fanout = (segments.stride() - HEADER_BYTES) / ENTRY_BYTES;
		if (fanout < 4) {
			throw new IllegalStateException("Page size too small for a B+tree: fanout " + fanout);
		}
	}

	public int fanout() {
		return fanout;
	}

	/** Creates an empty tree and returns the id of its root leaf. */
	public long create() throws IOException {
		long id = segments.allocate();
		MemorySegment node = segments.mutable(id);
		setCount(node, 0);
		setLeaf(node, true);
		node.set(Layouts.LONG, OFF_NEXT_LEAF, ElementId.NULL);
		node.set(Layouts.LONG, OFF_LEFTMOST, ElementId.NULL);
		segments.touched(id);
		return id;
	}

	// ---------------------------------------------------------------- node accessors

	private static int count(MemorySegment node) {
		return node.get(Layouts.INT, OFF_COUNT);
	}

	private static void setCount(MemorySegment node, int value) {
		node.set(Layouts.INT, OFF_COUNT, value);
	}

	private static boolean isLeaf(MemorySegment node) {
		return node.get(Layouts.BYTE, OFF_LEAF) != 0;
	}

	private static void setLeaf(MemorySegment node, boolean leaf) {
		node.set(Layouts.BYTE, OFF_LEAF, (byte) (leaf ? 1 : 0));
	}

	private static long k1(MemorySegment node, int i) {
		return node.get(Layouts.LONG, HEADER_BYTES + (long) i * ENTRY_BYTES);
	}

	private static long k2(MemorySegment node, int i) {
		return node.get(Layouts.LONG, HEADER_BYTES + (long) i * ENTRY_BYTES + 8);
	}

	private static long payload(MemorySegment node, int i) {
		return node.get(Layouts.LONG, HEADER_BYTES + (long) i * ENTRY_BYTES + 16);
	}

	private static void setEntry(MemorySegment node, int i, long k1, long k2, long payload) {
		long base = HEADER_BYTES + (long) i * ENTRY_BYTES;
		node.set(Layouts.LONG, base, k1);
		node.set(Layouts.LONG, base + 8, k2);
		node.set(Layouts.LONG, base + 16, payload);
	}

	private static int compare(long a1, long a2, long b1, long b2) {
		int c = Long.compareUnsigned(a1, b1);
		return c != 0 ? c : Long.compareUnsigned(a2, b2);
	}

	/** Index of the first entry not less than the key. */
	private static int lowerBound(MemorySegment node, long key1, long key2) {
		int low = 0;
		int high = count(node);
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (compare(k1(node, mid), k2(node, mid), key1, key2) < 0) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	// ---------------------------------------------------------------- lookup

	/** Returns the value for an exact key, or null. */
	public Long get(long rootId, long key1, long key2) {
		long leafId = findLeaf(rootId, key1, key2);
		MemorySegment leaf = segments.resolve(leafId);
		int i = lowerBound(leaf, key1, key2);
		if (i < count(leaf) && compare(k1(leaf, i), k2(leaf, i), key1, key2) == 0) {
			return payload(leaf, i);
		}
		return null;
	}

	private long findLeaf(long nodeId, long key1, long key2) {
		long current = nodeId;
		while (true) {
			MemorySegment node = segments.resolve(current);
			if (isLeaf(node)) {
				return current;
			}
			current = childFor(node, key1, key2);
		}
	}

	private static long childFor(MemorySegment node, long key1, long key2) {
		int n = count(node);
		long child = node.get(Layouts.LONG, OFF_LEFTMOST);
		for (int i = 0; i < n; i++) {
			if (compare(k1(node, i), k2(node, i), key1, key2) <= 0) {
				child = payload(node, i);
			} else {
				break;
			}
		}
		return child;
	}

	/** Every value whose key has the given {@code k1}, in ascending {@code k2} order. */
	public List<Long> scanPrefix(long rootId, long key1) {
		List<Long> result = new ArrayList<>();
		long leafId = findLeaf(rootId, key1, 0L);
		while (!ElementId.isNull(leafId)) {
			MemorySegment leaf = segments.resolve(leafId);
			int n = count(leaf);
			int i = lowerBound(leaf, key1, 0L);
			boolean past = false;
			for (; i < n; i++) {
				if (k1(leaf, i) != key1) {
					past = true;
					break;
				}
				result.add(payload(leaf, i));
			}
			if (past) {
				break;
			}
			leafId = leaf.get(Layouts.LONG, OFF_NEXT_LEAF);
		}
		return result;
	}

	/**
	 * Writes to every leaf an entry with this {@code k1} could be inserted into, so that two transactions about to
	 * insert under one {@code k1} are certain to write a byte in common.
	 *
	 * <p>
	 * This is what a unique check needs and conflict detection cannot otherwise give it. Two inserts into one leaf
	 * already collide — both shift entries and write the count — but deletion does not rebalance, so a separator
	 * {@code (k1, nX)} can outlive every entry it once divided and route {@code (k1, n1 < nX)} and
	 * {@code (k1, n2 >= nX)} to different leaves. Two writers each probing for the other's value then find nothing,
	 * insert into different pages, and commit side by side with the same unique value, because snapshot isolation does
	 * not validate what they read. Bumping a counter in each leaf of the range turns that into a write-write conflict.
	 * </p>
	 *
	 * <p>
	 * The range is taken from the routing, not from the entries: from the leaf {@code (k1, 0)} descends to through the
	 * one {@code (k1, max)} descends to. A leaf holding no entry of the prefix can still be where one is inserted, which
	 * is exactly the stale-separator case. When no separator carries this {@code k1} — almost always — that is one leaf,
	 * the one the insert lands in anyway, so the claim adds no conflict the insert did not already have.
	 * </p>
	 */
	public void claimPrefix(long rootId, long key1) {
		long leafId = findLeaf(rootId, key1, 0L);
		long lastId = findLeaf(rootId, key1, -1L);
		while (!ElementId.isNull(leafId)) {
			MemorySegment leaf = changing(leafId);
			segments.touched(leafId);
			if (leafId == lastId) {
				return;
			}
			leafId = leaf.get(Layouts.LONG, OFF_NEXT_LEAF);
		}
	}

	/** An existing page made writable for a change, with the change recorded in its version — see {@link #OFF_VERSION}. */
	private MemorySegment changing(long id) {
		MemorySegment node = segments.mutable(id);
		node.set(Layouts.INT, OFF_VERSION, node.get(Layouts.INT, OFF_VERSION) + 1);
		return node;
	}

	// ---------------------------------------------------------------- insert

	/**
	 * Inserts or replaces a key. Returns the (possibly new) root id: a root split allocates a new root.
	 */
	public long put(long rootId, long key1, long key2, long value) throws IOException {
		Split split = insert(rootId, key1, key2, value);
		if (split == null) {
			return rootId;
		}
		long newRootId = segments.allocate();
		MemorySegment newRoot = segments.mutable(newRootId);
		setCount(newRoot, 1);
		setLeaf(newRoot, false);
		newRoot.set(Layouts.LONG, OFF_NEXT_LEAF, ElementId.NULL);
		newRoot.set(Layouts.LONG, OFF_LEFTMOST, rootId);
		setEntry(newRoot, 0, split.key1(), split.key2(), split.rightId());
		segments.touched(newRootId);
		return newRootId;
	}

	private record Split(long key1, long key2, long rightId) {
	}

	private Split insert(long nodeId, long key1, long key2, long value) throws IOException {
		MemorySegment node = segments.resolve(nodeId);
		if (isLeaf(node)) {
			return insertIntoLeaf(nodeId, key1, key2, value);
		}
		long childId = childFor(node, key1, key2);
		Split split = insert(childId, key1, key2, value);
		if (split == null) {
			return null;
		}
		return insertIntoInternal(nodeId, split.key1(), split.key2(), split.rightId());
	}

	private Split insertIntoLeaf(long leafId, long key1, long key2, long value) throws IOException {
		MemorySegment leaf = segments.resolve(leafId);
		int n = count(leaf);
		int i = lowerBound(leaf, key1, key2);
		boolean present = i < n && compare(k1(leaf, i), k2(leaf, i), key1, key2) == 0;
		if (present && payload(leaf, i) == value) {
			// Already there, as a backfill run twice finds it. Not a change, so not a version bump either: bumping
			// would make an idempotent re-put conflict with every concurrent writer of the leaf.
			return null;
		}
		leaf = changing(leafId);
		if (present) {
			setEntry(leaf, i, key1, key2, value);
			segments.touched(leafId);
			return null;
		}
		if (n < fanout) {
			shiftRight(leaf, i, n);
			setEntry(leaf, i, key1, key2, value);
			setCount(leaf, n + 1);
			segments.touched(leafId);
			return null;
		}

		// Split: move the upper half into a new leaf, then place the new entry on the correct side.
		long rightId = segments.allocate();
		MemorySegment right = segments.mutable(rightId);
		leaf = segments.mutable(leafId);
		int mid = n / 2;
		setLeaf(right, true);
		setCount(right, n - mid);
		right.set(Layouts.LONG, OFF_LEFTMOST, ElementId.NULL);
		for (int j = mid; j < n; j++) {
			setEntry(right, j - mid, k1(leaf, j), k2(leaf, j), payload(leaf, j));
		}
		setCount(leaf, mid);
		right.set(Layouts.LONG, OFF_NEXT_LEAF, leaf.get(Layouts.LONG, OFF_NEXT_LEAF));
		leaf.set(Layouts.LONG, OFF_NEXT_LEAF, rightId);

		if (compare(key1, key2, k1(right, 0), k2(right, 0)) < 0) {
			insertNoSplit(leaf, key1, key2, value);
		} else {
			insertNoSplit(right, key1, key2, value);
		}
		segments.touched(leafId);
		segments.touched(rightId);
		return new Split(k1(right, 0), k2(right, 0), rightId);
	}

	private Split insertIntoInternal(long nodeId, long key1, long key2, long childId) throws IOException {
		MemorySegment node = changing(nodeId);
		int n = count(node);
		int i = lowerBound(node, key1, key2);
		if (n < fanout) {
			shiftRight(node, i, n);
			setEntry(node, i, key1, key2, childId);
			setCount(node, n + 1);
			segments.touched(nodeId);
			return null;
		}
		long rightId = segments.allocate();
		MemorySegment right = segments.mutable(rightId);
		node = segments.mutable(nodeId);
		int mid = n / 2;
		setLeaf(right, false);
		right.set(Layouts.LONG, OFF_NEXT_LEAF, ElementId.NULL);
		// The middle separator is promoted rather than copied, which is what keeps internal nodes exclusive.
		long promoteK1 = k1(node, mid);
		long promoteK2 = k2(node, mid);
		right.set(Layouts.LONG, OFF_LEFTMOST, payload(node, mid));
		int moved = 0;
		for (int j = mid + 1; j < n; j++) {
			setEntry(right, moved++, k1(node, j), k2(node, j), payload(node, j));
		}
		setCount(right, moved);
		setCount(node, mid);

		if (compare(key1, key2, promoteK1, promoteK2) < 0) {
			insertNoSplit(node, key1, key2, childId);
		} else {
			insertNoSplit(right, key1, key2, childId);
		}
		segments.touched(nodeId);
		segments.touched(rightId);
		return new Split(promoteK1, promoteK2, rightId);
	}

	private void insertNoSplit(MemorySegment node, long key1, long key2, long payload) {
		int n = count(node);
		int i = lowerBound(node, key1, key2);
		shiftRight(node, i, n);
		setEntry(node, i, key1, key2, payload);
		setCount(node, n + 1);
	}

	private static void shiftRight(MemorySegment node, int from, int count) {
		for (int j = count; j > from; j--) {
			setEntry(node, j, k1(node, j - 1), k2(node, j - 1), payload(node, j - 1));
		}
	}

	// ---------------------------------------------------------------- delete

	/** Removes a key. Nodes are not merged: an underfull node is still a correct one. */
	public boolean remove(long rootId, long key1, long key2) {
		long leafId = findLeaf(rootId, key1, key2);
		// Looked up before the page is made writable: a miss must not copy the leaf into the transaction's overlay.
		MemorySegment leaf = segments.resolve(leafId);
		int n = count(leaf);
		int i = lowerBound(leaf, key1, key2);
		if (i >= n || compare(k1(leaf, i), k2(leaf, i), key1, key2) != 0) {
			return false;
		}
		leaf = changing(leafId);
		for (int j = i; j < n - 1; j++) {
			setEntry(leaf, j, k1(leaf, j + 1), k2(leaf, j + 1), payload(leaf, j + 1));
		}
		setCount(leaf, n - 1);
		segments.touched(leafId);
		return true;
	}

	/**
	 * Every entry in key order, flattened as {@code k1, k2, value} triples. For verification, which has to compare the
	 * tree with the records as of one instant and so takes the whole of it in one walk rather than leaf by leaf.
	 */
	public long[] entries(long rootId) {
		long[] flat = new long[0];
		int used = 0;
		long leafId = findLeaf(rootId, 0L, 0L);
		while (!ElementId.isNull(leafId)) {
			MemorySegment leaf = segments.resolve(leafId);
			int n = count(leaf);
			if (n < 0 || n > fanout) {
				throw new io.metaloom.graph.core.api.PageCorruptException("Index page " + ElementId.toString(leafId)
					+ " declares " + n + " entries, outside 0.." + fanout);
			}
			if (used + 3 * n > flat.length) {
				flat = java.util.Arrays.copyOf(flat, Math.max(2 * flat.length, used + 3 * n));
			}
			for (int i = 0; i < n; i++) {
				flat[used++] = k1(leaf, i);
				flat[used++] = k2(leaf, i);
				flat[used++] = payload(leaf, i);
			}
			leafId = leaf.get(Layouts.LONG, OFF_NEXT_LEAF);
		}
		return used == flat.length ? flat : java.util.Arrays.copyOf(flat, used);
	}

	/** Total live entries. Diagnostic; walks every leaf. */
	public long size(long rootId) {
		long leafId = findLeaf(rootId, 0L, 0L);
		long total = 0;
		while (!ElementId.isNull(leafId)) {
			MemorySegment leaf = segments.resolve(leafId);
			total += count(leaf);
			leafId = leaf.get(Layouts.LONG, OFF_NEXT_LEAF);
		}
		return total;
	}

	/**
	 * Every page of the tree, root first. The caller must collect the ids before freeing any of them: the walk reads
	 * each node to find its children.
	 */
	public List<Long> pages(long rootId) {
		List<Long> result = new ArrayList<>();
		java.util.Deque<Long> pending = new java.util.ArrayDeque<>();
		pending.push(rootId);
		while (!pending.isEmpty()) {
			long id = pending.pop();
			result.add(id);
			MemorySegment node = segments.resolve(id);
			if (isLeaf(node)) {
				continue;
			}
			pending.push(node.get(Layouts.LONG, OFF_LEFTMOST));
			for (int i = count(node) - 1; i >= 0; i--) {
				pending.push(payload(node, i));
			}
		}
		return result;
	}

	/** Depth of the tree, for tests that assert the fan-out actually keeps lookups shallow. */
	public int depth(long rootId) {
		int depth = 1;
		long current = rootId;
		while (true) {
			MemorySegment node = segments.resolve(current);
			if (isLeaf(node)) {
				return depth;
			}
			depth++;
			current = node.get(Layouts.LONG, OFF_LEFTMOST);
		}
	}
}
