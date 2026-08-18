package io.metaloom.graph.protocol;

/**
 * What a client offers when a server asks who it is.
 *
 * <p>
 * In {@code graph-protocol} rather than in the client, because it is the {@link Frames#AUTH} payload and both ends of
 * the wire need to name it. The client depends on this module and on vertx-core and nothing else, which is asserted by
 * {@code ClientDependencyTest} and is why the privileges a server grants cross as strings rather than as an enum.
 * </p>
 *
 * <h2>The {@code toString} is overridden, and it is not decoration</h2>
 *
 * <p>
 * A record generates one that prints every component. This record's components are a user name and a password, and it
 * is about to be held inside {@code ClientOptions} — an object whose whole purpose is to be handed around and which
 * anything might print into a log line, an exception, or a debugger view. Redacting here is what makes that
 * impossible rather than merely unlikely, and {@code ClientOptionsTest} asserts it.
 * </p>
 *
 * @param user
 *            the user name
 * @param password
 *            the password, which crosses the wire in cleartext because this protocol has no transport security. See
 *            {@code spec/WIRE.md} section 7
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public record Credentials(String user, String password) {

	public Credentials {
		if (user == null || user.isEmpty()) {
			throw new IllegalArgumentException("Credentials need a user name");
		}
		if (password == null) {
			throw new IllegalArgumentException("Credentials need a password");
		}
	}

	@Override
	public String toString() {
		return "Credentials[user=" + user + ", password=***]";
	}
}
