package io.metaloom.graph.cypher.exec;

/**
 * A query that parsed but asked for something the values cannot do — adding a node to a number, dividing by zero,
 * calling a function on the wrong type.
 *
 * <p>
 * Distinct from a syntax error because the query was well formed, and distinct from a storage failure because nothing
 * is wrong with the store. It is the caller's query meeting the caller's data.
 * </p>
 */
public class CypherTypeException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CypherTypeException(String message) {
		super(message);
	}
}
