package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.exec.GraphAccess;
import io.metaloom.graph.cypher.exec.QueryExecutor;

/**
 * {@link Cypher#stream} against the executor it is supposed to be a view of.
 *
 * <h2>Why the reference path is the executor and not {@link Cypher#query}</h2>
 *
 * <p>
 * {@code query} is implemented as {@code stream} plus a collecting sink, so comparing the two would compare a thing
 * with itself and pass whatever either of them did. The reference here is therefore {@link QueryExecutor} driven
 * directly — the same two lines {@code Cypher} used before {@code stream} existed — which is a genuinely separate path
 * through the same engine.
 * </p>
 *
 * <p>
 * That distinction stops being subtle at Stage 3. When the non-blocking clause prefix becomes lazy, {@code stream}
 * will no longer route through {@code QueryExecutor.run} for most queries, and this class becomes the test that the
 * lazy pipeline and the list-at-a-time one agree — <b>ordered, cell for cell</b>. The query list below is chosen for
 * that future: it covers the clauses that will stream and the ones that must keep buffering, so a regression in
 * either shows up here rather than in a wire test three modules away.
 * </p>
 *
 * <h2>What it checks beyond the values</h2>
 *
 * <p>
 * The value comparison is the least interesting half. The contracts a network protocol actually leans on are that
 * {@code columns} arrives exactly once and before any row <em>including for an empty result</em>, that stopping early
 * yields exactly the prefix and not a re-run, and that the read view closes on every exit — returning false, throwing,
 * or running out of rows. Each of those has its own test below, because each is a different way for a server to leak a
 * snapshot.
 * </p>
 */
@Tag("unit")
public class StreamingEquivalenceTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;

	/**
	 * Every clause the executor accepts, and the shapes that will land on either side of the Stage 3 split.
	 *
	 * <p>
	 * Ordered results where the query orders, unordered where it does not — the comparison below is exact and ordered
	 * either way, because both paths walk the same underlying data in the same sequence and a change in that sequence
	 * is exactly what this is here to catch.
	 * </p>
	 */
	private static final List<String> QUERIES = List.of(
		// Streamable at Stage 3: scan, filter, project, expand, slice.
		"MATCH (n) RETURN n",
		"MATCH (n:Person) RETURN n.name",
		"MATCH (n:Person) WHERE n.age > 30 RETURN n.name, n.age",
		"MATCH (n:Person) WHERE n.city = 'Bonn' AND n.age IS NOT NULL RETURN n.name",
		"MATCH (a:Person)-[r:KNOWS]->(b) RETURN a.name, r.since, b.name",
		"MATCH (a)-[r]->(b) RETURN a, r, b",
		"MATCH (a:Person)-[:KNOWS*1..2]->(b) RETURN a.name, b.name",
		"MATCH p = (a:Person)-[:KNOWS]->(b) RETURN p",
		"MATCH (n:Person) RETURN n.name SKIP 1",
		"MATCH (n:Person) RETURN n.name LIMIT 2",
		"MATCH (n:Person) RETURN n.name SKIP 1 LIMIT 1",
		"UNWIND [1, 2, 3] AS x RETURN x",
		"UNWIND [1, 2, 3] AS x WITH x WHERE x > 1 RETURN x",
		"MATCH (n:Person) WITH n WHERE n.age IS NOT NULL RETURN n.name",
		"MATCH (n) RETURN n.name, n.missing",
		"RETURN 1 AS one, 'two' AS two, true AS three, null AS four",
		"MATCH (n:NoSuchLabel) RETURN n.name",
		// Blocking at Stage 3, and therefore the fallback path: order, distinct, aggregate, star, union.
		"MATCH (n:Person) RETURN n.name ORDER BY n.name",
		"MATCH (n:Person) RETURN n.name ORDER BY n.name DESC LIMIT 2",
		"MATCH (n:Person) RETURN DISTINCT n.city",
		"MATCH (n:Person) RETURN count(*) AS people",
		"MATCH (n:Person) RETURN n.city, count(*) AS people ORDER BY n.city",
		"MATCH (n:Person) RETURN min(n.age) AS youngest, max(n.age) AS oldest",
		"MATCH (n:Person) RETURN collect(n.name) AS names",
		"MATCH (n:Person) RETURN *",
		"MATCH (n:Person) WITH * RETURN n.name",
		// Both parts of a UNION have to project the same column names, hence the aliases.
		"MATCH (n:Person) RETURN n.name AS name UNION MATCH (p:Product) RETURN p.name AS name",
		"MATCH (n:Person) RETURN n.name AS name UNION ALL MATCH (p:Product) RETURN p.name AS name");

	@BeforeEach
	public void seed() throws Exception {
		store = stores.open();
		try (Transaction txn = store.begin()) {
			long alice = store.createNode(List.of("Person", "Employee"),
				Properties.of().set("name", "Alice").set("age", 34L).set("city", "Bonn"));
			long bob = store.createNode(List.of("Person"),
				Properties.of().set("name", "Bob").set("age", 28L).set("city", "Bonn"));
			long carol = store.createNode(List.of("Person"),
				Properties.of().set("name", "Carol").set("city", "Berlin"));
			long widget = store.createNode(List.of("Product"), Properties.of().set("name", "Widget"));

			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L));
			store.createEdge(bob, "KNOWS", carol);
			store.createEdge(alice, "BOUGHT", widget);
			txn.commit();
		}
	}

	@AfterEach
	public void checkNoViewLeaked() throws Exception {
		// On every test, not only the ones about lifecycle: a leaked view is invisible until something much later
		// fails, and by then the test that leaked it is not the one that goes red.
		assertEquals(0, store.snapshotStats().openViews(), "a query must not leave a read view open");
		store.close();
	}

	// ---------------------------------------------------------------- the reference path

	/** What {@code Cypher} did before {@code stream} existed: the executor, driven directly, inside its own view. */
	private QueryExecutor.Result reference(String query) {
		Ast.Query parsed = Cypher.parse(query);
		try (var view = store.beginRead()) {
			return new QueryExecutor(new GraphAccess(store), Map.of()).run(parsed);
		}
	}

	private static final class Recorder implements RowSink {

		private final List<List<Object>> rows = new ArrayList<>();
		private List<String> columns;
		private int columnsCalls;
		private final int stopAfter;

		Recorder() {
			this(Integer.MAX_VALUE);
		}

		Recorder(int stopAfter) {
			this.stopAfter = stopAfter;
		}

		@Override
		public void columns(List<String> columns) {
			this.columns = columns;
			this.columnsCalls++;
		}

		@Override
		public boolean row(List<Object> values) {
			assertEquals(1, columnsCalls, "columns must arrive before the first row");
			rows.add(new ArrayList<>(values));
			return rows.size() < stopAfter;
		}
	}

	// ---------------------------------------------------------------- the comparison

	@Test
	public void testEveryClauseAgreesWithTheExecutorCellForCell() throws Exception {
		for (String query : QUERIES) {
			QueryExecutor.Result expected = reference(query);

			Recorder recorder = new Recorder();
			Cypher.stream(store, query, Map.of(), recorder);

			assertEquals(expected.columns(), recorder.columns, "columns differ for: " + query);
			assertEquals(expected.rows().size(), recorder.rows.size(), "row count differs for: " + query);
			// Ordered and cell by cell. Comparing sorted or as a multiset would hide exactly the defect this is
			// most likely to catch once the pipeline goes lazy, which is a change in the order rows are produced.
			assertEquals(expected.rows(), recorder.rows, "rows differ for: " + query);
		}
	}

	@Test
	public void testColumnsArriveExactlyOnceEvenWithNoRows() throws Exception {
		Recorder recorder = new Recorder();
		Cypher.stream(store, "MATCH (n:NoSuchLabel) RETURN n.name AS name", Map.of(), recorder);

		assertTrue(recorder.rows.isEmpty());
		assertEquals(1, recorder.columnsCalls, "an empty result still has columns, and a header frame needs them");
		assertEquals(List.of("name"), recorder.columns);
	}

	@Test
	public void testParametersReachTheStreamingPath() throws Exception {
		Recorder recorder = new Recorder();
		Cypher.stream(store, "MATCH (n:Person) WHERE n.age > $min RETURN n.name", Map.of("min", 30L), recorder);

		assertEquals(List.of(List.of("Alice")), recorder.rows);
	}

	// ---------------------------------------------------------------- stopping early

	@Test
	public void testStoppingEarlyYieldsExactlyThePrefix() throws Exception {
		String query = "MATCH (n:Person) RETURN n.name ORDER BY n.name";
		List<List<Object>> all = reference(query).rows();
		assertTrue(all.size() >= 3, "the fixture needs enough rows for a prefix to be a prefix");

		for (int stop = 1; stop <= all.size(); stop++) {
			Recorder recorder = new Recorder(stop);
			Cypher.stream(store, query, Map.of(), recorder);
			assertEquals(all.subList(0, stop), recorder.rows, "stopping after " + stop + " rows");
		}
	}

	@Test
	public void testStoppingEarlyIsNotAnError() throws Exception {
		// Returning false is an outcome, not a failure: stream returns normally and the caller is not asked to
		// catch anything. A protocol that cancels a query does it this way on every request.
		Recorder recorder = new Recorder(1);
		Cypher.stream(store, "MATCH (n) RETURN n", Map.of(), recorder);
		assertEquals(1, recorder.rows.size());
	}

	// ---------------------------------------------------------------- the view closes on every exit

	@Test
	public void testTheViewClosesWhenTheSinkStopsEarly() throws Exception {
		Cypher.stream(store, "MATCH (n) RETURN n", Map.of(), new Recorder(1));
		assertEquals(0, store.snapshotStats().openViews(),
			"stopping early must close the view, or a cancelling client leaks a snapshot per query");
	}

	@Test
	public void testTheViewClosesWhenTheSinkThrows() throws Exception {
		RuntimeException boom = new RuntimeException("the client hung up");
		RuntimeException thrown = assertThrows(RuntimeException.class,
			() -> Cypher.stream(store, "MATCH (n) RETURN n", Map.of(), values -> {
				throw boom;
			}));

		assertSame(boom, thrown, "the sink's failure is the caller's, not something wrapped");
		assertEquals(0, store.snapshotStats().openViews(), "a throwing sink must not leak the view either");
	}

	@Test
	public void testTheViewClosesWhenTheQueryFails() throws Exception {
		// Parses cleanly and fails during execution, so the view is already open when it throws — which is the
		// case that leaks if the close is not in a finally.
		assertThrows(CypherTypeException.class,
			() -> Cypher.stream(store, "UNWIND 5 AS x RETURN x", Map.of(), new Recorder()));
		assertEquals(0, store.snapshotStats().openViews());
	}

	// ---------------------------------------------------------------- joining a caller's view

	@Test
	public void testStreamJoinsACallersViewRatherThanNesting() throws Exception {
		try (var view = store.beginRead()) {
			Recorder first = new Recorder();
			Recorder second = new Recorder();
			// Two queries inside one caller-owned view. Opening a second view would be refused outright, so this
			// failing looks like an exception rather than a wrong answer.
			Cypher.stream(store, "MATCH (n:Person) RETURN n.name ORDER BY n.name", Map.of(), first);
			Cypher.stream(store, "MATCH (n:Person) RETURN n.name ORDER BY n.name", Map.of(), second);

			assertEquals(first.rows, second.rows);
			assertEquals(1, store.snapshotStats().openViews(), "the caller's view, and only the caller's");
		}
	}

	@Test
	public void testStreamDoesNotCloseACallersView() throws Exception {
		try (var view = store.beginRead()) {
			Cypher.stream(store, "MATCH (n) RETURN n", Map.of(), new Recorder(1));
			assertTrue(view.isOpen(), "stopping early inside a caller's view must not close the caller's view");
			assertFalse(store.snapshotStats().openViews() == 0);
		}
	}
}
