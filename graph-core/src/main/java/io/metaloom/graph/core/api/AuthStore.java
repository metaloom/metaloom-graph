package io.metaloom.graph.core.api;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.auth.AuthCodec;
import io.metaloom.graph.core.auth.AuthFile;
import io.metaloom.graph.core.auth.AuthNames;
import io.metaloom.graph.core.auth.PasswordHash;
import io.metaloom.graph.core.auth.StoredUser;

/**
 * The users, roles and grants under a root of databases.
 *
 * <pre>{@code
 * try (AuthStore auth = AuthStore.open(Path.of("/data/graphs"))) {
 *     Optional<Principal> principal = auth.authenticate("alice", "secret".toCharArray());
 * }
 * }</pre>
 *
 * <h2>Where it lives, and why nothing collides with it</h2>
 *
 * <p>
 * {@code <root>/.auth/}, beside the database directories. {@link GraphDatabases#validate} is an allow-list that does
 * not include {@code .}, so no database can ever be called {@code .auth}; and {@link GraphDatabases#names()} lists only
 * directories holding a {@code MANIFEST}, which this one never has. Two independent mechanisms, neither of which had to
 * change to make room for this.
 * </p>
 *
 * <h2>The lock is held across a write, not across a lifetime</h2>
 *
 * <p>
 * A store's {@code LOCK} is taken for as long as the store is open, because one process must own the data. This one is
 * the opposite: {@code .auth/LOCK} is taken for the duration of a single read-modify-write and released. That is what
 * lets {@code metaloom-graph-cli auth reset-admin} edit the identity file of a root that a server currently has open —
 * which is the whole point of having an offline recovery command, and would be impossible if the server held the lock.
 * </p>
 *
 * <p>
 * The server's half of that bargain is {@link #reloadIfChanged()}, called on the login path. A reset therefore takes
 * effect on the next login with no restart. It is not called on a session lookup: one {@code stat} per login is
 * nothing beside the PBKDF2 work that login is about to do anyway, and one per request would not be.
 * </p>
 *
 * <p>
 * <b>Not thread-safe against concurrent mutation from one process</b> — the file lock excludes other processes, and
 * within a process the administrative routes are not a hot path. {@link #authenticate} and {@link #principal} are safe
 * to call from many threads.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public final class AuthStore implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(AuthStore.class);

	/** The directory under the root. The leading dot is what makes it unreachable as a database name. */
	public static final String DIRECTORY = ".auth";

	public static final String FILE = "AUTH";

	private static final String TEMP_FILE = "AUTH.tmp";

	private static final String LOCK_FILE = "LOCK";

	/** The user a first start creates. */
	public static final String ADMIN = "admin";

	private final Path directory;

	private final Path file;

	private final Path lockFile;

	private volatile AuthFile image;

	/** What {@link #reloadIfChanged()} compares against; a stat is cheaper than a read and far cheaper than a parse. */
	private volatile long loadedSize = -1;

	private volatile long loadedModifiedMillis = -1;

	private boolean closed;

	private AuthStore(Path directory, AuthFile image) {
		this.directory = directory;
		this.file = directory.resolve(FILE);
		this.lockFile = directory.resolve(LOCK_FILE);
		this.image = image;
	}

	/**
	 * Opens, or creates, the identity directory under a root.
	 *
	 * <p>
	 * Creating the directory is not creating an identity: a root with no {@code AUTH} file opens to an empty image, and
	 * {@link #bootstrap} is a separate, deliberate act.
	 * </p>
	 *
	 * @throws AuthFormatException
	 *             if a file is there and cannot be read. <b>Never bootstrapped over</b> — overwriting a damaged
	 *             credential file would discard grants nobody could reconstruct, and could silently widen access
	 */
	public static AuthStore open(Path root) throws IOException {
		Path directory = root.resolve(DIRECTORY);
		Files.createDirectories(directory);
		restrict(directory);

		Path temp = directory.resolve(TEMP_FILE);
		if (Files.exists(temp)) {
			// A write that did not survive to its ATOMIC_MOVE. The old file is intact by construction, so the partial
			// one is simply removed rather than examined.
			logger.warn("Removing {}, left behind by a write that did not complete", temp);
			Files.deleteIfExists(temp);
		}

		Path path = directory.resolve(FILE);
		AuthStore store = new AuthStore(directory, AuthFile.empty());
		if (Files.isRegularFile(path)) {
			store.image = AuthCodec.decode(Files.readAllBytes(path));
			store.stamp();
		}
		return store;
	}

	// ---------------------------------------------------------------- reading

	/** Whether anybody at all can log in. A server with authentication on and this false has no reachable behaviour. */
	public boolean isEmpty() {
		return image.users().isEmpty();
	}

	public List<User> users() {
		return image.publicUsers();
	}

	public List<Role> roles() {
		return image.roles();
	}

	public Optional<User> user(String name) {
		StoredUser stored = image.user(name);
		return stored == null ? Optional.empty() : Optional.of(stored.user());
	}

	public Optional<Role> role(String name) {
		return Optional.ofNullable(image.role(name));
	}

	/** The generation of the image currently held; increments on every successful write. */
	public long generation() {
		return image.generation();
	}

	/**
	 * The principal for a user, without checking a password.
	 *
	 * <p>
	 * For a session that was authenticated earlier: grants are resolved <b>now</b> rather than being frozen at login,
	 * and a disabled user resolves to nothing, so a revoke or a disable takes effect on the next request instead of at
	 * the next login.
	 * </p>
	 */
	public Optional<Principal> principal(String name) {
		StoredUser stored = image.user(name);
		if (stored == null || !stored.user().enabled()) {
			return Optional.empty();
		}
		return Optional.of(Principal.of(name, image.effectiveGrants(name)));
	}

	/**
	 * Verifies a password.
	 *
	 * <p>
	 * <b>An unknown user costs the same as a known one.</b> The derivation runs against
	 * {@code PasswordHash.UNKNOWN_USER} rather than returning early, because a login endpoint that answered faster for
	 * a name that does not exist is a user enumerator — and the difference would be a hundred milliseconds, not
	 * something needing equipment to measure.
	 * </p>
	 *
	 * <p>
	 * Returns empty rather than throwing, and returns the same empty for every reason. Which of "no such user", "wrong
	 * password" and "disabled" it was is not something the caller is told, because it is not something the caller
	 * should be able to tell the network.
	 * </p>
	 *
	 * <p>
	 * <b>Blocking.</b> One call is on the order of a hundred milliseconds of CPU by design. Never call it on an event
	 * loop.
	 * </p>
	 */
	public Optional<Principal> authenticate(String name, char[] password) {
		StoredUser stored = name == null ? null : image.user(name);
		if (stored == null) {
			PasswordHash.UNKNOWN_USER.verify(password);
			return Optional.empty();
		}
		if (!stored.hash().verify(password)) {
			return Optional.empty();
		}
		if (!stored.user().enabled()) {
			// Checked after the hash, deliberately: checking first would make a disabled account answer faster than an
			// enabled one with a wrong password, which tells an attacker which names are real.
			return Optional.empty();
		}
		if (stored.hash().needsRehash()) {
			// The password is known to be right at this exact moment, which is the only moment it can be re-derived at
			// the new cost. Failing to persist it must not fail the login.
			try {
				setPassword(name, password);
			} catch (RuntimeException | IOException e) {
				logger.warn("Could not re-hash {}'s password at the current cost: {}", name, e.toString());
			}
		}
		return Optional.of(Principal.of(name, image.effectiveGrants(name)));
	}

	/**
	 * Re-reads the file when it has changed on disk.
	 *
	 * <p>
	 * A {@code stat} comparison rather than a read, and called on the login path only. This is what makes
	 * {@code auth reset-admin} against a running server take effect without a restart.
	 * </p>
	 *
	 * @return true when the image was replaced
	 */
	public boolean reloadIfChanged() {
		try {
			if (!Files.isRegularFile(file)) {
				return false;
			}
			long size = Files.size(file);
			long modified = Files.getLastModifiedTime(file).toMillis();
			if (size == loadedSize && modified == loadedModifiedMillis) {
				return false;
			}
			image = AuthCodec.decode(Files.readAllBytes(file));
			stamp();
			logger.info("Reloaded the identity file, now at generation {}", image.generation());
			return true;
		} catch (IOException e) {
			// A read that fails leaves the previous image in place. Refusing every login because the file became
			// briefly unreadable would turn a transient fault into an outage.
			logger.warn("Could not re-read {}: {}", file, e.toString());
			return false;
		}
	}

	// ---------------------------------------------------------------- writing

	/**
	 * Creates the first administrator, if and only if there is nobody at all.
	 *
	 * <p>
	 * "Nobody at all" rather than "no file": a file that parses and holds no users is the same unreachable state as no
	 * file, and it is not a state anybody chose. A file that does not parse is a different matter and never reaches
	 * here — {@link #open} refuses it.
	 * </p>
	 *
	 * @return true when an administrator was created, false when there already was one
	 */
	public boolean bootstrap(char[] password) throws IOException {
		if (!isEmpty()) {
			return false;
		}
		List<Grant> everything = new ArrayList<>();
		for (Privilege privilege : Privilege.values()) {
			everything.add(Grant.on(privilege));
		}
		long now = System.currentTimeMillis();
		mutate(current -> new AuthFile(current.generation(),
			List.of(new Role(Role.ADMIN, everything)),
			List.of(new StoredUser(new User(ADMIN, true, now, now, List.of(Role.ADMIN), List.of()),
				PasswordHash.of(password)))));
		return true;
	}

	/**
	 * Puts the administrator back: resets the password, recreates the user if it is gone, and re-grants everything.
	 *
	 * <p>
	 * All three, because an administrator whose grants were revoked is exactly as locked out as one whose password was
	 * lost, and somebody reaching for this command has no way to tell which happened. A recovery command that fixed
	 * only the case its name mentions would leave them running it repeatedly and wondering why nothing changed.
	 * </p>
	 */
	public void resetAdmin(char[] password) throws IOException {
		long now = System.currentTimeMillis();
		List<Grant> everything = new ArrayList<>();
		for (Privilege privilege : Privilege.values()) {
			everything.add(Grant.on(privilege));
		}
		mutate(current -> {
			List<Role> roles = new ArrayList<>(current.roles());
			roles.removeIf(role -> role.name().equals(Role.ADMIN));
			roles.add(new Role(Role.ADMIN, everything));

			StoredUser existing = current.user(ADMIN);
			User profile = existing == null
				? new User(ADMIN, true, now, now, List.of(Role.ADMIN), List.of())
				: new User(ADMIN, true, existing.user().createdAtEpochMillis(), now,
					withAdminRole(existing.user().roles()), existing.user().grants());

			List<StoredUser> users = new ArrayList<>(current.users());
			users.removeIf(user -> user.user().name().equals(ADMIN));
			users.add(new StoredUser(profile, PasswordHash.of(password)));
			return new AuthFile(current.generation(), roles, users);
		});
	}

	private static List<String> withAdminRole(List<String> roles) {
		Set<String> merged = new LinkedHashSet<>(roles);
		merged.add(Role.ADMIN);
		return List.copyOf(merged);
	}

	public void createUser(String name, char[] password, List<String> roles) throws IOException {
		AuthNames.validate("user", name);
		long now = System.currentTimeMillis();
		mutate(current -> {
			if (current.user(name) != null) {
				throw new AuthException("There is already a user called '" + name + "'");
			}
			for (String role : roles) {
				if (current.role(role) == null) {
					throw new AuthException("There is no role called '" + role + "'");
				}
			}
			List<StoredUser> users = new ArrayList<>(current.users());
			users.add(new StoredUser(new User(name, true, now, now, roles, List.of()), PasswordHash.of(password)));
			return new AuthFile(current.generation(), current.roles(), users);
		});
	}

	public void dropUser(String name) throws IOException {
		mutate(current -> {
			if (current.user(name) == null) {
				throw new AuthException("There is no user called '" + name + "'");
			}
			if (current.users().size() == 1) {
				// The same rule GraphDatabases applies to the last database, and for the same reason: a server nobody
				// can log into has no useful behaviour, and this is the edit that would produce one.
				throw new AuthException("'" + name + "' is the only user; removing them would leave a server nobody "
					+ "can log into. Create another user first.");
			}
			List<StoredUser> users = new ArrayList<>(current.users());
			users.removeIf(user -> user.user().name().equals(name));
			return new AuthFile(current.generation(), current.roles(), users);
		});
	}

	public void setPassword(String name, char[] password) throws IOException {
		long now = System.currentTimeMillis();
		mutate(current -> {
			StoredUser existing = current.user(name);
			if (existing == null) {
				throw new AuthException("There is no user called '" + name + "'");
			}
			List<StoredUser> users = new ArrayList<>(current.users());
			users.replaceAll(user -> user.user().name().equals(name)
				? user.withPassword(PasswordHash.of(password), now)
				: user);
			return new AuthFile(current.generation(), current.roles(), users);
		});
	}

	public void setEnabled(String name, boolean enabled) throws IOException {
		mutate(current -> {
			StoredUser existing = current.user(name);
			if (existing == null) {
				throw new AuthException("There is no user called '" + name + "'");
			}
			User profile = existing.user();
			User replacement = new User(profile.name(), enabled, profile.createdAtEpochMillis(),
				profile.passwordChangedAtEpochMillis(), profile.roles(), profile.grants());
			List<StoredUser> users = new ArrayList<>(current.users());
			users.replaceAll(user -> user.user().name().equals(name) ? user.withUser(replacement) : user);
			return new AuthFile(current.generation(), current.roles(), users);
		});
	}

	public void setUserRoles(String name, List<String> roles) throws IOException {
		mutate(current -> {
			StoredUser existing = current.user(name);
			if (existing == null) {
				throw new AuthException("There is no user called '" + name + "'");
			}
			for (String role : roles) {
				if (current.role(role) == null) {
					throw new AuthException("There is no role called '" + role + "'");
				}
			}
			User profile = existing.user();
			User replacement = new User(profile.name(), profile.enabled(), profile.createdAtEpochMillis(),
				profile.passwordChangedAtEpochMillis(), roles, profile.grants());
			List<StoredUser> users = new ArrayList<>(current.users());
			users.replaceAll(user -> user.user().name().equals(name) ? user.withUser(replacement) : user);
			return new AuthFile(current.generation(), current.roles(), users);
		});
	}

	public void createRole(String name) throws IOException {
		AuthNames.validate("role", name);
		mutate(current -> {
			if (current.role(name) != null) {
				throw new AuthException("There is already a role called '" + name + "'");
			}
			List<Role> roles = new ArrayList<>(current.roles());
			roles.add(new Role(name, List.of()));
			return new AuthFile(current.generation(), roles, current.users());
		});
	}

	public void dropRole(String name) throws IOException {
		mutate(current -> {
			if (current.role(name) == null) {
				throw new AuthException("There is no role called '" + name + "'");
			}
			List<Role> roles = new ArrayList<>(current.roles());
			roles.removeIf(role -> role.name().equals(name));
			// Every reference goes with it. Leaving one behind would write a file that this build's own decoder
			// refuses, which is the worst possible outcome: an edit that succeeds and makes the server unstartable.
			List<StoredUser> users = new ArrayList<>(current.users());
			users.replaceAll(user -> {
				if (!user.user().roles().contains(name)) {
					return user;
				}
				List<String> remaining = new ArrayList<>(user.user().roles());
				remaining.remove(name);
				User profile = user.user();
				return user.withUser(new User(profile.name(), profile.enabled(), profile.createdAtEpochMillis(),
					profile.passwordChangedAtEpochMillis(), remaining, profile.grants()));
			});
			return new AuthFile(current.generation(), roles, users);
		});
	}

	public void grantToRole(String role, Grant grant) throws IOException {
		mutate(current -> {
			Role existing = current.role(role);
			if (existing == null) {
				throw new AuthException("There is no role called '" + role + "'");
			}
			if (existing.grants().contains(grant)) {
				return current;
			}
			List<Grant> grants = new ArrayList<>(existing.grants());
			grants.add(grant);
			List<Role> roles = new ArrayList<>(current.roles());
			roles.replaceAll(candidate -> candidate.name().equals(role) ? new Role(role, grants) : candidate);
			return new AuthFile(current.generation(), roles, current.users());
		});
	}

	public void revokeFromRole(String role, Grant grant) throws IOException {
		mutate(current -> {
			Role existing = current.role(role);
			if (existing == null) {
				throw new AuthException("There is no role called '" + role + "'");
			}
			List<Grant> grants = new ArrayList<>(existing.grants());
			if (!grants.remove(grant)) {
				throw new AuthException("The role '" + role + "' does not hold " + grant);
			}
			List<Role> roles = new ArrayList<>(current.roles());
			roles.replaceAll(candidate -> candidate.name().equals(role) ? new Role(role, grants) : candidate);
			return new AuthFile(current.generation(), roles, current.users());
		});
	}

	/**
	 * Applies an edit under the file lock, and writes the result atomically.
	 *
	 * <p>
	 * The sequence is: take the lock, re-read what is actually on disk, apply, write a temporary file, force it, move
	 * it over the real one, fsync the directory, release. Re-reading inside the lock is what makes an edit safe against
	 * a second process that changed the file since this one loaded it — without it, two administrators editing at once
	 * would each write their own view and the second would erase the first.
	 * </p>
	 */
	private synchronized void mutate(UnaryOperator<AuthFile> edit) throws IOException {
		checkOpen();
		Files.createDirectories(directory);
		try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock lock = tryLock(channel)) {

			AuthFile current = Files.isRegularFile(file) ? AuthCodec.decode(Files.readAllBytes(file)) : AuthFile.empty();
			AuthFile edited = edit.apply(current);
			if (edited == current) {
				// The edit was a no-op — granting something already granted. Not writing keeps the generation stable,
				// so a concurrent reader's reload is not woken for nothing.
				image = current;
				stamp();
				return;
			}
			AuthFile next = edited.withGeneration(current.generation() + 1);
			byte[] bytes = AuthCodec.encode(next);

			Path temp = directory.resolve(TEMP_FILE);
			try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
				out.write(java.nio.ByteBuffer.wrap(bytes));
				out.force(true);
			}
			restrictFile(temp);
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			syncDirectory();

			image = next;
			stamp();
			// The lock is released here rather than held, so an offline CLI can edit this file while a server runs.
			lock.release();
		} catch (OverlappingFileLockException e) {
			throw new AuthException("This process is already writing the identity file", e);
		}
	}

	private FileLock tryLock(FileChannel channel) throws IOException {
		FileLock lock = channel.tryLock();
		if (lock == null) {
			throw new AuthException("Another process is writing " + file + ". A server or a "
				+ "'metaloom-graph-cli auth' command holds the lock; try again in a moment.");
		}
		return lock;
	}

	/** Records what was on disk when the image was loaded, so {@link #reloadIfChanged()} can tell. */
	private void stamp() {
		try {
			loadedSize = Files.size(file);
			loadedModifiedMillis = Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			loadedSize = -1;
			loadedModifiedMillis = -1;
		}
	}

	private void syncDirectory() {
		// Best effort: a rename is not durable until the directory entry is, but not every filesystem lets a directory
		// be opened for this and failing the whole edit over it would be worse than the window it closes.
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException e) {
			logger.debug("Could not fsync {}: {}", directory, e.toString());
		}
	}

	private static void restrict(Path directory) {
		try {
			Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		} catch (IOException | UnsupportedOperationException e) {
			// A mounted volume may not permit this, and refusing to start over it would be worse than the exposure.
			logger.warn("Could not restrict {} to its owner: {}. Anything that can read the directory can read the "
				+ "password hashes in it.", directory, e.toString());
		}
	}

	private static void restrictFile(Path path) {
		try {
			Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (IOException | UnsupportedOperationException e) {
			logger.debug("Could not restrict {}: {}", path, e.toString());
		}
	}

	private void checkOpen() {
		if (closed) {
			throw new AuthException("This identity store is closed");
		}
	}

	/** The path the identity file lives at, for a message that has to name it. */
	public Path path() {
		return file;
	}

	@Override
	public void close() {
		closed = true;
	}
}
