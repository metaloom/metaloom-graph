package io.metaloom.graph.core.api;

/**
 * What a transaction is allowed to observe, and — for a write transaction — what it is allowed to get away with.
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
	 *
	 * <p>
	 * For a <b>write</b> transaction this is snapshot isolation in the textbook sense: the transaction reads a fixed
	 * state, and at commit any of its writes that another transaction has already changed since raises
	 * {@link TransactionConflictException}. First committer wins. It prevents dirty reads, non-repeatable reads,
	 * phantoms and lost updates, and — as snapshot isolation always does — it permits <b>write skew</b>, where two
	 * transactions each read what the other is about to change and both commit. {@link #SERIALIZABLE} is the level that
	 * rules that out.
	 * </p>
	 */
	SNAPSHOT,

	/**
	 * As {@link #SNAPSHOT}, and additionally the transaction is refused if anything it <em>read</em> changed underneath
	 * it. Write transactions only.
	 *
	 * <p>
	 * That extra check is what closes write skew: a transaction that decided something from a page another transaction
	 * has since committed over did not run at any single instant, so it is aborted rather than committed. The
	 * validation is backward — at commit, against the pages the reader touched — rather than the dangerous-structure
	 * detection of SSI. The guarantee is the same and the machinery is a set of page keys the engine is already
	 * computing, where SSI would need an in-conflict and out-conflict flag per transaction and a second pass to find
	 * the pivot.
	 * </p>
	 *
	 * <p>
	 * <b>It aborts more than SSI would, and the reason is granularity.</b> Read tracking here is per page, so two
	 * transactions touching unrelated records that happen to share one are a conflict. That is sound — never a wrong
	 * answer, only a retry — but it makes this a level to ask for where it is needed rather than to default to. A read
	 * transaction opened at this level behaves as {@link #SNAPSHOT}, which is what serializability means for something
	 * that writes nothing.
	 * </p>
	 *
	 * <p>
	 * <b>A lookup of a label, relationship type or property key the store has never seen is a read too</b>, though it
	 * touches no page: {@code nodesByLabel("Doctor")} on a store without the label answers from the dictionary alone.
	 * The commit refuses the transaction if any such name has come to exist since. The dictionary does not record who
	 * introduced a name, so a transaction that looked a name up, found nothing and then introduced it itself is refused
	 * once; its retry finds the name and validates as usual.
	 * </p>
	 */
	SERIALIZABLE
}
