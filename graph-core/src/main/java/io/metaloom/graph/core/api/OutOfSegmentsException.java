package io.metaloom.graph.core.api;


/** A record kind exhausted its 2^24 segments. */
public class OutOfSegmentsException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	public OutOfSegmentsException(Kind kind) {
		super("Kind " + kind + " has exhausted its segment space");
	}
}
