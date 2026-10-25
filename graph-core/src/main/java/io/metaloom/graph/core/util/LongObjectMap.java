package io.metaloom.graph.core.util;

import java.util.Arrays;

/**
 * An insertion-ordered map from primitive {@code long} keys to objects, for the per-transaction bookkeeping on the
 * write path.
 *
 * <p>
 * It replaces {@code LinkedHashMap<Long, V>} in the places that are consulted on every record touched inside a
 * transaction — the overlay lookup, the metadata journal, a view's reconstructed pages. Those maps were measured as
 * the largest single cost of a bulk load: a {@code Long} boxed per lookup (half of everything the load allocated), a
 * chained node per entry, and a pointer chase per probe. Here a lookup is a multiply, an index into an {@code int}
 * table and one {@code long} comparison, and nothing is allocated.
 * </p>
 *
 * <p>
 * Entries live in two dense arrays in the order they were first put, which is the iteration order — the same order a
 * {@code LinkedHashMap} gives, and the commit relies on it to log pages in the order they were first written.
 * Replacing the value of an existing key keeps its position, as {@code LinkedHashMap.put} does. There is no removal:
 * every map this serves is discarded or cleared whole. Not thread safe; each instance belongs to one transaction.
 * </p>
 *
 * @param <V>
 *            the value type
 */
public final class LongObjectMap<V> {

	private static final int INITIAL_TABLE = 16;

	/** Open addressing, linear probing. Each cell holds an entry index plus one, so zero means empty. */
	private int[] table;
	private long[] keys;
	private Object[] values;
	private int size;

	public LongObjectMap() {
		table = new int[INITIAL_TABLE];
		keys = new long[INITIAL_TABLE / 2];
		values = new Object[INITIAL_TABLE / 2];
	}

	private static int hash(long key) {
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32));
	}

	/** The value for a key, or null. */
	@SuppressWarnings("unchecked")
	public V get(long key) {
		int[] t = table;
		int mask = t.length - 1;
		for (int cell = hash(key) & mask;; cell = (cell + 1) & mask) {
			int index = t[cell];
			if (index == 0) {
				return null;
			}
			if (keys[index - 1] == key) {
				return (V) values[index - 1];
			}
		}
	}

	/**
	 * Associates a value with a key. An existing key keeps its place in the iteration order.
	 *
	 * @return the previous value, or null
	 */
	@SuppressWarnings("unchecked")
	public V put(long key, V value) {
		int[] t = table;
		int mask = t.length - 1;
		int cell = hash(key) & mask;
		for (;; cell = (cell + 1) & mask) {
			int index = t[cell];
			if (index == 0) {
				break;
			}
			if (keys[index - 1] == key) {
				V previous = (V) values[index - 1];
				values[index - 1] = value;
				return previous;
			}
		}
		if (size == keys.length) {
			keys = Arrays.copyOf(keys, size * 2);
			values = Arrays.copyOf(values, size * 2);
		}
		keys[size] = key;
		values[size] = value;
		size++;
		t[cell] = size;
		// Kept at most half full, so a miss ends within a probe or two.
		if (size * 2 > t.length) {
			rehash(t.length * 2);
		}
		return null;
	}

	private void rehash(int capacity) {
		int[] t = new int[capacity];
		int mask = capacity - 1;
		for (int i = 0; i < size; i++) {
			int cell = hash(keys[i]) & mask;
			while (t[cell] != 0) {
				cell = (cell + 1) & mask;
			}
			t[cell] = i + 1;
		}
		table = t;
	}

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	/** The key of the {@code i}-th entry in insertion order. */
	public long keyAt(int i) {
		if (i >= size) {
			throw new IndexOutOfBoundsException(i);
		}
		return keys[i];
	}

	/** The value of the {@code i}-th entry in insertion order. */
	@SuppressWarnings("unchecked")
	public V valueAt(int i) {
		if (i >= size) {
			throw new IndexOutOfBoundsException(i);
		}
		return (V) values[i];
	}

	public void clear() {
		if (table.length > INITIAL_TABLE * 64) {
			// A bulk transaction can grow one of these to hundreds of thousands of entries; do not keep that around.
			table = new int[INITIAL_TABLE];
			keys = new long[INITIAL_TABLE / 2];
			values = new Object[INITIAL_TABLE / 2];
		} else {
			Arrays.fill(table, 0);
			Arrays.fill(values, 0, size, null);
		}
		size = 0;
	}
}
