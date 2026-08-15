package io.metaloom.graph.core.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.WalSyncMode;

/**
 * How long does it take to build a large random graph, and what does it cost on disk.
 *
 * <p>
 * Defaults to <b>10 million nodes and 20 million relationships</b>, wired at random. Excluded from every profile,
 * including {@code -Pall-tests}, because it writes tens of gigabytes and runs for minutes. Run it deliberately:
 * </p>
 *
 * <pre>
 * mvn test -Phuge -Dtest=GraphScaleBenchmark
 * mvn test -Phuge -Dtest=GraphScaleBenchmark -Dgraph.scale.nodes=1000000 -Dgraph.scale.edges=2000000
 * mvn test -Phuge -Dtest=GraphScaleBenchmark -Dgraph.scale.dir=/mnt/fast -Dgraph.scale.keep=true
 * </pre>
 *
 * <p>
 * <b>What the numbers mean and do not mean.</b> This is a single-process timing loop against whatever filesystem the
 * temporary directory lands on, not a controlled benchmark. It measures the load path end to end — id allocation,
 * property encoding, adjacency maintenance at both endpoints, index maintenance, WAL append and fsync — so it is the
 * number to quote for "how long to ingest a graph", and useless for attributing a cost to any one of those.
 * </p>
 *
 * <p>
 * <b>Batching is not optional here.</b> The log is no-steal: a transaction's changes live in private page copies until
 * commit, so one transaction around 10 million nodes would need the whole load in heap. The batch sizes below are what
 * a real loader would have to pick too, and they are part of the measurement rather than a detail of the test.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
@Tag("huge")
public class GraphScaleBenchmark {

	private static final int NODES = Integer.getInteger("graph.scale.nodes", 10_000_000);
	private static final int EDGES = Integer.getInteger("graph.scale.edges", 20_000_000);

	/** Nodes per transaction. Bounded by the overlay, which holds a private copy of every page a transaction touches. */
	private static final int NODE_BATCH = Integer.getInteger("graph.scale.nodeBatch", 100_000);

	/**
	 * Edges per transaction, deliberately far smaller. A random edge touches two node pages and can promote either
	 * endpoint onto its own 8 KiB adjacency block, so a batch of edges dirties orders of magnitude more page than a
	 * batch of nodes does.
	 */
	private static final int EDGE_BATCH = Integer.getInteger("graph.scale.edgeBatch", 20_000);

	/** Checkpoint cadence. Without one the WAL is never trimmed and the load is bounded by log space, not data. */
	private static final int CHECKPOINT_EVERY = Integer.getInteger("graph.scale.checkpointEvery", 40);

	private static final boolean MATERIALISE = Boolean.getBoolean("graph.scale.materialise");
	private static final boolean KEEP = Boolean.getBoolean("graph.scale.keep");
	private static final boolean WITH_PROPERTIES = !"false".equals(System.getProperty("graph.scale.properties"));

	@Test
	public void benchRandomGraph() throws Exception {
		Path dir = Files.createTempDirectory(Path.of(System.getProperty("graph.scale.dir", System.getProperty("java.io.tmpdir"))),
			"graph-scale-");
		System.out.printf("%n=== %,d nodes / %,d %s, random, seed 20260814 ===%n",
			NODES, EDGES, MATERIALISE ? "materialised relationships" : "lightweight edges");
		System.out.println("store: " + dir);
		System.out.printf("batches: %,d nodes and %,d edges per transaction, checkpoint every %d batches%n",
			NODE_BATCH, EDGE_BATCH, CHECKPOINT_EVERY);
		System.out.println("properties per node: " + (WITH_PROPERTIES ? "2 (name, i)" : "none"));

		GraphStoreOptions options = GraphStoreOptions.builder()
			// The default: an fsync per commit. With batches this size the fsync is not the cost, and anything weaker
			// would be measuring a store that can lose the load it just reported.
			.walSyncMode(WalSyncMode.FSYNC_ON_COMMIT)
			.build();

		long[] ids = new long[NODES];
		Timing nodes;
		Timing edges;
		StoreStats stats;

		try (GraphStore store = GraphStore.open(dir, options)) {

			// ------------------------------------------------------------ nodes
			long start = System.nanoTime();
			int batches = 0;
			for (int base = 0; base < NODES; base += NODE_BATCH) {
				int end = Math.min(base + NODE_BATCH, NODES);
				try (Transaction txn = store.begin()) {
					for (int i = base; i < end; i++) {
						ids[i] = WITH_PROPERTIES
							? store.createNode("Person", io.metaloom.graph.core.api.Properties.of()
								.set("name", "n" + i)
								.set("i", i))
							: store.createNode("Person");
					}
					txn.commit();
				}
				if (++batches % CHECKPOINT_EVERY == 0) {
					store.checkpoint();
					progress("nodes", end, NODES, start);
				}
			}
			store.checkpoint();
			nodes = Timing.since("createNode", NODES, start);
			nodes.print();

			// ------------------------------------------------------------ edges
			Random random = new Random(20260814L);
			start = System.nanoTime();
			batches = 0;
			for (int base = 0; base < EDGES; base += EDGE_BATCH) {
				int end = Math.min(base + EDGE_BATCH, EDGES);
				try (Transaction txn = store.begin()) {
					for (int i = base; i < end; i++) {
						long from = ids[random.nextInt(NODES)];
						long to = ids[random.nextInt(NODES)];
						if (MATERIALISE) {
							store.createRelationship(from, "KNOWS", to, io.metaloom.graph.core.api.Properties.of()
								.set("weight", i));
						} else {
							store.createEdge(from, "KNOWS", to);
						}
					}
					txn.commit();
				}
				if (++batches % CHECKPOINT_EVERY == 0) {
					store.checkpoint();
					progress("edges", end, EDGES, start);
				}
			}
			store.checkpoint();
			edges = Timing.since(MATERIALISE ? "createRelationship" : "createEdge", EDGES, start);
			edges.print();

			// ------------------------------------------------------------ what it cost
			stats = store.stats();
			System.out.println();
			System.out.printf("nodes %,d  relationships %,d  adjacency cells %,d  property cells %,d  segments %,d%n",
				stats.nodeCount(), stats.relationshipCount(), stats.adjacencyCellCount(),
				stats.propertyCellCount(), stats.segmentFileCount());
			report(dir, stats);

			// ------------------------------------------------------------ reading it back
			readBack(store, ids, random);
		}

		// Reopening a store this size is the operationally interesting number: it is what a restart costs.
		long start = System.nanoTime();
		try (GraphStore store = GraphStore.open(dir, options)) {
			StoreStats reopened = store.stats();
			System.out.printf("%nreopen: %,d ms, %,d nodes still there%n",
				TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), reopened.nodeCount());
			assertEquals(stats.nodeCount(), reopened.nodeCount());
		}

		assertEquals(NODES, stats.nodeCount());
		assertTrue(nodes.perSecond() > 0 && edges.perSecond() > 0);

		if (KEEP) {
			System.out.println("store retained at " + dir);
		} else {
			deleteRecursively(dir);
		}
	}

	// ---------------------------------------------------------------- read path

	private void readBack(GraphStore store, long[] ids, Random random) {
		int probes = 200_000;

		long start = System.nanoTime();
		long labels = 0;
		for (int i = 0; i < probes; i++) {
			labels += store.nodeLabel(ids[random.nextInt(ids.length)]).length();
		}
		Timing.since("nodeLabel, random id, warm", probes, start).print();
		assertTrue(labels > 0);

		start = System.nanoTime();
		long seen = 0;
		for (int i = 0; i < probes; i++) {
			seen += store.neighbours(ids[random.nextInt(ids.length)], Direction.OUT).size();
		}
		Timing.since("neighbours(OUT), random node", probes, start).print();
		System.out.printf("  %,d neighbours over %,d probes, mean out-degree %.2f%n", seen, probes, (double) seen / probes);

		if (WITH_PROPERTIES) {
			start = System.nanoTime();
			for (int i = 0; i < probes; i++) {
				store.nodeProperties(ids[random.nextInt(ids.length)]);
			}
			Timing.since("nodeProperties, random id", probes, start).print();

			int lookups = 20_000;
			start = System.nanoTime();
			long found = 0;
			for (int i = 0; i < lookups; i++) {
				found += store.nodesByProperty("i", random.nextInt(ids.length)).size();
			}
			Timing.since("nodesByProperty, B+tree", lookups, start).print();
			System.out.printf("  %,d matched%n", found);
		}
	}

	// ---------------------------------------------------------------- reporting

	private static void report(Path dir, StoreStats stats) throws IOException {
		long apparent = 0;
		long files = 0;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				apparent += Files.size(p);
				files++;
			}
		}
		System.out.printf("storageBytes (engine accounting) %,15d = %6.1f GiB%n",
			stats.storageBytes(), stats.storageBytes() / (1024.0 * 1024 * 1024));
		System.out.printf("recordBytes  (live payload)      %,15d = %6.1f GiB%n",
			stats.recordBytes(), stats.recordBytes() / (1024.0 * 1024 * 1024));
		System.out.printf("apparent file size over %,d files %,14d = %6.1f GiB (segments are sparse; du reports less)%n",
			files, apparent, apparent / (1024.0 * 1024 * 1024));
		System.out.printf("bytes per node %.1f%n", stats.bytesPerNode());
	}

	private static void progress(String what, long done, long total, long startNanos) {
		long elapsed = System.nanoTime() - startNanos;
		double rate = done / (elapsed / 1_000_000_000.0);
		long remaining = (long) ((total - done) / Math.max(rate, 1));
		System.out.printf("  %s %,d/%,d  %,.0f/s  %ds elapsed, ~%ds left%n",
			what, done, total, rate, TimeUnit.NANOSECONDS.toSeconds(elapsed), remaining);
	}

	private record Timing(String what, long count, long nanos) {

		static Timing since(String what, long count, long startNanos) {
			return new Timing(what, count, System.nanoTime() - startNanos);
		}

		double perSecond() {
			return count / (nanos / 1_000_000_000.0);
		}

		void print() {
			System.out.printf("%-34s %,12d in %8.1f s = %,12.0f /s%n",
				what, count, nanos / 1_000_000_000.0, perSecond());
		}
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
