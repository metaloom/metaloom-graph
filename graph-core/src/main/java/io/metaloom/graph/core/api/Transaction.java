package io.metaloom.graph.core.api;

import java.io.IOException;

/**
 * A unit of work.
 * <p>
 * Every mutation goes into private page copies; nothing reaches the data files until {@link #commit()} has made the
 * redo log durable. A transaction that is closed without committing rolls back by discarding those copies, which costs
 * nothing.
 * <p>
 * Isolation is <b>single-writer serialisable</b>: one writer at a time, any number of concurrent readers, and readers
 * never observe a partially applied commit. Snapshot isolation with concurrent writers needs the MVCC version chains
 * of spec/TRANSACTIONS.md and is not implemented.
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a>
 */
public interface Transaction extends AutoCloseable {

	/** The transaction id, unique within the store's lifetime. */
	long id();

	/**
	 * Makes the transaction durable and applies it.
	 *
	 * @return the commit LSN, which doubles as the commit timestamp
	 */
	long commit() throws IOException;

	/** Discards every change. Idempotent. */
	void rollback();

	boolean isActive();

	/** Number of pages this transaction has modified so far. */
	int touchedPages();

	/** Rolls back if {@link #commit()} was not called. */
	@Override
	void close();
}
