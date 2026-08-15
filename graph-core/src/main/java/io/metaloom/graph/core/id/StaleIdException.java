package io.metaloom.graph.core.id;

import io.metaloom.graph.core.api.GraphStoreException;

/**
 * Thrown when an id refers to a slot whose generation no longer matches, meaning the element it named has been deleted
 * and its slot handed out again.
 */
public class StaleIdException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	private final long id;

	public StaleIdException(long id, String detail) {
		super("Stale id " + ElementId.toString(id) + ": " + detail);
		this.id = id;
	}

	public long id() {
		return id;
	}
}
