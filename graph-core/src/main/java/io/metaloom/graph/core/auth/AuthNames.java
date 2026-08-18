package io.metaloom.graph.core.auth;

import io.metaloom.graph.core.api.GraphDatabases;

/**
 * The allow-list a user or role name has to pass, which is the one a database name has to pass.
 *
 * <h2>Why the rule is copied rather than the method reused</h2>
 *
 * <p>
 * {@link GraphDatabases#validate} raises messages that say "a database name", and a person who mistyped a user name
 * should not be told about databases. The rule itself must not diverge, though — so it is asserted rather than trusted:
 * {@code AuthNamesTest} fires the same hostile corpus at both and fails if they ever disagree about a name.
 * </p>
 *
 * <h2>Why an allow-list at all, for something that is not a path</h2>
 *
 * <p>
 * A user name is not about to become a directory, so the argument {@link GraphDatabases} makes does not transfer
 * directly. Three others do. It becomes a <b>token in a single-space-separated file</b>, so a name holding a space
 * would produce a file that reads back as a different grant. It is written into <b>log lines</b>, where a newline is a
 * forged log entry. And it is compared for <b>equality</b> to decide authority, which makes Unicode normalisation a
 * security question — two names that look identical and are not is precisely the confusion an attacker wants.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 3.4
 */
public final class AuthNames {

	/** The same limit a database name has: long enough to describe, short enough to log. */
	public static final int MAX_LENGTH = GraphDatabases.MAX_NAME_LENGTH;

	private AuthNames() {
	}

	/**
	 * Checks a user or role name.
	 *
	 * @param kind
	 *            "user" or "role", so the message names what was actually wrong
	 * @throws IllegalArgumentException
	 *             naming what is allowed rather than what was found, because the reader is usually about to pick a
	 *             different name
	 */
	public static void validate(String kind, String name) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("A " + kind + " name is required");
		}
		if (name.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("A " + kind + " name may be at most " + MAX_LENGTH + " characters, '"
				+ name + "' is " + name.length());
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| c == '-' || c == '_';
			if (!allowed) {
				throw new IllegalArgumentException("A " + kind + " name may hold only letters, digits, '-' and '_'; '"
					+ name + "' has '" + c + "' at position " + i);
			}
		}
		if (name.charAt(0) == '-') {
			// Not a path problem here, but the same one it is there: a name starting with a dash is a name that will
			// one day be read as a flag.
			throw new IllegalArgumentException("A " + kind + " name may not start with '-', was '" + name + "'");
		}
	}

	/** Whether a name would pass, without the exception. */
	public static boolean isValid(String name) {
		try {
			validate("name", name);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
