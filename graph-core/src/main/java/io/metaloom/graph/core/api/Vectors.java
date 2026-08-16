package io.metaloom.graph.core.api;

/**
 * The three distance functions, over {@code float[]}.
 * <p>
 * They are here rather than inside the index because they are the <b>ground truth</b>: an approximate index is only
 * ever as good as the exact answer it is measured against, and the exact answer has to be computable without the index
 * existing. The brute-force scan ({@link GraphStore#nearestByScan}) and the query language's {@code vector.*} functions
 * both call these, so a recall measurement compares an index against the same arithmetic the index itself used rather
 * than against a second implementation that might differ.
 * <p>
 * <b>Every accumulator is a {@code double}.</b> Summing 768 products in a {@code float} loses several bits by the end,
 * and two vectors that should tie would then order by rounding error rather than by distance.
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
public final class Vectors {

	private Vectors() {
	}

	/** The dot product. Higher is nearer, and unlike cosine it is not bounded. */
	public static double dot(float[] a, float[] b) {
		requireComparable(a, b);
		double sum = 0;
		for (int i = 0; i < a.length; i++) {
			sum += (double) a[i] * (double) b[i];
		}
		return sum;
	}

	/**
	 * The cosine similarity, in [-1, 1]. Higher is nearer.
	 * <p>
	 * <b>A zero vector scores 0 against everything</b> rather than {@code NaN}. It has no direction, so no answer is
	 * mathematically right — but {@code NaN} propagates into every comparison that touches it and would sort a broken
	 * row into an arbitrary position instead of the bottom.
	 */
	public static double cosine(float[] a, float[] b) {
		requireComparable(a, b);
		double sum = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < a.length; i++) {
			double x = a[i];
			double y = b[i];
			sum += x * y;
			normA += x * x;
			normB += y * y;
		}
		if (normA == 0 || normB == 0) {
			return 0;
		}
		return sum / (Math.sqrt(normA) * Math.sqrt(normB));
	}

	/** The Euclidean (L2) distance. <b>Lower</b> is nearer — the opposite of the other two. */
	public static double euclidean(float[] a, float[] b) {
		requireComparable(a, b);
		double sum = 0;
		for (int i = 0; i < a.length; i++) {
			double d = (double) a[i] - (double) b[i];
			sum += d * d;
		}
		return Math.sqrt(sum);
	}

	/** The L2 norm of one vector. */
	public static double norm(float[] vector) {
		double sum = 0;
		for (float component : vector) {
			sum += (double) component * (double) component;
		}
		return Math.sqrt(sum);
	}

	/**
	 * Two vectors of different lengths are not far apart — they are incomparable, and there is no defensible number to
	 * return for them.
	 */
	private static void requireComparable(float[] a, float[] b) {
		if (a == null || b == null) {
			throw new IllegalArgumentException("A distance needs two vectors");
		}
		if (a.length != b.length) {
			throw new IllegalArgumentException(
				"Vectors of " + a.length + " and " + b.length + " dimensions cannot be compared");
		}
	}
}
