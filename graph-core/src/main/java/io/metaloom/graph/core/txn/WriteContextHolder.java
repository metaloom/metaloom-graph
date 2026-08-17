package io.metaloom.graph.core.txn;

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
 */
public final class WriteContextHolder {

	private final ThreadLocal<WriteContext> current = new ThreadLocal<>();

	public void set(WriteContext context) {
		current.set(context);
	}

	public void clear() {
		current.remove();
	}

	/** The write transaction running on the calling thread, or null. */
	public WriteContext current() {
		return current.get();
	}
}
