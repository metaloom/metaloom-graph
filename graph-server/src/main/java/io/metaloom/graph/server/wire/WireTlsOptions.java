package io.metaloom.graph.server.wire;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TLS on the binary port: a certificate, its key, and the port that presents them.
 *
 * <h2>A second listener, not a mode</h2>
 *
 * <p>
 * This does not turn the binary port into an encrypted port. It opens a <b>second</b> one, on
 * {@link #DEFAULT_PORT}, served by the same {@code WireServer} — the same stream permits, the same worker pool, the
 * same watchdog, so {@code WireServerOptions.maxBufferedBytes()} still describes the whole server rather than one of
 * two halves. Which listener a client reached is the whole of what decides whether the connection is encrypted, and
 * that decision is made before a byte of this protocol is exchanged.
 * </p>
 *
 * <p>
 * Two listeners rather than one negotiated port, for three reasons:
 * </p>
 *
 * <ol>
 * <li><b>The protocol does not change.</b> There is no {@code STARTTLS}, no capability bit, no version bump — the
 * frames on an encrypted connection are byte-identical to the frames on a plain one. A negotiated upgrade would have
 * to be refusable by the server and demandable by the client, which is two new failure modes and a downgrade attack
 * to reason about, in exchange for one port number.
 * <li><b>Migration has a middle.</b> An existing deployment can open the encrypted port, move its clients over one at
 * a time while both work, and only then set {@code --wire-tls-only}. A single switchable port makes that a flag day.
 * <li><b>A wrong guess fails immediately.</b> A plaintext client that reaches the TLS port gets a handshake failure
 * rather than a connection that hangs waiting for a {@code WELCOME}, because TLS speaks first and this protocol's
 * server does not.
 * </ol>
 *
 * <h2>What this is not</h2>
 *
 * <p>
 * <b>It is not authentication</b>, and it does not make {@code --no-auth} safe: it proves to the <em>client</em> which
 * server it reached and keeps the conversation private, and says nothing about who the client is. The two are
 * complementary and neither substitutes for the other — a password on a cleartext port is readable by anything on the
 * path, and an encrypted port with no login is readable by anyone who can reach it. See {@code spec/AUTH.md}.
 * </p>
 *
 * <p>
 * <b>It is not client-certificate authentication.</b> A client certificate is not asked for and would not be looked at
 * if offered; a principal comes from the {@code AUTH} frame and nowhere else. Mapping a certificate subject onto a
 * user is a second authentication mechanism rather than a TLS setting — {@code AUTH-8} in {@code spec/TASKS.md}.
 * </p>
 *
 * @param host
 *            what the encrypted listener binds. Defaults to whatever the plaintext one binds, so widening the binary
 *            port stays one decision; set it separately to expose only the encrypted half
 * @param port
 *            0 for any free one, which is what the tests use
 * @param certificate
 *            a PEM certificate, leaf first, followed by any intermediates the client will not already have. The format
 *            a Kubernetes {@code kubernetes.io/tls} secret holds under {@code tls.crt}, and the one cert-manager and
 *            every ACME client write
 * @param privateKey
 *            the matching PEM private key, PKCS#8 or PKCS#1, unencrypted. {@code tls.key} in that same secret. It is
 *            read once at {@code listen()} and never logged
 */
public record WireTlsOptions(String host, int port, Path certificate, Path privateKey) {

	/**
	 * 8084, so the project's ports stay adjacent: 8080 HTTP, 8081 binary, 8082 monitoring, 8083 MCP, 8084 binary over
	 * TLS.
	 *
	 * <p>
	 * Deliberately not 8081 with encryption switched on. An operator who adds a certificate to a running deployment
	 * gets a new port and keeps the old one, which is what makes the move a migration rather than a cutover.
	 * </p>
	 */
	public static final int DEFAULT_PORT = 8084;

	public WireTlsOptions {
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("A bind address is required for the encrypted binary port");
		}
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("Port must be between 0 and 65535, was " + port);
		}
		if (certificate == null || privateKey == null) {
			throw new IllegalArgumentException("TLS on the binary port needs both a certificate and a private key");
		}
		// Checked here rather than left to the bind, because the two are not the same failure. An unreadable path is
		// a configuration mistake with an obvious remedy and it should name the path; a malformed PEM is a key
		// material problem that only the TLS stack can diagnose, and that one is left to it.
		requireReadable(certificate, "certificate");
		requireReadable(privateKey, "private key");
	}

	private static void requireReadable(Path path, String what) {
		if (!Files.isReadable(path)) {
			throw new IllegalArgumentException("The TLS " + what + " at " + path.toAbsolutePath()
				+ " does not exist or cannot be read. It must be an unencrypted PEM file"
				+ (Files.exists(path) ? "; it exists, so this is a permission problem" : ""));
		}
		if (Files.isDirectory(path)) {
			throw new IllegalArgumentException("The TLS " + what + " at " + path.toAbsolutePath()
				+ " is a directory. A Kubernetes tls secret mounted at /tls holds tls.crt and tls.key inside it");
		}
	}

	/** The same certificate and key on a different address. */
	public WireTlsOptions withHost(String host) {
		return new WireTlsOptions(host, port, certificate, privateKey);
	}

	public WireTlsOptions withPort(int port) {
		return new WireTlsOptions(host, port, certificate, privateKey);
	}
}
