package io.metaloom.graph.protocol;

import java.util.List;
import java.util.Map;

/**
 * A node as it arrives over the wire.
 *
 * <p>
 * The same shape as {@code CypherNode}, and declared separately on purpose: this module must not depend on the query
 * engine, because the query engine depends on the storage engine, which depends on the native layer. A client that had
 * to carry all of that to read a row would be a strange thing to ship. The server converts at its edge, which costs one
 * small record per element and buys a client jar with neither FFM nor a store in it.
 * </p>
 */
public record WireNode(long id, List<String> labels, Map<String, Object> properties) {

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
