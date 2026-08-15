package io.metaloom.graph.cypher.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.ast.Ast.Direction;
import io.metaloom.graph.cypher.ast.Ast.Expr;
import io.metaloom.graph.cypher.ast.Ast.Op;

/**
 * Recursive descent for clauses and patterns, Pratt for expressions.
 *
 * <p>
 * <b>The parser accepts only what the executor can run.</b> A clause this build does not implement is refused here,
 * where the message can name the clause and point at it, rather than parsed successfully and refused later by a planner
 * whose complaint would arrive without a position. The cost is that the grammar is not openCypher's grammar; the
 * benefit is that "not supported" and "malformed" are different errors, which is the distinction a user actually needs.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
public final class CypherParser {

	private final String source;
	private final List<Token> tokens;
	private int position;

	public CypherParser(String source) {
		this.source = source;
		this.tokens = CypherLexer.tokenize(source);
	}

	public static Ast.Query parse(String source) {
		return new CypherParser(source).query();
	}

	// ---------------------------------------------------------------- query

	public Ast.Query query() {
		List<Ast.SingleQuery> parts = new ArrayList<>();
		List<Boolean> unionAll = new ArrayList<>();
		parts.add(singleQuery());
		while (peek().isKeyword("UNION")) {
			take();
			unionAll.add(peek().isKeyword("ALL"));
			if (peek().isKeyword("ALL")) {
				take();
			}
			parts.add(singleQuery());
		}
		expectEnd();
		return new Ast.Query(parts, unionAll);
	}

	private Ast.SingleQuery singleQuery() {
		List<Ast.Clause> clauses = new ArrayList<>();
		while (true) {
			Token token = peek();
			if (token.isKeyword("MATCH") || token.isKeyword("OPTIONAL")) {
				clauses.add(matchClause());
			} else if (token.isKeyword("UNWIND")) {
				clauses.add(unwindClause());
			} else if (token.isKeyword("WITH")) {
				clauses.add(withClause());
			} else if (token.isKeyword("RETURN")) {
				clauses.add(returnClause());
				break;
			} else if (isWriteClause(token)) {
				throw error(token, token.text() + " is not supported: this build runs read-only queries. "
					+ "Use the Java API for writes");
			} else if (token.type() == Token.Type.EOF && clauses.isEmpty()) {
				throw error(token, "Empty query; expected MATCH, UNWIND, WITH or RETURN");
			} else {
				throw error(token, "Expected MATCH, UNWIND, WITH or RETURN but found " + describe(token));
			}
		}
		return new Ast.SingleQuery(clauses);
	}

	private static boolean isWriteClause(Token token) {
		return token.type() == Token.Type.KEYWORD && switch (token.text()) {
			case "CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "FOREACH", "LOAD" -> true;
			default -> false;
		};
	}

	private Ast.Match matchClause() {
		boolean optional = false;
		if (peek().isKeyword("OPTIONAL")) {
			Token at = take();
			expectKeyword("MATCH");
			// Parsed rather than lexed away, so the message can be about the feature instead of about the word.
			throw error(at, "OPTIONAL MATCH is not supported: it needs null-padding semantics this build "
				+ "does not implement");
		}
		expectKeyword("MATCH");
		List<Ast.PatternPart> pattern = new ArrayList<>();
		pattern.add(patternPart());
		while (peek().isSymbol(",")) {
			take();
			pattern.add(patternPart());
		}
		Expr where = null;
		if (peek().isKeyword("WHERE")) {
			take();
			where = expression();
		}
		return new Ast.Match(pattern, where, optional);
	}

	private Ast.Unwind unwindClause() {
		expectKeyword("UNWIND");
		Expr expression = expression();
		expectKeyword("AS");
		return new Ast.Unwind(expression, identifier("a variable name after AS"));
	}

	private Ast.With withClause() {
		expectKeyword("WITH");
		Ast.Projection projection = projection();
		Expr where = null;
		if (peek().isKeyword("WHERE")) {
			take();
			where = expression();
		}
		return new Ast.With(projection, where);
	}

	private Ast.Return returnClause() {
		expectKeyword("RETURN");
		return new Ast.Return(projection());
	}

	private Ast.Projection projection() {
		boolean distinct = false;
		if (peek().isKeyword("DISTINCT")) {
			take();
			distinct = true;
		}
		boolean star = false;
		List<Ast.ProjectionItem> items = new ArrayList<>();
		if (peek().isSymbol("*")) {
			take();
			star = true;
			// `RETURN *, x AS y` is legal Cypher, so a comma may still follow.
			while (peek().isSymbol(",")) {
				take();
				items.add(projectionItem());
			}
		} else {
			items.add(projectionItem());
			while (peek().isSymbol(",")) {
				take();
				items.add(projectionItem());
			}
		}

		List<Ast.SortItem> order = new ArrayList<>();
		if (peek().isKeyword("ORDER")) {
			take();
			expectKeyword("BY");
			order.add(sortItem());
			while (peek().isSymbol(",")) {
				take();
				order.add(sortItem());
			}
		}
		Expr skip = null;
		if (peek().isKeyword("SKIP")) {
			take();
			skip = expression();
		}
		Expr limit = null;
		if (peek().isKeyword("LIMIT")) {
			take();
			limit = expression();
		}
		return new Ast.Projection(distinct, star, items, order, skip, limit);
	}

	private Ast.ProjectionItem projectionItem() {
		int start = position;
		Expr expression = expression();
		String alias;
		if (peek().isKeyword("AS")) {
			take();
			alias = identifier("a column name after AS");
		} else {
			// Cypher names an unaliased column after its source text. Reconstructing it from the tokens keeps
			// `n.name` as `n.name` rather than turning it into something the caller never wrote.
			alias = sourceText(start, position);
		}
		return new Ast.ProjectionItem(expression, alias);
	}

	private Ast.SortItem sortItem() {
		Expr expression = expression();
		boolean ascending = true;
		if (peek().isKeyword("DESC") || peek().isKeyword("DESCENDING")) {
			take();
			ascending = false;
		} else if (peek().isKeyword("ASC") || peek().isKeyword("ASCENDING")) {
			take();
		}
		return new Ast.SortItem(expression, ascending);
	}

	// ---------------------------------------------------------------- patterns

	private Ast.PatternPart patternPart() {
		String pathVariable = null;
		if (peek().type() == Token.Type.IDENTIFIER && peekAt(1).isSymbol("=")) {
			pathVariable = take().text();
			take();
		}
		List<Ast.NodePattern> nodes = new ArrayList<>();
		List<Ast.RelPattern> relationships = new ArrayList<>();
		nodes.add(nodePattern());
		while (isRelationshipStart()) {
			relationships.add(relPattern());
			nodes.add(nodePattern());
		}
		return new Ast.PatternPart(pathVariable, nodes, relationships);
	}

	private boolean isRelationshipStart() {
		Token token = peek();
		return token.isSymbol("-") || token.isSymbol("<-");
	}

	private Ast.NodePattern nodePattern() {
		expectSymbol("(", "a node pattern");
		String variable = null;
		if (peek().type() == Token.Type.IDENTIFIER) {
			variable = take().text();
		}
		List<String> labels = labelList();
		Map<String, Expr> properties = peek().isSymbol("{") ? propertyMap() : null;
		expectSymbol(")", "the end of a node pattern");
		return new Ast.NodePattern(variable, labels, properties);
	}

	private List<String> labelList() {
		List<String> labels = new ArrayList<>();
		while (peek().isSymbol(":")) {
			take();
			labels.add(labelName());
		}
		return labels;
	}

	/** A label or relationship type. Keywords are allowed here: {@code :END} and {@code :Order} are ordinary names. */
	private String labelName() {
		Token token = peek();
		if (token.type() == Token.Type.IDENTIFIER || token.type() == Token.Type.KEYWORD) {
			take();
			return token.text();
		}
		throw error(token, "Expected a label or type name but found " + describe(token));
	}

	private Ast.RelPattern relPattern() {
		Direction direction;
		if (peek().isSymbol("<-")) {
			take();
			direction = Direction.LEFT;
		} else {
			expectSymbol("-", "a relationship pattern");
			direction = Direction.BOTH;
		}

		String variable = null;
		List<String> types = new ArrayList<>();
		Map<String, Expr> properties = null;
		Ast.Range range = null;

		if (peek().isSymbol("[")) {
			take();
			if (peek().type() == Token.Type.IDENTIFIER) {
				variable = take().text();
			}
			if (peek().isSymbol(":")) {
				take();
				types.add(labelName());
				while (peek().isSymbol("|")) {
					take();
					// `[:A|:B]` and `[:A|B]` are both written in the wild; accept the redundant colon.
					if (peek().isSymbol(":")) {
						take();
					}
					types.add(labelName());
				}
			}
			if (peek().isSymbol("*")) {
				take();
				range = range();
			}
			if (peek().isSymbol("{")) {
				properties = propertyMap();
			}
			expectSymbol("]", "the end of a relationship pattern");
		}

		if (peek().isSymbol("->")) {
			take();
			if (direction == Direction.LEFT) {
				throw error(previous(), "A relationship cannot point both ways");
			}
			direction = Direction.RIGHT;
		} else {
			expectSymbol("-", "the end of a relationship pattern");
		}
		return new Ast.RelPattern(variable, types, properties, direction, range);
	}

	private Ast.Range range() {
		int min = 1;
		int max = Integer.MAX_VALUE;
		boolean sawMin = false;
		if (peek().type() == Token.Type.INTEGER) {
			min = (int) Long.parseLong(take().text());
			sawMin = true;
		}
		if (peek().isSymbol("..")) {
			take();
			if (peek().type() == Token.Type.INTEGER) {
				max = (int) Long.parseLong(take().text());
			}
		} else if (sawMin) {
			// `*2` is exactly two hops, not two-or-more.
			max = min;
		}
		if (min < 0 || max < min) {
			throw error(previous(), "A variable-length range must have min <= max, got " + min + ".." + max);
		}
		return new Ast.Range(min, max);
	}

	private Map<String, Expr> propertyMap() {
		expectSymbol("{", "a property map");
		Map<String, Expr> entries = new LinkedHashMap<>();
		if (!peek().isSymbol("}")) {
			while (true) {
				String key = propertyKeyName();
				expectSymbol(":", "a value after the property name");
				entries.put(key, expression());
				if (peek().isSymbol(",")) {
					take();
					continue;
				}
				break;
			}
		}
		expectSymbol("}", "the end of a property map");
		return entries;
	}

	private String propertyKeyName() {
		Token token = peek();
		if (token.type() == Token.Type.IDENTIFIER || token.type() == Token.Type.KEYWORD) {
			take();
			return token.text();
		}
		throw error(token, "Expected a property name but found " + describe(token));
	}

	// ---------------------------------------------------------------- expressions (Pratt)

	public Expr expression() {
		return or();
	}

	private Expr or() {
		Expr left = xor();
		while (peek().isKeyword("OR")) {
			take();
			left = new Ast.Binary(Op.OR, left, xor());
		}
		return left;
	}

	private Expr xor() {
		Expr left = and();
		while (peek().isKeyword("XOR")) {
			take();
			left = new Ast.Binary(Op.XOR, left, and());
		}
		return left;
	}

	private Expr and() {
		Expr left = not();
		while (peek().isKeyword("AND")) {
			take();
			left = new Ast.Binary(Op.AND, left, not());
		}
		return left;
	}

	private Expr not() {
		if (peek().isKeyword("NOT")) {
			take();
			return new Ast.Unary(Op.NOT, not());
		}
		return comparison();
	}

	private Expr comparison() {
		Expr left = additive();
		while (true) {
			Token token = peek();
			Op op = null;
			if (token.isSymbol("=")) {
				op = Op.EQUAL;
			} else if (token.isSymbol("<>")) {
				op = Op.NOT_EQUAL;
			} else if (token.isSymbol("<")) {
				op = Op.LESS;
			} else if (token.isSymbol("<=")) {
				op = Op.LESS_OR_EQUAL;
			} else if (token.isSymbol(">")) {
				op = Op.GREATER;
			} else if (token.isSymbol(">=")) {
				op = Op.GREATER_OR_EQUAL;
			} else if (token.isKeyword("IN")) {
				op = Op.IN;
			} else if (token.isKeyword("STARTS")) {
				take();
				expectKeyword("WITH");
				left = new Ast.Comparison(Op.STARTS_WITH, left, additive());
				continue;
			} else if (token.isKeyword("ENDS")) {
				take();
				expectKeyword("WITH");
				left = new Ast.Comparison(Op.ENDS_WITH, left, additive());
				continue;
			} else if (token.isKeyword("CONTAINS")) {
				take();
				left = new Ast.Comparison(Op.CONTAINS, left, additive());
				continue;
			} else if (token.isKeyword("IS")) {
				take();
				boolean negated = false;
				if (peek().isKeyword("NOT")) {
					take();
					negated = true;
				}
				expectKeyword("NULL");
				left = new Ast.IsNull(left, negated);
				continue;
			}
			if (op == null) {
				return left;
			}
			take();
			left = new Ast.Comparison(op, left, additive());
		}
	}

	private Expr additive() {
		Expr left = multiplicative();
		while (peek().isSymbol("+") || peek().isSymbol("-")) {
			Op op = take().text().equals("+") ? Op.ADD : Op.SUBTRACT;
			left = new Ast.Binary(op, left, multiplicative());
		}
		return left;
	}

	private Expr multiplicative() {
		Expr left = power();
		while (peek().isSymbol("*") || peek().isSymbol("/") || peek().isSymbol("%")) {
			String symbol = take().text();
			Op op = symbol.equals("*") ? Op.MULTIPLY : symbol.equals("/") ? Op.DIVIDE : Op.MODULO;
			left = new Ast.Binary(op, left, power());
		}
		return left;
	}

	private Expr power() {
		Expr left = unary();
		if (peek().isSymbol("^")) {
			take();
			// Right associative: 2^3^2 is 2^(3^2).
			return new Ast.Binary(Op.POWER, left, power());
		}
		return left;
	}

	private Expr unary() {
		if (peek().isSymbol("-")) {
			take();
			return new Ast.Unary(Op.NEGATE, unary());
		}
		if (peek().isSymbol("+")) {
			take();
			return unary();
		}
		return postfix();
	}

	private Expr postfix() {
		Expr expression = primary();
		while (true) {
			if (peek().isSymbol(".")) {
				take();
				expression = new Ast.Property(expression, propertyKeyName());
				continue;
			}
			if (peek().isSymbol("[")) {
				take();
				Expr index = expression();
				expectSymbol("]", "the end of an index");
				expression = new Ast.Index(expression, index);
				continue;
			}
			if (peek().isSymbol(":")) {
				List<String> labels = labelList();
				expression = new Ast.LabelPredicate(expression, labels);
				continue;
			}
			return expression;
		}
	}

	private Expr primary() {
		Token token = peek();
		switch (token.type()) {
			case INTEGER -> {
				take();
				return new Ast.Literal(Long.parseLong(token.text()));
			}
			case FLOAT -> {
				take();
				return new Ast.Literal(Double.parseDouble(token.text()));
			}
			case STRING -> {
				take();
				return new Ast.Literal(token.text());
			}
			case PARAMETER -> {
				take();
				return new Ast.Parameter(token.text());
			}
			case IDENTIFIER -> {
				take();
				if (peek().isSymbol("(")) {
					return functionCall(token.text());
				}
				return new Ast.Variable(token.text());
			}
			case KEYWORD -> {
				switch (token.text()) {
					case "TRUE" -> {
						take();
						return new Ast.Literal(Boolean.TRUE);
					}
					case "FALSE" -> {
						take();
						return new Ast.Literal(Boolean.FALSE);
					}
					case "NULL" -> {
						take();
						return new Ast.Literal(null);
					}
					case "CASE" -> {
						throw error(token, "CASE is not supported in this build");
					}
					case "ALL", "ANY", "NONE", "SINGLE" -> {
						throw error(token, token.text() + " predicates are not supported in this build");
					}
					default -> throw error(token, "Unexpected keyword " + token.text() + " in an expression");
				}
			}
			case SYMBOL -> {
				if (token.isSymbol("(")) {
					take();
					Expr grouped = expression();
					expectSymbol(")", "a closing parenthesis");
					return grouped;
				}
				if (token.isSymbol("[")) {
					take();
					List<Expr> elements = new ArrayList<>();
					if (!peek().isSymbol("]")) {
						elements.add(expression());
						while (peek().isSymbol(",")) {
							take();
							elements.add(expression());
						}
					}
					expectSymbol("]", "the end of a list");
					return new Ast.ListLiteral(elements);
				}
				if (token.isSymbol("{")) {
					return new Ast.MapLiteral(propertyMap());
				}
				throw error(token, "Unexpected " + describe(token) + " in an expression");
			}
			default -> throw error(token, "Expected an expression but found " + describe(token));
		}
	}

	private Expr functionCall(String name) {
		expectSymbol("(", "a function call");
		boolean distinct = false;
		List<Expr> arguments = new ArrayList<>();
		if (peek().isKeyword("DISTINCT")) {
			take();
			distinct = true;
		}
		if (peek().isSymbol("*")) {
			// count(*) — the only function that takes a star, and it means "rows" rather than "values".
			take();
			expectSymbol(")", "the end of a function call");
			return new Ast.FunctionCall(name, List.of(), false);
		}
		if (!peek().isSymbol(")")) {
			arguments.add(expression());
			while (peek().isSymbol(",")) {
				take();
				arguments.add(expression());
			}
		}
		expectSymbol(")", "the end of a function call");
		return new Ast.FunctionCall(name, arguments, distinct);
	}

	// ---------------------------------------------------------------- token plumbing

	private Token peek() {
		return tokens.get(Math.min(position, tokens.size() - 1));
	}

	private Token peekAt(int ahead) {
		return tokens.get(Math.min(position + ahead, tokens.size() - 1));
	}

	private Token previous() {
		return tokens.get(Math.max(0, position - 1));
	}

	private Token take() {
		Token token = peek();
		if (position < tokens.size() - 1) {
			position++;
		}
		return token;
	}

	private void expectKeyword(String keyword) {
		if (!peek().isKeyword(keyword)) {
			throw error(peek(), "Expected " + keyword + " but found " + describe(peek()));
		}
		take();
	}

	private void expectSymbol(String symbol, String what) {
		if (!peek().isSymbol(symbol)) {
			throw error(peek(), "Expected '" + symbol + "' (" + what + ") but found " + describe(peek()));
		}
		take();
	}

	private String identifier(String what) {
		Token token = peek();
		if (token.type() != Token.Type.IDENTIFIER) {
			throw error(token, "Expected " + what + " but found " + describe(token));
		}
		take();
		return token.text();
	}

	private void expectEnd() {
		if (peek().type() != Token.Type.EOF) {
			throw error(peek(), "Unexpected " + describe(peek()) + " after the end of the query");
		}
	}

	/** The source text spanned by a token range, used to name an unaliased projection column. */
	private String sourceText(int from, int to) {
		StringBuilder out = new StringBuilder();
		for (int i = from; i < to && i < tokens.size(); i++) {
			Token token = tokens.get(i);
			if (out.length() > 0 && needsSpace(tokens.get(i - 1), token)) {
				out.append(' ');
			}
			out.append(token.type() == Token.Type.STRING ? "'" + token.text() + "'"
				: token.type() == Token.Type.PARAMETER ? "$" + token.text() : token.text());
		}
		return out.toString();
	}

	private static boolean needsSpace(Token before, Token after) {
		// `n.name` and `f(x)` read better closed up; `a AND b` does not.
		if (before.isSymbol(".") || after.isSymbol(".") || after.isSymbol("(") || after.isSymbol(")")
			|| after.isSymbol(",") || before.isSymbol("(")) {
			return false;
		}
		return before.type() == Token.Type.KEYWORD || after.type() == Token.Type.KEYWORD
			|| before.type() == Token.Type.IDENTIFIER && after.type() == Token.Type.IDENTIFIER;
	}

	private static String describe(Token token) {
		return switch (token.type()) {
			case EOF -> "the end of the query";
			case STRING -> "the string '" + token.text() + "'";
			case PARAMETER -> "the parameter $" + token.text();
			case KEYWORD -> "the keyword " + token.text();
			default -> "'" + token.text() + "'";
		};
	}

	private CypherSyntaxException error(Token token, String message) {
		return new CypherSyntaxException(source, token.line(), token.column(), message);
	}
}
