package io.metaloom.graph.core.txn;

import java.io.IOException;

import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.TransactionConflictException;
import io.metaloom.graph.core.page.PageOverlay;

/**
 * The write side of a transaction: the {@link WriteContext} it runs in, plus the callbacks the store uses to commit or
 * discard it.
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 4
 */
public final class TransactionImpl implements Transaction {

	/** What the store does when a transaction ends. */
	public interface Completion {

		long commit(TransactionImpl txn) throws IOException;

		void rollback(TransactionImpl txn);
	}

	private final long id;
	private final WriteContext context;
	private final Completion completion;
	/**
	 * The thread that began it. The store's gate hold and its per-thread state belong to that thread, so a commit or
	 * rollback from anywhere else would release a lock it does not hold and clear another thread's state.
	 */
	private final Thread owner = Thread.currentThread();

	private boolean active = true;
	private long commitLsn = -1;

	public TransactionImpl(long id, int pageSize, Snapshot snapshot, IsolationLevel level, Completion completion) {
		this.id = id;
		this.context = new WriteContext(id, pageSize, snapshot, level);
		this.completion = completion;
	}

	@Override
	public long id() {
		return id;
	}

	public WriteContext context() {
		return context;
	}

	public PageOverlay overlay() {
		return context.overlay();
	}

	/** The view this transaction reads through, which is also the point its conflict detection validates against. */
	public Snapshot snapshot() {
		return context.snapshot();
	}

	@Override
	public IsolationLevel level() {
		return context.level();
	}

	@Override
	public long commit() throws IOException {
		if (!active) {
			throw new GraphStoreException("Transaction " + id + " is no longer active");
		}
		checkOwner("commit");
		Throwable failed = context.rollbackOnlyCause();
		if (failed != null) {
			// Rolled back here rather than left to close(), so the handle is over whichever way the caller goes on.
			active = false;
			completion.rollback(this);
			if (failed instanceof TransactionConflictException || failed instanceof SnapshotTooOldException) {
				// Already the answer a caller retries on, and still the true one: the statement lost a race.
				throw (GraphStoreException) failed;
			}
			throw new GraphStoreException("Transaction " + id + " is rollback-only and was rolled back: a statement in "
				+ "it failed after it had already changed the transaction (" + failed + "), and committing would have "
				+ "made half of that statement durable. Retry the whole transaction.", failed);
		}
		try {
			commitLsn = completion.commit(this);
		} finally {
			// Cleared whichever way the commit went. A conflict ends the transaction just as firmly as success does:
			// its overlay has been discarded, so letting a caller retry commit() on the same handle would commit
			// nothing and report an LSN as though it had.
			active = false;
		}
		return commitLsn;
	}

	@Override
	public void rollback() {
		if (!active) {
			return;
		}
		checkOwner("roll back");
		active = false;
		completion.rollback(this);
	}

	private void checkOwner(String action) {
		if (Thread.currentThread() != owner) {
			throw new GraphStoreException("Transaction " + id + " belongs to thread '" + owner.getName()
				+ "' and cannot " + action + " from '" + Thread.currentThread().getName() + "'");
		}
	}

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public int touchedPages() {
		return context.overlay().size();
	}

	public long commitLsn() {
		return commitLsn;
	}

	@Override
	public void close() {
		rollback();
	}
}
