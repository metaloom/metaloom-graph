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

	/**
	 * The same expansion, pushed a row at a time and stoppable.
	 *
	 * <p>
	 * The difference that matters is not the shape of the callback: it is that the <em>seed</em> is consumed one node
	 * at a time rather than collected first. {@link #match(List, Row)} calls {@link #seed} for the leading pattern,
	 * which for an unindexed {@code MATCH (n)} builds one row per live node before a single relationship is expanded —
	 * so a query wanting ten rows out of ten million nodes pays for ten million either way. Here the scan stops as soon
	 * as the consumer says so, and it says so as soon as a {@code LIMIT} downstream is satisfied.
	 * </p>
	 *
	 * <p>
	 * Everything after the seed is the list-based code unchanged, per seed row. That is deliberate: the expansion
	 * carries per-row relationship-uniqueness sets and path accumulators in lockstep, and a second implementation of
	 * that is a second place for a scoping bug to live.
	 * </p>
	 *
	 * @return false if the consumer stopped the walk
	 */
	public boolean match(List<Ast.PatternPart> pattern, Row input, RowConsumer consumer) {
		return matchFrom(pattern, 0, input, consumer);
	}

	private boolean matchFrom(List<Ast.PatternPart> pattern, int index, Row row, RowConsumer consumer) {
		if (index == pattern.size()) {
			return consumer.accept(row);
		}
		Ast.PatternPart part = pattern.get(index);
		return seed(part.nodes().get(0), row, seeded -> {
			for (Row expanded : expand(part, List.of(seeded))) {
				if (!matchFrom(pattern, index + 1, expanded, consumer)) {
					return false;
				}
			}
			return true;
		});
	}

	private List<Row> matchPart(Ast.PatternPart part, Row input) {
		return expand(part, seed(part.nodes().get(0), input));
	}

	/** The relationship steps of one pattern part, from rows that already bind its leading node. */
	private List<Row> expand(Ast.PatternPart part, List<Row> seedRows) {
		List<Ast.NodePattern> nodes = part.nodes();
		List<Ast.RelPattern> relationships = part.relationships();

		List<Row> rows = seedRows;
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
	 * The same seeding, pushed one row at a time.
	 *
	 * <p>
	 * Same three access paths in the same order — a bound variable, then the index, then the scan — so the two forms
	 * cannot choose differently. Only the scan can actually stop early; a candidate list from an index has already been
	 * built by the time it is returned, and stopping the loop over it saves the reads but not the probe.
	 * </p>
	 *
	 * @return false if the consumer stopped the seeding
	 */
	private boolean seed(Ast.NodePattern pattern, Row input, RowConsumer consumer) {
		String name = anchorName(pattern);
		if (input.has(name) && input.get(name) instanceof CypherNode bound) {
			return !matches(pattern, bound, input) || consumer.accept(input);
		}

		List<Long> candidates = candidates(pattern, input);
		if (candidates != null) {
			for (long id : candidates) {
				if (!graph.exists(id)) {
					continue;
				}
				CypherNode node = graph.node(id);
				if (matches(pattern, node, input) && !consumer.accept(input.with(name, node))) {
					return false;
				}
			}
			return true;
		}

		return graph.forEachNodeWhile(id -> {
			CypherNode node = graph.node(id);
			return !matches(pattern, node, input) || consumer.accept(input.with(name, node));
		});
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
