package io.metaloom.graph.cypher.value;

import java.util.List;
import java.util.Map;

/**
 * A node as a query sees it: an identity, its labels, and its properties.
 *
 * <p>
 * {@code RETURN n} must hand back a node rather than the {@code long} the storage layer uses, because Cypher then lets
 * the caller ask it for {@code n.name}, {@code labels(n)} and {@code id(n)}. The properties are read once when the node
 * is bound rather than lazily, so a row is a value and does not hold the store open behind it.
 * </p>
 */
public record CypherNode(long id, List<String> labels, Map<String, Object> properties) {

	public Object property(String key) {
		return properties.get(key);
	}

	public boolean hasLabel(String label) {
		return labels.contains(label);
	}

	@Override
	public String toString() {
		StringBuilder out = new StringBuilder("(");
		for (String label : labels) {
			out.append(':').append(label);
		}
		if (!properties.isEmpty()) {
			out.append(labels.isEmpty() ? "" : " ").append(properties);
		}
		return out.append(')').toString();
	}
}
