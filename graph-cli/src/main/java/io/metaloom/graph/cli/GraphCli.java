package io.metaloom.graph.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.metaloom.graph.cli.dump.DumpFormatException;
import io.metaloom.graph.cli.dump.DumpReader;
import io.metaloom.graph.cli.dump.GraphBackup;
import io.metaloom.graph.cli.dump.GraphRestore;
import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.api.Role;
import io.metaloom.graph.core.api.User;
import io.metaloom.graph.core.api.VerifyLevel;

/**
 * {@code metaloom-graph-cli} — back up and restore a database.
 *
 * <pre>
 * metaloom-graph-cli database backup  mydb --path=/data/graphs --out=mydb.dump
 * metaloom-graph-cli database restore mydb --server=http://localhost:8080 --in=mydb.dump
 * </pre>
 *
 * <p>
 * Everything user-facing goes to <b>stderr</b> and only the dump goes to stdout, so {@code --out=-} can be piped
 * without the progress lines ending up in the file. That is the whole reason for the split, and it is why a backup that
 * printed its summary to stdout would be quietly broken for the one case the {@code -} spelling exists for.
 * </p>
 *
 * @see <a href="../../../../../../../spec/BACKUP.md">spec/BACKUP.md</a>
 */
public final class GraphCli {

	private final CliOptions options;
	private final PrintStream err;
	private final OutputStream stdout;
	private final InputStream stdin;

	GraphCli(CliOptions options, PrintStream err, OutputStream stdout, InputStream stdin) {
		this.options = options;
		this.err = err;
		this.stdout = stdout;
		this.stdin = stdin;
	}

	public static void main(String[] args) {
		CliOptions options;
		try {
			options = CliOptions.parse(args, System::getenv, System.in);
		} catch (CliOptions.HelpRequested help) {
			System.out.println(CliOptions.usage());
			return;
		} catch (IllegalArgumentException invalid) {
			System.err.println(invalid.getMessage());
			System.err.println();
			System.err.println("Run with --help for the full list.");
			System.exit(2);
			return;
		}
		int status = new GraphCli(options, System.err, System.out, System.in).run();
		if (status != 0) {
			System.exit(status);
		}
	}

	/**
	 * @return the process exit status: 0 for success, 1 for a failure, 2 for a usage mistake
	 */
	int run() {
		try {
			switch (options.command()) {
				case "database backup" -> backup();
				case "database restore" -> restore();
				case "database list" -> list();
				case "database inspect" -> inspect();
				case "auth reset-admin" -> resetAdmin();
				case "auth list" -> authList();
				case "auth user" -> authUser();
				case "auth role" -> authRole();
				case "auth grant" -> authGrant(true);
				case "auth revoke" -> authGrant(false);
				default -> {
					err.println("Unknown command '" + options.command() + "'.");
					err.println("Try: " + String.join(" | ", commands()));
					return 2;
				}
			}
			return 0;
		} catch (DumpFormatException bad) {
			// Its own branch because it is the one failure that is about the *file* rather than about the store, and
			// the answer is different: check the dump, not the database.
			err.println("The dump could not be read: " + bad.getMessage());
			return 1;
		} catch (GraphBackup.BackupAbandonedException abandoned) {
			err.println(abandoned.getMessage());
			return 1;
		} catch (GraphStoreException store) {
			err.println(store.getMessage());
			if (String.valueOf(store.getMessage()).contains("locked by another process")) {
				// The single most likely mistake with --path, and the message the engine gives is about locks rather
				// than about what to do instead.
				err.println();
				err.println("A database that a server has open cannot also be opened here — one process writes. "
					+ "Use --server=<url> to work through the running server, or stop it first.");
			}
			return 1;
		} catch (IOException | RuntimeException failure) {
			err.println(failure.getMessage() == null ? failure.toString() : failure.getMessage());
			return 1;
		}
	}

	// ---------------------------------------------------------------- backup

	private void backup() throws IOException {
		if (options.file() == null) {
			throw new IllegalArgumentException("backup needs --out=<file>, or --out=- to write to stdout");
		}
		long started = System.nanoTime();
		// Whichever path this takes, the counts printed below come out of the dump itself rather than out of the
		// engine or off the wire: offline they are what was just written, online they are read back off the bytes as
		// they go past. A summary that agreed with the walk but not with the file would be the wrong one.
		GraphBackup.Summary summary;
		try (OutputStream out = openOut()) {
			if (options.offline()) {
				try (GraphDatabases databases = GraphDatabases.open(options.path())) {
					GraphStore store = databases.database(options.database());
					summary = GraphBackup.write(store, options.database(), out, System.currentTimeMillis(),
						progress());
				}
			} else {
				summary = RemoteGraph.backup(options.server(), options.database(), out, progress());
			}
		}
		Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
		err.printf("Backed up '%s': %,d nodes, %,d relationships, %,d lightweight edges, %,d bytes in %s%n",
			options.database(), summary.nodes(), summary.relationships(), summary.lightweightEdges(),
			summary.bytes(), human(elapsed));
		if (!options.streamed()) {
			err.println("Written to " + options.file());
		}
	}

	// ---------------------------------------------------------------- restore

	private void restore() throws IOException {
		if (options.file() == null) {
			throw new IllegalArgumentException("restore needs --in=<file>, or --in=- to read stdin");
		}
		if (!options.offline()) {
			throw new IllegalArgumentException("restore over --server is not available in this build: it would have "
				+ "to overwrite a database the server has open. Stop the server and restore with --path, which is "
				+ "also the mode that can create the target with the dump's own geometry.");
		}
		long started = System.nanoTime();
		try (DumpReader in = openIn();
			GraphDatabases databases = GraphDatabases.open(options.path())) {
			GraphRestore.Header header = GraphRestore.header(in);
			err.printf("Restoring a dump of '%s' taken %s%n", header.database(),
				Instant.ofEpochMilli(header.takenAtEpochMillis()));

			// An empty database is not something anyone is asked to confirm losing, and one is very often there
			// without having been asked for: opening a root that does not exist yet creates a `default` inside it.
			// Requiring --force to restore into that would mean the commonest restore of all — a dump into a fresh
			// root, on a machine where the data used to be — was the one that needed a flag about overwriting
			// something the user never made and that holds nothing.
			boolean exists = databases.exists(options.database());
			boolean occupied = exists && databases.database(options.database()).stats().nodeCount() > 0;
			if (occupied && !options.force()) {
				throw new GraphStoreException("A database '" + options.database() + "' already exists and holds data. "
					+ "A restore replaces it whole rather than merging into it, so it will not do that without "
					+ "--force.");
			}
			if (occupied) {
				err.println("Replacing the existing '" + options.database() + "', because --force was given");
			}
			// One step, not drop-then-create: dropping refuses to remove the last database in a root, and the target
			// here is very often the only one there is. Created with the DUMP's geometry, not the root's — page size,
			// segment capacity and vector dimensions are fixed at creation, so this is the only moment at which a
			// target can be made able to hold the data.
			GraphStore store = databases.replace(options.database(), header.geometry());
			GraphRestore.Summary summary = GraphRestore.readBody(store, in, header);

			err.println("Verifying the restored store");
			store.verify(VerifyLevel.FULL);

			Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
			err.printf("Restored '%s': %,d nodes, %,d relationships, %,d lightweight edges, "
				+ "%d indexes, %d unique constraints, %d vector indexes, in %s%n",
				options.database(), summary.nodes(), summary.relationships(), summary.lightweightEdges(),
				summary.compositeIndexes(), summary.uniqueConstraints(), summary.vectorIndexes(), human(elapsed));
			err.println("Element ids are new: anything outside the store that recorded one is stale.");
		}
	}

	// ---------------------------------------------------------------- list and inspect

	private void list() throws IOException {
		if (options.offline()) {
			try (GraphDatabases databases = GraphDatabases.open(options.path())) {
				for (String name : databases.names()) {
					println(name);
				}
			}
		} else {
			for (String name : RemoteGraph.databases(options.server())) {
				println(name);
			}
		}
	}

	/** Reads a dump's header without opening any store, which is what makes a bad file cheap to diagnose. */
	private void inspect() throws IOException {
		if (options.file() == null) {
			throw new IllegalArgumentException("inspect needs --in=<file>");
		}
		try (DumpReader in = openIn()) {
			GraphRestore.Header header = GraphRestore.header(in);
			println("format version   " + header.version());
			println("database         " + header.database());
			println("taken            " + Instant.ofEpochMilli(header.takenAtEpochMillis()));
			println("snapshot lsn     " + header.snapshotLsn());
			println("page size        " + header.geometry().pageSize());
			println("segment capacity " + header.geometry().segmentCapacity());
			println("vector dims      " + (header.geometry().vectorDimensions() == 0 ? "none"
				: header.geometry().vectorDimensions()));
		}
	}

	// ---------------------------------------------------------------- auth

	/**
	 * Puts the administrator back, however they were locked out.
	 *
	 * <p>
	 * The generated password goes to <b>stdout</b> and every word of explanation to stderr, so
	 * {@code auth reset-admin --path=/data > password.txt} yields a file holding the password and nothing else. That
	 * is the same split {@code --out=-} relies on for a dump, applied to the one other thing this program produces
	 * that a script might want to capture.
	 * </p>
	 */
	private void resetAdmin() throws IOException {
		requireOffline("auth reset-admin");
		boolean generated = options.password() == null;
		char[] password = passwordOrGenerated();
		try {
			try (AuthStore auth = openAuth()) {
				auth.resetAdmin(password);
				err.println("Reset the '" + AuthStore.ADMIN + "' user in " + auth.path() + ": password set, user "
					+ "recreated if it was missing, and every privilege re-granted on every database.");
			}
			if (generated) {
				println(new String(password));
				err.println("The password above is on stdout and is not stored anywhere else. It is shown once.");
			}
			err.println("A running server picks this up on the next login; it does not need a restart.");
		} finally {
			scrub(password);
		}
	}

	private void authList() throws IOException {
		requireOffline("auth list");
		try (AuthStore auth = openAuth()) {
			if (auth.isEmpty()) {
				err.println("There are no users in " + auth.path() + ". A server with authentication enabled will "
					+ "create an administrator when it starts.");
				return;
			}
			println("users");
			for (User user : auth.users()) {
				println("  " + user.name() + (user.enabled() ? "" : "  (disabled)")
					+ (user.roles().isEmpty() ? "" : "  roles: " + String.join(", ", user.roles())));
				for (Grant grant : user.grants()) {
					println("    " + grant);
				}
			}
			println("roles");
			for (Role role : auth.roles()) {
				println("  " + role.name());
				for (Grant grant : role.grants()) {
					println("    " + grant);
				}
			}
		}
	}

	private void authUser() throws IOException {
		requireOffline("auth user");
		List<String> arguments = options.arguments();
		if (arguments.size() < 2) {
			throw new IllegalArgumentException("auth user needs 'add <name>' or 'drop <name>'");
		}
		String action = arguments.get(0);
		String name = arguments.get(1);
		try (AuthStore auth = openAuth()) {
			switch (action) {
				case "add" -> {
					boolean generated = options.password() == null;
					char[] password = passwordOrGenerated();
					try {
						auth.createUser(name, password, arguments.subList(2, arguments.size()));
						err.println("Created the user '" + name + "'.");
						if (generated) {
							println(new String(password));
							err.println("The password above is on stdout and is shown once.");
						}
					} finally {
						scrub(password);
					}
				}
				case "drop" -> {
					auth.dropUser(name);
					err.println("Removed the user '" + name + "'.");
				}
				default -> throw new IllegalArgumentException("auth user takes 'add' or 'drop', not '" + action + "'");
			}
		}
	}

	private void authRole() throws IOException {
		requireOffline("auth role");
		List<String> arguments = options.arguments();
		if (arguments.size() < 2) {
			throw new IllegalArgumentException("auth role needs 'add <name>' or 'drop <name>'");
		}
		String action = arguments.get(0);
		String name = arguments.get(1);
		try (AuthStore auth = openAuth()) {
			switch (action) {
				case "add" -> {
					auth.createRole(name);
					err.println("Created the role '" + name + "'.");
				}
				case "drop" -> {
					auth.dropRole(name);
					err.println("Removed the role '" + name + "', and every reference to it.");
				}
				default -> throw new IllegalArgumentException("auth role takes 'add' or 'drop', not '" + action + "'");
			}
		}
	}

	private void authGrant(boolean granting) throws IOException {
		String command = granting ? "auth grant" : "auth revoke";
		requireOffline(command);
		List<String> arguments = options.arguments();
		if (arguments.size() != 3) {
			throw new IllegalArgumentException(command + " needs '<role> <PRIVILEGE> <database|"
				+ Grant.ALL_DATABASES + ">', for example '" + command + " analyst MERGE sales'");
		}
		Grant grant = new Grant(Privilege.parse(arguments.get(1)), arguments.get(2));
		try (AuthStore auth = openAuth()) {
			if (granting) {
				auth.grantToRole(arguments.get(0), grant);
				err.println("Granted " + grant + " to '" + arguments.get(0) + "'.");
			} else {
				auth.revokeFromRole(arguments.get(0), grant);
				err.println("Revoked " + grant + " from '" + arguments.get(0) + "'.");
			}
		}
	}

	private AuthStore openAuth() throws IOException {
		return AuthStore.open(options.path());
	}

	/** The auth commands never take {@code --server}; {@code CliOptions} refuses it, and this is the second line. */
	private void requireOffline(String command) {
		if (!options.offline()) {
			throw new IllegalArgumentException(command + " needs --path=<root>");
		}
	}

	/**
	 * The password given, or a fresh one.
	 *
	 * <p>
	 * Twenty-four characters from a thirty-two symbol alphabet is 120 bits, which is far past anything that will be
	 * attacked offline. {@code I}, {@code O}, {@code 0} and {@code 1} are left out because this string's most likely
	 * next journey is being read off one screen and typed into another.
	 * </p>
	 */
	private char[] passwordOrGenerated() {
		if (options.password() != null) {
			return options.password().toCharArray();
		}
		return generatePassword();
	}

	/**
	 * Best-effort scrub of a password this program is finished with.
	 *
	 * <p>
	 * Written out here rather than borrowed from the engine's {@code PasswordHash}, which lives in a package
	 * {@code module-info} does not export: reaching into it happens to work on the classpath and would stop working
	 * the day anybody put this on the module path.
	 * </p>
	 */
	private static void scrub(char[] password) {
		if (password != null) {
			java.util.Arrays.fill(password, '\0');
		}
	}

	/** The alphabet, and the length, shared with the server's first-start announcement. */
	static char[] generatePassword() {
		final String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
		SecureRandom random = new SecureRandom();
		char[] password = new char[24];
		for (int i = 0; i < password.length; i++) {
			password[i] = alphabet.charAt(random.nextInt(alphabet.length()));
		}
		return password;
	}

	// ---------------------------------------------------------------- plumbing

	private void println(String line) {
		try {
			stdout.write((line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			stdout.flush();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private OutputStream openOut() throws IOException {
		if (options.streamed()) {
			return new NonClosing(stdout);
		}
		return new java.io.BufferedOutputStream(Files.newOutputStream(Path.of(options.file())));
	}

	private DumpReader openIn() throws IOException {
		if (options.streamed()) {
			// Length unknown, so the reader's length checks fall back to "believe it" — see DumpReader. A file is
			// strictly better and is what the help text leads with.
			return new DumpReader(new java.io.BufferedInputStream(stdin), Long.MAX_VALUE);
		}
		Path file = Path.of(options.file());
		if (!Files.isReadable(file)) {
			throw new IOException("Cannot read " + file);
		}
		return new DumpReader(new java.io.BufferedInputStream(Files.newInputStream(file)), Files.size(file));
	}

	private GraphBackup.Progress progress() {
		if (options.quiet()) {
			return GraphBackup.Progress.NONE;
		}
		return (done, total) -> err.printf("\r  %,d / %,d nodes", done, total);
	}

	private static String human(Duration elapsed) {
		long millis = elapsed.toMillis();
		return millis < 1000 ? millis + " ms" : String.format("%.1f s", millis / 1000.0);
	}

	/** stdout must survive the dump being "closed", or `list` after a piped backup would write to a dead stream. */
	private static final class NonClosing extends java.io.FilterOutputStream {

		NonClosing(OutputStream out) {
			super(out);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
		}

		@Override
		public void close() throws IOException {
			out.flush();
		}
	}

	static List<String> commands() {
		return List.of("database backup", "database restore", "database list", "database inspect",
			"auth reset-admin", "auth list", "auth user", "auth role", "auth grant", "auth revoke");
	}
}
