package io.metaloom.graph.server.auth;

import java.io.IOException;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.AuthStore;

/**
 * The first administrator, and the one time a password is ever printed.
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 8
 */
public final class Bootstrap {

	private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

	/**
	 * Twenty-four characters of a thirty-one symbol alphabet: about 119 bits.
	 *
	 * <p>
	 * {@code I}, {@code O}, {@code 0} and {@code 1} are missing on purpose. This string's most likely next journey is
	 * being read off one screen and typed into another, and those four are where that goes wrong.
	 * </p>
	 */
	private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	private static final int LENGTH = 24;

	private Bootstrap() {
	}

	/**
	 * Makes sure somebody can log in, and says how if a password had to be invented.
	 *
	 * @param configured
	 *            {@code GRAPH_INITIAL_PASSWORD} or {@code --initial-password}, or null to generate one
	 * @param out
	 *            where a generated password is printed. {@code System.out} in production; a buffer in a test
	 * @return true when an administrator was created
	 */
	public static boolean ensureAdministrator(AuthStore auth, String configured, PrintStream out) throws IOException {
		if (!auth.isEmpty()) {
			if (configured != null) {
				// Not applied, and said out loud. A chart that leaves GRAPH_INITIAL_PASSWORD set would otherwise reset
				// the administrator's password on every pod restart, silently undoing whatever it had been changed to
				// — and the operator would have no way to see that happening.
				logger.info("An initial password was configured, and is ignored: {} already holds users. Use "
					+ "'metaloom-graph-cli auth reset-admin --path=<root>' to change one.", auth.path());
			}
			return false;
		}

		boolean generated = configured == null;
		char[] password = generated ? generate() : configured.toCharArray();
		try {
			auth.bootstrap(password);
			if (generated) {
				announce(out, AuthStore.ADMIN, password);
				// The log gets the fact and not the value: it may be shipped to a collector, and a password in a log
				// aggregator is a password that outlives every rotation anybody performs afterwards.
				logger.warn("No identity file existed, so an administrator was created and a password generated. It "
					+ "was printed to standard output and is not recorded anywhere else.");
			} else {
				logger.info("No identity file existed, so the administrator '{}' was created with the configured "
					+ "password.", AuthStore.ADMIN);
			}
			return true;
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Prints the password to standard output, once, framed.
	 *
	 * <p>
	 * To the stream rather than through the logger, and the difference is the whole point: logback has a configurable
	 * appender and may be shipping to a collector, so {@code logger.info(password)} is a credential in somebody's log
	 * index forever. Standard output is where a one-time secret conventionally goes, and where a person starting a
	 * server is looking.
	 * </p>
	 *
	 * <p>
	 * It still warns about {@code kubectl logs}, because in a container standard output <em>is</em> the log and
	 * pretending otherwise would be the more dangerous half-truth.
	 * </p>
	 */
	static void announce(PrintStream out, String user, char[] password) {
		String line = "=".repeat(72);
		out.println();
		out.println(line);
		out.println("  A first administrator has been created for this root.");
		out.println();
		out.println("    user:     " + user);
		out.println("    password: " + new String(password));
		out.println();
		out.println("  This is shown once and is stored nowhere else. Write it down now.");
		out.println("  In a container this went to the container log, so treat it as exposed and");
		out.println("  change it: metaloom-graph-cli auth reset-admin --path=<root>");
		out.println();
		out.println("  Set GRAPH_INITIAL_PASSWORD to choose the first password instead, or");
		out.println("  --no-auth to run without authentication at all.");
		out.println(line);
		out.println();
		out.flush();
	}

	/** A fresh password. Shared with the CLI's generator in shape and alphabet, deliberately. */
	public static char[] generate() {
		SecureRandom random = new SecureRandom();
		char[] password = new char[LENGTH];
		for (int i = 0; i < password.length; i++) {
			password[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
		}
		return password;
	}
}
