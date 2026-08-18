package io.metaloom.graph.cypher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.GraphAccess;
import io.metaloom.graph.cypher.exec.QueryExecutor;
import io.metaloom.graph.cypher.io.CsvSource;
import io.metaloom.graph.cypher.parse.CypherParser;

/**
 * Runs a read-only openCypher query against a store.
 *
 * <pre>{@code
 * CypherResult result = Cypher.query(store,
 *     "MATCH (p:Person)-[:KNOWS]->(f) WHERE p.age > $min RETURN f.name, count(*) AS mutual ORDER BY mutual DESC",
 *     Map.of("min", 30));
 * for (CypherRow row : result) {
 *     System.out.println(row.get("f.name"));
 * }
 * }</pre>
 *
 * <h2>Two shapes, one execution path</h2>
 *
 * <p>
 * {@link #query} materialises and {@link #stream} pushes rows to a {@link RowSink} as they are produced. They are not
 * two implementations: {@code query} <em>is</em> {@code stream} with a sink that collects, so there is one place the
 * read view is managed and no way for the two to drift apart.
 * </p>
 *
 * <p>
 * Use {@code stream} when the result may be large, when the caller wants the first row before the last one is found,
 * or when the query has to be stoppable — a deadline or a cancellation can only be honoured between rows, and
 * {@link RowSink#row} returning {@code false} is where that happens.
 * </p>
 *
 * <h2>Isolation</h2>
 *
 * <p>
 * Every query runs inside a {@code SNAPSHOT} read view, so a query that scans and then expands cannot observe a mix of
 * commits — without one, a writer committing mid-query could make the same query see a node in its scan and a
 * different graph by the time it expanded from it.
 * </p>
 *
 * <p>
 * <b>If the caller already has a view open, the query joins it</b> rather than opening another. Views are
 * thread-confined and refuse to nest, so opening one unconditionally would make {@code Cypher.query} unusable inside a
 * caller's own snapshot — which is exactly where someone wanting several queries over one consistent state would put
 * it.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
public final class Cypher {

	private Cypher() {
	}

	public static CypherResult query(GraphStore store, String query) {
		return query(store, query, Map.of());
	}

	public static CypherResult query(GraphStore store, String query, Map<String, Object> parameters) {
		return query(store, CypherParser.parse(query), parameters);
	}

	/**
	 * Runs an already-parsed query.
	 *
	 * <p>
	 * Parsing is pure and cheap and running is neither, so a server can parse on the thread that accepted the request
	 * and only then pay for a worker and a read view. A syntax error — much the most common failure — then costs
	 * neither.
	 * </p>
	 */
	public static CypherResult query(GraphStore store, Ast.Query parsed, Map<String, Object> parameters) {
		Collector collector = new Collector();
		stream(store, parsed, parameters, collector);
		return new CypherResult(collector.columns, collector.rows, collector.stats);
	}

	/**
	 * Runs a query and pushes its rows to a sink as they are produced.
	 *
	 * <p>
	 * <b>What this does and does not bound.</b> The sink never receives a materialised result, so a caller encoding
	 * rows onto a socket never builds the whole payload — which is the difference between a bounded and an unbounded
	 * client, and the difference between a first row after the last one is found and a first row after the first one
	 * is. It does <b>not</b> yet bound what the executor holds: rows are produced a clause at a time over a list, so a
	 * query's peak memory here is still its widest intermediate result. See spec/CYPHER.md.
	 * </p>
	 *
	 * <p>
	 * Runs entirely on the calling thread, which is what makes it safe: the read view is opened, used and closed by
	 * one thread, and the sink is called from that same thread. {@link RowSink} states the rules an implementation has
	 * to keep.
	 * </p>
	 */
	public static void stream(GraphStore store, String query, Map<String, Object> parameters, RowSink sink) {
		stream(store, CypherParser.parse(query), parameters, sink, QueryAbort.NEVER);
	}

	/** @see #stream(GraphStore, String, Map, RowSink) */
	public static void stream(GraphStore store, Ast.Query parsed, Map<String, Object> parameters, RowSink sink) {
		stream(store, parsed, parameters, sink, QueryAbort.NEVER);
	}

	/**
	 * Runs a query that can be stopped from outside.
	 *
	 * <p>
	 * There are two ways to stop, and a caller enforcing a deadline needs both. {@link RowSink#row} returning
	 * {@code false} stops <em>delivery</em>, which is enough once rows are flowing. {@link QueryAbort} stops
	 * <em>execution</em>, which is the one that matters for a query that has not produced a row yet — a scan of ten
	 * million nodes reaches the sink exactly once, at the end, so a between-rows check alone would let it run to
	 * completion and only then notice it had been abandoned.
	 * </p>
	 *
	 * <p>
	 * The signal is consulted inside {@link GraphAccess}, on a fraction of store touches, and raises
	 * {@link QueryAbortedException} — which unwinds the executor and closes the read view on its way out.
	 * </p>
	 */
	public static void stream(GraphStore store, Ast.Query parsed, Map<String, Object> parameters, RowSink sink,
		QueryAbort abort) {
		stream(store, parsed, parameters, sink, abort, CsvSource.DENY);
	}

	/**
	 * Runs a query that may read a file, against a source that decides which files it may read.
	 *
	 * <p>
	 * The only entry point through which {@code LOAD CSV} can do anything: every other overload passes
	 * {@link CsvSource#DENY}, so a caller that has not thought about file access cannot be made to perform it by a query
	 * string. {@link CsvSource} says why that is the default rather than a convenience.
	 * </p>
	 *
	 * <p>
	 * The source is consulted per {@code LOAD CSV} clause, per execution, with the URL exactly as the query wrote it.
	 * Resolving it — and refusing it — is the source's job, because the policy depends on how the caller is exposed and
	 * this module cannot know that.
	 * </p>
	 */
	public static void stream(GraphStore store, Ast.Query parsed, Map<String, Object> parameters, RowSink sink,
		QueryAbort abort, CsvSource csv) {
		if (parsed.isWrite()) {
			write(store, parsed, parameters, sink, abort, csv);
			return;
		}
		// Joins the caller's view when there is one. Opening a second would be refused, and closing theirs at the end
		// of this query would be worse than being refused.
		ReadTransaction view = store.hasReadTransaction() ? null : store.beginRead();
		try {
			// Inside the try, so the sink sees every row while the view it was read under is still open. A sink that
			// stops early simply ends the loop; that is an outcome, and the finally still closes the view.
			new QueryExecutor(new GraphAccess(store, abort), parameters, csv)
				.stream(parsed, abortChecking(sink, abort));
		} finally {
			if (view != null) {
				view.close();
			}
		}
	}

	/**
	 * Wraps a sink so the abort signal is consulted between rows as well as inside the scan.
	 *
	 * <p>
	 * Both are needed and neither replaces the other. {@link GraphAccess} checks while it reads, which is what stops a
	 * scan that has not produced a row yet; this checks while rows flow, which is what stops a query whose remaining
	 * cost is in the sink rather than in the store.
	 * </p>
	 */
	private static RowSink abortChecking(RowSink sink, QueryAbort abort) {
		if (abort == QueryAbort.NEVER) {
			return sink;
		}
		return new RowSink() {

			@Override
			public void columns(List<String> columns) {
				sink.columns(columns);
			}

			@Override
			public boolean row(List<Object> values) {
				String reason = abort.abortReason();
				if (reason != null) {
					throw new QueryAbortedException(reason);
				}
				return sink.row(values);
			}

			@Override
			public void stats(CypherStats stats) {
				sink.stats(stats);
			}
		};
	}

	/**
	 * Runs a query that changes the store, in one transaction, committing before it delivers a single row.
	 *
	 * <p>
	 * <b>No read view.</b> A writer reads the current mapping rather than a view — that is what prevents a lost update
	 * — so a view here would be ignored by the storage layer while still costing this thread a liveness check on every
	 * operation, which an abandoned view can fail. The transaction's own overlay is what makes the query's reads
	 * consistent, and it is also what makes them see the query's own writes.
	 * </p>
	 *
	 * <p>
	 * <b>Nothing is streamed.</b> The rows are produced, the transaction commits, and only then does the sink see
	 * anything. A sink that had already received rows when the commit failed would have been handed a result from a
	 * transaction that no longer exists — and on the far side of a socket those rows cannot be recalled. So a write
	 * query's result is materialised, which is a real cost and is stated in spec/CYPHER.md rather than implied away by
	 * the name of this method.
	 * </p>
	 *
	 * <p>
	 * A caller who opened the transaction keeps it: the query joins it, commits nothing, and its writes become durable
	 * when the caller commits. That is what makes several queries into one atomic unit.
	 * </p>
	 */
	private static void write(GraphStore store, Ast.Query parsed, Map<String, Object> parameters, RowSink sink,
		QueryAbort abort, CsvSource csv) {
		if (store.hasReadTransaction()) {
			throw new CypherWriteException("A write query cannot run inside a read view. The view fixes what reads "
				+ "see at the instant it opened, and a writer has to read the current state — so this query would "
				+ "silently not get the isolation the view was opened for. Close the view first.");
		}
		if (periodicCommitOf(parsed) > 0) {
			writeInBatches(store, parsed, parameters, sink, abort, csv);
			return;
		}
		if (store.hasWriteTransaction()) {
			// The caller's transaction. Their commit, their conflict: this query is part of a unit of work somebody
			// else is assembling, so it neither commits nor retries.
			deliver(new QueryExecutor(new GraphAccess(store, abort), parameters, csv).run(parsed), sink, abort);
			return;
		}
		// Its own transaction, and therefore its own conflicts to absorb.
		//
		// A write query is one statement. Writers run concurrently, so two of them touching the same node — or, far
		// more often, inserting into the same index leaf — collide, and the commit of the loser is refused. There is
		// nothing for the caller to reconsider about a single query, so it is re-run against the newer state, which is
		// the same bargain store.setNodeProperties() makes for a single mutation.
		//
		// Re-running is safe because the query has produced nothing yet — a write query commits before it delivers a
		// row, which is the property the javadoc above is built on — and because a refused transaction has written no
		// log record and touched no data file.
		//
		// inStatement() rather than a retry loop of its own. The loop that used to be here was the engine's loop
		// copied, and it had the engine's defect with it: a bounded number of optimistic attempts is not a guarantee
		// of progress, because the writer that beat you does not leave, and CypherConcurrencyTest refused a query
		// over it. The engine now owns the escalation that makes the promise true for both paths at once.
		try {
			QueryExecutor.Result result = store.inStatement(
				() -> new QueryExecutor(new GraphAccess(store, abort), parameters, csv).run(parsed));
			// Outside the statement: delivery must happen once, and the statement may run more than once.
			deliver(result, sink, abort);
			// Nothing streams here by design; see the javadoc above.
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The batch size of a {@code USING PERIODIC COMMIT} query, or 0. Only a single-part query can carry one. */
	private static int periodicCommitOf(Ast.Query parsed) {
		return parsed.parts().size() == 1 ? parsed.parts().get(0).periodicCommit() : 0;
	}

	/**
	 * {@code USING PERIODIC COMMIT}: the load, in transactions of {@code n} rows.
	 *
	 * <p>
	 * The clause exists because of a property of everything above it. A write query cannot stream — it commits before
	 * delivering a row — so {@code LOAD CSV … CREATE} runs through the materialised path, and its peak memory is the
	 * whole file. That is fine for a file somebody wrote by hand and useless for the case this clause is for. Batching
	 * bounds both the heap and the transaction: {@code n} rows are read, the rest of the query runs over just those
	 * rows, that transaction commits, and the reader continues from where it stopped.
	 * </p>
	 *
	 * <h2>It gives up atomicity, and says so</h2>
	 *
	 * <p>
	 * <b>The query is no longer one transaction, so it can fail half-applied.</b> If row 70,000 of 100,000 violates a
	 * constraint, the batches before it are committed and durable and there is nothing left to roll back — the
	 * transaction that would have done so ended successfully some time ago. This is exactly how the clause behaves in
	 * other engines, and it is the trade being asked for: a bounded transaction cannot also be an all-or-nothing one.
	 * The failure is re-thrown with the row count that survived, because "some prefix of your file is loaded" is only
	 * actionable if the caller is told which prefix. Writing the load with {@code MERGE} rather than {@code CREATE}
	 * makes the retry idempotent, which is the practical answer and is why the documentation leads with it.
	 * </p>
	 *
	 * <h2>What it refuses</h2>
	 *
	 * <p>
	 * <b>A caller's own transaction.</b> Everywhere else a write query joins one the caller opened, and commits
	 * nothing; here committing is the entire mechanism. Doing that to somebody else's transaction would end a unit of
	 * work they were still assembling, so it is refused rather than either silently ignoring the batching or silently
	 * committing on their behalf.
	 * </p>
	 */
	private static void writeInBatches(GraphStore store, Ast.Query parsed, Map<String, Object> parameters,
		RowSink sink, QueryAbort abort, CsvSource csv) {
		if (store.hasWriteTransaction()) {
			throw new CypherWriteException("USING PERIODIC COMMIT cannot run inside a transaction the caller opened: "
				+ "committing every batch would end that transaction, and this query does not get to decide when a "
				+ "caller's unit of work is finished. Run it on its own, or drop the PERIODIC COMMIT.");
		}
		Ast.SingleQuery part = parsed.parts().get(0);
		int batchSize = part.periodicCommit();
		QueryExecutor executor = new QueryExecutor(new GraphAccess(store, abort), parameters, csv);

		// The transaction in flight, and the rows behind the last boundary. Both are single-element arrays because the
		// boundary is a callback: the executor decides when a batch ends, and this is where that becomes a commit.
		Transaction[] open = { null };
		long[] committed = { 0 };
		CypherStats stats;
		try {
			stats = executor.runBatched(part, batchSize, new QueryExecutor.BatchBoundary() {

				@Override
				public void begin() {
					open[0] = store.begin();
				}

				@Override
				public void commit(long rowsCommitted) {
					try {
						open[0].commit();
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					} finally {
						open[0].close();
						open[0] = null;
					}
					committed[0] = rowsCommitted;
				}
			});
		} catch (RuntimeException e) {
			throw partiallyApplied(e, committed[0], batchSize);
		} finally {
			// The batch that was in flight when something threw. close() without commit() rolls it back, so the store
			// holds whole batches or nothing — never half of one.
			if (open[0] != null) {
				open[0].close();
			}
		}
		// Counters and no rows, in the same way CREATE with no RETURN is.
		sink.columns(List.of());
		sink.stats(stats);
	}

	/**
	 * Re-throws a failed batched load with the one fact its caller cannot otherwise recover: how much of it is durable.
	 *
	 * <p>
	 * Naming the surviving prefix is the difference between a load that can be reasoned about and one that has to be
	 * investigated by querying the store. The advice is {@code MERGE} rather than a resume offset, because an offset
	 * would be wrong the moment the file has a header or the failure was itself a duplicate.
	 * </p>
	 */
	private static RuntimeException partiallyApplied(RuntimeException cause, long committed, int batchSize) {
		if (committed == 0) {
			// Nothing was committed, so the transaction that rolled back holds everything this query did. That is
			// ordinary failure and does not need the explanation below.
			throw cause;
		}
		return new CypherWriteException("The load failed after " + committed + " rows were already committed, in "
			+ "batches of " + batchSize + ". USING PERIODIC COMMIT is not one transaction, so those rows are durable "
			+ "and cannot be rolled back — re-running is safe only if the load is written with MERGE rather than "
			+ "CREATE. Cause: " + cause.getMessage(), cause);
	}

	private static void deliver(QueryExecutor.Result result, RowSink sink, QueryAbort abort) {
		sink.columns(result.columns());
		for (List<Object> row : result.rows()) {
			String reason = abort.abortReason();
			if (reason != null) {
				throw new QueryAbortedException(reason);
			}
			if (!sink.row(row)) {
				return;
			}
		}
		sink.stats(result.stats());
	}

	/** Parses without running, for a caller that wants to validate a query or inspect its shape. */
	public static Ast.Query parse(String query) {
		return CypherParser.parse(query);
	}

	/**
	 * Turns the streaming form back into the materialised one.
	 *
	 * <p>
	 * {@link #query} is this and nothing else, so the two paths cannot drift apart: there is one execution path and
	 * one place the read view is managed, and the blocking API is a sink that says yes to every row.
	 * </p>
	 */
	private static final class Collector implements RowSink {

		private List<String> columns = List.of();
		private final List<List<Object>> rows = new ArrayList<>();
		private CypherStats stats = CypherStats.NONE;

		@Override
		public void columns(List<String> columns) {
			this.columns = columns;
		}

		@Override
		public boolean row(List<Object> values) {
			rows.add(values);
			return true;
		}

		@Override
		public void stats(CypherStats stats) {
			this.stats = stats;
		}
	}
}
