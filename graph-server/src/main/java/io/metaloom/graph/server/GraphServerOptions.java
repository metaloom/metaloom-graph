package io.metaloom.graph.server;

import java.nio.file.Path;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.protocol.Protocol;
import io.metaloom.graph.server.demo.DemoData;

/**
 * The command line.
 *
 * <p>
 * Parsed into a value rather than read out of {@code args} where it is used, so that it can be tested without starting
 * a server: an argument mistake that only shows up as a server bound to the wrong port is a bad way to find out.
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
 *            how long a single query may run before it is abandoned, 0 for no limit. Applies to both front ends
 * @param wire
 *            whether to open the binary port as well as the HTTP one
 * @param wirePort
 *            the binary port, 0 for any free one. Bound to loopback — it has no authentication
 * @param maxConcurrentStreams
 *            queries the binary port will run at once. <b>Multiplied by the write queue size, this is the memory the
 *            server may hold for clients that have stopped reading</b>; see {@code WireServerOptions}
 * @param database
 *            the database a request that does not name one gets, created at startup if it is not there
 * @param allowDrop
 *            whether {@code DELETE /api/databases/{name}} exists. Off by default, and separate from
 *            {@code allowWrite}: it destroys a whole database and its directory in one request, including databases
 *            no query addressed, and enabling writes is not asking for that
 * @param allowWrite
 *            whether a query may change a store at all. Off by default: neither port has authentication, and the
 *            difference between handing out a copy of the data and handing out the ability to delete it deserves an
 *            explicit act
 */
public record GraphServerOptions(Path root, int port, boolean demo, int demoAirports, long queryTimeoutMillis,
	boolean wire, int wirePort, int maxConcurrentStreams, String database, boolean allowDrop, boolean allowWrite) {

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

	/** The same default the wire server carries; repeated here only so the usage text can print it. */
	static final int DEFAULT_MAX_CONCURRENT_STREAMS = 8;

	/**
	 * Parses the command line, or throws {@link IllegalArgumentException} with a message worth printing.
	 *
	 * <p>
	 * Options may appear before or after the positional arguments, and {@code --demo-size} implies {@code --demo}:
	 * asking for a size and getting no data because a second flag was missing would be a silly way to lose a minute.
	 * </p>
	 */
	static GraphServerOptions parse(String[] args) {
		Path store = null;
		int port = DEFAULT_PORT;
		boolean demo = false;
		int airports = DemoData.SMALL;
		long queryTimeout = DEFAULT_QUERY_TIMEOUT_MILLIS;
		boolean wire = true;
		int wirePort = Protocol.DEFAULT_PORT;
		int streams = DEFAULT_MAX_CONCURRENT_STREAMS;
		String database = GraphDatabases.DEFAULT_NAME;
		boolean allowDrop = false;
		boolean allowWrite = false;
		int positional = 0;

		for (String argument : args) {
			if (argument.equals("--demo")) {
				demo = true;
			} else if (argument.startsWith("--demo-size=")) {
				airports = DemoData.airportsFor(argument.substring("--demo-size=".length()));
				demo = true;
			} else if (argument.startsWith("--query-timeout=")) {
				queryTimeout = parseTimeout(argument.substring("--query-timeout=".length()));
			} else if (argument.equals("--no-wire")) {
				wire = false;
			} else if (argument.startsWith("--wire-port=")) {
				wirePort = parsePort(argument.substring("--wire-port=".length()));
			} else if (argument.startsWith("--max-streams=")) {
				streams = parseStreams(argument.substring("--max-streams=".length()));
			} else if (argument.startsWith("--database=")) {
				database = argument.substring("--database=".length());
				// Validated here rather than at open time, so a typo is a usage error printed with the usage text
				// instead of a stack trace from somewhere inside the storage layer.
				GraphDatabases.validate(database);
			} else if (argument.equals("--allow-drop")) {
				allowDrop = true;
			} else if (argument.equals("--allow-write")) {
				allowWrite = true;
			} else if (argument.equals("--help") || argument.equals("-h")) {
				throw new HelpRequested();
			} else if (argument.startsWith("-")) {
				throw new IllegalArgumentException("Unknown option '" + argument + "'");
			} else if (positional == 0) {
				store = Path.of(argument);
				positional++;
			} else if (positional == 1) {
				port = parsePort(argument);
				positional++;
			} else {
				throw new IllegalArgumentException("Unexpected argument '" + argument + "'");
			}
		}

		if (store == null) {
			throw new IllegalArgumentException("A root directory is required");
		}
		// Zero means "any free port" for both, and two of them would collide on nothing — but a fixed port given
		// twice is a mistake that would otherwise surface as "address already in use" from whichever bound second.
		if (wire && wirePort != 0 && wirePort == port) {
			throw new IllegalArgumentException("The HTTP port and the binary port are both " + port
				+ "; give --wire-port a different one or --no-wire");
		}
		return new GraphServerOptions(store, port, demo, airports, queryTimeout, wire, wirePort, streams, database,
			allowDrop, allowWrite);
	}

	private static int parseStreams(String argument) {
		int streams;
		try {
			streams = Integer.parseInt(argument);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("--max-streams must be a number, was '" + argument + "'");
		}
		if (streams < 1) {
			throw new IllegalArgumentException("--max-streams must be at least 1, was " + streams);
		}
		return streams;
	}

	private static long parseTimeout(String argument) {
		long millis;
		try {
			millis = Long.parseLong(argument);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("--query-timeout must be a number of milliseconds, was '"
				+ argument + "'");
		}
		if (millis < 0) {
			throw new IllegalArgumentException("--query-timeout must not be negative, was " + millis);
		}
		return millis;
	}

	private static int parsePort(String argument) {
		int port;
		try {
			port = Integer.parseInt(argument);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Port must be a number, was '" + argument + "'");
		}
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("Port must be between 0 and 65535, was " + port);
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
		return """
			usage: graph-server [options] <root-directory> [http-port]

			The root holds one subdirectory per database. A directory that is itself a store is
			refused, with the two commands that move it into a root.

			  --database=<name>     The database a request that does not name one gets. Created at
			                        startup if it is not there. Letters, digits, '-' and '_'.
			                        (default: %s)
			  --allow-write         Accept CREATE, SET, REMOVE and DELETE queries. Off by default:
			                        neither port has authentication, so enabling this hands anything
			                        that can reach the server the ability to change and delete data.
			  --allow-drop          Enable DELETE /api/databases/<name>, which deletes a database and
			                        everything in it. Off by default, and separate from --allow-write:
			                        it destroys a whole database in one request, including ones no
			                        query addressed.
			  --demo                Seed the default database with a demo graph, but only if it is
			                        empty. Independent of --allow-write, which is about queries.
			  --demo-size=<size>    How much demo data. Implies --demo.
			                        tiny | small | medium | large, or a number of airports.
			                        tiny=%d  small=%d  medium=%d  large=%d   (default: small)
			  --query-timeout=<ms>  Abandon a query that runs longer than this. 0 disables the limit,
			                        which lets one query hold a worker and a read view indefinitely.
			                        Applies to both front ends. (default: %d)
			  --wire-port=<port>    The binary streaming port, bound to loopback. 0 for any free one.
			                        (default: %d)
			  --no-wire             HTTP only. No binary port.
			  --max-streams=<n>     Queries the binary port runs at once; the n+1th is refused rather
			                        than queued. Multiplied by the per-stream write queue, this is the
			                        memory the server may hold for clients that stop reading.
			                        (default: %d)
			  -h, --help            This.

			The HTTP port serves the browser UI and /api/query, which materialises a whole result as
			JSON. The binary port streams rows as they are produced; use graph-client to read it.

			Neither port has authentication or transport security. The binary port binds loopback for
			that reason; the HTTP port does not, and predates this note.

			The demo graph is an air route network: airports in cities in countries, airlines with
			hubs, and routes between airports carrying distance, duration and flight number. See
			spec/CYPHER.md for queries to try.
			""".formatted(GraphDatabases.DEFAULT_NAME, DemoData.TINY, DemoData.SMALL, DemoData.MEDIUM,
				DemoData.LARGE, DEFAULT_QUERY_TIMEOUT_MILLIS, Protocol.DEFAULT_PORT,
				DEFAULT_MAX_CONCURRENT_STREAMS);
	}
}
