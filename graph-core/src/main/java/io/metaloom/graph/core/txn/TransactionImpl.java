package io.metaloom.graph.core.txn;

import java.io.IOException;

import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.page.PageOverlay;

/**
 * The write side of a transaction: an overlay of private page copies plus the callbacks the store uses to commit or
 * discard them.
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
	private final PageOverlay overlay;
	private final Completion completion;

	private boolean active = true;
	private long commitLsn = -1;

	public TransactionImpl(long id, int pageSize, Completion completion) {
		this.id = id;
		this.overlay = new PageOverlay(pageSize);
		this.completion = completion;
	}

	@Override
	public long id() {
		return id;
	}

	public PageOverlay overlay() {
		return overlay;
	}

	@Override
	public long commit() throws IOException {
		if (!active) {
			throw new GraphStoreException("Transaction " + id + " is no longer active");
		}
		commitLsn = completion.commit(this);
		active = false;
		return commitLsn;
	}

	@Override
	public void rollback() {
		if (!active) {
			return;
		}
		active = false;
		completion.rollback(this);
	}

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public int touchedPages() {
		return overlay.size();
	}

	public long commitLsn() {
		return commitLsn;
	}

	@Override
	public void close() {
		rollback();
	}
}
