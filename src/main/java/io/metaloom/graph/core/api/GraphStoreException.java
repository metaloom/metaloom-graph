package io.metaloom.graph.core.api;

/**
 * Base of the engine's unchecked exception hierarchy.
 *
 * @see <a href="../../../../../../../../spec/ARCHITECTURE.md">spec/ARCHITECTURE.md</a> section 9
 */
public class GraphStoreException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public GraphStoreException(String message) {
		super(message);
	}

	public GraphStoreException(String message, Throwable cause) {
		super(message, cause);
	}
}
