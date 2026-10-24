package io.metaloom.graph.core.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Several stores under one root, one directory each.
 *
 * <pre>{@code
 * try (GraphDatabases databases = GraphDatabases.open(Path.of("/data/graphs"))) {
 *     GraphStore sales = databases.database("sales");
 *     Cypher.query(sales, "MATCH (n) RETURN count(*)");
 * }
 * }</pre>
 *
 * <p>
 * A database is a {@link GraphStore} and nothing more — this adds no layer between a caller and the engine. What it
 * adds is the part that is easy to get wrong: turning a name that arrived over a network into a directory, opening each
 * store at most once, and closing them all together.
 * </p>
 *
 * <h2>The name is the security boundary</h2>
 *
 * <p>
 * A database name reaches this class from an unauthenticated port, and it is about to become a path. {@link #validate}
 * is therefore an allow-list — letters, digits, {@code -} and {@code _} — rather than a search for the sequences that
 * are known to be dangerous. A deny-list would have to anticipate {@code ..}, {@code /}, {@code \}, a leading
 * {@code /}, a bare {@code .}, NUL, a Windows device name and whatever the filesystem does with Unicode normalisation;
 * an allow-list has to anticipate nothing.
 * </p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * Stores open lazily and stay open, because opening one maps every segment file and takes an exclusive {@code LOCK}.
 * A root with fifty databases and one in use costs one open store, not fifty. Closing this closes all of them.
 * </p>
 *
 * <p>
 * <b>Not thread-safe against concurrent {@link #create} and {@link #drop} of the same name</b>, which are
 * administrative operations; {@link #database} is safe to call from many threads and is the one on the hot path.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/DATABASES.md">spec/DATABASES.md</a>
 */
public final class GraphDatabases implements AutoCloseable {

	/** The database a caller gets when it does not name one. Created on first open if the root is empty. */
	public static final String DEFAULT_NAME = "default";

	/**
	 * Long enough for a descriptive name, short enough to stay well inside every filesystem's component limit — 255
	 * bytes on ext4, and a name is also going to appear in log lines and error messages.
	 */
	public static final int MAX_NAME_LENGTH = 64;

	private final Path root;
	private final GraphStoreOptions options;
	private final Map<String, GraphStore> open = new LinkedHashMap<>();
	private boolean closed;

	private GraphDatabases(Path root, GraphStoreOptions options) {
		this.root = root;
		this.options = options;
	}

	/**
	 * Opens a root directory, creating it and the default database if they do not exist.
	 *
	 * @throws GraphStoreException
	 *             if the directory is a store rather than a root of them. See {@link #isStoreDirectory}
	 */
	public static GraphDatabases open(Path root) throws IOException {
		return open(root, GraphStoreOptions.defaults());
	}

	public static GraphDatabases open(Path root, GraphStoreOptions options) throws IOException {
		if (isStoreDirectory(root)) {
			// Refused rather than adopted or migrated. Adopting it would leave one database without a directory of
			// its own, and moving somebody's data on their behalf — on a disk that might be full — is not a decision
			// this call gets to make.
			throw new GraphStoreException(root + " is a store directory, not a root of databases."
				+ " Each database now lives in its own subdirectory. Move it into one:\n"
				+ "  mkdir " + root + ".tmp\n"
				+ "  mv " + root + " " + root + ".tmp/" + DEFAULT_NAME + "\n"
				+ "  mv " + root + ".tmp " + root);
		}
		Files.createDirectories(root);
		GraphDatabases databases = new GraphDatabases(root, options);
		if (databases.names().isEmpty()) {
			// A root with no databases is not usable and not interesting, and the alternative is that every first
			// run fails with "no such database: default".
			databases.create(DEFAULT_NAME);
		}
		return databases;
	}

	/**
	 * Whether a directory holds a store rather than a set of them.
	 *
	 * <p>
	 * The manifest, because it is the one file the engine writes before anything else and the one it refuses to open
	 * without. A directory holding {@code seg/} but no manifest is a failed create, not a store.
	 * </p>
	 */
	public static boolean isStoreDirectory(Path path) {
		return Files.isRegularFile(path.resolve("MANIFEST"));
	}

	/**
	 * Checks a name that is about to become a path component.
	 *
	 * @throws IllegalArgumentException
	 *             with a message naming what is allowed rather than what was wrong, because the caller who sees it is
	 *             usually about to pick a different name
	 */
	public static void validate(String name) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("A database name is required");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("A database name may be at most " + MAX_NAME_LENGTH
				+ " characters, '" + name + "' is " + name.length());
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| c == '-' || c == '_';
			if (!allowed) {
				throw new IllegalArgumentException("A database name may hold only letters, digits, '-' and '_';"
					+ " '" + name + "' has '" + c + "' at position " + i);
			}
		}
		if (name.charAt(0) == '-') {
			// Not a path problem — a name starting with a dash is a name that will one day be read as a flag.
			throw new IllegalArgumentException("A database name may not start with '-', was '" + name + "'");
		}
	}

	/** The names present under the root, sorted, whether or not they are open. */
	public List<String> names() throws IOException {
		checkOpen();
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		try (Stream<Path> entries = Files.list(root)) {
			for (Path entry : (Iterable<Path>) entries::iterator) {
				String name = entry.getFileName().toString();
				// A directory nobody here created, or one with a name this build would refuse, is listed only if it
				// is a real store — so a half-made directory or an editor's backup does not appear as a database.
				if (Files.isDirectory(entry) && isValid(name) && isStoreDirectory(entry)) {
					names.add(name);
				}
			}
		}
		names.sort(Comparator.naturalOrder());
		return names;
	}

	public boolean exists(String name) throws IOException {
		validate(name);
		return isStoreDirectory(root.resolve(name));
	}

	/**
	 * The store for a name, opening it the first time it is asked for.
	 *
	 * @throws GraphStoreException
	 *             if there is no such database. Deliberately not created on demand: this is reached from an
	 *             unauthenticated port, and a typo that silently makes a database is a typo that fills a disk
	 */
	public GraphStore database(String name) throws IOException {
		validate(name);
		synchronized (this) {
			checkOpen();
			GraphStore store = open.get(name);
			if (store != null) {
				return store;
			}
			if (!isStoreDirectory(root.resolve(name))) {
				throw new GraphStoreException("No database '" + name + "'");
			}
			return openStore(name);
		}
	}

	/**
	 * Creates a database and opens it.
	 *
	 * @throws GraphStoreException
	 *             if one of that name already exists. Creating over an existing store would either lose it or
	 *             silently hand back somebody else's data, and neither is a thing to guess at
	 */
	public GraphStore create(String name) throws IOException {
		return create(name, options);
	}

	/**
	 * Creates a database with options of its own, rather than the root's.
	 *
	 * <p>
	 * Almost every caller wants {@link #create(String)}: one root, one set of options, no surprises. This exists for
	 * the one case where the options are not the caller's to choose — <b>restoring a backup</b>. Page size, segment
	 * capacity and vector dimensions are fixed when a store is created and are recorded in its manifest, so a dump
	 * taken from a store with 768-dimension vectors cannot be replayed faithfully into a root whose default has none.
	 * Without this the restore would either refuse or, worse, quietly produce a store that could not hold the data it
	 * was given.
	 * </p>
	 *
	 * <p>
	 * The options are <b>not</b> remembered for the root: a later {@code database(name)} reopens with the root's, and
	 * the geometry checks in the segment layer refuse a mismatch loudly. That is the correct failure — the store's own
	 * manifest is the authority on what it was created with, and this method does not get to change what the root
	 * means.
	 * </p>
	 */
	public GraphStore create(String name, GraphStoreOptions storeOptions) throws IOException {
		validate(name);
		synchronized (this) {
			checkOpen();
			Path path = root.resolve(name);
			if (isStoreDirectory(path)) {
				throw new GraphStoreException("A database '" + name + "' already exists");
			}
			Files.createDirectories(path);
			GraphStore store = GraphStore.open(path, storeOptions == null ? options : storeOptions);
			open.put(name, store);
			return store;
		}
	}

	/**
	 * Replaces a database with an empty one, whether or not it existed.
	 *
	 * <p>
	 * For <b>restoring a backup</b>, which is the one operation that legitimately destroys a database in order to put
	 * one back, and which {@link #drop} followed by {@link #create} cannot express: dropping refuses to remove the last
	 * database in a root, and a root opened for a restore very often holds exactly one — the {@code default} that
	 * {@link #open} created when it found the directory empty. The most natural restore there is, into a fresh root,
	 * would otherwise be the one that could not be done.
	 * </p>
	 *
	 * <p>
	 * That refusal is still right where it applies. It exists so that a running server cannot be left serving nothing,
	 * and nothing here weakens it: the replacement is created before the lock is released, so the root is never
	 * observed empty. This is not "drop without the check", it is one step that has no intermediate state.
	 * </p>
	 *
	 * <p>
	 * The store options are the dump's, not the root's, for the reason spelled out on {@link #create(String,
	 * GraphStoreOptions)}: geometry is fixed at creation, so this is the only moment at which a target can be made able
	 * to hold what it is about to be given.
	 * </p>
	 */
	public GraphStore replace(String name, GraphStoreOptions storeOptions) throws IOException {
		validate(name);
		synchronized (this) {
			checkOpen();
			GraphStore existing = open.remove(name);
			if (existing != null) {
				// Closed before the directory goes, always. Unlinking a mapped file leaves the mapping valid and the
				// store convinced it still has somewhere to write.
				existing.close();
			}
			Path path = root.resolve(name);
			if (Files.exists(path)) {
				deleteRecursively(path);
			}
			Files.createDirectories(path);
			GraphStore store = GraphStore.open(path, storeOptions == null ? options : storeOptions);
			open.put(name, store);
			return store;
		}
	}

	/**
	 * Opens an empty store <b>beside</b> a database, for building its replacement without touching it.
	 *
	 * <p>
	 * For a restore that may fail late. A dump's checksum is in its trailer, so it is only known to be whole once all
	 * of it has been replayed; {@link #replace} would by then have deleted the original. The staged store lives in a
	 * dot-directory under the root, which {@link #validate} can never accept as a name, so {@link #names} never lists
	 * it and no request can address it. A leftover from an earlier, interrupted attempt is cleared first.
	 * </p>
	 *
	 * <p>
	 * The store returned is the caller's to close. Then either {@link #install} puts it in place or
	 * {@link #discardStaged} throws it away.
	 * </p>
	 */
	public GraphStore stage(String name, GraphStoreOptions storeOptions) throws IOException {
		validate(name);
		synchronized (this) {
			checkOpen();
			Path staging = stagingPathOf(name);
			if (Files.exists(staging)) {
				deleteRecursively(staging);
			}
			Files.createDirectories(staging);
			return GraphStore.open(staging, storeOptions == null ? options : storeOptions);
		}
	}

	/**
	 * Swaps a store built by {@link #stage}, and since closed, in for the database of that name.
	 *
	 * <p>
	 * Two renames within the root rather than a copy: the original is moved aside, the staged directory moved in, and
	 * only then is the original deleted. Like {@link #replace} it does not refuse to replace the last database.
	 * </p>
	 */
	public GraphStore install(String name, GraphStoreOptions storeOptions) throws IOException {
		validate(name);
		synchronized (this) {
			checkOpen();
			Path staging = stagingPathOf(name);
			if (!isStoreDirectory(staging)) {
				throw new GraphStoreException("Nothing has been staged for '" + name + "'");
			}
			GraphStore existing = open.remove(name);
			if (existing != null) {
				// Closed before the directory moves, for the reason replace() gives.
				existing.close();
			}
			Path path = root.resolve(name);
			Path aside = root.resolve(".replaced-" + name);
			if (Files.exists(aside)) {
				deleteRecursively(aside);
			}
			if (Files.exists(path)) {
				Files.move(path, aside, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
			Files.move(staging, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			if (Files.exists(aside)) {
				deleteRecursively(aside);
			}
			GraphStore store = GraphStore.open(path, storeOptions == null ? options : storeOptions);
			open.put(name, store);
			return store;
		}
	}

	/** Deletes what {@link #stage} made, once its store is closed. No error if there is nothing. */
	public void discardStaged(String name) throws IOException {
		validate(name);
		synchronized (this) {
			Path staging = stagingPathOf(name);
			if (Files.exists(staging)) {
				deleteRecursively(staging);
			}
		}
	}

	/** A dot-name, so never a valid database name and never listed. */
	private Path stagingPathOf(String name) {
		return root.resolve(".staging-" + name);
	}

	/**
	 * Closes a database and deletes its directory.
	 *
	 * @return false if there was no such database, so that dropping twice is not an error
	 * @throws GraphStoreException
	 *             if it is the last one. A root with no databases cannot be reopened without one being made, and a
	 *             server holding no database at all is a state with no useful behaviour. {@link #replace} is the way
	 *             to put a database back over the last one
	 */
	public boolean drop(String name) throws IOException {
		validate(name);
		synchronized (this) {
			checkOpen();
			if (!isStoreDirectory(root.resolve(name))) {
				return false;
			}
			if (names().size() <= 1) {
				throw new GraphStoreException("'" + name + "' is the only database; a root has to hold at least one");
			}
			GraphStore store = open.remove(name);
			if (store != null) {
				// Closed first, always. Unlinking a mapped file leaves the mapping valid and the store convinced it
				// still has somewhere to write, which is a far stranger failure than a refusal.
				store.close();
			}
			deleteRecursively(root.resolve(name));
			return true;
		}
	}

	/** The directory a database lives in, whether or not it exists. */
	public Path pathOf(String name) {
		validate(name);
		return root.resolve(name);
	}

	public Path root() {
		return root;
	}

	/** The databases currently open, for a caller that has to sweep them — a watchdog, a stats page. */
	public synchronized List<GraphStore> openDatabases() {
		return List.copyOf(open.values());
	}

	public synchronized boolean isOpen(String name) {
		return open.containsKey(name);
	}

	@Override
	public synchronized void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		IOException first = null;
		for (GraphStore store : open.values()) {
			try {
				store.close();
			} catch (IOException e) {
				first = first == null ? e : first;
			}
		}
		open.clear();
		if (first != null) {
			throw first;
		}
	}

	// ---------------------------------------------------------------- internals

	private GraphStore openStore(String name) throws IOException {
		GraphStore store = GraphStore.open(root.resolve(name), options);
		open.put(name, store);
		return store;
	}

	private void checkOpen() {
		if (closed) {
			throw new IllegalStateException("This database root is closed");
		}
	}

	private static boolean isValid(String name) {
		try {
			validate(name);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static void deleteRecursively(Path path) throws IOException {
		try (Stream<Path> walk = Files.walk(path)) {
			// Deepest first, or a directory is deleted before the files in it.
			for (Path entry : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(entry);
			}
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}
}
