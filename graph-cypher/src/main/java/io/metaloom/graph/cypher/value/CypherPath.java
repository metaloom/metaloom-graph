package io.metaloom.graph.cypher.value;

import java.util.List;

/**
 * An alternating sequence of nodes and relationships, as bound by {@code p = (a)-[*1..3]->(b)}.
 *
 * @param nodes
 *            always one more than {@code relationships}
 */
public record CypherPath(List<CypherNode> nodes, List<CypherRelationship> relationships) {

	/** Cypher's {@code length(p)}: hops, not nodes. */
	public int length() {
		return relationships.size();
	}

	public CypherNode start() {
		return nodes.get(0);
	}

	public CypherNode end() {
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
