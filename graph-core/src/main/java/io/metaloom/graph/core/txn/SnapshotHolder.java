package io.metaloom.graph.core.txn;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes a thread's open read view to the segment layer.
 *
 * <p>
 * The counterpart of {@link WriteContextHolder}, and now the same shape. It was not always: while the engine had one
 * writer at a time an overlay could be published through a single owner field, and read views were the only thing that
 * needed one per thread. Concurrent writers made both sides the same problem.
 * </p>
 *
 * <p>
 * The thread-local is only ever set while a view is open and is removed on close, so a pooled thread does not carry a
 * stale view into its next task.
 * </p>
 *
 * <p>
 * Like {@link WriteContextHolder} it counts the threads with a view published and answers null without touching the
 * thread-local while that count is zero, which is every read made outside an explicit read transaction while nobody
 * holds one. The reasoning that makes this safe is the same and is written down there.
 * </p>
 */
public final class SnapshotHolder {

	private final ThreadLocal<Snapshot> current = new ThreadLocal<>();

	/** How many threads have a view published right now. */
	private final AtomicInteger published = new AtomicInteger();

	public void set(Snapshot snapshot) {
		if (snapshot == null) {
			clear();
			return;
		}
		if (current.get() == null) {
			published.incrementAndGet();
		}
		current.set(snapshot);
	}

	public void clear() {
		if (current.get() != null) {
			current.remove();
			published.decrementAndGet();
		}
	}

	/** The calling thread's open view, or null. */
	public Snapshot current() {
		return published.get() == 0 ? null : current.get();
	}

	/** The calling thread's view when it fixes what it reads; null for none and for read-committed views. */
	public Snapshot versioned() {
		Snapshot snapshot = current();
		return snapshot != null && snapshot.isOpen() && snapshot.isVersioned() ? snapshot : null;
	}
}
