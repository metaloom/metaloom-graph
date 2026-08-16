package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.Vectors;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * The brute-force vector scan: exact nearest-k, no index.
 *
 * <p>
 * This is the ground truth an approximate index is measured against, so its own correctness cannot rest on anything an
 * index does. Two of the tests below therefore compute the expected answer a second way — by sorting every vector in
 * the test itself — rather than asserting a list somebody wrote down.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
@Tag("unit")
public class VectorScanTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testNearestIsOrderedNearestFirst() throws Exception {
		try (GraphStore store = stores.open()) {
			long east;
			long eastish;
			long north;
			try (Transaction txn = store.begin()) {
				east = doc(store, "east", 1f, 0f, 0f);
				eastish = doc(store, "east-ish", 0.9f, 0.1f, 0f);
				north = doc(store, "north", 0f, 1f, 0f);
				// Carries the key with a non-vector value, and carries no key at all: neither is a candidate, and
				// neither may fail the query.
				store.createNode(List.of("Doc"), Properties.of().set("embedding", "not a vector"));
				store.createNode(List.of("Doc"), Properties.of().set("name", "bare"));
				txn.commit();
			}

			List<Neighbour> near = store.nearestByScan("Doc", "embedding", new float[] { 1f, 0f, 0f }, 3,
				VectorMetric.COSINE);
			assertEquals(List.of(east, eastish, north), ids(near), "nearest first");
			assertEquals(1.0, near.get(0).score(), 1e-6, "and the score is the metric's own value");
			assertTrue(near.get(0).score() > near.get(2).score(), "which for cosine descends");
		}
	}

	/**
	 * The trap {@code VectorMetric.rank} exists to close: cosine wants the largest score and L2 the smallest, so a
	 * ranking that hard-codes either direction returns the <em>furthest</em> vectors for the other metric.
	 */
	@Test
	public void testEuclideanRanksTheOtherWayAndStillComesBackNearestFirst() throws Exception {
		try (GraphStore store = stores.open()) {
			long near;
			long far;
			try (Transaction txn = store.begin()) {
				near = doc(store, "near", 1f, 1f, 0f);
				far = doc(store, "far", 50f, 50f, 0f);
				txn.commit();
			}
			List<Neighbour> byL2 = store.nearestByScan("Doc", "embedding", new float[] { 0f, 0f, 0f }, 2,
				VectorMetric.EUCLIDEAN);
			assertEquals(List.of(near, far), ids(byL2), "L2 puts the smallest distance first");
			assertTrue(byL2.get(0).score() < byL2.get(1).score(), "and its score ascends, unlike cosine's");

			// The same two vectors, ranked by dot product, come back the other way round — which is the whole reason
			// the metric travels with the query rather than being assumed.
			assertEquals(List.of(far, near),
				ids(store.nearestByScan("Doc", "embedding", new float[] { 1f, 1f, 0f }, 2, VectorMetric.DOT)));
		}
	}

	/** The differential: the scan must agree with sorting every vector in the test, for every metric. */
	@Test
	public void testTheScanMatchesAnIndependentSort() throws Exception {
		Random random = new Random(20260816L);
		try (GraphStore store = stores.open()) {
			List<long[]> ids = new ArrayList<>();
			List<float[]> vectors = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 400; i++) {
					float[] vector = new float[8];
					for (int d = 0; d < vector.length; d++) {
						vector[d] = random.nextFloat() * 2 - 1;
					}
					long id = store.createNode(List.of("Doc"),
						Properties.of().set("name", "d" + i).set("embedding", vector));
					ids.add(new long[] { id });
					vectors.add(vector);
				}
				txn.commit();
			}

			float[] query = new float[] { 0.5f, -0.25f, 0f, 1f, -1f, 0.1f, 0.2f, -0.3f };
			for (VectorMetric metric : VectorMetric.values()) {
				List<Long> expected = new ArrayList<>();
				List<Integer> order = new ArrayList<>();
				for (int i = 0; i < vectors.size(); i++) {
					order.add(i);
				}
				order.sort((a, b) -> Double.compare(metric.rank(query, vectors.get(a)),
					metric.rank(query, vectors.get(b))));
				for (int i = 0; i < 10; i++) {
					expected.add(ids.get(order.get(i))[0]);
				}
				assertEquals(expected, ids(store.nearestByScan("Doc", "embedding", query, 10, metric)),
					"the scan disagrees with an independent sort under " + metric);
			}
		}
	}

	/**
	 * A vector of a different length is skipped, not refused. A property key is free-form, so one node holding a
	 * four-dimension value is a fact about that node rather than a reason to fail everybody else's query — the
	 * refusal belongs on the write path of a declared index.
	 */
	@Test
	public void testAWrongLengthVectorIsSkipped() throws Exception {
		try (GraphStore store = stores.open()) {
			long good;
			try (Transaction txn = store.begin()) {
				good = doc(store, "three", 1f, 0f, 0f);
				store.createNode(List.of("Doc"),
					Properties.of().set("name", "four").set("embedding", new float[] { 1f, 0f, 0f, 0f }));
				txn.commit();
			}
			assertEquals(List.of(good),
				ids(store.nearestByScan("Doc", "embedding", new float[] { 1f, 0f, 0f }, 10, VectorMetric.COSINE)));
		}
	}

	@Test
	public void testTheLabelNarrowsTheCandidatesAndNullScansEverything() throws Exception {
		try (GraphStore store = stores.open()) {
			long doc;
			try (Transaction txn = store.begin()) {
				doc = doc(store, "doc", 1f, 0f, 0f);
				store.createNode(List.of("Other"),
					Properties.of().set("name", "other").set("embedding", new float[] { 1f, 0f, 0f }));
				txn.commit();
			}
			float[] query = { 1f, 0f, 0f };
			assertEquals(List.of(doc), ids(store.nearestByScan("Doc", "embedding", query, 10, VectorMetric.COSINE)));
			assertEquals(2, store.nearestByScan(null, "embedding", query, 10, VectorMetric.COSINE).size(),
				"a null label scans every node instead");
			assertTrue(store.nearestByScan("Nonexistent", "embedding", query, 10, VectorMetric.COSINE).isEmpty());
		}
	}

	/** A key nothing has ever written is an empty answer, not a failure: a store with no vectors yet must still answer. */
	@Test
	public void testAnUnknownKeyIsEmptyRatherThanAFailure() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				doc(store, "doc", 1f, 0f, 0f);
				txn.commit();
			}
			assertTrue(store.nearestByScan("Doc", "never-written", new float[] { 1f, 0f, 0f }, 5, VectorMetric.COSINE)
				.isEmpty());
		}
	}

	@Test
	public void testKAndTheQueryAreValidated() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(IllegalArgumentException.class,
				() -> store.nearestByScan("Doc", "embedding", new float[] { 1f }, 0, VectorMetric.COSINE));
			assertThrows(IllegalArgumentException.class,
				() -> store.nearestByScan("Doc", "embedding", new float[0], 5, VectorMetric.COSINE));
			assertThrows(IllegalArgumentException.class,
				() -> store.nearestByScan("Doc", "embedding", null, 5, VectorMetric.COSINE));
		}
	}

	/**
	 * Ties are not a corner case — any corpus with a repeated vector produces them — and this scan is the answer an
	 * approximate index gets compared against, so it has to be the same answer every run. The lower node id wins.
	 */
	@Test
	public void testTiesBreakOnTheLowerNodeIdRatherThanOnScanOrder() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> identical = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 6; i++) {
					identical.add(doc(store, "same" + i, 1f, 0f, 0f));
				}
				txn.commit();
			}
			List<Long> ascending = new ArrayList<>(identical);
			ascending.sort(null);

			float[] query = { 1f, 0f, 0f };
			List<Long> first = ids(store.nearestByScan("Doc", "embedding", query, 3, VectorMetric.COSINE));
			assertEquals(ascending.subList(0, 3), first, "the three lowest ids, not the first three scanned");
			assertEquals(first, ids(store.nearestByScan("Doc", "embedding", query, 3, VectorMetric.COSINE)),
				"and the same three on a second call");
		}
	}

	/** A zero vector has no direction, so cosine cannot say anything about it — and must not say {@code NaN}. */
	@Test
	public void testAZeroVectorDoesNotPoisonTheOrdering() throws Exception {
		try (GraphStore store = stores.open()) {
			long real;
			try (Transaction txn = store.begin()) {
				real = doc(store, "real", 1f, 0f, 0f);
				doc(store, "zero", 0f, 0f, 0f);
				txn.commit();
			}
			List<Neighbour> near = store.nearestByScan("Doc", "embedding", new float[] { 1f, 0f, 0f }, 2,
				VectorMetric.COSINE);
			assertEquals(real, near.get(0).nodeId());
			assertEquals(0.0, near.get(1).score(), 1e-9);
			assertNotEquals(Double.NaN, near.get(1).score());
			assertEquals(0.0, Vectors.cosine(new float[] { 0f, 0f }, new float[] { 1f, 1f }), 1e-9);
		}
	}

	private static long doc(GraphStore store, String name, float... vector) throws Exception {
		return store.createNode(List.of("Doc"), Properties.of().set("name", name).set("embedding", vector));
	}

	private static List<Long> ids(List<Neighbour> neighbours) {
		List<Long> ids = new ArrayList<>(neighbours.size());
		for (Neighbour neighbour : neighbours) {
			ids.add(neighbour.nodeId());
		}
		return ids;
	}
}
