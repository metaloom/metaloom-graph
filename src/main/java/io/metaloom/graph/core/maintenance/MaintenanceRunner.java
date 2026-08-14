package io.metaloom.graph.core.maintenance;

import java.io.IOException;

/**
 * How a background maintenance task gets its work into a transaction.
 * <p>
 * Compaction deliberately does <em>not</em> hold the writer lock for its whole run. It takes it one batch at a time, so
 * a foreground writer only ever waits for a single batch rather than for a whole segment. That, plus a configurable
 * pause between batches, is the entire scheduling policy: there is no background thread and no priority scheme, and the
 * caller stays in control of when compaction runs.
 *
 * @see <a href="../../../../../../../../spec/PLAN.md">spec/PLAN.md</a> Phase 8
 */
public interface MaintenanceRunner {

	/** Runs the unit in its own auto-commit transaction, or joins the caller's if one is already open. */
	void runWrite(Unit unit) throws IOException;

	/** Runs the unit under a read latch, so it never observes half of a commit. */
	void runRead(Unit unit) throws IOException;

	/**
	 * Forces every data file and trims the log.
	 * <p>
	 * Compaction needs this before it unlinks a segment file: the log holds page images addressed by segment number, and
	 * replaying one for a file that has been deleted is unrecoverable. A checkpoint moves the replay origin past every
	 * such record.
	 */
	void runCheckpoint() throws IOException;

	@FunctionalInterface
	interface Unit {
		void run() throws IOException;
	}
}
