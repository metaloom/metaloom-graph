package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.Vectors;

/**
 * The three distance functions, on their own.
 *
 * <p>
 * They are worth a unit test rather than only the end-to-end coverage because everything above them — the brute-force
 * scan, the query language's {@code vector.*} functions, and later an approximate index measured against all of it —
 * assumes they agree. A recall figure computed against arithmetic that is subtly wrong is a confident number about
 * nothing.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
@Tag("unit")
public class VectorsTest {

	@Test
	public void testDot() {
		assertEquals(11.0, Vectors.dot(new float[] { 1f, 2f }, new float[] { 3f, 4f }), 1e-9);
		assertEquals(0.0, Vectors.dot(new float[] { 1f, 0f }, new float[] { 0f, 1f }), 1e-9);
		assertEquals(-1.0, Vectors.dot(new float[] { 1f }, new float[] { -1f }), 1e-9);
	}

	@Test
	public void testCosineIgnoresMagnitude() {
		assertEquals(1.0, Vectors.cosine(new float[] { 1f, 0f }, new float[] { 7f, 0f }), 1e-9);
		assertEquals(0.0, Vectors.cosine(new float[] { 1f, 0f }, new float[] { 0f, 3f }), 1e-9);
		assertEquals(-1.0, Vectors.cosine(new float[] { 1f, 1f }, new float[] { -2f, -2f }), 1e-9);
	}

	/**
	 * No answer is mathematically right for a vector with no direction, but {@code NaN} is the wrong wrong answer: it
	 * propagates through every comparison that touches it, so one broken row sorts arbitrarily rather than last.
	 */
	@Test
	public void testAZeroVectorScoresZeroRatherThanNaN() {
		double score = Vectors.cosine(new float[] { 0f, 0f }, new float[] { 1f, 1f });
		assertEquals(0.0, score, 1e-9);
		assertTrue(Double.isFinite(score));
		assertTrue(Double.isFinite(Vectors.cosine(new float[] { 0f }, new float[] { 0f })));
	}

	@Test
	public void testEuclidean() {
		assertEquals(5.0, Vectors.euclidean(new float[] { 0f, 0f }, new float[] { 3f, 4f }), 1e-9);
		assertEquals(0.0, Vectors.euclidean(new float[] { 1f, 2f }, new float[] { 1f, 2f }), 1e-9);
		assertEquals(5.0, Vectors.norm(new float[] { 3f, 4f }), 1e-9);
	}

	/** Two vectors of different lengths are not far apart, they are incomparable. */
	@Test
	public void testDifferentLengthsAreRefused() {
		assertTrue(assertThrows(IllegalArgumentException.class,
			() -> Vectors.cosine(new float[] { 1f }, new float[] { 1f, 2f })).getMessage().contains("cannot be compared"));
		assertThrows(IllegalArgumentException.class, () -> Vectors.dot(new float[] { 1f }, new float[] { 1f, 2f }));
		assertThrows(IllegalArgumentException.class, () -> Vectors.euclidean(new float[] { 1f }, new float[0]));
	}

	/**
	 * The one property every ranking above this class depends on: {@code rank} is smaller for a nearer vector,
	 * whichever metric it is, even though {@code score} runs the other way for two of the three.
	 */
	@Test
	public void testRankIsAlwaysLowerIsNearer() {
		float[] query = { 1f, 0f };
		float[] near = { 0.9f, 0.1f };
		float[] far = { -1f, 0f };
		for (VectorMetric metric : VectorMetric.values()) {
			assertTrue(metric.rank(query, near) < metric.rank(query, far),
				metric + " must rank the nearer vector lower");
		}
		assertTrue(VectorMetric.COSINE.score(query, near) > VectorMetric.COSINE.score(query, far),
			"while cosine's own score runs the other way");
		assertTrue(VectorMetric.EUCLIDEAN.score(query, near) < VectorMetric.EUCLIDEAN.score(query, far),
			"and euclidean's does not");
		assertTrue(VectorMetric.COSINE.higherIsNearer());
		assertTrue(VectorMetric.DOT.higherIsNearer());
		assertTrue(!VectorMetric.EUCLIDEAN.higherIsNearer());
	}

	/**
	 * Every accumulator is a double on purpose. Summed in a float, 768 products of this size lose enough of the tail
	 * that two vectors which should tie would order by rounding error instead.
	 */
	@Test
	public void testTheAccumulatorIsWideEnoughToBeStable() {
		float[] a = new float[768];
		float[] b = new float[768];
		java.util.Arrays.fill(a, 0.1f);
		java.util.Arrays.fill(b, 0.1f);
		assertEquals(768 * 0.1 * 0.1, Vectors.dot(a, b), 1e-6);
		assertEquals(1.0, Vectors.cosine(a, b), 1e-9);
	}
}
