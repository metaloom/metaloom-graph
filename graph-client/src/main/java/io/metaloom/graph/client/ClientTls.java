package io.metaloom.graph.client;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How a client decides whether it reached the server it meant to.
 *
 * <h2>Encryption is the easy half</h2>
 *
 * <p>
 * Turning TLS on is one flag and it is not the part that goes wrong. The part that goes wrong is <b>verification</b>:
 * an encrypted connection to an attacker who answered instead of the server is encrypted, and is worth nothing. So
 * this record's two questions are which certificates to believe and whether the name on the certificate has to match
 * the host that was dialled, and <b>both default to the safe answer</b> — the JVM's trust store and yes.
 * </p>
 *
 * <p>
 * That default matters more here than it looks. Vert.x's own {@code NetClientOptions} leaves hostname verification
 * <em>off</em>, so a client that merely sets {@code setSsl(true)} accepts a valid certificate issued for any name at
 * all. {@link #verifyHostname} exists to make that a decision somebody wrote down rather than one nobody made.
 * </p>
 *
 * <h2>Which factory to reach for</h2>
 *
 * <ul>
 * <li>{@link #system()} — a certificate from a public CA, or a private CA already in the JVM's trust store. The
 * ordinary production answer.
 * <li>{@link #trusting(Path)} — a self-signed certificate, or a private CA that is not installed. Pass the PEM the
 * server presents, or the CA that signed it. Still verifies the hostname.
 * <li>{@link #insecure()} — verifies nothing. For a test, and named so that it cannot appear in a diff unnoticed.
 * </ul>
 *
 * @param trustCertificate
 *            a PEM certificate to trust <b>in place of</b> the JVM's trust store — a self-signed server certificate,
 *            or the private CA that issued it. Null means the JVM's own trust anchors, which is what a public CA
 *            needs
 * @param trustAll
 *            accept any certificate, from anyone. This <b>removes</b> the authentication half of TLS and leaves only
 *            encryption, which stops a passive observer and does nothing about an active one
 * @param verifyHostname
 *            require the certificate to be issued for the host that was dialled. On unless deliberately turned off,
 *            and the thing to turn off when connecting to an IP address whose certificate names only a DNS name
 */
public record ClientTls(Path trustCertificate, boolean trustAll, boolean verifyHostname) {

	public ClientTls {
		if (trustAll && trustCertificate != null) {
			throw new IllegalArgumentException("A trust certificate was given and trustAll was set, so the "
				+ "certificate would never be consulted. Pass one or the other");
		}
		if (trustCertificate != null && !Files.isReadable(trustCertificate)) {
			throw new IllegalArgumentException("The trusted certificate at " + trustCertificate.toAbsolutePath()
				+ " does not exist or cannot be read. It must be a PEM file");
		}
	}

	/** The JVM's trust store, and the hostname must match. What a certificate from a public CA needs. */
	public static ClientTls system() {
		return new ClientTls(null, false, true);
	}

	/**
	 * Trust exactly this certificate — or the CA that signed it — and nothing else.
	 *
	 * <p>
	 * The right answer for a self-signed or internally-issued certificate, and strictly stronger than
	 * {@link #insecure()}: it still refuses everything it was not told about, so an attacker holding a different
	 * certificate is refused rather than accepted.
	 * </p>
	 */
	public static ClientTls trusting(Path pemCertificate) {
		return new ClientTls(pemCertificate, false, true);
	}

	/**
	 * Encrypts, and verifies nothing at all.
	 *
	 * <p>
	 * <b>Anyone who can answer the connection can be the server</b>, read the password sent in the {@code AUTH} frame
	 * and return whatever rows they like. It is here because a test with a certificate generated seconds ago needs it
	 * and because the alternative — people discovering the trust-all switch on a forum and setting it permanently — is
	 * worse. Prefer {@link #trusting(Path)}, which is barely more work and actually verifies something.
	 * </p>
	 */
	public static ClientTls insecure() {
		return new ClientTls(null, true, false);
	}

	/** A copy that does not require the certificate's name to match the host dialled. */
	public ClientTls withVerifyHostname(boolean verify) {
		return new ClientTls(trustCertificate, trustAll, verify);
	}
}
