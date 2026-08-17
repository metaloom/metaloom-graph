package io.metaloom.graph.core.api;

/**
 * Two write transactions overlapped, and this one lost.
 *
 * <p>
 * <b>A normal outcome, and the price of concurrent writers.</b> Writers used to be serialised by one lock, so this
 * exception could not exist: a second writer waited rather than conflicting. They now run at the same time, each
 * reading a fixed view and each keeping its changes private until it commits, which means two of them can decide
 * different things about the same bytes. The commit that gets there first keeps its decision; the second is told, here,
 * rather than being allowed to overwrite a state it never read.
 * </p>
 *
 * <p>
 * <b>Retry it.</b> {@link GraphStore#inTransaction} does exactly that, and a conflict is the case it was written for —
 * unlike {@link ConstraintViolationException}, which is about data that has not changed, or
 * {@link SnapshotTooOldException}, which is about a reader that no longer exists. Progress is guaranteed because a
 * conflict always has a winner: something committed, so a retry runs against a newer state and one fewer competitor.
 * </p>
 *
 * <p>
 * The message names the page and the byte range, because "a conflict occurred" tells nobody which of their
 * transactions to restructure. {@link #isReadConflict()} separates the two causes: a write-write conflict means both
 * transactions changed the same bytes, and a read conflict — {@link IsolationLevel#SERIALIZABLE} only — means this one
 * decided something from a page that has since changed.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 6
 */
public class TransactionConflictException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	private final long txnId;
	private final long pageKey;
	private final long snapshotLsn;
	private final long conflictingLsn;
	private final boolean readConflict;

	public TransactionConflictException(long txnId, long pageKey, long snapshotLsn, long conflictingLsn,
		boolean readConflict, String what) {
		super("Transaction " + txnId + " conflicts with the commit at LSN " + conflictingLsn + ": " + what
			+ " was changed after this transaction's snapshot at LSN " + snapshotLsn
			+ (readConflict
				? ". It was read rather than written, so this is a serialization failure rather than a lost update."
				: ". First committer wins.")
			+ " Retry the transaction; inTransaction() does that for you.");
		this.txnId = txnId;
		this.pageKey = pageKey;
		this.snapshotLsn = snapshotLsn;
		this.conflictingLsn = conflictingLsn;
		this.readConflict = readConflict;
	}

	/**
	 * A conflict the engine recognised structurally rather than by comparing byte ranges.
	 *
	 * <p>
	 * There is one: two transactions racing to create the index catalog page, which has to be the first INDEX
	 * allocation and therefore cannot be built twice. The loser finds out before it reaches the commit, so there are no
	 * LSNs to report — but it is the same failure and takes the same retry.
	 * </p>
	 */
	public TransactionConflictException(long txnId, String what) {
		super("Transaction " + txnId + " conflicts with another: " + what
			+ ". Retry the transaction; inTransaction() does that for you.");
		this.txnId = txnId;
		this.pageKey = 0;
		this.snapshotLsn = -1;
		this.conflictingLsn = -1;
		this.readConflict = false;
	}

	public long txnId() {
		return txnId;
	}

	/** The page the two transactions met on, as {@code io.metaloom.graph.core.page.PageOverlay.key}. */
	public long pageKey() {
		return pageKey;
	}

	public long snapshotLsn() {
		return snapshotLsn;
	}

	/** The LSN of the commit that got there first. */
	public long conflictingLsn() {
		return conflictingLsn;
	}

	/** True when this transaction only read the contested page; false when it wrote it. */
	public boolean isReadConflict() {
		return readConflict;
	}
}
