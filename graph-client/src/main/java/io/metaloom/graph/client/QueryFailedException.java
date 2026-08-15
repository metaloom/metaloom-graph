package io.metaloom.graph.client;

import io.metaloom.graph.protocol.WireError;

/**
 * The server refused or abandoned a query.
 *
 * <p>
 * Thrown at exactly the row where the error arrived, which is the point: <b>the rows already delivered are not a
 * result.</b> A query that fails after ten thousand rows has produced ten thousand rows of a wrong answer, and a client
 * that returned them as if the stream had merely ended early would be handing back truncated data with no way to tell.
 * </p>
 *
 * <p>
 * {@link #error()} is the code to branch on and {@link #getMessage()} is the engine's own text — for a syntax error,
 * including the caret line naming the position.
 * </p>
 */
public class QueryFailedException extends GraphClientException {

	private static final long serialVersionUID = 1L;

	private final WireError error;
	private final int line;
	private final int column;

	public QueryFailedException(WireError error, String message, int line, int column) {
		super(message);
		this.error = error;
		this.line = line;
		this.column = column;
	}

	public WireError error() {
		return error;
	}

	/** 1-based, and zero for an error that is not about a position in the query text. */
	public int line() {
		return line;
	}

	public int column() {
		return column;
	}
}
