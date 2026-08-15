package io.metaloom.graph.protocol;

import java.util.Map;

/**
 * A relationship as it arrives over the wire.
 *
 * @param id
 *            null for a lightweight edge, which has no record and therefore no id and no properties. It travels as a
 *            flags bit with the id field omitted rather than as a sentinel, because zero is a legitimate value
 *            elsewhere in this codebase and because a flags byte is the extension point a sentinel is not.
 */
public record WireRelationship(Long id, String type, long startId, long endId, Map<String, Object> properties) {

	public Object property(String key) {
		return properties.get(key);
	}

	/** False for a lightweight edge, which has no record and therefore no properties of its own. */
	public boolean isMaterialised() {
		return id != null;
	}

	@Override
	public String toString() {
		return "[:" + type + (properties.isEmpty() ? "" : " " + properties) + "]";
	}
}
