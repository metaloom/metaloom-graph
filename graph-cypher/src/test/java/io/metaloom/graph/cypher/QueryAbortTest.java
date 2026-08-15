package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Stopping a query that has already started.
 *
 * <h2>The distinction this is here to prove</h2>
 *
 * <p>
 * {@link RowSink#row} returning {@code false} stops delivery, and that is all it can do. A scan of every node reaches
 * the sink <b>once, at the end</b>, because the executor produces its whole result before the first row is pushed — so
 * a server whose only stopping mechanism is the sink would let an abandoned query run to completion, holding a worker
 * thread and a read view the entire time, and would only then discover nobody wanted the answer. That is precisely the
 * failure {@code QUERY_TIMEOUT_MILLIS} was declared to prevent and, before {@link QueryAbort}, could not.
 * </p>
 *
 * <p>
 * The tests below therefore care about <em>where</em> the query stops, not just that it does: the scan must be
 * interrupted part way through, with rows still unread, and the read view must be closed on the way out.
 * </p>
 */
@Tag("unit")
public class QueryAbortTest {

	/** Comfortably more than {@code GraphAccess.ABORT_CHECK_INTERVAL}, so a scan crosses it many times over. */
	private static final int NODES = 20_000;

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;

	@BeforeEach
	public void seed() throws Exception {
		store = stores.open();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < NODES; i++) {
				store.createNode(List.of("Person"), Properties.of().set("name", "p" + i).set("n", (long) i));
			}
			txn.commit();
		}
	}

	@AfterEach
	public void close() throws Exception {
		assertEquals(0, store.snapshotStats().openViews(), "an aborted query must not leave a read view open");
		store.close();
	}

	private static final class Collecting implements RowSink {

		final List<List<Object>> rows = new ArrayList<>();

		@Override
		public boolean row(List<Object> values) {
			rows.add(values);
			return true;
		}
	}

	// ---------------------------------------------------------------- stopping execution

	@Test
	public void testAnAbortStopsTheScanBeforeItFinishes() throws Exception {
		// Counts how many times the signal was consulted, and says stop on the second. If the abort were only
		// checked between rows this would never fire at all, because a full scan produces no row until it is done.
		AtomicInteger consulted = new AtomicInteger();
		QueryAbort abort = () -> consulted.incrementAndGet() >= 2 ? "stopped on purpose" : null;

		Collecting sink = new Collecting();
		QueryAbortedException thrown = assertThrows(QueryAbortedException.class,
			() -> Cypher.stream(store, Cypher.parse("MATCH (n) RETURN n"), Map.of(), sink, abort));

		assertEquals("stopped on purpose", thrown.getMessage(), "the reason travels verbatim to whoever reports it");
		assertTrue(sink.rows.isEmpty(), "the scan must stop during execution, before a single row was delivered");
		assertTrue(consulted.get() >= 2, "the signal has to be consulted while the scan runs, not once around it");
	}

	@Test
	public void testAnExpiredDeadlineStopsAScan() throws Exception {
		QueryAbort abort = QueryAbort.deadline(System.nanoTime() - 1, 30_000);

		QueryAbortedException thrown = assertThrows(QueryAbortedException.class,
			() -> Cypher.stream(store, Cypher.parse("MATCH (n) RETURN n.name"), Map.of(), new Collecting(), abort));

		assertTrue(thrown.getMessage().contains("30000 ms"), "the message names the budget: " + thrown.getMessage());
	}

	@Test
	public void testADeadlineThatHasNotPassedDoesNotInterfere() throws Exception {
		QueryAbort abort = QueryAbort.deadline(System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5),
			300_000);

		Collecting sink = new Collecting();
		Cypher.stream(store, Cypher.parse("MATCH (n) RETURN n.name"), Map.of(), sink, abort);

		assertEquals(NODES, sink.rows.size(), "a live deadline must not truncate a result");
	}

	@Test
	public void testNeverIsTheDefaultAndCostsNothing() throws Exception {
		Collecting sink = new Collecting();
		// The four-argument form, which is what every existing caller uses.
		Cypher.stream(store, "MATCH (n) RETURN n.name", Map.of(), sink);
		assertEquals(NODES, sink.rows.size());
	}

	// ---------------------------------------------------------------- what it leaves behind

	@Test
	public void testAnAbortIsDistinguishableFromEveryOtherFailure() throws Exception {
		// A caller mapping this to a client has to be able to tell "abandoned" from "your query was wrong" and from
		// "the store is unhappy", because the three deserve different answers and different operator reactions.
		QueryAbortedException aborted = assertThrows(QueryAbortedException.class,
			() -> Cypher.stream(store, Cypher.parse("MATCH (n) RETURN n"), Map.of(), new Collecting(),
				() -> "abandoned"));
		assertEquals(QueryAbortedException.class, aborted.getClass());

		assertThrows(io.metaloom.graph.cypher.parse.CypherSyntaxException.class,
			() -> Cypher.parse("MATCH (n) RETURN"));
	}

	@Test
	public void testAbortingRepeatedlyLeavesTheStoreUsable() throws Exception {
		for (int i = 0; i < 20; i++) {
			assertThrows(QueryAbortedException.class,
				() -> Cypher.stream(store, Cypher.parse("MATCH (n) RETURN n"), Map.of(), new Collecting(),
					() -> "stop"));
		}
		// Twenty abandoned scans must leave no snapshot behind, or a server under a flapping client accumulates
		// retention until the store starts abandoning views itself.
		assertEquals(0, store.snapshotStats().openViews());

		Collecting sink = new Collecting();
		Cypher.stream(store, "MATCH (n) RETURN n.name LIMIT 5", Map.of(), sink);
		assertEquals(5, sink.rows.size(), "the store still answers after twenty aborts");
	}
}
