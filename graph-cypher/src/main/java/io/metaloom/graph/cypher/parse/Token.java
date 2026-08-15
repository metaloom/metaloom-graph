package io.metaloom.graph.cypher.parse;

/**
 * One lexical token, with the position it came from so that a parse error can point at it.
 *
 * @param type
 *            what kind of token this is
 * @param text
 *            the source text, or the decoded value for a string literal
 * @param line
 *            1-based
 * @param column
 *            1-based
 */
public record Token(Token.Type type, String text, int line, int column) {

	public enum Type {
		/** A bare or backtick-quoted name: a variable, a label, a relationship type, a property key, a function. */
		IDENTIFIER,
		/** A reserved word. {@link Token#text()} is the upper-cased form, because Cypher keywords are case-insensitive. */
		KEYWORD,
		INTEGER,
		FLOAT,
		STRING,
		/** {@code $name} or {@code $0}; the text is the name without the dollar. */
		PARAMETER,
		/** Punctuation and operators: {@code ( ) [ ] { } , : . | = <> < > <= >= + - * / % ^ .. -> <- $} */
		SYMBOL,
		EOF
	}

	public boolean is(Type expected, String expectedText) {
		return type == expected && text.equalsIgnoreCase(expectedText);
	}

	public boolean isKeyword(String word) {
		return type == Type.KEYWORD && text.equals(word);
	}

	public boolean isSymbol(String symbol) {
		return type == Type.SYMBOL && text.equals(symbol);
	}

	@Override
	public String toString() {
		return type + "(" + text + ")@" + line + ":" + column;
	}
}
