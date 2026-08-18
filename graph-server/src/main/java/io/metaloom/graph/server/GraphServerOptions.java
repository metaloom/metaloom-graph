package io.metaloom.graph.server;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.protocol.Protocol;
import io.metaloom.graph.server.demo.DemoData;
import io.metaloom.graph.server.wire.WireServerOptions;
import io.metaloom.graph.server.wire.WireTlsOptions;

/**
 * The command line and the environment.
 *
 * <p>
 * Parsed into a value rather than read out of {@code args} where it is used, so that it can be tested without starting
 * a server: an argument mistake that only shows up as a server bound to the wrong port is a bad way to find out.
 * </p>
 *
 * <h2>Where a setting comes from</h2>
 *
 * <p>
 * <b>Command line flag beats environment variable beats default.</b> The environment exists because a container image
 * cannot be reconfigured without rebuilding its {@code CMD}, and a Helm chart has environment variables and not an
 * argument vector. The flag still wins so that a shell that sets {@code GRAPH_ALLOW_WRITE} for one server cannot
 * silently change what a second one started in the same shell does.
 * </p>
 *
 * <p>
 * The variables are declared once in {@link #VARIABLES}, which is what both the reading and the {@code --help} output
 * are generated from — a variable that is read and not documented, or documented and not read, is the failure this
 * shape rules out. One of them, {@code GRAPH_INITIAL_PASSWORD}, <b>is</b> a credential and is marked
 * {@link EnvVar#sensitive}: {@code --help} describes it and nothing here ever prints its value, including
 * {@link #toString()}. This paragraph used to say that nothing in this system was a credential, which stopped being
 * true when {@code spec/AUTH.md} was built.
 * </p>
 *
 * @param root
 *            the root directory, created if it does not exist. It holds <b>one subdirectory per database</b>; pointing
 *            it at a directory that is itself a store is refused, with the commands to move it
 * @param port
 *            the HTTP port to bind, 0 for any free one
 * @param demo
 *            seed an <b>empty</b> store with the demo graph before serving it
 * @param demoAirports
 *            how much demo data: the airport count that everything else is generated around
 * @param queryTimeoutMillis
 *            how long a single query may run before it is abandoned, 0 for no limit. Applies to every front end
 * @param slowQueryMillis
 *            a query that takes at least this long is written to the slow query log, 0 to log none. Applies to every
 *            front end
 * @param wire
 *            whether to open the binary port as well as the HTTP one
 * @param wirePort
 *            the binary port, 0 for any free one
 * @param wireHost
 *            what the binary port binds. Loopback by default — it has no authentication. In a pod that makes it
 *            unreachable from outside the pod, which is the correct default and the reason the chart has to widen it
 *            deliberately rather than the image doing it for everyone
 * @param maxConcurrentStreams
 *            queries the binary port will run at once. <b>Multiplied by the write queue size, this is the memory the
 *            server may hold for clients that have stopped reading</b>; see {@code WireServerOptions}
 * @param metrics
 *            whether to open the monitoring port, which serves {@code /metrics}, {@code /health} and {@code /ready}
 * @param metricsPort
 *            the monitoring port, 0 for any free one. Separate from the HTTP port so that a liveness probe never
 *            shares a listener with the query path it is supposed to be reporting on
 * @param mcp
 *            whether to open the Model Context Protocol port. <b>Off by default</b>, unlike the others: it is a remote
 *            code path an LLM drives against a server with no authentication
 * @param mcpPort
 *            the MCP port, 0 for any free one
 * @param mcpHost
 *            what the MCP port binds. Loopback by default, for the reason above
 * @param database
 *            the database a request that does not name one gets, created at startup if it is not there
 * @param allowDrop
 *            whether {@code DELETE /api/databases/{name}} exists. Off by default, and separate from
 *            {@code allowWrite}: it destroys a whole database and its directory in one request, including databases
 *            no query addressed, and enabling writes is not asking for that
 * @param allowWrite
 *            whether a query may change a store at all. Off by default: no port has authentication, and the
 *            difference between handing out a copy of the data and handing out the ability to delete it deserves an
 *            explicit act
 * @param allowLoadCsv
 *            whether {@code LOAD CSV} may read anything. <b>Off by default, and a flag of its own rather than part of
 *            {@code allowWrite}</b>, because {@code LOAD CSV … RETURN row} writes nothing: a server that gated file
 *            access on the write flag would serve it while believing itself read-only. Requires {@code importDir}
 * @param importDir
 *            the one directory {@code LOAD CSV} may read, and the whole of what it may read. There is no default,
 *            deliberately — the only defensible one would be "nothing", which is what leaving it unset means
 * @param allowBackup
 *            whether {@code GET /api/databases/{name}/backup} exists. <b>Off by default and separate from
 *            {@code allowWrite}</b>: a backup is a read, but it is the read that hands over every byte of a database
 *            in a single request, which on a port with no authentication is its own decision — the same reasoning
 *            that gives {@code allowDrop} a flag of its own
 * @param auth
 *            whether a request has to say who it is. <b>On by default</b>, unlike every capability flag here, because
 *            this is the one setting whose unsafe value is the one somebody gets by not thinking about it. It composes
 *            with the flags above rather than replacing them: a request needs the privilege <b>and</b> the flag, so
 *            turning this on can never widen what a deployment is able to do — {@code spec/AUTH.md} section 5
 * @param initialPassword
 *            the first administrator's password, used <b>only</b> when there is no identity file yet. Ignored, with a
 *            log line saying so, once there is one: a chart that left this set would otherwise reset the password on
 *            every pod restart and silently undo whatever an administrator had changed it to. <b>Sensitive</b> — it is
 *            never printed by {@code --help}, by {@link #toString()} or by anything else here
 * @param sessionTimeoutMillis
 *            how long a browser session may sit idle before it has to log in again
 * @param wireTls
 *            a certificate, a key and a port for a <b>second</b> binary listener that terminates TLS, or null for
 *            none. It does not replace {@link #wirePort} — both are open unless {@link #wirePlaintext} says otherwise
 *            — and it affects the binary port only: the HTTP and MCP ports are unchanged, and are still expected to
 *            sit behind an ingress or a reverse proxy that terminates TLS for them. {@code spec/WIRE.md} section 9
 * @param wirePlaintext
 *            whether the unencrypted binary listener is opened. True unless {@code --wire-tls-only} was given, so that
 *            adding a certificate never closes a port under the clients already on it
 */
public record GraphServerOptions(Path root, int port, boolean demo, int demoAirports, long queryTimeoutMillis,
	long slowQueryMillis, boolean wire, int wirePort, String wireHost, int maxConcurrentStreams,
	boolean metrics, int metricsPort, boolean mcp, int mcpPort, String mcpHost,
	String database, boolean allowDrop, boolean allowWrite, boolean allowLoadCsv, Path importDir,
	boolean allowBackup, boolean auth, String initialPassword, long sessionTimeoutMillis,
	WireTlsOptions wireTls, boolean wirePlaintext) {

	static final int DEFAULT_PORT = 8080;

	/**
	 * The default query budget.
	 *
	 * <p>
	 * A limit rather than none, because the alternative is that one unindexed query over a large store holds a worker
	 * thread and a read view for as long as the data takes, and an open view holds back reclamation for exactly that
	 * long. Thirty seconds is far longer than any interactive query and far shorter than a runaway one.
	 * </p>
	 */
	public static final long DEFAULT_QUERY_TIMEOUT_MILLIS = 30_000;

	/**
	 * One second, above which a query is written to the slow query log.
	 *
	 * <p>
	 * Chosen to be well clear of anything the browser UI does on the demo graph, so that a fresh install logs nothing
	 * and a line in that log means something. It is far below {@link #DEFAULT_QUERY_TIMEOUT_MILLIS} on purpose: a
	 * threshold at or above the timeout can never fire, because the query is abandoned first.
	 * </p>
	 */
	public static final long DEFAULT_SLOW_QUERY_MILLIS = 1_000;

	/** The monitoring port. Mirrors Loom's 8989 in role, not in number — 8082 keeps this project's ports adjacent. */
	public static final int DEFAULT_METRICS_PORT = 8082;

	public static final int DEFAULT_MCP_PORT = 8083;

	/**
	 * Thirty minutes of inactivity before a session has to log in again.
	 *
	 * <p>
	 * Long enough that reading a result and coming back to the editor does not sign somebody out, short enough that an
	 * unattended browser is not a standing credential. An absolute cap applies as well, in {@code SessionTable}, so a
	 * client polling every minute cannot hold one indefinitely.
	 * </p>
	 */
	public static final long DEFAULT_SESSION_TIMEOUT_MILLIS = 30 * 60 * 1000L;

	/** The same reasoning as {@link WireServerOptions#DEFAULT_HOST}, and deliberately the same value. */
	public static final String DEFAULT_MCP_HOST = WireServerOptions.DEFAULT_HOST;

	/** The same default the wire server carries; repeated here only so the usage text can print it. */
	static final int DEFAULT_MAX_CONCURRENT_STREAMS = 8;

	/**
	 * One environment variable, its name and what it does.
	 *
	 * <p>
	 * A declared list rather than a scatter of {@code getenv} calls, so that {@link #usage()} can print exactly the
	 * set that is actually read.
	 * </p>
	 */
	public record EnvVar(String name, String description, boolean sensitive) {

		/** Most variables are not credentials; the one that is says so. */
		public EnvVar(String name, String description) {
			this(name, description, false);
		}
	}

	/** Every variable this server reads, in the order {@code --help} prints them. */
	public static final List<EnvVar> VARIABLES = List.of(
		new EnvVar("GRAPH_ROOT", "The root directory. Makes the positional argument optional, which is what lets a "
			+ "container image carry no arguments at all."),
		new EnvVar("GRAPH_HTTP_PORT", "The HTTP port."),
		new EnvVar("GRAPH_DATABASE", "The default database name."),
		new EnvVar("GRAPH_ALLOW_WRITE", "Accept write queries. true/false."),
		new EnvVar("GRAPH_ALLOW_DROP", "Enable database deletion. true/false."),
		new EnvVar("GRAPH_DEMO", "Seed an empty default database with the demo graph. true/false."),
		new EnvVar("GRAPH_DEMO_SIZE", "tiny | small | medium | large, or a number of airports. Implies GRAPH_DEMO."),
		new EnvVar("GRAPH_QUERY_TIMEOUT_MILLIS", "Abandon a query that runs longer than this. 0 disables."),
		new EnvVar("GRAPH_SLOW_QUERY_MILLIS", "Log a query that takes at least this long. 0 disables."),
		new EnvVar("GRAPH_WIRE_ENABLED", "Open the binary streaming port. true/false."),
		new EnvVar("GRAPH_WIRE_PORT", "The binary streaming port."),
		new EnvVar("GRAPH_WIRE_HOST", "What the binary port binds. Loopback by default, so in a pod it is not "
			+ "reachable from outside until this is set."),
		new EnvVar("GRAPH_WIRE_TLS_CERT", "A PEM certificate. Setting it and GRAPH_WIRE_TLS_KEY opens a second, "
			+ "encrypted binary port; the plaintext one stays open unless GRAPH_WIRE_TLS_ONLY is set."),
		new EnvVar("GRAPH_WIRE_TLS_KEY", "The matching unencrypted PEM private key."),
		new EnvVar("GRAPH_WIRE_TLS_PORT", "The encrypted binary port."),
		new EnvVar("GRAPH_WIRE_TLS_HOST", "What the encrypted binary port binds. Defaults to GRAPH_WIRE_HOST."),
		new EnvVar("GRAPH_WIRE_TLS_ONLY", "Close the unencrypted binary port and serve only the encrypted one. "
			+ "true/false."),
		new EnvVar("GRAPH_MAX_STREAMS", "Queries the binary port runs at once."),
		new EnvVar("GRAPH_METRICS_ENABLED", "Open the monitoring port. true/false."),
		new EnvVar("GRAPH_METRICS_PORT", "The monitoring port: /metrics, /health and /ready."),
		new EnvVar("GRAPH_MCP_ENABLED", "Open the MCP port. Off by default. true/false."),
		new EnvVar("GRAPH_MCP_PORT", "The MCP port."),
		new EnvVar("GRAPH_MCP_HOST", "What the MCP port binds. Loopback by default."),
		new EnvVar("GRAPH_ALLOW_LOAD_CSV", "Accept LOAD CSV. Off by default, and needs GRAPH_IMPORT_DIR. true/false."),
		new EnvVar("GRAPH_IMPORT_DIR", "The one directory LOAD CSV may read, and the whole of what it may read."),
		new EnvVar("GRAPH_ALLOW_BACKUP", "Serve GET /api/databases/<name>/backup. Off by default. true/false."),
		new EnvVar("GRAPH_AUTH_ENABLED", "Require a login on the HTTP, binary and MCP ports. ON by default. "
			+ "true/false."),
		new EnvVar("GRAPH_INITIAL_PASSWORD", "The first administrator's password, used only when there is no "
			+ "identity file yet. Ignored afterwards. Set it from a Secret, never inline.", true),
		new EnvVar("GRAPH_SESSION_TIMEOUT_MILLIS", "How long a browser session may sit idle before it has to log "
			+ "in again."));

	public GraphServerOptions {
		if (wireHost == null || wireHost.isBlank()) {
			throw new IllegalArgumentException("A bind address is required for the binary port");
		}
		if (mcpHost == null || mcpHost.isBlank()) {
			throw new IllegalArgumentException("A bind address is required for the MCP port");
		}
		// Refused at parse time rather than at the first query, because a server that accepted the flag and then
		// refused every LOAD CSV would look like a bug in the queries rather than a gap in its own configuration.
		// The pair is also refused the other way round: an import directory with the flag off reads as enabled and is
		// not, which is the more dangerous of the two misunderstandings.
		if (allowLoadCsv && importDir == null) {
			throw new IllegalArgumentException("--allow-load-csv needs --import-dir=<path>: LOAD CSV reads files "
				+ "under one directory and there is no default, because no port here has authentication and the "
				+ "default would be somebody's whole filesystem");
		}
		if (!allowLoadCsv && importDir != null) {
			throw new IllegalArgumentException("--import-dir was given but --allow-load-csv was not, so LOAD CSV "
				+ "would still be refused. Pass both, or neither");
		}
		// The same shape as the pair above, and the more dangerous of the two misunderstandings it guards: a chart
		// that sets a password on a server with authentication switched off reads as protected and is not.
		if (!auth && initialPassword != null) {
			throw new IllegalArgumentException("An initial password was given but authentication is off, so nothing "
				+ "would ever ask for it. Drop --no-auth, or drop the password");
		}
		if (sessionTimeoutMillis < 0) {
			throw new IllegalArgumentException("A session timeout must not be negative, was " + sessionTimeoutMillis);
		}
		// The same shape as the --allow-load-csv / --import-dir pair: a combination that reads as configured and is
		// not. Here it is worse than useless, because the reading is "the binary port is encrypted".
		if (!wirePlaintext && wireTls == null) {
			throw new IllegalArgumentException("--wire-tls-only needs --wire-tls-cert and --wire-tls-key: without "
				+ "them there would be no binary port at all. Pass a certificate, or use --no-wire if closing the "
				+ "binary port is what you meant");
		}
		if (!wire && wireTls != null) {
			throw new IllegalArgumentException("A TLS certificate was given for the binary port but --no-wire "
				+ "switched that port off, so nothing would present it. Drop one of the two");
		}
	}

	/**
	 * Everything except the password.
	 *
	 * <p>
	 * A record's generated {@code toString} prints every component, which would put a credential into any log line,
	 * exception message or debugger view that touched this object. Overriding it is the only way to make that
	 * impossible rather than merely unlikely, and {@code GraphServerOptionsTest} asserts it.
	 * </p>
	 */
	@Override
	public String toString() {
		return "GraphServerOptions[root=" + root + ", port=" + port + ", demo=" + demo
			+ ", demoAirports=" + demoAirports + ", queryTimeoutMillis=" + queryTimeoutMillis
			+ ", slowQueryMillis=" + slowQueryMillis + ", wire=" + wire + ", wirePort=" + wirePort
			+ ", wireHost=" + wireHost + ", maxConcurrentStreams=" + maxConcurrentStreams
			+ ", metrics=" + metrics + ", metricsPort=" + metricsPort + ", mcp=" + mcp + ", mcpPort=" + mcpPort
			+ ", mcpHost=" + mcpHost + ", database=" + database + ", allowDrop=" + allowDrop
			+ ", allowWrite=" + allowWrite + ", allowLoadCsv=" + allowLoadCsv + ", importDir=" + importDir
			+ ", allowBackup=" + allowBackup + ", auth=" + auth
			+ ", initialPassword=" + (initialPassword == null ? "<unset>" : "<set>")
			+ ", sessionTimeoutMillis=" + sessionTimeoutMillis
			+ ", wireTls=" + (wireTls == null ? "<none>" : wireTls) + ", wirePlaintext=" + wirePlaintext + "]";
	}

	/** Parses the command line against the real environment. */
	static GraphServerOptions parse(String[] args) {
		return parse(args, System::getenv);
	}

	/**
	 * Parses the command line and the environment, or throws {@link IllegalArgumentException} with a message worth
	 * printing.
	 *
	 * <p>
	 * Options may appear before or after the positional arguments, and {@code --demo-size} implies {@code --demo}:
	 * asking for a size and getting no data because a second flag was missing would be a silly way to lose a minute.
	 * </p>
	 *
	 * @param env
	 *            the environment lookup, injected so this can be tested without a process environment. Native image
	 *            also sees a plain lambda here where it would need metadata for reflection over an options object
	 */
	static GraphServerOptions parse(String[] args, UnaryOperator<String> env) {
		Path store = null;
		int port = DEFAULT_PORT;
		boolean demo = false;
		int airports = DemoData.SMALL;
		long queryTimeout = DEFAULT_QUERY_TIMEOUT_MILLIS;
		long slowQuery = DEFAULT_SLOW_QUERY_MILLIS;
		boolean wire = true;
		int wirePort = Protocol.DEFAULT_PORT;
		String wireHost = WireServerOptions.DEFAULT_HOST;
		Path wireTlsCert = null;
		Path wireTlsKey = null;
		int wireTlsPort = WireTlsOptions.DEFAULT_PORT;
		// Null rather than wireHost, because "unset" and "set to the same value" have to stay distinguishable until
		// every source has been read: the variable may be set before the flag that changes wireHost is seen.
		String wireTlsHost = null;
		boolean wirePlaintext = true;
		int streams = DEFAULT_MAX_CONCURRENT_STREAMS;
		boolean metrics = true;
		int metricsPort = DEFAULT_METRICS_PORT;
		boolean mcp = false;
		int mcpPort = DEFAULT_MCP_PORT;
		String mcpHost = DEFAULT_MCP_HOST;
		String database = GraphDatabases.DEFAULT_NAME;
		boolean allowDrop = false;
		boolean allowWrite = false;
		boolean allowLoadCsv = false;
		Path importDir = null;
		boolean allowBackup = false;
		// The one default here that is not "off". Authentication is the setting whose unsafe value is the one an
		// operator gets by not thinking about it, so not thinking about it has to be the safe outcome.
		boolean auth = true;
		String initialPassword = null;
		long sessionTimeout = DEFAULT_SESSION_TIMEOUT_MILLIS;
		int positional = 0;

		// The environment first, so that every flag below overwrites it rather than the other way round.
		String value;
		if ((value = read(env, "GRAPH_ROOT")) != null) {
			store = Path.of(value);
		}
		if ((value = read(env, "GRAPH_HTTP_PORT")) != null) {
			port = parsePort(value, "GRAPH_HTTP_PORT");
		}
		if ((value = read(env, "GRAPH_DATABASE")) != null) {
			database = value;
			GraphDatabases.validate(database);
		}
		if ((value = read(env, "GRAPH_ALLOW_WRITE")) != null) {
			allowWrite = parseBoolean(value, "GRAPH_ALLOW_WRITE");
		}
		if ((value = read(env, "GRAPH_ALLOW_DROP")) != null) {
			allowDrop = parseBoolean(value, "GRAPH_ALLOW_DROP");
		}
		if ((value = read(env, "GRAPH_ALLOW_LOAD_CSV")) != null) {
			allowLoadCsv = parseBoolean(value, "GRAPH_ALLOW_LOAD_CSV");
		}
		if ((value = read(env, "GRAPH_IMPORT_DIR")) != null) {
			importDir = Path.of(value);
		}
		if ((value = read(env, "GRAPH_ALLOW_BACKUP")) != null) {
			allowBackup = parseBoolean(value, "GRAPH_ALLOW_BACKUP");
		}
		if ((value = read(env, "GRAPH_AUTH_ENABLED")) != null) {
			auth = parseBoolean(value, "GRAPH_AUTH_ENABLED");
		}
		if ((value = read(env, "GRAPH_INITIAL_PASSWORD")) != null) {
			initialPassword = value;
		}
		if ((value = read(env, "GRAPH_SESSION_TIMEOUT_MILLIS")) != null) {
			sessionTimeout = parseMillis(value, "GRAPH_SESSION_TIMEOUT_MILLIS");
		}
		if ((value = read(env, "GRAPH_DEMO")) != null) {
			demo = parseBoolean(value, "GRAPH_DEMO");
		}
		if ((value = read(env, "GRAPH_DEMO_SIZE")) != null) {
			airports = DemoData.airportsFor(value);
			demo = true;
		}
		if ((value = read(env, "GRAPH_QUERY_TIMEOUT_MILLIS")) != null) {
			queryTimeout = parseMillis(value, "GRAPH_QUERY_TIMEOUT_MILLIS");
		}
		if ((value = read(env, "GRAPH_SLOW_QUERY_MILLIS")) != null) {
			slowQuery = parseMillis(value, "GRAPH_SLOW_QUERY_MILLIS");
		}
		if ((value = read(env, "GRAPH_WIRE_ENABLED")) != null) {
			wire = parseBoolean(value, "GRAPH_WIRE_ENABLED");
		}
		if ((value = read(env, "GRAPH_WIRE_PORT")) != null) {
			wirePort = parsePort(value, "GRAPH_WIRE_PORT");
		}
		if ((value = read(env, "GRAPH_WIRE_HOST")) != null) {
			wireHost = value;
		}
		if ((value = read(env, "GRAPH_WIRE_TLS_CERT")) != null) {
			wireTlsCert = Path.of(value);
		}
		if ((value = read(env, "GRAPH_WIRE_TLS_KEY")) != null) {
			wireTlsKey = Path.of(value);
		}
		if ((value = read(env, "GRAPH_WIRE_TLS_PORT")) != null) {
			wireTlsPort = parsePort(value, "GRAPH_WIRE_TLS_PORT");
		}
		if ((value = read(env, "GRAPH_WIRE_TLS_HOST")) != null) {
			wireTlsHost = value;
		}
		if ((value = read(env, "GRAPH_WIRE_TLS_ONLY")) != null) {
			wirePlaintext = !parseBoolean(value, "GRAPH_WIRE_TLS_ONLY");
		}
		if ((value = read(env, "GRAPH_MAX_STREAMS")) != null) {
			streams = parseStreams(value, "GRAPH_MAX_STREAMS");
		}
		if ((value = read(env, "GRAPH_METRICS_ENABLED")) != null) {
			metrics = parseBoolean(value, "GRAPH_METRICS_ENABLED");
		}
		if ((value = read(env, "GRAPH_METRICS_PORT")) != null) {
			metricsPort = parsePort(value, "GRAPH_METRICS_PORT");
		}
		if ((value = read(env, "GRAPH_MCP_ENABLED")) != null) {
			mcp = parseBoolean(value, "GRAPH_MCP_ENABLED");
		}
		if ((value = read(env, "GRAPH_MCP_PORT")) != null) {
			mcpPort = parsePort(value, "GRAPH_MCP_PORT");
		}
		if ((value = read(env, "GRAPH_MCP_HOST")) != null) {
			mcpHost = value;
		}

		for (String argument : args) {
			if (argument.equals("--demo")) {
				demo = true;
			} else if (argument.startsWith("--demo-size=")) {
				airports = DemoData.airportsFor(argument.substring("--demo-size=".length()));
				demo = true;
			} else if (argument.startsWith("--query-timeout=")) {
				queryTimeout = parseMillis(argument.substring("--query-timeout=".length()), "--query-timeout");
			} else if (argument.startsWith("--slow-query-millis=")) {
				slowQuery = parseMillis(argument.substring("--slow-query-millis=".length()), "--slow-query-millis");
			} else if (argument.equals("--no-wire")) {
				wire = false;
			} else if (argument.startsWith("--wire-port=")) {
				wirePort = parsePort(argument.substring("--wire-port=".length()), "--wire-port");
			} else if (argument.startsWith("--wire-host=")) {
				wireHost = argument.substring("--wire-host=".length());
			} else if (argument.startsWith("--wire-tls-cert=")) {
				wireTlsCert = Path.of(argument.substring("--wire-tls-cert=".length()));
			} else if (argument.startsWith("--wire-tls-key=")) {
				wireTlsKey = Path.of(argument.substring("--wire-tls-key=".length()));
			} else if (argument.startsWith("--wire-tls-port=")) {
				wireTlsPort = parsePort(argument.substring("--wire-tls-port=".length()), "--wire-tls-port");
			} else if (argument.startsWith("--wire-tls-host=")) {
				wireTlsHost = argument.substring("--wire-tls-host=".length());
			} else if (argument.equals("--wire-tls-only")) {
				wirePlaintext = false;
			} else if (argument.startsWith("--max-streams=")) {
				streams = parseStreams(argument.substring("--max-streams=".length()), "--max-streams");
			} else if (argument.equals("--no-metrics")) {
				metrics = false;
			} else if (argument.startsWith("--metrics-port=")) {
				metricsPort = parsePort(argument.substring("--metrics-port=".length()), "--metrics-port");
			} else if (argument.equals("--mcp")) {
				mcp = true;
			} else if (argument.startsWith("--mcp-port=")) {
				mcpPort = parsePort(argument.substring("--mcp-port=".length()), "--mcp-port");
				mcp = true;
			} else if (argument.startsWith("--mcp-host=")) {
				mcpHost = argument.substring("--mcp-host=".length());
				mcp = true;
			} else if (argument.startsWith("--database=")) {
				database = argument.substring("--database=".length());
				// Validated here rather than at open time, so a typo is a usage error printed with the usage text
				// instead of a stack trace from somewhere inside the storage layer.
				GraphDatabases.validate(database);
			} else if (argument.equals("--allow-drop")) {
				allowDrop = true;
			} else if (argument.equals("--allow-write")) {
				allowWrite = true;
			} else if (argument.equals("--allow-backup")) {
				allowBackup = true;
			} else if (argument.equals("--no-auth")) {
				auth = false;
			} else if (argument.startsWith("--initial-password=")) {
				initialPassword = argument.substring("--initial-password=".length());
			} else if (argument.startsWith("--session-timeout=")) {
				sessionTimeout = parseMillis(argument.substring("--session-timeout=".length()), "--session-timeout");
			} else if (argument.equals("--allow-load-csv")) {
				allowLoadCsv = true;
			} else if (argument.startsWith("--import-dir=")) {
				importDir = Path.of(argument.substring("--import-dir=".length()));
			} else if (argument.equals("--help") || argument.equals("-h")) {
				throw new HelpRequested();
			} else if (argument.startsWith("-")) {
				throw new IllegalArgumentException("Unknown option '" + argument + "'");
			} else if (positional == 0) {
				store = Path.of(argument);
				positional++;
			} else if (positional == 1) {
				port = parsePort(argument, "Port");
				positional++;
			} else {
				throw new IllegalArgumentException("Unexpected argument '" + argument + "'");
			}
		}

		if (store == null) {
			throw new IllegalArgumentException("A root directory is required, as an argument or in GRAPH_ROOT");
		}
		// Half a pair is refused by name. A certificate with no key is an operator who set one of two variables, and
		// a server that quietly stayed plaintext because of it would be the worst possible outcome of trying.
		if ((wireTlsCert == null) != (wireTlsKey == null)) {
			throw new IllegalArgumentException("TLS on the binary port needs both a certificate and a key; only the "
				+ (wireTlsCert == null ? "key" : "certificate") + " was given");
		}
		WireTlsOptions wireTls = wireTlsCert == null
			? null
			// The encrypted listener inherits the binary port's bind address unless it was given one of its own, so
			// that widening the binary port stays a single decision.
			: new WireTlsOptions(wireTlsHost == null ? wireHost : wireTlsHost, wireTlsPort, wireTlsCert, wireTlsKey);
		checkPortsDiffer(port, wire && wirePlaintext ? wirePort : -1, metrics ? metricsPort : -1,
			mcp ? mcpPort : -1, wire && wireTls != null ? wireTls.port() : -1);

		return new GraphServerOptions(store, port, demo, airports, queryTimeout, slowQuery, wire, wirePort, wireHost,
			streams, metrics, metricsPort, mcp, mcpPort, mcpHost, database, allowDrop, allowWrite, allowLoadCsv,
			importDir, allowBackup, auth, initialPassword, sessionTimeout, wireTls, wirePlaintext);
	}

	private static String read(UnaryOperator<String> env, String name) {
		String value = env.apply(name);
		if (value == null) {
			return null;
		}
		value = value.trim();
		// An empty variable is how a Helm template renders a value nobody set. Treating it as "unset" rather than as
		// "" is what keeps an optional chart value from becoming an empty database name.
		return value.isEmpty() ? null : value;
	}

	/**
	 * Refuses two front ends configured onto one fixed port.
	 *
	 * <p>
	 * Zero means "any free port", so zeros never collide with anything and are excluded; a disabled front end is
	 * passed as -1 and excluded for the same reason. Without this the mistake surfaces as "address already in use"
	 * from whichever listener happened to bind second, which names the port but not the two settings that chose it.
	 * </p>
	 */
	private static void checkPortsDiffer(int http, int wire, int metrics, int mcp, int wireTls) {
		Map<Integer, String> seen = new LinkedHashMap<>();
		record Candidate(int port, String name) {
		}
		for (Candidate candidate : List.of(new Candidate(http, "the HTTP port"), new Candidate(wire, "the binary port"),
			new Candidate(metrics, "the monitoring port"), new Candidate(mcp, "the MCP port"),
			new Candidate(wireTls, "the encrypted binary port"))) {
			if (candidate.port() <= 0) {
				continue;
			}
			String other = seen.putIfAbsent(candidate.port(), candidate.name());
			if (other != null) {
				throw new IllegalArgumentException(other + " and " + candidate.name() + " are both "
					+ candidate.port() + "; give one of them a different port");
			}
		}
	}

	private static int parseStreams(String argument, String source) {
		int streams;
		try {
			streams = Integer.parseInt(argument);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(source + " must be a number, was '" + argument + "'");
		}
		if (streams < 1) {
			throw new IllegalArgumentException(source + " must be at least 1, was " + streams);
		}
		return streams;
	}

	private static long parseMillis(String argument, String source) {
		long millis;
		try {
			millis = Long.parseLong(argument);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(source + " must be a number of milliseconds, was '" + argument + "'");
		}
		if (millis < 0) {
			throw new IllegalArgumentException(source + " must not be negative, was " + millis);
		}
		return millis;
	}

	private static boolean parseBoolean(String argument, String source) {
		return switch (argument.toLowerCase()) {
			case "true", "1", "yes", "on" -> true;
			case "false", "0", "no", "off" -> false;
			// Not Boolean.parseBoolean, which maps every typo to false — an operator who wrote GRAPH_ALLOW_WRITE=ture
			// would get a read-only server and no reason for it.
			default -> throw new IllegalArgumentException(source + " must be true or false, was '" + argument + "'");
		};
	}

	private static int parsePort(String argument, String source) {
		int port;
		try {
			port = Integer.parseInt(argument);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(source + " must be a number, was '" + argument + "'");
		}
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException(source + " must be between 0 and 65535, was " + port);
		}
		return port;
	}

	/** Thrown for {@code --help}, which is a request rather than a mistake and so must not exit non-zero. */
	static final class HelpRequested extends RuntimeException {

		private static final long serialVersionUID = 1L;

		HelpRequested() {
			super("help", null, false, false);
		}
	}

	static String usage() {
		StringBuilder environment = new StringBuilder();
		for (EnvVar variable : VARIABLES) {
			environment.append("  ").append(variable.name());
			if (variable.sensitive()) {
				// Marked in the help rather than silently treated differently, so an operator can see which one needs
				// a Secret rather than a plain value in a manifest.
				environment.append("   (a credential: set it from a secret, never inline)");
			}
			environment.append('\n');
			environment.append("        ").append(variable.description()).append('\n');
		}
		return """
			usage: graph-server [options] [root-directory] [http-port]

			The root holds one subdirectory per database. A directory that is itself a store is
			refused, with the two commands that move it into a root. It may also come from GRAPH_ROOT.

			  --database=<name>     The database a request that does not name one gets. Created at
			                        startup if it is not there. Letters, digits, '-' and '_'.
			                        (default: %s)
			  --no-auth             Do not require a login on any port. Authentication is ON by
			                        default. With it off every request is anonymous, and the flags
			                        below are the only thing bounding what a peer can do.
			  --initial-password=<pw>
			                        The first administrator's password, used only when there is no
			                        identity file yet and ignored once there is. Without it one is
			                        generated and printed to stdout, once. Prefer the environment
			                        variable: an argument is visible in 'ps' to every other user.
			  --session-timeout=<ms>
			                        How long a browser session may sit idle. (default: %d)
			  --allow-write         Accept CREATE, SET, REMOVE and DELETE queries. Off by default,
			                        and a separate question from who is asking: a request needs the
			                        privilege AND this flag, so this bounds the whole deployment
			                        whatever anybody has been granted.
			  --allow-drop          Enable DELETE /api/databases/<name>, which deletes a database and
			                        everything in it. Off by default, and separate from --allow-write:
			                        it destroys a whole database in one request, including ones no
			                        query addressed.
			  --allow-backup        Serve GET /api/databases/<name>/backup, which streams a whole
			                        database out. Off by default and separate from --allow-write:
			                        it is a read, but it is the read that hands over everything.
			  --allow-load-csv      Accept LOAD CSV. Off by default and NOT implied by --allow-write,
			                        because LOAD CSV ... RETURN row writes nothing: without its own
			                        flag a read-only server would serve it. Requires --import-dir.
			  --import-dir=<path>   The one directory LOAD CSV may read, and the whole of what it can
			                        read. file:///x.csv means <path>/x.csv; symlinks out are refused.
			  --demo                Seed the default database with a demo graph, but only if it is
			                        empty. Independent of --allow-write, which is about queries.
			  --demo-size=<size>    How much demo data. Implies --demo.
			                        tiny | small | medium | large, or a number of airports.
			                        tiny=%d  small=%d  medium=%d  large=%d   (default: small)
			  --query-timeout=<ms>  Abandon a query that runs longer than this. 0 disables the limit,
			                        which lets one query hold a worker and a read view indefinitely.
			                        Applies to every front end. (default: %d)
			  --slow-query-millis=<ms>
			                        Write a query taking at least this long to the slow query log.
			                        0 logs none. Must be below --query-timeout to ever fire.
			                        (default: %d)
			  --wire-port=<port>    The binary streaming port. 0 for any free one. (default: %d)
			  --wire-host=<addr>    What the binary port binds. (default: %s)
			  --no-wire             No binary port.
			  --wire-tls-cert=<pem> A PEM certificate for the binary port. With --wire-tls-key it
			                        opens a SECOND, encrypted binary port; the plaintext one stays
			                        open until --wire-tls-only. Affects the binary port only - the
			                        HTTP and MCP ports are unchanged.
			  --wire-tls-key=<pem>  The matching unencrypted PEM private key.
			  --wire-tls-port=<port>
			                        The encrypted binary port. 0 for any free one. (default: %d)
			  --wire-tls-host=<addr>
			                        What the encrypted binary port binds. (default: --wire-host)
			  --wire-tls-only       Close the plaintext binary port and serve only the encrypted one.
			  --max-streams=<n>     Queries the binary port runs at once; the n+1th is refused rather
			                        than queued. Multiplied by the per-stream write queue, this is the
			                        memory the server may hold for clients that stop reading.
			                        (default: %d)
			  --metrics-port=<port> The monitoring port: /metrics, /health and /ready. (default: %d)
			  --no-metrics          No monitoring port.
			  --mcp                 Open the Model Context Protocol port. Off by default: it is a
			                        remote code path an LLM drives.
			  --mcp-port=<port>     The MCP port. Implies --mcp. (default: %d)
			  --mcp-host=<addr>     What the MCP port binds. Implies --mcp. (default: %s)
			  -h, --help            This.

			Environment. A flag beats a variable beats a default.

			%s
			The HTTP port serves the browser UI and /api/query, which materialises a whole result as
			JSON. The binary port streams rows as they are produced; use graph-client to read it.

			The HTTP, binary and MCP ports require a login unless --no-auth is given. Only the binary
			port can encrypt itself, with --wire-tls-cert; put the HTTP and MCP ports behind an
			ingress or a reverse proxy that terminates TLS for them. Encryption and authentication do
			not replace one another: a password on a cleartext port is readable by anything on the
			path, and an encrypted port with no authentication is readable by anyone who can reach
			it. The monitoring port never asks for one: a kubelet probe and a Prometheus scrape carry
			no credential, and a probe failing over a misconfigured secret would turn that into a
			restart loop. Do not publish it anyway - a scrape carries database names, node counts and
			store sizes.

			Without --wire-tls-cert there is no transport security on any port and a password crosses
			in cleartext. Put TLS in front of it, or keep it on a network you trust. The binary and MCP
			ports bind loopback.

			A privilege and a flag answer different questions: --allow-write and its neighbours bound
			what this SERVER will ever do, a grant bounds what one USER may do within that, and both
			have to permit.

			The demo graph is an air route network: airports in cities in countries, airlines with
			hubs, and routes between airports carrying distance, duration and flight number. See
			spec/CYPHER.md for queries to try.
			""".formatted(GraphDatabases.DEFAULT_NAME, DEFAULT_SESSION_TIMEOUT_MILLIS, DemoData.TINY, DemoData.SMALL, DemoData.MEDIUM,
				DemoData.LARGE, DEFAULT_QUERY_TIMEOUT_MILLIS, DEFAULT_SLOW_QUERY_MILLIS, Protocol.DEFAULT_PORT,
				WireServerOptions.DEFAULT_HOST, WireTlsOptions.DEFAULT_PORT, DEFAULT_MAX_CONCURRENT_STREAMS,
				DEFAULT_METRICS_PORT, DEFAULT_MCP_PORT, DEFAULT_MCP_HOST, environment);
	}
}
