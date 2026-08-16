package io.metaloom.graph.core.api;

/**
 * Which notion of "near" a vector search uses.
 * <p>
 * The choice belongs to the model that produced the vectors, not to the store: cosine, dot product and L2 give
 * different neighbours for the same data, and a model trained against one of them means nothing under another.
 * <p>
 * <b>The two directions are the trap this enum exists to close.</b> Cosine and dot product are similarities — higher is
 * nearer. L2 is a distance — lower is nearer. Ranking code that hard-codes either one is wrong for the other, so it
 * ranks on {@link #rank(float[], float[])}, which is always "lower is nearer", and reports
 * {@link #score(float[], float[])}, which is always the metric's natural value.
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
public enum VectorMetric {

	/** Cosine similarity, in [-1, 1]. The usual choice for text and image embeddings. */
	COSINE,

	/** Unbounded dot product. Right when the model's vectors carry magnitude that means something. */
	DOT,

	/** Euclidean distance. The only one of the three where a smaller number is a better match. */
	EUCLIDEAN;

	/** The metric's natural value: a similarity for {@link #COSINE} and {@link #DOT}, a distance for {@link #EUCLIDEAN}. */
	public double score(float[] a, float[] b) {
		return switch (this) {
			case COSINE -> Vectors.cosine(a, b);
			case DOT -> Vectors.dot(a, b);
			case EUCLIDEAN -> Vectors.euclidean(a, b);
		};
	}

	/**
	 * The ranking key, where <b>lower is always nearer</b>, whichever metric this is. Never shown to a caller — it
	 * exists so that one comparator serves all three.
	 * <p>
	 * This computes the distance. A caller that also wants the {@link #score} should compute that instead and flip its
	 * sign itself when {@link #higherIsNearer}, rather than calling both and paying for the arithmetic twice.
	 */
	public double rank(float[] a, float[] b) {
		double score = score(a, b);
		return higherIsNearer() ? -score : score;
	}

	/** Whether a larger {@link #score} means a nearer vector. False only for {@link #EUCLIDEAN}. */
	public boolean higherIsNearer() {
		return this != EUCLIDEAN;
	}
}
