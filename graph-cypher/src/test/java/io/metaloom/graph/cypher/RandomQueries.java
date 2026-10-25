package io.metaloom.graph.cypher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Random query text for the fuzz and differential tests: from a grammar, by mutating a seed query, or as noise.
 *
 * <p>
 * The grammar covers every clause and expression form the subset documents, over a fixed vocabulary of labels
 * ({@code A}, {@code B}, {@code C}), relationship types ({@code R}, {@code S}) and property keys ({@code i}, {@code s},
 * {@code f}, {@code l}) — the same ones {@link #seedGraph} writes, so a generated MATCH finds something often enough to
 * reach the code after it. It is deliberately not restricted to valid queries: an undefined variable, a wrong arity,
 * a type mismatch or a missing parameter is an input the engine has to refuse properly, and that is half of what the
 * fuzzer checks.
 * </p>
 *
 * <p>
 * Everything is drawn from one {@link Random}, so a seed reproduces a sequence exactly.
 * </p>
 */
public final class RandomQueries {

	public static final String[] LABELS = { "A", "B", "C" };
	public static final String[] TYPES = { "R", "S" };
	public static final String[] KEYS = { "i", "s", "f", "l" };

	private static final String[] SCALAR_FUNCTIONS = { "id", "labels", "type", "keys", "properties", "startNode",
		"endNode", "length", "size", "head", "last", "tail", "range", "coalesce", "exists", "toString", "toUpper",
		"toLower", "trim", "ltrim", "rtrim", "substring", "replace", "split", "reverse", "toInteger", "toFloat",
		"toBoolean", "abs", "ceil", "floor", "round", "sqrt", "sign", "nodes", "relationships", "vector.cosine",
		"vector.l2", "nosuchfunction" };

	private static final String[] AGGREGATES = { "count", "sum", "avg", "min", "max", "collect" };

	private static final String[] BINARY = { "+", "-", "*", "/", "%", "^", "=", "<>", "<", "<=", ">", ">=", "AND", "OR",
		"XOR", "IN", "STARTS WITH", "ENDS WITH", "CONTAINS" };

	/** Tokens a mutation may insert: every symbol and keyword, and a few values that sit on edges. */
	private static final String[] VOCABULARY = { "(", ")", "[", "]", "{", "}", ",", ":", ".", "..", "|", "=", "<>",
		"<", ">", "<=", ">=", "+", "-", "*", "/", "%", "^", "+=", "->", "<-", "-->", "<--", "=~", "$p", "$", "`", "'",
		"\"", "\\", "MATCH", "OPTIONAL", "WHERE", "RETURN", "WITH", "UNWIND", "AS", "DISTINCT", "ORDER BY", "SKIP",
		"LIMIT", "UNION", "ALL", "AND", "OR", "XOR", "NOT", "IN", "IS NULL", "IS NOT NULL", "STARTS WITH", "CONTAINS",
		"CASE", "WHEN", "THEN", "ELSE", "END", "CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "CALL", "YIELD",
		"FOREACH", "ON CREATE SET", "ON MATCH SET", "LOAD CSV", "FROM", "USING PERIODIC COMMIT", "shortestPath",
		"allShortestPaths", "count(*)", "null", "true", "0", "-1", "9223372036854775807", "9223372036854775808",
		"-9223372036854775808", "0x7fffffffffffffff", "1e308", "1e309", "0.0", ".5", "1.", "*0..", "*..0", "*2..1",
		"n", "m", "r", "p", "x", "n.i", "n.s", "'😀'", "'\\u0000'", "'\\uD800'", "٣", "ß",
		"/*", "*/", "//", "\n", "\t", "\u0000", "￿" };

	private final Random random;
	private int fresh;

	/** Every name this query bound to a node, so the write clauses can mostly pick one that is a node. */
	private final java.util.Set<String> nodeNames = new java.util.HashSet<>();

	public RandomQueries(Random random) {
		this.random = random;
	}

	// ---------------------------------------------------------------- the graph every generated query runs over

	/**
	 * Builds the graph the vocabulary refers to, through the Java API so that it holds what Cypher cannot write:
	 * lightweight edges. Nodes with several labels and none, a missing property now and then, an integer and a float
	 * that are equal, parallel edges and self-loops.
	 *
	 * @return the node ids, in creation order
	 */
	public static List<Long> seedGraph(io.metaloom.graph.core.api.GraphStore store, Random random, int nodes)
		throws java.io.IOException {
		List<Long> ids = new ArrayList<>(nodes);
		try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
			for (int k = 0; k < nodes; k++) {
				List<String> labels = new ArrayList<>();
				for (String label : LABELS) {
					if (random.nextInt(3) == 0) {
						labels.add(label);
					}
				}
				io.metaloom.graph.core.api.Properties properties = io.metaloom.graph.core.api.Properties.of();
				if (random.nextInt(5) != 0) {
					// Both spellings of one number, so 1 = 1.0 and the index's typed keys meet.
					properties.set("i", random.nextBoolean() ? (Object) (long) (k % 7) : (Object) (double) (k % 7));
				}
				if (random.nextInt(4) != 0) {
					properties.set("s", Integer.toString(k % 5));
				}
				if (random.nextInt(3) != 0) {
					properties.set("f", k / 4.0);
				}
				if (random.nextInt(3) == 0) {
					properties.set("l", List.of((long) (k % 3), (long) (k % 2)));
				}
				ids.add(store.createNode(labels, properties));
			}
			for (int e = 0; e < nodes * 2; e++) {
				long from = ids.get(random.nextInt(ids.size()));
				long to = random.nextInt(10) == 0 ? from : ids.get(random.nextInt(ids.size()));
				String type = TYPES[random.nextInt(TYPES.length)];
				if (random.nextBoolean()) {
					store.createRelationship(from, type, to,
						io.metaloom.graph.core.api.Properties.of().set("w", (long) random.nextInt(4)));
				} else {
					store.createEdge(from, type, to);
				}
			}
			txn.commit();
		}
		return ids;
	}

	// ---------------------------------------------------------------- whole queries

	/** One query from the grammar. Mostly well formed, often wrong in a way the engine must refuse properly. */
	public String query() {
		fresh = 0;
		nodeNames.clear();
		List<String> scope = new ArrayList<>();
		StringBuilder out = new StringBuilder();
		int clauses = 1 + random.nextInt(4);
		for (int i = 0; i < clauses; i++) {
			out.append(clause(scope)).append(' ');
		}
		out.append(returnClause(scope));
		if (random.nextInt(12) == 0) {
			out.append(random.nextBoolean() ? " UNION " : " UNION ALL ").append(returnClause(new ArrayList<>()));
		}
		return out.toString();
	}

	private String clause(List<String> scope) {
		int choice = random.nextInt(40);
		if (choice >= 33 && nodes(scope).isEmpty()) {
			// SET, REMOVE and DELETE want a node to act on; without one in scope, match one first.
			choice = 0;
		}
		return switch (choice) {
			case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 -> match(scope);
			case 12, 13, 14, 15 -> unwind(scope);
			case 16, 17, 18, 19, 20 -> with(scope);
			case 21, 22, 23, 24 -> create(scope);
			case 25, 26, 27, 28 -> merge(scope);
			case 29 -> "OPTIONAL " + match(scope);
			case 30, 31 -> "CALL vector.query('A', 'l', " + expression(scope, 2) + ", " + random.nextInt(4)
				+ ") YIELD " + bind(scope, "node");
			case 32 -> delete(scope);
			case 33, 34, 35, 36 -> set(scope);
			case 37, 38 -> remove(scope);
			default -> delete(scope);
		};
	}

	/** The names in scope that were bound to nodes. */
	private List<String> nodes(List<String> scope) {
		List<String> out = new ArrayList<>();
		for (String name : scope) {
			if (nodeNames.contains(name)) {
				out.add(name);
			}
		}
		return out;
	}

	/** A node in scope, or now and then any name at all, which the engine must refuse properly. */
	private String target(List<String> scope) {
		List<String> nodes = nodes(scope);
		if (nodes.isEmpty() || random.nextInt(20) == 0) {
			return scope.isEmpty() ? "n" : pick(scope);
		}
		return pick(nodes);
	}

	private String match(List<String> scope) {
		StringBuilder out = new StringBuilder("MATCH ");
		int parts = 1 + (random.nextInt(4) == 0 ? 1 : 0);
		for (int i = 0; i < parts; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(patternPart(scope, true));
		}
		if (random.nextBoolean()) {
			out.append(" WHERE ").append(expression(scope, 3));
		}
		return out.toString();
	}

	private String patternPart(List<String> scope, boolean matching) {
		StringBuilder out = new StringBuilder();
		boolean path = matching && random.nextInt(5) == 0;
		if (path) {
			out.append(bind(scope, "p")).append(" = ");
		}
		boolean shortest = path && random.nextInt(3) == 0;
		if (shortest) {
			out.append(random.nextBoolean() ? "shortestPath(" : "allShortestPaths(");
		}
		out.append(node(scope));
		int hops = shortest ? 1 : random.nextInt(3);
		for (int i = 0; i < hops; i++) {
			out.append(relationship(scope, matching)).append(node(scope));
		}
		if (shortest) {
			out.append(')');
		}
		return out.toString();
	}

	private String node(List<String> scope) {
		StringBuilder out = new StringBuilder("(");
		int choice = random.nextInt(4);
		if (choice == 0 && !scope.isEmpty()) {
			out.append(random.nextInt(10) == 0 || nodes(scope).isEmpty() ? pick(scope) : pick(nodes(scope)));
		} else if (choice != 1) {
			String name = bind(scope, "n");
			nodeNames.add(name);
			out.append(name);
		}
		int labels = random.nextInt(3) == 0 ? 1 + random.nextInt(2) : 0;
		for (int i = 0; i < labels; i++) {
			out.append(':').append(pick(LABELS));
		}
		if (random.nextInt(3) == 0) {
			out.append(' ').append(propertyMap(scope));
		}
		return out.append(')').toString();
	}

	private String relationship(List<String> scope, boolean matching) {
		StringBuilder out = new StringBuilder();
		int direction = random.nextInt(3);
		out.append(direction == 1 ? "<-[" : "-[");
		if (random.nextBoolean()) {
			out.append(bind(scope, "r"));
		}
		// MERGE builds what it does not find, so there a relationship mostly has the one type CREATE needs.
		if (random.nextInt(matching ? 4 : 20) != 0) {
			out.append(':').append(pick(TYPES));
			if (random.nextInt(matching ? 5 : 30) == 0) {
				out.append('|').append(pick(TYPES));
			}
		}
		if (matching && random.nextInt(4) == 0) {
			// Bounded: an unbounded range over a graph with cycles enumerates exponentially many paths by design
			// (CYPHER.md §3.2), which is a memory test rather than a fuzz input. Mutations still produce unbounded ones.
			out.append('*');
			int min = random.nextInt(3);
			switch (random.nextInt(4)) {
				case 0 -> out.append(min);
				// Now and then a minimum above the maximum, which must be refused.
				case 1 -> out.append(min).append("..").append(random.nextInt(10) == 0 ? min - 1 : min + random.nextInt(2));
				case 2 -> out.append("..").append(1 + random.nextInt(3));
				default -> out.append(random.nextInt(2)).append("..").append(2 + random.nextInt(2));
			}
		}
		if (random.nextInt(4) == 0) {
			out.append(' ').append(propertyMap(scope));
		}
		out.append(']');
		out.append(direction == 0 ? "->" : "-");
		return out.toString();
	}

	private String propertyMap(List<String> scope) {
		StringBuilder out = new StringBuilder("{");
		int entries = random.nextInt(3);
		for (int i = 0; i < entries; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(pick(KEYS)).append(": ").append(expression(scope, 1));
		}
		return out.append('}').toString();
	}

	private String unwind(List<String> scope) {
		String list = random.nextInt(4) == 0 ? expression(scope, 2)
			: "[" + expression(scope, 1) + ", " + expression(scope, 1) + "]";
		return "UNWIND " + list + " AS " + bind(scope, "x");
	}

	private String with(List<String> scope) {
		String projection = projection(scope, true);
		String where = random.nextInt(3) == 0 ? " WHERE " + expression(scope, 2) : "";
		return "WITH " + projection + where;
	}

	private String returnClause(List<String> scope) {
		return "RETURN " + projection(scope, false);
	}

	/** A projection, which also narrows the scope to what it names — exactly as WITH does. */
	private String projection(List<String> scope, boolean rescope) {
		StringBuilder out = new StringBuilder();
		if (random.nextInt(6) == 0) {
			out.append("DISTINCT ");
		}
		List<String> projected = new ArrayList<>();
		if (random.nextInt(8) == 0) {
			out.append('*');
			projected.addAll(scope);
		} else {
			int items = 1 + random.nextInt(3);
			for (int i = 0; i < items; i++) {
				if (i > 0) {
					out.append(", ");
				}
				if (!scope.isEmpty() && random.nextInt(5) < 2) {
					// Carried through under its own name, so a node stays a node for the clauses after a WITH.
					String carried = pick(scope);
					if (!projected.contains(carried)) {
						out.append(carried);
						projected.add(carried);
						continue;
					}
				}
				String expression = random.nextInt(4) == 0 ? aggregate(scope) : expression(scope, 2);
				String alias = "c" + (fresh++);
				out.append(expression).append(" AS ").append(alias);
				projected.add(alias);
			}
		}
		if (random.nextInt(3) == 0) {
			out.append(" ORDER BY ").append(random.nextBoolean() && !projected.isEmpty() ? pick(projected)
				: expression(scope, 1));
			if (random.nextBoolean()) {
				out.append(random.nextBoolean() ? " DESC" : " ASC");
			}
		}
		if (random.nextInt(5) == 0) {
			out.append(" SKIP ").append(random.nextInt(4) == 0 ? expression(scope, 0) : random.nextInt(3));
		}
		if (random.nextInt(4) == 0) {
			out.append(" LIMIT ").append(random.nextInt(4) == 0 ? expression(scope, 0) : random.nextInt(5));
		}
		if (rescope) {
			scope.clear();
			scope.addAll(projected);
		}
		return out.toString();
	}

	private String aggregate(List<String> scope) {
		String name = pick(AGGREGATES);
		if (name.equals("count") && random.nextBoolean()) {
			return "count(*)";
		}
		return name + "(" + (random.nextInt(4) == 0 ? "DISTINCT " : "") + expression(scope, 1) + ")";
	}

	private String create(List<String> scope) {
		StringBuilder out = new StringBuilder("CREATE ");
		out.append(node(scope));
		if (random.nextBoolean()) {
			// Mostly one of the two directions CREATE accepts; now and then the undirected arrow it must refuse.
			int direction = random.nextInt(12);
			out.append(direction == 0 ? "<-[" : "-[");
			if (random.nextBoolean()) {
				out.append(bind(scope, "r"));
			}
			out.append(':').append(pick(TYPES));
			if (random.nextInt(3) == 0) {
				out.append(' ').append(propertyMap(scope));
			}
			out.append(direction <= 1 ? "]-" : "]->");
			out.append(node(scope));
		}
		return out.toString();
	}

	private String merge(List<String> scope) {
		StringBuilder out = new StringBuilder("MERGE ").append(patternPart(scope, false));
		if (random.nextBoolean()) {
			out.append(" ON CREATE SET ").append(setItem(scope));
		}
		if (random.nextBoolean()) {
			out.append(" ON MATCH SET ").append(setItem(scope));
		}
		return out.toString();
	}

	private String set(List<String> scope) {
		StringBuilder out = new StringBuilder("SET ").append(setItem(scope));
		if (random.nextBoolean()) {
			out.append(", ").append(setItem(scope));
		}
		return out.toString();
	}

	private String setItem(List<String> scope) {
		String target = target(scope);
		return switch (random.nextInt(4)) {
			case 0 -> target + ":" + pick(LABELS);
			case 1 -> target + " = " + (random.nextBoolean() ? propertyMap(scope) : expression(scope, 1));
			case 2 -> target + " += " + (random.nextBoolean() ? propertyMap(scope) : expression(scope, 1));
			default -> target + "." + pick(KEYS) + " = " + expression(scope, 2);
		};
	}

	private String remove(List<String> scope) {
		String target = target(scope);
		return "REMOVE " + (random.nextBoolean() ? target + ":" + pick(LABELS) : target + "." + pick(KEYS));
	}

	private String delete(List<String> scope) {
		String target = random.nextInt(15) == 0 ? expression(scope, 1) : target(scope);
		return (random.nextBoolean() ? "DETACH DELETE " : "DELETE ") + target;
	}

	/** A new name, added to the scope, or now and then one that is already in it. */
	private String bind(List<String> scope, String prefix) {
		if (!scope.isEmpty() && random.nextInt(8) == 0) {
			return pick(scope);
		}
		String name = prefix + (fresh++);
		scope.add(name);
		return name;
	}

	// ---------------------------------------------------------------- expressions

	public String expression(List<String> scope, int depth) {
		if (depth <= 0) {
			return atom(scope);
		}
		return switch (random.nextInt(12)) {
			case 0, 1 -> atom(scope);
			case 2, 3, 4 -> "(" + expression(scope, depth - 1) + " " + pick(BINARY) + " " + expression(scope, depth - 1)
				+ ")";
			case 5 -> (random.nextBoolean() ? "NOT " : "-") + expression(scope, depth - 1);
			case 6 -> expression(scope, depth - 1) + (random.nextBoolean() ? " IS NULL" : " IS NOT NULL");
			case 7 -> function(scope, depth);
			case 8 -> list(scope, depth);
			case 9 -> "{a: " + expression(scope, depth - 1) + ", b: " + expression(scope, depth - 1) + "}";
			case 10 -> expression(scope, depth - 1) + "[" + expression(scope, depth - 1) + "]";
			default -> caseExpression(scope, depth);
		};
	}

	private String function(List<String> scope, int depth) {
		String name = pick(SCALAR_FUNCTIONS);
		int arity = random.nextInt(8) == 0 ? random.nextInt(4) : arity(name);
		StringBuilder out = new StringBuilder(name).append('(');
		for (int i = 0; i < arity; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(expression(scope, depth - 1));
		}
		return out.append(')').toString();
	}

	private static int arity(String name) {
		return switch (name) {
			case "range", "substring" -> 2 + (name.length() % 2);
			case "replace" -> 3;
			case "split", "vector.cosine", "vector.l2" -> 2;
			case "coalesce" -> 2;
			default -> 1;
		};
	}

	private String list(List<String> scope, int depth) {
		int size = random.nextInt(4);
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < size; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(expression(scope, depth - 1));
		}
		return out.append(']').toString();
	}

	private String caseExpression(List<String> scope, int depth) {
		StringBuilder out = new StringBuilder("CASE ");
		boolean simple = random.nextBoolean();
		if (simple) {
			out.append(expression(scope, depth - 1)).append(' ');
		}
		int whens = 1 + random.nextInt(2);
		for (int i = 0; i < whens; i++) {
			out.append("WHEN ").append(expression(scope, depth - 1)).append(" THEN ")
				.append(expression(scope, depth - 1)).append(' ');
		}
		if (random.nextBoolean()) {
			out.append("ELSE ").append(expression(scope, depth - 1)).append(' ');
		}
		return out.append("END").toString();
	}

	private String atom(List<String> scope) {
		int choice = random.nextInt(10);
		if (choice < 4 && !scope.isEmpty()) {
			String variable = pick(scope);
			return random.nextBoolean() ? variable : variable + "." + pick(KEYS);
		}
		return switch (choice) {
			case 4 -> "$" + pick(new String[] { "p", "q", "list", "map", "missing" });
			case 5 -> pick(new String[] { "0", "1", "-1", "2", "7", "9223372036854775807", "-9223372036854775808" });
			case 6 -> pick(new String[] { "0.5", "-0.0", "1.0", "1e308", "2.5", "(0.0 / 0.0)" });
			case 7 -> pick(new String[] { "''", "'a'", "'1'", "'😀x'", "'ß'", "'2.5'", "' 3 '" });
			case 8 -> pick(new String[] { "null", "true", "false", "[]", "{}" });
			default -> random.nextBoolean() ? "range(0, " + random.nextInt(5) + ")" : "[1, null, 'a']";
		};
	}

	// ---------------------------------------------------------------- mutation and noise

	/** A seed query, damaged: token-level edits mostly, a character-level one sometimes. */
	public String mutate(String seed) {
		List<String> tokens = new ArrayList<>(Arrays.asList(seed.split("(?<=[ (),\\[\\]{}:])|(?=[ (),\\[\\]{}:])")));
		int edits = 1 + random.nextInt(3);
		for (int e = 0; e < edits && !tokens.isEmpty(); e++) {
			int at = random.nextInt(tokens.size());
			switch (random.nextInt(7)) {
				case 0 -> tokens.remove(at);
				case 1 -> tokens.add(at, " " + pick(VOCABULARY) + " ");
				case 2 -> tokens.set(at, " " + pick(VOCABULARY) + " ");
				case 3 -> tokens.add(at, tokens.get(random.nextInt(tokens.size())));
				case 4 -> {
					int other = random.nextInt(tokens.size());
					String swap = tokens.get(at);
					tokens.set(at, tokens.get(other));
					tokens.set(other, swap);
				}
				case 5 -> {
					// Truncate: a query cut off part way is what a dropped connection sends.
					while (tokens.size() > at) {
						tokens.remove(tokens.size() - 1);
					}
				}
				default -> {
					String token = tokens.get(at);
					if (!token.isEmpty()) {
						int c = random.nextInt(token.length());
						tokens.set(at, token.substring(0, c) + (char) random.nextInt(0x3000) + token.substring(c + 1));
					}
				}
			}
		}
		return String.join("", tokens);
	}

	/** Tokens from the vocabulary in no particular order, and raw characters. */
	public String noise() {
		StringBuilder out = new StringBuilder();
		int length = random.nextInt(14);
		for (int i = 0; i < length; i++) {
			if (random.nextInt(6) == 0) {
				out.append((char) random.nextInt(0x10000));
			} else {
				out.append(pick(VOCABULARY)).append(random.nextBoolean() ? " " : "");
			}
		}
		return out.toString();
	}

	/** Parameters for {@code $p}, {@code $q}, {@code $list} and {@code $map}, each of a random type, sometimes absent. */
	public Map<String, Object> parameters() {
		Map<String, Object> parameters = new HashMap<>();
		for (String name : new String[] { "p", "q", "list", "map" }) {
			if (random.nextInt(5) != 0) {
				parameters.put(name, value(2));
			}
		}
		return parameters;
	}

	private Object value(int depth) {
		return switch (random.nextInt(depth > 0 ? 11 : 9)) {
			case 0 -> null;
			case 1 -> random.nextInt(10);
			case 2 -> (long) random.nextInt(10) - 3;
			case 3 -> random.nextBoolean() ? Long.MAX_VALUE : Long.MIN_VALUE;
			case 4 -> random.nextBoolean() ? 1.5 : Double.NaN;
			case 5 -> 2.5f;
			case 6 -> pick(new String[] { "", "a", "1", "😀", "\uD83D", "x\u0000y" });
			case 7 -> random.nextBoolean();
			case 8 -> new float[] { 1f, 2f };
			case 9 -> {
				List<Object> list = new ArrayList<>();
				int size = random.nextInt(3);
				for (int i = 0; i < size; i++) {
					list.add(value(depth - 1));
				}
				yield list;
			}
			default -> {
				Map<String, Object> map = new LinkedHashMap<>();
				map.put(pick(KEYS), value(depth - 1));
				if (random.nextBoolean()) {
					map.put(random.nextBoolean() ? "\uDE00" : "k", value(depth - 1));
				}
				yield map;
			}
		};
	}

	private String pick(String[] options) {
		return options[random.nextInt(options.length)];
	}

	private String pick(List<String> options) {
		return options.get(random.nextInt(options.size()));
	}
}
