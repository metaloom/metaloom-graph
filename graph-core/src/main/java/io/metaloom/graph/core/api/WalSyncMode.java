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
	 * At most one fsync per interval; an idle log is forced within the interval by a background flusher. A process
	 * crash loses nothing the kernel was handed. A <b>power loss</b> may discard commits from the last interval, and
	 * because a commit is applied to the mapped data files before its log is forced, it can also leave a transaction
	 * torn across pages — spec/WAL.md section 4. See {@link GraphStoreOptions#walSyncIntervalMillis()}.
	 */
	GROUP_COMMIT,

	/**
	 * Never fsync explicitly; rely on the operating system. Bulk import and tests only. Survives a process crash; after a
	 * power loss it has the same torn-transaction exposure as {@link #GROUP_COMMIT}, over an unbounded window.
	 */
	ASYNC
}
