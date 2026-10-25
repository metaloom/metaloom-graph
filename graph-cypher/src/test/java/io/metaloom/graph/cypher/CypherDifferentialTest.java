package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Random queries against an evaluator written separately, over random graphs read through the Java API.
 *
 * <p>
 * {@link CypherVersusApiTest} compares a fixed set of queries with what the API computes. This draws the queries: a
 * predicate from a small grammar — comparisons over integers, floats that equal them, strings and missing values,
 * joined by AND, OR, XOR and NOT — and an aggregation and an ordering over the same values. The oracle here shares
 * no code with {@code Values}: three-valued logic is written out again below, numbers are compared as
 * {@link BigDecimal}, and the rows are found by walking {@code forEachNode} and {@code neighbours}. Two
 * implementations of one rule that agree on a few thousand random inputs is evidence a hand-written case is not.
 * </p>
 *
 * <p>
 * The last test is the planner's: one equality asked four ways — an inline map, a WHERE equality, a negated inequality
 * the planner cannot use as a seed, and a full scan — before and after a composite index is declared, must find the
 * same nodes, and the nodes the API finds by reading every property.
 * </p>
 */
@Tag("unit")
public class CypherDifferentialTest {

	private static final String[] LABELS = { "A", "B" };
	private static final String[] TYPES = { "R", "S" };

	@RegisterExtension
	TempStore stores = new TempStore();

	// ---------------------------------------------------------------- the graph

	/** Small integers, some stored as floats; a string key; a third of each missing; both edge shapes; self-loops. */
	private static void seed(GraphStore store, Random random, int nodes) throws Exception {
		List<Long> ids = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int k = 0; k < nodes; k++) {
				List<String> labels = new ArrayList<>();
				for (String label : LABELS) {
					if (random.nextInt(2) == 0) {
						labels.add(label);
					}
				}
				Properties properties = Properties.of();
				if (random.nextInt(3) != 0) {
					long value = random.nextInt(6) - 2;
					properties.set("i", random.nextInt(3) == 0 ? (Object) (double) value : (Object) value);
				}
				if (random.nextInt(3) != 0) {
					properties.set("s", Character.toString('a' + random.nextInt(3)));
				}
				if (random.nextInt(8) == 0) {
					properties.set("i", 0.5 * (random.nextInt(5) - 2));
				}
				ids.add(store.createNode(labels, properties));
			}
			for (int e = 0; e < nodes * 2; e++) {
				long from = ids.get(random.nextInt(ids.size()));
				long to = random.nextInt(8) == 0 ? from : ids.get(random.nextInt(ids.size()));
				String type = TYPES[random.nextInt(TYPES.length)];
				if (random.nextBoolean()) {
					store.createRelationship(from, type, to, Properties.of());
				} else {
					store.createEdge(from, type, to);
				}
			}
			txn.commit();
		}
	}

	// ---------------------------------------------------------------- an independent evaluator

	/** A predicate, as Cypher text and as an evaluation over the two endpoints' properties. Null is unknown. */
	private interface Predicate {
		String cypher();

		Boolean eval(Map<String, Object> a, Map<String, Object> b);
	}

	/** An operand: a property of a or b, or a literal. */
	private record Operand(String cypher, java.util.function.BiFunction<Map<String, Object>, Map<String, Object>, Object> read) {
	}

	private static Operand operand(Random random) {
		return switch (random.nextInt(7)) {
			case 0 -> new Operand("a.i", (a, b) -> a.get("i"));
			case 1 -> new Operand("b.i", (a, b) -> b.get("i"));
			case 2 -> new Operand("a.s", (a, b) -> a.get("s"));
			case 3 -> new Operand("b.s", (a, b) -> b.get("s"));
			case 4 -> {
				long value = random.nextInt(6) - 2;
				yield random.nextBoolean() ? new Operand(Long.toString(value), (a, b) -> value)
					: new Operand(value + ".0", (a, b) -> (double) value);
			}
			case 5 -> {
				String value = Character.toString('a' + random.nextInt(3));
				yield new Operand("'" + value + "'", (a, b) -> value);
			}
			default -> new Operand("null", (a, b) -> null);
		};
	}

	private static Predicate predicate(Random random, int depth) {
		int choice = random.nextInt(depth <= 0 ? 2 : 6);
		switch (choice) {
			case 0 -> {
				Operand left = operand(random);
				Operand right = operand(random);
				String op = new String[] { "=", "<>", "<", "<=", ">", ">=" }[random.nextInt(6)];
				return new Predicate() {
					public String cypher() {
						return left.cypher() + " " + op + " " + right.cypher();
					}

					public Boolean eval(Map<String, Object> a, Map<String, Object> b) {
						return compare(left.read().apply(a, b), op, right.read().apply(a, b));
					}
				};
			}
			case 1 -> {
				Operand operand = operand(random);
				boolean negated = random.nextBoolean();
				return new Predicate() {
					public String cypher() {
						return operand.cypher() + (negated ? " IS NOT NULL" : " IS NULL");
					}

					public Boolean eval(Map<String, Object> a, Map<String, Object> b) {
						return (operand.read().apply(a, b) == null) != negated;
					}
				};
			}
			case 2 -> {
				Predicate inner = predicate(random, depth - 1);
				return new Predicate() {
					public String cypher() {
						return "NOT (" + inner.cypher() + ")";
					}

					public Boolean eval(Map<String, Object> a, Map<String, Object> b) {
						Boolean value = inner.eval(a, b);
						return value == null ? null : !value;
					}
				};
			}
			default -> {
				Predicate left = predicate(random, depth - 1);
				Predicate right = predicate(random, depth - 1);
				String op = new String[] { "AND", "OR", "XOR" }[choice - 3];
				return new Predicate() {
					public String cypher() {
						return "(" + left.cypher() + ") " + op + " (" + right.cypher() + ")";
					}

					public Boolean eval(Map<String, Object> a, Map<String, Object> b) {
						Boolean x = left.eval(a, b);
						Boolean y = right.eval(a, b);
						return switch (op) {
							case "AND" -> Boolean.FALSE.equals(x) || Boolean.FALSE.equals(y) ? Boolean.FALSE
								: x == null || y == null ? null : Boolean.TRUE;
							case "OR" -> Boolean.TRUE.equals(x) || Boolean.TRUE.equals(y) ? Boolean.TRUE
								: x == null || y == null ? null : Boolean.FALSE;
							default -> x == null || y == null ? null : x ^ y;
						};
					}
				};
			}
		}
	}

	/** Comparison as openCypher defines it over these types: numbers exactly, strings by code unit, anything else null. */
	private static Boolean compare(Object left, String op, Object right) {
		if (left == null || right == null) {
			return null;
		}
		Integer order = null;
		if (left instanceof Number x && right instanceof Number y) {
			order = new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString()));
		} else if (left instanceof String x && right instanceof String y) {
			order = Integer.signum(x.compareTo(y));
		}
		if (order == null) {
			// Unlike types: = is false and <> true, an ordering is unknown.
			return switch (op) {
				case "=" -> Boolean.FALSE;
				case "<>" -> Boolean.TRUE;
				default -> null;
			};
		}
		int o = order;
		return switch (op) {
			case "=" -> o == 0;
			case "<>" -> o != 0;
			case "<" -> o < 0;
			case "<=" -> o <= 0;
			case ">" -> o > 0;
			default -> o >= 0;
		};
	}

	private static Map<String, Object> properties(GraphStore store, long id) {
		Properties stored = store.nodeProperties(id);
		Map<String, Object> out = new LinkedHashMap<>();
		for (String key : stored.keys()) {
			out.put(key, stored.get(key));
		}
		return out;
	}

	// ---------------------------------------------------------------- the tests

	/** WHERE over one hop: every predicate the grammar makes keeps exactly the rows the oracle keeps. */
	@Test
	public void testRandomPredicatesAgreeWithAnIndependentEvaluator() throws Exception {
		for (long seed = 1; seed <= 3; seed++) {
			try (GraphStore store = stores.open()) {
				Random random = new Random(seed);
				seed(store, random, 40);
				for (int q = 0; q < 150; q++) {
					String label = LABELS[random.nextInt(LABELS.length)];
					String type = TYPES[random.nextInt(TYPES.length)];
					Predicate predicate = predicate(random, 3);
					String query = "MATCH (a:" + label + ")-[:" + type + "]->(b) WHERE " + predicate.cypher()
						+ " RETURN id(a) AS a, id(b) AS b";

					List<String> expected = new ArrayList<>();
					store.forEachNode(a -> {
						if (!store.nodeLabels(a).contains(label)) {
							return;
						}
						Map<String, Object> left = properties(store, a);
						for (Edge edge : store.neighbours(a, Direction.OUT, Set.of(type))) {
							if (Boolean.TRUE.equals(predicate.eval(left, properties(store, edge.neighbourId())))) {
								expected.add(a + "->" + edge.neighbourId());
							}
						}
					});
					List<String> actual = new ArrayList<>();
					for (CypherRow row : Cypher.query(store, query)) {
						actual.add(row.longValue("a") + "->" + row.longValue("b"));
					}
					Collections.sort(expected);
					Collections.sort(actual);
					assertEquals(expected, actual, "seed " + seed + ": " + query);
				}
			}
		}
	}

	/** Grouping, the aggregates that skip nulls, and count(*) that does not, against a fold written out by hand. */
	@Test
	public void testRandomGroupingsAgreeWithAnIndependentFold() throws Exception {
		for (long seed = 11; seed <= 13; seed++) {
			try (GraphStore store = stores.open()) {
				Random random = new Random(seed);
				seed(store, random, 60);
				for (int q = 0; q < 40; q++) {
					String label = LABELS[random.nextInt(LABELS.length)];
					Predicate predicate = predicate(random, 2);
					String query = "MATCH (a:" + label + ")-[]->(b) WHERE " + predicate.cypher()
						+ " RETURN a.s AS k, count(*) AS rows, count(b.i) AS values, sum(b.i) AS total,"
						+ " min(b.i) AS low, max(b.i) AS high, count(DISTINCT b.i) AS uniques";

					// Group key: the string, or null — one group for every node without one.
					Map<String, List<Object>> groups = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
					store.forEachNode(a -> {
						if (!store.nodeLabels(a).contains(label)) {
							return;
						}
						Map<String, Object> left = properties(store, a);
						for (Edge edge : store.neighbours(a, Direction.BOTH)) {
							if (edge.direction() == Direction.IN) {
								// Only the outgoing view of each edge: the query's arrow points right.
								continue;
							}
							Map<String, Object> right = properties(store, edge.neighbourId());
							if (Boolean.TRUE.equals(predicate.eval(left, right))) {
								groups.computeIfAbsent((String) left.get("s"), k -> new ArrayList<>()).add(right.get("i"));
							}
						}
					});
					Map<String, String> expected = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
					groups.forEach((key, values) -> expected.put(key, fold(values)));

					Map<String, String> actual = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
					for (CypherRow row : Cypher.query(store, query)) {
						actual.put(row.string("k"), row.get("rows") + " " + row.get("values") + " "
							+ number(row.get("total")) + " " + number(row.get("low")) + " " + number(row.get("high"))
							+ " " + row.get("uniques"));
					}
					assertEquals(expected, actual, "seed " + seed + ": " + query);
				}
			}
		}
	}

	private static String fold(List<Object> values) {
		List<BigDecimal> present = new ArrayList<>();
		for (Object value : values) {
			if (value != null) {
				present.add(new BigDecimal(value.toString()));
			}
		}
		BigDecimal total = present.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		String low = present.stream().min(Comparator.naturalOrder()).map(CypherDifferentialTest::plain).orElse("null");
		String high = present.stream().max(Comparator.naturalOrder()).map(CypherDifferentialTest::plain).orElse("null");
		long distinct = present.stream().map(BigDecimal::stripTrailingZeros).distinct().count();
		return values.size() + " " + present.size() + " " + plain(total) + " " + low + " " + high + " " + distinct;
	}

	private static String number(Object value) {
		return value == null ? "null" : plain(new BigDecimal(value.toString()));
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	/** ORDER BY over keys with nulls, ints and floats, both directions, sliced: the oracle sorts the same values. */
	@Test
	public void testRandomOrderingsAgreeWithAnIndependentSort() throws Exception {
		try (GraphStore store = stores.open()) {
			Random random = new Random(21);
			seed(store, random, 50);
			List<Map<String, Object>> all = new ArrayList<>();
			store.forEachNode(id -> all.add(properties(store, id)));
			for (int q = 0; q < 60; q++) {
				boolean firstDescending = random.nextBoolean();
				boolean secondDescending = random.nextBoolean();
				int skip = random.nextInt(10);
				int limit = random.nextInt(30);
				String query = "MATCH (n) RETURN n.i AS i, n.s AS s ORDER BY n.i" + (firstDescending ? " DESC" : "")
					+ ", n.s" + (secondDescending ? " DESC" : "") + " SKIP " + skip + " LIMIT " + limit;

				// Nulls last ascending, and therefore first descending.
				Comparator<Map<String, Object>> byNumber = Comparator.comparing(
					row -> row.get("i") == null ? null : new BigDecimal(row.get("i").toString()),
					Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()));
				Comparator<Map<String, Object>> byString = Comparator.comparing(row -> (String) row.get("s"),
					Comparator.nullsLast(Comparator.<String>naturalOrder()));
				Comparator<Map<String, Object>> order = (firstDescending ? byNumber.reversed() : byNumber)
					.thenComparing(secondDescending ? byString.reversed() : byString);
				List<String> expected = new ArrayList<>();
				all.stream().sorted(order).skip(skip).limit(limit)
					.forEach(row -> expected.add(number(row.get("i")) + "/" + row.get("s")));

				List<String> actual = new ArrayList<>();
				for (CypherRow row : Cypher.query(store, query)) {
					actual.add(number(row.get("i")) + "/" + row.get("s"));
				}
				assertEquals(expected, actual, query);
			}
		}
	}

	/**
	 * Variable-length patterns in every direction and range, against a depth-first walk written here: one path per
	 * route, no relationship twice within one, and a self-loop crossed once by an undirected step.
	 */
	@Test
	public void testRandomVariableLengthPathsAgreeWithADepthFirstWalk() throws Exception {
		try (GraphStore store = stores.open()) {
			Random random = new Random(41);
			seed(store, random, 14);
			for (int q = 0; q < 40; q++) {
				int min = random.nextInt(3);
				int max = min + random.nextInt(3);
				Direction direction = Direction.values()[random.nextInt(Direction.values().length)];
				String type = random.nextBoolean() ? TYPES[random.nextInt(TYPES.length)] : null;
				String label = random.nextBoolean() ? LABELS[random.nextInt(LABELS.length)] : null;
				String step = "[" + (type == null ? "" : ":" + type) + "*" + min + ".." + max + "]";
				String arrow = switch (direction) {
					case OUT -> "-" + step + "->";
					case IN -> "<-" + step + "-";
					default -> "-" + step + "-";
				};
				String query = "MATCH (a" + (label == null ? "" : ":" + label) + ")" + arrow
					+ "(b) RETURN id(a) AS a, id(b) AS b";

				List<String> expected = new ArrayList<>();
				store.forEachNode(a -> {
					if (label == null || store.nodeLabels(a).contains(label)) {
						walk(store, a, a, direction, type, min, max, 0, new java.util.HashSet<>(), expected);
					}
				});
				List<String> actual = new ArrayList<>();
				for (CypherRow row : Cypher.query(store, query)) {
					actual.add(row.longValue("a") + "->" + row.longValue("b"));
				}
				Collections.sort(expected);
				Collections.sort(actual);
				assertEquals(expected, actual, query);
			}
		}
	}

	private static void walk(GraphStore store, long start, long at, Direction direction, String type, int min,
		int max, int depth, Set<String> used, List<String> out) {
		if (depth >= min) {
			out.add(start + "->" + at);
		}
		if (depth == max) {
			return;
		}
		List<Edge> edges = type == null ? store.neighbours(at, direction) : store.neighbours(at, direction, Set.of(type));
		for (Edge edge : edges) {
			if (direction == Direction.BOTH && edge.direction() == Direction.IN && edge.neighbourId() == at) {
				// The second listing of a self-loop: one relationship, crossed once.
				continue;
			}
			long from = edge.direction() == Direction.OUT ? at : edge.neighbourId();
			long to = edge.direction() == Direction.OUT ? edge.neighbourId() : at;
			String identity = edge.isMaterialised() ? "#" + edge.relationshipId() : from + "/" + edge.type() + "/" + to;
			if (used.add(identity)) {
				walk(store, start, edge.neighbourId(), direction, type, min, max, depth + 1, used, out);
				used.remove(identity);
			}
		}
	}

	/**
	 * One equality, four access paths, with and without a declared index: the same nodes every time.
	 *
	 * <p>
	 * The values are the ones that have broken probes before: an integer and the float equal to it (the index keys
	 * each by its own type), a non-integral float, a string spelling the number, a boolean, a list, a list of floats
	 * equal to it, and absent. Each is asked for as a literal and as a parameter.
	 * </p>
	 */
	@Test
	public void testEveryAccessPathFindsTheSameNodes() throws Exception {
		Object[] values = { 1L, 1.0, 2L, 2.5, "1", true, List.of(1L, 2L), List.of(1.0, 2.0) };
		String[] literals = { "1", "1.0", "2", "2.5", "'1'", "true", "[1, 2]", "[1.0, 2.0]" };
		try (GraphStore store = stores.open()) {
			Random random = new Random(31);
			try (Transaction txn = store.begin()) {
				for (int k = 0; k < 80; k++) {
					Properties properties = Properties.of();
					if (random.nextInt(4) != 0) {
						properties.set("k", values[random.nextInt(values.length)]);
					}
					store.createNode(random.nextBoolean() ? List.of("P") : List.of("Q", "P"), properties);
				}
				txn.commit();
			}
			for (boolean indexed : new boolean[] { false, true }) {
				if (indexed) {
					store.createIndex("P", "k");
				}
				for (int v = 0; v < values.length; v++) {
					Object wanted = values[v];
					List<Long> expected = new ArrayList<>();
					store.forEachNode(id -> {
						if (store.nodeLabels(id).contains("P") && cypherEqual(store.nodeProperties(id).get("k"), wanted)) {
							expected.add(id);
						}
					});
					Collections.sort(expected);
					assertTrue(!expected.isEmpty(), "the seeded graph must hold " + literals[v]);
					String[] queries = {
						"MATCH (n:P {k: " + literals[v] + "}) RETURN id(n) AS id",
						"MATCH (n:P) WHERE n.k = " + literals[v] + " RETURN id(n) AS id",
						"MATCH (n:P) WHERE NOT (n.k <> " + literals[v] + ") RETURN id(n) AS id",
						"MATCH (n) WHERE n:P AND " + literals[v] + " = n.k RETURN id(n) AS id",
						"MATCH (n:P {k: $v}) RETURN id(n) AS id",
						"MATCH (n:P) WHERE n.k = $v RETURN id(n) AS id",
						"MATCH (n:Q:P {k: $v}) RETURN id(n) AS id" };
					for (String query : queries) {
						List<Long> actual = new ArrayList<>();
						for (Object id : Cypher.query(store, query, Map.of("v", wanted)).column("id")) {
							actual.add((Long) id);
						}
						Collections.sort(actual);
						List<Long> want = expected;
						if (query.contains(":Q:P")) {
							want = new ArrayList<>();
							for (long id : expected) {
								if (store.nodeLabels(id).contains("Q")) {
									want.add(id);
								}
							}
						}
						assertEquals(want, actual, (indexed ? "indexed: " : "unindexed: ") + query + " with $v = "
							+ wanted);
					}
				}
			}
		}
	}

	/** Equality as openCypher defines it for the values above: numbers by value, lists element by element. */
	private static boolean cypherEqual(Object stored, Object wanted) {
		if (stored == null) {
			return false;
		}
		if (stored instanceof Number x && wanted instanceof Number y) {
			return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
		}
		if (stored instanceof List<?> x && wanted instanceof List<?> y) {
			if (x.size() != y.size()) {
				return false;
			}
			for (int i = 0; i < x.size(); i++) {
				if (!cypherEqual(x.get(i), y.get(i))) {
					return false;
				}
			}
			return true;
		}
		return Objects.equals(stored, wanted);
	}
}
