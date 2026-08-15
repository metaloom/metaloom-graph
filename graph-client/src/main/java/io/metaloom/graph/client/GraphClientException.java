package io.metaloom.graph.client;

/** The connection failed, or the peer said something that is not this protocol. */
public class GraphClientException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public GraphClientException(String message) {
		super(message);
	}

	public GraphClientException(String message, Throwable cause) {
		super(message, cause);
	}
}
