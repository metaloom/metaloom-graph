package io.metaloom.graph.cypher.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.cypher.Cypher;

/**
 * The executor's own costs, on a graph small enough to sit in the page cache: projection, expression evaluation,
 * aggregation, sorting and expansion through anonymous nodes. What it measures is the query layer — per-row maps,
 * boxing, re-evaluation — rather than the storage engine, which has benchmarks of its own.
 *
 * <p>
 * It uses nothing but {@link Cypher#query} and the public store API, so the same class runs against an older build of
 * this module unchanged. That is how its before and after numbers are taken: two builds, one JVM each, run in turn
 * several times over so that neither gets the warmer machine. {@code main} prints one line per query in ms per
 * execution, the median of the timed runs.
 * </p>
 *
 * <pre>
 * mvn test -Pbench -pl graph-cypher -am -Dtest=CypherExecutionBenchmark
 * </pre>
 */
@Tag("bench")
public class CypherExecutionBenchmark {

	private static final int PEOPLE = 20_000;

	/** Every query is checked for its answer once before it is timed, so a fast wrong one cannot win. */
	static final Map<String, String> QUERIES = new LinkedHashMap<>();

	static {
		QUERIES.put("project+sort",
			"MATCH (n:Person) RETURN n.name AS name, n.age AS age, n.city AS city, n.score AS score ORDER BY age, name"
				+ " LIMIT 10");
		QUERIES.put("with-chain",
			"MATCH (n:Person) WITH n.age AS age, n.name AS name, n.city AS city WHERE age > 30 "
				+ "WITH age, name WHERE name STARTS WITH 'p1' RETURN count(*) AS c");
		QUERIES.put("arithmetic",
			"UNWIND range(1, 200000) AS x WITH x, x * 2 + 1 AS y, x % 7 AS z WHERE z = 3 RETURN sum(y) AS s");
		QUERIES.put("group",
			"MATCH (n:Person) RETURN n.city AS city, count(*) AS c, avg(n.age) AS a, max(n.score) AS m");
		QUERIES.put("two-hop-anon",
			"MATCH (a:Person)-[:KNOWS]->()-[:KNOWS]->(c) RETURN count(*) AS c");
		QUERIES.put("functions",
			"MATCH (n:Person) RETURN count(*) AS c, sum(size(toUpper(n.name))) AS s, "
				+ "sum(toInteger(substring(n.name, 1))) AS t");
	}

	@Test
	public void testRun() throws Exception {
		main(new String[0]);
	}

	public static void main(String[] args) throws Exception {
		int timed = args.length > 0 ? Integer.parseInt(args[0]) : 15;
		// An optional second argument names the one query to run, for a quieter measurement of one change.
		String only = args.length > 1 ? args[1] : null;
		Path dir = Files.createTempDirectory("cypher-bench-");
		try (GraphStore store = GraphStore.open(dir, GraphStoreOptions.defaults())) {
			seed(store);
			for (Map.Entry<String, String> query : QUERIES.entrySet()) {
				if (only != null && !only.equals(query.getKey())) {
					continue;
				}
				String answer = Cypher.query(store, query.getValue()).values().toString();
				// Warm up the JIT on this query before anything is counted.
				for (int i = 0; i < 5; i++) {
					Cypher.query(store, query.getValue());
				}
				double[] millis = new double[timed];
				for (int i = 0; i < timed; i++) {
					long start = System.nanoTime();
					String again = Cypher.query(store, query.getValue()).values().toString();
					millis[i] = (System.nanoTime() - start) / 1e6;
					if (!again.equals(answer)) {
						throw new IllegalStateException(query.getKey() + " changed its answer");
					}
				}
				Arrays.sort(millis);
				System.out.printf("BENCH %-14s %8.2f ms  (min %.2f)  %s%n", query.getKey(), millis[timed / 2], millis[0],
					answer.length() > 60 ? answer.substring(0, 60) + "..." : answer);
			}
		} finally {
			try (var walk = Files.walk(dir)) {
				for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(entry);
				}
			}
		}
	}

	private static void seed(GraphStore store) throws Exception {
		Random random = new Random(7);
		String[] cities = { "Bonn", "Köln", "Berlin", "Wien", "Graz", "Basel" };
		List<Long> people = new ArrayList<>(PEOPLE);
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < PEOPLE; i++) {
				people.add(store.createNode(List.of("Person"), Properties.of()
					.set("name", "p" + i)
					.set("age", (long) (18 + random.nextInt(60)))
					.set("city", cities[random.nextInt(cities.length)])
					.set("score", random.nextDouble())));
			}
			for (int i = 0; i < PEOPLE * 3; i++) {
				store.createEdge(people.get(random.nextInt(PEOPLE)), "KNOWS", people.get(random.nextInt(PEOPLE)));
			}
			txn.commit();
		}
	}
}
