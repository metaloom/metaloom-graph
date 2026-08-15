package io.metaloom.graph.cypher.value;

import java.util.Map;

/**
 * A relationship as a query sees it.
 *
 * <p>
 * <b>{@link #id()} is null for a lightweight edge</b>, and that is a property of the storage engine rather than a gap
 * here: an edge created with {@code createEdge} costs 16 bytes and has no record, therefore no id and no properties.
 * Everything else — {@code type(r)}, {@code startNode(r)}, {@code endNode(r)} — works on both kinds. A query that binds
 * {@code r} over lightweight edges gets relationships whose {@code id} is null and whose {@code properties} are empty,
 * which is honest; the alternative of inventing a synthetic id would produce something no other call could resolve.
 * </p>
 *
 * @param id
 *            null for a lightweight edge
 */
public record CypherRelationship(Long id, String type, long startId, long endId, Map<String, Object> properties) {

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
