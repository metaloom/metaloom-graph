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
		return equalKnown(left, right);
	}

	/** Equality where both sides are known non-null. Used by DISTINCT and grouping, which need two-valued answers. */
	public static boolean equalKnown(Object left, Object right) {
		if (left instanceof Number a && right instanceof Number b) {
			if (isIntegral(a) && isIntegral(b)) {
				return a.longValue() == b.longValue();
			}
			return a.doubleValue() == b.doubleValue();
		}
		if (left instanceof List<?> a && right instanceof List<?> b) {
			if (a.size() != b.size()) {
				return false;
			}
			for (int i = 0; i < a.size(); i++) {
				Object x = a.get(i);
				Object y = b.get(i);
				if (x == null || y == null) {
					if (x != y) {
						return false;
					}
					continue;
				}
				if (!equalKnown(x, y)) {
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
		return left.equals(right);
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
		Integer order = order(left, right);
		return order == null ? null : accept.test(order);
	}

	/** A total order for ORDER BY, where null sorts last and unlike types are ordered by type name. */
	public static int orderForSort(Object left, Object right) {
		if (left == null && right == null) {
			return 0;
		}
		// ORDER BY has to be a total order or the sort is undefined, so nulls go somewhere definite: last ascending.
		if (left == null) {
			return 1;
		}
		if (right == null) {
			return -1;
		}
		Integer order = order(left, right);
		return order != null ? order : typeName(left).compareTo(typeName(right));
	}

	/** Comparison of two non-null values, or null when they are not comparable. */
	private static Integer order(Object left, Object right) {
		if (left instanceof Number a && right instanceof Number b) {
			if (isIntegral(a) && isIntegral(b)) {
				return Long.compare(a.longValue(), b.longValue());
			}
			return Double.compare(a.doubleValue(), b.doubleValue());
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
				int element = orderForSort(a.get(i), b.get(i));
				if (element != 0) {
					return element;
				}
			}
			return Integer.compare(a.size(), b.size());
		}
		return null;
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
		boolean sawNull = false;
		for (Object candidate : list) {
			if (candidate == null) {
				sawNull = true;
				continue;
			}
			if (equalKnown(needle, candidate)) {
				return Boolean.TRUE;
			}
		}
		return sawNull ? null : Boolean.FALSE;
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
		// String concatenation shares the operator, and a number joined to a string is a string.
		if (left instanceof String || right instanceof String) {
			return stringOf(left) + stringOf(right);
		}
		if (left instanceof List<?> a && right instanceof List<?> b) {
			List<Object> joined = new ArrayList<>(a);
			joined.addAll(b);
			return joined;
		}
		if (left instanceof List<?> a) {
			List<Object> joined = new ArrayList<>(a);
			joined.add(right);
			return joined;
		}
		Number a = number(left, "+");
		Number b = number(right, "+");
		return isIntegral(a) && isIntegral(b) ? (Object) (a.longValue() + b.longValue())
			: (Object) (a.doubleValue() + b.doubleValue());
	}

	public static Object subtract(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		Number a = number(left, "-");
		Number b = number(right, "-");
		return isIntegral(a) && isIntegral(b) ? (Object) (a.longValue() - b.longValue())
			: (Object) (a.doubleValue() - b.doubleValue());
	}

	public static Object multiply(Object left, Object right) {
		if (left == null || right == null) {
			return null;
		}
		Number a = number(left, "*");
		Number b = number(right, "*");
		return isIntegral(a) && isIntegral(b) ? (Object) (a.longValue() * b.longValue())
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
			return a.longValue() / b.longValue();
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
		return isIntegral(number) ? (Object) (-number.longValue()) : (Object) (-number.doubleValue());
	}

	// ---------------------------------------------------------------- helpers

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
