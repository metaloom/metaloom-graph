package io.metaloom.graph.cypher.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns query text into tokens.
 *
 * <p>
 * Hand written, like everything else in this repository that could have been generated. The reason here is the same as
 * for the LZ4 decompressor and the varint codec: the thing being avoided is not the dependency's size but its
 * <em>presence on a path that must not fail</em>, plus the loss of control over the error messages, which for a query
 * language are half the product.
 * </p>
 *
 * <p>
 * Cypher keywords are case-insensitive; identifiers are not. Keyword tokens therefore carry their upper-cased text and
 * identifiers carry theirs verbatim.
 * </p>
 */
public final class CypherLexer {

	/**
	 * Words the parser treats as syntax.
	 *
	 * <p>
	 * Deliberately not the full openCypher reserved list. A word that is reserved in Cypher but meaningless in this
	 * subset — {@code CREATE}, {@code MERGE}, {@code CALL} — is still listed, so that a query using it fails at the
	 * clause with "not supported" rather than being parsed as a variable named CREATE and failing much later with
	 * something unrecognisable.
	 * </p>
	 */
	private static final Set<String> KEYWORDS = Set.of(
		"MATCH", "OPTIONAL", "WHERE", "RETURN", "WITH", "UNWIND", "AS", "DISTINCT",
		"ORDER", "BY", "ASC", "ASCENDING", "DESC", "DESCENDING", "SKIP", "LIMIT",
		"UNION", "ALL", "AND", "OR", "XOR", "NOT", "IN", "IS", "NULL", "TRUE", "FALSE",
		"STARTS", "ENDS", "CONTAINS", "CASE", "WHEN", "THEN", "ELSE", "END",
		"CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "CALL", "YIELD", "FOREACH", "ON", "CONSTRAINT",
		"INDEX", "DROP", "LOAD", "CSV", "USING", "PERIODIC", "COMMIT");

	/** Two-character operators, checked before the single-character ones so that {@code <=} does not lex as {@code <}. */
	private static final String[] TWO_CHAR = { "<>", "<=", ">=", "->", "<-", "..", "=~", "+=" };

	private final String source;
	private int index;
	private int line = 1;
	private int column = 1;

	public CypherLexer(String source) {
		this.source = source == null ? "" : source;
	}

	public static List<Token> tokenize(String source) {
		return new CypherLexer(source).scan();
	}

	public List<Token> scan() {
		List<Token> tokens = new ArrayList<>();
		while (true) {
			skipWhitespaceAndComments();
			if (index >= source.length()) {
				tokens.add(new Token(Token.Type.EOF, "<end of query>", line, column));
				return tokens;
			}
			tokens.add(next());
		}
	}

	private Token next() {
		int startLine = line;
		int startColumn = column;
		char c = source.charAt(index);

		if (c == '`') {
			return new Token(Token.Type.IDENTIFIER, quotedIdentifier(), startLine, startColumn);
		}
		if (c == '\'' || c == '"') {
			return new Token(Token.Type.STRING, stringLiteral(c), startLine, startColumn);
		}
		if (c == '$') {
			advance();
			if (index >= source.length() || !(isIdentifierStart(source.charAt(index))
				|| Character.isDigit(source.charAt(index)))) {
				throw error(startLine, startColumn, "A '$' must be followed by a parameter name");
			}
			StringBuilder name = new StringBuilder();
			while (index < source.length()
				&& (isIdentifierPart(source.charAt(index)) || Character.isDigit(source.charAt(index)))) {
				name.append(source.charAt(index));
				advance();
			}
			return new Token(Token.Type.PARAMETER, name.toString(), startLine, startColumn);
		}
		if (Character.isDigit(c) || (c == '.' && index + 1 < source.length()
			&& Character.isDigit(source.charAt(index + 1)))) {
			return number(startLine, startColumn);
		}
		if (isIdentifierStart(c)) {
			StringBuilder word = new StringBuilder();
			while (index < source.length() && isIdentifierPart(source.charAt(index))) {
				word.append(source.charAt(index));
				advance();
			}
			String text = word.toString();
			String upper = text.toUpperCase(java.util.Locale.ROOT);
			return KEYWORDS.contains(upper)
				? new Token(Token.Type.KEYWORD, upper, startLine, startColumn)
				: new Token(Token.Type.IDENTIFIER, text, startLine, startColumn);
		}

		for (String candidate : TWO_CHAR) {
			if (source.startsWith(candidate, index)) {
				advance();
				advance();
				return new Token(Token.Type.SYMBOL, candidate, startLine, startColumn);
			}
		}
		if ("()[]{},:.|=<>+-*/%^".indexOf(c) >= 0) {
			advance();
			return new Token(Token.Type.SYMBOL, String.valueOf(c), startLine, startColumn);
		}
		throw error(startLine, startColumn, "Unexpected character '" + c + "'");
	}

	private Token number(int startLine, int startColumn) {
		int start = index;
		boolean isFloat = false;
		if (source.charAt(index) == '0' && index + 1 < source.length()
			&& (source.charAt(index + 1) == 'x' || source.charAt(index + 1) == 'X')) {
			advance();
			advance();
			while (index < source.length() && isHex(source.charAt(index))) {
				advance();
			}
			String text = source.substring(start, index);
			return new Token(Token.Type.INTEGER, String.valueOf(Long.parseLong(text.substring(2), 16)),
				startLine, startColumn);
		}
		while (index < source.length() && Character.isDigit(source.charAt(index))) {
			advance();
		}
		// A '.' only continues the number when a digit follows: in `1..3` the dots are the range operator, and
		// consuming the first of them would turn a variable-length pattern into a malformed float.
		if (index < source.length() && source.charAt(index) == '.'
			&& index + 1 < source.length() && Character.isDigit(source.charAt(index + 1))) {
			isFloat = true;
			advance();
			while (index < source.length() && Character.isDigit(source.charAt(index))) {
				advance();
			}
		}
		if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
			int mark = index;
			advance();
			if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
				advance();
			}
			if (index < source.length() && Character.isDigit(source.charAt(index))) {
				isFloat = true;
				while (index < source.length() && Character.isDigit(source.charAt(index))) {
					advance();
				}
			} else {
				// Not an exponent after all, e.g. the `e` of an identifier butted against a number.
				index = mark;
			}
		}
		String text = source.substring(start, index);
		return new Token(isFloat ? Token.Type.FLOAT : Token.Type.INTEGER, text, startLine, startColumn);
	}

	private String quotedIdentifier() {
		int startLine = line;
		int startColumn = column;
		advance();
		StringBuilder out = new StringBuilder();
		while (true) {
			if (index >= source.length()) {
				throw error(startLine, startColumn, "Unterminated backtick-quoted name");
			}
			char c = source.charAt(index);
			if (c == '`') {
				advance();
				// A doubled backtick is an escaped one, which is how a name containing a backtick is written.
				if (index < source.length() && source.charAt(index) == '`') {
					out.append('`');
					advance();
					continue;
				}
				return out.toString();
			}
			out.append(c);
			advance();
		}
	}

	private String stringLiteral(char quote) {
		int startLine = line;
		int startColumn = column;
		advance();
		StringBuilder out = new StringBuilder();
		while (true) {
			if (index >= source.length()) {
				throw error(startLine, startColumn, "Unterminated string literal");
			}
			char c = source.charAt(index);
			if (c == quote) {
				advance();
				return out.toString();
			}
			if (c == '\\') {
				advance();
				if (index >= source.length()) {
					throw error(startLine, startColumn, "Unterminated string literal");
				}
				char escape = source.charAt(index);
				advance();
				switch (escape) {
					case 'n' -> out.append('\n');
					case 't' -> out.append('\t');
					case 'r' -> out.append('\r');
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case '0' -> out.append('\0');
					case '\\' -> out.append('\\');
					case '\'' -> out.append('\'');
					case '"' -> out.append('"');
					case 'u' -> {
						if (index + 4 > source.length()) {
							throw error(line, column, "A \\u escape needs four hex digits");
						}
						String hex = source.substring(index, index + 4);
						for (int i = 0; i < 4; i++) {
							advance();
						}
						out.append((char) Integer.parseInt(hex, 16));
					}
					default -> throw error(line, column - 1, "Unknown escape '\\" + escape + "'");
				}
				continue;
			}
			out.append(c);
			advance();
		}
	}

	private void skipWhitespaceAndComments() {
		while (index < source.length()) {
			char c = source.charAt(index);
			if (Character.isWhitespace(c)) {
				advance();
				continue;
			}
			if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
				while (index < source.length() && source.charAt(index) != '\n') {
					advance();
				}
				continue;
			}
			if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
				int startLine = line;
				int startColumn = column;
				advance();
				advance();
				while (true) {
					if (index >= source.length()) {
						throw error(startLine, startColumn, "Unterminated block comment");
					}
					if (source.charAt(index) == '*' && index + 1 < source.length()
						&& source.charAt(index + 1) == '/') {
						advance();
						advance();
						break;
					}
					advance();
				}
				continue;
			}
			return;
		}
	}

	private void advance() {
		if (source.charAt(index) == '\n') {
			line++;
			column = 1;
		} else {
			column++;
		}
		index++;
	}

	private CypherSyntaxException error(int atLine, int atColumn, String message) {
		return new CypherSyntaxException(source, atLine, atColumn, message);
	}

	private static boolean isIdentifierStart(char c) {
		return Character.isLetter(c) || c == '_';
	}

	private static boolean isIdentifierPart(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static boolean isHex(char c) {
		return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}
}
