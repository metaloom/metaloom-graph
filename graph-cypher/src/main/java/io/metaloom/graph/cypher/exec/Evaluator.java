package io.metaloom.graph.cypher.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.ast.Ast.Expr;
import io.metaloom.graph.cypher.func.Functions;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * Evaluates an expression against a row.
 *
 * <p>
 * Aggregating functions are not evaluated here — {@code Aggregate} recognises them in the projection and handles
 * grouping itself, then binds their results as ordinary values before this runs over the rest of the expression. So
 * {@code count(*) + 1} works: the aggregate is computed first and this sees a number.
 * </p>
 */
public final class Evaluator {

	private final GraphAccess graph;
	private final Map<String, Object> parameters;

	public Evaluator(GraphAccess graph, Map<String, Object> parameters) {
		this.graph = graph;
		this.parameters = parameters == null ? Map.of() : parameters;
	}

	public GraphAccess graph() {
		return graph;
	}

	public Object evaluate(Expr expression, Row row) {
		return switch (expression) {
			case Ast.Literal literal -> literal.value();
			case Ast.Variable variable -> {
				if (!row.has(variable.name())) {
					throw new CypherTypeException("Variable " + variable.name() + " is not defined here. In scope: "
						+ row.names());
				}
				yield row.get(variable.name());
			}
			case Ast.Parameter parameter -> {
				if (!parameters.containsKey(parameter.name())) {
					throw new CypherTypeException("Parameter $" + parameter.name()
						+ " was not supplied. Supplied: " + parameters.keySet());
				}
				yield parameters.get(parameter.name());
			}
			case Ast.ListLiteral list -> {
				List<Object> out = new ArrayList<>(list.elements().size());
				for (Expr element : list.elements()) {
					out.add(evaluate(element, row));
				}
				yield out;
			}
			case Ast.MapLiteral map -> {
				Map<String, Object> out = new LinkedHashMap<>();
				for (Map.Entry<String, Expr> entry : map.entries().entrySet()) {
					out.put(entry.getKey(), evaluate(entry.getValue(), row));
				}
				yield out;
			}
			case Ast.Property property -> property(evaluate(property.target(), row), property.key());
			case Ast.Index index -> index(evaluate(index.target(), row), evaluate(index.index(), row));
			case Ast.IsNull isNull -> {
				boolean absent = evaluate(isNull.operand(), row) == null;
				yield isNull.negated() ? !absent : absent;
			}
			case Ast.LabelPredicate predicate -> labelPredicate(evaluate(predicate.target(), row),
				predicate.labels());
			case Ast.Unary unary -> switch (unary.op()) {
				case NOT -> Values.not(evaluate(unary.operand(), row));
				case NEGATE -> Values.negate(evaluate(unary.operand(), row));
				default -> throw new CypherTypeException("Unsupported unary operator " + unary.op());
			};
			case Ast.Binary binary -> binary(binary, row);
			case Ast.Comparison comparison -> comparison(comparison, row);
			case Ast.FunctionCall call -> Functions.call(this, call, row);
			case Ast.Case caseExpression -> caseValue(caseExpression, row);
		};
	}

	/**
	 * {@code CASE}, in both forms.
	 *
	 * <p>
	 * <b>Only the branch that is taken is evaluated.</b> That is not an optimisation — it is what makes
	 * {@code CASE WHEN n.d = 0 THEN 0 ELSE n.x / n.d END} mean what it says. Evaluating every {@code THEN} first would
	 * divide by zero to produce a value the expression then discards.
	 * </p>
	 *
	 * <p>
	 * The simple form compares with {@link Values#equalKnown} under Cypher equality, so {@code 1} and {@code 1.0}
	 * select the same branch. A null on either side selects <em>no</em> branch: null equals null is null rather than
	 * true, so {@code CASE null WHEN null THEN 'a' ELSE 'b' END} is {@code 'b'} — which surprises people, and is the
	 * same rule as everywhere else in §2.
	 * </p>
	 */
	private Object caseValue(Ast.Case expression, Row row) {
		Object subject = expression.subject() == null ? null : evaluate(expression.subject(), row);
		for (int i = 0; i < expression.whens().size(); i++) {
			Object when = evaluate(expression.whens().get(i), row);
			boolean taken = expression.subject() == null
				? Values.isTrue(when)
				: subject != null && when != null && Values.equalKnown(subject, when);
			if (taken) {
				return evaluate(expression.thens().get(i), row);
			}
		}
		// No ELSE means null, rather than an error: a CASE that matches nothing is a missing value, and the rest of
		// the language already knows what to do with one.
		return expression.otherwise() == null ? null : evaluate(expression.otherwise(), row);
	}

	private Object binary(Ast.Binary binary, Row row) {
		// AND and OR short-circuit, which matters for `x IS NOT NULL AND x.p = 1`: without it the right side would
		// evaluate against a null and the query would fail where Cypher says it should not.
		if (binary.op() == Ast.Op.AND) {
			Object left = evaluate(binary.left(), row);
			if (Boolean.FALSE.equals(left)) {
				return Boolean.FALSE;
			}
			return Values.and(left, evaluate(binary.right(), row));
		}
		if (binary.op() == Ast.Op.OR) {
			Object left = evaluate(binary.left(), row);
			if (Boolean.TRUE.equals(left)) {
				return Boolean.TRUE;
			}
			return Values.or(left, evaluate(binary.right(), row));
		}
		Object left = evaluate(binary.left(), row);
		Object right = evaluate(binary.right(), row);
		return switch (binary.op()) {
			case XOR -> Values.xor(left, right);
			case ADD -> Values.add(left, right);
			case SUBTRACT -> Values.subtract(left, right);
			case MULTIPLY -> Values.multiply(left, right);
			case DIVIDE -> Values.divide(left, right);
			case MODULO -> Values.modulo(left, right);
			case POWER -> Values.power(left, right);
			default -> throw new CypherTypeException("Unsupported operator " + binary.op());
		};
	}

	private Object comparison(Ast.Comparison comparison, Row row) {
		Object left = evaluate(comparison.left(), row);
		Object right = evaluate(comparison.right(), row);
		return switch (comparison.op()) {
			case EQUAL -> Values.equal(left, right);
			case NOT_EQUAL -> Values.notEqual(left, right);
			case LESS -> Values.compare(left, right, order -> order < 0);
			case LESS_OR_EQUAL -> Values.compare(left, right, order -> order <= 0);
			case GREATER -> Values.compare(left, right, order -> order > 0);
			case GREATER_OR_EQUAL -> Values.compare(left, right, order -> order >= 0);
			case IN -> Values.in(left, right);
			case STARTS_WITH -> Values.startsWith(left, right);
			case ENDS_WITH -> Values.endsWith(left, right);
			case CONTAINS -> Values.contains(left, right);
			default -> throw new CypherTypeException("Unsupported comparison " + comparison.op());
		};
	}

	/**
	 * {@code x.key} over a node, a relationship or a map.
	 * <p>
	 * A missing property is null rather than an error, which is what lets {@code WHERE n.age > 30} work on a set of
	 * nodes where only some carry an age.
	 */
	public Object property(Object target, String key) {
		return switch (target) {
			case null -> null;
			case CypherNode node -> node.property(key);
			case CypherRelationship relationship -> relationship.property(key);
			case Map<?, ?> map -> map.get(key);
			default -> throw new CypherTypeException(
				"Cannot read property '" + key + "' of " + Values.typeName(target));
		};
	}

	private Object index(Object target, Object index) {
		if (target == null || index == null) {
			return null;
		}
		if (target instanceof List<?> list) {
			long position = Values.number(index, "[]").longValue();
			// Negative indices count from the end, as in Cypher.
			long resolved = position < 0 ? list.size() + position : position;
			return resolved < 0 || resolved >= list.size() ? null : list.get((int) resolved);
		}
		if (index instanceof String key) {
			return property(target, key);
		}
		throw new CypherTypeException("Cannot index " + Values.typeName(target) + " with "
			+ Values.typeName(index));
	}

	private Object labelPredicate(Object target, List<String> labels) {
		if (target == null) {
			return null;
		}
		if (target instanceof CypherNode node) {
			for (String label : labels) {
				if (!node.hasLabel(label)) {
					return Boolean.FALSE;
				}
			}
			return Boolean.TRUE;
		}
		if (target instanceof CypherRelationship relationship) {
			// `r:TYPE` is a type test on a relationship, which can only ever have one type.
			return labels.size() == 1 && relationship.type().equals(labels.get(0));
		}
		throw new CypherTypeException("A label predicate needs a node but got " + Values.typeName(target));
	}

	/** Exposed for {@code length(p)} and friends, which need path structure rather than a property. */
	public static int pathLength(CypherPath path) {
		return path.length();
	}
}
