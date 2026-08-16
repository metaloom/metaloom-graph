package io.metaloom.graph.core.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * How fast a declared vector index actually builds, and how long it holds the writer lock while doing it.
 *
 * <p>
 * <b>This measurement is a prerequisite, not a report.</b> The HNSW plan committed to a bulk build whose searches run
 * inside {@code runWrite}, and recorded a doubt about it: an insert at {@code efConstruction=200} is on the order of
 * two thousand distance evaluations, and ten million of those under the writer lock is the "hours" figure
 * spec/VECTOR.md §4.4 warns about. The plan said to measure the single-threaded insert rate at 100k <em>before</em>
 * accepting that design. This is that measurement, and it is checked in so the answer is reproducible rather than
 * remembered.
 * </p>
 *
 * <h2>The two numbers are independent and both matter</h2>
 *
 * <p>
 * <b>Throughput</b> decides whether a build is feasible at all: it extrapolates to a wall-clock figure for 10M.
 * <b>Writer-lock hold</b> decides whether it is usable on a live store, and it is the one the restructuring would
 * change. A build that takes an hour while letting other writers through between batches is a different operational
 * proposition from one that takes an hour with the lock held throughout, and throughput alone cannot tell them apart.
 * </p>
 *
 * <p>
 * The lock hold is measured from the outside, by a thread that repeatedly asks for the writer lock and times how long
 * it waits. That is the number an application actually experiences, and unlike instrumenting the build it cannot
 * flatter itself by measuring only the part it knows about.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a> section 4.4
 */
@Tag("bench")
public class VectorBuildBenchmark {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** Overridable so the same harness can be pushed at 1M on a machine with the disk for it. */
	private static final int MEMBERS = Integer.getInteger("graph.vector.build.members", 100_000);

	/**
	 * 128, not the 768 the design targets.
	 * <p>
	 * A 768-dimension record needs a 4 KiB stride, so 100k of them is 400 MiB of vector data before any links — which
	 * measures the page cache of the machine rather than the build. The cost this benchmark is about is the
	 * <em>number of distance evaluations per insert</em>, which is set by {@code efConstruction} and {@code M} and is
	 * identical at any width; the width scales the cost of each one linearly and predictably. Extrapolating 128 to 768
	 * is honest arithmetic; extrapolating a page-cache miss rate is not.
	 */
	private static final int DIMENSIONS = 128;

	private static GraphStoreOptions options() {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(512L * 1024 * 1024)
			.vectorDimensions(DIMENSIONS)
			.build();
	}

	/**
	 * Clustered rather than uniform, because uniform random vectors in 128 dimensions are all nearly equidistant and
	 * an HNSW search over them terminates far sooner than it does over real embeddings. Measuring the easy case would
	 * report a build rate the real workload never sees.
	 */
	private static float[] vector(Random random, int cluster) {
		float[] v = new float[DIMENSIONS];
		for (int i = 0; i < DIMENSIONS; i++) {
			v[i] = (float) random.nextGaussian() * 0.15f;
		}
		v[cluster % DIMENSIONS] += 1.0f;
		return v;
	}

	@Test
	public void testTheBulkBuildRateAndTheWriterLockItHolds() throws Exception {
		try (GraphStore store = stores.open(options())) {
			Random random = new Random(20260816);

			long loadStart = System.nanoTime();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < MEMBERS; i++) {
					store.createNode("Doc", Properties.of().set("embedding", vector(random, i % 64)));
				}
				txn.commit();
			}
			long loadMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStart);

			// A writer that does nothing but ask for the lock, so the wait it sees is the wait a real one would.
			AtomicLong longestWaitMillis = new AtomicLong();
			AtomicLong acquisitions = new AtomicLong();
			AtomicLong blocked = new AtomicLong(1);
			Thread contender = new Thread(() -> {
				while (blocked.get() == 1) {
					long asked = System.nanoTime();
					try (Transaction txn = store.begin()) {
						long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - asked);
						longestWaitMillis.accumulateAndGet(waited, Math::max);
						acquisitions.incrementAndGet();
						txn.commit();
					} catch (Exception e) {
						return;
					}
					try {
						Thread.sleep(5);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}, "writer-lock-contender");
			contender.setDaemon(true);
			contender.start();

			long buildStart = System.nanoTime();
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "embedding", DIMENSIONS, VectorMetric.COSINE));
			long buildNanos = System.nanoTime() - buildStart;

			blocked.set(0);
			contender.join(TimeUnit.SECONDS.toMillis(30));

			long buildMillis = TimeUnit.NANOSECONDS.toMillis(buildNanos);
			double perSecond = MEMBERS / (buildNanos / 1e9);
			double microsEach = buildNanos / 1_000.0 / MEMBERS;
			double hoursForTenMillion = 10_000_000 / perSecond / 3600;

			System.out.printf("%n=== vector build, %,d members at %d dimensions ===%n", MEMBERS, DIMENSIONS);
			System.out.printf("  load          %,d ms%n", loadMillis);
			System.out.printf("  build         %,d ms  (%.0f inserts/s, %.0f us each)%n",
				buildMillis, perSecond, microsEach);
			System.out.printf("  extrapolated  %.2f h for 10M members at this rate%n", hoursForTenMillion);
			System.out.printf("  writer lock   longest wait %,d ms over %,d acquisitions by a competing writer%n",
				longestWaitMillis.get(), acquisitions.get());
			System.out.printf("  => the lock is %s to another writer during a build%n",
				acquisitions.get() > 1 ? "yielded between batches" : "HELD THROUGHOUT");

			assertEquals(MEMBERS, store.nodesByLabel("Doc").size(), "every member must still be there");
			assertTrue(acquisitions.get() > 1,
				"a build must let another writer in between batches; it acquired the lock "
					+ acquisitions.get() + " time(s) in " + buildMillis + " ms");
		}
	}
}
