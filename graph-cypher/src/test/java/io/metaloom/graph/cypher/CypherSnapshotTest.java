package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * How a query interacts with the engine's read views.
 *
 * <p>
 * A query is not one read. It scans, then expands from what it found, then reads properties off the far end — dozens
 * of storage calls that a writer can commit between. Without a view, a query could count a node in its scan and find
 * it deleted by the time it expanded from it, which is not a result any user asked for. So every query opens a
 * {@code SNAPSHOT} view for its duration.
 * </p>
 */
@Tag("unit")
public class CypherSnapshotTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testAQueryClosesTheViewItOpened() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("N", Properties.of().set("i", 1L));

			assertEquals(0, store.snapshotStats().openViews());
			assertEquals(1, Cypher.query(store, "MATCH (n:N) RETURN n").size());
			assertEquals(0, store.snapshotStats().openViews(),
				"a leaked view would hold every version committed after it");
			assertFalse(store.hasReadTransaction());
		}
	}

	@Test
	public void testAQueryJoinsTheCallersViewRatherThanNesting() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("N", Properties.of().set("i", 1L));

			try (ReadTransaction view = store.beginRead()) {
				// Views refuse to nest, so a query that opened its own unconditionally would fail outright here.
				assertEquals(1L, Cypher.query(store, "MATCH (n:N) RETURN n.i").row(0).longValue("n.i"));

				store.setNodeProperties(node, Properties.of().set("i", 2L));

				assertEquals(1L, Cypher.query(store, "MATCH (n:N) RETURN n.i").row(0).longValue("n.i"),
					"joining the caller's view means the query sees the caller's instant, not the latest commit");
				assertEquals(1, store.snapshotStats().openViews(), "and no second view was opened");
			}

			assertEquals(2L, Cypher.query(store, "MATCH (n:N) RETURN n.i").row(0).longValue("n.i"),
				"outside the view, a fresh query sees the update");
		}
	}

	/**
	 * The property a view buys: a query is atomic with respect to a writer.
	 *
	 * <p>
	 * The query below scans every node and expands from each. A writer commits continuously underneath. Every run must
	 * see a graph that actually existed at one instant — specifically, node count and edge count that agree with each
	 * other, rather than a scan from before a commit joined to an expansion from after it.
	 * </p>
	 */
	@Test
	public void testAQueryIsAtomicAgainstAConcurrentWriter() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub = store.createNode("Hub");
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 20; i++) {
					store.createEdge(hub, "LINK", store.createNode("Leaf", Properties.of().set("i", (long) i)));
				}
				txn.commit();
			}

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failed = new AtomicReference<>();
			AtomicLong committed = new AtomicLong();
			Thread writer = new Thread(() -> {
				try {
					for (int i = 20; !stop.get(); i++) {
						store.createEdge(hub, "LINK", store.createNode("Leaf", Properties.of().set("i", (long) i)));
						committed.incrementAndGet();
					}
				} catch (Throwable t) {
					failed.compareAndSet(null, t);
				}
			});
			writer.start();
			while (committed.get() < 5 && failed.get() == null) {
				Thread.onSpinWait();
			}

			for (int round = 0; round < 40; round++) {
				CypherResult result = Cypher.query(store,
					"MATCH (h:Hub)-[:LINK]->(l:Leaf) RETURN count(*) AS edges, count(l.i) AS leaves");
				long edges = result.row(0).longValue("edges");
				long leaves = result.row(0).longValue("leaves");
				assertEquals(edges, leaves,
					"every edge must reach a leaf that carries an i; a mismatch means the query saw an edge "
						+ "committed after its scan, or a node deleted before its read");
				assertTrue(edges >= 20, "the graph only grows, so no run may see fewer than the initial 20");
			}

			stop.set(true);
			writer.join();
			assertNull(failed.get(), String.valueOf(failed.get()));
			assertEquals(0, store.snapshotStats().openViews());
		}
	}

	@Test
	public void testTwoQueriesInOneViewSeeTheSameGraph() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("N", Properties.of().set("i", 1L));

			try (ReadTransaction view = store.beginRead()) {
				int before = Cypher.query(store, "MATCH (n:N) RETURN n").size();
				store.createNode("N", Properties.of().set("i", 2L));
				int after = Cypher.query(store, "MATCH (n:N) RETURN n").size();

				assertEquals(before, after,
					"a caller who wants several queries over one consistent state opens a view and gets it");
			}
			assertEquals(2, Cypher.query(store, "MATCH (n:N) RETURN n").size());
		}
	}
}
