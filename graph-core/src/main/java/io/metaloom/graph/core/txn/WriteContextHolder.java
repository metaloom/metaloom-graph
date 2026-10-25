package io.metaloom.graph.core.txn;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes the write transaction running on a thread to the segment layer.
 *
 * <p>
 * This used to be an owner field and a volatile overlay reference, which was enough while the engine had one writer:
 * whoever held the writer lock was the writer, and every other thread read the mapping. With concurrent writers there
 * is no such thread, so this is a {@link ThreadLocal} — the same shape {@link SnapshotHolder} has always had, for the
 * same reason.
 * </p>
 *
 * <p>
 * The thread-local is removed rather than nulled when a transaction ends, so a pooled thread does not carry a dead
 * overlay into its next task.
 * </p>
 *
 * <h2>The count in front of the thread-local</h2>
 *
 * <p>
 * Every record read asks this holder whether its thread is writing, and a read by id asks it three or four times. A
 * thread-local lookup is cheap but not free, and on the read path it was measured at a third of
 * {@code nodeProperties}. So the holder also counts how many threads currently have a context published, and answers
 * null from that one read when the count is zero — which is every read made while no transaction is open anywhere.
 * </p>
 *
 * <p>
 * That is safe because a thread's own publication is ordered before its own reads: a thread that has published sees
 * a count of at least one, whatever other threads do, since each thread only ever takes back its own increment. The
 * pairing is kept exact by counting transitions of the thread-local rather than calls, so a stray {@link #clear} can
 * never lower the count below the number of contexts actually published.
 * </p>
 */
public final class WriteContextHolder {

	private final ThreadLocal<WriteContext> current = new ThreadLocal<>();

	/** How many threads have a context published right now. See the class comment. */
	private final AtomicInteger published = new AtomicInteger();

	public void set(WriteContext context) {
		if (context == null) {
			clear();
			return;
		}
		if (current.get() == null) {
			// Before the thread-local, so the count is never lower than what is published.
			published.incrementAndGet();
		}
		current.set(context);
	}

	public void clear() {
		if (current.get() != null) {
			current.remove();
			published.decrementAndGet();
		}
	}

	/** The write transaction running on the calling thread, or null. */
	public WriteContext current() {
		return published.get() == 0 ? null : current.get();
	}

	/**
	 * False when no thread has a write transaction open, so nothing on the calling thread can be either. True means
	 * only that some thread might; {@link #current} gives the answer for this one.
	 */
	public boolean anyPublished() {
		return published.get() != 0;
	}
}
