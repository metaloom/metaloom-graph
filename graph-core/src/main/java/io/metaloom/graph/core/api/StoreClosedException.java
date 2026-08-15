package io.metaloom.graph.core.api;

/** Use of a store, view or cursor after it was closed. */
public class StoreClosedException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	public StoreClosedException(String message) {
		super(message);
	}
}
