package io.metaloom.graph.cypher.integration;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.CypherWriteException;
import io.metaloom.graph.cypher.QueryAbort;
import io.metaloom.graph.cypher.QueryAbortedException;
import io.metaloom.graph.cypher.RandomQueries;
import io.metaloom.graph.cypher.RowSink;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.exec.GraphAccess;
import io.metaloom.graph.cypher.exec.QueryExecutor;
import io.metaloom.graph.cypher.io.CsvAccessException;
import io.metaloom.graph.cypher.io.CsvFormatException;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;

/**
 * Every input ends in one of the documented outcomes, and none of them hangs.
 *
 * <p>
 * The outcomes are a result, or one of the exceptions a caller can classify: a syntax error with a position, a type
 * error, a refused write, an abort, a CSV refusal, a constraint violation. Anything else — a
 * {@code NullPointerException}, a {@code ClassCastException}, an index out of bounds, a {@code NumberFormatException}
 * from a parser that trusted a token, a {@code StackOverflowError} — is a defect, whatever the input: the server reports
 * it as an internal error, so a mistake in a query becomes the server's fault in the logs and on the dashboard. The
 * {@code IllegalArgumentException} the store raises for a value it cannot hold is on that list too, because it is the
 * query layer's job to refuse such a value first, as a type error that names it.
 * </p>
 *
 * <p>
 * Inputs come from three sources: {@link RandomQueries}' grammar, which reaches the executor; mutations of the external
 * corpus and of the grammar's own output, which reach the parser's error paths; and noise. Every write runs in a
 * transaction that is rolled back, so the graph is the same for every input, and the store must pass
 * {@code verify(FULL)} at the end and hold exactly what it held at the start — a rollback that left a trace is a
 * finding too.
 * </p>
 *
 * <p>
 * Two oracles beyond the exception contract, because a wrong answer throws nothing: every read that succeeds is run
 * again through the materialised executor and must give the same rows in the same order as the streaming one, and the
 * graph's contents are compared before and after the whole run.
 * </p>
 *
 * <p>
 * <b>Replayable.</b> Input {@code k} of seed {@code s} is drawn from its own {@link Random}, so a failure names the
 * pair and {@link #replay} regenerates exactly that input. The failure message also carries the input reduced to a
 * minimal form that still fails the same way. {@code -Dcypher.fuzz.seed} and {@code -Dcypher.fuzz.iterations} change
 * the run; the {@code fuzz}-tagged method is the long one ({@code mvn test -Pall-tests}).
 * </p>
 */
@Tag("unit")
public class CypherFuzzTest {

	private static final long SEED = Long.getLong("cypher.fuzz.seed", 0x5EED_CAFEL);
	private static final int ITERATIONS = Integer.getInteger("cypher.fuzz.iterations", 3_000);

	/** The deadline each input runs under. Short: the point is that the engine honours it, not that inputs finish. */
	private static final long BUDGET_MILLIS = 2_000;

	/** How long an input may take before it counts as a hang: the budget, and generous room for the JIT and GC. */
	private static final long HANG_SECONDS = 30;

	/** Rows a sink takes before saying it has had enough, which also exercises every early-stop path. */
	private static final int ROW_CAP = 500;

	/** The exceptions a caller is promised. Anything else escaping a query is a defect. */
	private static final Set<Class<? extends Throwable>> DOCUMENTED = Set.of(CypherSyntaxException.class,
		CypherTypeException.class, CypherWriteException.class, QueryAbortedException.class, CsvAccessException.class,
		CsvFormatException.class, ConstraintViolationException.class);

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testEveryInputEndsInADocumentedOutcome() throws Exception {
		run(SEED, ITERATIONS);
	}

	/** The long run, under a fresh seed each time — which it prints, because that is what makes a failure replayable. */
	@Test
	@Tag("fuzz")
	public void testEveryInputEndsInADocumentedOutcomeAtLength() throws Exception {
		long seed = Long.getLong("cypher.fuzz.seed", System.nanoTime());
		System.out.println("CypherFuzzTest long run, seed " + seed);
		run(seed, Integer.getInteger("cypher.fuzz.iterations", 200_000));
	}

	// ---------------------------------------------------------------- the run

	private record Input(String query, Map<String, Object> parameters) {
	}

	private record Failure(String signature, Throwable error, long seed, int iteration, Input input, Input minimal) {
	}

	private void run(long seed, int iterations) throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cypher-fuzz");
			thread.setDaemon(true);
			return thread;
		});
		try (GraphStore store = stores.open()) {
			RandomQueries.seedGraph(store, new Random(seed), 40);
			String before = contents(store);
			List<String> seeds = corpus();
			Map<String, Failure> failures = new LinkedHashMap<>();
			Map<String, Integer> outcomes = new java.util.TreeMap<>();
			for (int k = 0; k < iterations; k++) {
				Input input = replay(seed, k, seeds);
				Throwable error = execute(worker, store, input);
				outcomes.merge(error == null ? "ok" : error.getClass().getSimpleName(), 1, Integer::sum);
				if (error != null && !DOCUMENTED.contains(error.getClass())) {
					String signature = signature(error);
					if (!failures.containsKey(signature)) {
						failures.put(signature, new Failure(signature, error, seed, k, input,
							minimise(worker, store, input, signature)));
					}
				}
			}
			store.verify(VerifyLevel.FULL);
			if (!failures.isEmpty()) {
				fail(report(failures.values(), outcomes));
			}
			System.out.println("CypherFuzzTest seed " + seed + ", " + iterations + " inputs: " + outcomes);
			if (!before.equals(contents(store))) {
				fail("Every write ran in a transaction that was rolled back, yet the graph changed");
			}
			// Proof the run reached the executor rather than bouncing off the parser every time.
			int executed = outcomes.getOrDefault("ok", 0);
			if (executed < iterations / 10) {
				fail("Only " + executed + " of " + iterations + " inputs ran to a result; the generator is not reaching "
					+ "the executor. Outcomes: " + outcomes);
			}
		} finally {
			worker.shutdownNow();
		}
	}

	/** Input {@code k} of seed {@code seed}, regenerated from nothing but the pair. */
	static Input replay(long seed, int k, List<String> seeds) {
		Random random = new Random(seed * 0x9E3779B97F4A7C15L + k);
		RandomQueries queries = new RandomQueries(random);
		int source = random.nextInt(20);
		String query;
		if (source < 11) {
			query = queries.query();
		} else if (source < 17) {
			query = queries.mutate(random.nextBoolean() ? seeds.get(random.nextInt(seeds.size())) : queries.query());
		} else {
			query = queries.noise();
		}
		return new Input(query, queries.parameters());
	}

	/** The external corpus's queries and dumps, plus the statements the specs use as examples. */
	private static List<String> corpus() {
		List<String> seeds = new ArrayList<>();
		for (Corpus.Query query : Corpus.queries()) {
			seeds.add(query.cypher());
		}
		for (String dataset : Corpus.datasets()) {
			List<String> statements = Corpus.statements(dataset);
			seeds.addAll(statements.subList(0, Math.min(5, statements.size())));
		}
		seeds.addAll(List.of(
			"MATCH (p:Person)-[:KNOWS*1..2]->(f) WHERE p.age > $min RETURN f.name, count(*) AS paths ORDER BY paths DESC",
			"MERGE (n:Person {email: $e}) ON CREATE SET n.at = timestamp() ON MATCH SET n.seen = true",
			"MATCH (n:Person) SET n.age = 35, n:Employee, n += {city: 'Bonn'}",
			"MATCH (n:Person) REMOVE n.age, n:Employee",
			"MATCH (n:Stale) DETACH DELETE n",
			"MATCH p = shortestPath((a:A {i: 1})-[:R*..10]-(b:B)) RETURN size(nodes(p)) AS nodes",
			"CASE WHEN n.score >= 80 THEN 'high' WHEN n.score >= 0 THEN 'low' ELSE 'unknown' END",
			"LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row MERGE (p:Person {id: row.id})",
			"USING PERIODIC COMMIT 10 LOAD CSV FROM $file AS row FIELDTERMINATOR ';' CREATE (:Row {v: row[0]})",
			"CALL vector.query('A', 'l', [1.0, 2.0], 3) YIELD node, score RETURN node, score",
			"UNWIND range(1, 3) AS x WITH x, x * 2 AS y WHERE y > 2 RETURN DISTINCT x ORDER BY x DESC SKIP 1 LIMIT 1",
			"MATCH (a)-[r]-(b) WITH a, collect(r) AS rs RETURN a, size(rs) AS n UNION ALL RETURN null AS a, 0 AS n"));
		return seeds;
	}

	/** Parses and runs one input on the worker, returning what it threw, or null. A hang fails the test outright. */
	private static Throwable execute(ExecutorService worker, GraphStore store, Input input) throws Exception {
		Future<Throwable> outcome = worker.submit(() -> {
			try {
				runOnce(store, input);
				return null;
			} catch (Throwable error) {
				return error;
			}
		});
		try {
			return outcome.get(HANG_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			outcome.cancel(true);
			fail("This input did not finish in " + HANG_SECONDS + " s under a " + BUDGET_MILLIS + " ms deadline, which "
				+ "the engine is meant to enforce while it runs:\n  " + input.query() + "\n  parameters " + render(input));
			return null;
		}
	}

	private static void runOnce(GraphStore store, Input input) throws Exception {
		Ast.Query parsed = Cypher.parse(input.query());
		QueryAbort abort = QueryAbort.deadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MILLIS),
			BUDGET_MILLIS);
		int[] rows = { 0 };
		RowSink sink = values -> ++rows[0] < ROW_CAP;
		if (!parsed.isWrite()) {
			Cypher.stream(store, parsed, input.parameters(), sink, abort);
			bothPathsAgree(store, parsed, input);
			return;
		}
		// Joined by the query and never committed: closing it rolls back, so every input sees the same graph.
		try (Transaction ignored = store.begin()) {
			Cypher.stream(store, parsed, input.parameters(), sink, abort);
		}
	}

	/** Thrown when an oracle disagrees, so that it is reported, signed and minimised like any other defect. */
	static final class Disagreement extends RuntimeException {

		private static final long serialVersionUID = 1L;

		Disagreement(String message) {
			super(message);
		}
	}

	/**
	 * A read that succeeded must give the same rows, in the same order, through the materialised executor as through
	 * the streaming one — the contract CYPHER.md §7.1 states and LazyExecutionTest checks for hand-picked queries. This
	 * checks it for every query the grammar happens to make: {@code stream} chooses the push path when every clause
	 * allows it, and {@code run} never does.
	 */
	private static void bothPathsAgree(GraphStore store, Ast.Query parsed, Input input) {
		if (input.query().contains("timestamp")) {
			return;
		}
		QueryExecutor.Result materialised;
		List<List<Object>> streamed = new ArrayList<>();
		List<String> columns = new ArrayList<>();
		try (var view = store.beginRead()) {
			QueryAbort abort = QueryAbort.deadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MILLIS),
				BUDGET_MILLIS);
			try {
				materialised = new QueryExecutor(new GraphAccess(store, abort), input.parameters()).run(parsed);
				new QueryExecutor(new GraphAccess(store, abort), input.parameters()).stream(parsed, new RowSink() {
					@Override
					public void columns(List<String> names) {
						columns.addAll(names);
					}

					@Override
					public boolean row(List<Object> values) {
						streamed.add(values);
						return true;
					}
				});
			} catch (QueryAbortedException e) {
				// Too big to compare inside the budget: no verdict, rather than a false one.
				return;
			}
		}
		if (!materialised.columns().equals(columns) || !materialised.rows().equals(streamed)) {
			throw new Disagreement("The streaming and the materialised executor disagree.\n    materialised "
				+ materialised.columns() + " " + materialised.rows() + "\n    streamed     " + columns + " " + streamed);
		}
	}

	/** Every label, property and outgoing edge of every node, in a canonical order: what a rollback must not change. */
	private static String contents(GraphStore store) {
		List<String> nodes = new ArrayList<>();
		store.forEachNode(id -> {
			List<String> edges = new ArrayList<>();
			for (io.metaloom.graph.core.api.Edge edge : store.neighbours(id, io.metaloom.graph.core.api.Direction.OUT)) {
				edges.add(edge.type() + "->" + edge.neighbourId() + (edge.isMaterialised()
					? store.relationshipProperties(edge.relationshipId()).toString() : ""));
			}
			java.util.Collections.sort(edges);
			nodes.add(id + new java.util.TreeSet<>(store.nodeLabels(id)).toString()
				+ store.nodeProperties(id) + edges);
		});
		java.util.Collections.sort(nodes);
		return String.join("\n", nodes);
	}

	// ---------------------------------------------------------------- reporting

	/** The exception's class and the first frame of this project's code it passed through, which names the defect. */
	private static String signature(Throwable error) {
		for (StackTraceElement frame : error.getStackTrace()) {
			if (frame.getClassName().startsWith("io.metaloom.")) {
				return error.getClass().getName() + " at " + frame.getClassName() + "." + frame.getMethodName() + ":"
					+ frame.getLineNumber();
			}
		}
		return error.getClass().getName();
	}

	/**
	 * The input reduced while it still fails with the same signature: delta debugging over characters, coarse chunks
	 * first. The parameters are kept as they are; the query is where the shape of a defect lives.
	 */
	private static Input minimise(ExecutorService worker, GraphStore store, Input input, String signature)
		throws Exception {
		String query = input.query();
		int chunk = Math.max(1, query.length() / 2);
		int attempts = 0;
		while (chunk >= 1 && attempts < 2_000) {
			boolean reduced = false;
			for (int start = 0; start < query.length() && attempts < 2_000; start += chunk) {
				String candidate = query.substring(0, start) + query.substring(Math.min(query.length(), start + chunk));
				attempts++;
				Throwable error = execute(worker, store, new Input(candidate, input.parameters()));
				if (error != null && signature(error).equals(signature)) {
					query = candidate;
					reduced = true;
					start -= chunk;
				}
			}
			if (!reduced) {
				chunk /= 2;
			}
		}
		return new Input(query, input.parameters());
	}

	private static String report(java.util.Collection<Failure> failures, Map<String, Integer> outcomes) {
		StringBuilder out = new StringBuilder(failures.size() + " undocumented outcome(s). All outcomes: " + outcomes);
		for (Failure failure : failures) {
			out.append("\n\n").append(failure.signature()).append(": ").append(failure.error().getMessage())
				.append("\n  seed ").append(failure.seed()).append(" input ").append(failure.iteration())
				.append("\n  query     ").append(failure.input().query())
				.append("\n  minimal   ").append(failure.minimal().query())
				.append("\n  parameters ").append(render(failure.input()));
			java.io.StringWriter trace = new java.io.StringWriter();
			failure.error().printStackTrace(new java.io.PrintWriter(trace));
			out.append('\n').append(trace.toString().lines().limit(12).reduce("", (a, b) -> a + "\n    " + b));
		}
		return out.toString();
	}

	private static String render(Input input) {
		Map<String, Object> shown = new LinkedHashMap<>();
		input.parameters().forEach((key, value) -> shown.put(key,
			value instanceof float[] vector ? java.util.Arrays.toString(vector) : value));
		return shown.toString();
	}
}
