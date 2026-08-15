package io.metaloom.graph.cypher.exec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * Turns one MATCH pattern into rows, by picking a starting point and expanding along the pattern.
 *
 * <h2>Choosing where to start</h2>
 *
 * <p>
 * Selectivity order, best first: a node already bound by an earlier clause (zero cost — it is a value in the row), an
 * indexed property equality, a label scan, and finally a full node scan. That ordering is what makes
 * {@code MATCH (a:Person {email:$e})-[:KNOWS]->(b)} a single index probe plus one expansion rather than a scan of
 * every node in the store.
 * </p>
 *
 * <h2>Variable-length expansion</h2>
 *
 * <p>
 * <b>{@code GraphStore.traverse} is deliberately not used.</b> It is a breadth-first walk with <em>node</em>
 * uniqueness that returns a flat list of edges — the right shape for "what can I reach", and the wrong shape for
 * Cypher, which wants one row per distinct <em>path</em> under <em>relationship</em> uniqueness. The two differ
 * whenever a graph has more than one route between the same pair of nodes: traverse reports the node once, Cypher
 * reports every route. {@code VarLengthPathTest} pins the difference down against a graph where it shows.
 * </p>
 */
public final class PatternMatcher {

	private final Evaluator evaluator;
	private final GraphAccess graph;

	public PatternMatcher(Evaluator evaluator) {
		this.evaluator = evaluator;
		this.graph = evaluator.graph();
	}

	/** Expands one incoming row into every row that satisfies the pattern. */
	public List<Row> match(List<Ast.PatternPart> pattern, Row input) {
		List<Row> rows = List.of(input);
		for (Ast.PatternPart part : pattern) {
			List<Row> next = new ArrayList<>();
			for (Row row : rows) {
				next.addAll(matchPart(part, row));
			}
			rows = next;
			if (rows.isEmpty()) {
				// Nothing satisfies the pattern so far; the remaining parts cannot rescue it.
				return rows;
			}
		}
		return rows;
	}

	private List<Row> matchPart(Ast.PatternPart part, Row input) {
		List<Ast.NodePattern> nodes = part.nodes();
		List<Ast.RelPattern> relationships = part.relationships();

		List<Row> rows = seed(nodes.get(0), input);
		// Relationships bound so far, per row, so that a pattern cannot traverse the same edge twice.
		List<Set<Object>> usedPerRow = new ArrayList<>();
		List<List<CypherNode>> pathNodes = new ArrayList<>();
		List<List<CypherRelationship>> pathRels = new ArrayList<>();
		for (Row row : rows) {
			usedPerRow.add(new HashSet<>());
			List<CypherNode> starting = new ArrayList<>();
			starting.add((CypherNode) row.get(anchorName(nodes.get(0))));
			pathNodes.add(starting);
			pathRels.add(new ArrayList<>());
		}

		for (int step = 0; step < relationships.size(); step++) {
			Ast.RelPattern relationship = relationships.get(step);
			Ast.NodePattern target = nodes.get(step + 1);
			List<Row> nextRows = new ArrayList<>();
			List<Set<Object>> nextUsed = new ArrayList<>();
			List<List<CypherNode>> nextPathNodes = new ArrayList<>();
			List<List<CypherRelationship>> nextPathRels = new ArrayList<>();

			for (int i = 0; i < rows.size(); i++) {
				Row row = rows.get(i);
				CypherNode from = (CypherNode) row.get(anchorName(nodes.get(step)));
				List<Step> steps = relationship.range() == null
					? singleHop(from, relationship, usedPerRow.get(i))
					: variableHops(from, relationship, usedPerRow.get(i));

				for (Step candidate : steps) {
					CypherNode reached = graph.node(candidate.endId());
					if (!matches(target, reached, row)) {
						continue;
					}
					Row bound = row;
					// The relationship binds before the node it leads to, so that `RETURN *` lists variables in the
					// order the pattern names them: (a)-[r]->(b) gives a, r, b.
					if (relationship.variable() != null) {
						bound = bound.with(relationship.variable(), relationship.range() == null
							? candidate.relationships().get(0)
							: new ArrayList<Object>(candidate.relationships()));
					}
					// A node pattern that reuses a bound variable is a join, not a new binding.
					String targetName = anchorName(target);
					if (row.has(targetName) && row.get(targetName) instanceof CypherNode already) {
						if (already.id() != reached.id()) {
							continue;
						}
					} else {
						bound = bound.with(targetName, reached);
					}
					if (!propertiesMatch(relationship.properties(), candidate.relationships(), bound)) {
						continue;
					}

					Set<Object> used = new HashSet<>(usedPerRow.get(i));
					used.addAll(candidate.keys());
					List<CypherNode> walkNodes = new ArrayList<>(pathNodes.get(i));
					walkNodes.addAll(candidate.intermediateNodes());
					walkNodes.add(reached);
					List<CypherRelationship> walkRels = new ArrayList<>(pathRels.get(i));
					walkRels.addAll(candidate.relationships());

					nextRows.add(bound);
					nextUsed.add(used);
					nextPathNodes.add(walkNodes);
					nextPathRels.add(walkRels);
				}
			}
			rows = nextRows;
			usedPerRow = nextUsed;
			pathNodes = nextPathNodes;
			pathRels = nextPathRels;
			if (rows.isEmpty()) {
				return rows;
			}
		}

		if (part.pathVariable() != null) {
			List<Row> bound = new ArrayList<>(rows.size());
			for (int i = 0; i < rows.size(); i++) {
				bound.add(rows.get(i).with(part.pathVariable(),
					new CypherPath(List.copyOf(pathNodes.get(i)), List.copyOf(pathRels.get(i)))));
			}
			return bound;
		}
		return rows;
	}

	// ---------------------------------------------------------------- seeding

	/** Every row the first node pattern can produce, choosing the cheapest access path available. */
	private List<Row> seed(Ast.NodePattern pattern, Row input) {
		String name = anchorName(pattern);
		if (input.has(name) && input.get(name) instanceof CypherNode bound) {
			// Already bound by an earlier clause: verify rather than scan.
			return matches(pattern, bound, input) ? List.of(input) : List.of();
		}

		List<Row> rows = new ArrayList<>();
		List<Long> candidates = candidates(pattern, input);
		if (candidates != null) {
			for (long id : candidates) {
				if (!graph.exists(id)) {
					continue;
				}
				CypherNode node = graph.node(id);
				if (matches(pattern, node, input)) {
					rows.add(input.with(name, node));
				}
			}
			return rows;
		}

		graph.forEachNode(id -> {
			CypherNode node = graph.node(id);
			if (matches(pattern, node, input)) {
				rows.add(input.with(name, node));
			}
		});
		return rows;
	}

	/**
	 * A candidate id list from an index, or null when only a full scan will do.
	 *
	 * <p>
	 * An indexed property beats a label because it is far more selective, and the pattern's inline map is exactly the
	 * equality predicate the property index answers. Only a literal-valued entry qualifies: one whose value depends on
	 * the row cannot be probed before the row exists.
	 * </p>
	 */
	private List<Long> candidates(Ast.NodePattern pattern, Row row) {
		if (pattern.properties() != null && !pattern.properties().isEmpty()) {
			for (Map.Entry<String, Ast.Expr> entry : pattern.properties().entrySet()) {
				Object value;
				try {
					value = evaluator.evaluate(entry.getValue(), row);
				} catch (RuntimeException e) {
					continue;
				}
				if (value != null) {
					return graph.nodesByProperty(entry.getKey(), value);
				}
			}
		}
		if (!pattern.labels().isEmpty()) {
			// The rarest label would be the better choice; the store does not expose per-label counts, so the first
			// is used and the rest are verified. Worth revisiting when it does.
			return graph.nodesByLabel(pattern.labels().get(0));
		}
		return null;
	}

	private boolean matches(Ast.NodePattern pattern, CypherNode node, Row row) {
		for (String label : pattern.labels()) {
			if (!node.hasLabel(label)) {
				return false;
			}
		}
		if (pattern.properties() != null) {
			for (Map.Entry<String, Ast.Expr> entry : pattern.properties().entrySet()) {
				Object expected = evaluator.evaluate(entry.getValue(), row);
				Object actual = node.property(entry.getKey());
				// An inline map entry is an equality predicate, so a null on either side fails the match rather
				// than propagating: `{name: null}` matches nothing, as in Cypher.
				if (expected == null || actual == null || !Values.equalKnown(expected, actual)) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean propertiesMatch(Map<String, Ast.Expr> expected, List<CypherRelationship> relationships, Row row) {
		if (expected == null || expected.isEmpty()) {
			return true;
		}
		for (CypherRelationship relationship : relationships) {
			for (Map.Entry<String, Ast.Expr> entry : expected.entrySet()) {
				Object want = evaluator.evaluate(entry.getValue(), row);
				Object actual = relationship.property(entry.getKey());
				if (want == null || actual == null || !Values.equalKnown(want, actual)) {
					return false;
				}
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- expansion

	/** One traversal step, or a chain of them for a variable-length pattern. */
	private record Step(long endId, List<CypherRelationship> relationships, List<CypherNode> intermediateNodes,
		List<Object> keys) {
	}

	private List<Step> singleHop(CypherNode from, Ast.RelPattern pattern, Set<Object> used) {
		List<Step> steps = new ArrayList<>();
		for (GraphAccess.Expansion expansion : graph.expand(from.id(), direction(pattern), types(pattern))) {
			Object key = key(expansion.relationship());
			if (used.contains(key)) {
				continue;
			}
			steps.add(new Step(expansion.neighbourId(), List.of(expansion.relationship()), List.of(), List.of(key)));
		}
		return steps;
	}

	/**
	 * Depth-first enumeration of every path within the range, under relationship uniqueness.
	 *
	 * <p>
	 * Each distinct path is a distinct result, which is what separates this from
	 * {@link io.metaloom.graph.core.api.GraphStore#traverse}. A node may therefore be reached several times, once per
	 * route, and the same relationship may never appear twice in one path — the rule that keeps
	 * {@code -[:KNOWS*]-} from looping forever on a cycle.
	 * </p>
	 */
	private List<Step> variableHops(CypherNode from, Ast.RelPattern pattern, Set<Object> used) {
		List<Step> results = new ArrayList<>();
		Ast.Range range = pattern.range();
		if (range.min() == 0) {
			// A zero-length match binds the start node to the end with no relationship at all.
			results.add(new Step(from.id(), List.of(), List.of(), List.of()));
		}
		walk(from.id(), pattern, range, 1, new ArrayList<>(), new ArrayList<>(), new LinkedHashSet<>(used), results);
		return results;
	}

	private void walk(long fromId, Ast.RelPattern pattern, Ast.Range range, int depth,
		List<CypherRelationship> chain, List<CypherNode> visitedNodes, Set<Object> used, List<Step> results) {
		if (depth > range.max()) {
			return;
		}
		for (GraphAccess.Expansion expansion : graph.expand(fromId, direction(pattern), types(pattern))) {
			Object key = key(expansion.relationship());
			if (used.contains(key)) {
				continue;
			}
			chain.add(expansion.relationship());
			used.add(key);

			if (depth >= range.min()) {
				results.add(new Step(expansion.neighbourId(), List.copyOf(chain), List.copyOf(visitedNodes),
					new ArrayList<>(used)));
			}
			if (depth < range.max()) {
				visitedNodes.add(graph.node(expansion.neighbourId()));
				walk(expansion.neighbourId(), pattern, range, depth + 1, chain, visitedNodes, used, results);
				visitedNodes.remove(visitedNodes.size() - 1);
			}

			used.remove(key);
			chain.remove(chain.size() - 1);
		}
	}

	/**
	 * Identity for the uniqueness rule.
	 *
	 * <p>
	 * A materialised relationship has an id. A lightweight edge has none, so it is identified by its endpoints,
	 * direction-normalised, and its type — which is exactly what makes it unique in the store, since the adjacency
	 * entry <em>is</em> the edge.
	 * </p>
	 */
	private static Object key(CypherRelationship relationship) {
		if (relationship.id() != null) {
			return relationship.id();
		}
		return relationship.startId() + "/" + relationship.type() + "/" + relationship.endId();
	}

	private static Direction direction(Ast.RelPattern pattern) {
		return switch (pattern.direction()) {
			case RIGHT -> Direction.OUT;
			case LEFT -> Direction.IN;
			case BOTH -> Direction.BOTH;
		};
	}

	private static Set<String> types(Ast.RelPattern pattern) {
		return pattern.types().isEmpty() ? null : new java.util.LinkedHashSet<>(pattern.types());
	}

	/**
	 * The row key for a node pattern.
	 * <p>
	 * An anonymous node still needs a binding so the expansion can find where it is, so it gets a name no query could
	 * write. The projection never sees these because {@code RETURN *} filters them out.
	 */
	private static String anchorName(Ast.NodePattern pattern) {
		return pattern.variable() != null ? pattern.variable()
			: "  anon@" + System.identityHashCode(pattern);
	}

	/** True for a binding this matcher invented rather than one the query named. */
	public static boolean isAnonymous(String name) {
		return name.startsWith("  anon@");
	}
}
