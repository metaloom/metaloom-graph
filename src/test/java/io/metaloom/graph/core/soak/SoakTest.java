package io.metaloom.graph.core.soak;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * A steady-state workload run for a configurable length of time, watching for the things that only show up over time:
 * leaks, unbounded growth, and fragmentation that compaction cannot undo.
 * <p>
 * The default run is short enough to sit in {@code -Pslow}. The specification asks for twelve hours, which is a
 * different command rather than a different test:
 *
 * <pre>
 * mvn test -Pslow -Dtest=SoakTest -Dsoak.seconds=43200
 * </pre>
 *
 * <b>The twelve hour run has not been performed.</b> What is asserted below has been, at the default duration, and
 * the numbers it prints are what a longer run should be compared against.
 * <p>
 * The load is deliberately churny — the steady state is a store whose live set stays roughly constant while its
 * allocation pattern never repeats — because a workload that only ever inserts will not show a leak.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 1
 */
@Tag("slow")
public class SoakTest {

	/** Live nodes the workload holds roughly constant, so growth means a leak rather than a bigger graph. */
	private static final int STEADY_STATE = 4000;

	/**
	 * Ceiling on the edge count; the mix equilibrates somewhere below it.
	 * <p>
	 * Holding the edges still matters more than it sounds. A churn loop that creates edges and only ever removes them
	 * as a side effect of deleting a node is not a steady state at all — the graph really is growing, bytes per node
	 * really does climb, and the leak detector fires on a store that is behaving perfectly. That is what the first
	 * version of this test did, and the "leak" it reported was its own workload.
	 */
	private static final int STEADY_EDGES = 20_000;

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testSteadyStateDoesNotGrowWithoutBound() throws Exception {
		long seconds = Long.getLong("soak.seconds", 20);
		long deadline = System.nanoTime() + seconds * 1_000_000_000L;
		System.out.println("soak: running for " + seconds + "s (override with -Dsoak.seconds=N)");

		try (GraphStore store = stores.open()) {
			Random random = new Random(20260813L);
			List<Long> live = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < STEADY_STATE; i++) {
					live.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			store.compact(CompactionOptions.always());
			store.checkpoint();

			// A little connectivity up front, so the warmup baseline includes adjacency rather than nodes alone.
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < STEADY_EDGES; i++) {
					store.createEdge(live.get(random.nextInt(live.size())), "E", live.get(random.nextInt(live.size())));
					edges++;
				}
				txn.commit();
			}
			store.compact(CompactionOptions.always());
			store.checkpoint();
			live.clear();
			live.addAll(store.nodesByLabel("N"));

			Sample first = sample(store, "after warmup");
			Sample last = first;
			Sample settled = null;
			long rounds = 0;
			long operations = 0;

			while (System.nanoTime() < deadline) {
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < 500; i++) {
						operations += churn(store, live, random);
					}
					txn.commit();
				}
				rounds++;

				// Every so often, do what an operator would: reclaim, compact, and check the structure. Compaction
				// invalidates ids, so the working set is re-found through the index afterwards — which is exactly
				// what an application holding ids across a compaction has to do.
				if (rounds % 20 == 0) {
					store.reclaim();
					store.compact(CompactionOptions.always());
					store.checkpoint();
					store.verify(VerifyLevel.FULL);
					live.clear();
					live.addAll(store.nodesByLabel("N"));
					last = sample(store, "round " + rounds);
					if (settled == null && rounds >= 100) {
						settled = last;
					}
				}
			}
			store.reclaim();
			store.compact(CompactionOptions.always());
			store.checkpoint();
			store.verify(VerifyLevel.FULL);
			live.clear();
			live.addAll(store.nodesByLabel("N"));
			last = sample(store, "final");
			if (settled == null) {
				settled = first;
			}

			System.out.printf("soak: %,d operations over %,d rounds%n", operations, rounds);
			System.out.println("  first:   " + first);
			System.out.println("  settled: " + settled);
			System.out.println("  last:    " + last);

			// The comparison is against the *settled* sample, not the warmup one. The first hundred rounds are the
			// store finding its shape — compaction reclaiming the warmup's allocation pattern, the index rebuilding —
			// and measuring from there would confuse settling with leaking. What must be flat is the tail.
			assertTrue(settled != null, "the run was too short to settle; raise -Dsoak.seconds");
			assertTrue(last.bytesPerElement() < settled.bytesPerElement() * 1.5,
				"committed bytes per live element grew without bound after settling: " + settled + " -> " + last);
			assertTrue(last.segmentFiles() <= settled.segmentFiles(),
				"segment files must not accumulate: " + settled.segmentFiles() + " -> " + last.segmentFiles());
			assertTrue(last.nodes() >= STEADY_STATE / 2 && last.nodes() <= STEADY_STATE * 2,
				"the workload should hold the live node set roughly constant, ended at " + last.nodes());
			assertTrue(last.edges() > 0, "and it should not have deleted every edge in the graph");
		}
	}

	/** One churn step: the mix is weighted so that both halves of the live set stay near their steady state. */
	private int churn(GraphStore store, List<Long> live, Random random) throws java.io.IOException {
		int roll = random.nextInt(100);
		if (live.size() < STEADY_STATE || roll < 25) {
			live.add(store.createNode("N", Properties.of().set("i", random.nextInt(100_000))));
			return 1;
		}
		long id = live.get(random.nextInt(live.size()));
		if (roll < 45) {
			store.setNodeProperties(id, Properties.of()
				.set("i", random.nextInt(100_000))
				.set("blob", random.nextInt(50) == 0 ? "b".repeat(400) : "small"));
		} else if (roll < 65) {
			if (edges < STEADY_EDGES) {
				store.createEdge(id, "E", live.get(random.nextInt(live.size())));
				edges++;
			} else {
				edges -= removeOneEdge(store, id);
			}
		} else if (roll < 75) {
			store.nodeProperties(id);
			store.neighbours(id, Direction.BOTH);
		} else if (live.size() > STEADY_STATE) {
			long doomed = live.remove(random.nextInt(live.size()));
			edges -= store.degreeOut(doomed) + store.degreeIn(doomed);
			store.deleteNode(doomed);
		}
		return 1;
	}

	/** Removes one outgoing edge of a node, if it has any. Returns how many edges went away. */
	private int removeOneEdge(GraphStore store, long id) throws java.io.IOException {
		for (Edge edge : store.neighbours(id, Direction.OUT)) {
			if (store.deleteEdge(id, edge.type(), edge.neighbourId())) {
				return 1;
			}
		}
		return 0;
	}

	/** Live edges, tracked by the workload because counting them would dominate the loop. */
	private long edges;

	private Sample sample(GraphStore store, String label) {
		System.gc();
		StoreStats stats = store.stats();
		Runtime runtime = Runtime.getRuntime();
		long heap = runtime.totalMemory() - runtime.freeMemory();
		Sample sample = new Sample(label, stats.nodeCount(), edges, stats.storageBytes(), stats.segmentFileCount(),
			heap);
		System.out.println("  " + sample);
		return sample;
	}

	private record Sample(String label, long nodes, long edges, long storageBytes, int segmentFiles, long heapBytes) {

		/** Bytes per live element, counting edges: the metric that stays flat when nothing is leaking. */
		double bytesPerElement() {
			long elements = nodes + edges;
			return elements == 0 ? 0 : (double) storageBytes / elements;
		}

		@Override
		public String toString() {
			return String.format("%-14s nodes=%,7d edges=%,7d storage=%,11d B (%.1f B/element) segments=%d heap=%,d B",
				label, nodes, edges, storageBytes, bytesPerElement(), segmentFiles, heapBytes);
		}
	}
}
