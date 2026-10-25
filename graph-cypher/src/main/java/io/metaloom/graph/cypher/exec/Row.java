package io.metaloom.graph.cypher.exec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One intermediate row: variable name to value.
 *
 * <p>
 * A map rather than a slotted array. The slotted form is what a mature planner uses and is measurably faster, but it
 * needs the planner to assign every variable a slot up front and to keep those assignments consistent across WITH
 * boundaries — which is a source of subtle scoping bugs that a map simply does not have. Correctness first; the shape
 * of this class is where to look if the executor ever needs to be fast.
 * </p>
 */
public final class Row {

	private final Map<String, Object> values;

	public Row() {
		this.values = new LinkedHashMap<>();
	}

	private Row(Map<String, Object> values) {
		this.values = values;
	}

	public Object get(String name) {
		return values.get(name);
	}

	public boolean has(String name) {
		return values.containsKey(name);
	}

	/** A copy with one more binding. Rows are treated as immutable so an operator can fan one row into several. */
	public Row with(String name, Object value) {
		Map<String, Object> copy = new LinkedHashMap<>(values);
		copy.put(name, value);
		return new Row(copy);
	}

	/**
	 * An empty row sized for {@code names} bindings, to be filled with {@link #put} before anything else sees it.
	 *
	 * <p>
	 * A projection of k items built with {@link #with} copies the map k times, so building one row was quadratic in its
	 * width and allocated k maps to keep one. These two and {@code put} are the builder form: package-private, and
	 * only ever applied to a row nobody else holds yet, so the immutability every operator relies on is unchanged.
	 * </p>
	 */
	static Row withCapacity(int names) {
		return new Row(new LinkedHashMap<>(capacity(names)));
	}

	/** A private copy of {@code source} with room for {@code extra} more bindings, to be filled with {@link #put}. */
	static Row copyOf(Row source, int extra) {
		Map<String, Object> copy = new LinkedHashMap<>(capacity(source.values.size() + extra));
		copy.putAll(source.values);
		return new Row(copy);
	}

	/** Binds in place. Only for a row from {@link #withCapacity} or {@link #copyOf} that has not been shared yet. */
	void put(String name, Object value) {
		values.put(name, value);
	}

	private static int capacity(int entries) {
		return (int) Math.ceil(entries / 0.75) + 1;
	}

	public Row merge(Row other) {
		Map<String, Object> copy = new LinkedHashMap<>(values);
		copy.putAll(other.values);
		return new Row(copy);
	}

	/** A copy holding only these names, which is what a projection leaves in scope. */
	public Row only(java.util.Collection<String> names) {
		Map<String, Object> copy = new LinkedHashMap<>();
		for (String name : names) {
			if (values.containsKey(name)) {
				copy.put(name, values.get(name));
			}
		}
		return new Row(copy);
	}

	public Map<String, Object> asMap() {
		return values;
	}

	public java.util.Set<String> names() {
		return values.keySet();
	}

	@Override
	public String toString() {
		return values.toString();
	}
}
