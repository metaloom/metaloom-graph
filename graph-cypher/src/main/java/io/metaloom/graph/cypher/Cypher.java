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
		if (parsed.isWrite()) {
			write(store, parsed, parameters, sink, abort);
			return;
		}
		// Joins the caller's view when there is one. Opening a second would be refused, and closing theirs at the end
		// of this query would be worse than being refused.
		ReadTransaction view = store.hasReadTransaction() ? null : store.beginRead();
		try {
			QueryExecutor.Result result = new QueryExecutor(new GraphAccess(store, abort), parameters).run(parsed);
			// Inside the try, so the sink sees every row while the view it was read under is still open. A sink that
			// stops early simply ends the loop; that is an outcome, and the finally still closes the view.
			deliver(result, sink, abort);
		} finally {
			if (view != null) {
				view.close();
			}
		}
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
		QueryAbort abort) {
		if (store.hasReadTransaction()) {
			throw new CypherWriteException("A write query cannot run inside a read view. The view fixes what reads "
				+ "see at the instant it opened, and a writer has to read the current state — so this query would "
				+ "silently not get the isolation the view was opened for. Close the view first.");
		}
		// begin() does not refuse to nest, and a nested transaction is not caught but silently ruinous: committing the
		// inner one clears the overlay, after which the outer one's writes bypass the log entirely.
		Transaction txn = store.hasWriteTransaction() ? null : store.begin();
		try {
			QueryExecutor.Result result = new QueryExecutor(new GraphAccess(store, abort), parameters).run(parsed);
			if (txn != null) {
				txn.commit();
			}
			deliver(result, sink, abort);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			// Rolls back when commit() was not reached, which is every failure path including an abort mid-scan.
			if (txn != null) {
				txn.close();
			}
		}
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
