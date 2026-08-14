package io.metaloom.graph.core.api;

/** The store was written with an incompatible format version or a different geometry. */
public class FormatVersionException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	public FormatVersionException(String message) {
		super(message);
	}
}
