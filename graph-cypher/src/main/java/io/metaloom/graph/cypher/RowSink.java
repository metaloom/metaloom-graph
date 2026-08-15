package io.metaloom.graph.cypher;

import java.util.List;

/**
 * Where {@link Cypher#stream} puts its rows.
 *
 * <p>
 * The one thing this buys that {@link Cypher#query} cannot is a place to say <b>stop</b>. {@link #row} returns a
 * boolean, and returning {@code false} unwinds the query, closes its read view and returns — which is what makes a
 * deadline, a cancellation and a client that hung up all expressible. Without a per-row check there is nowhere to put
 * one: {@code executeBlocking} cannot interrupt a running task, so a query that has started can only be abandoned by
 * abandoning its <em>answer</em> while the scan keeps the thread and the view.
 * </p>
 *
 * <h2>Rules for an implementation</h2>
 *
 * <ul>
 * <li><b>Every call arrives on the thread that called {@code stream}</b>, which is the thread that owns the read view.
 * A sink must not hand the values it receives to another thread and then read more of the store from there — read
 * views are thread-confined, and a foreign thread does not get an error, it silently reads outside the snapshot.
 * <li><b>The values themselves are safe to move.</b> {@link io.metaloom.graph.cypher.value.CypherNode} and its
 * siblings are immutable records whose properties are read when the row is built, so a row holds nothing of the store
 * behind it. Encoding one and handing the bytes to another thread is fine; that is what the wire server does.
 * <li><b>Do not block indefinitely.</b> The caller owns the deadline, and it can only enforce it between rows. A sink
 * that parks forever on a full queue defeats every bound the caller has.
 * <li>The {@code List} passed to {@link #row} is <b>not retained</b> by the executor and may be used freely, but it is
 * only valid for the duration of the call if the sink wants to avoid copying.
 * </ul>
 */
@FunctionalInterface
public interface RowSink {

	/**
	 * The column names, in projection order, before any row.
	 *
	 * <p>
	 * Always called exactly once, and always before the first {@link #row}, including when the result has no rows at
	 * all. A protocol that sends a header frame can rely on that.
	 * </p>
	 */
	default void columns(List<String> columns) {
	}

	/**
	 * One row, in the column order given to {@link #columns}.
	 *
	 * @return {@code false} to stop producing. The query unwinds, the read view closes, and {@code stream} returns
	 *         normally — stopping early is an outcome, not an error.
	 */
	boolean row(List<Object> values);

	/**
	 * What the query changed, once, after the last row.
	 *
	 * <p>
	 * After rather than before, because a counter is only final when the query is. Not called at all when a sink
	 * stopped early by returning {@code false} from {@link #row}: the numbers describe a whole query, and a sink that
	 * stopped reading has no whole query to be told about. For a write query the store has still been changed by then
	 * — the commit happens before the first row is delivered — so this reports what happened rather than what will.
	 * </p>
	 */
	default void stats(CypherStats stats) {
	}
}
