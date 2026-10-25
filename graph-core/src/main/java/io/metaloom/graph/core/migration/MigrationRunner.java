package io.metaloom.graph.core.migration;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.MigrationException;
import io.metaloom.graph.core.api.StoreFormat;
import io.metaloom.graph.core.api.StoreMigration;
import io.metaloom.graph.core.migration.MigrationMarker.Phase;

/**
 * Runs a chain of {@link FormatMigration} steps over a copy of a store and swaps the result in.
 *
 * <pre>
 *   original   ──copy (reflink or sparse)──▶  .migrating-name   ──steps──▶  verify(FULL)
 *      │                                                                        │
 *      └──rename──▶ name.pre-migration-vN          .migrating-name ──rename──▶ name
 * </pre>
 *
 * <p>
 * The marker {@code .migration-<name>} is written before the copy starts and moves through
 * {@link Phase#COPYING}, {@link Phase#MIGRATING} and {@link Phase#VERIFIED}. Before {@code VERIFIED} the original has
 * not been touched, so a rerun throws the copy away and starts again; from {@code VERIFIED} on the swap may have begun,
 * so a rerun only ever finishes it. The store's {@code LOCK} is held from before the copy until after the swap, so no
 * other process can open the original and write to it after it has been copied.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/MIGRATION.md">spec/MIGRATION.md</a> section 4
 */
public final class MigrationRunner implements StoreMigration {

	private static final MigrationRunner STANDARD = new MigrationRunner(MigrationRegistry.standard(),
		StoreVersions.MANIFEST, MigrationVerifier.OPEN_AND_VERIFY_FULL, MigrationHooks.NONE);

	private final MigrationRegistry registry;
	private final StoreVersions versions;
	private final MigrationVerifier verifier;
	private final MigrationHooks hooks;

	public MigrationRunner(MigrationRegistry registry, StoreVersions versions, MigrationVerifier verifier,
		MigrationHooks hooks) {
		this.registry = registry;
		this.versions = versions;
		this.verifier = verifier;
		this.hooks = hooks;
	}

	public static MigrationRunner standard() {
		return STANDARD;
	}

	@Override
	public int targetVersion() {
		return registry.target();
	}

	// ---------------------------------------------------------------- plan

	@Override
	public Plan plan(Path store) throws IOException {
		Path source = store.toAbsolutePath().normalize();
		Path work = MigrationMarker.workFor(source);
		long usable = Files.getFileStore(existingAncestor(source)).getUsableSpace();
		Optional<MigrationMarker> marker = MigrationMarker.read(source);

		if (marker.isPresent() && marker.get().phase() == Phase.VERIFIED) {
			MigrationMarker m = marker.get();
			List<String> blockers = new ArrayList<>();
			String state = swapState(source, m);
			if (state != null) {
				blockers.add(state);
			}
			return new Plan(source, m.from(), m.to(), steps(m.from(), m.to()), blockers,
				source.resolveSibling(m.work()), source.resolveSibling(m.backup()),
				"A migration from version " + m.from() + " to " + m.to() + " was verified and interrupted during the"
					+ " swap; running it again finishes the swap",
				-1, usable);
		}

		String interrupted = marker.map(m -> "A migration from version " + m.from() + " to " + m.to()
			+ " was interrupted in phase " + m.phase() + ", before the original was touched; running it again"
			+ " discards its copy and starts over").orElse(null);
		if (!Files.isRegularFile(source.resolve("MANIFEST"))) {
			throw new GraphStoreException("No store at " + source + ": there is no MANIFEST");
		}
		StoreFormat.Info info = StoreFormat.inspect(source);
		int from = versions.read(source);
		int to = registry.target();
		Path backup = MigrationMarker.backupFor(source, from);
		List<String> blockers = new ArrayList<>();
		if (from > to) {
			blockers.add("The store is at format version " + from + ", newer than this build, which reads version "
				+ to + ". Open it with the build that wrote it, or a newer one. There is no downgrade.");
			return new Plan(source, from, to, List.of(), blockers, work, backup, interrupted, -1, usable);
		}
		List<FormatMigration> path;
		try {
			path = registry.path(from);
		} catch (MigrationException e) {
			blockers.add(e.getMessage());
			return new Plan(source, from, to, List.of(), blockers, work, backup, interrupted, -1, usable);
		}
		if (path.isEmpty()) {
			return new Plan(source, from, to, List.of(), blockers, work, backup, interrupted, 0, usable);
		}
		String locked = lockProbe(source);
		if (locked != null) {
			blockers.add(locked);
		}
		blockers.addAll(path.get(0).logBlockers(source, info));
		if (Files.exists(backup)) {
			blockers.add("The backup name " + backup + " is taken, by an earlier migration of this store from the"
				+ " same version. Move it away or delete it; a migration never overwrites a backup.");
		}
		if (marker.isEmpty() && Files.exists(work)) {
			blockers.add("A working copy " + work + " exists with no migration marker beside it, so this run cannot"
				+ " tell whose it is. Delete it if no migration is running.");
		}
		return new Plan(source, from, to, describe(path), blockers, work, backup, interrupted,
			StoreCopier.allocatedBytes(source), usable);
	}

	private List<Step> steps(int from, int to) {
		try {
			return from >= to ? List.of() : describe(registry.path(from));
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	private static List<Step> describe(List<FormatMigration> path) {
		return path.stream().map(step -> new Step(step.from(), step.to(), step.description())).toList();
	}

	// ---------------------------------------------------------------- migrate

	@Override
	public Result migrate(Path store, Listener listener) throws IOException {
		long started = System.nanoTime();
		Path source = store.toAbsolutePath().normalize();
		Optional<MigrationMarker> marker = MigrationMarker.read(source);
		boolean resumed = false;
		if (marker.isPresent()) {
			MigrationMarker m = marker.get();
			if (m.phase() == Phase.VERIFIED) {
				return finishSwap(source, m, listener, started);
			}
			listener.event("Found a migration from version " + m.from() + " to " + m.to() + " interrupted in phase "
				+ m.phase() + ". The original was never touched; discarding the partial copy and starting again.");
			StoreCopier.deleteRecursively(source.resolveSibling(m.work()));
			MigrationMarker.delete(source);
			resumed = true;
		}

		Plan plan = plan(source);
		if (plan.fromVersion() > plan.toVersion()) {
			throw new FormatVersionException(plan.blockers().get(0));
		}
		if (!plan.canProceed()) {
			throw new MigrationException("The migration of " + source + " from format version " + plan.fromVersion()
				+ " to " + plan.toVersion() + " cannot run. Nothing was changed.\n  - "
				+ String.join("\n  - ", plan.blockers()));
		}
		if (plan.steps().isEmpty()) {
			listener.event("The store at " + source + " is already at format version " + plan.toVersion()
				+ "; nothing to do.");
			return new Result(source, plan.fromVersion(), plan.toVersion(), List.of(), null, resumed,
				Duration.ofNanos(System.nanoTime() - started));
		}

		int from = plan.fromVersion();
		int to = plan.toVersion();
		List<FormatMigration> path = registry.path(from);
		StoreFormat.Info info = StoreFormat.inspect(source);
		Path work = plan.workDirectory();
		Path backup = plan.backupDirectory();

		// Taken before the marker is written and held until after the swap: nothing may open the original and write to
		// it once it has been copied. Refusing here, before anything exists, is what lets a locked store be reported as
		// "nothing was changed" rather than as a failed migration.
		FileChannel lockChannel = FileChannel.open(source.resolve("LOCK"), StandardOpenOption.CREATE,
			StandardOpenOption.READ, StandardOpenOption.WRITE);
		FileLock lock;
		try {
			lock = lock(lockChannel, source);
		} catch (IOException | RuntimeException e) {
			lockChannel.close();
			throw e;
		}
		boolean verified = false;
		try {
			MigrationMarker m = MigrationMarker.start(from, to, work, backup);
			try {
				m.write(source);
				hooks.reached(MigrationHooks.STARTED);

				listener.event("Copying " + source + " to " + work);
				StoreCopier.Copied copied = StoreCopier.copy(source, work);
				listener.event("Copied " + copied.files() + " files"
					+ (copied.reflinked() > 0 ? ", " + copied.reflinked() + " by reflink" : "") + ", "
					+ copied.bytesWritten() + " bytes written");
				hooks.reached(MigrationHooks.COPIED);

				m = m.withPhase(Phase.MIGRATING);
				m.write(source);
				for (FormatMigration step : path) {
					listener.event("Step " + step.from() + " -> " + step.to() + ": " + step.description());
					step.migrate(new MigrationContext(work, info, step.from(), step.to(), listener::event));
					versions.write(work, step.to());
					int now = versions.read(work);
					if (now != step.to()) {
						throw new MigrationException("After step " + step.from() + " -> " + step.to() + " the copy"
							+ " reads as version " + now);
					}
					hooks.reached(MigrationHooks.STEPPED + step.to());
				}

				listener.event("Verifying the migrated copy: open, then verify(FULL)");
				verifier.verify(work, verificationOptions(work, info));

				m = m.withPhase(Phase.VERIFIED);
				m.write(source);
				verified = true;
				hooks.reached(MigrationHooks.VERIFIED);

				swap(source, work, backup, listener);
			} finally {
				lock.release();
			}
		} catch (IOException | RuntimeException failure) {
			if (verified) {
				throw new MigrationException("The migration of " + source + " to format version " + to + " was"
					+ " verified, and then the swap failed: " + failure.getMessage() + ". Run it again to finish the"
					+ " swap; the original is at " + (Files.exists(backup) ? backup : source) + ".", failure);
			}
			MigrationException wrapped = new MigrationException("The migration of " + source + " from format version "
				+ from + " to " + to + " failed: " + failure.getMessage() + ". The original store is untouched, and"
				+ " the partial copy has been removed.", failure);
			try {
				StoreCopier.deleteRecursively(work);
				MigrationMarker.delete(source);
			} catch (IOException | RuntimeException cleanup) {
				wrapped.addSuppressed(cleanup);
			}
			throw wrapped;
		} finally {
			lockChannel.close();
		}
		listener.event("Migrated " + source + " from format version " + from + " to " + to + ". The original is kept"
			+ " at " + backup + "; delete it once the migrated store has been checked.");
		return new Result(source, from, to, describe(path), backup, resumed,
			Duration.ofNanos(System.nanoTime() - started));
	}

	/**
	 * The options the copy is verified with: its own geometry, from its own manifest because a step may have changed
	 * it, and the original's log segment size, which the manifest does not record and the copy has no log to measure.
	 */
	private static GraphStoreOptions verificationOptions(Path work, StoreFormat.Info source) throws IOException {
		StoreFormat.Info migrated = StoreFormat.inspect(work);
		GraphStoreOptions.Builder builder = GraphStoreOptions.builder()
			.pageSize(migrated.pageSize())
			.segmentCapacity(migrated.segmentCapacity())
			.vectorDimensions(migrated.vectorDimensions());
		if (source.walSegmentBytes() > 0) {
			builder.walSegmentBytes(source.walSegmentBytes());
		}
		return builder.build();
	}

	/** Two renames in one directory, each followed by a sync of that directory. The lock is still held. */
	private void swap(Path source, Path work, Path backup, Listener listener) throws IOException {
		listener.event("Swapping: " + source + " -> " + backup + ", then " + work + " -> " + source);
		Files.move(source, backup, StandardCopyOption.ATOMIC_MOVE);
		MigrationMarker.syncDirectory(source.getParent());
		hooks.reached(MigrationHooks.BACKED_UP);
		Files.move(work, source, StandardCopyOption.ATOMIC_MOVE);
		MigrationMarker.syncDirectory(source.getParent());
		hooks.reached(MigrationHooks.SWAPPED);
		MigrationMarker.delete(source);
	}

	/**
	 * Completes the swap of a migration that was verified before it was interrupted.
	 *
	 * <p>
	 * Three states are consistent, one per point the swap can stop at: neither rename done, the first done, both done.
	 * Anything else — a backup but no copy, two stores and no marker of which is which — means something other than
	 * this runner moved the directories, and is refused with what was found rather than guessed at.
	 * </p>
	 */
	private Result finishSwap(Path source, MigrationMarker m, Listener listener, long started) throws IOException {
		Path work = source.resolveSibling(m.work());
		Path backup = source.resolveSibling(m.backup());
		String inconsistent = swapState(source, m);
		if (inconsistent != null) {
			throw new MigrationException(inconsistent);
		}
		listener.event("Finishing a migration from version " + m.from() + " to " + m.to() + " that was verified"
			+ " before it was interrupted");
		boolean workExists = isStore(work);
		boolean sourceIsStore = isStore(source);
		if (workExists && sourceIsStore) {
			try (FileChannel lockChannel = FileChannel.open(source.resolve("LOCK"), StandardOpenOption.CREATE,
				StandardOpenOption.READ, StandardOpenOption.WRITE)) {
				FileLock lock = lock(lockChannel, source);
				try {
					swap(source, work, backup, listener);
				} finally {
					lock.release();
				}
			}
		} else if (workExists) {
			if (Files.isDirectory(source)) {
				// An empty directory where the original was: something tried to open the store after the first rename
				// and created the directory before the marker refused it. swapState has checked it holds nothing.
				Files.delete(source);
			}
			Files.move(work, source, StandardCopyOption.ATOMIC_MOVE);
			MigrationMarker.syncDirectory(source.getParent());
			hooks.reached(MigrationHooks.SWAPPED);
			MigrationMarker.delete(source);
		} else {
			MigrationMarker.delete(source);
		}
		int now = versions.read(source);
		if (now != m.to()) {
			throw new MigrationException("The store at " + source + " reads as format version " + now + " after"
				+ " finishing a migration to " + m.to() + ". Inspect it and " + backup + " by hand.");
		}
		listener.event("Migrated " + source + " from format version " + m.from() + " to " + m.to()
			+ ". The original is kept at " + backup + ".");
		return new Result(source, m.from(), m.to(), steps(m.from(), m.to()), backup, true,
			Duration.ofNanos(System.nanoTime() - started));
	}

	/** Null when the directories are in one of the three states a verified migration can stop in, else why not. */
	private static String swapState(Path source, MigrationMarker m) throws IOException {
		Path work = source.resolveSibling(m.work());
		Path backup = source.resolveSibling(m.backup());
		boolean workExists = isStore(work);
		boolean sourceIsStore = isStore(source);
		boolean sourceIsEmptyDirectory = !sourceIsStore && isEmptyDirectory(source);
		boolean sourceAbsent = !Files.exists(source) || sourceIsEmptyDirectory;
		boolean backupExists = isStore(backup);
		if (workExists && sourceIsStore && !backupExists) {
			return null; // neither rename happened
		}
		if (workExists && sourceAbsent && backupExists) {
			return null; // the original is aside, the copy not yet in
		}
		if (!workExists && sourceIsStore && backupExists) {
			return null; // both renames happened; only the marker is left
		}
		return "A verified migration of " + source + " from version " + m.from() + " to " + m.to() + " stopped in a"
			+ " state this runner did not leave: the migrated copy " + work + (workExists ? " exists" : " is missing")
			+ ", the store " + (sourceIsStore ? "exists" : "is missing") + ", the backup " + backup
			+ (backupExists ? " exists" : " is missing") + ". Something else moved these directories. Decide by hand"
			+ " which is the store, then delete " + MigrationMarker.pathFor(source) + ".";
	}

	// ---------------------------------------------------------------- plumbing

	private static boolean isStore(Path directory) {
		return Files.isRegularFile(directory.resolve("MANIFEST"));
	}

	private static boolean isEmptyDirectory(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) {
			return false;
		}
		try (Stream<Path> entries = Files.list(directory)) {
			return entries.findAny().isEmpty();
		}
	}

	private static FileLock lock(FileChannel channel, Path source) throws IOException {
		FileLock lock;
		try {
			lock = channel.tryLock();
		} catch (OverlappingFileLockException e) {
			throw new MigrationException("The store at " + source + " is open in this JVM. Close it before migrating.");
		}
		if (lock == null) {
			throw new MigrationException(lockedMessage(source));
		}
		return lock;
	}

	/** Takes the lock and lets it go, so a plan can say the store is in use. Null when it is free. */
	private static String lockProbe(Path source) throws IOException {
		Path lockFile = source.resolve("LOCK");
		if (!Files.exists(lockFile)) {
			return null;
		}
		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			FileLock lock = channel.tryLock();
			if (lock == null) {
				return lockedMessage(source);
			}
			lock.release();
			return null;
		} catch (OverlappingFileLockException e) {
			return "The store at " + source + " is open in this JVM. Close it before migrating.";
		}
	}

	private static String lockedMessage(Path source) {
		return "The store at " + source + " is locked by another process — a server has it open. Stop the server"
			+ " cleanly (SIGTERM) first: a migration rewrites the store's files and must be the only thing using them.";
	}

	private static Path existingAncestor(Path path) {
		Path current = path;
		while (current != null && !Files.exists(current)) {
			current = current.getParent();
		}
		return current == null ? path.getRoot() : current;
	}
}
