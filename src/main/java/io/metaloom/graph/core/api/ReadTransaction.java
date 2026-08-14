package io.metaloom.graph.core.api;

/**
 * A fixed view of the store, held open across many reads.
 *
 * <p>
 * Every read method on {@link GraphStore} joins the calling thread's read transaction if one is open, exactly as every
 * write method joins its write transaction. So a snapshot is opened once and the ordinary API is used unchanged:
 * </p>
 *
 * <pre>{@code
 * try (ReadTransaction view = store.beginRead()) {
 *     List<Edge> edges = store.traverse(start, Direction.OUT, null, 6);
 *     // Writers commit freely underneath; this view does not move.
 *     for (Edge edge : edges) {
 *         Properties props = store.nodeProperties(edge.toId());
 *     }
 * }
 * }</pre>
 *
 * <p>
 * <b>Thread confined.</b> The view is published to the thread that opened it and to no other, which is what lets the
 * reconstruction cache below it be free of synchronisation. Passing one between threads is a programming error and is
 * detected rather than silently ignored.
 * </p>
 *
 * <p>
 * <b>Close it.</b> Under {@link IsolationLevel#SNAPSHOT} an open view holds back reclamation of every version committed
 * since it began, so a leaked one is a memory leak with a name. The store reports the oldest open view's age in
 * {@link StoreStats} and gives up on it — with {@link SnapshotTooOldException} — before the heap does.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 3
 */
public interface ReadTransaction extends AutoCloseable {

	/** The LSN this view is fixed at. Under {@link IsolationLevel#READ_COMMITTED} it is the LSN at begin, and unused. */
	long snapshotLsn();

	IsolationLevel level();

	/** False once {@link #close()} has run. */
	boolean isOpen();

	/**
	 * Bytes of superseded page content retained on this view's behalf.
	 * <p>
	 * Shared with every other open view, so the sum across views over-counts; it is the per-view figure that says which
	 * reader is expensive.
	 */
	long retainedBytes();

	@Override
	void close();
}
