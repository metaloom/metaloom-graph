package io.metaloom.graph.core.migration;

import java.io.IOException;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import io.metaloom.graph.core.api.MigrationException;

/**
 * The file that says a migration of a store is in progress, and how far it got.
 *
 * <p>
 * It lives <b>beside</b> the store, as {@code .migration-<name>} in the same parent directory, because both the store
 * and its working copy are renamed during the swap and a marker inside either would move with it. A dot-name, so
 * {@code GraphDatabases} never lists it as a database; the working copy, {@code .migrating-<name>}, is a dot-name for
 * the same reason, and the backup, {@code <name>.pre-migration-v<N>}, holds a character no database name may.
 * </p>
 *
 * <p>
 * Plain {@code key=value} text, so an operator looking at a stuck migration can read it without this build. Written
 * to a temporary name and renamed over, with the directory synced, so a crash leaves the previous phase or the new one
 * and never half a file.
 * </p>
 *
 * @param from
 *            the version the original is at
 * @param to
 *            the version the migration ends at
 * @param work
 *            the working copy's directory name, relative to the store's parent
 * @param backup
 *            the name the original is moved to, relative to the store's parent
 */
public record MigrationMarker(int from, int to, Phase phase, String work, String backup, String started) {

	/** Bumped if this file's keys ever change, so an older build can refuse a marker it would misread. */
	static final int MARKER_VERSION = 1;

	public enum Phase {
		/** The working copy is being made. The original is untouched; a rerun starts over. */
		COPYING,
		/** Steps are running on the working copy. The original is untouched; a rerun starts over. */
		MIGRATING,
		/**
		 * The working copy is migrated and verified, and the swap may have begun. A rerun finishes the swap and never
		 * starts over: the original may already have been renamed.
		 */
		VERIFIED
	}

	public static Path pathFor(Path store) {
		Path absolute = store.toAbsolutePath().normalize();
		return parentOf(absolute).resolve(".migration-" + absolute.getFileName());
	}

	public static Path workFor(Path store) {
		Path absolute = store.toAbsolutePath().normalize();
		return parentOf(absolute).resolve(".migrating-" + absolute.getFileName());
	}

	public static Path backupFor(Path store, int fromVersion) {
		Path absolute = store.toAbsolutePath().normalize();
		return parentOf(absolute).resolve(absolute.getFileName() + ".pre-migration-v" + fromVersion);
	}

	private static Path parentOf(Path absolute) {
		Path parent = absolute.getParent();
		if (parent == null || absolute.getFileName() == null) {
			throw new IllegalArgumentException("A store cannot be a filesystem root: " + absolute);
		}
		return parent;
	}

	static MigrationMarker start(int from, int to, Path work, Path backup) {
		return new MigrationMarker(from, to, Phase.COPYING, work.getFileName().toString(),
			backup.getFileName().toString(), Instant.now().toString());
	}

	MigrationMarker withPhase(Phase next) {
		return new MigrationMarker(from, to, next, work, backup, started);
	}

	public static Optional<MigrationMarker> read(Path store) throws IOException {
		Path path = pathFor(store);
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		Properties properties = new Properties();
		properties.load(new StringReader(Files.readString(path, StandardCharsets.UTF_8)));
		try {
			int version = Integer.parseInt(properties.getProperty("marker", "0"));
			if (version != MARKER_VERSION) {
				throw new MigrationException("The migration marker " + path + " has version " + version + ", and this"
					+ " build reads version " + MARKER_VERSION + ". Finish the migration with the build that started it.");
			}
			return Optional.of(new MigrationMarker(Integer.parseInt(properties.getProperty("from")),
				Integer.parseInt(properties.getProperty("to")), Phase.valueOf(properties.getProperty("phase")),
				require(properties, "work", path), require(properties, "backup", path),
				properties.getProperty("started", "")));
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new MigrationException("The migration marker " + path + " cannot be read (" + e.getMessage() + ")."
				+ " Look at it, and at the directories beside it, before deleting it.", e);
		}
	}

	private static String require(Properties properties, String key, Path path) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank() || value.contains("/") || value.contains("\\") || value.startsWith("..")) {
			// A marker names directories beside the store and nothing else; one that points anywhere else is not ours.
			throw new IllegalArgumentException("'" + key + "' is missing or is not a plain directory name");
		}
		return value;
	}

	void write(Path store) throws IOException {
		Path path = pathFor(store);
		String text = "# A store format migration is in progress. spec/MIGRATION.md section 4.\n"
			+ "marker=" + MARKER_VERSION + "\n"
			+ "from=" + from + "\n"
			+ "to=" + to + "\n"
			+ "phase=" + phase + "\n"
			+ "work=" + work + "\n"
			+ "backup=" + backup + "\n"
			+ "started=" + started + "\n";
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			StandardOpenOption.TRUNCATE_EXISTING)) {
			channel.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
			channel.force(true);
		}
		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		syncDirectory(path.getParent());
	}

	static void delete(Path store) throws IOException {
		Path path = pathFor(store);
		Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
		if (Files.deleteIfExists(path)) {
			syncDirectory(path.getParent());
		}
	}

	/**
	 * Refuses to open a store whose migration was interrupted.
	 *
	 * <p>
	 * Called first thing by {@code GraphStore.open}, before the directory is created or locked. Past the
	 * {@link Phase#VERIFIED} point the original may already have been renamed away, and an open that found the
	 * directory missing would create an empty store in its place — which the next migration run would then have to
	 * tell apart from the real one. Refusing in every phase keeps the rule simple: a marker means "finish the
	 * migration first".
	 * </p>
	 */
	public static void refuseIfInterrupted(Path store) throws IOException {
		Path absolute = store.toAbsolutePath().normalize();
		if (absolute.getParent() == null || absolute.getFileName() == null) {
			return;
		}
		Path path = pathFor(absolute);
		if (!Files.exists(path)) {
			return;
		}
		Optional<MigrationMarker> marker = read(absolute);
		String detail = marker.map(m -> "from format version " + m.from() + " to " + m.to() + ", stopped in phase "
			+ m.phase()).orElse("");
		StringBuilder message = new StringBuilder("A migration of the store at ").append(absolute).append(' ')
			.append(detail).append(" was interrupted (").append(path).append("). It does not open until that is "
				+ "finished, because the store may be half way through being swapped. Run the migration again:\n")
			.append("  metaloom-graph-cli database migrate ").append(absolute.getFileName()).append(" --path=")
			.append(absolute.getParent());
		if (marker.isPresent() && marker.get().phase() != Phase.VERIFIED) {
			message.append("\nThe original was never touched. To abandon the migration instead, delete ")
				.append(absolute.getParent().resolve(marker.get().work())).append(" and ").append(path).append('.');
		}
		throw new MigrationException(message.toString());
	}

	static void syncDirectory(Path directory) {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException e) {
			// Not every platform can open a directory for syncing. Linux can, and it is the one where rename durability
			// needs it; elsewhere the rename is as durable as that filesystem makes it.
		}
	}
}
