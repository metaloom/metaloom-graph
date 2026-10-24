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

	/**
	 * Rows per transaction when {@code USING PERIODIC COMMIT} names no number.
	 *
	 * <p>
	 * The same 4,000 the demo seeder settled on, and for the same reason: it is large enough that the per-commit fsync
	 * is amortised across a useful amount of work, and small enough that a batch's rows and the writes they produce are
	 * a bounded amount of heap.
	 * </p>
	 */
	public static final int DEFAULT_PERIODIC_COMMIT = 4_000;

	/** An upper bound, because a batch is materialised before it is committed. */
	public static final int MAX_PERIODIC_COMMIT = 1_000_000;

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
		boolean returned = false;
		// Read before the loop, because it is not a clause: it qualifies the part as a whole and may appear only at
		// the front of it. See periodicCommitPrefix.
		int periodicCommit = periodicCommitPrefix();
		while (true) {
			Token token = peek();
			if (token.type() == Token.Type.EOF || token.isKeyword("UNION")) {
				break;
			}
			if (returned) {
				// RETURN ends a query part. Saying so beats "expected UNION or end of input", which describes the
				// parser's state rather than the mistake — and putting a write clause after RETURN is a real one.
				throw error(token, describe(token) + " cannot follow RETURN, which ends a query");
			}
			if (token.isKeyword("MATCH") || token.isKeyword("OPTIONAL")) {
				clauses.add(matchClause());
			} else if (token.isKeyword("UNWIND")) {
				clauses.add(unwindClause());
			} else if (token.isKeyword("WITH")) {
				clauses.add(withClause());
			} else if (token.isKeyword("CREATE")) {
				clauses.add(createClause());
			} else if (token.isKeyword("MERGE")) {
				clauses.add(mergeClause());
			} else if (token.isKeyword("SET")) {
				clauses.add(setClause());
			} else if (token.isKeyword("REMOVE")) {
				clauses.add(removeClause());
			} else if (token.isKeyword("DELETE") || token.isKeyword("DETACH")) {
				clauses.add(deleteClause());
			} else if (token.isKeyword("CALL")) {
				clauses.add(callClause());
			} else if (token.isKeyword("LOAD")) {
				clauses.add(loadCsvClause());
			} else if (token.isKeyword("RETURN")) {
				clauses.add(returnClause());
				returned = true;
			} else {
				String unsupported = unsupportedClause(token);
				throw error(token, unsupported != null ? unsupported
					: "Expected a clause but found " + describe(token));
			}
		}
		if (clauses.isEmpty()) {
			throw error(peek(), "Empty query; expected MATCH, CREATE, UNWIND, WITH or RETURN");
		}
		Ast.SingleQuery part = new Ast.SingleQuery(clauses, periodicCommit);
		if (periodicCommit > 0 && !(clauses.get(0) instanceof Ast.LoadCsv)) {
			// A batch boundary is only meaningful when the CSV is what drives the rows: with anything else first, the
			// rows were already fixed before the load ran and "commit every n of them" has no defined meaning.
			throw error(peek(), "USING PERIODIC COMMIT requires LOAD CSV to be the first clause of the query, "
				+ "because the batches it commits are batches of CSV rows");
		}
		if (!returned && !part.isWrite()) {
			// A read with nowhere to put its answer. Refused rather than run, because it looks like a query whose
			// RETURN was forgotten and running it would report success having produced nothing.
			throw error(peek(), "A query that does not write must end in RETURN; this one would compute a result "
				+ "and discard it");
		}
		return part;
	}

	/**
	 * The message for a clause this build lexes but does not implement, or null when the token is not one.
	 *
	 * <p>
	 * These words are keywords in the lexer specifically so they can be refused here by name, with a caret, rather than
	 * parsed as an ordinary identifier and failing later somewhere that cannot say what went wrong.
	 * </p>
	 */
	private static String unsupportedClause(Token token) {
		if (token.type() != Token.Type.KEYWORD) {
			return null;
		}
		return switch (token.text()) {
			case "FOREACH" -> "FOREACH is not supported: use UNWIND, which this build does implement";
			default -> null;
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

	/**
	 * {@code USING PERIODIC COMMIT [n]}, if it is there. Returns 0 when it is not.
	 *
	 * <p>
	 * Read before the clause loop rather than inside it because it is not a clause — it takes no input and produces no
	 * rows. It qualifies the part, and openCypher allows it only at the very front, which is also the only position
	 * where it means anything (see the refusal in {@link #singleQuery()}).
	 * </p>
	 */
	private int periodicCommitPrefix() {
		if (!peek().isKeyword("USING")) {
			return 0;
		}
		take();
		expectKeyword("PERIODIC");
		expectKeyword("COMMIT");
		if (peek().type() != Token.Type.INTEGER) {
			return DEFAULT_PERIODIC_COMMIT;
		}
		Token size = take();
		long rows = integer(size);
		if (rows <= 0) {
			// Zero would be "commit every no rows", which is either an infinite loop or the whole file in one
			// transaction depending on how it were read. Neither is what was written.
			throw error(size, "USING PERIODIC COMMIT needs a positive batch size, not " + rows);
		}
		if (rows > MAX_PERIODIC_COMMIT) {
			throw error(size, "A periodic commit batch of " + rows + " rows is above the limit of "
				+ MAX_PERIODIC_COMMIT + "; a batch is held in memory before it is committed");
		}
		return (int) rows;
	}

	/**
	 * {@code LOAD CSV [WITH HEADERS] FROM <expr> AS <var> [FIELDTERMINATOR '<c>']}.
	 *
	 * <p>
	 * <b>{@code FROM}, {@code HEADERS} and {@code FIELDTERMINATOR} are matched here rather than lexed as keywords</b>,
	 * and that is not a shortcut. A keyword token carries a folded spelling, and both {@code labelName} and
	 * {@code propertyKeyName} accept keywords as ordinary names — so reserving {@code FROM} would silently turn every
	 * {@code r.from} and every {@code {from: …}} in the language into {@code FROM}. A column called {@code from} is
	 * near-universal in the edge lists this clause exists to load, which makes it exactly the wrong word to reserve.
	 * They are ordinary identifiers everywhere, including inside this clause's own {@code AS} variable.
	 * </p>
	 */
	private Ast.LoadCsv loadCsvClause() {
		expectKeyword("LOAD");
		expectKeyword("CSV");
		boolean withHeaders = false;
		if (peek().isKeyword("WITH")) {
			// WITH is a clause starter, so it cannot simply be consumed: `LOAD CSV WITH HEADERS` is this clause and a
			// bare `LOAD CSV WITH` is a mistake that must not be reported as a projection with nothing in it.
			Token with = take();
			if (!peek().is(Token.Type.IDENTIFIER, "headers")) {
				throw error(with, "Expected WITH HEADERS or FROM after LOAD CSV, but found " + describe(peek())
					+ " after WITH");
			}
			take();
			withHeaders = true;
		}
		if (!peek().is(Token.Type.IDENTIFIER, "from")) {
			throw error(peek(), "Expected FROM after LOAD CSV but found " + describe(peek()));
		}
		take();
		Expr url = expression();
		expectKeyword("AS");
		String variable = identifier("a variable name after AS");

		char terminator = ',';
		if (peek().is(Token.Type.IDENTIFIER, "fieldterminator")) {
			take();
			Token value = peek();
			if (value.type() != Token.Type.STRING || value.text().length() != 1) {
				throw error(value, "FIELDTERMINATOR takes a single-character string, as in FIELDTERMINATOR ';'");
			}
			take();
			terminator = value.text().charAt(0);
			if (terminator == '"' || terminator == '\r' || terminator == '\n') {
				throw error(value, "A field terminator may not be a quote or a line break: those already have a "
					+ "meaning in the format");
			}
		}
		return new Ast.LoadCsv(url, variable, withHeaders, terminator);
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

	// ---------------------------------------------------------------- write clauses

	private Ast.Create createClause() {
		expectKeyword("CREATE");
		List<Ast.PatternPart> pattern = new ArrayList<>();
		pattern.add(creatablePart());
		while (peek().isSymbol(",")) {
			take();
			pattern.add(creatablePart());
		}
		return new Ast.Create(pattern);
	}

	/**
	 * A pattern that describes a graph which can actually be built.
	 *
	 * <p>
	 * The pattern grammar is shared with MATCH rather than duplicated, so three shapes MATCH accepts arrive here and
	 * have to be refused: a variable-length range does not say how many relationships to make, an undirected
	 * relationship does not say which way it points, and a relationship with no type or a choice of types does not say
	 * what to create. Each is refused with its own reason, because "invalid pattern" would leave the author guessing
	 * which of the three it was.
	 * </p>
	 */
	private Ast.PatternPart creatablePart() {
		Token at = peek();
		Ast.PatternPart part = patternPart();
		refuseUnbuildable(at, part, "CREATE", false);
		return part;
	}

	/**
	 * @param allowUndirected
	 *            true for MERGE, which may be written {@code (a)-[:R]-(b)}: undirected there is a question — does an
	 *            edge either way already exist — and openCypher answers it by matching both ways and creating
	 *            left to right. CREATE has no question to ask, so an arrowless relationship there is an omission
	 */
	private void refuseUnbuildable(Token at, Ast.PatternPart part, String clause, boolean allowUndirected) {
		if (part.selector() != Ast.PathSelector.ALL) {
			throw error(at, clause + " cannot take a shortest-path pattern: it describes a search through a graph "
				+ "rather than a graph to build");
		}
		for (Ast.RelPattern relationship : part.relationships()) {
			if (relationship.range() != null) {
				throw error(at, clause + " cannot take a variable-length pattern: it does not say how many "
					+ "relationships to create");
			}
			if (!allowUndirected && relationship.direction() == Direction.BOTH) {
				throw error(at, clause + " needs a direction on every relationship: write -> or <-");
			}
			if (relationship.types().isEmpty()) {
				throw error(at, clause + " needs a type on every relationship, as in -[:KNOWS]->");
			}
			if (relationship.types().size() > 1) {
				throw error(at, clause + " creates one relationship, so it takes one type rather than the choice "
					+ String.join("|", relationship.types()));
			}
		}
	}

	/**
	 * {@code MERGE Pattern [ON CREATE SET …] [ON MATCH SET …]}, in either order and either alone.
	 *
	 * <p>
	 * One pattern part, and the comma is refused with its own message rather than left to fail as an unexpected token:
	 * a list looks like it should work, and what it would have to mean — match all of it or build all of it — is
	 * exactly what MERGE cannot say about several independent patterns.
	 * </p>
	 */
	private Ast.Merge mergeClause() {
		expectKeyword("MERGE");
		Token at = peek();
		Ast.PatternPart pattern = patternPart();
		refuseUnbuildable(at, pattern, "MERGE", true);
		if (peek().isSymbol(",")) {
			throw error(peek(), "MERGE takes one pattern: it matches the whole of it or builds the whole of it, and "
				+ "a comma-separated list has no single answer to that. Write one MERGE for each");
		}

		List<Ast.SetItem> onCreate = new ArrayList<>();
		List<Ast.SetItem> onMatch = new ArrayList<>();
		while (peek().isKeyword("ON")) {
			take();
			boolean creating;
			if (peek().isKeyword("CREATE")) {
				creating = true;
			} else if (peek().isKeyword("MATCH")) {
				creating = false;
			} else {
				throw error(peek(), "Expected CREATE or MATCH after ON but found " + describe(peek()));
			}
			take();
			expectKeyword("SET");
			List<Ast.SetItem> items = creating ? onCreate : onMatch;
			items.add(setItem());
			while (peek().isSymbol(",")) {
				take();
				items.add(setItem());
			}
		}
		return new Ast.Merge(pattern, onCreate, onMatch);
	}

	private Ast.SetClause setClause() {
		expectKeyword("SET");
		List<Ast.SetItem> items = new ArrayList<>();
		items.add(setItem());
		while (peek().isSymbol(",")) {
			take();
			items.add(setItem());
		}
		return new Ast.SetClause(items);
	}

	/**
	 * One assignment.
	 *
	 * <p>
	 * The target is matched by shape rather than parsed as an expression, because {@code SET n.age = 30} <em>is</em> a
	 * valid expression — an equality comparison — and parsing it as one would turn an assignment into a predicate that
	 * silently evaluates to a boolean and writes nothing.
	 * </p>
	 */
	private Ast.SetItem setItem() {
		Token start = peek();
		if (start.type() == Token.Type.IDENTIFIER) {
			Token next = peekAt(1);
			if (next.isSymbol(":")) {
				return new Ast.SetLabels(take().text(), labelList());
			}
			if (next.isSymbol("=") || next.isSymbol("+=")) {
				String variable = take().text();
				boolean merge = take().isSymbol("+=");
				Expr map = expression();
				return merge ? new Ast.SetPropertiesMerge(variable, map)
					: new Ast.SetPropertiesReplace(variable, map);
			}
			if (next.isSymbol(".")) {
				Expr target = new Ast.Variable(take().text());
				take();
				String key = propertyKeyName();
				expectSymbol("=", "a value after the property being set");
				return new Ast.SetProperty(target, key, expression());
			}
		}
		throw error(start, "Expected a property, a label or a map after SET — as in SET n.age = 30, SET n:Person, "
			+ "SET n = {age: 30} or SET n += {age: 30} — but found " + describe(start));
	}

	private Ast.Remove removeClause() {
		expectKeyword("REMOVE");
		List<Ast.RemoveItem> items = new ArrayList<>();
		items.add(removeItem());
		while (peek().isSymbol(",")) {
			take();
			items.add(removeItem());
		}
		return new Ast.Remove(items);
	}

	private Ast.RemoveItem removeItem() {
		Token start = peek();
		if (start.type() == Token.Type.IDENTIFIER) {
			Token next = peekAt(1);
			if (next.isSymbol(":")) {
				return new Ast.RemoveLabels(take().text(), labelList());
			}
			if (next.isSymbol(".")) {
				Expr target = new Ast.Variable(take().text());
				take();
				return new Ast.RemoveProperty(target, propertyKeyName());
			}
		}
		throw error(start, "Expected a property or a label after REMOVE — as in REMOVE n.age or REMOVE n:Person — "
			+ "but found " + describe(start));
	}

	private Ast.Delete deleteClause() {
		boolean detach = false;
		if (peek().isKeyword("DETACH")) {
			take();
			detach = true;
		}
		expectKeyword("DELETE");
		List<Expr> targets = new ArrayList<>();
		targets.add(expression());
		while (peek().isSymbol(",")) {
			take();
			targets.add(expression());
		}
		return new Ast.Delete(targets, detach);
	}

	// ---------------------------------------------------------------- patterns

	private Ast.PatternPart patternPart() {
		String pathVariable = null;
		if (peek().type() == Token.Type.IDENTIFIER && peekAt(1).isSymbol("=")) {
			pathVariable = take().text();
			take();
		}

		Token at = peek();
		Ast.PathSelector selector = pathSelector();
		if (selector == Ast.PathSelector.ALL) {
			return chain(pathVariable, selector);
		}
		// The name and its opening parenthesis are already taken; what follows is an ordinary pattern.
		Ast.PatternPart part = chain(pathVariable, selector);
		expectSymbol(")", "the end of " + at.text() + "(...)");
		return shortestPathPart(at, part);
	}

	/**
	 * {@code shortestPath} and {@code allShortestPaths}, which look like function calls and are not.
	 *
	 * <p>
	 * They take a <em>pattern</em> rather than an expression, so nothing in the expression grammar can reach them and
	 * they have to be recognised here, where a pattern is expected. Both names are ordinary identifiers to the lexer —
	 * a graph may legitimately have a property called {@code shortestPath} — so the trailing parenthesis is part of the
	 * test rather than the name alone.
	 * </p>
	 *
	 * @return the selector, having consumed the name and its opening parenthesis, or {@link Ast.PathSelector#ALL}
	 *         having consumed nothing
	 */
	private Ast.PathSelector pathSelector() {
		Token token = peek();
		if (token.type() != Token.Type.IDENTIFIER || !peekAt(1).isSymbol("(")) {
			return Ast.PathSelector.ALL;
		}
		Ast.PathSelector selector = switch (token.text()) {
			case "shortestPath" -> Ast.PathSelector.SHORTEST;
			case "allShortestPaths" -> Ast.PathSelector.ALL_SHORTEST;
			default -> Ast.PathSelector.ALL;
		};
		if (selector != Ast.PathSelector.ALL) {
			take();
			take();
		}
		return selector;
	}

	/** The node-relationship-node chain itself, which is the same grammar inside and outside a path function. */
	private Ast.PatternPart chain(String pathVariable, Ast.PathSelector selector) {
		List<Ast.NodePattern> nodes = new ArrayList<>();
		List<Ast.RelPattern> relationships = new ArrayList<>();
		nodes.add(nodePattern());
		while (isRelationshipStart()) {
			relationships.add(relPattern());
			nodes.add(nodePattern());
		}
		return new Ast.PatternPart(pathVariable, selector, nodes, relationships);
	}

	/**
	 * The two shapes a shortest-path search cannot be asked for.
	 *
	 * <p>
	 * Both are openCypher's own restrictions rather than this build's. A search runs between <em>two</em> endpoints, so
	 * a longer chain has no single pair to search between; and the search works outward from the start one distance at
	 * a time, so a minimum above one would mean finding the shortest path and then rejecting it for being short — which
	 * is a filter over all paths, not a shortest-path search, and the plain pattern already expresses it.
	 * </p>
	 */
	private Ast.PatternPart shortestPathPart(Token at, Ast.PatternPart part) {
		String name = at.text();
		if (part.relationships().size() != 1) {
			throw error(at, name + " takes a pattern with exactly one relationship, as in "
				+ "(a)-[:TYPE*..10]-(b); this one has " + part.relationships().size());
		}
		Ast.Range range = part.relationships().get(0).range();
		if (range != null && range.min() > 1) {
			throw error(at, name + " takes a minimum length of 0 or 1, not " + range.min()
				+ ": it searches outward from the start node, so a longer minimum is a filter over every path "
				+ "rather than a shortest-path search");
		}
		return part;
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

	/**
	 * A label or relationship type. Keywords are allowed here: {@code :END} and {@code :Order} are ordinary names.
	 *
	 * <p>
	 * {@link Token#raw()} rather than {@code text()}, because a keyword token carries the folded spelling and a label is
	 * case-sensitive: {@code :Order} stored as {@code ORDER} is a wrong answer that throws nothing.
	 * </p>
	 */
	private String labelName() {
		Token token = peek();
		if (token.type() == Token.Type.IDENTIFIER || token.type() == Token.Type.KEYWORD) {
			take();
			return token.raw();
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
			min = rangeBound(take());
			sawMin = true;
		}
		if (peek().isSymbol("..")) {
			take();
			if (peek().type() == Token.Type.INTEGER) {
				max = rangeBound(take());
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

	/** A hop count. Past an int no walk can get there, so a larger bound is clamped rather than truncated to 32 bits. */
	private int rangeBound(Token token) {
		return (int) Math.min(Integer.MAX_VALUE, integer(token));
	}

	/** An integer token as a long, or a syntax error with a caret — never a NumberFormatException. */
	private long integer(Token token) {
		try {
			return Long.parseLong(token.text());
		} catch (NumberFormatException e) {
			throw error(token, "The integer " + token.text() + " does not fit in 64 bits");
		}
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

	/** A property key. As with {@link #labelName()}, a keyword is an ordinary name here and keeps its spelling. */
	private String propertyKeyName() {
		Token token = peek();
		if (token.type() == Token.Type.IDENTIFIER || token.type() == Token.Type.KEYWORD) {
			take();
			return token.raw();
		}
		throw error(token, "Expected a property name but found " + describe(token));
	}

	// ---------------------------------------------------------------- expressions (Pratt)

	/**
	 * How deep the parser may recurse into one expression — parentheses, lists, arguments, unary operators.
	 *
	 * <p>
	 * A recursive-descent parser over a query string is a stack overflow anyone who can send a query can trigger, and
	 * a {@link StackOverflowError} is an Error, not a refusal: it can surface anywhere above. Each level here is a
	 * dozen frames, so this is far below what any thread's stack holds and far above what any written query needs.
	 * </p>
	 */
	public static final int MAX_NESTING = 200;

	/**
	 * How deep the finished tree of one expression may be. Larger than {@link #MAX_NESTING} because a flat chain —
	 * {@code a OR b OR c …}, the shape a generated query has — is built by a loop here but walked recursively by
	 * everything after the parser.
	 */
	public static final int MAX_DEPTH = 1_000;

	private int nesting;

	public Expr expression() {
		Token start = peek();
		descend(start);
		try {
			Expr expression = or();
			if (nesting == 1 && depth(expression) > MAX_DEPTH) {
				throw error(start, "This expression is nested more than " + MAX_DEPTH + " levels deep");
			}
			return expression;
		} finally {
			nesting--;
		}
	}

	private void descend(Token at) {
		if (++nesting > MAX_NESTING) {
			throw error(at, "This expression is nested more than " + MAX_NESTING + " levels deep");
		}
	}

	/** One level of recursion that does not pass through {@link #expression()}: a prefix operator, or {@code ^}. */
	private Expr nested(java.util.function.Supplier<Expr> inner) {
		descend(previous());
		try {
			return inner.get();
		} finally {
			nesting--;
		}
	}

	/** The depth of a tree, walked with an explicit stack: recursing here would be the overflow being guarded. */
	private static int depth(Expr root) {
		int deepest = 0;
		java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
		stack.push(new Object[] { root, 1 });
		while (!stack.isEmpty()) {
			Object[] entry = stack.pop();
			int level = (Integer) entry[1];
			deepest = Math.max(deepest, level);
			if (deepest > MAX_DEPTH) {
				return deepest;
			}
			for (Expr child : children((Expr) entry[0])) {
				if (child != null) {
					stack.push(new Object[] { child, level + 1 });
				}
			}
		}
		return deepest;
	}

	private static List<Expr> children(Expr expression) {
		return switch (expression) {
			case Ast.Literal ignored -> List.of();
			case Ast.Variable ignored -> List.of();
			case Ast.Parameter ignored -> List.of();
			case Ast.ListLiteral list -> list.elements();
			case Ast.MapLiteral map -> List.copyOf(map.entries().values());
			case Ast.Property property -> List.of(property.target());
			case Ast.Index index -> List.of(index.target(), index.index());
			case Ast.Binary binary -> List.of(binary.left(), binary.right());
			case Ast.Unary unary -> List.of(unary.operand());
			case Ast.Comparison comparison -> List.of(comparison.left(), comparison.right());
			case Ast.LabelPredicate predicate -> List.of(predicate.target());
			case Ast.IsNull isNull -> List.of(isNull.operand());
			case Ast.FunctionCall call -> call.arguments();
			case Ast.Case branch -> {
				List<Expr> all = new ArrayList<>(branch.whens());
				all.addAll(branch.thens());
				all.add(branch.subject());
				all.add(branch.otherwise());
				yield all;
			}
		};
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
			return new Ast.Unary(Op.NOT, nested(this::not));
		}
		return comparison();
	}

	private Expr comparison() {
		Expr left = additive();
		// The right operand of the last ordering or equality comparison, while the chain continues: `a < b < c` is
		// `a < b AND b < c`, as in openCypher, not a boolean compared with c.
		Expr chained = null;
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
				chained = null;
				continue;
			} else if (token.isKeyword("ENDS")) {
				take();
				expectKeyword("WITH");
				left = new Ast.Comparison(Op.ENDS_WITH, left, additive());
				chained = null;
				continue;
			} else if (token.isKeyword("CONTAINS")) {
				take();
				left = new Ast.Comparison(Op.CONTAINS, left, additive());
				chained = null;
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
				chained = null;
				continue;
			}
			if (op == null) {
				return left;
			}
			take();
			Expr right = additive();
			if (op == Op.IN) {
				left = new Ast.Comparison(op, left, right);
				chained = null;
			} else if (chained != null) {
				left = new Ast.Binary(Op.AND, left, new Ast.Comparison(op, chained, right));
				chained = right;
			} else {
				left = new Ast.Comparison(op, left, right);
				chained = right;
			}
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
			return new Ast.Binary(Op.POWER, left, nested(this::power));
		}
		return left;
	}

	private Expr unary() {
		if (peek().isSymbol("-")) {
			take();
			// The one integer literal that only exists negated: 9223372036854775808 does not fit in a long, and its
			// negation does. Folded here because no later stage can see the minus and the digits together.
			if (peek().type() == Token.Type.INTEGER && peek().text().equals("9223372036854775808")) {
				take();
				return new Ast.Literal(Long.MIN_VALUE);
			}
			return new Ast.Unary(Op.NEGATE, nested(this::unary));
		}
		if (peek().isSymbol("+")) {
			take();
			return nested(this::unary);
		}
		return postfix();
	}

	private Expr postfix() {
		Expr expression = primary();
		while (true) {
			if (peek().isSymbol(".")) {
				take();
				String name = propertyKeyName();
				// A namespaced function call — `vector.cosine(a, b)`. The lexer cannot see it: `.` is not an
				// identifier character (and must not become one, or `n.name` and the `1..3` range both break), so
				// `vector.cosine` arrives here as a property access on a variable named `vector`. Folding it back into
				// a call is only safe because of the two conditions below: the namespace has to be a bare variable, so
				// `n.a.b(…)` is untouched, and a `(` has to follow immediately.
				//
				// Without this the query does not fail as an unknown function. It fails at the *clause* — the `(` falls
				// out of the projection and RETURN reports that a parenthesis cannot follow it — which is a message
				// nobody could act on.
				if (expression instanceof Ast.Variable namespace && peek().isSymbol("(")) {
					expression = functionCall(namespace.name() + "." + name);
					continue;
				}
				expression = new Ast.Property(expression, name);
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
				return new Ast.Literal(integer(token));
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
						return caseExpression();
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

	/**
	 * {@code CASE}, in both forms, told apart by what follows the keyword.
	 *
	 * <pre>
	 * CASE n.grade WHEN 'A' THEN 4 WHEN 'B' THEN 3 ELSE 0 END     -- simple:  compared for equality
	 * CASE WHEN n.score &gt; 90 THEN 'A' WHEN n.score &gt; 80 THEN 'B' END  -- generic: each is a predicate
	 * </pre>
	 *
	 * <p>
	 * A {@code WHEN} straight after the keyword means there is no subject; anything else is one. That is the whole
	 * distinction, and it is unambiguous because a subject expression can never begin with {@code WHEN}.
	 * </p>
	 */
	private Expr caseExpression() {
		Token at = peek();
		expectKeyword("CASE");
		Expr subject = peek().isKeyword("WHEN") ? null : expression();

		List<Expr> whens = new ArrayList<>();
		List<Expr> thens = new ArrayList<>();
		while (peek().isKeyword("WHEN")) {
			take();
			whens.add(expression());
			expectKeyword("THEN");
			thens.add(expression());
		}
		if (whens.isEmpty()) {
			throw error(at, "CASE needs at least one WHEN ... THEN ...");
		}

		Expr otherwise = null;
		if (peek().isKeyword("ELSE")) {
			take();
			otherwise = expression();
		}
		expectKeyword("END");
		return new Ast.Case(subject, whens, thens, otherwise);
	}

	/**
	 * {@code CALL namespace.procedure(args) YIELD a, b}.
	 * <p>
	 * {@code YIELD} is required rather than optional. A call whose results are not bound to anything has nowhere to
	 * put them — this build has no implicit result table — and accepting it silently would make a query that looks
	 * like it does something do nothing at all.
	 */
	private Ast.Call callClause() {
		expectKeyword("CALL");
		StringBuilder procedure = new StringBuilder(propertyKeyName());
		while (peek().isSymbol(".")) {
			take();
			procedure.append('.').append(propertyKeyName());
		}
		expectSymbol("(", "the arguments of " + procedure);
		List<Expr> arguments = new ArrayList<>();
		if (!peek().isSymbol(")")) {
			arguments.add(expression());
			while (peek().isSymbol(",")) {
				take();
				arguments.add(expression());
			}
		}
		expectSymbol(")", "the end of the arguments");
		if (!peek().isKeyword("YIELD")) {
			throw error(peek(), "CALL " + procedure + "() needs a YIELD naming what to bind its results to");
		}
		take();
		List<String> yields = new ArrayList<>();
		yields.add(propertyKeyName());
		while (peek().isSymbol(",")) {
			take();
			yields.add(propertyKeyName());
		}
		return new Ast.Call(procedure.toString(), arguments, yields);
	}

	private Expr functionCall(String name) {
		String upper = name.toUpperCase(java.util.Locale.ROOT);
		if (upper.equals("ANY") || upper.equals("NONE") || upper.equals("SINGLE")) {
			// ALL is a keyword and is refused in primary(); these three are not, so they arrive as a function name and
			// would otherwise fail at the WHERE inside them.
			throw error(previous(), upper + " predicates are not supported in this build");
		}
		if (name.equals("shortestPath") || name.equals("allShortestPaths")) {
			// It parses as a function call here and its argument is a pattern, so the error would otherwise be about
			// the arrow rather than about where the call is allowed to appear.
			throw error(previous(), name + " belongs in a MATCH pattern rather than in an expression, as in "
				+ "MATCH p = " + name + "((a)-[:TYPE*..10]-(b))");
		}
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
			// raw(), for the reason this method exists at all: the column is named after what the caller wrote, and a
			// property key that happens to be a keyword — RETURN n.end — must not come back as `n.END`.
			out.append(token.type() == Token.Type.STRING ? "'" + token.raw() + "'"
				: token.type() == Token.Type.PARAMETER ? "$" + token.raw() : token.raw());
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
