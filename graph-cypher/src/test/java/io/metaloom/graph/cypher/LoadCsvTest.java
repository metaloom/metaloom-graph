package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.io.CsvAccessException;
import io.metaloom.graph.cypher.io.CsvFormatException;
import io.metaloom.graph.cypher.io.CsvSource;
import io.metaloom.graph.cypher.parse.CypherParser;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;

/**
 * {@code LOAD CSV}, end to end.
 *
 * <p>
 * The files are in memory. That is not a shortcut for the sake of speed: {@link CsvSource} exists precisely so that
 * <em>where the bytes come from</em> is somebody else's decision, and a test that wrote temp files would be testing the
 * server's sandbox — which has its own tests — rather than the clause.
 * </p>
 */
@Tag("unit")
public class LoadCsvTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;

	@BeforeEach
	public void openStore() throws Exception {
		store = stores.open();
	}

	/** A source over a fixed set of named documents; anything not in the map is refused, as a real one would. */
	private static CsvSource sourceOf(Map<String, String> files) {
		return url -> {
			String content = files.get(url);
			if (content == null) {
				throw new CsvAccessException("No such file: " + url);
			}
			return new StringReader(content);
		};
	}

	private static CsvSource sourceOf(String content) {
		return sourceOf(Map.of("file:///people.csv", content));
	}

	private List<List<Object>> rows(String query, CsvSource csv) {
		Collector collector = new Collector();
		Cypher.stream(store, CypherParser.parse(query), Map.of(), collector, QueryAbort.NEVER, csv);
		return collector.rows;
	}

	private CypherStats run(String query, CsvSource csv) {
		Collector collector = new Collector();
		Cypher.stream(store, CypherParser.parse(query), Map.of(), collector, QueryAbort.NEVER, csv);
		return collector.stats;
	}

	private static final class Collector implements RowSink {

		private List<String> columns = List.of();
		private final List<List<Object>> rows = new ArrayList<>();
		private CypherStats stats = CypherStats.NONE;

		@Override
		public void columns(List<String> columns) {
			this.columns = columns;
		}

		@Override
		public boolean row(List<Object> values) {
			rows.add(values);
			return true;
		}

		@Override
		public void stats(CypherStats stats) {
			this.stats = stats;
		}
	}

	// ---------------------------------------------------------------- grammar

	@Test
	public void testTheGrammar() {
		Ast.LoadCsv plain = (Ast.LoadCsv) CypherParser.parse("LOAD CSV FROM 'f.csv' AS row RETURN row")
			.parts().get(0).clauses().get(0);
		assertEquals("row", plain.variable());
		assertEquals(',', plain.fieldTerminator());
		assertTrue(!plain.withHeaders());

		Ast.LoadCsv full = (Ast.LoadCsv) CypherParser
			.parse("LOAD CSV WITH HEADERS FROM $file AS r FIELDTERMINATOR ';' RETURN r")
			.parts().get(0).clauses().get(0);
		assertTrue(full.withHeaders());
		assertEquals(';', full.fieldTerminator());
		assertInstanceOf(Ast.Parameter.class, full.url(), "the URL is an expression, so $file works");
	}

	/**
	 * The reason {@code FROM}, {@code HEADERS} and {@code FIELDTERMINATOR} are not lexer keywords.
	 *
	 * <p>
	 * Reserving them would fold every {@code r.from} in the language to {@code FROM} — and {@code from} is the single
	 * most likely column name in the edge lists this clause exists to load, which makes it the worst possible word to
	 * take away from users.
	 * </p>
	 */
	@Test
	public void testFromAndHeadersRemainOrdinaryNames() {
		Ast.Create create = (Ast.Create) CypherParser
			.parse("CREATE (n:Edge {from: 'a', to: 'b', headers: 1, fieldterminator: 2})")
			.parts().get(0).clauses().get(0);
		assertEquals(List.of("from", "to", "headers", "fieldterminator"),
			List.copyOf(create.pattern().get(0).nodes().get(0).properties().keySet()));

		// And inside the clause's own downstream, which is where it matters most.
		assertEquals(List.of(List.of("a", "b")),
			rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row RETURN row.from, row.to",
				sourceOf("from,to\na,b")));
	}

	@Test
	public void testTheClauseIsRefusedByNameWhenMalformed() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("LOAD CSV 'f.csv' AS row RETURN row")).getMessage().contains("Expected FROM"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("LOAD CSV WITH FROM 'f.csv' AS row RETURN row")).getMessage()
				.contains("WITH HEADERS"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("LOAD CSV FROM 'f.csv' AS row FIELDTERMINATOR ';;' RETURN row")).getMessage()
				.contains("single-character"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("LOAD CSV FROM 'f.csv' AS row FIELDTERMINATOR '\"' RETURN row")).getMessage()
				.contains("may not be a quote"));
	}

	// ---------------------------------------------------------------- reading

	@Test
	public void testWithoutHeadersARowIsAList() {
		assertEquals(List.of(List.of("1", "Alice"), List.of("2", "Bob")),
			rows("LOAD CSV FROM 'file:///people.csv' AS row RETURN row[0], row[1]",
				sourceOf("1,Alice\n2,Bob")));
	}

	@Test
	public void testWithHeadersARowIsAMap() {
		assertEquals(List.of(List.of("Alice", "30"), List.of("Bob", "41")),
			rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row RETURN row.name, row.age",
				sourceOf("name,age\nAlice,30\nBob,41")));
	}

	/**
	 * Every value is a string, and that is a decision rather than an omission.
	 *
	 * <p>
	 * Inferring types is the behaviour that looks helpful and silently destroys data: a leading-zero identifier becomes
	 * a number and loses its zeros, and a product code like {@code 1E5} becomes 100000.0. So the clause hands back what
	 * the file said and {@code toInteger} is written where it is meant.
	 * </p>
	 */
	@Test
	public void testValuesAreAlwaysStrings() {
		assertEquals(List.of(List.of("007", "1E5", "true")),
			rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS r RETURN r.id, r.code, r.ok",
				sourceOf("id,code,ok\n007,1E5,true")));
		// And the query says what it wants.
		assertEquals(List.of(List.of(7L, 100000.0d)),
			rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS r "
				+ "RETURN toInteger(r.id), toFloat(r.code)", sourceOf("id,code,ok\n007,1E5,true")));
	}

	@Test
	public void testAShortRowPadsWithNullAndALongRowIsRefused() {
		assertNull(rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS r RETURN r.age",
			sourceOf("name,age\nAlice")).get(0).get(0), "a missing trailing column is null, not an error");

		CsvFormatException wide = assertThrows(CsvFormatException.class,
			() -> rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS r RETURN r.name",
				sourceOf("name,age\nAlice,30,extra")));
		assertTrue(wide.getMessage().contains("Line 2"), wide.getMessage());
		assertTrue(wide.getMessage().contains("unquoted separator"), wide.getMessage());
	}

	@Test
	public void testADuplicateOrEmptyHeaderIsRefusedBeforeAnyRowIsRead() {
		assertTrue(assertThrows(CsvFormatException.class,
			() -> rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS r RETURN r.name",
				sourceOf("name,name\na,b"))).getMessage().contains("more than once"));
		assertTrue(assertThrows(CsvFormatException.class,
			() -> rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS r RETURN r.name",
				sourceOf("name,\na,b"))).getMessage().contains("no name in the header"));
	}

	@Test
	public void testAFieldTerminator() {
		assertEquals(List.of(List.of("Alice", "Bonn")),
			rows("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS r FIELDTERMINATOR ';' "
				+ "RETURN r.name, r.city", sourceOf("name;city\nAlice;Bonn")));
	}

	@Test
	public void testTheUrlCanBeAParameter() {
		Collector collector = new Collector();
		Cypher.stream(store, CypherParser.parse("LOAD CSV FROM $file AS r RETURN r[0]"),
			Map.of("file", "file:///people.csv"), collector, QueryAbort.NEVER, sourceOf("Alice"));
		assertEquals(List.of(List.of("Alice")), collector.rows);
	}

	// ---------------------------------------------------------------- access control

	/**
	 * The default is refusal, and it is the default everywhere.
	 *
	 * <p>
	 * A query string is very often the part of an application that came from outside it, so an embedder that has never
	 * heard of this clause must not be turned into a file reader by one.
	 * </p>
	 */
	@Test
	public void testWithoutASourceNothingIsRead() {
		CsvAccessException refused = assertThrows(CsvAccessException.class,
			() -> Cypher.query(store, "LOAD CSV FROM 'file:///etc/passwd' AS r RETURN r"));
		assertTrue(refused.getMessage().contains("does not read files"), refused.getMessage());
	}

	@Test
	public void testASourceRefusalPropagates() {
		assertThrows(CsvAccessException.class,
			() -> rows("LOAD CSV FROM 'file:///elsewhere.csv' AS r RETURN r[0]", sourceOf("x")));
	}

	/** A read-only server gates on {@code isWrite}, so the clause needs a flag of its own to be gated by. */
	@Test
	public void testAQueryThatOnlyReadsAFileIsNotAWriteButDoesReadFiles() {
		Ast.Query read = CypherParser.parse("LOAD CSV FROM 'f.csv' AS r RETURN r");
		assertTrue(!read.isWrite(), "nothing is created, so --allow-write would not refuse it");
		assertTrue(read.readsFiles(), "which is exactly why this exists");

		Ast.Query none = CypherParser.parse("MATCH (n) RETURN n");
		assertTrue(!none.readsFiles());
	}

	// ---------------------------------------------------------------- lifetime

	/**
	 * A {@code LIMIT} stops the read, and the reader is still closed.
	 *
	 * <p>
	 * The early exit is the path most likely to leak a handle, because it is the one that does not go through the end
	 * of the loop or through a catch.
	 * </p>
	 */
	@Test
	public void testAnEarlyLimitStopsTheReadAndClosesTheFile() {
		boolean[] closed = { false };
		CsvSource watched = url -> new StringReader("a\nb\nc\nd\ne") {

			@Override
			public void close() {
				closed[0] = true;
				super.close();
			}
		};
		assertEquals(2, rows("LOAD CSV FROM 'x' AS r RETURN r[0] LIMIT 2", watched).size());
		assertTrue(closed[0], "the reader is closed even though nothing drained it");
	}

	@Test
	public void testAFailureClosesTheFile() {
		boolean[] closed = { false };
		CsvSource watched = url -> new StringReader("name,name\na,b") {

			@Override
			public void close() {
				closed[0] = true;
				super.close();
			}
		};
		assertThrows(CsvFormatException.class,
			() -> rows("LOAD CSV WITH HEADERS FROM 'x' AS r RETURN r.name", watched));
		assertTrue(closed[0]);
	}

	// ---------------------------------------------------------------- writing

	@Test
	public void testLoadingAGraph() {
		CypherStats stats = run("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row "
			+ "CREATE (p:Imported {name: row.name, age: toInteger(row.age)})",
			sourceOf("name,age\nAlice,30\nBob,41\nCarol,25"));
		assertEquals(3, stats.nodesCreated());
		assertEquals(6, stats.propertiesSet());

		assertEquals(List.of(List.of("Alice", 30L)),
			rows("MATCH (p:Imported) WHERE p.age = 30 RETURN p.name, p.age", CsvSource.DENY));
		store.verify(VerifyLevel.FULL);
	}

	@Test
	public void testLoadingRelationshipsFromAnEdgeList() {
		run("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row "
			+ "MERGE (a:City {name: row.from}) MERGE (b:City {name: row.to}) MERGE (a)-[:ROAD]->(b)",
			sourceOf("from,to\nBonn,Cologne\nCologne,Bonn\nBonn,Aachen"));

		assertEquals(List.of(List.of(3L)), rows("MATCH (c:City) RETURN count(c)", CsvSource.DENY));
		assertEquals(List.of(List.of(2L)),
			rows("MATCH (:City {name: 'Bonn'})-[:ROAD]->(c) RETURN count(c)", CsvSource.DENY));
		store.verify(VerifyLevel.FULL);
	}

	// ---------------------------------------------------------------- periodic commit

	@Test
	public void testPeriodicCommitLoadsEverything() {
		StringBuilder csv = new StringBuilder("name\n");
		for (int i = 0; i < 250; i++) {
			csv.append("p").append(i).append('\n');
		}
		CypherStats stats = run("USING PERIODIC COMMIT 100 "
			+ "LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row CREATE (:Batched {name: row.name})",
			sourceOf(csv.toString()));
		assertEquals(250, stats.nodesCreated(), "the counters are the load's, not the last batch's");
		assertEquals(List.of(List.of(250L)),
			rows("MATCH (n:Batched) RETURN count(n)", CsvSource.DENY));
		store.verify(VerifyLevel.FULL);
	}

	/** A row count that divides exactly must not open a final transaction for the zero rows after it. */
	@Test
	public void testABatchSizeThatDividesExactly() {
		StringBuilder csv = new StringBuilder("name\n");
		for (int i = 0; i < 20; i++) {
			csv.append("e").append(i).append('\n');
		}
		assertEquals(20, run("USING PERIODIC COMMIT 10 "
			+ "LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row CREATE (:Exact {name: row.name})",
			sourceOf(csv.toString())).nodesCreated());
		store.verify(VerifyLevel.FULL);
	}

	/**
	 * The trade the clause asks for, asserted rather than only documented.
	 *
	 * <p>
	 * A batched load is not one transaction, so a failure partway leaves the batches before it durable. There is
	 * nothing to roll back — those transactions ended successfully — and the useful thing to do is say so, with the
	 * count, rather than let the caller discover it by querying.
	 * </p>
	 */
	@Test
	public void testAFailedBatchLeavesTheEarlierOnesCommittedAndSaysSo() {
		StringBuilder csv = new StringBuilder("name\n");
		for (int i = 0; i < 25; i++) {
			// Row 21 has a width the header cannot explain, so the third batch fails while the first two are durable.
			csv.append(i == 21 ? "boom,extra" : "u" + i).append('\n');
		}
		CypherWriteException failure = assertThrows(CypherWriteException.class,
			() -> run("USING PERIODIC COMMIT 10 "
				+ "LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row CREATE (:Partial {name: row.name})",
				sourceOf(csv.toString())));
		assertTrue(failure.getMessage().contains("20 rows were already committed"), failure.getMessage());
		assertTrue(failure.getMessage().contains("cannot be rolled back"), failure.getMessage());
		assertInstanceOf(CsvFormatException.class, failure.getCause());

		assertEquals(List.of(List.of(20L)), rows("MATCH (n:Partial) RETURN count(n)", CsvSource.DENY),
			"exactly the batches that committed, and no half batch");
		store.verify(VerifyLevel.FULL);
	}

	/** A failure in the very first batch rolls back completely, and is reported as the ordinary failure it is. */
	@Test
	public void testAFailureBeforeAnyCommitIsNotDressedUp() {
		assertThrows(CsvFormatException.class,
			() -> run("USING PERIODIC COMMIT 10 "
				+ "LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row CREATE (:Nothing {name: row.name})",
				sourceOf("name\na\nb,c\n")));
		assertEquals(List.of(List.of(0L)), rows("MATCH (n:Nothing) RETURN count(n)", CsvSource.DENY));
	}

	@Test
	public void testPeriodicCommitIsRefusedInsideACallersTransaction() {
		try (var txn = store.begin()) {
			CypherWriteException refused = assertThrows(CypherWriteException.class,
				() -> run("USING PERIODIC COMMIT 10 LOAD CSV FROM 'file:///people.csv' AS r "
					+ "CREATE (:Inner {name: r[0]})", sourceOf("a\nb")));
			assertTrue(refused.getMessage().contains("cannot run inside a transaction the caller opened"),
				refused.getMessage());
		}
	}

	@Test
	public void testPeriodicCommitRequiresLoadCsvFirst() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("USING PERIODIC COMMIT 100 MATCH (n) CREATE (:Copy)")).getMessage()
				.contains("first clause"));
	}

	@Test
	public void testABatchSizeMustBePositiveAndBounded() {
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("USING PERIODIC COMMIT 0 LOAD CSV FROM 'f' AS r CREATE (:X)")).getMessage()
				.contains("positive"));
		assertTrue(assertThrows(CypherSyntaxException.class,
			() -> CypherParser.parse("USING PERIODIC COMMIT 99999999 LOAD CSV FROM 'f' AS r CREATE (:X)"))
				.getMessage().contains("above the limit"));
		assertEquals(CypherParser.DEFAULT_PERIODIC_COMMIT,
			CypherParser.parse("USING PERIODIC COMMIT LOAD CSV FROM 'f' AS r CREATE (:X)").parts().get(0)
				.periodicCommit(),
			"a bare PERIODIC COMMIT takes the default");
	}

	// ---------------------------------------------------------------- both execution paths

	/**
	 * The streaming and materialised paths must agree, which is the standing rule for every clause here.
	 *
	 * <p>
	 * {@code LOAD CSV … RETURN} streams; adding a write to the same query forces the list path. Both are asked the same
	 * question about the same file.
	 * </p>
	 */
	@Test
	public void testTheTwoPathsAgree() {
		String file = "name,age\nAlice,30\nBob,41\nCarol,25";
		List<List<Object>> streamed = rows(
			"LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row RETURN row.name, row.age", sourceOf(file));

		run("LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row "
			+ "CREATE (:Agree {name: row.name, age: row.age})", sourceOf(file));
		List<List<Object>> materialised = rows(
			"MATCH (n:Agree) RETURN n.name, n.age ORDER BY n.name DESC", CsvSource.DENY);

		assertEquals(3, streamed.size());
		assertEquals(List.copyOf(streamed), List.of(List.of("Alice", "30"), List.of("Bob", "41"),
			List.of("Carol", "25")));
		assertEquals(3, materialised.size());
		assertTrue(materialised.contains(List.of("Alice", "30")));
	}

	/** A load that touches the store never must still be stoppable, or the query deadline would not bound it. */
	@Test
	public void testALoadThatOnlyReadsIsStillAbortable() {
		StringBuilder csv = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			csv.append("row").append(i).append('\n');
		}
		Collector collector = new Collector();
		assertThrows(QueryAbortedException.class,
			() -> Cypher.stream(store, CypherParser.parse("LOAD CSV FROM 'file:///people.csv' AS r RETURN r[0]"),
				Map.of(), collector, () -> "the deadline passed", sourceOf(csv.toString())));
	}

	/** A source that hands back a failing reader surfaces as an I/O failure rather than as a wrong answer. */
	@Test
	public void testAnUnreadableFileFails() {
		CsvSource broken = url -> new Reader() {

			@Override
			public int read(char[] chars, int off, int len) throws IOException {
				throw new IOException("the disk went away");
			}

			@Override
			public void close() {
			}
		};
		assertThrows(RuntimeException.class,
			() -> rows("LOAD CSV FROM 'x' AS r RETURN r[0]", broken));
	}
}
