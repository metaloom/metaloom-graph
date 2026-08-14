package io.metaloom.graph.core.api;

/**
 * What a read transaction is allowed to observe.
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 3
 */
public enum IsolationLevel {

	/**
	 * Every operation sees the latest committed state, so two reads of the same record inside one transaction may
	 * disagree.
	 *
	 * <p>
	 * This is what the store did before snapshots existed, and it remains the cheapest thing it can do: no version is
	 * retained on the reader's behalf, and the reader does not hold back reclamation. Choose it when the transaction
	 * exists to group writes rather than to fix a view.
	 * </p>
	 */
	READ_COMMITTED,

	/**
	 * Every operation sees the state as of the instant the transaction began, however long it runs and however many
	 * commits land underneath it.
	 *
	 * <p>
	 * The cost is memory: the versions a snapshot needs are retained until it closes, which is why a forgotten
	 * transaction is a leak and why the store bounds the total and raises {@link SnapshotTooOldException} rather than
	 * growing without limit.
	 * </p>
	 */
	SNAPSHOT
}
