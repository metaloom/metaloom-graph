package io.metaloom.graph.core.api;

import java.nio.file.Path;

/**
 * The store was written in an older format version than this build reads, and has to be migrated before it can be
 * opened.
 *
 * <p>
 * Nothing was changed by the open that raised this: the version is checked before anything else in the store is read.
 * There is deliberately no automatic upgrade on open. A migration copies the store, needs room for the copy, and keeps
 * the original beside it as {@code <name>.pre-migration-v<N>} — a server start must not quietly double the disk usage
 * of every database it opens. The message names the command to run.
 * </p>
 *
 * @see StoreMigration
 * @see <a href="../../../../../../../../spec/MIGRATION.md">spec/MIGRATION.md</a>
 */
public class MigrationRequiredException extends FormatVersionException {

	private static final long serialVersionUID = 1L;

	private final transient Path store;
	private final int storeVersion;
	private final int currentVersion;
	private final boolean migratable;

	/**
	 * @param migratable
	 *            whether this build has a chain of migration steps from {@code storeVersion} to {@code currentVersion}.
	 *            When it does not, the message says how to move the data instead
	 */
	public MigrationRequiredException(Path store, int storeVersion, int currentVersion, boolean migratable) {
		super(message(store, storeVersion, currentVersion, migratable));
		this.store = store;
		this.storeVersion = storeVersion;
		this.currentVersion = currentVersion;
		this.migratable = migratable;
	}

	public Path store() {
		return store;
	}

	public int storeVersion() {
		return storeVersion;
	}

	public int currentVersion() {
		return currentVersion;
	}

	/** Whether {@code metaloom-graph-cli database migrate} can bring this store to the current version. */
	public boolean migratable() {
		return migratable;
	}

	static String message(Path store, int storeVersion, int currentVersion, boolean migratable) {
		Path absolute = store.toAbsolutePath().normalize();
		String name = absolute.getFileName() == null ? absolute.toString() : absolute.getFileName().toString();
		Path root = absolute.getParent();
		StringBuilder out = new StringBuilder("Store at ").append(absolute).append(" has format version ")
			.append(storeVersion).append(" and this build reads version ").append(currentVersion).append(". ");
		if (!migratable) {
			out.append("There is no migration path from version ").append(storeVersion)
				.append(": it predates the first version this build can migrate. Take a dump with the build that wrote"
					+ " it ('metaloom-graph-cli database backup') and restore it with this one ('database restore').")
				.append(" Nothing was changed.");
			return out.toString();
		}
		out.append("Migrate it first, with the server stopped:\n");
		out.append("  metaloom-graph-cli database migrate ").append(name).append(" --path=").append(root)
			.append(" --dry-run   # the plan, changes nothing\n");
		out.append("  metaloom-graph-cli database migrate ").append(name).append(" --path=").append(root).append('\n');
		out.append("The original is kept beside it as ").append(name).append(".pre-migration-v").append(storeVersion)
			.append(". Nothing was changed by this attempt to open it.");
		return out.toString();
	}
}
