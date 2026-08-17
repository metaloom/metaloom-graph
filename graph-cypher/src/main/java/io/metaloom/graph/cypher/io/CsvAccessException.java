package io.metaloom.graph.cypher.io;

/**
 * A {@code LOAD CSV} whose URL the configured {@link CsvSource} will not serve.
 *
 * <p>
 * Separate from an {@code IOException}, because the two mean opposite things to whoever sent the query: a refusal is
 * about permission and never becomes right on a retry, while a missing or unreadable file is about the resource and
 * might. The servers map them to different status codes for that reason.
 * </p>
 *
 * <p>
 * <b>The message must not distinguish "outside the import directory" from "does not exist".</b> Both are refusals here,
 * and a message that told them apart would turn the clause into a way of asking whether a path exists on a machine the
 * caller cannot otherwise see.
 * </p>
 */
public class CsvAccessException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CsvAccessException(String message) {
		super(message);
	}

	public CsvAccessException(String message, Throwable cause) {
		super(message, cause);
	}
}
