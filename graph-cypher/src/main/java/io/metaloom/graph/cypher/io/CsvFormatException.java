package io.metaloom.graph.cypher.io;

/**
 * A CSV file this reader cannot make sense of: an unclosed quoted field, or a row whose width does not match the
 * header.
 *
 * <p>
 * Always names a line number. A bulk import is the one place where "something is wrong with the input" is useless
 * advice — the file has a million rows and the operator needs the one.
 * </p>
 */
public class CsvFormatException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CsvFormatException(String message) {
		super(message);
	}
}
