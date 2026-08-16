package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.index.HnswIndex;
import io.metaloom.graph.core.segment.Geometry;

/**
 * How good the approximate answer is, measured against the exact one.
 *
 * <p>
 * This is the test the whole index is judged by, and it is deliberately <b>not</b> an equality assertion. An
 * approximate structure that always agreed with a scan would either be exact or be measured on data too small to
 * approximate anything; what matters is the fraction of the true nearest neighbours it finds, reported as a number
 * with a floor the build fails below. A recall figure that is never printed is a recall figure nobody can argue with.
 * </p>
 *
 * <p>
 * The exact answer comes from {@code nearestByScan}, which shares no code with the index — the two agree because they
 * compute the same thing, not because one is implemented in terms of the other.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
@Tag("unit")
public class VectorRecallTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int DIMENSIONS = 16;
	private static final int CORPUS = 1500;
	private static final int K = 10;
	private static final int QUERIES = 25;

	/**
	 * The floor. Below this the index is worse than the numbers this implementation was written to produce, and the
	 * build should fail rather than the regression be discovered later.
	 */
	private static final double RECALL_FLOOR = 0.80;

	private static GraphStoreOptions options() {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(DIMENSIONS)
			.build();
	}

	private static float[] randomVector(Random random) {
		float[] vector = new float[DIMENSIONS];
		for (int i = 0; i < DIMENSIONS; i++) {
			vector[i] = (float) random.nextGaussian();
		}
		return vector;
	}

	private static Set<Long> ids(List<Neighbour> neighbours) {
		Set<Long> ids = new HashSet<>();
		for (Neighbour neighbour : neighbours) {
			ids.add(neighbour.nodeId());
		}
		return ids;
	}

	@Test
	public void testRecallAgainstAnExactScan() throws Exception {
		Random random = new Random(20260816L);
		try (GraphStore store = stores.open(options())) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < CORPUS; i++) {
					store.createNode(List.of("Doc"),
						Properties.of().set("name", "d" + i).set("embedding", randomVector(random)));
				}
				txn.commit();
			}
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "embedding", DIMENSIONS, VectorMetric.COSINE));

			double found = 0;
			for (int q = 0; q < QUERIES; q++) {
				float[] query = randomVector(random);
				Set<Long> exact = ids(store.nearestByScan("Doc", "embedding", query, K, VectorMetric.COSINE));
				Set<Long> approximate = ids(store.nearest("Doc", "embedding", query, K, null));
				assertEquals(K, approximate.size(), "the index returned fewer than k results");
				approximate.retainAll(exact);
				found += approximate.size();
			}
			double recall = found / (QUERIES * (double) K);
			System.out.println("VectorRecallTest recall@" + K + " over " + CORPUS + " vectors = " + recall);
			assertTrue(recall >= RECALL_FLOOR,
				"recall@" + K + " was " + recall + ", below the " + RECALL_FLOOR + " floor");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * Recall under a filter is a <b>separate</b> measurement with its own floor, and lower by construction.
	 * <p>
	 * The filter is applied when admitting a result rather than to steer the walk — steering it is the classic way to
	 * collapse an approximate search, because the greedy descent never reaches the region where the matches are. The
	 * price of doing it correctly is that a selective filter needs a wider beam to fill its k, and sometimes cannot.
	 */
	@Test
	public void testRecallUnderAFilter() throws Exception {
		Random random = new Random(4242L);
		try (GraphStore store = stores.open(options())) {
			List<Long> even = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < CORPUS; i++) {
					long id = store.createNode(List.of("Doc"),
						Properties.of().set("name", "d" + i).set("embedding", randomVector(random)));
					if (i % 2 == 0) {
						even.add(id);
					}
				}
				txn.commit();
			}
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "embedding", DIMENSIONS, VectorMetric.COSINE));
			Set<Long> allowed = new HashSet<>(even);

			double found = 0;
			double possible = 0;
			for (int q = 0; q < QUERIES; q++) {
				float[] query = randomVector(random);
				// The exact filtered answer: scan everything, keep only what passes, take the nearest k.
				List<Neighbour> scanned = store.nearestByScan("Doc", "embedding", query, CORPUS, VectorMetric.COSINE);
				Set<Long> exact = new HashSet<>();
				for (Neighbour neighbour : scanned) {
					if (allowed.contains(neighbour.nodeId()) && exact.size() < K) {
						exact.add(neighbour.nodeId());
					}
				}
				Set<Long> approximate = ids(store.nearest("Doc", "embedding", query, K, allowed::contains));
				for (long id : approximate) {
					assertTrue(allowed.contains(id), "the filter let through a node it should not have");
				}
				approximate.retainAll(exact);
				found += approximate.size();
				possible += exact.size();
			}
			double recall = found / possible;
			System.out.println("VectorRecallTest filtered recall@" + K + " = " + recall);
			assertTrue(recall >= 0.60, "filtered recall@" + K + " was " + recall + ", below the 0.60 floor");
		}
	}

	/**
	 * The layer assignment comes from the vector's own bytes, so the same corpus produces the same graph twice —
	 * across a rebuild, and across a compaction that changes every id.
	 */
	@Test
	public void testTheLayerAssignmentIsAPropertyOfTheVector() {
		float[] vector = { 1f, 2f, 3f, 4f };
		int first = HnswIndex.levelOf(vector, 16, 8);
		assertEquals(first, HnswIndex.levelOf(vector.clone(), 16, 8), "an equal vector must land on the same layer");
		assertTrue(first >= 0 && first <= 8);

		// And it is a distribution, not a constant: over many vectors most land at the base and a few go higher.
		Random random = new Random(7L);
		int higher = 0;
		for (int i = 0; i < 2000; i++) {
			if (HnswIndex.levelOf(randomVector(random), 16, 8) > 0) {
				higher++;
			}
		}
		assertTrue(higher > 20 && higher < 400, "expected roughly a sixteenth above the base layer, got " + higher);
	}
}
