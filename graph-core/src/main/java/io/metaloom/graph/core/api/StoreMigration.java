package io.metaloom.graph.core.api;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Brings a store written in an older format version up to {@link StoreFormat#CURRENT_VERSION}.
 *
 * <pre>{@code
 * StoreMigration migration = StoreMigration.standard();
 * StoreMigration.Plan plan = migration.plan(store);      // reads, changes nothing
 * if (plan.canProceed() && !plan.upToDate()) {
 *     migration.migrate(store, System.err::println);
 * }
 * }</pre>
 *
 * <h2>What a migration guarantees</h2>
 *
 * <ul>
 * <li><b>The original is never written.</b> The steps run on a copy beside it, {@code .migrating-<name>}; the original
 * is renamed to {@code <name>.pre-migration-v<N>} only after the copy has been migrated, opened and passed
 * {@code verify(FULL)}, and it is kept, never deleted.</li>
 * <li><b>A failure leaves the original as it was</b>, and the partial copy is removed.</li>
 * <li><b>A crash is finished by running it again.</b> A marker file beside the store records how far it got; before
 * the copy is verified a rerun starts over, after it a rerun completes the swap. Until then the store refuses to open
 * rather than be opened in the middle of a swap.</li>
 * <li><b>The store must have been closed cleanly.</b> A log with committed transactions past the last checkpoint would
 * have to be replayed by the code that wrote it, so such a store is refused with instructions.</li>
 * <li><b>A store newer than this build is refused</b>, and there is no downgrade.</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../spec/MIGRATION.md">spec/MIGRATION.md</a>
 */
public interface StoreMigration {

	/** The migration this build ships: every registered step, ending at {@link StoreFormat#CURRENT_VERSION}. */
	static StoreMigration standard() {
		return io.metaloom.graph.core.migration.MigrationRunner.standard();
	}

	/** The version a migration brings a store to. */
	int targetVersion();

	/**
	 * Works out what {@link #migrate} would do, and what would stop it, without changing anything.
	 *
	 * @throws GraphStoreException
	 *             if there is no store at the path and no interrupted migration of one
	 */
	Plan plan(Path store) throws IOException;

	/**
	 * Migrates a store in place of the original, keeping the original beside it. A store already at the target
	 * version is left alone. An interrupted earlier migration of the same store is finished or restarted first.
	 *
	 * @throws MigrationException
	 *             if the migration was refused or failed. Either way the original is untouched
	 * @throws FormatVersionException
	 *             if the store is newer than this build
	 */
	Result migrate(Path store, Listener listener) throws IOException;

	default Result migrate(Path store) throws IOException {
		return migrate(store, Listener.NONE);
	}

	/** One step of a plan: version {@code from} to {@code from + 1}. */
	record Step(int from, int to, String description) {
	}

	/**
	 * What a migration would do.
	 *
	 * @param store
	 *            the store directory
	 * @param fromVersion
	 *            the store's current format version
	 * @param toVersion
	 *            the version the migration ends at
	 * @param steps
	 *            the steps in the order they would run; empty when the store is current
	 * @param blockers
	 *            every reason the migration cannot run now, each with what to do about it; empty when it can
	 * @param workDirectory
	 *            where the migrated copy is built
	 * @param backupDirectory
	 *            where the original is kept afterwards
	 * @param interrupted
	 *            a description of an interrupted earlier migration that {@link #migrate} would finish or restart, or
	 *            null
	 * @param bytesToCopy
	 *            the store's allocated size, which a copy without reflink support needs free beside it; -1 when it
	 *            cannot be measured here
	 * @param usableBytes
	 *            free space in the directory that holds the store
	 */
	record Plan(Path store, int fromVersion, int toVersion, List<Step> steps, List<String> blockers,
		Path workDirectory, Path backupDirectory, String interrupted, long bytesToCopy, long usableBytes) {

		public Plan {
			steps = List.copyOf(steps);
			blockers = List.copyOf(blockers);
		}

		/** True when there is nothing to do: the store is current and no earlier migration is half finished. */
		public boolean upToDate() {
			return fromVersion == toVersion && interrupted == null;
		}

		/** True when {@link StoreMigration#migrate} would go ahead. */
		public boolean canProceed() {
			return blockers.isEmpty();
		}
	}

	/**
	 * What a migration did.
	 *
	 * @param backupDirectory
	 *            where the original now is, or null when nothing was migrated
	 * @param resumed
	 *            true when this run finished an interrupted earlier one
	 */
	record Result(Path store, int fromVersion, int toVersion, List<Step> applied, Path backupDirectory,
		boolean resumed, Duration elapsed) {

		public Result {
			applied = List.copyOf(applied);
		}

		/** False when the store was already current and nothing was touched. */
		public boolean changed() {
			return backupDirectory != null;
		}
	}

	/** Progress, one line per event. */
	@FunctionalInterface
	interface Listener {

		Listener NONE = message -> {
		};

		void event(String message);
	}
}
