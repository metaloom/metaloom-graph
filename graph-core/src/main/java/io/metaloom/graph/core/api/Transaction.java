package io.metaloom.graph.core.api;

import java.io.IOException;

/**
 * A unit of work.
 * <p>
 * Every mutation goes into private page copies; nothing reaches the data files until {@link #commit()} has made the
 * redo log durable. A transaction that is closed without committing rolls back by discarding those copies, which costs
 * nothing.
 * <p>
 * <b>Writers run concurrently.</b> Each reads a view fixed when it began and keeps its changes private until it
 * commits, so two of them can decide different things about the same bytes; the first to commit keeps its decision and
 * the second is refused with {@link TransactionConflictException}. That is snapshot isolation, and
 * {@link IsolationLevel#SERIALIZABLE} adds validation of what the transaction read on top of it. Readers are never
 * blocked by either, and never observe a partially applied commit.
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a>
 */
public interface Transaction extends AutoCloseable {

	/** The transaction id, unique within the store's lifetime. */
	long id();

	/** What this transaction is validated against at commit. */
	IsolationLevel level();

	/**
	 * Makes the transaction durable and applies it.
	 *
	 * @return the commit LSN, which doubles as the commit timestamp
	 * @throws TransactionConflictException
	 *             if another transaction changed something this one depended on. The transaction is over either way;
	 *             retry it, which is what {@link GraphStore#inTransaction} does.
	 * @throws GraphStoreException
	 *             if the transaction is <b>rollback-only</b>: a statement in it failed after it had already started
	 *             changing the transaction, so part of that statement is in it and nothing can take that part out
	 *             again. The transaction is rolled back. A statement refused before it wrote anything — every unique
	 *             constraint violation is — leaves the transaction usable instead.
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
