package io.metaloom.graph.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.protocol.WireNode;
import io.metaloom.graph.protocol.WirePath;
import io.metaloom.graph.protocol.WireRelationship;

/**
 * One result row, addressable by column name or position.
 *
 * <p>
 * Deliberately the same shape as {@code CypherRow} on the embedded side, so that moving a piece of code from in-process
 * to over-the-wire is a change of import and not a change of style. What differs is the element types — {@link WireNode}
 * rather than {@code CypherNode} — because the client does not carry the query engine. The fields are identical.
 * </p>
 */
public final class Row {

	private final List<String> columns;
	private final List<Object> values;

	Row(List<String> columns, List<Object> values) {
		this.columns = columns;
		this.values = values;
	}

	public List<String> columns() {
		return columns;
	}

	public Object get(String column) {
		int index = columns.indexOf(column);
		if (index < 0) {
			throw new IllegalArgumentException("No column '" + column + "'. Columns: " + columns);
		}
		return values.get(index);
	}

	public Object get(int index) {
		return values.get(index);
	}

	public WireNode node(String column) {
		return as(column, WireNode.class);
	}

	public WireRelationship relationship(String column) {
		return as(column, WireRelationship.class);
	}

	public WirePath path(String column) {
		return as(column, WirePath.class);
	}

	public String string(String column) {
		Object value = get(column);
		return value == null ? null : value.toString();
	}

	/** Null-safe, because a column holding null is ordinary rather than exceptional. */
	public Long longValue(String column) {
		Object value = get(column);
		return value == null ? null : ((Number) value).longValue();
	}

	public Double doubleValue(String column) {
		Object value = get(column);
		return value == null ? null : ((Number) value).doubleValue();
	}

	private <T> T as(String column, Class<T> type) {
		Object value = get(column);
		if (value == null) {
			return null;
		}
		if (!type.isInstance(value)) {
			throw new IllegalStateException("Column '" + column + "' holds a "
				+ value.getClass().getSimpleName() + ", not a " + type.getSimpleName());
		}
		return type.cast(value);
	}

	public Map<String, Object> asMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < columns.size(); i++) {
			out.put(columns.get(i), values.get(i));
		}
		return out;
	}

	public List<Object> values() {
		return values;
	}

	@Override
	public String toString() {
		return asMap().toString();
	}
}
