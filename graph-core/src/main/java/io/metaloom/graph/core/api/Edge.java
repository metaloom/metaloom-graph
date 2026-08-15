package io.metaloom.graph.core.api;

import io.metaloom.graph.core.id.ElementId;

/**
 * One traversal result: the neighbour reached, the relationship type, the direction, and the relationship id when the
 * edge is materialised.
 *
 * @param neighbourId
 *            the node at the other end
 * @param type
 *            the relationship type
 * @param direction
 *            OUT or IN, relative to the node being traversed from
 * @param relationshipId
 *            {@link ElementId#NULL} for a lightweight edge
 */
public record Edge(long neighbourId, String type, Direction direction, long relationshipId) {

	public boolean isMaterialised() {
		return !ElementId.isNull(relationshipId);
	}

	@Override
	public String toString() {
		return "(" + ElementId.toString(neighbourId) + ")"
			+ (direction == Direction.OUT ? "-[" : "<-[") + type
			+ (isMaterialised() ? " " + ElementId.toString(relationshipId) : "")
			+ (direction == Direction.OUT ? "]->" : "]-");
	}
}
