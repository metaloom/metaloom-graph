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
import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * The four write clauses, and the bookkeeping they share.
 *
 * <p>
 * One instance per query, because two things have to outlive a single clause: the counters that become
 * {@link CypherStats}, and the set of elements this query has deleted — without which {@code DELETE n} followed by
 * {@code SET n.x = 1} would reach the store with an id that no longer resolves, and produce a stale-id error instead of
 * a sentence explaining what the query did.
 * </p>
 *
 * <h2>Rebinding</h2>
 *
 * <p>
 * A {@link CypherNode} is an immutable snapshot taken when the variable was bound, so a clause that changes an element
 * leaves every row holding the old value. Left alone, {@code MATCH (n) SET n.age = 30 RETURN n} would return nodes
 * showing the age they had before — a wrong answer that throws nothing. So each clause records which elements it
 * touched and re-reads them once at the end, rewriting the bindings in every row, including inside lists and paths.
 * </p>
 *
 * <h2>Eagerness</h2>
 *
 * <p>
 * Every clause here consumes a materialised list and produces one, so {@code MATCH (n) CREATE (:Copy)} cannot feed its
 * creations back into the scan that is driving it. That is the same property other engines buy with explicit Eager
 * operators, and here it falls out of the executor's shape — which is also why a lazy pipeline must never carry one of
 * these clauses.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
final class Writer {

	private final GraphAccess graph;
	private final Evaluator evaluator;

	private final Set<Long> deletedNodes = new HashSet<>();
	private final Set<Long> deletedRelationships = new HashSet<>();

	private long nodesCreated;
	private long nodesDeleted;
	private long relationshipsCreated;
	private long relationshipsDeleted;
	private long propertiesSet;
	private long labelsAdded;
	private long labelsRemoved;

	Writer(GraphAccess graph, Evaluator evaluator) {
		this.graph = graph;
		this.evaluator = evaluator;
	}

	CypherStats stats() {
		return new CypherStats(nodesCreated, nodesDeleted, relationshipsCreated, relationshipsDeleted, propertiesSet,
			labelsAdded, labelsRemoved);
	}

	// ---------------------------------------------------------------- CREATE

	List<Row> create(Ast.Create clause, List<Row> rows) {
		List<Row> out = new ArrayList<>(rows.size());
		for (Row row : rows) {
			Row current = row;
			for (Ast.PatternPart part : clause.pattern()) {
				current = createPart(part, current);
			}
			out.add(current);
		}
		return out;
	}

	private Row createPart(Ast.PatternPart part, Row row) {
		Row current = row;
		List<CypherNode> nodes = new ArrayList<>(part.nodes().size());
		for (Ast.NodePattern pattern : part.nodes()) {
			CypherNode node = createOrReuse(pattern, current);
			nodes.add(node);
			if (pattern.variable() != null && !current.has(pattern.variable())) {
				current = current.with(pattern.variable(), node);
			}
		}

		List<CypherRelationship> relationships = new ArrayList<>(part.relationships().size());
		for (int i = 0; i < part.relationships().size(); i++) {
			Ast.RelPattern pattern = part.relationships().get(i);
			// The parser has already refused a range, an undirected arrow and anything other than exactly one type,
			// so by here the pattern names one relationship going one way.
			boolean rightwards = pattern.direction() == Ast.Direction.RIGHT;
			CypherNode from = rightwards ? nodes.get(i) : nodes.get(i + 1);
			CypherNode to = rightwards ? nodes.get(i + 1) : nodes.get(i);
			refuseIfDeleted(from.id(), "a relationship");
			refuseIfDeleted(to.id(), "a relationship");
			String type = pattern.types().get(0);
			Map<String, Object> properties = literalMap(pattern.properties(), current);
			long id = graph.createRelationship(from.id(), type, to.id(), properties);
			relationshipsCreated++;
			propertiesSet += properties.size();
			CypherRelationship relationship = new CypherRelationship(id, type, from.id(), to.id(), properties);
			relationships.add(relationship);
			if (pattern.variable() != null) {
				current = current.with(pattern.variable(), relationship);
			}
		}

		if (part.pathVariable() != null) {
			current = current.with(part.pathVariable(), new CypherPath(nodes, relationships));
		}
		return current;
	}

	/**
	 * A node pattern in CREATE either names something already bound or describes something new.
	 *
	 * <p>
	 * Reusing a bound variable is how {@code MATCH (a) CREATE (a)-[:R]->(b)} attaches to an existing node, and it is
	 * also how a self-relationship is written. Giving that occurrence labels or properties is refused: the node exists
	 * and the query is describing it as though it did not, which is much more likely to be a name collision than an
	 * intention.
	 * </p>
	 */
	private CypherNode createOrReuse(Ast.NodePattern pattern, Row row) {
		if (pattern.variable() != null && row.has(pattern.variable())) {
			Object bound = row.get(pattern.variable());
			if (!(bound instanceof CypherNode node)) {
				throw new CypherTypeException("CREATE cannot use '" + pattern.variable() + "' as a node: it is bound "
					+ "to " + Values.typeName(bound));
			}
			if (!pattern.labels().isEmpty() || (pattern.properties() != null && !pattern.properties().isEmpty())) {
				throw new CypherTypeException("'" + pattern.variable() + "' is already bound, so CREATE cannot give "
					+ "it labels or properties; use SET to change an existing node");
			}
			refuseIfDeleted(node.id(), "a relationship");
			return node;
		}
		Map<String, Object> properties = literalMap(pattern.properties(), row);
		// Distinct, because the engine drops a duplicate rather than storing it twice — `CREATE (n:A:A)` adds one
		// label, and a counter saying two would be reporting the query text instead of the store.
		List<String> labels = List.copyOf(new LinkedHashSet<>(pattern.labels()));
		long id = graph.createNode(labels, properties);
		nodesCreated++;
		labelsAdded += labels.size();
		propertiesSet += properties.size();
		return new CypherNode(id, labels, properties);
	}

	private Map<String, Object> literalMap(Map<String, Ast.Expr> pattern, Row row) {
		if (pattern == null || pattern.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, Ast.Expr> entry : pattern.entrySet()) {
			Object value = evaluator.evaluate(entry.getValue(), row);
			// A null property is not stored at all, matching SET n.x = null. A key present with no value would be a
			// third state the store would have to distinguish from absent, and Cypher does not have one.
			if (value != null) {
				values.put(entry.getKey(), value);
			}
		}
		return values;
	}

	// ---------------------------------------------------------------- SET and REMOVE

	List<Row> set(Ast.SetClause clause, List<Row> rows) {
		Touched touched = new Touched();
		for (Row row : rows) {
			Pending pending = new Pending();
			for (Ast.SetItem item : clause.items()) {
				switch (item) {
					case Ast.SetProperty property -> {
						Object value = evaluator.evaluate(property.value(), row);
						assign(pending, evaluator.evaluate(property.target(), row), property.key(), value);
					}
					case Ast.SetPropertiesReplace replace -> {
						Object target = binding(row, replace.variable());
						Map<String, Object> map = asPropertyMap(evaluator.evaluate(replace.map(), row), "SET");
						pending.of(target).clear();
						pending.of(target).putAll(map);
					}
					case Ast.SetPropertiesMerge merge -> {
						Object target = binding(row, merge.variable());
						pending.of(target).putAll(asPropertyMap(evaluator.evaluate(merge.map(), row), "SET"));
					}
					case Ast.SetLabels labels -> {
						CypherNode node = node(binding(row, labels.variable()), "SET");
						refuseIfDeleted(node.id(), "labels");
						for (String label : labels.labels()) {
							if (graph.addNodeLabel(node.id(), label)) {
								labelsAdded++;
							}
						}
						touched.nodes.add(node.id());
					}
				}
			}
			pending.flush(touched);
		}
		return refresh(rows, touched);
	}

	List<Row> remove(Ast.Remove clause, List<Row> rows) {
		Touched touched = new Touched();
		for (Row row : rows) {
			Pending pending = new Pending();
			for (Ast.RemoveItem item : clause.items()) {
				switch (item) {
					case Ast.RemoveProperty property ->
						assign(pending, evaluator.evaluate(property.target(), row), property.key(), null);
					case Ast.RemoveLabels labels -> {
						CypherNode node = node(binding(row, labels.variable()), "REMOVE");
						refuseIfDeleted(node.id(), "labels");
						for (String label : labels.labels()) {
							if (graph.removeNodeLabel(node.id(), label)) {
								labelsRemoved++;
							}
						}
						touched.nodes.add(node.id());
					}
				}
			}
			pending.flush(touched);
		}
		return refresh(rows, touched);
	}

	/** Records one property assignment against the element it belongs to. A null value removes the key. */
	private void assign(Pending pending, Object target, String key, Object value) {
		if (target == null) {
			// SET null.x = 1 is a no-op rather than an error, which is how every other null-valued expression behaves.
			return;
		}
		Map<String, Object> properties = pending.of(target);
		if (value == null) {
			properties.remove(key);
		} else {
			properties.put(key, value);
		}
	}

	/**
	 * The property writes accumulated for one row, so that several assignments to one element cost one store write.
	 *
	 * <p>
	 * That is not a micro-optimisation. {@code setNodeProperties} decodes the element's whole property block,
	 * re-encodes the map, allocates a new block, frees the old one and diffs both against the property index — so
	 * {@code SET n.a = 1, n.b = 2} done twice would do all of that twice, and would leave a freed block behind for
	 * every assignment after the first.
	 * </p>
	 */
	private final class Pending {

		private final Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
		private final Map<Long, Map<String, Object>> relationships = new LinkedHashMap<>();
		private final Map<Long, Map<String, Object>> nodesBefore = new LinkedHashMap<>();
		private final Map<Long, Map<String, Object>> relationshipsBefore = new LinkedHashMap<>();

		Map<String, Object> of(Object target) {
			return switch (target) {
				case CypherNode node -> {
					refuseIfDeleted(node.id(), "properties");
					nodesBefore.computeIfAbsent(node.id(), id -> graph.node(id).properties());
					yield nodes.computeIfAbsent(node.id(), id -> new LinkedHashMap<>(nodesBefore.get(id)));
				}
				case CypherRelationship relationship -> {
					if (!relationship.isMaterialised()) {
						throw new CypherTypeException("This relationship has no properties and cannot be given any: "
							+ "it is a lightweight edge, which costs 16 bytes and stores no record. Create it with "
							+ "properties, or use the Java API");
					}
					refuseIfDeleted(relationship.id(), "properties");
					relationshipsBefore.computeIfAbsent(relationship.id(),
						id -> graph.relationship(id).properties());
					yield relationships.computeIfAbsent(relationship.id(),
						id -> new LinkedHashMap<>(relationshipsBefore.get(id)));
				}
				default -> throw new CypherTypeException("Only a node or a relationship has properties to set, but "
					+ "this is " + Values.typeName(target));
			};
		}

		void flush(Touched touched) {
			for (Map.Entry<Long, Map<String, Object>> entry : nodes.entrySet()) {
				Map<String, Object> before = nodesBefore.get(entry.getKey());
				if (before.equals(entry.getValue())) {
					// Nothing changed, so nothing is written. Rewriting an identical map would allocate a new property
					// block, free the old one and dirty an index page, all to arrive where it started.
					continue;
				}
				graph.setNodeProperties(entry.getKey(), entry.getValue());
				propertiesSet += changedKeys(before, entry.getValue());
				touched.nodes.add(entry.getKey());
			}
			for (Map.Entry<Long, Map<String, Object>> entry : relationships.entrySet()) {
				Map<String, Object> before = relationshipsBefore.get(entry.getKey());
				if (before.equals(entry.getValue())) {
					continue;
				}
				graph.setRelationshipProperties(entry.getKey(), entry.getValue());
				propertiesSet += changedKeys(before, entry.getValue());
				touched.relationships.add(entry.getKey());
			}
		}
	}

	private static long changedKeys(Map<String, Object> before, Map<String, Object> after) {
		long changed = 0;
		for (Map.Entry<String, Object> entry : after.entrySet()) {
			if (!java.util.Objects.deepEquals(before.get(entry.getKey()), entry.getValue())) {
				changed++;
			}
		}
		for (String key : before.keySet()) {
			if (!after.containsKey(key)) {
				changed++;
			}
		}
		return changed;
	}

	// ---------------------------------------------------------------- DELETE

	List<Row> delete(Ast.Delete clause, List<Row> rows) {
		for (Row row : rows) {
			List<Object> targets = new ArrayList<>(clause.targets().size());
			for (Ast.Expr target : clause.targets()) {
				targets.add(evaluator.evaluate(target, row));
			}
			// Relationships first, whatever order they were written in, so that DELETE a, r succeeds exactly as
			// DELETE r, a does. Otherwise the plain-DELETE degree check below would fail on a node whose only
			// relationship the same clause was about to remove.
			for (Object target : targets) {
				deleteRelationships(target);
			}
			for (Object target : targets) {
				deleteNodes(target, clause.detach());
			}
		}
		// The rows keep the values they were bound to. RETURN n after DELETE n gives back what the node was, which is
		// the only answer left to give and is what other implementations return too.
		return rows;
	}

	private void deleteRelationships(Object target) {
		switch (target) {
			case null -> {
				// DELETE null is a no-op, like every other null-valued expression.
			}
			case CypherRelationship relationship -> deleteRelationship(relationship);
			case CypherPath path -> path.relationships().forEach(this::deleteRelationship);
			case CypherNode ignored -> {
				// Handled in the second pass.
			}
			default -> throw new CypherTypeException("DELETE takes a node, a relationship or a path, but this is "
				+ Values.typeName(target));
		}
	}

	private void deleteNodes(Object target, boolean detach) {
		switch (target) {
			// Spelled out rather than left to the default: a switch over a null selector throws, so omitting this
			// turns DELETE n.missing into a NullPointerException instead of the no-op every other null-valued
			// expression is.
			case null -> {
			}
			case CypherNode node -> deleteNode(node, detach);
			case CypherPath path -> path.nodes().forEach(node -> deleteNode(node, detach));
			default -> {
				// Relationships were handled in the first pass; anything else was refused there.
			}
		}
	}

	private void deleteRelationship(CypherRelationship relationship) {
		if (relationship.isMaterialised()) {
			if (deletedRelationships.add(relationship.id())) {
				graph.deleteRelationship(relationship.id());
				relationshipsDeleted++;
			}
			return;
		}
		// A lightweight edge has no id, so it can only be named by its endpoints and type. Two parallel lightweight
		// edges are indistinguishable by construction — they are the same 16 bytes — so removing either satisfies a
		// request to remove one, and two rows removing "the same" edge remove both.
		if (deletedNodes.contains(relationship.startId()) || deletedNodes.contains(relationship.endId())) {
			return;
		}
		if (graph.deleteEdge(relationship.startId(), relationship.type(), relationship.endId())) {
			relationshipsDeleted++;
		}
	}

	private void deleteNode(CypherNode node, boolean detach) {
		if (deletedNodes.contains(node.id())) {
			return;
		}
		if (!detach) {
			int degree = graph.degree(node.id());
			if (degree > 0) {
				// The engine's deleteNode always cascades, so this rule belongs to the query language and has to be
				// enforced above it. Refusing is the useful answer: a DELETE that silently took relationships with it
				// would be a data loss nobody asked for.
				throw new CypherTypeException("Cannot delete node " + node.id() + ": it still has relationships. "
					+ "Use DETACH DELETE to remove them with it, or delete them first");
			}
		} else {
			// Counted before the delete, and after any earlier delete in the same clause, so a relationship whose
			// other end has already gone is not counted twice.
			relationshipsDeleted += graph.expand(node.id(), Direction.BOTH, null).size();
		}
		graph.deleteNode(node.id());
		deletedNodes.add(node.id());
		nodesDeleted++;
	}

	// ---------------------------------------------------------------- deleted elements and rebinding

	private void refuseIfDeleted(long id, String what) {
		if (deletedNodes.contains(id) || deletedRelationships.contains(id)) {
			throw new CypherTypeException("This query has already deleted element " + id + ", so it cannot give it "
				+ what);
		}
	}

	private Object binding(Row row, String variable) {
		if (!row.has(variable)) {
			throw new CypherTypeException("'" + variable + "' is not bound; it has to come from an earlier MATCH, "
				+ "CREATE, WITH or UNWIND");
		}
		return row.get(variable);
	}

	private static CypherNode node(Object value, String clause) {
		if (value instanceof CypherNode node) {
			return node;
		}
		throw new CypherTypeException(clause + " can only change the labels of a node, but this is "
			+ Values.typeName(value));
	}

	private static Map<String, Object> asPropertyMap(Object value, String clause) {
		return switch (value) {
			case null -> Map.of();
			case Map<?, ?> map -> {
				Map<String, Object> out = new LinkedHashMap<>();
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (entry.getValue() != null) {
						out.put(String.valueOf(entry.getKey()), entry.getValue());
					}
				}
				yield out;
			}
			case CypherNode node -> node.properties();
			case CypherRelationship relationship -> relationship.properties();
			default -> throw new CypherTypeException(clause + " needs a map on the right of = or +=, but this is "
				+ Values.typeName(value));
		};
	}

	/** The elements one clause changed, so they can be re-read once rather than once per row that holds them. */
	private static final class Touched {

		private final Set<Long> nodes = new LinkedHashSet<>();
		private final Set<Long> relationships = new LinkedHashSet<>();

		boolean isEmpty() {
			return nodes.isEmpty() && relationships.isEmpty();
		}
	}

	private List<Row> refresh(List<Row> rows, Touched touched) {
		if (touched.isEmpty()) {
			return rows;
		}
		Map<Long, CypherNode> nodes = new HashMap<>();
		for (long id : touched.nodes) {
			if (!deletedNodes.contains(id)) {
				nodes.put(id, graph.node(id));
			}
		}
		Map<Long, CypherRelationship> relationships = new HashMap<>();
		for (long id : touched.relationships) {
			if (!deletedRelationships.contains(id)) {
				relationships.put(id, graph.relationship(id));
			}
		}
		List<Row> out = new ArrayList<>(rows.size());
		for (Row row : rows) {
			Row current = row;
			for (String name : row.names()) {
				Object value = row.get(name);
				Object rebound = rebind(value, nodes, relationships);
				if (rebound != value) {
					current = current.with(name, rebound);
				}
			}
			out.add(current);
		}
		return out;
	}

	private static Object rebind(Object value, Map<Long, CypherNode> nodes,
		Map<Long, CypherRelationship> relationships) {
		return switch (value) {
			case CypherNode node -> nodes.getOrDefault(node.id(), node);
			case CypherRelationship relationship -> relationship.isMaterialised()
				? relationships.getOrDefault(relationship.id(), relationship)
				: relationship;
			case CypherPath path -> {
				List<CypherNode> pathNodes = new ArrayList<>(path.nodes().size());
				boolean changed = false;
				for (CypherNode node : path.nodes()) {
					CypherNode rebound = nodes.getOrDefault(node.id(), node);
					changed |= rebound != node;
					pathNodes.add(rebound);
				}
				List<CypherRelationship> pathRelationships = new ArrayList<>(path.relationships().size());
				for (CypherRelationship relationship : path.relationships()) {
					CypherRelationship rebound = relationship.isMaterialised()
						? relationships.getOrDefault(relationship.id(), relationship)
						: relationship;
					changed |= rebound != relationship;
					pathRelationships.add(rebound);
				}
				yield changed ? new CypherPath(pathNodes, pathRelationships) : path;
			}
			case List<?> list -> {
				List<Object> out = new ArrayList<>(list.size());
				boolean changed = false;
				for (Object element : list) {
					Object rebound = rebind(element, nodes, relationships);
					changed |= rebound != element;
					out.add(rebound);
				}
				yield changed ? out : list;
			}
			default -> value;
		};
	}
}
