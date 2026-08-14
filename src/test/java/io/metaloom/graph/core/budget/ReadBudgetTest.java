package io.metaloom.graph.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * Reads per operation, enforced as build failures.
 * <p>
 * These are the tests that keep the project honest about its central claim. "Reading an element by id costs zero index
 * probes and zero extra seeks" is a sentence in a README until something asserts that reading a node touches exactly
 * one node record and <b>zero</b> index, adjacency, property and relationship records. A change that adds a lookup to
 * the read path passes every functional test in the suite and fails here, which is the entire point.
 * <p>
 * <b>What the counter measures.</b> One record-slice acquisition, per kind. That is not the same as one device read:
 * several records of the same page cost one device read, and a warm page costs none at all. So the budgets below are
 * an upper bound on device reads and an exact measure of how much structure an operation has to walk. The
 * zero-valued ones carry the design claim and are exact either way; measuring true device reads needs cold-cache
 * instrumentation and the direct-I/O engine, which is Phase 6 work that does not exist.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
@Tag("unit")
public class ReadBudgetTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** Costs one operation, having warmed everything it touches so no first-touch allocation is counted. */
	private IoStats cost(GraphStoreImpl store, Runnable operation) {
		operation.run();
		IoStats before = store.counters().snapshot();
		operation.run();
		return store.counters().snapshot().since(before);
	}

	// ---------------------------------------------------------------- the addressing claim

	@Test
	public void testReadingANodeByIdTouchesNothingButTheNode() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long id;
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 5000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				id = store.createNode("Target", Properties.of().set("i", -1));
				txn.commit();
			}

			IoStats label = cost(impl, () -> store.nodeLabel(id));
			assertEquals(1, label.recordReads(Kind.NODE), "a label read is one node record: " + label);
			assertEquals(0, label.recordReads(Kind.INDEX), "no index probe: " + label);
			assertEquals(0, label.recordReads(Kind.ADJ), "no adjacency read: " + label);
			assertEquals(0, label.recordReads(Kind.PROP), "no property read: " + label);
			assertEquals(0, label.recordReads(Kind.REL), "no relationship read: " + label);
			assertEquals(0, label.recordWrites(Kind.NODE), "a read must not dirty a page: " + label);

			IoStats exists = cost(impl, () -> store.nodeExists(id));
			assertEquals(1, exists.recordReads(Kind.NODE), "an existence check is one node record: " + exists);
			assertEquals(0, exists.totalRecordReads() - 1, "and nothing else: " + exists);

			IoStats properties = cost(impl, () -> store.nodeProperties(id));
			assertEquals(1, properties.recordReads(Kind.NODE), "one node record: " + properties);
			assertEquals(0, properties.recordReads(Kind.INDEX), "no index probe: " + properties);
			assertEquals(1, properties.recordReads(Kind.PROP),
				"a property block is one contiguous run and costs exactly one touch: " + properties);
		}
	}

	@Test
	public void testTheBudgetDoesNotGrowWithTheStore() throws Exception {
		// The point of arithmetic addressing: cost is independent of how much is in the store.
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long small;
			try (Transaction txn = store.begin()) {
				small = store.createNode("N", Properties.of().set("i", 0));
				for (int i = 1; i < 100; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}
			long atHundred = cost(impl, () -> store.nodeLabel(small)).totalRecordReads();

			try (Transaction txn = store.begin()) {
				for (int i = 100; i < 100_000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}
			long atHundredThousand = cost(impl, () -> store.nodeLabel(small)).totalRecordReads();

			assertEquals(atHundred, atHundredThousand,
				"a thousandfold larger store must not make a read by id cost more");
		}
	}

	// ---------------------------------------------------------------- adjacency tiers

	@Test
	public void testLowDegreeNeighboursCostNoAdjacencyReads() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long a;
			try (Transaction txn = store.begin()) {
				a = store.createNode("A");
				store.createEdge(a, "E", store.createNode("B"));
				store.createEdge(a, "E", store.createNode("C"));
				txn.commit();
			}
			assertEquals(2, store.neighbours(a, Direction.OUT).size());

			IoStats stats = cost(impl, () -> store.neighbours(a, Direction.OUT));
			assertEquals(0, stats.recordReads(Kind.ADJ),
				"both edges are inline in the node record, so adjacency must not be touched at all: " + stats);
			assertEquals(0, stats.recordReads(Kind.INDEX), "and no index probe: " + stats);
		}
	}

	@Test
	public void testAFourThousandDegreeNodeCostsOneAdjacencyRead() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 4000; i++) {
					store.createEdge(hub, "E", store.createNode("Spoke"));
				}
				txn.commit();
			}
			assertEquals(4000, store.neighbours(hub, Direction.OUT).size());

			IoStats stats = cost(impl, () -> store.neighbours(hub, Direction.OUT));
			assertEquals(1, stats.recordReads(Kind.ADJ),
				"4000 edges fit one 8 KiB block, so enumerating them is one block read: " + stats);
		}
	}

	@Test
	public void testSupernodeScanCostIsBoundedByBlockCount() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 100_000; i++) {
					store.createEdge(hub, "E", store.createNode("Spoke"));
				}
				txn.commit();
			}
			int blocks = impl.adjacencyStore().blockCount(hub);
			IoStats stats = cost(impl, () -> store.neighbours(hub, Direction.OUT));

			// Each block is read once to decode it and once to follow the chain pointer; the budget is that the scan
			// is linear in blocks, not in edges. One read per edge is what the proof of concept did.
			assertTrue(stats.recordReads(Kind.ADJ) <= 2L * blocks + 2,
				"a 100,000 edge scan must cost O(blocks), not O(edges): " + blocks + " blocks, " + stats);
			assertTrue(stats.recordReads(Kind.ADJ) < 100_000 / 100,
				"and must be at least two orders of magnitude below one read per edge: " + stats);
		}
	}

	// ---------------------------------------------------------------- indexed lookup

	@Test
	public void testIndexedLookupIsLogarithmic() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 50_000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}
			int depth = impl.indexCatalog().tree().depth(impl.indexCatalog().propertyRootForTest());
			IoStats stats = cost(impl, () -> store.nodesByProperty("i", 12345));

			// Descend the tree, read the leaf, then verify the one candidate against its stored property.
			assertTrue(stats.recordReads(Kind.INDEX) <= 2L * depth + 2,
				"a point lookup over 50,000 nodes must cost about the tree depth (" + depth + "): " + stats);
			// Two, not one: the candidate is checked for liveness and then read for its property, and each resolves
			// the record separately. It is the same page both times, and the cost is bounded by the number of
			// candidates rather than by the size of the store, which is what the budget is really about.
			assertEquals(2, stats.recordReads(Kind.NODE),
				"verifying one candidate must cost a bounded number of node reads: " + stats);
		}
	}

	// ---------------------------------------------------------------- writes

	@Test
	public void testCreatingANodeHasABoundedWriteFootprint() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 1000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}

			IoStats before = impl.counters().snapshot();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 1000; i++) {
					store.createNode("N", Properties.of().set("i", 1_000_000 + i));
				}
				txn.commit();
			}
			IoStats stats = impl.counters().snapshot().since(before);

			assertTrue(stats.recordWrites(Kind.NODE) <= 4000,
				"a node create should be a handful of record touches, not more: " + stats);
			assertEquals(0, stats.recordWrites(Kind.ADJ), "a node with no edges must not touch adjacency: " + stats);
			assertEquals(0, stats.recordWrites(Kind.BLOB), "small properties must not escape to a blob: " + stats);
			assertEquals(1, stats.walSyncs(), "one transaction is one fsync: " + stats);
		}
	}

	@Test
	public void testEdgeCreationTouchesBothEndpointsAndNothingElse() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					ids.add(store.createNode("N"));
				}
				txn.commit();
			}

			IoStats before = impl.counters().snapshot();
			try (Transaction txn = store.begin()) {
				for (int i = 1; i < ids.size(); i++) {
					store.createEdge(ids.get(i - 1), "E", ids.get(i));
				}
				txn.commit();
			}
			IoStats stats = impl.counters().snapshot().since(before);

			assertEquals(0, stats.recordWrites(Kind.REL),
				"a property-free edge must not materialise a relationship record: " + stats);
			assertEquals(0, stats.recordWrites(Kind.ADJ),
				"and at degree two it must not allocate an adjacency block: " + stats);
			assertEquals(0, stats.recordWrites(Kind.PROP), "nor a property block: " + stats);
		}
	}

	@Test
	public void testCountersAreVisibleThroughTheMetricsApi() throws IOException {
		try (GraphStore store = stores.open()) {
			store.createNode("N", Properties.of().set("i", 1));
			store.checkpoint();
			var metrics = store.metrics();
			assertTrue(metrics.io().walBytesAppended() > 0, "the log must have recorded the write: " + metrics);
			assertTrue(metrics.io().checkpoints() >= 1, "the checkpoint must be counted: " + metrics);
			assertTrue(metrics.averageCheckpointMillis() >= 0, metrics.toString());
			assertEquals(0, metrics.walLagBytes(), "FSYNC_ON_COMMIT leaves nothing unsynced: " + metrics);
			assertTrue(metrics.toString().contains("StoreMetrics"));
		}
	}
}
