package io.metaloom.graph.protocol;

import java.util.List;

/**
 * A path: n+1 nodes and n relationships, so only the hop count is encoded.
 *
 * <p>
 * <b>The shape is checked when one is made.</b> Only the hop count travels, so a path that was not n+1 nodes and n
 * relationships used to encode into bytes the decoder read as something else entirely — it takes hops+1 nodes and runs
 * on into whatever value came next. A wrong answer two values away from its cause; refusing it here puts the failure
 * where the mistake is.
 * </p>
 */
public record WirePath(List<WireNode> nodes, List<WireRelationship> relationships) {

	public WirePath {
		if (nodes == null || relationships == null || nodes.size() != relationships.size() + 1) {
			throw new IllegalArgumentException("A path of " + (relationships == null ? "?" : relationships.size())
				+ " hops has one node more than it has relationships, not " + (nodes == null ? "none" : nodes.size()));
		}
	}

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
