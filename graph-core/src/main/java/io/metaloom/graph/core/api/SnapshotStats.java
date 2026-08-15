package io.metaloom.graph.core.api;

/**
 * What snapshot isolation currently costs.
 *
 * <p>
 * Every field is zero on a store where nobody has opened a {@link IsolationLevel#SNAPSHOT} view, which is the state a
 * write-only or read-committed workload stays in permanently. The two to watch are {@link #retainedBytes()}, which is
 * the memory an open reader is holding, and {@link #oldestOpenAgeMillis()}, which is the reader holding it.
 * </p>
 *
 * @param openViews
 *            snapshot views currently open
 * @param oldestOpenLsn
 *            the LSN of the oldest open view, or the current LSN when none is open — this is the reclamation horizon
 * @param oldestOpenAgeMillis
 *            how long the oldest open view has been open. A large value with a large {@code retainedBytes} names the
 *            leak
 * @param retainedBytes
 *            superseded page content held so that open views can still be reconstructed
 * @param retainedEntries
 *            individual overwritten byte runs retained
 * @param versionedPages
 *            distinct pages carrying at least one retained version
 * @param abandonedViews
 *            views given up on since the store opened because retention exceeded its budget. Anything above zero means
 *            some reader saw {@link SnapshotTooOldException}
 */
public record SnapshotStats(
	int openViews,
	long oldestOpenLsn,
	long oldestOpenAgeMillis,
	long retainedBytes,
	long retainedEntries,
	int versionedPages,
	long abandonedViews) {

	public static final SnapshotStats NONE = new SnapshotStats(0, 0, 0, 0, 0, 0, 0);
}
