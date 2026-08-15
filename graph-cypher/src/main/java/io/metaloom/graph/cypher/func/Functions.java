package io.metaloom.graph.cypher.func;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.exec.Evaluator;
import io.metaloom.graph.cypher.exec.Row;
import io.metaloom.graph.cypher.exec.Values;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * The scalar function library.
 *
 * <p>
 * Aggregates are <b>not</b> here: {@code count}, {@code sum}, {@code avg}, {@code min}, {@code max} and
 * {@code collect} need every row of a group at once, so they are implemented by the aggregation operator and only
 * their <em>names</em> live here, in {@link #AGGREGATES}, so the planner can recognise them in a projection.
 * </p>
 *
 * <p>
 * A function this build does not have fails with its own name and the list of what exists, rather than with a null
 * result — silently returning null for an unknown function is how a typo becomes an empty result set.
 * </p>
 */
public final class Functions {

	private Functions() {
	}

	/** Recognised by the planner, computed by the aggregation operator. */
	public static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "collect");

	private static final Set<String> SCALARS = Set.of(
		"id", "labels", "type", "keys", "properties", "startnode", "endnode", "length", "size",
		"head", "last", "tail", "range", "coalesce", "timestamp", "exists",
		"tostring", "toupper", "tolower", "upper", "lower", "trim", "ltrim", "rtrim",
		"substring", "replace", "split", "reverse",
		"tointeger", "tofloat", "toboolean", "abs", "ceil", "floor", "round", "sqrt", "sign", "nodes",
		"relationships");

	public static boolean isAggregate(String name) {
		return AGGREGATES.contains(name.toLowerCase(Locale.ROOT));
	}

	public static Object call(Evaluator evaluator, Ast.FunctionCall call, Row row) {
		String name = call.name().toLowerCase(Locale.ROOT);
		if (isAggregate(name)) {
			// Reached only when an aggregate appears where the planner did not expect one, e.g. inside WHERE.
			throw new CypherTypeException(call.name()
				+ " is an aggregating function and can only be used in a RETURN or WITH projection");
		}
		if (!SCALARS.contains(name)) {
			throw new CypherTypeException("Unknown function " + call.name() + "(). Available: "
				+ new java.util.TreeSet<>(SCALARS) + " and the aggregates " + new java.util.TreeSet<>(AGGREGATES));
		}

		// coalesce is the one function that must not evaluate every argument: its whole purpose is to stop at the
		// first non-null.
		if (name.equals("coalesce")) {
			for (Ast.Expr argument : call.arguments()) {
				Object value = evaluator.evaluate(argument, row);
				if (value != null) {
					return value;
				}
			}
			return null;
		}
		if (name.equals("timestamp")) {
			expect(call, 0);
			return System.currentTimeMillis();
		}

		List<Object> args = new ArrayList<>(call.arguments().size());
		for (Ast.Expr argument : call.arguments()) {
			args.add(evaluator.evaluate(argument, row));
		}
		return apply(name, call, args);
	}

	private static Object apply(String name, Ast.FunctionCall call, List<Object> args) {
		switch (name) {
			case "id" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					case CypherNode node -> node.id();
					// Null for a lightweight edge, which genuinely has no id. See CypherRelationship.
					case CypherRelationship relationship -> relationship.id();
					default -> throw typeError("id", args.get(0));
				};
			}
			case "labels" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				if (args.get(0) instanceof CypherNode node) {
					return new ArrayList<Object>(node.labels());
				}
				throw typeError("labels", args.get(0));
			}
			case "type" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				if (args.get(0) instanceof CypherRelationship relationship) {
					return relationship.type();
				}
				throw typeError("type", args.get(0));
			}
			case "keys" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					case CypherNode node -> new ArrayList<Object>(node.properties().keySet());
					case CypherRelationship relationship -> new ArrayList<Object>(relationship.properties().keySet());
					case Map<?, ?> map -> new ArrayList<Object>(map.keySet());
					default -> throw typeError("keys", args.get(0));
				};
			}
			case "properties" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					case CypherNode node -> node.properties();
					case CypherRelationship relationship -> relationship.properties();
					case Map<?, ?> map -> map;
					default -> throw typeError("properties", args.get(0));
				};
			}
			case "startnode" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				if (args.get(0) instanceof CypherRelationship relationship) {
					return relationship.startId();
				}
				throw typeError("startNode", args.get(0));
			}
			case "endnode" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				if (args.get(0) instanceof CypherRelationship relationship) {
					return relationship.endId();
				}
				throw typeError("endNode", args.get(0));
			}
			case "nodes" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				if (args.get(0) instanceof CypherPath path) {
					return new ArrayList<Object>(path.nodes());
				}
				throw typeError("nodes", args.get(0));
			}
			case "relationships" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				if (args.get(0) instanceof CypherPath path) {
					return new ArrayList<Object>(path.relationships());
				}
				throw typeError("relationships", args.get(0));
			}
			case "length" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					// length() is hops for a path and characters for a string; size() is the general one.
					case CypherPath path -> (long) path.length();
					case String string -> (long) string.length();
					case List<?> list -> (long) list.size();
					default -> throw typeError("length", args.get(0));
				};
			}
			case "size" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					case String string -> (long) string.length();
					case List<?> list -> (long) list.size();
					case Map<?, ?> map -> (long) map.size();
					default -> throw typeError("size", args.get(0));
				};
			}
			case "head" -> {
				expect(call, 1);
				List<?> list = listArg("head", args.get(0));
				return list == null || list.isEmpty() ? null : list.get(0);
			}
			case "last" -> {
				expect(call, 1);
				List<?> list = listArg("last", args.get(0));
				return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
			}
			case "tail" -> {
				expect(call, 1);
				List<?> list = listArg("tail", args.get(0));
				return list == null ? null
					: new ArrayList<Object>(list.subList(Math.min(1, list.size()), list.size()));
			}
			case "reverse" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				if (args.get(0) instanceof String string) {
					return new StringBuilder(string).reverse().toString();
				}
				List<Object> copy = new ArrayList<>(listArg("reverse", args.get(0)));
				java.util.Collections.reverse(copy);
				return copy;
			}
			case "range" -> {
				if (call.arguments().size() < 2 || call.arguments().size() > 3) {
					throw new CypherTypeException("range() takes two or three arguments");
				}
				long from = Values.number(args.get(0), "range").longValue();
				long to = Values.number(args.get(1), "range").longValue();
				long step = args.size() > 2 ? Values.number(args.get(2), "range").longValue() : 1;
				if (step == 0) {
					throw new CypherTypeException("range() step cannot be zero");
				}
				List<Object> out = new ArrayList<>();
				// Inclusive of both bounds, as in Cypher: range(1,3) is [1,2,3].
				for (long i = from; step > 0 ? i <= to : i >= to; i += step) {
					out.add(i);
				}
				return out;
			}
			case "exists" -> {
				expect(call, 1);
				return args.get(0) != null;
			}
			case "tostring" -> {
				expect(call, 1);
				return args.get(0) == null ? null : Values.stringOf(args.get(0));
			}
			case "toupper", "upper" -> {
				expect(call, 1);
				return args.get(0) == null ? null : stringArg(name, args.get(0)).toUpperCase(Locale.ROOT);
			}
			case "tolower", "lower" -> {
				expect(call, 1);
				return args.get(0) == null ? null : stringArg(name, args.get(0)).toLowerCase(Locale.ROOT);
			}
			case "trim" -> {
				expect(call, 1);
				return args.get(0) == null ? null : stringArg("trim", args.get(0)).strip();
			}
			case "ltrim" -> {
				expect(call, 1);
				return args.get(0) == null ? null : stringArg("ltrim", args.get(0)).stripLeading();
			}
			case "rtrim" -> {
				expect(call, 1);
				return args.get(0) == null ? null : stringArg("rtrim", args.get(0)).stripTrailing();
			}
			case "substring" -> {
				if (call.arguments().size() < 2 || call.arguments().size() > 3) {
					throw new CypherTypeException("substring() takes two or three arguments");
				}
				if (args.get(0) == null) {
					return null;
				}
				String string = stringArg("substring", args.get(0));
				int start = (int) Values.number(args.get(1), "substring").longValue();
				if (start < 0 || start > string.length()) {
					throw new CypherTypeException("substring() start " + start + " is outside the string");
				}
				if (args.size() < 3) {
					return string.substring(start);
				}
				int length = (int) Values.number(args.get(2), "substring").longValue();
				return string.substring(start, Math.min(string.length(), start + Math.max(0, length)));
			}
			case "replace" -> {
				expect(call, 3);
				if (args.get(0) == null || args.get(1) == null || args.get(2) == null) {
					return null;
				}
				return stringArg("replace", args.get(0))
					.replace(stringArg("replace", args.get(1)), stringArg("replace", args.get(2)));
			}
			case "split" -> {
				expect(call, 2);
				if (args.get(0) == null || args.get(1) == null) {
					return null;
				}
				String[] parts = stringArg("split", args.get(0))
					.split(java.util.regex.Pattern.quote(stringArg("split", args.get(1))), -1);
				return new ArrayList<Object>(List.of(parts));
			}
			case "tointeger" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					case Number number -> number.longValue();
					case Boolean b -> b ? 1L : 0L;
					// A string that is not a number is null rather than an error: toInteger is a coercion attempt.
					case String string -> parseLong(string);
					default -> throw typeError("toInteger", args.get(0));
				};
			}
			case "tofloat" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					case Number number -> number.doubleValue();
					case String string -> parseDouble(string);
					default -> throw typeError("toFloat", args.get(0));
				};
			}
			case "toboolean" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					case Boolean b -> b;
					case String string -> switch (string.toLowerCase(Locale.ROOT)) {
						case "true" -> Boolean.TRUE;
						case "false" -> Boolean.FALSE;
						default -> null;
					};
					default -> throw typeError("toBoolean", args.get(0));
				};
			}
			case "abs" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				Number number = Values.number(args.get(0), "abs");
				return Values.isIntegral(number) ? (Object) Math.abs(number.longValue())
					: (Object) Math.abs(number.doubleValue());
			}
			case "ceil" -> {
				expect(call, 1);
				return args.get(0) == null ? null : Math.ceil(Values.number(args.get(0), "ceil").doubleValue());
			}
			case "floor" -> {
				expect(call, 1);
				return args.get(0) == null ? null : Math.floor(Values.number(args.get(0), "floor").doubleValue());
			}
			case "round" -> {
				expect(call, 1);
				return args.get(0) == null ? null
					: (double) Math.round(Values.number(args.get(0), "round").doubleValue());
			}
			case "sqrt" -> {
				expect(call, 1);
				return args.get(0) == null ? null : Math.sqrt(Values.number(args.get(0), "sqrt").doubleValue());
			}
			case "sign" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				double value = Values.number(args.get(0), "sign").doubleValue();
				return (long) Math.signum(value);
			}
			default -> throw new CypherTypeException("Unknown function " + call.name() + "()");
		}
	}

	private static Long parseLong(String value) {
		try {
			return Long.valueOf(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Double parseDouble(String value) {
		try {
			return Double.valueOf(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static List<?> listArg(String function, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof List<?> list) {
			return list;
		}
		throw typeError(function, value);
	}

	private static String stringArg(String function, Object value) {
		if (value instanceof String string) {
			return string;
		}
		throw typeError(function, value);
	}

	private static CypherTypeException typeError(String function, Object value) {
		return new CypherTypeException(function + "() cannot be applied to " + Values.typeName(value));
	}

	private static void expect(Ast.FunctionCall call, int arity) {
		if (call.arguments().size() != arity) {
			throw new CypherTypeException(call.name() + "() takes " + arity + " argument"
				+ (arity == 1 ? "" : "s") + " but got " + call.arguments().size());
		}
	}
}
