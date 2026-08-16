package io.metaloom.graph.core.api;

/**
 * A declared approximate-nearest-neighbour index.
 *
 * <p>
 * Unlike the label and property indexes, which maintain themselves for everything, this one has to be declared. Three
 * reasons, each decisive on its own: the dimension has to be pinned, because an index over vectors of mixed length is
 * not an index; the metric has to be chosen, because cosine, dot product and L2 give different neighbours and the
 * choice belongs to the model that produced the vectors; and building the structure is expensive enough that no
 * unrelated bulk load should pay for one nobody asked for.
 * </p>
 *
 * <p>
 * <b>The model is part of the identity, not decoration.</b> Two models produce vectors of the same length in the same
 * datatype and mean completely different things by them, so a distance between them is a plausible-looking number with
 * no meaning at all. Recording it is what makes a model upgrade a re-index rather than a migration: the new index is
 * declared beside the old, both answer, and the old is dropped once its replacement has been shown to be better.
 * </p>
 *
 * @param label
 *            the node label whose members this indexes
 * @param key
 *            the property key holding the vector
 * @param dimensions
 *            fixed; a write of any other length is refused rather than silently skipped
 * @param metric
 *            fixed; a query under a different one would be answered from the wrong neighbourhood
 * @param model
 *            free text naming what produced the vectors, or empty when the application takes that responsibility
 * @param m
 *            links per node per layer above the base; the base layer gets {@code 2m}. Higher is better recall and more
 *            space, and it is capped by what the record's stride left room for
 * @param efConstruction
 *            how wide the search runs while inserting. Higher is a better graph and a slower build
 * @param state
 *            whether the structure is usable yet
 */
public record VectorIndexDefinition(String label, String key, int dimensions, VectorMetric metric, String model,
	int m, int efConstruction, VectorIndexState state) {

	/** The default link budget. 16 is the usual starting point and is what the record stride is sized around. */
	public static final int DEFAULT_M = 16;

	/** The default construction breadth. */
	public static final int DEFAULT_EF_CONSTRUCTION = 200;

	public VectorIndexDefinition {
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("A vector index needs a label");
		}
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("A vector index needs a property key");
		}
		if (dimensions <= 0) {
			throw new IllegalArgumentException("A vector index needs a positive dimension count, got " + dimensions);
		}
		if (m <= 0 || efConstruction <= 0) {
			throw new IllegalArgumentException("m and efConstruction must be positive");
		}
		java.util.Objects.requireNonNull(metric, "metric");
		java.util.Objects.requireNonNull(state, "state");
		// An unknown model is the empty string rather than null, so the record stays usable as a map key without
		// every caller having to null-check it.
		model = model == null ? "" : model;
	}

	/** A declaration with the default tuning, which is what most callers want. */
	public static VectorIndexDefinition of(String label, String key, int dimensions, VectorMetric metric) {
		return new VectorIndexDefinition(label, key, dimensions, metric, "", DEFAULT_M, DEFAULT_EF_CONSTRUCTION,
			VectorIndexState.BUILDING);
	}

	/** Whether this declaration covers that label and key. The pair is the index's address. */
	public boolean covers(String label, String key) {
		return this.label.equals(label) && this.key.equals(key);
	}
}
