package io.metaloom.graph.cypher;

/**
 * A write query asked to run somewhere it cannot.
 *
 * <p>
 * Distinct from a type error, which is the caller's query meeting the caller's data: this is about the state the query
 * was handed to, and the two ways to reach it are running a write inside an open read view, and asking for
 * {@code USING PERIODIC COMMIT} inside a transaction the caller opened. Both are mistakes in the calling code rather
 * than in the query text.
 * </p>
 *
 * <p>
 * It also carries the one failure that is neither: a batched load that failed partway, where some batches are already
 * durable. That case has a cause attached and a message naming how many rows survived — see
 * {@code Cypher.partiallyApplied}.
 * </p>
 */
public class CypherWriteException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CypherWriteException(String message) {
		super(message);
	}

	public CypherWriteException(String message, Throwable cause) {
		super(message, cause);
	}
}
