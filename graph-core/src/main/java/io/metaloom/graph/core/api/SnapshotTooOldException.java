package io.metaloom.graph.core.api;

/**
 * A snapshot outlived the versions it needed, so the view it promised can no longer be reconstructed.
 *
 * <p>
 * <b>This is a normal outcome, not a defect.</b> Retaining a snapshot costs memory in proportion to how much the store
 * changed while it was open; the store bounds that total and gives up on the oldest readers rather than growing until
 * the heap does. A caller that sees this should retry with a fresh snapshot, or raise
 * {@code snapshot.retained.bytes} if the workload genuinely needs a reader open that long.
 * </p>
 *
 * <p>
 * The alternative — returning whatever the mapping happens to hold now — would be a silent non-repeatable read inside a
 * transaction that asked for the opposite, which is the one thing a snapshot exists to prevent.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 5.1
 */
public class SnapshotTooOldException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	private final long snapshotLsn;
	private final long horizonLsn;

	public SnapshotTooOldException(long snapshotLsn, long horizonLsn) {
		super("Snapshot at LSN " + snapshotLsn + " has been reclaimed; the oldest reconstructable state is LSN "
			+ horizonLsn + ". Retry with a new snapshot, or raise the retained version budget.");
		this.snapshotLsn = snapshotLsn;
		this.horizonLsn = horizonLsn;
	}

	/** The LSN the failed snapshot was taken at. */
	public long snapshotLsn() {
		return snapshotLsn;
	}

	/** The oldest LSN any snapshot can still be reconstructed at. */
	public long horizonLsn() {
		return horizonLsn;
	}
}
