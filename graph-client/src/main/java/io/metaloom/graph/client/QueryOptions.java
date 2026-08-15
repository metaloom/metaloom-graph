package io.metaloom.graph.client;

import java.time.Duration;

/**
 * What applies to one query.
 *
 * @param deadlineMillis
 *            how long the server may spend on this query, or 0 to accept whatever the server's own limit is. It is
 *            enforced on the server rather than by the client giving up waiting, and the difference is the whole
 *            reason it travels on the wire: a client that merely stopped listening would leave the query running, still
 *            holding a worker thread and a read view, still holding back reclamation. The server's engine consults the
 *            deadline while it scans and stops where it is.
 */
public record QueryOptions(long deadlineMillis) {

	/** The server's own {@code --query-timeout} applies. */
	public static final QueryOptions SERVER_DEFAULT = new QueryOptions(0);

	public QueryOptions {
		if (deadlineMillis < 0) {
			throw new IllegalArgumentException("deadlineMillis must not be negative, was " + deadlineMillis);
		}
	}

	public static QueryOptions deadline(Duration deadline) {
		return new QueryOptions(deadline.toMillis());
	}
}
