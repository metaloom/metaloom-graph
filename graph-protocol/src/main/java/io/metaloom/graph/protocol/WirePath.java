package io.metaloom.graph.protocol;

import java.util.List;

/** A path: n+1 nodes and n relationships, so only the hop count is encoded. */
public record WirePath(List<WireNode> nodes, List<WireRelationship> relationships) {

	/** Hops, not nodes — the same meaning Cypher's {@code length(p)} has. */
	public int length() {
		return relationships.size();
	}

	public WireNode start() {
		return nodes.get(0);
	}

	public WireNode end() {
		return nodes.get(nodes.size() - 1);
	}

	@Override
	public String toString() {
		StringBuilder out = new StringBuilder(nodes.get(0).toString());
		for (int i = 0; i < relationships.size(); i++) {
			out.append('-').append(relationships.get(i)).append("->").append(nodes.get(i + 1));
		}
		return out.toString();
	}
}
