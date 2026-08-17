package io.metaloom.graph.cypher.parse;

/**
 * One lexical token, with the position it came from so that a parse error can point at it.
 *
 * @param type
 *            what kind of token this is
 * @param text
 *            the source text, or the decoded value for a string literal. For a {@link Type#KEYWORD} this is the
 *            <b>upper-cased</b> form, which is what every {@code isKeyword} comparison and every {@code switch} in the
 *            parser is written against
 * @param raw
 *            what was actually written. Identical to {@code text} for everything except a keyword, where it is the
 *            spelling before case folding — see {@link #raw()}
 * @param line
 *            1-based
 * @param column
 *            1-based
 */
public record Token(Token.Type type, String text, String raw, int line, int column) {

	/**
	 * Everything but a keyword: what was written is what the parser sees, so the two spellings coincide.
	 */
	public Token(Type type, String text, int line, int column) {
		this(type, text, text, line, column);
	}

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

	/**
	 * The name as the author spelled it.
	 *
	 * <p>
	 * A label and a property key are ordinary names that happen to be allowed to collide with a keyword — {@code :Order}
	 * and {@code .end} are the examples the grammar calls out. They reach the parser as {@link Type#KEYWORD} tokens, so
	 * reading {@link #text()} for them yields {@code ORDER} and {@code END}: not a parse error, but a node stored under a
	 * label nobody wrote. This is the accessor those two positions must use, and the only reason the component exists.
	 * </p>
	 */
	@Override
	public String raw() {
		return raw;
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
