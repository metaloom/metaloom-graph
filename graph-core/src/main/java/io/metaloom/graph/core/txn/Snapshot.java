package io.metaloom.graph.core.txn;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.SnapshotTooOldException;

/**
 * One reader's fixed view: an LSN, plus the pages it has had to reconstruct to honour it.
 *
 * <p>
 * The visibility rule of [TRANSACTIONS.md §3.1](../../../../../../../../spec/TRANSACTIONS.md) is
 * {@code xmin <= snapshotTs && !inFlight.contains(xmin)}. Here it reduces to the first half alone, and the in-flight
 * set is not needed, because <b>a commit becomes visible at exactly one instant</b>: the moment its pages are copied
 * into the mapping, under the apply lock, by the single writer. There is no window in which a commit LSN has been
 * allocated but the commit is not yet observable, so there is nothing for an in-flight set to exclude. The set exists
 * in the spec because ARIES publishes a commit LSN before the data lands; no-steal does the opposite.
 * </p>
 *
 * <p>
 * <b>Thread confined.</b> The reconstruction cache below is a plain {@link HashMap} because exactly one thread ever
 * touches it. Use from another thread is rejected rather than tolerated — a shared snapshot would corrupt the cache in
 * a way that surfaces much later as a wrong answer.
 * </p>
 */
public final class Snapshot implements ReadTransaction {

	/**
	 * Raised when a page has to be rebuilt but the read is running optimistically.
	 *
	 * <p>
	 * Reconstruction reads the mapping and the version chains together, so it is only coherent while the apply step is
	 * excluded. Rather than take a lock for every read on the chance that one is needed, the optimistic attempt bails
	 * out here and the store repeats the read under the lock — which it is already able to do, because that is what it
	 * does when optimistic validation fails.
	 * </p>
	 */
	public static final class NeedsExclusionException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/**
		 * Stackless, because it is control flow rather than a fault and is caught a few frames up — but a fresh
		 * instance every time all the same.
		 * <p>
		 * A shared instance was tried and is a trap: an exception thrown out of a try-with-resources body and then
		 * again from {@code close()} is the <em>same object</em>, and {@code addSuppressed} refuses to suppress an
		 * exception into itself. The real failure is then replaced by "Self-suppression not permitted", which names
		 * nothing.
		 */
		NeedsExclusionException() {
			super("Page reconstruction requires the apply lock", null, false, false);
		}
	}

	private final long id;
	private final long snapshotLsn;
	private final IsolationLevel level;
	private final PageVersions versions;
	private final TransactionManager manager;
	private final Thread owner;
	private final long openedAtNanos;

	/** Pages already rebuilt for this view. Valid for the life of the snapshot — see {@link #pageAsOf}. */
	private final Map<Long, MemorySegment> reconstructed = new HashMap<>();

	private long reconstructedBytes;
	private boolean open = true;

	/**
	 * How many nested scopes currently exclude the apply step.
	 * <p>
	 * A depth rather than a flag: the store's read wrapper is not reentrant today, but if a read ever ran inside
	 * another the inner scope's exit would clear the outer's exclusion, and the outer would then throw
	 * {@link NeedsExclusionException} out of an attempt that was already holding the lock — a failure with nothing to
	 * catch it. Counting costs one field.
	 */
	private int exclusionDepth;

	Snapshot(long id, long snapshotLsn, IsolationLevel level, PageVersions versions, TransactionManager manager,
		long openedAtNanos) {
		this.id = id;
		this.snapshotLsn = snapshotLsn;
		this.level = level;
		this.versions = versions;
		this.manager = manager;
		this.owner = Thread.currentThread();
		this.openedAtNanos = openedAtNanos;
	}

	public long id() {
		return id;
	}

	@Override
	public long snapshotLsn() {
		return snapshotLsn;
	}

	@Override
	public IsolationLevel level() {
		return level;
	}

	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public long retainedBytes() {
		return reconstructedBytes;
	}

	public long openedAtNanos() {
		return openedAtNanos;
	}

	/** True when this view fixes what it reads. {@link IsolationLevel#READ_COMMITTED} views do not and cost nothing. */
	public boolean isVersioned() {
		return level == IsolationLevel.SNAPSHOT;
	}

	/**
	 * Tells the snapshot whether the caller currently excludes the apply step, which is what makes reconstruction
	 * coherent and cacheable.
	 */
	public void exclusionHeld(boolean held) {
		exclusionDepth += held ? 1 : -1;
	}

	void checkOwner() {
		if (Thread.currentThread() != owner) {
			throw new GraphStoreException("Read transaction " + id + " belongs to thread " + owner.getName()
				+ " and cannot be used from " + Thread.currentThread().getName()
				+ "; open one per thread instead of sharing one");
		}
	}

	/**
	 * The page as this view is entitled to see it, or null when that is the mapped page itself.
	 *
	 * <p>
	 * <b>The cache never goes stale.</b> A rebuilt image is the page as of this snapshot's LSN, and a later commit
	 * cannot change what that was: it appends a chain entry describing what it overwrote, which a rebuild from the new
	 * mapping would apply to arrive back at the same bytes. So an image is computed at most once per page per view, and
	 * a long reader converges on paying nothing.
	 * </p>
	 *
	 * @param mappedPage
	 *            supplied lazily, because on the common path it is not needed at all
	 */
	public MemorySegment pageAsOf(long pageKey, java.util.function.Supplier<MemorySegment> mappedPage, int pageSize) {
		MemorySegment cached = reconstructed.get(pageKey);
		if (cached != null) {
			return cached;
		}
		PageVersions.Version head = versions.head(pageKey);
		if (head == null || head.supersededAt() <= snapshotLsn) {
			// Nothing has superseded this page since the view began, so the mapping is already what it should see.
			return null;
		}
		if (snapshotLsn < versions.horizonLsn()) {
			throw new SnapshotTooOldException(snapshotLsn, versions.horizonLsn());
		}
		if (exclusionDepth == 0) {
			throw new NeedsExclusionException();
		}
		MemorySegment rebuilt = versions.reconstruct(pageKey, snapshotLsn, mappedPage.get(), pageSize);
		if (rebuilt == null) {
			return null;
		}
		reconstructed.put(pageKey, rebuilt);
		reconstructedBytes += pageSize;
		return rebuilt;
	}

	/**
	 * Fails the view if the versions it depends on have been reclaimed.
	 *
	 * <p>
	 * Checked once per operation rather than per page, because a view that has been abandoned by the horizon is wrong
	 * everywhere, not only on the pages it has yet to touch.
	 * </p>
	 */
	public void checkAlive() {
		checkOwner();
		if (!open) {
			throw new GraphStoreException("Read transaction " + id + " is closed");
		}
		if (isVersioned() && snapshotLsn < versions.horizonLsn()) {
			throw new SnapshotTooOldException(snapshotLsn, versions.horizonLsn());
		}
	}

	@Override
	public void close() {
		if (!open) {
			return;
		}
		open = false;
		reconstructed.clear();
		reconstructedBytes = 0;
		manager.release(this);
	}
}
