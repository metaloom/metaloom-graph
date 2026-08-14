package io.metaloom.graph.core.txn;

/**
 * Publishes a thread's open read view to the segment layer.
 *
 * <p>
 * The counterpart of {@link io.metaloom.graph.core.page.OverlayHolder}, and deliberately not the same shape. An overlay
 * belongs to the one writer, so a single owner field suffices. Read views are the opposite case — many threads, each
 * with its own — so this is a {@link ThreadLocal}.
 * </p>
 *
 * <p>
 * The thread-local is only ever set while a view is open and is removed on close, so a pooled thread does not carry a
 * stale view into its next task.
 * </p>
 */
public final class SnapshotHolder {

	private final ThreadLocal<Snapshot> current = new ThreadLocal<>();

	public void set(Snapshot snapshot) {
		current.set(snapshot);
	}

	public void clear() {
		current.remove();
	}

	/** The calling thread's open view, or null. */
	public Snapshot current() {
		return current.get();
	}

	/** The calling thread's view when it fixes what it reads; null for none and for read-committed views. */
	public Snapshot versioned() {
		Snapshot snapshot = current.get();
		return snapshot != null && snapshot.isOpen() && snapshot.isVersioned() ? snapshot : null;
	}
}
