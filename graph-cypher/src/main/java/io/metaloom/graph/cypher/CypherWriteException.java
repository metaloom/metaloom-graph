package io.metaloom.graph.cypher;

/**
 * A write query asked to run somewhere it cannot.
 *
 * <p>
 * Distinct from a type error, which is the caller's query meeting the caller's data: this is about the state the query
 * was handed to, and the only way to reach it today is to run a write inside an open read view. That is a mistake in
 * the calling code rather than in the query text, which is why it is not a 400 on either server — neither of them can
 * produce it.
 * </p>
 */
public class CypherWriteException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CypherWriteException(String message) {
		super(message);
	}
}
