package io.metaloom.graph.cypher.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *
 * <h2>Shortest paths</h2>
 *
 * <p>
 * {@code shortestPath(…)} and {@code allShortestPaths(…)} are a different algorithm rather than a filter over the one
 * above — breadth-first, one expansion per node, described at {@link #search}. Keeping them apart is the point: the
 * depth-first enumeration is exponential in the bound by design, because every path <em>is</em> the answer there, and
 * running it to then discard all but the shortest would make a search that is linear in the graph quadratic in the
 * number of routes through it.
 * </p>
 */
public final class PatternMatcher {

	private final Evaluator evaluator;
	private final GraphAccess graph;

	public PatternMatcher(Evaluator evaluator) {
		this.evaluator = evaluator;
		this.graph = evaluator.graph();
	}

	/**
	 * A row part-way through a MATCH, with the relationships it has crossed so far.
	 *
	 * <p>
	 * Uniqueness is per MATCH rather than per comma-separated part of it, so the set travels from one part to the next:
	 * {@code (a)-[r1]->(b), (c)-[r2]->(d)} over a single edge is no rows, not one row binding that edge twice.
	 * </p>
	 */
	private record Bound(Row row, Set<Object> used) {
	}

	/** Expands one incoming row into every row that satisfies the pattern. */
	public List<Row> match(List<Ast.PatternPart> pattern, Row input) {
		return match(pattern, null, input);
	}

	/**
	 * The same, with the clause's WHERE available to choose a start point.
	 *
	 * <p>
	 * The WHERE is <em>not</em> applied here — the caller still filters on it. It is only read for top-level equalities
	 * on the leading node that can seed an index probe, exactly as an inline map does. See {@link #seedEqualities}.
	 * </p>
	 */
	public List<Row> match(List<Ast.PatternPart> pattern, Ast.Expr where, Row input) {
		List<Bound> rows = List.of(new Bound(input, Set.of()));
		for (int index = 0; index < pattern.size(); index++) {
			Ast.PatternPart part = pattern.get(index);
			Map<String, Ast.Expr> hints = index == 0 ? seedEqualities(part, where) : Map.of();
			List<Bound> next = new ArrayList<>();
			for (Bound bound : rows) {
				next.addAll(expand(part, seed(part.nodes().get(0), hints, bound.row()), bound.used(),
					index == pattern.size() - 1));
			}
			rows = next;
			if (rows.isEmpty()) {
				// Nothing satisfies the pattern so far; the remaining parts cannot rescue it.
				return List.of();
			}
		}
		List<Row> out = new ArrayList<>(rows.size());
		for (Bound bound : rows) {
			out.add(bound.row());
		}
		return out;
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
		return match(pattern, null, input, consumer);
	}

	/** The pushed form of {@link #match(List, Ast.Expr, Row)}. */
	public boolean match(List<Ast.PatternPart> pattern, Ast.Expr where, Row input, RowConsumer consumer) {
		return matchFrom(pattern, where, 0, new Bound(input, Set.of()), consumer);
	}

	private boolean matchFrom(List<Ast.PatternPart> pattern, Ast.Expr where, int index, Bound bound,
		RowConsumer consumer) {
		if (index == pattern.size()) {
			return consumer.accept(bound.row());
		}
		Ast.PatternPart part = pattern.get(index);
		Map<String, Ast.Expr> hints = index == 0 ? seedEqualities(part, where) : Map.of();
		return seed(part.nodes().get(0), hints, bound.row(), seeded -> {
			for (Bound expanded : expand(part, List.of(seeded), bound.used(), index == pattern.size() - 1)) {
				if (!matchFrom(pattern, where, index + 1, expanded, consumer)) {
					return false;
				}
			}
			return true;
		});
	}

	/**
	 * The relationship steps of one pattern part, from rows that already bind its leading node.
	 *
	 * @param crossed
	 *            the relationships earlier parts of the same MATCH crossed, which this part may not cross again
	 * @param lastPart
	 *            whether any part follows this one; if none does, nothing reads the crossed set this part returns
	 */
	private List<Bound> expand(Ast.PatternPart part, List<Row> seedRows, Set<Object> crossed, boolean lastPart) {
		if (part.selector() != Ast.PathSelector.ALL) {
			return shortest(part, seedRows, crossed);
		}
		List<Ast.NodePattern> nodes = part.nodes();
		List<Ast.RelPattern> relationships = part.relationships();

		List<Row> rows = seedRows;
		// The path is only assembled when the pattern names one. Copying both lists for every candidate of every step
		// was most of what an anonymous two-hop count allocated, to build paths nothing read.
		boolean tracking = part.pathVariable() != null;
		// Relationships bound so far, per row, so that a pattern cannot traverse the same edge twice.
		List<Set<Object>> usedPerRow = new ArrayList<>();
		List<List<CypherNode>> pathNodes = new ArrayList<>();
		List<List<CypherRelationship>> pathRels = new ArrayList<>();
		for (Row row : rows) {
			usedPerRow.add(crossed);
			if (tracking) {
				List<CypherNode> starting = new ArrayList<>();
				starting.add((CypherNode) row.get(anchorName(nodes.get(0))));
				pathNodes.add(starting);
				pathRels.add(new ArrayList<>());
			} else {
				pathNodes.add(null);
				pathRels.add(null);
			}
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
					: variableHops(from, relationship, usedPerRow.get(i), tracking);

				for (Step candidate : steps) {
					CypherNode reached = unread(target, tracking) ? new CypherNode(candidate.endId(), List.of(), Map.of())
						: graph.node(candidate.endId());
					if (!matches(target, reached, row)) {
						continue;
					}
					Object relationshipValue = null;
					if (relationship.variable() != null) {
						relationshipValue = relationship.range() == null
							? candidate.relationships().get(0)
							: new ArrayList<Object>(candidate.relationships());
						// Bound by an earlier clause, it is a join, exactly as a reused node variable is.
						if (row.has(relationship.variable())) {
							Object already = row.get(relationship.variable());
							if (already == null || !Values.equalKnown(already, relationshipValue)) {
								continue;
							}
							relationshipValue = null;
						}
					}
					// A node pattern that reuses a bound variable is a join, not a new binding.
					String targetName = anchorName(target);
					boolean bindTarget = !row.has(targetName);
					if (!bindTarget) {
						CypherNode already = boundNode(row, targetName);
						if (already == null || already.id() != reached.id()) {
							continue;
						}
					}
					// One copy of the row for both new bindings rather than one each. The relationship binds before the
					// node it leads to, so that `RETURN *` lists variables in the order the pattern names them:
					// (a)-[r]->(b) gives a, r, b.
					Row bound = row;
					if (relationshipValue != null || bindTarget) {
						bound = Row.copyOf(row, 2);
						if (relationshipValue != null) {
							bound.put(relationship.variable(), relationshipValue);
						}
						if (bindTarget) {
							bound.put(targetName, reached);
						}
					}
					if (!propertiesMatch(relationship.properties(), candidate.relationships(), bound)) {
						continue;
					}

					// What later steps and later parts may not cross again. After the last step of the last part
					// nothing reads it, so it is not copied there.
					Set<Object> used = usedPerRow.get(i);
					if (!lastPart || step + 1 < relationships.size()) {
						used = new HashSet<>(used);
						used.addAll(candidate.keys());
					}
					List<CypherNode> walkNodes = null;
					List<CypherRelationship> walkRels = null;
					if (tracking) {
						walkNodes = new ArrayList<>(pathNodes.get(i));
						walkNodes.addAll(candidate.intermediateNodes());
						// A zero-length step reaches the node the path already ends on, which must not be listed twice.
						if (!candidate.relationships().isEmpty()) {
							walkNodes.add(reached);
						}
						walkRels = new ArrayList<>(pathRels.get(i));
						walkRels.addAll(candidate.relationships());
					}

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
				return List.of();
			}
		}

		List<Bound> out = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			if (part.pathVariable() != null) {
				refusePathRebinding(row, part.pathVariable());
				row = row.with(part.pathVariable(),
					new CypherPath(List.copyOf(pathNodes.get(i)), List.copyOf(pathRels.get(i))));
			}
			out.add(new Bound(row, usedPerRow.get(i)));
		}
		return out;
	}

	/**
	 * Whether a reached node will never be read: anonymous, so nothing can name it; no labels or properties to check;
	 * and not part of a path being assembled. Such a node only has to say where the next step starts, which its id
	 * does, so its labels and properties are not fetched — for {@code (a)-->()-->(c)} that was one record and property
	 * block decoded per edge crossed, to bind a snapshot nothing could see.
	 */
	private static boolean unread(Ast.NodePattern pattern, boolean tracking) {
		return !tracking && pattern.variable() == null && pattern.labels().isEmpty()
			&& (pattern.properties() == null || pattern.properties().isEmpty());
	}

	// ---------------------------------------------------------------- shortest paths

	/**
	 * {@code shortestPath(…)} and {@code allShortestPaths(…)}, which replace the enumeration above with a search.
	 *
	 * <p>
	 * The shape is the same as {@link #expand}'s — one row per path, binding the relationship variable, the far node
	 * and the path variable — but nothing else about it is. Enumerating every path and keeping the shortest would be
	 * correct and unusable: the number of paths through a graph grows exponentially with the bound, which is precisely
	 * why {@code shortestPath} exists as its own construct rather than as {@code ORDER BY length(p) LIMIT 1}.
	 * </p>
	 *
	 * <p>
	 * <b>One row per pair of endpoints.</b> Both ends are patterns, not necessarily single nodes, so a search from one
	 * start may reach several nodes that satisfy the end pattern — each at its own distance, each contributing its own
	 * shortest path. That is openCypher's rule and it is why the search cannot stop at the first node it reaches.
	 * </p>
	 */
	private List<Bound> shortest(Ast.PatternPart part, List<Row> seedRows, Set<Object> used) {
		Ast.NodePattern startPattern = part.nodes().get(0);
		Ast.NodePattern endPattern = part.nodes().get(1);
		Ast.RelPattern relationship = part.relationships().get(0);
		boolean everyShortest = part.selector() == Ast.PathSelector.ALL_SHORTEST;
		String endName = anchorName(endPattern);

		List<Bound> out = new ArrayList<>();
		for (Row row : seedRows) {
			CypherNode from = (CypherNode) row.get(anchorName(startPattern));
			// An end already bound by an earlier clause narrows the search to one destination rather than filtering
			// afterwards, which for allShortestPaths is the difference between enumerating one node's routes and
			// every reachable node's. Bound to null, there is no destination and so no path.
			Long endId = null;
			if (row.has(endName)) {
				CypherNode already = boundNode(row, endName);
				if (already == null) {
					continue;
				}
				endId = already.id();
			}
			if (part.pathVariable() != null) {
				refusePathRebinding(row, part.pathVariable());
			}

			for (CypherPath path : search(from, endPattern, endId, relationship, row, everyShortest)) {
				Row bound = row;
				if (relationship.variable() != null) {
					bound = bound.with(relationship.variable(), relationship.range() == null
						? path.relationships().get(0)
						: new ArrayList<Object>(path.relationships()));
				}
				if (endId == null) {
					bound = bound.with(endName, path.end());
				}
				if (part.pathVariable() != null) {
					bound = bound.with(part.pathVariable(), path);
				}
				// The search itself ignores what earlier parts crossed — a route is shortest or it is not — but the
				// parts after it must not cross this one's relationships again.
				Set<Object> crossed = new HashSet<>(used);
				for (CypherRelationship step : path.relationships()) {
					crossed.add(key(step));
				}
				out.add(new Bound(bound, crossed));
			}
		}
		return out;
	}

	/**
	 * Breadth-first from the start node, recording how each node was first reached.
	 *
	 * <p>
	 * Breadth-first is what makes the answer shortest: a node's distance is fixed the moment it is first seen, so the
	 * routes reconstructed backwards from it are minimal by construction. It also makes the uniqueness rule free —
	 * distance strictly increases along any reconstructed route, so no node and therefore no relationship can appear in
	 * one twice, and the {@code used} bookkeeping the depth-first walk needs has nothing to do here.
	 * </p>
	 *
	 * <p>
	 * The cost is bounded by the graph rather than by the number of paths: every node is expanded at most once. What is
	 * <em>not</em> bounded is {@code allShortestPaths}' output, which is the number of minimal routes — that is the
	 * answer the caller asked for, so it is enumerated rather than capped, and noted in spec/CYPHER.md.
	 * </p>
	 *
	 * @param endId
	 *            the only destination worth reporting, or null when the end pattern is not already bound
	 */
	private List<CypherPath> search(CypherNode from, Ast.NodePattern endPattern, Long endId, Ast.RelPattern pattern,
		Row row, boolean everyShortest) {
		int min = pattern.range() == null ? 1 : pattern.range().min();
		int max = pattern.range() == null ? 1 : pattern.range().max();

		// Insertion-ordered so that the rows come out in discovery order rather than in whatever order a hash gives,
		// which is what keeps a result stable between runs.
		Map<Long, Integer> distance = new LinkedHashMap<>();
		Map<Long, List<Arrival>> arrivals = new HashMap<>();
		distance.put(from.id(), 0);

		List<Long> frontier = new ArrayList<>();
		frontier.add(from.id());
		for (int depth = 1; depth <= max && !frontier.isEmpty(); depth++) {
			List<Long> next = new ArrayList<>();
			for (long at : frontier) {
				for (GraphAccess.Expansion expansion : graph.expand(at, direction(pattern), types(pattern))) {
					// An inline map on the relationship restricts which edges may be crossed, so it is applied here
					// rather than to the finished path: an edge it excludes must not shorten the route.
					if (!propertiesMatch(pattern.properties(), List.of(expansion.relationship()), row)) {
						continue;
					}
					long to = expansion.neighbourId();
					Integer known = distance.get(to);
					if (known == null) {
						distance.put(to, depth);
						next.add(to);
					} else if (known != depth || !everyShortest) {
						// Already reached, by a route no longer than this one. A second arrival at the same depth is a
						// second shortest path, which only allShortestPaths wants; a shorter one wins outright.
						continue;
					}
					arrivals.computeIfAbsent(to, ignored -> new ArrayList<>())
						.add(new Arrival(at, to, expansion.relationship()));
				}
			}
			frontier = next;
		}

		List<CypherPath> paths = new ArrayList<>();
		Map<Long, List<List<Arrival>>> memo = new HashMap<>();
		for (Map.Entry<Long, Integer> reached : distance.entrySet()) {
			int hops = reached.getValue();
			if (hops < min || hops > max) {
				// Only a zero-hop entry — the start node itself — can be below the minimum, and it is a match when the
				// range allows a length of nothing.
				continue;
			}
			if (endId != null && reached.getKey().longValue() != endId.longValue()) {
				continue;
			}
			CypherNode node = graph.node(reached.getKey());
			if (!matches(endPattern, node, row)) {
				continue;
			}
			for (List<Arrival> route : routes(reached.getKey(), arrivals, memo)) {
				paths.add(materialise(from, route));
			}
		}
		return paths;
	}

	/** How one node was reached from another: the step, kept backwards so a route can be rebuilt from its end. */
	private record Arrival(long fromId, long toId, CypherRelationship relationship) {
	}

	/**
	 * Every minimal route to a node, rebuilt backwards through the arrivals.
	 *
	 * <p>
	 * Memoised across the destinations of one search rather than recomputed per destination: the routes to a node are
	 * shared by every route that passes through it, and without the memo a diamond-shaped graph recomputes its whole
	 * upper half once per branch. For {@code shortestPath} there is exactly one arrival per node and this is a walk
	 * back down a chain.
	 * </p>
	 */
	private List<List<Arrival>> routes(long to, Map<Long, List<Arrival>> arrivals,
		Map<Long, List<List<Arrival>>> memo) {
		List<List<Arrival>> known = memo.get(to);
		if (known != null) {
			return known;
		}
		List<List<Arrival>> out = new ArrayList<>();
		List<Arrival> reached = arrivals.get(to);
		if (reached == null) {
			// The start node, which was never arrived at: one route, of no steps at all.
			out.add(List.of());
		} else {
			for (Arrival arrival : reached) {
				for (List<Arrival> prefix : routes(arrival.fromId(), arrivals, memo)) {
					List<Arrival> route = new ArrayList<>(prefix);
					route.add(arrival);
					out.add(route);
				}
			}
		}
		memo.put(to, out);
		return out;
	}

	private CypherPath materialise(CypherNode from, List<Arrival> route) {
		List<CypherNode> nodes = new ArrayList<>(route.size() + 1);
		List<CypherRelationship> relationships = new ArrayList<>(route.size());
		nodes.add(from);
		for (Arrival arrival : route) {
			relationships.add(arrival.relationship());
			nodes.add(graph.node(arrival.toId()));
		}
		return new CypherPath(List.copyOf(nodes), List.copyOf(relationships));
	}

	// ---------------------------------------------------------------- seeding


	/**
	 * The node a pattern variable is already bound to, or null when it is bound to null.
	 *
	 * <p>
	 * Bound to null is not unbound. Treating the two alike made {@code UNWIND [null] AS n MATCH (n)} scan and rebind
	 * {@code n} to every node in the store, where a null can only ever match nothing — the rule that keeps a missing
	 * value from turning into all of them. A value that is not a node at all cannot be used as one.
	 * </p>
	 */
	private static CypherNode boundNode(Row row, String name) {
		Object value = row.get(name);
		if (value == null || value instanceof CypherNode) {
			return (CypherNode) value;
		}
		throw new CypherTypeException("'" + name + "' is bound to " + Values.typeName(value)
			+ ", so it cannot be used as a node in a pattern");
	}

	/** Every row the first node pattern can produce, choosing the cheapest access path available. */
	private List<Row> seed(Ast.NodePattern pattern, Map<String, Ast.Expr> hints, Row input) {
		String name = anchorName(pattern);
		if (input.has(name)) {
			// Already bound by an earlier clause: verify rather than scan.
			CypherNode bound = boundNode(input, name);
			return bound != null && matches(pattern, bound, input) ? List.of(input) : List.of();
		}

		List<Row> rows = new ArrayList<>();
		List<Long> candidates = candidates(pattern, hints, input);
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
	private boolean seed(Ast.NodePattern pattern, Map<String, Ast.Expr> hints, Row input, RowConsumer consumer) {
		String name = anchorName(pattern);
		if (input.has(name)) {
			CypherNode bound = boundNode(input, name);
			return bound == null || !matches(pattern, bound, input) || consumer.accept(input);
		}

		List<Long> candidates = candidates(pattern, hints, input);
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
	 * The equalities in a WHERE that may choose the leading node's start point, as key to value expression.
	 *
	 * <p>
	 * {@code MATCH (n:Person) WHERE n.email = $e} is the same predicate as {@code (n:Person {email: $e})}, and should
	 * cost the same. Only a top-level conjunct qualifies — under OR or NOT the row could pass without it — and only one
	 * whose other side cannot depend on the pattern being matched: a literal, a parameter, or a property of a variable
	 * this MATCH does not bind. The WHERE is still applied to every row afterwards, so a hint can narrow the candidates
	 * and can never decide the answer.
	 * </p>
	 */
	private static Map<String, Ast.Expr> seedEqualities(Ast.PatternPart part, Ast.Expr where) {
		String name = part.nodes().get(0).variable();
		if (where == null || name == null || part.selector() != Ast.PathSelector.ALL) {
			return Map.of();
		}
		Map<String, Ast.Expr> hints = new LinkedHashMap<>();
		collectEqualities(where, name, hints);
		return hints;
	}

	private static void collectEqualities(Ast.Expr expression, String name, Map<String, Ast.Expr> hints) {
		if (expression instanceof Ast.Binary binary && binary.op() == Ast.Op.AND) {
			collectEqualities(binary.left(), name, hints);
			collectEqualities(binary.right(), name, hints);
			return;
		}
		if (expression instanceof Ast.Comparison comparison && comparison.op() == Ast.Op.EQUAL) {
			hint(comparison.left(), comparison.right(), name, hints);
			hint(comparison.right(), comparison.left(), name, hints);
		}
	}

	private static void hint(Ast.Expr side, Ast.Expr other, String name, Map<String, Ast.Expr> hints) {
		if (side instanceof Ast.Property property && property.target() instanceof Ast.Variable variable
			&& variable.name().equals(name) && independentOf(other, name)) {
			hints.putIfAbsent(property.key(), other);
		}
	}

	/**
	 * Whether a value can be computed before the seed node exists. Deliberately narrow: anything else is left to the
	 * WHERE, which is correct whatever it contains.
	 */
	private static boolean independentOf(Ast.Expr expression, String name) {
		return switch (expression) {
			case Ast.Literal ignored -> true;
			case Ast.Parameter ignored -> true;
			// Evaluated against the incoming row; a variable this MATCH binds later is not in it, and the probe is then
			// skipped rather than guessed.
			case Ast.Property property -> property.target() instanceof Ast.Variable variable
				&& !variable.name().equals(name);
			default -> false;
		};
	}

	/**
	 * A candidate id list from an index, or null when only a full scan will do.
	 *
	 * <p>
	 * A <b>declared composite index</b> over one of the pattern's labels and one of its literal properties comes
	 * first, because it answers the whole of {@code (n:Person {email: ...})} in one probe rather than probing the
	 * property and then verifying the label per candidate. It is only ever preferred when it exists — an undeclared
	 * pair would fall back to exactly the property probe below, so asking first costs a catalog read and never a scan.
	 * </p>
	 *
	 * <p>
	 * Otherwise an indexed property beats a label because it is far more selective, and the pattern's inline map is
	 * exactly the equality predicate the property index answers. Only a literal-valued entry qualifies: one whose
	 * value depends on the row cannot be probed before the row exists. The WHERE equalities in {@code hints} are
	 * considered after the inline map, and only when their value is a scalar the index certainly holds.
	 * </p>
	 */
	private List<Long> candidates(Ast.NodePattern pattern, Map<String, Ast.Expr> hints, Row row) {
		String probeKey = null;
		Object probeValue = null;
		List<Map.Entry<String, Ast.Expr>> equalities = new ArrayList<>();
		if (pattern.properties() != null) {
			equalities.addAll(pattern.properties().entrySet());
		}
		int inline = equalities.size();
		equalities.addAll(hints.entrySet());
		for (int i = 0; i < equalities.size(); i++) {
			Map.Entry<String, Ast.Expr> entry = equalities.get(i);
			Object value;
			try {
				value = evaluator.evaluate(entry.getValue(), row);
			} catch (RuntimeException e) {
				continue;
			}
			if (value == null || !probeable(value) || i >= inline && value instanceof List) {
				continue;
			}
			for (String label : pattern.labels()) {
				if (graph.hasIndex(label, entry.getKey())) {
					return probe(label, entry.getKey(), value);
				}
			}
			if (probeKey == null) {
				probeKey = entry.getKey();
				probeValue = value;
			}
		}
		if (probeKey != null) {
			return probe(null, probeKey, probeValue);
		}
		if (!pattern.labels().isEmpty()) {
			// The rarest label would be the better choice; the store does not expose per-label counts, so the first
			// is used and the rest are verified. Worth revisiting when it does.
			return graph.nodesByLabel(pattern.labels().get(0));
		}
		return null;
	}

	/**
	 * Whether every value Cypher calls equal to this one is found by probing for it, and its integer or float twin.
	 *
	 * <p>
	 * A scalar is: {@link #probe} asks for the twin. A list of numbers is not, because each element has a twin and the
	 * list has one spelling per combination of them — {@code [1, 2]} equals {@code [1.0, 2.0]} and {@code [1, 2.0]},
	 * and the index keys each differently. Probing for the one written lost the rest, and MERGE then created the twin
	 * it could not find. Such a value takes the label scan instead, and the equality is checked per candidate. A list
	 * of strings and booleans has one spelling and is probed as before.
	 * </p>
	 */
	private static boolean probeable(Object value) {
		if (value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Double) {
			return true;
		}
		if (value instanceof List<?> list) {
			for (Object element : list) {
				if (!(element instanceof String || element instanceof Boolean)) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * One equality probe, under Cypher equality rather than the index's.
	 *
	 * <p>
	 * The index is keyed on the stored value's own type, so {@code 30} and {@code 30.0} hash apart there while
	 * {@code 30 = 30.0} is true here. An integral number is therefore probed in both spellings and the two answers
	 * joined; an empty answer is a real answer, with no scan behind it, so missing the other spelling would lose rows
	 * rather than cost time. A non-integral float has no integer twin, and nothing else has a twin at all.
	 * </p>
	 */
	private List<Long> probe(String label, String key, Object value) {
		List<Long> found = label == null ? graph.nodesByProperty(key, value)
			: graph.nodesByLabelAndProperty(label, key, value);
		Object twin = switch (value) {
			case Long l -> (double) l;
			case Double d when d == Math.rint(d) && d >= -0x1p63 && d < 0x1p63 -> (long) (double) d;
			default -> null;
		};
		if (twin == null) {
			return found;
		}
		List<Long> other = label == null ? graph.nodesByProperty(key, twin)
			: graph.nodesByLabelAndProperty(label, key, twin);
		if (other.isEmpty()) {
			return found;
		}
		Set<Long> union = new LinkedHashSet<>(found);
		union.addAll(other);
		return new ArrayList<>(union);
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
				if (!Values.isTrue(Values.equal(expected, actual))) {
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
				if (!Values.isTrue(Values.equal(want, actual))) {
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
	/**
	 * @param tracking
	 *            whether the nodes a path passes through are wanted, which is only when the pattern names the path.
	 *            Otherwise they are not read: every intermediate node of every enumerated path was fetched in full
	 */
	private List<Step> variableHops(CypherNode from, Ast.RelPattern pattern, Set<Object> used, boolean tracking) {
		List<Step> results = new ArrayList<>();
		Ast.Range range = pattern.range();
		if (range.min() == 0) {
			// A zero-length match binds the start node to the end with no relationship at all.
			results.add(new Step(from.id(), List.of(), List.of(), List.of()));
		}
		walk(from.id(), pattern, range, 1, new ArrayList<>(), tracking ? new ArrayList<>() : null,
			new LinkedHashSet<>(used), results);
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
				results.add(new Step(expansion.neighbourId(), List.copyOf(chain),
					visitedNodes == null ? List.of() : List.copyOf(visitedNodes), new ArrayList<>(used)));
			}
			if (depth < range.max()) {
				if (visitedNodes != null) {
					visitedNodes.add(graph.node(expansion.neighbourId()));
				}
				walk(expansion.neighbourId(), pattern, range, depth + 1, chain, visitedNodes, used, results);
				if (visitedNodes != null) {
					visitedNodes.remove(visitedNodes.size() - 1);
				}
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
		// A record rather than a formatted string: this runs for every lightweight edge every step considers, and
		// rendering two longs into a string per candidate was a visible share of an anonymous expansion.
		return new EdgeKey(relationship.startId(), relationship.type(), relationship.endId());
	}

	/** The identity of a lightweight edge, which has no id: its endpoints, in their stored direction, and its type. */
	private record EdgeKey(long startId, String type, long endId) {
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
	private String anchorName(Ast.NodePattern pattern) {
		if (pattern.variable() != null) {
			return pattern.variable();
		}
		// One name per pattern object for the life of the query, numbered rather than derived from
		// System.identityHashCode: two patterns whose hashes collided shared a name, and the second was then joined
		// to whatever the first had bound instead of matching freely. Cached, too — this runs once per row per step.
		String name = anonymous.get(pattern);
		if (name == null) {
			name = ANONYMOUS + anonymous.size();
			anonymous.put(pattern, name);
		}
		return name;
	}

	private static final String ANONYMOUS = "  anon@";

	private final Map<Ast.NodePattern, String> anonymous = new java.util.IdentityHashMap<>();

	/** A path variable names a new path; one already in scope is refused rather than overwritten. */
	private static void refusePathRebinding(Row row, String name) {
		if (row.has(name)) {
			throw new CypherTypeException("'" + name + "' is already defined, so it cannot also name a path");
		}
	}

	/** True for a binding this matcher invented rather than one the query named. */
	public static boolean isAnonymous(String name) {
		return name.startsWith(ANONYMOUS);
	}
}
