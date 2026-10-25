package io.metaloom.graph.core.maintenance;

import java.io.IOException;

/**
 * How a background maintenance task gets its work into a transaction.
 * <p>
 * Compaction deliberately does <em>not</em> exclude writers for its whole run. It takes the exclusion one batch at a
 * time, so a foreground writer only ever waits for a single batch rather than for a whole segment. That, plus a configurable
 * pause between batches, is the entire scheduling policy: there is no background thread and no priority scheme, and the
 * caller stays in control of when compaction runs.
 *
 * @see <a href="../../../../../../../../spec/PLAN.md">spec/PLAN.md</a> Phase 8
 */
public interface MaintenanceRunner {

	/** Runs the unit in its own auto-commit transaction, or joins the caller's if one is already open. */
	void runWrite(Unit unit) throws IOException;

	/**
	 * Runs the unit in a transaction with every other writer excluded.
	 *
	 * <p>
	 * Compaction needs this and ordinary writes do not, and the difference is what the unit is doing. A foreground
	 * write changes a record and is validated against anybody who changed the same bytes. A compaction batch
	 * <em>relocates</em> records: it reads one slot and writes another, so a concurrent writer updating the source
	 * would have its update copied from a stale image and then have the source freed underneath it — two disjoint byte
	 * ranges, nothing for conflict detection to see, and a lost update. Reclaiming pages, pulling down a high water
	 * mark and retiring a segment file are worse still, because they change what the addressing means.
	 * </p>
	 *
	 * <p>
	 * The exclusion is per batch, not per run, which is the same scheduling policy compaction has always had: a
	 * foreground writer waits for one batch rather than for a whole segment.
	 * </p>
	 */
	void runExclusive(Unit unit) throws IOException;

	/**
	 * Runs the unit with every other writer excluded and <b>outside any transaction</b>, refusing while a read view is
	 * open.
	 *
	 * <p>
	 * For work whose effect on the file is immediate and cannot be staged: punching a hole. Inside a transaction the
	 * generation floors and checksums describing the punch went to the journal and were only durable at its commit,
	 * while the hole was already in the file, so a crash in between lost the floors that keep stale ids stale. Here they
	 * are written to the mapping and forced in the order the unit chooses. A view is refused because a punched page is
	 * the base its reconstruction would start from.
	 * </p>
	 *
	 * <p>
	 * <b>A checkpoint runs first</b>, under the same exclusion. Recovery replays byte-run differences, and replaying
	 * one logged before an unlogged write over the file after it puts back bytes that no later record restores — so the
	 * replay origin has to be past every record before the unit touches the file.
	 * </p>
	 */
	void runUnlogged(Unit unit) throws IOException;

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
