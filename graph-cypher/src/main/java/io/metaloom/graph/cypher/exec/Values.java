package io.metaloom.graph.cypher.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * Cypher's value semantics: equality, ordering, truth, and the arithmetic of mixed numeric types.
 *
 * <p>
 * <b>This is the file to be suspicious of.</b> Cypher is three-valued, and almost every operation has a null case that
 * differs from Java's:
 * </p>
 *
 * <ul>
 * <li>{@code null = null} is <b>null</b>, not true. So is {@code null &lt;&gt; null}.</li>
 * <li>Any comparison involving null is null.</li>
 * <li>{@code AND}/{@code OR} follow Kleene logic: {@code false AND null} is <b>false</b>, but {@code true AND null} is
 * null; {@code true OR null} is <b>true</b>, but {@code false OR null} is null.</li>
 * <li>{@code WHERE} keeps a row only when the predicate is exactly {@code TRUE}; null is dropped, like false.</li>
 * <li>{@code x IN list} is null when x is null <em>or</em> when x is absent and the list contains a null, because then
 * the answer genuinely is not known.</li>
 * </ul>
 *
 * <p>
 * Getting one of these wrong does not throw; it silently returns rows the query did not ask for, or drops rows it did.
 * {@code NullSemanticsTest} exists for this file alone.
 * </p>
 */
public final class Values {

	private Values() {
	}

	// ---------------------------------------------------------------- truth

	/**
	 * Whether a predicate keeps a row.
	 * <p>
	 * Null is not an error and not true. A filter is the one place where the third value collapses back to two, and it
	 * collapses towards dropping the row.
	 */
	public static boolean isTrue(Object value) {
		return Boolean.TRUE.equals(value);
	}

	/** Kleene AND. Null only survives when neither side is decisively false. */
	public static Object and(Object left, Object right) {
		if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
			return Boolean.FALSE;
		}
		if (left == null || right == null) {
			return null;
		}
		return asBoolean(left, "AND") && asBoolean(right, "AND");
	}

	/** Kleene OR. Null only survives when neither side is decisively true. */
	public static Object or(Object left, Object right) {
		if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
			return Boolean.TRUE;
		}
		if (left == null || right == null) {
			return null;
		}
		return asBoolean(left, "OR") || asBoolean(right, "OR");
	}

	public static Object xor(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		return asBoolean(left, "XOR") ^ asBoolean(right, "XOR");
	}

	public static Object not(Object value) {
		if (value == null) {
			return null;
		}
		return !asBoolean(value, "NOT");
	}

	private static boolean asBoolean(Object value, String operator) {
		if (value instanceof Boolean b) {
			return b;
		}
		throw new CypherTypeException(operator + " expects a boolean but got " + typeName(value));
	}

	// ---------------------------------------------------------------- equality

	/**
	 * Cypher equality, which returns null rather than false when either side is null.
	 *
	 * <p>
	 * Numbers compare across types — {@code 1 = 1.0} is true — because a property read back from storage may be a Long
	 * where the literal in the query is a Double, and a user has no way to know which.
	 * </p>
	 */
	public static Object equal(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		// A null inside a list or map is as unknown as one outside it: [1, null] = [1, 2] is null, but a known
		// difference anywhere still decides it, so [1, null] = [2, null] is false.
		if (left instanceof List<?> a && right instanceof List<?> b) {
			if (a.size() != b.size()) {
				return Boolean.FALSE;
			}
			boolean unknown = false;
			for (int i = 0; i < a.size(); i++) {
				Object element = equal(a.get(i), b.get(i));
				if (Boolean.FALSE.equals(element)) {
					return Boolean.FALSE;
				}
				unknown |= element == null;
			}
			return unknown ? null : Boolean.TRUE;
		}
		if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
			if (!a.keySet().equals(b.keySet())) {
				return Boolean.FALSE;
			}
			boolean unknown = false;
			for (Map.Entry<?, ?> entry : a.entrySet()) {
				Object element = equal(entry.getValue(), b.get(entry.getKey()));
				if (Boolean.FALSE.equals(element)) {
					return Boolean.FALSE;
				}
				unknown |= element == null;
			}
			return unknown ? null : Boolean.TRUE;
		}
		return equalKnown(left, right);
	}

	/** Equality where both sides are known non-null. Used by DISTINCT and grouping, which need two-valued answers. */
	public static boolean equalKnown(Object left, Object right) {
		if (left instanceof Number a && right instanceof Number b) {
			return numbersEqual(a, b);
		}
		if (left instanceof List<?> a && right instanceof List<?> b) {
			if (a.size() != b.size()) {
				return false;
			}
			for (int i = 0; i < a.size(); i++) {
				if (!sameOrBothNull(a.get(i), b.get(i))) {
					return false;
				}
			}
			return true;
		}
		if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
			if (!a.keySet().equals(b.keySet())) {
				return false;
			}
			for (Map.Entry<?, ?> entry : a.entrySet()) {
				if (!sameOrBothNull(entry.getValue(), b.get(entry.getKey()))) {
					return false;
				}
			}
			return true;
		}
		if (left instanceof CypherNode a && right instanceof CypherNode b) {
			// Identity, not contents: two reads of the same node are the same node.
			return a.id() == b.id();
		}
		if (left instanceof CypherRelationship a && right instanceof CypherRelationship b) {
			return a.id() != null && a.id().equals(b.id())
				|| a.id() == null && b.id() == null && a.startId() == b.startId() && a.endId() == b.endId()
					&& a.type().equals(b.type());
		}
		if (left instanceof CypherPath a && right instanceof CypherPath b) {
			// By the identity of what it crosses, as its elements are compared: the record's own equals would compare
			// property snapshots, so two reads of one path taken either side of a SET would differ.
			return equalKnown(new ArrayList<Object>(a.nodes()), new ArrayList<Object>(b.nodes()))
				&& equalKnown(new ArrayList<Object>(a.relationships()), new ArrayList<Object>(b.relationships()));
		}
		return left.equals(right);
	}

	/**
	 * Numeric equality, exact across Integer and Float.
	 *
	 * <p>
	 * Not by widening the integer to a double: above 2^53 that rounds, so {@code 9007199254740993 = 9007199254740992.0}
	 * came out true — and since grouping keys an integral float as the integer it is exactly equal to, DISTINCT and
	 * {@code =} then disagreed about the same two values.
	 * </p>
	 */
	public static boolean numbersEqual(Number a, Number b) {
		boolean integralA = isIntegral(a);
		boolean integralB = isIntegral(b);
		if (integralA && integralB) {
			return a.longValue() == b.longValue();
		}
		if (!integralA && !integralB) {
			return a.doubleValue() == b.doubleValue();
		}
		double floating = integralA ? b.doubleValue() : a.doubleValue();
		return !Double.isNaN(floating) && compareExact(integralA ? a.longValue() : b.longValue(), floating) == 0;
	}

	/**
	 * Numeric order, exact across Integer and Float, with Double.compare's placement of NaN (above everything) and of
	 * -0.0 (below 0.0) when both are floats. A total order, which a sort needs: comparing an integer with a float by
	 * widening it is not transitive near 2^53, and a non-transitive comparator is one TimSort may refuse outright.
	 */
	public static int compareNumbers(Number a, Number b) {
		boolean integralA = isIntegral(a);
		boolean integralB = isIntegral(b);
		if (integralA && integralB) {
			return Long.compare(a.longValue(), b.longValue());
		}
		if (!integralA && !integralB) {
			return Double.compare(a.doubleValue(), b.doubleValue());
		}
		return integralA ? compareExact(a.longValue(), b.doubleValue()) : -compareExact(b.longValue(), a.doubleValue());
	}

	/** A long against a double, without rounding either. NaN is above every long, as Double.compare places it. */
	private static int compareExact(long integer, double floating) {
		if (Double.isNaN(floating) || floating >= 0x1p63) {
			return -1;
		}
		if (floating < -0x1p63) {
			return 1;
		}
		// In range, so the truncation is exact, and so is the fraction: the integer part of a double is a double.
		long whole = (long) floating;
		if (integer != whole) {
			return Long.compare(integer, whole);
		}
		double fraction = floating - whole;
		return fraction > 0 ? -1 : fraction < 0 ? 1 : 0;
	}

	private static boolean sameOrBothNull(Object left, Object right) {
		return left == null || right == null ? left == right : equalKnown(left, right);
	}

	/**
	 * A key with Java equality and hashing that agree with Cypher equivalence, for grouping and DISTINCT.
	 *
	 * <p>
	 * Equivalence is equality with two changes: null is equivalent to null, and NaN to NaN. An integral float becomes
	 * the integer it equals, so 1 and 1.0 land in one bucket; a node or a relationship becomes its identity, because two
	 * reads of one element are one element whatever properties each snapshot holds.
	 * </p>
	 */
	public static Object groupingKey(Object value) {
		return switch (value) {
			case null -> null;
			case Double d -> integralOrSelf(d);
			case Float f -> integralOrSelf(f.doubleValue());
			case Integer i -> i.longValue();
			case Short s -> s.longValue();
			case Byte b -> b.longValue();
			case CypherNode node -> new ElementKey("node", node.id());
			case CypherRelationship relationship -> relationship.id() != null
				? new ElementKey("relationship", relationship.id())
				: new ElementKey("edge", List.of(relationship.startId(), relationship.type(), relationship.endId()));
			case CypherPath path -> new ElementKey("path",
				List.of(groupingKey(path.nodes()), groupingKey(path.relationships())));
			case List<?> list -> {
				List<Object> out = new ArrayList<>(list.size());
				for (Object element : list) {
					out.add(groupingKey(element));
				}
				yield out;
			}
			case Map<?, ?> map -> {
				Map<Object, Object> out = new java.util.HashMap<>();
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					out.put(entry.getKey(), groupingKey(entry.getValue()));
				}
				yield out;
			}
			default -> value;
		};
	}

	/** A record rather than a list, so that no list a query builds can collide with an element's identity. */
	private record ElementKey(String kind, Object identity) {
	}

	private static Object integralOrSelf(double value) {
		// -0.0 is 0 here too, as it is under =.
		if (value == Math.rint(value) && value >= -0x1p63 && value < 0x1p63) {
			return (long) value;
		}
		return value;
	}

	public static Object notEqual(Object left, Object right) {
		Object equal = equal(left, right);
		return equal == null ? null : !(Boolean) equal;
	}

	// ---------------------------------------------------------------- ordering

	/** {@code <}, {@code <=}, {@code >}, {@code >=} — null in, null out, and incomparable types are null too. */
	public static Object compare(Object left, Object right, java.util.function.IntPredicate accept) {
		if (left == null || right == null) {
			return null;
		}
		if (left instanceof Number a && right instanceof Number b && (isNaN(a) || isNaN(b))) {
			// NaN is neither less than, equal to nor greater than anything.
			return Boolean.FALSE;
		}
		Integer order = comparison(left, right);
		return order == null ? null : accept.test(order);
	}

	private static boolean isNaN(Number number) {
		return !isIntegral(number) && Double.isNaN(number.doubleValue());
	}

	/**
	 * The order {@code <} and friends see: null when the two cannot be compared.
	 *
	 * <p>
	 * Lists compare element by element and inherit their elements' unknowns, so {@code [1] < ['a']} and
	 * {@code [1, null] < [1, 2]} are null — but an earlier element that already differs still decides, so
	 * {@code [1, null] < [2, null]} is true. The sort order below must not be used here: it orders every pair, which is
	 * what a sort needs and exactly what a predicate must not do.
	 * </p>
	 */
	private static Integer comparison(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		if (left instanceof Number a && right instanceof Number b) {
			if (isIntegral(a) && isIntegral(b)) {
				return Long.compare(a.longValue(), b.longValue());
			}
			if (isNaN(a) || isNaN(b)) {
				return null;
			}
			if (!isIntegral(a) && !isIntegral(b)) {
				// IEEE rather than Double.compare, whose total order is for sorting: -0.0 < 0.0 is false.
				double x = a.doubleValue();
				double y = b.doubleValue();
				return x < y ? -1 : x > y ? 1 : 0;
			}
			return compareNumbers(a, b);
		}
		if (left instanceof String a && right instanceof String b) {
			return a.compareTo(b);
		}
		if (left instanceof Boolean a && right instanceof Boolean b) {
			return Boolean.compare(a, b);
		}
		if (left instanceof List<?> a && right instanceof List<?> b) {
			int shared = Math.min(a.size(), b.size());
			for (int i = 0; i < shared; i++) {
				Integer element = comparison(a.get(i), b.get(i));
				if (element == null) {
					return null;
				}
				if (element != 0) {
					return element;
				}
			}
			return Integer.compare(a.size(), b.size());
		}
		return null;
	}

	/**
	 * The total order ORDER BY, {@code min} and {@code max} use: openCypher's orderability.
	 *
	 * <p>
	 * Unlike types order as map, node, relationship, list, path, string, boolean, number, and null last — the order
	 * openCypher and Neo4j define, so a mixed column sorts the same here as elsewhere. Within a type: numbers exactly
	 * across Integer and Float with NaN above every number, nodes and relationships by identity, lists and paths element
	 * by element. ORDER BY has to be a total order or the sort is undefined, so null goes somewhere definite: last
	 * ascending.
	 * </p>
	 */
	public static int orderForSort(Object left, Object right) {
		if (left == null && right == null) {
			return 0;
		}
		if (left == null) {
			return 1;
		}
		if (right == null) {
			return -1;
		}
		int leftRank = orderabilityRank(left);
		int rightRank = orderabilityRank(right);
		if (leftRank != rightRank) {
			return Integer.compare(leftRank, rightRank);
		}
		return switch (left) {
			case Number a -> compareNumbers(a, (Number) right);
			case String a -> a.compareTo((String) right);
			case Boolean a -> Boolean.compare(a, (Boolean) right);
			case List<?> a -> orderLists(a, (List<?>) right);
			case CypherNode a -> Long.compare(a.id(), ((CypherNode) right).id());
			case CypherRelationship a -> orderRelationships(a, (CypherRelationship) right);
			case CypherPath a -> {
				int nodes = orderLists(a.nodes(), ((CypherPath) right).nodes());
				yield nodes != 0 ? nodes : orderLists(a.relationships(), ((CypherPath) right).relationships());
			}
			case Map<?, ?> a -> orderMaps(a, (Map<?, ?>) right);
			default -> typeName(left).compareTo(typeName(right));
		};
	}

	private static int orderabilityRank(Object value) {
		return switch (value) {
			case Map<?, ?> ignored -> 0;
			case CypherNode ignored -> 1;
			case CypherRelationship ignored -> 2;
			case List<?> ignored -> 3;
			case CypherPath ignored -> 4;
			case String ignored -> 5;
			case Boolean ignored -> 6;
			case Number ignored -> 7;
			// Anything else a parameter can carry — a float[] query vector — after every Cypher type, before null.
			default -> 8;
		};
	}

	private static int orderLists(List<?> a, List<?> b) {
		int shared = Math.min(a.size(), b.size());
		for (int i = 0; i < shared; i++) {
			int element = orderForSort(a.get(i), b.get(i));
			if (element != 0) {
				return element;
			}
		}
		return Integer.compare(a.size(), b.size());
	}

	private static int orderRelationships(CypherRelationship a, CypherRelationship b) {
		if (a.id() != null && b.id() != null) {
			return Long.compare(a.id(), b.id());
		}
		if (a.id() != null || b.id() != null) {
			// A lightweight edge has no id; it sorts after every materialised one, by what does identify it.
			return a.id() != null ? -1 : 1;
		}
		int order = Long.compare(a.startId(), b.startId());
		if (order == 0) {
			order = a.type().compareTo(b.type());
		}
		return order != 0 ? order : Long.compare(a.endId(), b.endId());
	}

	/** Maps by their entries in key order: key first, then value, then the shorter map first. */
	private static int orderMaps(Map<?, ?> a, Map<?, ?> b) {
		List<String> left = sortedKeys(a);
		List<String> right = sortedKeys(b);
		int shared = Math.min(left.size(), right.size());
		for (int i = 0; i < shared; i++) {
			int key = left.get(i).compareTo(right.get(i));
			if (key != 0) {
				return key;
			}
			int value = orderForSort(a.get(left.get(i)), b.get(right.get(i)));
			if (value != 0) {
				return value;
			}
		}
		return Integer.compare(left.size(), right.size());
	}

	private static List<String> sortedKeys(Map<?, ?> map) {
		List<String> keys = new ArrayList<>(map.size());
		for (Object key : map.keySet()) {
			keys.add(String.valueOf(key));
		}
		java.util.Collections.sort(keys);
		return keys;
	}

	// ---------------------------------------------------------------- membership and strings

	/**
	 * {@code x IN list}.
	 *
	 * <p>
	 * The subtle case is a list containing null: {@code 1 IN [2, null]} is <b>null</b>, not false, because the null
	 * might have been a 1. But {@code 1 IN [1, null]} is true — a match found is a match regardless.
	 * </p>
	 */
	public static Object in(Object needle, Object haystack) {
		if (haystack == null) {
			return null;
		}
		if (!(haystack instanceof Collection<?> list)) {
			throw new CypherTypeException("IN expects a list on the right but got " + typeName(haystack));
		}
		if (needle == null) {
			return list.isEmpty() ? Boolean.FALSE : null;
		}
		// Each candidate under =, so a null nested inside one is as unknown here as it is there:
		// [1, null] IN [[1, 2]] might have been true, and is null.
		boolean unknown = false;
		for (Object candidate : list) {
			Object equal = equal(needle, candidate);
			if (Boolean.TRUE.equals(equal)) {
				return Boolean.TRUE;
			}
			unknown |= equal == null;
		}
		return unknown ? null : Boolean.FALSE;
	}

	public static Object startsWith(Object left, Object right) {
		return stringPredicate(left, right, String::startsWith);
	}

	public static Object endsWith(Object left, Object right) {
		return stringPredicate(left, right, String::endsWith);
	}

	public static Object contains(Object left, Object right) {
		return stringPredicate(left, right, String::contains);
	}

	private static Object stringPredicate(Object left, Object right,
		java.util.function.BiPredicate<String, String> test) {
		if (left == null || right == null) {
			return null;
		}
		if (!(left instanceof String a) || !(right instanceof String b)) {
			// Not an error: Cypher yields null when a string predicate is applied to a non-string, so a heterogeneous
			// property does not blow the query up.
			return null;
		}
		return test.test(a, b);
	}

	// ---------------------------------------------------------------- arithmetic

	public static Object add(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		// A list on either side makes this list concatenation, and that is decided before strings: [1, 2] + 'x' is
		// [1, 2, 'x'], where testing for a string first rendered the list into "[1, 2]x".
		if (left instanceof List<?> a && right instanceof List<?> b) {
			List<Object> joined = new ArrayList<>(a.size() + b.size());
			joined.addAll(a);
			joined.addAll(b);
			return joined;
		}
		if (left instanceof List<?> a) {
			List<Object> joined = new ArrayList<>(a.size() + 1);
			joined.addAll(a);
			joined.add(right);
			return joined;
		}
		if (right instanceof List<?> b) {
			List<Object> joined = new ArrayList<>(b.size() + 1);
			joined.add(left);
			joined.addAll(b);
			return joined;
		}
		// String concatenation shares the operator, and a number joined to a string is a string.
		if (left instanceof String || right instanceof String) {
			return stringOf(left) + stringOf(right);
		}
		Number a = number(left, "+");
		Number b = number(right, "+");
		return isIntegral(a) && isIntegral(b)
			? (Object) exact("+", () -> Math.addExact(a.longValue(), b.longValue()))
			: (Object) (a.doubleValue() + b.doubleValue());
	}

	public static Object subtract(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		Number a = number(left, "-");
		Number b = number(right, "-");
		return isIntegral(a) && isIntegral(b)
			? (Object) exact("-", () -> Math.subtractExact(a.longValue(), b.longValue()))
			: (Object) (a.doubleValue() - b.doubleValue());
	}

	public static Object multiply(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		Number a = number(left, "*");
		Number b = number(right, "*");
		return isIntegral(a) && isIntegral(b)
			? (Object) exact("*", () -> Math.multiplyExact(a.longValue(), b.longValue()))
			: (Object) (a.doubleValue() * b.doubleValue());
	}

	public static Object divide(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		Number a = number(left, "/");
		Number b = number(right, "/");
		if (isIntegral(a) && isIntegral(b)) {
			if (b.longValue() == 0) {
				throw new CypherTypeException("/ by zero");
			}
			return exact("/", () -> Math.divideExact(a.longValue(), b.longValue()));
		}
		return a.doubleValue() / b.doubleValue();
	}

	public static Object modulo(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		Number a = number(left, "%");
		Number b = number(right, "%");
		if (isIntegral(a) && isIntegral(b)) {
			if (b.longValue() == 0) {
				throw new CypherTypeException("% by zero");
			}
			return a.longValue() % b.longValue();
		}
		return a.doubleValue() % b.doubleValue();
	}

	public static Object power(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		// Always a double, as in Cypher: 2^3 is 8.0.
		return Math.pow(number(left, "^").doubleValue(), number(right, "^").doubleValue());
	}

	public static Object negate(Object value) {
		if (value == null) {
			return null;
		}
		Number number = number(value, "-");
		return isIntegral(number) ? (Object) exact("-", () -> Math.negateExact(number.longValue()))
			: (Object) (-number.doubleValue());
	}

	// ---------------------------------------------------------------- helpers

	/** 64-bit integer arithmetic that refuses to wrap: a silently negative sum is a wrong answer that throws nothing. */
	public static long exact(String operator, java.util.function.LongSupplier operation) {
		try {
			return operation.getAsLong();
		} catch (ArithmeticException e) {
			throw new CypherTypeException("Integer overflow in " + operator
				+ ": the result does not fit in 64 bits");
		}
	}

	public static boolean isIntegral(Number number) {
		return number instanceof Long || number instanceof Integer || number instanceof Short
			|| number instanceof Byte;
	}

	public static Number number(Object value, String operator) {
		if (value instanceof Number number) {
			return number;
		}
		throw new CypherTypeException(operator + " expects a number but got " + typeName(value));
	}

	/** How a value renders inside string concatenation and {@code toString}. */
	public static String stringOf(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
			// Cypher prints 1.0 rather than Java's 1.0 for some values and 1 for others; keep it stable.
			return d.toString();
		}
		return value.toString();
	}

	public static String typeName(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof CypherNode) {
			return "Node";
		}
		if (value instanceof CypherRelationship) {
			return "Relationship";
		}
		if (value instanceof CypherPath) {
			return "Path";
		}
		if (value instanceof List) {
			return "List";
		}
		if (value instanceof Map) {
			return "Map";
		}
		if (value instanceof String) {
			return "String";
		}
		if (value instanceof Boolean) {
			return "Boolean";
		}
		if (value instanceof Number number) {
			return isIntegral(number) ? "Integer" : "Float";
		}
		return value.getClass().getSimpleName();
	}
}
