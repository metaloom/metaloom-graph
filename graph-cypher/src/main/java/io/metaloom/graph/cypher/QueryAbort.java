package io.metaloom.graph.cypher;

/**
 * Asked, while a query runs, whether it should stop.
 *
 * <p>
 * A deadline and a cancellation are the same thing from the engine's side — someone outside wants this query to stop
 * — so they are one interface. What differs is the reason, which is why this returns a string rather than a boolean:
 * the caller that has to answer a client deserves to know <em>which</em> bound fired, and "the query took longer than
 * 30000 ms" and "the client hung up" lead to different responses and different operator reactions.
 * </p>
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>
 * There is no other way to stop a query that has started. {@code Thread.interrupt} is not honoured by the storage
 * layer, and {@code executeBlocking} cannot cancel a task that is already running — a server that "times out" without
 * this abandons the <em>answer</em> while the scan keeps its thread and its read view, which is the failure it was
 * trying to prevent. Stopping has to be cooperative, and this is where the cooperation happens.
 * </p>
 *
 * <p>
 * <b>Called often and cheaply.</b> {@link io.metaloom.graph.cypher.exec.GraphAccess} consults it on a fraction of
 * store touches rather than all of them, so an implementation may do real work — read a clock, check a volatile — but
 * should not block, allocate per call, or take a lock.
 * </p>
 */
@FunctionalInterface
public interface QueryAbort {

	/** Never stops. The default for every entry point that does not take one. */
	QueryAbort NEVER = () -> null;

	/**
	 * @return why the query should stop, or {@code null} to let it continue. The reason is put verbatim into the
	 *         {@link QueryAbortedException}, so write it for whoever reads the log.
	 */
	String abortReason();

	/**
	 * Stops once the given wall-clock instant has passed.
	 *
	 * @param deadlineNanos a {@link System#nanoTime} reading, compared with the wrap-safe subtraction rather than
	 *                      {@code <}, because nanoTime has no defined origin and does wrap.
	 */
	static QueryAbort deadline(long deadlineNanos, long budgetMillis) {
		return () -> System.nanoTime() - deadlineNanos >= 0
			? "the query exceeded its " + budgetMillis + " ms budget and was abandoned"
			: null;
	}
}
