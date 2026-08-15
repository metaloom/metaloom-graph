package io.metaloom.graph.cypher.exec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.func.Functions;

/**
 * Runs a parsed query and produces columns and rows.
 *
 * <p>
 * Clause at a time over a materialised list of rows, rather than a pull-based operator tree. The trade is deliberate:
 * a volcano iterator streams and would let LIMIT stop the scan early, but every clause here can already see all its
 * input, which is what ORDER BY, DISTINCT and aggregation need anyway — and the simpler shape has far fewer places for
 * a scoping bug to hide. The cost is that a query's peak memory is its widest intermediate result, which is recorded
 * in spec/CYPHER.md rather than hidden.
 * </p>
 */
public final class QueryExecutor {

	private final Evaluator evaluator;
	private final PatternMatcher matcher;
	private final Writer writer;

	public QueryExecutor(GraphAccess graph, Map<String, Object> parameters) {
		this.evaluator = new Evaluator(graph, parameters);
		this.matcher = new PatternMatcher(evaluator);
		this.writer = new Writer(graph, evaluator);
	}

	/**
	 * The result of one query: the column names, in order, the rows, and what the query changed.
	 *
	 * @param stats
	 *            {@link CypherStats#NONE} for a read. A write query is often all counters and no rows, so this is not
	 *            an addendum to the result — for {@code CREATE (n:Person)} it is the entire result
	 */
	public record Result(List<String> columns, List<List<Object>> rows, CypherStats stats) {
	}

	public Result run(Ast.Query query) {
		Result result = runSingle(query.parts().get(0));
		for (int i = 1; i < query.parts().size(); i++) {
			Result next = runSingle(query.parts().get(i));
			if (!result.columns().equals(next.columns())) {
				throw new CypherTypeException("All parts of a UNION must return the same columns: "
					+ result.columns() + " against " + next.columns());
			}
			List<List<Object>> combined = new ArrayList<>(result.rows());
			combined.addAll(next.rows());
			// UNION deduplicates, UNION ALL does not — the flag is per union, in order.
			if (!query.unionAll().get(i - 1)) {
				combined = distinctRows(combined);
			}
			result = new Result(result.columns(), combined, result.stats());
		}
		// One Writer serves every part, so the counters are the query's rather than the last part's.
		return new Result(result.columns(), result.rows(), writer.stats());
	}

	private Result runSingle(Ast.SingleQuery query) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Row());
		List<String> columns = List.of();
		boolean returned = false;

		for (Ast.Clause clause : query.clauses()) {
			switch (clause) {
				case Ast.Match match -> {
					List<Row> next = new ArrayList<>();
					for (Row row : rows) {
						next.addAll(matcher.match(match.pattern(), row));
					}
					rows = filter(next, match.where());
				}
				case Ast.Unwind unwind -> {
					List<Row> next = new ArrayList<>();
					for (Row row : rows) {
						Object value = evaluator.evaluate(unwind.expression(), row);
						if (value == null) {
							// UNWIND null produces no rows, which is not the same as producing one null row.
							continue;
						}
						if (!(value instanceof List<?> list)) {
							throw new CypherTypeException("UNWIND expects a list but got "
								+ Values.typeName(value));
						}
						for (Object element : list) {
							next.add(row.with(unwind.variable(), element));
						}
					}
					rows = next;
				}
				case Ast.With with -> {
					Projected projected = project(rows, with.projection());
					rows = filter(projected.rows(), with.where());
					rows = order(rows, with.projection(), projected.columns());
					rows = slice(rows, with.projection());
					columns = projected.columns();
				}
				case Ast.Return returnClause -> {
					Projected projected = project(rows, returnClause.projection());
					rows = order(projected.rows(), returnClause.projection(), projected.columns());
					rows = slice(rows, returnClause.projection());
					columns = projected.columns();
					returned = true;
				}
				// Each of these consumes the materialised list and produces one, which is what gives the write clauses
				// openCypher's eager semantics for nothing: a CREATE cannot feed the MATCH that is driving it.
				case Ast.Create create -> rows = writer.create(create, rows);
				case Ast.SetClause set -> rows = writer.set(set, rows);
				case Ast.Remove remove -> rows = writer.remove(remove, rows);
				case Ast.Delete delete -> rows = writer.delete(delete, rows);
			}
		}

		if (!returned) {
			// A write query with no RETURN. Its result is what it changed, and the counters carry that; projecting the
			// working rows would report one empty row per element touched, which is a result the query never asked
			// for. Only a write can reach here — the parser refuses a read that does not end in RETURN.
			return new Result(List.of(), List.of(), writer.stats());
		}

		List<List<Object>> output = new ArrayList<>(rows.size());
		for (Row row : rows) {
			List<Object> values = new ArrayList<>(columns.size());
			for (String column : columns) {
				values.add(row.get(column));
			}
			output.add(values);
		}
		return new Result(columns, output, writer.stats());
	}

	// ---------------------------------------------------------------- clauses

	private List<Row> filter(List<Row> rows, Ast.Expr where) {
		if (where == null) {
			return rows;
		}
		List<Row> kept = new ArrayList<>();
		for (Row row : rows) {
			// A predicate that evaluates to null drops the row, exactly as false does. See Values.isTrue.
			if (Values.isTrue(evaluator.evaluate(where, row))) {
				kept.add(row);
			}
		}
		return kept;
	}

	private record Projected(List<String> columns, List<Row> rows) {
	}

	private Projected project(List<Row> rows, Ast.Projection projection) {
		List<String> columns = new ArrayList<>();
		List<Ast.ProjectionItem> items = new ArrayList<>();
		if (projection.star()) {
			// `RETURN *` is every variable the query named, in binding order, and none of the anonymous ones the
			// pattern matcher invented for `()`.
			Set<String> names = new LinkedHashSet<>();
			for (Row row : rows) {
				for (String name : row.names()) {
					if (!PatternMatcher.isAnonymous(name)) {
						names.add(name);
					}
				}
			}
			for (String name : names) {
				columns.add(name);
				items.add(new Ast.ProjectionItem(new Ast.Variable(name), name));
			}
		}
		for (Ast.ProjectionItem item : projection.items()) {
			columns.add(item.alias());
			items.add(item);
		}

		List<Row> projected = hasAggregate(items)
			? aggregate(rows, items)
			: projectRows(rows, items);
		if (projection.distinct()) {
			projected = distinctBy(projected, columns);
		}
		return new Projected(columns, projected);
	}

	private List<Row> projectRows(List<Row> rows, List<Ast.ProjectionItem> items) {
		List<Row> out = new ArrayList<>(rows.size());
		for (Row row : rows) {
			Row projected = new Row();
			for (Ast.ProjectionItem item : items) {
				projected = projected.with(item.alias(), evaluator.evaluate(item.expression(), row));
			}
			// The source row stays reachable underneath so that ORDER BY can sort on something not projected, which
			// Cypher allows for RETURN.
			out.add(row.merge(projected));
		}
		return out;
	}

	// ---------------------------------------------------------------- aggregation

	private static boolean hasAggregate(List<Ast.ProjectionItem> items) {
		for (Ast.ProjectionItem item : items) {
			if (containsAggregate(item.expression())) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsAggregate(Ast.Expr expression) {
		return switch (expression) {
			case Ast.FunctionCall call -> Functions.isAggregate(call.name())
				|| call.arguments().stream().anyMatch(QueryExecutor::containsAggregate);
			case Ast.Binary binary -> containsAggregate(binary.left()) || containsAggregate(binary.right());
			case Ast.Unary unary -> containsAggregate(unary.operand());
			case Ast.Comparison comparison -> containsAggregate(comparison.left())
				|| containsAggregate(comparison.right());
			case Ast.Property property -> containsAggregate(property.target());
			case Ast.Index index -> containsAggregate(index.target()) || containsAggregate(index.index());
			case Ast.ListLiteral list -> list.elements().stream().anyMatch(QueryExecutor::containsAggregate);
			default -> false;
		};
	}

	/**
	 * Groups by every non-aggregating projection item and folds the aggregating ones.
	 *
	 * <p>
	 * That grouping rule is Cypher's, and it surprises people: there is no GROUP BY, the grouping keys are whatever
	 * you projected that is not an aggregate. {@code RETURN n.city, count(*)} groups by city because {@code n.city}
	 * is the only non-aggregate.
	 * </p>
	 */
	private List<Row> aggregate(List<Row> rows, List<Ast.ProjectionItem> items) {
		List<Ast.ProjectionItem> keys = new ArrayList<>();
		List<Ast.ProjectionItem> aggregates = new ArrayList<>();
		for (Ast.ProjectionItem item : items) {
			(containsAggregate(item.expression()) ? aggregates : keys).add(item);
		}

		Map<List<Object>, List<Row>> groups = new LinkedHashMap<>();
		for (Row row : rows) {
			List<Object> key = new ArrayList<>(keys.size());
			for (Ast.ProjectionItem item : keys) {
				key.add(evaluator.evaluate(item.expression(), row));
			}
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
		}
		// An aggregate with no grouping key over no rows still produces one row: `count(*)` of nothing is 0, not
		// no answer at all.
		if (groups.isEmpty() && keys.isEmpty()) {
			groups.put(List.of(), List.of());
		}

		List<Row> out = new ArrayList<>(groups.size());
		for (Map.Entry<List<Object>, List<Row>> group : groups.entrySet()) {
			Row projected = new Row();
			for (int i = 0; i < keys.size(); i++) {
				projected = projected.with(keys.get(i).alias(), group.getKey().get(i));
			}
			for (Ast.ProjectionItem item : aggregates) {
				projected = projected.with(item.alias(), foldAggregate(item.expression(), group.getValue()));
			}
			out.add(projected);
		}
		return out;
	}

	/** Evaluates an expression in which aggregating calls are folded over the group and the rest is ordinary. */
	private Object foldAggregate(Ast.Expr expression, List<Row> group) {
		if (expression instanceof Ast.FunctionCall call && Functions.isAggregate(call.name())) {
			return Aggregates.fold(evaluator, call, group);
		}
		if (expression instanceof Ast.Binary binary) {
			Object left = foldAggregate(binary.left(), group);
			Object right = foldAggregate(binary.right(), group);
			return switch (binary.op()) {
				case ADD -> Values.add(left, right);
				case SUBTRACT -> Values.subtract(left, right);
				case MULTIPLY -> Values.multiply(left, right);
				case DIVIDE -> Values.divide(left, right);
				case MODULO -> Values.modulo(left, right);
				case POWER -> Values.power(left, right);
				case AND -> Values.and(left, right);
				case OR -> Values.or(left, right);
				case XOR -> Values.xor(left, right);
				default -> throw new CypherTypeException("Unsupported operator " + binary.op()
					+ " over an aggregate");
			};
		}
		if (expression instanceof Ast.Unary unary) {
			Object operand = foldAggregate(unary.operand(), group);
			return unary.op() == Ast.Op.NOT ? Values.not(operand) : Values.negate(operand);
		}
		// A plain expression inside an aggregating projection is evaluated against the first row of the group; it is
		// constant across the group by construction, because it is one of the grouping keys.
		return group.isEmpty() ? null : evaluator.evaluate(expression, group.get(0));
	}

	// ---------------------------------------------------------------- ordering and paging

	private List<Row> order(List<Row> rows, Ast.Projection projection, List<String> columns) {
		if (projection.order().isEmpty()) {
			return rows;
		}
		List<Row> sorted = new ArrayList<>(rows);
		Comparator<Row> comparator = null;
		for (Ast.SortItem item : projection.order()) {
			Comparator<Row> next = Comparator.comparing(
				row -> sortKey(item.expression(), row), Values::orderForSort);
			if (!item.ascending()) {
				next = next.reversed();
			}
			comparator = comparator == null ? next : comparator.thenComparing(next);
		}
		sorted.sort(comparator);
		return sorted;
	}

	/**
	 * The value a sort item orders by.
	 *
	 * <p>
	 * Resolved as a projected column first. After an aggregation the row holds only the projection's output, so
	 * {@code RETURN n.city, count(*) ORDER BY n.city} has no {@code n} left to read a property from — but it does have
	 * a column called {@code n.city}, which is what the user meant and what Cypher orders by.
	 * </p>
	 */
	private Object sortKey(Ast.Expr expression, Row row) {
		String name = io.metaloom.graph.cypher.ast.Rendering.text(expression);
		if (row.has(name)) {
			return row.get(name);
		}
		return evaluator.evaluate(expression, row);
	}

	private List<Row> slice(List<Row> rows, Ast.Projection projection) {
		int from = 0;
		if (projection.skip() != null) {
			from = (int) Math.min(rows.size(), positive(projection.skip(), "SKIP"));
		}
		int to = rows.size();
		if (projection.limit() != null) {
			to = (int) Math.min(rows.size(), from + positive(projection.limit(), "LIMIT"));
		}
		return from == 0 && to == rows.size() ? rows : new ArrayList<>(rows.subList(from, to));
	}

	private long positive(Ast.Expr expression, String clause) {
		Object value = evaluator.evaluate(expression, new Row());
		long number = Values.number(value, clause).longValue();
		if (number < 0) {
			throw new CypherTypeException(clause + " must not be negative, got " + number);
		}
		return number;
	}

	// ---------------------------------------------------------------- distinct

	private List<Row> distinctBy(List<Row> rows, List<String> columns) {
		List<Row> kept = new ArrayList<>();
		List<List<Object>> seen = new ArrayList<>();
		for (Row row : rows) {
			List<Object> key = new ArrayList<>(columns.size());
			for (String column : columns) {
				key.add(row.get(column));
			}
			if (!containsRow(seen, key)) {
				seen.add(key);
				kept.add(row);
			}
		}
		return kept;
	}

	private static List<List<Object>> distinctRows(List<List<Object>> rows) {
		List<List<Object>> kept = new ArrayList<>();
		for (List<Object> row : rows) {
			if (!containsRow(kept, row)) {
				kept.add(row);
			}
		}
		return kept;
	}

	/**
	 * Row membership under Cypher equality rather than {@code Object.equals}.
	 * <p>
	 * A hash set would be faster and wrong: {@code 1} and {@code 1.0} are the same Cypher value with different hash
	 * codes, and two nodes are equal by id rather than by contents. The cost is quadratic in the number of distinct
	 * rows, which is noted in spec/CYPHER.md.
	 */
	private static boolean containsRow(List<List<Object>> rows, List<Object> candidate) {
		for (List<Object> row : rows) {
			if (sameRow(row, candidate)) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameRow(List<Object> left, List<Object> right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			Object a = left.get(i);
			Object b = right.get(i);
			if (a == null || b == null) {
				// DISTINCT treats two nulls as the same value, which is the one place null equals null in Cypher.
				if (a != b) {
					return false;
				}
				continue;
			}
			if (!Values.equalKnown(a, b)) {
				return false;
			}
		}
		return true;
	}
}
