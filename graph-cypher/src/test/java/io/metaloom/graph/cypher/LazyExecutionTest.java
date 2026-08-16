package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * The streaming executor: what it stops early, and what it correctly refuses to.
 *
 * <p>
 * The claim being tested is a change in <em>complexity</em>, not a constant factor. Over a list, {@code LIMIT 5} reads
 * every node and then throws almost all of them away; through the push pipeline it reads five. So the assertions here
 * count what the store was actually asked for, rather than timing anything — a timing test would pass on a fast machine
 * with the wrong implementation underneath.
 * </p>
 *
 * <p>
 * The other half matters as much: {@code ORDER BY}, {@code DISTINCT}, aggregation, {@code RETURN *} and every write
 * clause must <b>not</b> stop early, because each needs input it has not seen. A pipeline that stopped one of those
 * short would return a wrong answer rather than a slow one.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@Tag("unit")
public class LazyExecutionTest {

	private static final int NODES = 5_000;

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;
	/**
	 * Nodes actually materialised, which is the per-node work both access paths do.
	 *
	 * <p>
	 * Counting the scan callback alone would have been wrong and would have looked right: {@code MATCH (n:Person)}
	 * starts from the <em>label index</em>, so the scan is never entered and a counter on it reads zero whether the
	 * pipeline stopped early or not. The first version of this test passed for exactly that reason.
	 * </p>
	 */
	private AtomicInteger nodesRead;
	/** Callbacks from the unindexed scan, which only a pattern with no label and no indexed property reaches. */
	private AtomicInteger scanCallbacks;
	private GraphStore counting;

	@BeforeEach
	public void seed() throws Exception {
		store = stores.open();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < NODES; i++) {
				store.createNode(List.of("Person"),
					Properties.of().set("name", "p" + i).set("n", (long) i).set("bucket", (long) (i % 10)));
			}
			txn.commit();
		}
		nodesRead = new AtomicInteger();
		scanCallbacks = new AtomicInteger();
		counting = counting(store, nodesRead, scanCallbacks);
	}

	/**
	 * The store, with its node scan counted.
	 *
	 * <p>
	 * A proxy rather than a hand-written delegate because {@code GraphStore} has forty methods and thirty-eight of them
	 * would be noise. Counting at the store boundary rather than inside the executor is deliberate: it measures what
	 * was actually asked of the engine, which is the claim, rather than what the executor believes it asked for.
	 * </p>
	 */
	private static GraphStore counting(GraphStore delegate, AtomicInteger nodesRead, AtomicInteger scanCallbacks) {
		return (GraphStore) java.lang.reflect.Proxy.newProxyInstance(GraphStore.class.getClassLoader(),
			new Class<?>[] { GraphStore.class }, (proxy, method, args) -> {
				try {
					// One call per node the executor turned into a value, whichever access path found it.
					if (method.getName().equals("nodeLabels")) {
						nodesRead.incrementAndGet();
					}
					if (method.getName().equals("forEachNodeWhile")) {
						java.util.function.LongPredicate inner = (java.util.function.LongPredicate) args[0];
						return delegate.forEachNodeWhile(id -> {
							scanCallbacks.incrementAndGet();
							return inner.test(id);
						});
					}
					if (method.getName().equals("forEachNode")) {
						java.util.function.LongConsumer inner = (java.util.function.LongConsumer) args[0];
						delegate.forEachNode(id -> {
							scanCallbacks.incrementAndGet();
							inner.accept(id);
						});
						return null;
					}
					return method.invoke(delegate, args);
				} catch (java.lang.reflect.InvocationTargetException e) {
					// Or every store failure would reach the test as a reflection wrapper.
					throw e.getCause();
				}
			});
	}

	private List<List<Object>> run(String query) {
		Collecting sink = new Collecting();
		Cypher.stream(counting, Cypher.parse(query), Map.of(), sink);
		return sink.rows;
	}

	private static final class Collecting implements RowSink {

		private final List<List<Object>> rows = new ArrayList<>();
		private List<String> columns = List.of();

		@Override
		public void columns(List<String> columns) {
			this.columns = columns;
		}

		@Override
		public boolean row(List<Object> values) {
			rows.add(values);
			return true;
		}
	}

	// ---------------------------------------------------------------- what stops early

	@Test
	public void testLimitStopsTheScan() throws Exception {
		List<List<Object>> rows = run("MATCH (n:Person) RETURN n.name AS name LIMIT 5");

		assertEquals(5, rows.size());
		assertTrue(nodesRead.get() < 50,
			"a LIMIT 5 over " + NODES + " nodes must not read them all; it read " + nodesRead.get());
		store.close();
	}

	/**
	 * The unindexed scan, which is what {@code GraphStore.forEachNodeWhile} was added for.
	 *
	 * <p>
	 * No label and no indexed property, so there is no candidate list and the seed is the bitmap walk itself. This is
	 * the case where stopping early is a change in complexity rather than a saving on reads: without a stopping scan,
	 * the walk visits every live slot in every segment whatever the query wanted.
	 * </p>
	 */
	@Test
	public void testAnUnindexedScanStopsAtTheLimit() throws Exception {
		List<List<Object>> rows = run("MATCH (n) RETURN n.name AS name LIMIT 5");

		assertEquals(5, rows.size());
		assertTrue(scanCallbacks.get() > 0, "this query must reach the scan, or it is testing the index path");
		assertTrue(scanCallbacks.get() < 50,
			"the scan itself must stop; it walked " + scanCallbacks.get() + " of " + NODES + " slots");
		store.close();
	}

	@Test
	public void testAnUnindexedScanWithoutALimitStillReadsEverything() throws Exception {
		// The control. Without it, a scan that stopped after five whatever the query asked for would pass the test
		// above and be badly wrong.
		List<List<Object>> rows = run("MATCH (n) RETURN n.name AS name");

		assertEquals(NODES, rows.size());
		assertTrue(scanCallbacks.get() >= NODES, "every live node must be visited when nothing limits the query");
		store.close();
	}

	@Test
	public void testSkipAndLimitTogetherStopAfterWhatTheyNeed() throws Exception {
		List<List<Object>> rows = run("MATCH (n:Person) RETURN n.n AS n SKIP 10 LIMIT 5");

		assertEquals(5, rows.size());
		assertTrue(nodesRead.get() < 100, "SKIP 10 LIMIT 5 must stop after about fifteen, not " + nodesRead.get());
		// And the rows are the right ones: skipped from the front, not sampled.
		assertEquals(10L, rows.get(0).get(0));
		assertEquals(14L, rows.get(4).get(0));
		store.close();
	}

	@Test
	public void testAWhereClauseDoesNotDefeatTheLimit() throws Exception {
		// The limit counts rows that survived the predicate, so the scan runs until five have — which is still far
		// short of the whole store when the predicate matches one node in ten.
		List<List<Object>> rows = run("MATCH (n:Person) WHERE n.bucket = 3 RETURN n.name AS name LIMIT 5");

		assertEquals(5, rows.size());
		assertTrue(nodesRead.get() < 500, "the scan must stop once five rows survived, not after " + nodesRead.get());
		store.close();
	}

	@Test
	public void testASinkThatStopsEarlyStopsTheScan() throws Exception {
		// The same mechanism from the other end: a client that has read enough is a false from RowSink.row, and it has
		// to reach the scan for the same reason a LIMIT does.
		Cypher.stream(counting, Cypher.parse("MATCH (n:Person) RETURN n.name AS name"), Map.of(), values -> false);

		assertTrue(nodesRead.get() < 50, "a sink that refused the first row must stop the scan, not " + nodesRead.get());
		store.close();
	}

	// ---------------------------------------------------------------- what must not stop early

	@Test
	public void testOrderByStillReadsEverything() throws Exception {
		List<List<Object>> rows = run("MATCH (n:Person) RETURN n.n AS n ORDER BY n DESC LIMIT 3");

		// The three largest, which is only correct if every row was read before the limit was applied.
		assertEquals(List.of(4999L, 4998L, 4997L), List.of(rows.get(0).get(0), rows.get(1).get(0), rows.get(2).get(0)));
		assertTrue(nodesRead.get() >= NODES, "ORDER BY has to see every row before it can name the largest three");
		store.close();
	}

	@Test
	public void testAggregationStillReadsEverything() throws Exception {
		List<List<Object>> rows = run("MATCH (n:Person) RETURN count(*) AS total");

		assertEquals((long) NODES, rows.get(0).get(0));
		assertTrue(nodesRead.get() >= NODES, "a count that stopped early would be a wrong answer, not a fast one");
		store.close();
	}

	@Test
	public void testDistinctStillReadsEverything() throws Exception {
		List<List<Object>> rows = run("MATCH (n:Person) RETURN DISTINCT n.bucket AS bucket LIMIT 2");

		assertEquals(2, rows.size());
		assertTrue(nodesRead.get() >= NODES,
			"DISTINCT cannot know a value is new without having seen the ones before it");
		store.close();
	}

	@Test
	public void testReturnStarStillReadsEverything() throws Exception {
		// RETURN * looks streamable and is not: the column set is the union of variable names over every row, and the
		// header has to go out before the first row.
		List<List<Object>> rows = run("MATCH (n:Person) RETURN * LIMIT 3");

		assertEquals(3, rows.size());
		assertTrue(nodesRead.get() >= NODES, "the star's columns are only known once every row has been seen");
		store.close();
	}

	@Test
	public void testAWriteQueryIsNotStreamed() throws Exception {
		// A write commits before it delivers a row, and its eager semantics come from holding each clause's whole
		// input. Streaming one would take both away.
		Collecting sink = new Collecting();
		Cypher.stream(counting, Cypher.parse("MATCH (n:Person) WHERE n.bucket = 0 SET n.seen = true RETURN n.n AS n"),
			Map.of(), sink);

		assertEquals(NODES / 10, sink.rows.size());
		assertTrue(nodesRead.get() >= NODES, "the match behind a write must not stop early");
		store.close();
	}

	// ---------------------------------------------------------------- equivalence

	/**
	 * The streaming and blocking paths must agree.
	 *
	 * <p>
	 * Every query here is run twice: once as written, and once with an {@code ORDER BY} that forces the blocking path.
	 * A difference between the two is the failure this whole change could plausibly introduce, and it would not throw.
	 * </p>
	 */
	@Test
	public void testTheTwoPathsAgree() throws Exception {
		for (String query : List.of(
			"MATCH (n:Person) WHERE n.n < 20 RETURN n.name AS name",
			"MATCH (n:Person) WHERE n.n < 20 RETURN n.n AS n SKIP 3",
			"MATCH (n:Person) WHERE n.n < 20 RETURN n.n AS n SKIP 3 LIMIT 4",
			"MATCH (n:Person) WHERE n.n < 20 RETURN n.n AS n LIMIT 0",
			"MATCH (n:Person) WHERE n.n < 20 RETURN n.n AS n SKIP 100",
			"MATCH (n:Person) WHERE n.n < 20 WITH n.n AS n WHERE n > 5 RETURN n",
			"MATCH (n:Person) WHERE n.n < 20 WITH n.n AS n LIMIT 4 RETURN n",
			// WHERE must be applied before SKIP and LIMIT, not after: three of the rows that passed, not however
			// many of the first three did. Getting this backwards is a wrong answer that throws nothing, and it is
			// what the first version of the push pipeline did.
			"MATCH (n:Person) WHERE n.n < 50 WITH n.n AS n LIMIT 3 WHERE n > 20 RETURN n",
			"MATCH (n:Person) WHERE n.n < 50 WITH n.n AS n SKIP 2 LIMIT 3 WHERE n > 20 RETURN n",
			"MATCH (n:Person) WHERE n.n < 5 UNWIND [1, 2] AS k RETURN n.n AS n, k",
			"MATCH (n:Person)-[r]->(m) RETURN n.n AS n LIMIT 3",
			"MATCH (n:Person) WHERE n.n < 3 RETURN n.n AS n, n.name AS name")) {

			List<List<Object>> streamed = run(query);
			// The blocking path, driven directly rather than through Cypher.stream — which would pick the streaming
			// one and compare it against itself.
			io.metaloom.graph.cypher.exec.QueryExecutor.Result blocking =
				new io.metaloom.graph.cypher.exec.QueryExecutor(
					new io.metaloom.graph.cypher.exec.GraphAccess(store), Map.of()).run(Cypher.parse(query));

			assertEquals(blocking.rows().size(), streamed.size(), "row count of: " + query);
			for (int r = 0; r < streamed.size(); r++) {
				assertEquals(blocking.rows().get(r), streamed.get(r), "row " + r + " of: " + query);
			}
		}
		store.close();
	}

	@Test
	public void testAFilterAppliesBeforeTheLimitCounts() throws Exception {
		// Stated on its own as well as in the loop above, because it is a specific rule rather than a general
		// agreement, and a failure named for itself says much more than one buried in a list of ten queries.
		// The WHERE is written after the LIMIT — that is this grammar's clause order — but it is *applied* before it,
		// which is what the list path has always done. spec/CYPHER.md section 7.1 records the deviation.
		List<List<Object>> rows = run("MATCH (n:Person) WITH n.n AS n LIMIT 3 WHERE n > 100 RETURN n");

		assertEquals(3, rows.size(), "three rows that passed the filter, not what survived of the first three");
		assertEquals(101L, rows.get(0).get(0));
		assertEquals(103L, rows.get(2).get(0));
		store.close();
	}

	@Test
	public void testAStreamedQueryStillSeesOneConsistentState() throws Exception {
		// The read view is opened around the whole pipeline, not per batch, so a writer committing underneath a
		// half-drained stream cannot change what the rest of it sees.
		assertFalse(store.hasReadTransaction());
		Collecting sink = new Collecting();
		Cypher.stream(store, Cypher.parse("MATCH (n:Person) RETURN n.n AS n LIMIT 10"), Map.of(), sink);
		assertEquals(10, sink.rows.size());
		assertEquals(0, store.snapshotStats().openViews(), "and the view is closed on the way out");
		store.close();
	}
}
