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
 * @param arguments
 *            the positional words after the two that make the command. For {@code database backup mydb} that is
 *            {@code [mydb]}, and it is also where {@code database} came from. The {@code auth} commands need more than
 *            one — {@code auth grant analyst MERGE sales} is three — which is why this is a list rather than the single
 *            trailing name the dump commands originally needed
 * @param password
 *            a password given with {@code --password=} or read from stdin for {@code --password-stdin}, or null to
 *            generate one
 */
public record CliOptions(String command, String database, Path path, String server, String file, boolean force,
	boolean quiet, List<String> arguments, String password) {

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
		new EnvVar("GRAPH_DATABASE", "The database to act on when the command does not name one."),
		new EnvVar("GRAPH_ADMIN_PASSWORD", "The password for 'auth reset-admin' and 'auth user add'. Preferred over "
			+ "--password=, which a shell records in its history."));

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

	/**
	 * The commands that act on the identity file rather than on a store.
	 *
	 * <p>
	 * They are offline only, and {@code --server} is refused for them <b>by name</b>: driving them over HTTP would
	 * need an authenticated administrator, which is precisely the thing that has gone missing whenever somebody
	 * reaches for {@code auth reset-admin}.
	 * </p>
	 */
	private static final String AUTH_PREFIX = "auth ";

	public CliOptions {
		arguments = List.copyOf(arguments);
		if (path == null && server == null && !TRANSPORTLESS.contains(command)) {
			throw new IllegalArgumentException("Give --path <root> to open a store directly, or --server <url> to "
				+ "use a running one. There is no default: the two do different things, and guessing which was meant "
				+ "would be guessing whether to take a lock on somebody's data");
		}
		if (path != null && server != null) {
			throw new IllegalArgumentException("--path and --server are alternatives; pass one");
		}
		if (command != null && command.startsWith(AUTH_PREFIX) && server != null) {
			throw new IllegalArgumentException("'" + command + "' works on the identity file directly and cannot go "
				+ "through --server: doing that would need an administrator to already be logged in, which is the "
				+ "thing that has gone wrong whenever this command is needed. Use --path=<root>. It takes no store "
				+ "lock, so it works while a server is running.");
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
		return parse(args, env, null);
	}

	/**
	 * @param stdin
	 *            where {@code --password-stdin} reads from, or null to refuse it. Injected so the flag can be tested
	 *            without a console, the same way {@code env} is
	 */
	public static CliOptions parse(String[] args, UnaryOperator<String> env, java.io.InputStream stdin) {
		Path path = null;
		String server = null;
		String database = null;
		String file = null;
		String password = null;
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
		if ((value = read(env, "GRAPH_ADMIN_PASSWORD")) != null) {
			password = value;
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
			} else if (argument.startsWith("--password=")) {
				password = after(argument);
			} else if (argument.equals("--password-stdin")) {
				if (stdin == null) {
					throw new IllegalArgumentException("--password-stdin has nothing to read from here");
				}
				password = readPassword(stdin);
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
		List<String> arguments = words.size() > 2 ? List.copyOf(words.subList(2, words.size())) : List.of();
		if (!arguments.isEmpty() && database == null && !command.startsWith(AUTH_PREFIX)) {
			// For the dump commands the one trailing word is the database. The auth commands take their own
			// arguments there and never a database, so this must not quietly consume a role name as one.
			database = arguments.get(0);
		}
		if (!command.startsWith(AUTH_PREFIX) && arguments.size() > 1) {
			throw new IllegalArgumentException("Unexpected argument '" + arguments.get(1) + "'. Run with --help.");
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
		return new CliOptions(command, database, path, server, file, force, quiet, arguments, password);
	}

	/**
	 * Reads a password from stdin, which is the spelling that keeps it out of shell history and out of {@code ps}.
	 *
	 * <p>
	 * One line, and a trailing newline is stripped because {@code echo secret | …} adds one and nobody means it to be
	 * part of the password. A trailing carriage return goes too, so a file written on Windows does not silently set a
	 * password nobody can type.
	 * </p>
	 */
	private static String readPassword(java.io.InputStream stdin) {
		try {
			byte[] all = stdin.readAllBytes();
			String text = new String(all, java.nio.charset.StandardCharsets.UTF_8);
			int end = text.length();
			while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
				end--;
			}
			String password = text.substring(0, end);
			if (password.isEmpty()) {
				throw new IllegalArgumentException("--password-stdin read nothing");
			}
			return password;
		} catch (java.io.IOException e) {
			throw new IllegalArgumentException("--password-stdin could not be read: " + e.getMessage());
		}
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
			metaloom-graph-cli — back up and restore a MetaLoom graph database, and recover access to one

			USAGE
			  metaloom-graph-cli database backup  [<database>] (--path=<root> | --server=<url>) --out=<file>
			  metaloom-graph-cli database restore [<database>] (--path=<root> | --server=<url>) --in=<file> [--force]
			  metaloom-graph-cli database list             (--path=<root> | --server=<url>)
			  metaloom-graph-cli database inspect          --in=<file>

			  metaloom-graph-cli auth reset-admin  --path=<root> [--password=<pw> | --password-stdin]
			  metaloom-graph-cli auth list         --path=<root>
			  metaloom-graph-cli auth user         --path=<root> add <name> [--password=<pw>] | drop <name>
			  metaloom-graph-cli auth role         --path=<root> add <name> | drop <name>
			  metaloom-graph-cli auth grant        --path=<root> <role> <PRIVILEGE> <database|*>
			  metaloom-graph-cli auth revoke       --path=<root> <role> <PRIVILEGE> <database|*>

			THE AUTH COMMANDS
			  They edit <root>/.auth/, which holds the users, roles and grants. They are --path only:
			  going through --server would need an administrator already logged in, which is exactly
			  what has gone missing when 'auth reset-admin' is needed.

			  They take no store lock, so they work while a server is running, and the server picks the
			  change up on the next login rather than needing a restart.

			  'auth reset-admin' puts the administrator back three ways at once: it resets the password,
			  recreates the user if it was removed, and re-grants everything. Someone locked out has no
			  way to know which of the three happened to them.

			  A generated password is printed to stdout and nothing else is, so it can be piped or
			  captured. Prefer GRAPH_ADMIN_PASSWORD over --password=, which a shell puts in its history
			  and 'ps' shows to every other user on the machine.

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
			  --password=<pw>   A password for an auth command. Visible in shell history and in 'ps';
			                    prefer --password-stdin or GRAPH_ADMIN_PASSWORD.
			  --password-stdin  Read the password from stdin, one line.
			  --force           Let restore replace a database that already exists. It is dropped
			                    and recreated, so everything in it is lost. For 'auth reset-admin',
			                    replace an identity file that is corrupt and cannot be read.
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
