package io.metaloom.graph.core.migration;

/**
 * Named points in a migration, for a test to stop it at.
 *
 * <p>
 * The crash tests throw an {@link Error} from {@link #reached}: the runner cleans up after an {@code IOException} or a
 * {@code RuntimeException} and deliberately not after an {@code Error}, so an {@code Error} leaves the disk exactly as
 * a process killed at that instant would — except that the store's lock is released, as it would be by the process
 * dying.
 * </p>
 */
@FunctionalInterface
public interface MigrationHooks {

	/** The marker has been written; nothing has been copied yet. */
	String STARTED = "started";
	/** The working copy is complete; no step has run. */
	String COPIED = "copied";
	/** A step has run and its version has been stamped; the point is suffixed with the version, {@code stepped-5}. */
	String STEPPED = "stepped-";
	/** The copy passed verification and the marker says so; nothing has been renamed. */
	String VERIFIED = "verified";
	/** The original has been renamed to its backup name; the copy has not been moved in yet. */
	String BACKED_UP = "backed-up";
	/** The copy is in place; the marker has not been removed yet. */
	String SWAPPED = "swapped";

	MigrationHooks NONE = point -> {
	};

	void reached(String point);
}
