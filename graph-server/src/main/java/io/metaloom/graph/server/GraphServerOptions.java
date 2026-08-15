package io.metaloom.graph.server;

import java.nio.file.Path;

import io.metaloom.graph.server.demo.DemoData;

/**
 * The command line.
 *
 * <p>
 * Parsed into a value rather than read out of {@code args} where it is used, so that it can be tested without starting
 * a server: an argument mistake that only shows up as a server bound to the wrong port is a bad way to find out.
 * </p>
 *
 * @param store
 *            the store directory, created if it does not exist
 * @param port
 *            the port to bind, 0 for any free one
 * @param demo
 *            seed an <b>empty</b> store with the demo graph before serving it
 * @param demoAirports
 *            how much demo data: the airport count that everything else is generated around
 */
record GraphServerOptions(Path store, int port, boolean demo, int demoAirports) {

	static final int DEFAULT_PORT = 8080;

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
		int positional = 0;

		for (String argument : args) {
			if (argument.equals("--demo")) {
				demo = true;
			} else if (argument.startsWith("--demo-size=")) {
				airports = DemoData.airportsFor(argument.substring("--demo-size=".length()));
				demo = true;
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
			throw new IllegalArgumentException("A store directory is required");
		}
		return new GraphServerOptions(store, port, demo, airports);
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
			usage: graph-server [options] <store-directory> [port]

			  --demo                Seed the store with a demo graph, but only if it is empty.
			                        The store is opened read-write; the query language is not.
			  --demo-size=<size>    How much demo data. Implies --demo.
			                        tiny | small | medium | large, or a number of airports.
			                        tiny=%d  small=%d  medium=%d  large=%d   (default: small)
			  -h, --help            This.

			The demo graph is an air route network: airports in cities in countries, airlines with
			hubs, and routes between airports carrying distance, duration and flight number. See
			spec/CYPHER.md for queries to try.
			""".formatted(DemoData.TINY, DemoData.SMALL, DemoData.MEDIUM, DemoData.LARGE);
	}
}
