package io.metaloom.graph.core.api;

/**
 * One result of a vector search: the node, and how near it was.
 *
 * @param nodeId
 *            the node carrying the vector — not the vector's own id, which is internal
 * @param score
 *            the metric's natural value, as {@link VectorMetric#score}. A similarity for {@code COSINE} and
 *            {@code DOT}, a distance for {@code EUCLIDEAN}. Results are always ordered nearest first regardless of
 *            which, so <b>the list order is the answer and the score is the explanation</b>: never re-sort a result
 *            list on this field without consulting {@link VectorMetric#higherIsNearer}.
 */
public record Neighbour(long nodeId, double score) {
}
