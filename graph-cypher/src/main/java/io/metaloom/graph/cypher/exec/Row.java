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
