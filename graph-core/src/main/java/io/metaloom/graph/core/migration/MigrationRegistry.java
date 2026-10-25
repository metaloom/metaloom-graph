package io.metaloom.graph.core.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.MigrationException;
import io.metaloom.graph.core.api.StoreFormat;

/**
 * The migration steps a build knows, and the chain from a store's version to the target.
 *
 * <p>
 * A chain rather than a table of pairs: every step goes from N to N + 1, so the path from any version is unique, a
 * gap is visible at registration, and a new step is one entry rather than one per older version. A long chain is
 * slower than a direct conversion would be, and that is accepted — each step is written once, against two layouts
 * the author knows, instead of every pair of layouts there has ever been.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/MIGRATION.md">spec/MIGRATION.md</a> section 3
 */
public final class MigrationRegistry {

	/**
	 * Every step this build ships, oldest first.
	 *
	 * <p>
	 * <b>Empty</b>, because version 4 is the first one with a migration process at all — the 0.1.0-alpha.1 baseline.
	 * When the format changes, the step from 4 to 5 goes here in the same change that sets
	 * {@link StoreFormat#CURRENT_VERSION} to 5.
	 * </p>
	 */
	private static final MigrationRegistry STANDARD = new MigrationRegistry(StoreFormat.CURRENT_VERSION, List.of(
	// new V4ToV5(),
	));

	private final int target;
	private final Map<Integer, FormatMigration> byFrom = new TreeMap<>();

	/**
	 * @throws IllegalArgumentException
	 *             for a step that does not go from N to N + 1, two steps from one version, or a step past the target.
	 *             All three are mistakes in a build, not in a store, so they fail at construction rather than when
	 *             somebody's data needs them
	 */
	public MigrationRegistry(int target, List<FormatMigration> steps) {
		this.target = target;
		for (FormatMigration step : steps) {
			if (step.to() != step.from() + 1) {
				throw new IllegalArgumentException("A migration step goes from N to N + 1; '" + step.description()
					+ "' goes from " + step.from() + " to " + step.to());
			}
			if (step.to() > target) {
				throw new IllegalArgumentException("Step " + step.from() + " -> " + step.to() + " is past the target "
					+ target);
			}
			if (byFrom.putIfAbsent(step.from(), step) != null) {
				throw new IllegalArgumentException("Two migration steps from version " + step.from());
			}
		}
	}

	public static MigrationRegistry standard() {
		return STANDARD;
	}

	public int target() {
		return target;
	}

	/**
	 * The steps from {@code from} to the target, in order. Empty when the store is already there.
	 *
	 * @throws FormatVersionException
	 *             if {@code from} is newer than the target
	 * @throws MigrationException
	 *             if a step on the way is missing, naming it
	 */
	public List<FormatMigration> path(int from) {
		if (from > target) {
			throw new FormatVersionException("A store at format version " + from + " is newer than this build, which"
				+ " reads version " + target + ". There is no downgrade.");
		}
		List<FormatMigration> path = new ArrayList<>();
		for (int version = from; version < target; version++) {
			FormatMigration step = byFrom.get(version);
			if (step == null) {
				throw new MigrationException("There is no migration step from format version " + version
					+ (version == from ? "" : " (on the way from " + from + ")") + " to " + (version + 1)
					+ ". This build can migrate stores from version " + oldestMigratable() + " onwards; an older store"
					+ " has to be dumped with the build that wrote it and restored with this one.");
			}
			path.add(step);
		}
		return path;
	}

	public boolean hasPath(int from) {
		if (from > target) {
			return false;
		}
		for (int version = from; version < target; version++) {
			if (!byFrom.containsKey(version)) {
				return false;
			}
		}
		return true;
	}

	/** The oldest version from which an unbroken chain reaches the target; the target itself when there is none. */
	public int oldestMigratable() {
		int oldest = target;
		while (byFrom.containsKey(oldest - 1)) {
			oldest--;
		}
		return oldest;
	}
}
