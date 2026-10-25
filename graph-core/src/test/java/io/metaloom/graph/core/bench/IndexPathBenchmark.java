package io.metaloom.graph.core.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The index layer's own hot paths, timed without an fsync in the way.
 *
 * <p>
 * {@code PerformanceGateTest} measures the store as a whole and its durable commits are dominated by the fsync, which
 * hides anything the index does per write. These run with {@link WalSyncMode#ASYNC} so that what is left is CPU: the
 * B+tree insert, the catalog-page bookkeeping around it, and the HNSW walk. Each prints one line; nothing is gated,
 * because the numbers are for comparing a change against its own baseline on one machine.
 * </p>
 */
@Tag("bench")
public class IndexPathBenchmark {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int AUTO_COMMITS = 20_000;
	private static final int WRITERS = 4;
	private static final int BATCHED = 100_000;
	private static final int VECTORS = 10_000;
	private static final int QUERIES = 2_000;
	private static final int DIMENSIONS = 128;

	private static GraphStoreOptions options(int dimensions) {
		GraphStoreOptions.Builder builder = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(512L * 1024 * 1024)
			.walSyncMode(WalSyncMode.ASYNC);
		if (dimensions > 0) {
			builder.vectorDimensions(dimensions);
		}
		return builder.build();
	}

	@Test
	public void testIndexedWritePaths() throws Exception {
		try (GraphStore store = stores.open(options(0))) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			store.createNode("Warm", Properties.of().set("email", "warm"));

			IoStats countersBefore = impl.counters().snapshot();
			long start = System.nanoTime();
			for (int i = 0; i < AUTO_COMMITS; i++) {
				store.createNode("P", Properties.of().set("email", "a" + i).set("n", (long) i));
			}
			report("autoCommit createNode, 1 thread", AUTO_COMMITS, start);
			// The deterministic half of the comparison: timings on a shared machine move by tens of percent between
			// runs, while the index records an insert makes writable do not move at all.
			System.out.printf("  %-40s %,12.2f%n", "INDEX record writes per createNode",
				impl.counters().snapshot().since(countersBefore).recordWrites(Kind.INDEX) / (double) AUTO_COMMITS);

			long retriesBefore = impl.autoCommitRetries();
			AtomicReference<Throwable> failed = new AtomicReference<>();
			List<Thread> writers = new ArrayList<>();
			start = System.nanoTime();
			for (int t = 0; t < WRITERS; t++) {
				int writer = t;
				writers.add(Thread.ofPlatform().start(() -> {
					try {
						for (int i = 0; i < AUTO_COMMITS / WRITERS; i++) {
							store.createNode("Q", Properties.of().set("email", "w" + writer + "-" + i)
								.set("n", (long) i));
						}
					} catch (Throwable e) {
						failed.compareAndSet(null, e);
					}
				}));
			}
			for (Thread writer : writers) {
				writer.join();
			}
			report("autoCommit createNode, " + WRITERS + " threads", AUTO_COMMITS, start);
			System.out.printf("  %-40s %,12d%n", "lost conflicts, " + WRITERS + " threads",
				impl.autoCommitRetries() - retriesBefore);
			assertEquals(null, failed.get());

			start = System.nanoTime();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < BATCHED; i++) {
					store.createNode("B", Properties.of().set("email", "b" + i).set("n", (long) i));
				}
				txn.commit();
			}
			report("batched createNode", BATCHED, start);

			start = System.nanoTime();
			int found = 0;
			for (int i = 0; i < BATCHED; i++) {
				found += store.nodesByProperty("email", "b" + i).size();
			}
			report("nodesByProperty", BATCHED, start);
			assertEquals(BATCHED, found);

			start = System.nanoTime();
			for (int i = 0; i < 200; i++) {
				found += store.nodesByLabel("P").size();
			}
			report("nodesByLabel, 20k members", 200, start);
		}
	}

	@Test
	public void testVectorSearch() throws Exception {
		try (GraphStore store = stores.open(options(DIMENSIONS))) {
			for (VectorMetric metric : new VectorMetric[] { VectorMetric.COSINE, VectorMetric.EUCLIDEAN }) {
				String label = "Doc" + metric;
				Random random = new Random(20261024);
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < VECTORS; i++) {
						store.createNode(label, Properties.of().set("embedding", vector(random, i % 64)));
					}
					txn.commit();
				}
				long start = System.nanoTime();
				store.createVectorIndex(VectorIndexDefinition.of(label, "embedding", DIMENSIONS, metric));
				report("vector build " + metric, VECTORS, start);

				float[][] queries = new float[QUERIES][];
				for (int i = 0; i < QUERIES; i++) {
					queries[i] = vector(random, i % 64);
				}
				// Warm once, then time.
				for (int i = 0; i < 200; i++) {
					store.nearest(label, "embedding", queries[i], 10, null);
				}
				start = System.nanoTime();
				int results = 0;
				for (float[] query : queries) {
					results += store.nearest(label, "embedding", query, 10, null).size();
				}
				report("nearest k=10 " + metric, QUERIES, start);

				assertEquals(QUERIES * 10, results);
			}
		}
	}

	private static float[] vector(Random random, int cluster) {
		float[] v = new float[DIMENSIONS];
		for (int i = 0; i < DIMENSIONS; i++) {
			v[i] = (float) random.nextGaussian() * 0.15f;
		}
		v[cluster % DIMENSIONS] += 1.0f;
		return v;
	}

	private static void report(String what, int operations, long startNanos) {
		double seconds = (System.nanoTime() - startNanos) / 1e9;
		System.out.printf("  %-40s %,12.0f ops/s  (%,.1f ms)%n", what, operations / seconds, seconds * 1000);
	}
}
