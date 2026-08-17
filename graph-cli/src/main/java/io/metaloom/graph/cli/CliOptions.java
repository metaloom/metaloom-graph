package io.metaloom.graph.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The command line and the environment, parsed into a value.
 *
 * <p>
 * The same shape as {@code GraphServerOptions} in graph-server, deliberately: a record, a {@code parse} that takes the
 * environment as a function so it can be tested without one, a declared {@link #VARIABLES} list that the help text is
 * generated from, and a {@link HelpRequested} exception rather than a {@code System.exit} buried in a parser. Two
 * spellings of "read the command line" in one repository would be one too many.
 * </p>
 *
 * <h2>Offline and online are one flag apart</h2>
 *
 * <p>
 * {@code --path} opens the store directly and {@code --server} drives a running one over HTTP. Exactly one is required,
 * and the reason they are not interchangeable is worth knowing: <b>opening a store takes an exclusive {@code LOCK}</b>,
 * so {@code --path} cannot be used against a database a server currently has open. That is not a limitation to work
 * around — it is the engine's guarantee that one process writes — and the error says so rather than leaving somebody to
 * interpret "locked by another process".
 * </p>
 *
 * @param command
 *            the subcommand, already resolved, e.g. {@code database backup}
 * @param database
 *            the database to act on
 * @param path
 *            a root of databases to open directly, or null when {@code server} is set
 * @param server
 *            the base URL of a running server, or null when {@code path} is set
 * @param file
 *            where the dump is written or read; {@code -} means stdout or stdin
 * @param force
 *            allow a restore to replace a database that already exists
 * @param quiet
 *            suppress progress on stderr
 */
public record CliOptions(String command, String database, Path path, String server, String file, boolean force,
	boolean quiet) {

	/** Raised by {@code --help}, so the parser never prints or exits by itself. */
	public static final class HelpRequested extends RuntimeException {

		private static final long serialVersionUID = 1L;
	}

	public record EnvVar(String name, String description) {
	}

	/** Every variable this CLI reads, in the order the help text prints them. */
	public static final List<EnvVar> VARIABLES = List.of(
		new EnvVar("GRAPH_ROOT", "A root of databases to open directly, as --path does."),
		new EnvVar("GRAPH_SERVER", "The base URL of a running server, as --server does."),
		new EnvVar("GRAPH_DATABASE", "The database to act on when the command does not name one."));

	/**
	 * The one command that reaches no store and no server.
	 *
	 * <p>
	 * {@code inspect} reads a dump's header and stops, which is exactly what makes a bad dump cheap to diagnose:
	 * demanding a transport for it would mean needing a working database in hand before being allowed to ask why a
	 * file will not restore, which is the moment at which one is least likely to have one.
	 * </p>
	 */
	private static final List<String> TRANSPORTLESS = List.of("database inspect");

	public CliOptions {
		if (path == null && server == null && !TRANSPORTLESS.contains(command)) {
			throw new IllegalArgumentException("Give --path <root> to open a store directly, or --server <url> to "
				+ "use a running one. There is no default: the two do different things, and guessing which was meant "
				+ "would be guessing whether to take a lock on somebody's data");
		}
		if (path != null && server != null) {
			throw new IllegalArgumentException("--path and --server are alternatives; pass one");
		}
	}

	public boolean offline() {
		return path != null;
	}

	/** True when the dump is stdin or stdout rather than a named file. */
	public boolean streamed() {
		return "-".equals(file);
	}

	public static CliOptions parse(String[] args) {
		return parse(args, System::getenv);
	}

	public static CliOptions parse(String[] args, UnaryOperator<String> env) {
		Path path = null;
		String server = null;
		String database = null;
		String file = null;
		boolean force = false;
		boolean quiet = false;
		List<String> words = new ArrayList<>();

		String value;
		if ((value = read(env, "GRAPH_ROOT")) != null) {
			path = Path.of(value);
		}
		if ((value = read(env, "GRAPH_SERVER")) != null) {
			server = value;
		}
		if ((value = read(env, "GRAPH_DATABASE")) != null) {
			database = value;
		}

		for (String argument : args) {
			if (argument.equals("--help") || argument.equals("-h")) {
				throw new HelpRequested();
			} else if (argument.equals("--force")) {
				force = true;
			} else if (argument.equals("--quiet") || argument.equals("-q")) {
				quiet = true;
			} else if (argument.startsWith("--path=")) {
				path = Path.of(after(argument));
			} else if (argument.startsWith("--server=")) {
				server = after(argument);
			} else if (argument.startsWith("--out=")) {
				file = after(argument);
			} else if (argument.startsWith("--in=")) {
				file = after(argument);
			} else if (argument.startsWith("--database=")) {
				database = after(argument);
			} else if (argument.startsWith("-")) {
				throw new IllegalArgumentException("Unknown option '" + argument + "'. Run with --help.");
			} else {
				words.add(argument);
			}
		}

		if (words.isEmpty()) {
			throw new IllegalArgumentException("No command given. Run with --help.");
		}
		// Two words make a command — `database backup` — and anything after them is the database name. Resolved here
		// rather than in a dispatcher so that an unknown command fails before a store is opened.
		String command = words.size() >= 2 ? words.get(0) + " " + words.get(1) : words.get(0);
		if (words.size() > 2 && database == null) {
			database = words.get(2);
		}
		if (words.size() > 3) {
			throw new IllegalArgumentException("Unexpected argument '" + words.get(3) + "'. Run with --help.");
		}
		if (database == null) {
			database = "default";
		}
		if (path != null && server != null) {
			// A flag beats a variable, and the two variables are alternatives: if both were exported, whichever flag
			// was passed decides. Only when neither was passed is this genuinely ambiguous.
			boolean pathFromFlag = List.of(args).stream().anyMatch(a -> a.startsWith("--path="));
			boolean serverFromFlag = List.of(args).stream().anyMatch(a -> a.startsWith("--server="));
			if (pathFromFlag && !serverFromFlag) {
				server = null;
			} else if (serverFromFlag && !pathFromFlag) {
				path = null;
			}
		}
		return new CliOptions(command, database, path, server, file, force, quiet);
	}

	private static String after(String argument) {
		String value = argument.substring(argument.indexOf('=') + 1);
		if (value.isBlank()) {
			throw new IllegalArgumentException("'" + argument + "' needs a value");
		}
		return value;
	}

	/** An empty variable is unset, not empty — which is how a Helm template renders a value nobody set. */
	private static String read(UnaryOperator<String> env, String name) {
		String value = env.apply(name);
		return value == null || value.isBlank() ? null : value;
	}

	public static String usage() {
		StringBuilder out = new StringBuilder("""
			metaloom-graph-cli — back up and restore a MetaLoom graph database

			USAGE
			  metaloom-graph-cli database backup  [<database>] (--path=<root> | --server=<url>) --out=<file>
			  metaloom-graph-cli database restore [<database>] (--path=<root> | --server=<url>) --in=<file> [--force]
			  metaloom-graph-cli database list             (--path=<root> | --server=<url>)
			  metaloom-graph-cli database inspect          --in=<file>

			WHERE
			  --path=<root>     Open the store directly. The root holds one directory per database.
			                    Takes an exclusive lock, so the database must not be open in a
			                    server. This is the mode that works when a server will not start.
			  --server=<url>    Use a running server, e.g. http://localhost:8080. The backup is
			                    taken online: writers are not stopped, and the dump is a single
			                    point in time.

			OPTIONS
			  --out=<file>      Where to write the dump. '-' writes to stdout.
			  --in=<file>       The dump to read. '-' reads stdin.
			  --database=<name> The database to act on; also accepted as a positional argument.
			  --force           Let restore replace a database that already exists. It is dropped
			                    and recreated, so everything in it is lost.
			  --quiet, -q       No progress on stderr.
			  --help, -h        This.

			NOTES
			  A restore does not preserve element ids. A restored node holds the same labels,
			  properties and relationships and has a different id, because a fresh store allocates
			  its own slots. Anything outside the store that recorded an id is stale afterwards.

			  Indexes and constraints are recorded in the dump and re-declared after the data is
			  loaded, so a restored database answers the same queries at the same cost.

			ENVIRONMENT
			""");
		for (EnvVar variable : VARIABLES) {
			out.append(String.format("  %-18s %s%n", variable.name(), variable.description()));
		}
		return out.toString();
	}
}
