package io.metaloom.graph.core.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.metaloom.graph.core.api.StoreFormat;

/**
 * One migration step: takes a closed store from store format version {@link #from()} to {@code from() + 1}.
 *
 * <h2>The contract</h2>
 *
 * <ul>
 * <li><b>It runs on a copy.</b> {@link MigrationContext#store()} is the runner's working copy, never the original, so a
 * step may rewrite files in place, create and delete them, and fail half way: the copy is thrown away.</li>
 * <li><b>It runs on a cleanly closed store.</b> There is no log to replay — the runner refuses a store that has one
 * and does not copy {@code wal/} — so a step only has to know the data files, the manifest and the dictionaries.</li>
 * <li><b>It does not stamp the version.</b> The runner rewrites the manifest's version field to {@link #to()} after the
 * step returns. Bytes 0..11 and the CRC at 4092 of each manifest copy are frozen for exactly this reason
 * (spec/MIGRATION.md section 2.3); everything else in the manifest is the step's to change.</li>
 * <li><b>It is written against the old layout explicitly.</b> The engine's classes describe the current layout, which
 * after the next bump is not the one this step reads. A step that reads version N through {@code NodeStore} is reading
 * it through version N + 1's eyes. Copy the old offsets into the step.</li>
 * <li><b>It need not be idempotent.</b> A crashed migration is restarted from a fresh copy of the original, so a step
 * never sees its own partial output.</li>
 * <li><b>The result must open with the code that registered it.</b> The runner opens the migrated copy and runs
 * {@code verify(FULL)} before anything is swapped; a step that leaves a store this build cannot open, or that fails
 * verification, fails the migration and leaves the original alone.</li>
 * </ul>
 *
 * <p>
 * Register a step in {@link MigrationRegistry#standard()} and bump {@link StoreFormat#CURRENT_VERSION} in the same
 * change. spec/MIGRATION.md section 6 is the whole procedure, including the golden fixture.
 * </p>
 */
public interface FormatMigration {

	/** The version this step reads. */
	int from();

	/** The version this step writes. Always {@code from() + 1}; the registry refuses anything else. */
	default int to() {
		return from() + 1;
	}

	/** One line for the plan: what changes on disk, and roughly what it costs. */
	String description();

	/** Rewrites the working copy from {@link #from()} to {@link #to()}. */
	void migrate(MigrationContext context) throws IOException;

	/**
	 * Why the store's log stops a migration from this step's version, or nothing when it does not.
	 *
	 * <p>
	 * Asked of the <b>first</b> step of a plan only, because that is the one step that knows the source version's log.
	 * The default reads it with this build's {@link io.metaloom.graph.core.wal.WalReader}, which is right for as long
	 * as the record framing — the 40 byte header, the CRC and the self-identifying LSN — has not changed between that
	 * version and this one. A step from a version whose framing differs overrides this with a reader for the old one;
	 * reading an old log with the new framing would see nothing valid and call a dirty store clean.
	 * </p>
	 */
	default List<String> logBlockers(Path store, StoreFormat.Info info) throws IOException {
		return LogInspector.unreplayedWork(store, info.checkpointLsn());
	}
}
