package io.metaloom.graph.core.api;

/**
 * How hard a commit works to be durable.
 * <p>
 * The ordering rules are identical in every mode: the log record is always written before the page it describes is
 * applied, so the store is always recoverable to <em>some</em> consistent point. What varies is how much recent work a
 * power loss can discard.
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 4
 */
public enum WalSyncMode {

	/** Every commit is fsynced. No committed transaction is ever lost. The default, and the only safe production mode. */
	FSYNC_ON_COMMIT,

	/**
	 * At most one fsync per interval. A power loss may discard commits from the last interval, but never corrupts the
	 * store. See {@link GraphStoreOptions#walSyncIntervalMillis()}.
	 */
	GROUP_COMMIT,

	/**
	 * Never fsync explicitly; rely on the operating system. Bulk import and tests only. The store still recovers to a
	 * consistent point, it is simply an older one.
	 */
	ASYNC
}
