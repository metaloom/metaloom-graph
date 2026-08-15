package io.metaloom.graph.cypher.parse;

/**
 * A query that could not be parsed, with the offending position marked.
 *
 * <p>
 * The message carries the source line and a caret under the token that failed, because a parser that only says
 * "syntax error" makes the user bisect their own query. That matters more here than in a generated parser: this
 * grammar accepts a <em>subset</em> of openCypher, so a good share of failures are valid Cypher that this engine does
 * not implement, and the difference between "you typed it wrong" and "this build cannot do that" has to be visible.
 * </p>
 *
 * <pre>
 * MATCH (n:Person) WHERE n.age &gt;&gt; 30 RETURN n
 *                              ^
 * Unexpected '&gt;' at 1:31; expected an expression
 * </pre>
 */
public class CypherSyntaxException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int line;
	private final int column;
	private final String query;

	public CypherSyntaxException(String query, int line, int column, String message) {
		super(render(query, line, column, message));
		this.query = query;
		this.line = line;
		this.column = column;
	}

	public int line() {
		return line;
	}

	public int column() {
		return column;
	}

	public String query() {
		return query;
	}

	private static String render(String query, int line, int column, String message) {
		StringBuilder out = new StringBuilder();
		String[] lines = query.split("\n", -1);
		if (line >= 1 && line <= lines.length) {
			String source = lines[line - 1];
			out.append(source).append('\n');
			// Tabs are copied through so the caret lands under the token in a terminal that renders them the same way.
			for (int i = 0; i < column - 1 && i < source.length(); i++) {
				out.append(source.charAt(i) == '\t' ? '\t' : ' ');
			}
			out.append("^\n");
		}
		out.append(message).append(" (").append(line).append(':').append(column).append(')');
		return out.toString();
	}
}
