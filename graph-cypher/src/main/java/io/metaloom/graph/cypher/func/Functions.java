package io.metaloom.graph.cypher.func;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.core.api.VectorMetric;
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

	/**
	 * The most elements one {@code range()} may build. A list is materialised and boxed, so without a bound
	 * {@code range(1, 2000000000)} is an OutOfMemoryError for every query sharing the process, not a slow answer.
	 */
	public static final long MAX_RANGE = 10_000_000;

	/** Recognised by the planner, computed by the aggregation operator. */
	public static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "collect");

	private static final Set<String> SCALARS = Set.of(
		"id", "labels", "type", "keys", "properties", "startnode", "endnode", "length", "size",
		"head", "last", "tail", "range", "coalesce", "timestamp", "exists",
		"tostring", "toupper", "tolower", "upper", "lower", "trim", "ltrim", "rtrim",
		"substring", "replace", "split", "reverse",
		"tointeger", "tofloat", "toboolean", "abs", "ceil", "floor", "round", "sqrt", "sign", "nodes",
		"relationships",
		// Namespaced, which the parser folds back together — see CypherParser.postfix. They are here rather than
		// behind an index because they are the ground truth an approximate index is measured against, and because
		// re-ranking a small candidate set is worth having on its own.
		"vector.cosine", "vector.dot", "vector.l2");

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
		return apply(evaluator, name, call, args);
	}

	private static Object apply(Evaluator evaluator, String name, Ast.FunctionCall call, List<Object> args) {
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
					// The node, not its id, so that `startNode(r) = a` and `startNode(r).name` mean what they say.
					return evaluator.graph().node(relationship.startId());
				}
				throw typeError("startNode", args.get(0));
			}
			case "endnode" -> {
				expect(call, 1);
				if (args.get(0) == null) {
					return null;
				}
				if (args.get(0) instanceof CypherRelationship relationship) {
					return evaluator.graph().node(relationship.endId());
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
					case String string -> (long) codePoints(string);
					case List<?> list -> (long) list.size();
					default -> throw typeError("length", args.get(0));
				};
			}
			case "size" -> {
				expect(call, 1);
				return switch (args.get(0)) {
					case null -> null;
					// Characters, not UTF-16 units: an emoji is one, as it is in substring() and everywhere else.
					case String string -> (long) codePoints(string);
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
				if (step > 0 ? from > to : from < to) {
					return new ArrayList<>();
				}
				// Counted up front, unsigned, rather than by stepping until past the bound: near Long.MAX_VALUE the
				// step wraps and that loop never ends.
				long span = step > 0 ? to - from : from - to;
				long steps = Long.divideUnsigned(span, step > 0 ? step : -step);
				if (Long.compareUnsigned(steps, MAX_RANGE) >= 0) {
					throw new CypherTypeException("range() would produce more than " + MAX_RANGE
						+ " elements, which is more than a query may build as one list");
				}
				List<Object> out = new ArrayList<>((int) steps + 1);
				// Inclusive of both bounds, as in Cypher: range(1,3) is [1,2,3].
				for (long i = 0; i <= steps; i++) {
					if ((i & 1023) == 1023) {
						evaluator.graph().checkAbortNow();
					}
					out.add(from + i * step);
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
				// In code points, as size() counts them. In UTF-16 units, substring('😀x', 1) cut the emoji in half and
				// returned a lone surrogate — a string with no UTF-8 form, which the store then refused to hold.
				int characters = codePoints(string);
				// Compared as longs and only then narrowed: an (int) cast first turns 4294967297 into 1.
				long start = Values.number(args.get(1), "substring").longValue();
				if (start < 0 || start > characters) {
					throw new CypherTypeException("substring() start " + start + " is outside the string");
				}
				long end = characters;
				if (args.size() >= 3) {
					long length = Math.max(0, Values.number(args.get(2), "substring").longValue());
					end = start + Math.min(length, characters - start);
				}
				int from = string.offsetByCodePoints(0, (int) start);
				return string.substring(from, string.offsetByCodePoints(from, (int) (end - start)));
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
				String string = stringArg("split", args.get(0));
				String separator = stringArg("split", args.get(1));
				if (separator.isEmpty() && !string.isEmpty()) {
					// One element per character. String.split on an empty pattern adds a trailing empty string, and
					// splits between UTF-16 units rather than characters.
					List<Object> characters = new ArrayList<>(string.length());
					string.codePoints().forEach(c -> characters.add(Character.toString(c)));
					return characters;
				}
				String[] parts = string.split(java.util.regex.Pattern.quote(separator), -1);
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
					// As in Neo4j 5: zero is false and any other integer true. A float has no such convention.
					case Number number when Values.isIntegral(number) -> number.longValue() != 0;
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
				return Values.isIntegral(number) ? (Object) Values.exact("abs", () -> Math.absExact(number.longValue()))
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
				if (args.get(0) == null) {
					return null;
				}
				double value = Values.number(args.get(0), "round").doubleValue();
				// Math.round answers in a long: NaN became 0.0 and 1e300 became 9.2e18. From 2^52 up every double is
				// already a whole number, and NaN and the infinities round to themselves.
				return Double.isNaN(value) || Math.abs(value) >= 0x1p52 ? value : (double) Math.round(value);
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
			case "vector.cosine" -> {
				expect(call, 2);
				return distance(call, args, VectorMetric.COSINE);
			}
			case "vector.dot" -> {
				expect(call, 2);
				return distance(call, args, VectorMetric.DOT);
			}
			case "vector.l2" -> {
				expect(call, 2);
				return distance(call, args, VectorMetric.EUCLIDEAN);
			}
			default -> throw new CypherTypeException("Unknown function " + call.name() + "()");
		}
	}

	/**
	 * One of the three vector functions. Null in, null out, as everywhere else in the language — a node that simply has
	 * no embedding must not fail the query for every node that does.
	 */
	private static Object distance(Ast.FunctionCall call, List<Object> args, VectorMetric metric) {
		float[] a = vectorArg(call.name(), args.get(0));
		float[] b = vectorArg(call.name(), args.get(1));
		if (a == null || b == null) {
			return null;
		}
		if (a.length != b.length) {
			// Not null, and not a silent zero. Two vectors of different lengths are incomparable, and the overwhelmingly
			// likely cause is that a query vector was written for a different model than the stored one.
			throw new CypherTypeException(call.name() + "() cannot compare a vector of " + a.length
				+ " dimensions with one of " + b.length);
		}
		return metric.score(a, b);
	}

	/**
	 * A vector argument, from either shape one can arrive in: a {@code float[]} passed straight through as a parameter,
	 * or the list of numbers a stored vector becomes on its way through {@code GraphAccess.normalise}. A list of
	 * integers counts — {@code [1, 2, 3]} written literally in a query is a list of longs.
	 */
	/** The same coercion, for the procedure mechanism, which takes a vector argument for the same two shapes. */
	public static float[] vectorArgument(Object value, String where) {
		return vectorArg(where, value);
	}

	private static float[] vectorArg(String function, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof float[] vector) {
			return vector;
		}
		if (value instanceof List<?> list) {
			float[] vector = new float[list.size()];
			for (int i = 0; i < vector.length; i++) {
				Object element = list.get(i);
				if (element == null) {
					throw new CypherTypeException(function + "() cannot take a vector holding a null at index " + i);
				}
				vector[i] = Values.number(element, function).floatValue();
			}
			return vector;
		}
		throw typeError(function, value);
	}

	/**
	 * A string as an integer: an integer literal, or a float literal truncated toward zero exactly as
	 * {@code toInteger(2.7)} would be — so {@code toInteger('2.7')} is 2 rather than null. A float outside the 64-bit
	 * range is not a number this can return, and is null.
	 */
	private static Long parseLong(String value) {
		String text = value.trim();
		try {
			return Long.valueOf(text);
		} catch (NumberFormatException e) {
			Double floating = parseDouble(text);
			if (floating == null || floating.isNaN() || floating >= 0x1p63 || floating < -0x1p63) {
				return null;
			}
			return floating.longValue();
		}
	}

	/**
	 * Decimal notation, NaN and Infinity — which is what {@code toString} of a float can produce, so the round trip
	 * holds. Java's own parser also takes a {@code d} or {@code f} suffix and hexadecimal floats, so without the check
	 * {@code toFloat('1.5f')} was 1.5 rather than the null a string that is not a number gives.
	 */
	private static final java.util.regex.Pattern DECIMAL = java.util.regex.Pattern
		.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?|[+-]?(NaN|Infinity)");

	private static Double parseDouble(String value) {
		String text = value.trim();
		if (!DECIMAL.matcher(text).matches()) {
			return null;
		}
		try {
			return Double.valueOf(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Characters rather than UTF-16 units. */
	private static int codePoints(String string) {
		return string.codePointCount(0, string.length());
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
