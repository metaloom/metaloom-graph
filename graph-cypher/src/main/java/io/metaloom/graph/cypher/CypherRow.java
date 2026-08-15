package io.metaloom.graph.cypher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;

/** One result row, addressable by column name or position. */
public final class CypherRow {

	private final List<String> columns;
	private final List<Object> values;

	CypherRow(List<String> columns, List<Object> values) {
		this.columns = columns;
		this.values = values;
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

	public CypherNode node(String column) {
		return as(column, CypherNode.class);
	}

	public CypherRelationship relationship(String column) {
		return as(column, CypherRelationship.class);
	}

	public CypherPath path(String column) {
		return as(column, CypherPath.class);
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
