package io.metaloom.graph.server;

/**
 * A request named a composite index this database does not have.
 *
 * <p>
 * Its own type for the same reason {@link NoSuchDatabaseException} is: the store raises
 * {@code IllegalArgumentException} for a pair it never declared, and the HTTP layer maps that to a 400 about a
 * database name — which is a message about the wrong thing. A dropped-index 404 says the pair is not there and that
 * listing is the next step.
 * </p>
 */
public class NoSuchIndexException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public NoSuchIndexException(String message) {
		super(message);
	}
}
