package io.metaloom.graph.core.api;

/**
 * How far along a declared vector index is.
 *
 * <p>
 * The distinction exists because "no index" and "no matches" are opposite answers that must never look the same to a
 * caller. A query against an index that is still building is refused by name rather than answered from a structure
 * that only holds part of the data — an empty result there would be a confident lie.
 * </p>
 */
public enum VectorIndexState {

	/** Declared, and its members are being collected. Queries are refused rather than partially answered. */
	BUILDING,

	/** Every member is in the structure and maintained. Queries are answered. */
	READY
}
