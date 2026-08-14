package io.metaloom.graph.core.api;

/**
 * How deeply {@link GraphStore#verify(VerifyLevel)} looks.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 5
 */
public enum VerifyLevel {

	/** Page checksums and slot accounting. Cheap enough to run online. */
	CHECKSUMS,

	/**
	 * Everything in {@link #CHECKSUMS} plus the structural invariants: bidirectional adjacency, degree counters,
	 * no dangling references and no orphaned property blocks. Walks every live element.
	 */
	FULL
}
