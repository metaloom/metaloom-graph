package io.metaloom.graph.core.txn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.IsolationLevel;

/**
 * The active snapshot set, the reclamation horizon, and the budget that keeps retained versions from becoming a leak.
 *
 * <p>
 * Timestamps are LSNs, as [TRANSACTIONS.md §2](../../../../../../../../spec/TRANSACTIONS.md) requires: commit order and
 * log order are the same order by construction, so there is no second counter to keep in step and recovery does not
 * have to reconstruct one.
 * </p>
 *
 * <h2>The horizon</h2>
 *
 * <p>
 * {@code oldestActiveLsn} is the minimum LSN over open {@link IsolationLevel#SNAPSHOT} views. Everything superseded at
 * or below it is dead and vacuum takes it. With no such view open the horizon is the present, so the version store
 * empties itself after every commit — which is the state a store that never opens a snapshot stays in permanently, and
 * why this whole mechanism costs nothing until someone uses it.
 * </p>
 *
 * <h2>The budget</h2>
 *
 * <p>
 * A reader that stays open while the store churns retains a version of everything the writer changed. That is the
 * standard MVCC cost and it is unbounded in principle, so it is bounded here in practice: past
 * {@code snapshot.retained.bytes} the manager abandons the oldest views, oldest first, until it is back under budget.
 * An abandoned view raises {@link io.metaloom.graph.core.api.SnapshotTooOldException} on its next operation. <b>Giving
 * a reader an error is the only honest alternative to giving it the wrong answer</b>, which is what silently falling
 * back to current data would be.
 * </p>
 */
public final class TransactionManager {

	/** Retained version bytes past which the oldest readers are abandoned. */
	public static final long DEFAULT_RETAINED_BYTES = 256L * 1024 * 1024;

	private final PageVersions versions = new PageVersions();
	private final SnapshotHolder holder = new SnapshotHolder();
	private final ConcurrentHashMap<Long, Snapshot> active = new ConcurrentHashMap<>();
	private final AtomicLong nextSnapshotId = new AtomicLong(1);
	private final AtomicLong abandoned = new AtomicLong();
	private final long retainedBytesBudget;

	public TransactionManager(long retainedBytesBudget) {
		this.retainedBytesBudget = retainedBytesBudget;
	}

	public PageVersions versions() {
		return versions;
	}

	/** Publishes the calling thread's open view to the segment layer. */
	public SnapshotHolder holder() {
		return holder;
	}

	/**
	 * Opens a view at {@code currentLsn}.
	 * <p>
	 * The caller must exclude the commit apply step across this call, so that the LSN and the registration land
	 * together; otherwise a commit could slip between them and the view would be fixed at an LSN it never saw.
	 */
	public Snapshot begin(IsolationLevel level, long currentLsn, long nowNanos) {
		Snapshot open = holder.current();
		if (open != null && open.isOpen()) {
			throw new GraphStoreException("A read transaction is already open on thread "
				+ Thread.currentThread().getName() + "; close it before opening another");
		}
		Snapshot snapshot =
			new Snapshot(nextSnapshotId.getAndIncrement(), currentLsn, level, versions, this, nowNanos);
		if (snapshot.isVersioned()) {
			// Registered before it is published, so a commit that sees it in the active set is a commit whose
			// before-images this view can already reach.
			active.put(snapshot.id(), snapshot);
		}
		holder.set(snapshot);
		return snapshot;
	}

	void release(Snapshot snapshot) {
		active.remove(snapshot.id());
		if (holder.current() == snapshot) {
			holder.clear();
		}
	}

	/** True when at least one open view needs superseded pages kept. Commit skips recording versions when false. */
	public boolean needsVersions() {
		return !active.isEmpty();
	}

	public int activeCount() {
		return active.size();
	}

	public long abandonedCount() {
		return abandoned.get();
	}

	/** The oldest LSN any open view still needs, or {@code currentLsn} when none is open. */
	public long oldestActiveLsn(long currentLsn) {
		long oldest = currentLsn;
		for (Snapshot snapshot : active.values()) {
			if (snapshot.snapshotLsn() < oldest) {
				oldest = snapshot.snapshotLsn();
			}
		}
		return oldest;
	}

	/** Nanoseconds the oldest open view has been open, or 0 when none is. */
	public long oldestActiveAgeNanos(long nowNanos) {
		long oldest = 0;
		for (Snapshot snapshot : active.values()) {
			long age = nowNanos - snapshot.openedAtNanos();
			if (age > oldest) {
				oldest = age;
			}
		}
		return oldest;
	}

	/**
	 * Reclaims what no open view needs, and abandons the oldest views if that is not enough to stay under budget.
	 *
	 * @return the bytes reclaimed
	 */
	public long vacuum(long currentLsn) {
		long reclaimed = versions.vacuum(oldestActiveLsn(currentLsn));
		if (versions.retainedBytes() <= retainedBytesBudget) {
			return reclaimed;
		}
		// Over budget. Abandon views oldest first: each one released lets the horizon move up to the next.
		List<Snapshot> oldestFirst = new ArrayList<>(active.values());
		oldestFirst.sort(Comparator.comparingLong(Snapshot::snapshotLsn));
		for (Snapshot snapshot : oldestFirst) {
			if (versions.retainedBytes() <= retainedBytesBudget) {
				break;
			}
			active.remove(snapshot.id());
			abandoned.incrementAndGet();
			reclaimed += versions.vacuum(oldestActiveLsn(currentLsn));
		}
		return reclaimed;
	}

	/** Discards every retained version. Only safe when no view is open, which the caller asserts by calling this. */
	public void clear() {
		versions.clear();
	}

	public long retainedBytesBudget() {
		return retainedBytesBudget;
	}
}
