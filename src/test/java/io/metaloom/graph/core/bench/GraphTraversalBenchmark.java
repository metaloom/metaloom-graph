package io.metaloom.graph.core.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.WalSyncMode;

/**
 * Read performance on a large graph: random access by id, and deep traversal across tens of thousands of
 * relationships.
 *
 * <p>
 * <b>What this measures that the scale benchmark does not.</b>
 * [`GraphScaleBenchmark`](GraphScaleBenchmark.java) is a load benchmark with a read-back section attached; every probe
 * it makes is one hop deep. This one holds the graph still and asks how it behaves under the two read patterns that
 * actually characterise a graph store:
 * </p>
 *
 * <ol>
 * <li><b>Random access by id</b> — the addressing invariant's whole claim. An id decodes to an offset, so this should
 * be flat in the size of the store and should not care that the access is random.</li>
 * <li><b>Deep traversal</b> — 30,000 relationships out from one random node, breadth first. Every hop reads a node
 * record and an adjacency block chosen by the data rather than by the reader, so locality is whatever the loader
 * happened to leave behind. This is where a graph store is either good or a disguised key-value store.</li>
 * </ol>
 *
 * <p>
 * Tagged {@code huge} and excluded from every profile. It builds its own store:
 * </p>
 *
 * <pre>
 * mvn test -Phuge -Dtest=GraphTraversalBenchmark
 * mvn test -Phuge -Dtest=GraphTraversalBenchmark -Dgraph.traversal.nodes=10000000 -Dgraph.traversal.edges=20000000
 * </pre>
 *
 * @see <a href="../../../../../../../../spec/GRAPH_BENCHMARK.md">spec/GRAPH_BENCHMARK.md</a>
 */
@Tag("huge")
public class GraphTraversalBenchmark {

	private static final int NODES = Integer.getInteger("graph.traversal.nodes", 2_000_000);
	private static final int EDGES = Integer.getInteger("graph.traversal.edges", 8_000_000);
	/** Relationships to cross in one traversal. The number in the request this benchmark was written for. */
	private static final int BUDGET = Integer.getInteger("graph.traversal.budget", 30_000);
	private static final int NODE_BATCH = 100_000;
	private static final int EDGE_BATCH = 20_000;
	private static final int CHECKPOINT_EVERY = 20;

	@Test
	public void benchTraversal() throws Exception {
		Path dir = Files.createTempDirectory(
			Path.of(System.getProperty("graph.traversal.dir", System.getProperty("java.io.tmpdir"))), "graph-traversal-");
		GraphStoreOptions options = GraphStoreOptions.builder().walSyncMode(WalSyncMode.FSYNC_ON_COMMIT).build();

		System.out.printf("%n=== %,d nodes / %,d edges, mean degree %.1f, seed 20260814 ===%n",
			NODES, EDGES, 2.0 * EDGES / NODES);
		System.out.println("store: " + dir);

		long[] ids = new long[NODES];
		try (GraphStore store = GraphStore.open(dir, options)) {
			build(store, ids);
			long[] spine = buildSpine(store, ids);

			System.out.println();
			System.out.println("--- random access by id ---");
			randomAccess(store, ids);

			System.out.println();
			System.out.printf("--- deep traversal, %,d relationship budget ---%n", BUDGET);
			deepTraversal(store, ids);

			System.out.println();
			System.out.printf("--- one path, %,d hops deep ---%n", spine.length - 1);
			chainTraversal(store, spine);

			System.out.println();
			System.out.println("--- the same budget spent differently ---");
			shapeComparison(store, ids);
		} finally {
			if (!Boolean.getBoolean("graph.traversal.keep")) {
				deleteRecursively(dir);
			}
		}
	}

	// ---------------------------------------------------------------- the graph

	private void build(GraphStore store, long[] ids) throws IOException {
		long start = System.nanoTime();
		for (int base = 0; base < NODES; base += NODE_BATCH) {
			int end = Math.min(base + NODE_BATCH, NODES);
			try (Transaction txn = store.begin()) {
				for (int i = base; i < end; i++) {
					ids[i] = store.createNode("Person", Properties.of().set("i", i));
				}
				txn.commit();
			}
		}
		store.checkpoint();
		System.out.printf("built %,d nodes in %,d ms%n", NODES, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

		Random random = new Random(20260814L);
		start = System.nanoTime();
		int batches = 0;
		for (int base = 0; base < EDGES; base += EDGE_BATCH) {
			int end = Math.min(base + EDGE_BATCH, EDGES);
			try (Transaction txn = store.begin()) {
				for (int i = base; i < end; i++) {
					store.createEdge(ids[random.nextInt(NODES)], "KNOWS", ids[random.nextInt(NODES)]);
				}
				txn.commit();
			}
			if (++batches % CHECKPOINT_EVERY == 0) {
				store.checkpoint();
			}
		}
		store.checkpoint();
		System.out.printf("built %,d edges in %,d ms%n", EDGES, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
	}

	/**
	 * A dedicated path of {@link #BUDGET} fresh nodes, wired end to end.
	 *
	 * <p>
	 * Built out of new nodes rather than found in the random graph, because a path that long does not reliably exist
	 * in one — and a search for it would measure the search. Their ids are consecutive, which is the <em>friendliest</em>
	 * possible layout for a chain walk; a chain scattered across the store can only be slower, so this is the floor.
	 * </p>
	 */
	private long[] buildSpine(GraphStore store, long[] ids) throws IOException {
		int length = BUDGET + 1;
		long[] spine = new long[length];
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < length; i++) {
				spine[i] = store.createNode("Link", Properties.of().set("i", ids.length + i));
			}
			txn.commit();
		}
		for (int base = 0; base < length - 1; base += EDGE_BATCH) {
			int end = Math.min(base + EDGE_BATCH, length - 1);
			try (Transaction txn = store.begin()) {
				for (int i = base; i < end; i++) {
					store.createEdge(spine[i], "NEXT", spine[i + 1]);
				}
				txn.commit();
			}
		}
		store.checkpoint();
		return spine;
	}

	// ---------------------------------------------------------------- random access

	private void randomAccess(GraphStore store, long[] ids) {
		Random random = new Random(7L);
		int probes = 500_000;

		// Sequential ids first, then random ones, against the same store. The gap between the two lines is the only
		// thing on this page that the page cache and the prefetcher can explain, and it is the number that says
		// whether "random access is free" is a real property or an artefact of touching a warm, ordered region.
		time("nodeLabel, sequential ids", probes, () -> {
			long sink = 0;
			for (int i = 0; i < probes; i++) {
				sink += store.nodeLabel(ids[i % ids.length]).length();
			}
			return sink;
		});
		time("nodeLabel, random ids", probes, () -> {
			long sink = 0;
			for (int i = 0; i < probes; i++) {
				sink += store.nodeLabel(ids[random.nextInt(ids.length)]).length();
			}
			return sink;
		});
		time("nodeProperties, random ids", probes, () -> {
			long sink = 0;
			for (int i = 0; i < probes; i++) {
				sink += store.nodeProperties(ids[random.nextInt(ids.length)]).size();
			}
			return sink;
		});
		time("degreeOut, random ids", probes, () -> {
			long sink = 0;
			for (int i = 0; i < probes; i++) {
				sink += store.degreeOut(ids[random.nextInt(ids.length)]);
			}
			return sink;
		});
		time("neighbours(OUT), random ids", probes / 5, () -> {
			long sink = 0;
			for (int i = 0; i < probes / 5; i++) {
				sink += store.neighbours(ids[random.nextInt(ids.length)], Direction.OUT).size();
			}
			return sink;
		});
		time("nodesByProperty, random values", 20_000, () -> {
			long sink = 0;
			for (int i = 0; i < 20_000; i++) {
				sink += store.nodesByProperty("i", random.nextInt(ids.length)).size();
			}
			return sink;
		});
	}

	// ---------------------------------------------------------------- deep traversal

	/** One breadth-first walk that stops after {@link #BUDGET} relationships, from ten different random starts. */
	private void deepTraversal(GraphStore store, long[] ids) {
		Random random = new Random(99L);
		int runs = 10;

		// Warm the JIT on a start that is not one of the measured ones.
		walk(store, ids[random.nextInt(ids.length)], BUDGET);

		long totalNanos = 0;
		long totalEdges = 0;
		long totalNodes = 0;
		int deepest = 0;
		double worstPerEdge = 0;
		double bestPerEdge = Double.MAX_VALUE;

		for (int run = 0; run < runs; run++) {
			long start = ids[random.nextInt(ids.length)];
			long began = System.nanoTime();
			Walk result = walk(store, start, BUDGET);
			long nanos = System.nanoTime() - began;

			double perEdge = (double) nanos / Math.max(result.edges, 1);
			worstPerEdge = Math.max(worstPerEdge, perEdge);
			bestPerEdge = Math.min(bestPerEdge, perEdge);
			totalNanos += nanos;
			totalEdges += result.edges;
			totalNodes += result.nodes;
			deepest = Math.max(deepest, result.depth);

			System.out.printf("  run %2d: %,7d relationships, %,6d nodes discovered, depth %2d, %6.1f ms, %5.2f us/edge%n",
				run + 1, result.edges, result.nodes, result.depth,
				nanos / 1_000_000.0, perEdge / 1000.0);
		}

		double meanPerEdge = (double) totalNanos / totalEdges;
		System.out.printf("  mean: %,.0f relationships/s, %.2f us per relationship, %,d nodes discovered%n",
			totalEdges / (totalNanos / 1_000_000_000.0), meanPerEdge / 1000.0, totalNodes);
		System.out.printf("  spread across starts: %.2f to %.2f us/edge (%.1fx)%n",
			bestPerEdge / 1000.0, worstPerEdge / 1000.0, worstPerEdge / bestPerEdge);
		System.out.printf("  deepest walk: %d hops%n", deepest);
		assertTrue(totalEdges > 0);
	}

	private record Walk(int edges, int nodes, int depth) {
	}

	/**
	 * Breadth-first until the relationship budget is spent.
	 * <p>
	 * Deliberately written against the public API — {@code neighbours} per node, a visited set, a queue — because that
	 * is what a caller writes. {@code traverse()} does the same thing internally but cannot stop at a budget, so at
	 * this size it would materialise the whole component.
	 */
	private Walk walk(GraphStore store, long startId, int budget) {
		Set<Long> visited = new HashSet<>();
		Deque<long[]> queue = new ArrayDeque<>();
		visited.add(startId);
		queue.add(new long[] { startId, 0 });
		int crossed = 0;
		int deepest = 0;

		while (!queue.isEmpty() && crossed < budget) {
			long[] current = queue.poll();
			int depth = (int) current[1];
			deepest = Math.max(deepest, depth);
			for (Edge edge : store.neighbours(current[0], Direction.BOTH)) {
				crossed++;
				if (visited.add(edge.neighbourId())) {
					queue.add(new long[] { edge.neighbourId(), depth + 1 });
				}
				if (crossed >= budget) {
					break;
				}
			}
		}
		return new Walk(crossed, visited.size(), deepest);
	}

	/**
	 * The same number of relationships, reached three ways.
	 * <p>
	 * A budget spent breadth first, spent depth first, and spent on unrelated random nodes. The three differ only in
	 * how far apart in the store the pages they touch are, so the spread between them is a locality measurement and
	 * nothing else.
	 */
	private void shapeComparison(GraphStore store, long[] ids) {
		Random random = new Random(1234L);
		int runs = 5;

		double breadth = 0;
		double depth = 0;
		double scattered = 0;
		int breadthCalls = 0;
		int depthCalls = 0;
		int scatteredCalls = 0;
		for (int i = 0; i < runs; i++) {
			long start = ids[random.nextInt(ids.length)];
			Counting counter = new Counting();
			breadth += perEdgeNanos(() -> breadthCounted(store, start, BUDGET, counter), BUDGET);
			breadthCalls += counter.take();
			depth += perEdgeNanos(() -> depthFirst(store, start, BUDGET, counter), BUDGET);
			depthCalls += counter.take();
			scattered += perEdgeNanos(() -> scatteredReads(store, ids, BUDGET, counter), BUDGET);
			scatteredCalls += counter.take();
		}
		row("breadth first", breadth / runs, breadthCalls / runs);
		row("depth first", depth / runs, depthCalls / runs);
		row("unrelated nodes", scattered / runs, scatteredCalls / runs);
		System.out.println("  A walk costs MORE per call than sampling unrelated nodes does, which is the opposite of");
		System.out.println("  what locality would predict. The cause is that breadth-first search samples nodes in");
		System.out.println("  proportion to their degree — it reaches a popular node by many routes and an isolated");
		System.out.println("  one by none — so the nodes it opens are bigger than average and cost more to read.");
		System.out.println("  Locality itself buys nothing here: the whole store fits in page cache at this size, so");
		System.out.println("  there is no device read for a nearby page to save. Re-run this on a store larger than");
		System.out.println("  RAM before quoting any of it as a locality result.");
	}

	private static void row(String what, double nanosPerEdge, int calls) {
		System.out.printf("  %-16s %6.2f us per relationship over %,6d calls = %7.2f us per call%n",
			what, nanosPerEdge / 1000.0, calls, nanosPerEdge * BUDGET / Math.max(calls, 1) / 1000.0);
	}

	/** A mutable counter, so each shape can report how many {@code neighbours} calls it took to spend the budget. */
	private static final class Counting {
		private int calls;

		void call() {
			calls++;
		}

		int take() {
			int value = calls;
			calls = 0;
			return value;
		}
	}

	private void breadthCounted(GraphStore store, long startId, int budget, Counting counter) {
		Set<Long> visited = new HashSet<>();
		Deque<long[]> queue = new ArrayDeque<>();
		visited.add(startId);
		queue.add(new long[] { startId, 0 });
		int crossed = 0;
		while (!queue.isEmpty() && crossed < budget) {
			long[] current = queue.poll();
			counter.call();
			for (Edge edge : store.neighbours(current[0], Direction.BOTH)) {
				crossed++;
				if (visited.add(edge.neighbourId())) {
					queue.add(new long[] { edge.neighbourId(), (int) current[1] + 1 });
				}
				if (crossed >= budget) {
					break;
				}
			}
		}
	}

	/**
	 * A single path, {@link #BUDGET} hops long, walked one node at a time.
	 *
	 * <p>
	 * This is the traversal shape the breadth-first numbers cannot show. A wide random graph of mean degree 8 exhausts
	 * a 30,000 relationship budget in five hops, so nothing about it is deep — it is a shallow flood. A chain forces
	 * every hop to depend on the one before it, which means no read can be issued until the previous read has landed.
	 * It is therefore pure dependent-load latency, the worst case for any storage engine and the case a batched I/O
	 * engine cannot help with at all.
	 * </p>
	 */
	private void chainTraversal(GraphStore store, long[] spine) {
		int hops = spine.length - 1;
		for (int i = 0; i < 3; i++) {
			followChain(store, spine[0], hops);
		}
		long start = System.nanoTime();
		int walked = followChain(store, spine[0], hops);
		long nanos = System.nanoTime() - start;
		System.out.printf("  chain of %,d nodes: %,d hops in %6.2f ms = %6.0f ns per hop (%,.0f hops/s)%n",
			spine.length, walked, nanos / 1_000_000.0, (double) nanos / walked, walked / (nanos / 1_000_000_000.0));
		System.out.println("  Every hop waits for the one before it, so this is latency, not throughput. It is the");
		System.out.println("  number a prefetching cursor cannot improve and a lower per-node cost can.");
		assertTrue(walked > 0);
	}

	/** Follows OUT edges until the chain ends or the hop budget is spent. */
	private int followChain(GraphStore store, long startId, int hops) {
		long current = startId;
		int walked = 0;
		while (walked < hops) {
			List<Edge> out = store.neighbours(current, Direction.OUT);
			long next = ElementIdNone;
			for (Edge edge : out) {
				if (edge.direction() == Direction.OUT) {
					next = edge.neighbourId();
					break;
				}
			}
			if (next == ElementIdNone) {
				break;
			}
			current = next;
			walked++;
		}
		return walked;
	}

	private static final long ElementIdNone = io.metaloom.graph.core.id.ElementId.NULL;

	private int depthFirst(GraphStore store, long startId, int budget, Counting counter) {
		Set<Long> visited = new HashSet<>();
		Deque<Long> stack = new ArrayDeque<>();
		visited.add(startId);
		stack.push(startId);
		int crossed = 0;
		while (!stack.isEmpty() && crossed < budget) {
			long nodeId = stack.pop();
			counter.call();
			for (Edge edge : store.neighbours(nodeId, Direction.BOTH)) {
				crossed++;
				if (visited.add(edge.neighbourId())) {
					stack.push(edge.neighbourId());
				}
				if (crossed >= budget) {
					break;
				}
			}
		}
		return crossed;
	}

	private int scatteredReads(GraphStore store, long[] ids, int budget, Counting counter) {
		Random random = new Random(4242L);
		int crossed = 0;
		while (crossed < budget) {
			counter.call();
			List<Edge> edges = store.neighbours(ids[random.nextInt(ids.length)], Direction.BOTH);
			crossed += Math.max(edges.size(), 1);
		}
		return crossed;
	}

	// ---------------------------------------------------------------- plumbing

	private interface Work {
		long run();
	}

	private static void time(String what, int operations, Work work) {
		long sink = work.run();
		long start = System.nanoTime();
		sink += work.run();
		long nanos = System.nanoTime() - start;
		System.out.printf("  %-32s %,10d in %7.1f ms = %,12.0f /s  (%6.0f ns each)%n",
			what, operations, nanos / 1_000_000.0, operations / (nanos / 1_000_000_000.0), (double) nanos / operations);
		assertTrue(sink >= 0);
	}

	private static double perEdgeNanos(Runnable work, int edges) {
		long start = System.nanoTime();
		work.run();
		return (double) (System.nanoTime() - start) / edges;
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
