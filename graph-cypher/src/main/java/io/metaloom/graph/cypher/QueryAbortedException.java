package io.metaloom.graph.cypher;

/**
 * Raised when a {@link QueryAbort} says a running query should stop.
 *
 * <p>
 * Not a failure of the query and not a failure of the store: the answer was never wrong, it was abandoned. A caller
 * mapping this to a client should say so — a timeout and a syntax error deserve different words, and treating this as
 * an internal error would send an operator looking for a defect that is not there.
 * </p>
 *
 * <p>
 * It unwinds the executor, which closes the read view on the way out. That is the whole point: the alternative is a
 * query that has been given up on but still holds a worker thread and a snapshot.
 * </p>
 */
public class QueryAbortedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public QueryAbortedException(String reason) {
		super(reason);
	}
}
