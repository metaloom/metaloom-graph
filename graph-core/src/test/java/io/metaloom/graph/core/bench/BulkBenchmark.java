package io.metaloom.graph.core.bench;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Smoke benchmarks, excluded from the default build. Run with {@code mvn test -Pbench}.
 * <p>
 * These replace the unbounded bulk loops that used to sit in the unit suite. They are timing loops rather than JMH
 * harnesses; the full JMH suite with regression gates is a Phase 9 deliverable (spec/TESTING.md section 7).
 */
@Tag("bench")
public class BulkBenchmark {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void benchDurableCommitLatency() throws Exception {
		// The cost of durability itself: one fsync per commit is the floor for FSYNC_ON_COMMIT.
		int commits = 300;
		try (GraphStore store = stores.open(bigStore())) {
			long start = System.nanoTime();
			for (int i = 0; i < commits; i++) {
				store.createNode("Person", Properties.of().set("i", i));
			}
			report("createNode, one durable commit each", commits, start);
		}
		try (GraphStore store = stores.open(io.metaloom.graph.core.api.GraphStoreOptions.builder()
			.walSyncMode(io.metaloom.graph.core.api.WalSyncMode.GROUP_COMMIT)
			.walSyncIntervalMillis(20)
			.build())) {
			long start = System.nanoTime();
			for (int i = 0; i < commits * 20; i++) {
				store.createNode("Person", Properties.of().set("i", i));
			}
			report("createNode, group commit 20ms", commits * 20, start);
		}
	}

	@Test
	public void benchNodeCreateAndRead() throws Exception {
		int count = 200_000;
		try (GraphStore store = stores.open(bigStore())) {
			long[] ids = new long[count];
			long start = System.nanoTime();
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					ids[i] = store.createNode("Person", Properties.of().set("name", "Wes Anderson").set("i", i));
				}
				txn.commit();
			}
			report("createNode (one transaction)", count, start);

			start = System.nanoTime();
			for (int i = 0; i < count; i++) {
				store.nodeLabel(ids[i]);
			}
			report("nodeLabel (by id, no index probe)", count, start);

			start = System.nanoTime();
			for (int i = 0; i < count; i++) {
				store.nodeProperties(ids[i]);
			}
			report("nodeProperties", count, start);

			start = System.nanoTime();
			store.checkpoint();
			System.out.printf("checkpoint: %d ms%n", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
			System.out.println("stats: " + store.stats());
		}
	}

	@Test
	public void benchEdgeCreateAndTraverse() throws Exception {
		int nodes = 100_000;
		try (GraphStore store = stores.open(bigStore())) {
			long[] ids = new long[nodes];
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < nodes; i++) {
					ids[i] = store.createNode("N");
				}
				txn.commit();
			}
			long start = System.nanoTime();
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 1; i < nodes; i++) {
					store.createEdge(ids[i - 1], "NEXT", ids[i]);
				}
				txn.commit();
			}
			report("createEdge (lightweight)", nodes - 1, start);

			start = System.nanoTime();
			long seen = 0;
			for (int i = 0; i < nodes; i++) {
				seen += store.neighbours(ids[i], Direction.OUT).size();
			}
			report("neighbours (degree 1, inline)", nodes, start);
			System.out.println("edges seen: " + seen);
		}
	}

	@Test
	public void benchSupernodeTraversal() throws Exception {
		int degree = 500_000;
		try (GraphStore store = stores.open(bigStore())) {
			long hub;
			long[] others = new long[degree];
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < degree; i++) {
					others[i] = store.createNode("N");
				}
				txn.commit();
			}
			long start = System.nanoTime();
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < degree; i++) {
					store.createEdge(hub, "T", others[i]);
				}
				txn.commit();
			}
			report("createEdge into a supernode", degree, start);

			start = System.nanoTime();
			int size = store.neighbours(hub, Direction.OUT).size();
			long elapsed = System.nanoTime() - start;
			System.out.printf("supernode scan: %d neighbours in %d ms, %d blocks%n",
				size, TimeUnit.NANOSECONDS.toMillis(elapsed), store.stats().adjacencyCellCount());
			System.out.println("stats: " + store.stats());
		}
	}

	@Test
	public void benchIndexedLookup() throws Exception {
		int count = 100_000;
		try (GraphStore store = stores.open(bigStore())) {
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					store.createNode(i % 2 == 0 ? "Even" : "Odd", Properties.of().set("i", i));
				}
				txn.commit();
			}
			long start = System.nanoTime();
			int found = 0;
			for (int i = 0; i < 10_000; i++) {
				found += store.nodesByProperty("i", i).size();
			}
			report("nodesByProperty (B+tree)", 10_000, start);
			System.out.println("matched: " + found);

			start = System.nanoTime();
			int labelled = store.nodesByLabel("Even").size();
			System.out.printf("nodesByLabel scan: %d ids in %d ms%n", labelled,
				TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
		}
	}

	@Test
	public void benchCompaction() throws Exception {
		// The exit criterion for Phase 8, measured: a store churned to a tenth of its slots comes back dense.
		int count = 200_000;
		try (GraphStore store = stores.open(bigStore())) {
			java.util.List<Long> ids = new java.util.ArrayList<>(count);
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					ids.add(store.createNode(i % 10 == 0 ? "Keep" : "Drop", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					if (i % 10 != 0) {
						store.deleteNode(ids.get(i));
					}
				}
				txn.commit();
			}
			store.checkpoint();

			long start = System.nanoTime();
			io.metaloom.graph.core.api.CompactionReport report =
				store.compact(io.metaloom.graph.core.api.CompactionOptions.always());
			report("compact, records relocated", (int) report.totalRelocated(), start);
			System.out.println("  " + report);
			System.out.printf("  storage %,d -> %,d bytes (%.1f%% reclaimed), liveness %.3f -> %.3f%n",
				report.storageBytesBefore(), report.storageBytesAfter(),
				100.0 * report.storageBytesReclaimed() / report.storageBytesBefore(),
				report.livenessBefore(), report.livenessAfter());

			start = System.nanoTime();
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
			System.out.printf("%-40s %8d live in %5d ms%n", "verify(FULL) after compaction", count / 10,
				TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
		}
	}

	@Test
	public void benchDeleteNodeWithMaterialisedEdges() throws Exception {
		// The cascade deletes every relationship of a hub. Each one used to rewrite the hub's own block, which is freed a
		// moment later anyway — quadratic in the hub's degree.
		int hubs = 20;
		int degree = 2_000;
		try (GraphStore store = stores.open(bigStore())) {
			long[] hubIds = new long[hubs];
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int h = 0; h < hubs; h++) {
					hubIds[h] = store.createNode("Hub");
					for (int i = 0; i < degree; i++) {
						store.createRelationship(hubIds[h], "R", store.createNode("Leaf"), Properties.of().set("i", i));
					}
				}
				txn.commit();
			}
			long start = System.nanoTime();
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (long hub : hubIds) {
					store.deleteNode(hub);
				}
				txn.commit();
			}
			report("deleteNode, " + degree + " materialised edges", hubs, start);
		}
	}

	private static io.metaloom.graph.core.api.GraphStoreOptions bigStore() {
		return io.metaloom.graph.core.api.GraphStoreOptions.defaults();
	}

	/**
	 * Refilling a full segment after a reopen. The free ring is empty after a reopen and the bump cursor is exhausted,
	 * so every allocation falls back to the bitmap. That scan used to start at slot 0 every time, which made refilling
	 * the holes quadratic in their number.
	 */
	@Test
	public void benchRefillAFullSegmentAfterReopen() throws Exception {
		int slots;
		int holes = 0;
		try (GraphStore store = stores.open()) {
			io.metaloom.graph.core.store.GraphStoreImpl impl = (io.metaloom.graph.core.store.GraphStoreImpl) store;
			slots = impl.geometry().slotsPerSegment(io.metaloom.graph.core.id.Kind.NODE);
			long[] ids = new long[slots];
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < slots; i++) {
					ids[i] = store.createNode("N");
				}
				txn.commit();
			}
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < slots; i += 8) {
					store.deleteNode(ids[i]);
					holes++;
				}
				txn.commit();
			}
		}
		try (GraphStore store = stores.open()) {
			io.metaloom.graph.core.store.GraphStoreImpl impl = (io.metaloom.graph.core.store.GraphStoreImpl) store;
			long start = System.nanoTime();
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < holes; i++) {
					store.createNode("Refill");
				}
				txn.commit();
			}
			report("createNode into the holes of a full segment", holes, start);
			if (impl.segments(io.metaloom.graph.core.id.Kind.NODE).segments().size() != 1) {
				throw new AssertionError("every hole must be reused before a segment is added");
			}
		}
	}

	/**
	 * Breadth-first traversal over a random graph, warm. Run twice — with and without
	 * {@code -Dmetaloom.graph.prefetch.traversal=true} in the fork's argLine — to see what advising the frontier costs.
	 */
	@Test
	public void benchTraverseRandomGraph() throws Exception {
		int count = 100_000;
		try (GraphStore store = stores.open(bigStore())) {
			long[] ids = new long[count];
			java.util.Random random = new java.util.Random(20261024L);
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					ids[i] = store.createNode("N");
				}
				for (int i = 0; i < count * 4; i++) {
					store.createEdge(ids[random.nextInt(count)], "E", ids[random.nextInt(count)]);
				}
				txn.commit();
			}
			for (int round = 0; round < 2; round++) {
				long edges = 0;
				long start = System.nanoTime();
				for (int s = 0; s < 200; s++) {
					edges += store.traverse(ids[s * 7], Direction.OUT, null, 4).size();
				}
				long elapsed = System.nanoTime() - start;
				System.out.printf("traverse depth 4 (prefetch %s)       %,10d edges, %6.1f ns per edge%n",
					Boolean.getBoolean("metaloom.graph.prefetch.traversal") ? "on " : "off", edges,
					(double) elapsed / edges);
			}
		}
	}

	private static void report(String what, int count, long startNanos) {
		long elapsed = System.nanoTime() - startNanos;
		double perSecond = count / (elapsed / 1_000_000_000.0);
		System.out.printf("%-40s %8d ops in %5d ms = %,12.0f ops/s%n",
			what, count, TimeUnit.NANOSECONDS.toMillis(elapsed), perSecond);
	}
}
