package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.parse.CypherLexer;
import io.metaloom.graph.cypher.parse.CypherParser;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;
import io.metaloom.graph.cypher.parse.Token;

/**
 * The lexer and the parser: what the grammar accepts, what it refuses, and where it says the problem is.
 *
 * <p>
 * The error cases matter as much as the successes. This grammar accepts a <em>subset</em> of openCypher, so a fair
 * share of failures are valid Cypher this build cannot run — and telling that apart from a typo is most of what makes
 * a query language usable.
 * </p>
 */
@Tag("unit")
public class CypherParserTest {

	// ---------------------------------------------------------------- lexer

	@Test
	public void testKeywordsAreCaseInsensitiveAndIdentifiersAreNot() {
		List<Token> tokens = CypherLexer.tokenize("match (Person) RETURN person");
		assertEquals(Token.Type.KEYWORD, tokens.get(0).type());
		assertEquals("MATCH", tokens.get(0).text(), "a keyword normalises to upper case");
		assertEquals("Person", tokens.get(2).text(), "an identifier keeps its case");
		assertEquals("person", tokens.get(5).text());
	}

	@Test
	public void testNumbers() {
		assertEquals("42", CypherLexer.tokenize("42").get(0).text());
		assertEquals(Token.Type.INTEGER, CypherLexer.tokenize("42").get(0).type());
		assertEquals(Token.Type.FLOAT, CypherLexer.tokenize("4.2").get(0).type());
		assertEquals(Token.Type.FLOAT, CypherLexer.tokenize("1e3").get(0).type());
		assertEquals("255", CypherLexer.tokenize("0xff").get(0).text(), "hex is decoded to decimal");
	}

	/**
	 * The range operator must not be eaten by the number before it.
	 * <p>
	 * {@code *1..3} would lex as the float {@code 1.} followed by {@code .3} if the lexer consumed a dot without
	 * checking for a digit behind it, and the pattern would then fail with something incomprehensible.
	 */
	@Test
	public void testARangeIsNotAFloat() {
		List<Token> tokens = CypherLexer.tokenize("1..3");
		assertEquals(Token.Type.INTEGER, tokens.get(0).type());
		assertEquals("1", tokens.get(0).text());
		assertEquals("..", tokens.get(1).text());
		assertEquals("3", tokens.get(2).text());
	}

	@Test
	public void testStringsAndEscapes() {
		assertEquals("it's", CypherLexer.tokenize("\"it's\"").get(0).text());
		assertEquals("a\nb", CypherLexer.tokenize("'a\\nb'").get(0).text());
		assertEquals("quote\"inside", CypherLexer.tokenize("'quote\"inside'").get(0).text());
		assertEquals("A", CypherLexer.tokenize("'\\u0041'").get(0).text());
	}

	@Test
	public void testBacktickNamesAndComments() {
		assertEquals("a name", CypherLexer.tokenize("`a name`").get(0).text());
		assertEquals("with`tick", CypherLexer.tokenize("`with``tick`").get(0).text());
		assertEquals(Token.Type.EOF, CypherLexer.tokenize("// nothing\n/* also nothing */").get(0).type());
	}

	@Test
	public void testUnterminatedConstructsPointAtTheirStart() {
		CypherSyntaxException e = assertThrows(CypherSyntaxException.class,
			() -> CypherLexer.tokenize("RETURN 'oops"));
		assertEquals(1, e.line());
		assertEquals(8, e.column(), "the caret goes on the opening quote, not on the end of input");
		assertTrue(e.getMessage().contains("Unterminated"), e.getMessage());
	}

	// ---------------------------------------------------------------- parser

	@Test
	public void testASimpleQuery() {
		Ast.Query query = CypherParser.parse("MATCH (n:Person) WHERE n.age > 30 RETURN n.name AS name LIMIT 5");
		Ast.SingleQuery single = query.parts().get(0);
		assertEquals(2, single.clauses().size());

		Ast.Match match = (Ast.Match) single.clauses().get(0);
		assertEquals(List.of("Person"), match.pattern().get(0).nodes().get(0).labels());
		assertEquals("n", match.pattern().get(0).nodes().get(0).variable());
		assertTrue(match.where() instanceof Ast.Comparison);

		Ast.Return returned = (Ast.Return) single.clauses().get(1);
		assertEquals("name", returned.projection().items().get(0).alias());
		assertTrue(returned.projection().limit() instanceof Ast.Literal);
	}

	@Test
	public void testAnUnaliasedColumnIsNamedAfterItsSource() {
		Ast.Return returned = (Ast.Return) CypherParser.parse("RETURN n.name, count(*), 1 + 2")
			.parts().get(0).clauses().get(0);
		assertEquals("n.name", returned.projection().items().get(0).alias());
		assertEquals("count(*)", returned.projection().items().get(1).alias());
	}

	@Test
	public void testRelationshipDirectionsAndRanges() {
		Ast.Match right = (Ast.Match) CypherParser.parse("MATCH (a)-[:R]->(b) RETURN a")
			.parts().get(0).clauses().get(0);
		assertEquals(Ast.Direction.RIGHT, right.pattern().get(0).relationships().get(0).direction());

		Ast.Match left = (Ast.Match) CypherParser.parse("MATCH (a)<-[:R]-(b) RETURN a")
			.parts().get(0).clauses().get(0);
		assertEquals(Ast.Direction.LEFT, left.pattern().get(0).relationships().get(0).direction());

		Ast.Match both = (Ast.Match) CypherParser.parse("MATCH (a)-[:R]-(b) RETURN a")
			.parts().get(0).clauses().get(0);
		assertEquals(Ast.Direction.BOTH, both.pattern().get(0).relationships().get(0).direction());
	}

	@Test
	public void testRangeForms() {
		assertEquals(new Ast.Range(1, Integer.MAX_VALUE), range("MATCH (a)-[*]->(b) RETURN a"));
		assertEquals(new Ast.Range(2, 2), range("MATCH (a)-[*2]->(b) RETURN a"), "*2 is exactly two hops");
		assertEquals(new Ast.Range(1, 3), range("MATCH (a)-[*1..3]->(b) RETURN a"));
		assertEquals(new Ast.Range(2, Integer.MAX_VALUE), range("MATCH (a)-[*2..]->(b) RETURN a"));
		assertEquals(new Ast.Range(0, 1), range("MATCH (a)-[*0..1]->(b) RETURN a"));
		assertNull(((Ast.Match) CypherParser.parse("MATCH (a)-[:R]->(b) RETURN a").parts().get(0).clauses().get(0))
			.pattern().get(0).relationships().get(0).range(), "no star means no range");
	}

	private static Ast.Range range(String query) {
		return ((Ast.Match) CypherParser.parse(query).parts().get(0).clauses().get(0))
			.pattern().get(0).relationships().get(0).range();
	}

	@Test
	public void testOperatorPrecedence() {
		// 1 + 2 * 3 must group as 1 + (2 * 3), so the root is the addition.
		Ast.Binary root = (Ast.Binary) projected("RETURN 1 + 2 * 3");
		assertEquals(Ast.Op.ADD, root.op());
		assertEquals(Ast.Op.MULTIPLY, ((Ast.Binary) root.right()).op());

		// AND binds tighter than OR.
		Ast.Binary or = (Ast.Binary) projected("RETURN true OR false AND false");
		assertEquals(Ast.Op.OR, or.op());
		assertEquals(Ast.Op.AND, ((Ast.Binary) or.right()).op());

		// Power is right associative.
		Ast.Binary power = (Ast.Binary) projected("RETURN 2 ^ 3 ^ 2");
		assertEquals(Ast.Op.POWER, ((Ast.Binary) power.right()).op());
	}

	private static Ast.Expr projected(String query) {
		return ((Ast.Return) CypherParser.parse(query).parts().get(0).clauses().get(0))
			.projection().items().get(0).expression();
	}

	@Test
	public void testUnion() {
		Ast.Query query = CypherParser.parse("RETURN 1 AS x UNION ALL RETURN 2 AS x UNION RETURN 3 AS x");
		assertEquals(3, query.parts().size());
		assertEquals(List.of(true, false), query.unionAll(), "one flag per union, in order");
	}

	// ---------------------------------------------------------------- refusals

	@Test
	public void testAWriteClauseSaysItIsReadOnly() {
		CypherSyntaxException e = assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("CREATE (n:Person) RETURN n"));
		assertTrue(e.getMessage().contains("read-only"), e.getMessage());
		assertEquals(1, e.column(), "and points at the clause");
	}

	@Test
	public void testUnsupportedFeaturesAreNamedRatherThanCalledSyntaxErrors() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("OPTIONAL MATCH (n) RETURN n")).getMessage().contains("OPTIONAL MATCH"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("RETURN CASE WHEN true THEN 1 ELSE 2 END")).getMessage().contains("CASE"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MATCH (n) SET n.x = 1 RETURN n")).getMessage().contains("read-only"));
	}

	@Test
	public void testTheCaretLandsOnTheOffendingToken() {
		CypherSyntaxException e = assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MATCH (n:Person) WHERE n.age > > 30 RETURN n"));
		assertEquals(1, e.line());
		assertEquals(32, e.column());
		// The rendered message carries the source line and a caret beneath it, which is what makes it usable.
		String[] lines = e.getMessage().split("\n");
		assertEquals("MATCH (n:Person) WHERE n.age > > 30 RETURN n", lines[0]);
		assertEquals(31, lines[1].indexOf('^'));
	}

	@Test
	public void testAMultilineQueryReportsTheRightLine() {
		CypherSyntaxException e = assertThrows(CypherSyntaxException.class, () -> CypherParser.parse("""
			MATCH (n:Person)
			WHERE n.age >
			RETURN n
			"""));
		assertEquals(3, e.line(), "the failure is where RETURN appeared instead of an expression");
	}

	@Test
	public void testAQueryWithoutReturnIsRefused() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MATCH (n)")).getMessage().contains("RETURN"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("")).getMessage().contains("Empty query"));
	}

	@Test
	public void testTrailingInputIsRefused() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("RETURN 1 nonsense here")).getMessage().contains("after the end"));
	}

	@Test
	public void testAnInvertedRangeIsRefused() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("MATCH (a)-[*5..2]->(b) RETURN a")).getMessage().contains("min <= max"));
	}

	@Test
	public void testKeywordsAreUsableAsLabelsAndPropertyNames() {
		// `:Order`, `:End` and `.order` are ordinary names in a graph, and refusing them would be gratuitous.
		Ast.Match match = (Ast.Match) CypherParser.parse("MATCH (n:Order {end: 1}) RETURN n.order")
			.parts().get(0).clauses().get(0);
		assertEquals(List.of("ORDER"), match.pattern().get(0).nodes().get(0).labels());
		assertTrue(match.pattern().get(0).nodes().get(0).properties().containsKey("END"));
	}
}
