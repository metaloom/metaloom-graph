package io.metaloom.graph.core.api;

/** Checksum mismatch on a data page or a segment header. */
public class PageCorruptException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	public PageCorruptException(String message) {
		super(message);
	}
}
