package io.metaloom.graph.core.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * One node with twenty thousand relationships, and the question of whether that costs more per edge than the first
 * hundred did.
 *
 * <p>
 * <b>Why this is a gate and not a report.</b> The adjacency block appends to a raw tail and recompresses the whole
 * block when the tail meets the compressed region. As a block fills, the free space shrinks, so the tail holds fewer
 * entries, so the recompression runs more often — over a block's life that is a quadratic shape, and it is invisible
 * at the mean degree of 4 that the scale benchmark measures. A node with a degree in the thousands is where it would
 * show, which is exactly the shape a "popular tag" or a "shared collection" has in a real graph.
 * </p>
 *
 * <p>
 * The assertions below therefore compare the <em>last</em> thousand insertions against the <em>first</em> thousand,
 * and the read cost at full degree against the read cost at a hundredth of it. Both are ratios, so neither depends on
 * the machine.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/GRAPH_BENCHMARK.md">spec/GRAPH_BENCHMARK.md</a>
 */
@Tag("bench")
public class SupernodeBenchmark {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int DEGREE = Integer.getInteger("graph.supernode.degree", 20_000);
	private static final int WINDOW = 1_000;

	/**
	 * The insertion curve. Every {@link #WINDOW} edges are timed separately, so the shape of the cost over the node's
	 * life is visible rather than averaged away.
	 */
	@Test
	public void benchSupernodeInsertion() throws Exception {
		try (GraphStore store = stores.open(GraphStoreOptions.defaults())) {
			long hub;
			long[] spokes = new long[DEGREE];
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < DEGREE; i++) {
					spokes[i] = store.createNode("Spoke");
				}
				txn.commit();
			}

			int windows = DEGREE / WINDOW;
			double[] micros = new double[windows];
			System.out.printf("%n=== one node, %,d relationships ===%n", DEGREE);

			for (int w = 0; w < windows; w++) {
				long start = System.nanoTime();
				try (Transaction txn = store.begin()) {
					for (int i = w * WINDOW; i < (w + 1) * WINDOW; i++) {
						store.createEdge(hub, "LINKS", spokes[i]);
					}
					txn.commit();
				}
				micros[w] = (System.nanoTime() - start) / 1000.0 / WINDOW;
			}

			for (int w = 0; w < windows; w++) {
				System.out.printf("  edges %,6d..%,6d  %7.2f us/edge%n",
					w * WINDOW, (w + 1) * WINDOW, micros[w]);
			}

			assertEquals(DEGREE, store.degreeOut(hub), "every edge must be on the node");

			double first = micros[0];
			double last = micros[windows - 1];
			double best = Double.MAX_VALUE;
			double worst = 0;
			for (double m : micros) {
				best = Math.min(best, m);
				worst = Math.max(worst, m);
			}
			System.out.printf("first %.2f us/edge, last %.2f us/edge, best %.2f, worst %.2f%n",
				first, last, best, worst);

			// The claim being defended: the cost per edge does not grow with the degree already reached. A block that
			// recompressed on every append would put the last window in the hundreds.
			//
			// The comparison is against the *best* window, not the first. The first window pays for JIT warmup and for
			// the whole growth ladder — 512 B to 1 KiB to 2 KiB to 4 KiB to a page — so it is the most expensive one in
			// the run, and measuring the end against it would let a real regression through unnoticed.
			assertTrue(last < best * 3.0,
				"insertion at degree " + DEGREE + " costs " + String.format("%.2f", last / best)
					+ "x the cheapest window in the same run; the adjacency tiering has a term that grows with degree");
		}
	}

	/** Reading a supernode's edges, against reading a hundredth of them. Linear in the degree is the requirement. */
	@Test
	public void benchSupernodeRead() throws Exception {
		try (GraphStore store = stores.open(GraphStoreOptions.defaults())) {
			long small;
			long big;
			try (Transaction txn = store.begin()) {
				small = store.createNode("Hub");
				big = store.createNode("Hub");
				for (int i = 0; i < DEGREE; i++) {
					long spoke = store.createNode("Spoke");
					store.createEdge(big, "LINKS", spoke);
					if (i < DEGREE / 100) {
						store.createEdge(small, "LINKS", spoke);
					}
				}
				txn.commit();
			}

			// Probe counts are scaled to the work each call does, so this stays a fixed cost as DEGREE is raised.
			int smallDegree0 = store.degreeOut(small);
			double smallMicros = timeReads(store, small, probesFor(smallDegree0));
			double bigMicros = timeReads(store, big, probesFor(DEGREE));

			int smallDegree = store.degreeOut(small);
			System.out.printf("%nneighbours(OUT) at degree %,6d: %8.2f us  = %6.3f us per edge%n",
				smallDegree, smallMicros, smallMicros / smallDegree);
			System.out.printf("neighbours(OUT) at degree %,6d: %8.2f us  = %6.3f us per edge%n",
				DEGREE, bigMicros, bigMicros / DEGREE);

			double perEdgeSmall = smallMicros / smallDegree;
			double perEdgeBig = bigMicros / DEGREE;
			System.out.printf("per-edge ratio %.2fx across a %dx degree difference%n",
				perEdgeBig / perEdgeSmall, DEGREE / smallDegree);

			// Linear would be 1.0x per edge. Some growth is expected — a supernode chains, and each block in the chain
			// is a pointer hop — but it must not be proportional to the degree.
			assertTrue(perEdgeBig < perEdgeSmall * 3.0,
				"reading one edge of a degree-" + DEGREE + " node costs "
					+ String.format("%.2f", perEdgeBig / perEdgeSmall) + "x what it costs on a small node");
		}
	}

	/** Degree counters are header fields, so they must not depend on the degree at all. */
	@Test
	public void benchSupernodeDegree() throws Exception {
		try (GraphStore store = stores.open(GraphStoreOptions.defaults())) {
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < DEGREE; i++) {
					store.createEdge(hub, "LINKS", store.createNode("Spoke"));
				}
				txn.commit();
			}
			int probes = 200_000;
			long start = System.nanoTime();
			long sink = 0;
			for (int i = 0; i < probes; i++) {
				sink += store.degreeOut(hub);
			}
			double nanos = (double) (System.nanoTime() - start) / probes;
			System.out.printf("degreeOut at degree %,d: %.1f ns (%,.0f /s)%n",
				DEGREE, nanos, 1_000_000_000.0 / nanos);
			assertEquals((long) DEGREE * probes, sink);
			assertTrue(nanos < 2_000, "degreeOut must be a header read, not a walk; it took " + nanos + " ns");
		}
	}

	/** Enough probes to time reliably, few enough that a degree of 200,000 does not take a minute. */
	private static int probesFor(int degree) {
		return Math.max(20, 4_000_000 / Math.max(degree, 1));
	}

	private static double timeReads(GraphStore store, long nodeId, int probes) {
		for (int i = 0; i < 20; i++) {
			store.neighbours(nodeId, Direction.OUT);
		}
		long start = System.nanoTime();
		long seen = 0;
		for (int i = 0; i < probes; i++) {
			seen += store.neighbours(nodeId, Direction.OUT).size();
		}
		assertTrue(seen > 0);
		return (double) TimeUnit.NANOSECONDS.toNanos(System.nanoTime() - start) / probes / 1000.0;
	}
}
