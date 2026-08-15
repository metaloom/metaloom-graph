package io.metaloom.graph.server;

/**
 * A request named a database this server does not have.
 *
 * <p>
 * Its own type so that the HTTP layer can answer 404 rather than the 409 a {@code GraphStoreException} maps to. The
 * distinction is the one a caller acts on: 409 says the store refused, and retrying might work; 404 says the name is
 * wrong, and the next step is to list the databases or create one.
 * </p>
 */
public class NoSuchDatabaseException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public NoSuchDatabaseException(String message) {
		super(message);
	}
}
