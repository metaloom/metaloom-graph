package io.metaloom.graph.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a client verifies, and that the defaults verify something.
 *
 * <p>
 * These are cheap assertions about a record, and they earn their place for one reason: the value they pin down is a
 * value Vert.x defaults the <em>other</em> way. {@code NetClientOptions} leaves hostname verification off, so an
 * encrypted client built without thinking accepts a certificate issued for any name at all. The behaviour that
 * follows from these fields is asserted end to end, against a real handshake, in {@code WireTlsTest}.
 * </p>
 */
@Tag("unit")
public class ClientTlsTest {

	@TempDir
	Path directory;

	@Test
	public void testTheDefaultVerifiesTheHostname() {
		assertTrue(ClientTls.system().verifyHostname(), "an unverified hostname is an unverified server");
		assertFalse(ClientTls.system().trustAll());
		assertNull(ClientTls.system().trustCertificate(), "the JVM's own anchors");
	}

	@Test
	public void testTrustingACertificateStillChecksTheName() throws Exception {
		Path pem = Files.writeString(directory.resolve("ca.crt"), "-----BEGIN CERTIFICATE-----");
		ClientTls tls = ClientTls.trusting(pem);
		assertEquals(pem, tls.trustCertificate());
		assertFalse(tls.trustAll(), "a pinned certificate is stricter than trust-all, not a synonym for it");
		assertTrue(tls.verifyHostname());
	}

	/** Named so that it cannot appear in a diff unnoticed, and it does what the name says: nothing. */
	@Test
	public void testInsecureVerifiesNothing() {
		assertTrue(ClientTls.insecure().trustAll());
		assertFalse(ClientTls.insecure().verifyHostname());
	}

	/**
	 * Two settings that contradict each other are refused rather than silently ordered.
	 *
	 * <p>
	 * Whichever way the implementation happened to resolve it, one of the two readings would be wrong — and the
	 * dangerous reading, "I pinned a certificate so this is pinned", is the one somebody would believe.
	 * </p>
	 */
	@Test
	public void testACertificateTogetherWithTrustAllIsRefused() throws Exception {
		Path pem = Files.writeString(directory.resolve("ca.crt"), "-----BEGIN CERTIFICATE-----");
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> new ClientTls(pem, true, true));
		assertTrue(refused.getMessage().contains("never be consulted"), refused.getMessage());
	}

	@Test
	public void testAnUnreadableCertificateIsRefusedByPath() {
		Path absent = directory.resolve("nothing.crt");
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> ClientTls.trusting(absent));
		assertTrue(refused.getMessage().contains(absent.toAbsolutePath().toString()), refused.getMessage());
	}

	@Test
	public void testHostnameVerificationCanBeTurnedOffWithoutLosingTheTrustAnchor() throws Exception {
		Path pem = Files.writeString(directory.resolve("ca.crt"), "-----BEGIN CERTIFICATE-----");
		ClientTls tls = ClientTls.trusting(pem).withVerifyHostname(false);
		assertEquals(pem, tls.trustCertificate());
		assertFalse(tls.verifyHostname());
	}

	// ---------------------------------------------------------------- how it reaches a connection

	@Test
	public void testOptionsCarryTlsThroughEveryCopy() {
		ClientTls tls = ClientTls.system();
		ClientOptions options = ClientOptions.defaults()
			.withTls(tls)
			.withCredentials("admin", "hunter2")
			.withReadBufferBytes(1024 * 1024)
			.withSocketReceiveBufferBytes(64 * 1024)
			.withConnectTimeout(java.time.Duration.ofSeconds(3));
		assertSame(tls, options.tls(), "a copy method dropped the transport");
		assertEquals("admin", options.credentials().user());
	}

	@Test
	public void testTheDefaultIsAPlainConnection() {
		assertNull(ClientOptions.defaults().tls());
		assertTrue(ClientOptions.defaults().toString().contains("tls=<plaintext>"));
	}

	/**
	 * The transport is printable and the password still is not.
	 *
	 * <p>
	 * Worth asserting together: adding a component to a record with an overridden {@code toString} is exactly the
	 * edit that reintroduces the generated one.
	 * </p>
	 */
	@Test
	public void testTheOptionsPrintTheTransportAndNotThePassword() {
		ClientOptions options = ClientOptions.defaults()
			.withCredentials("admin", "hunter2")
			.withTls(ClientTls.system());
		assertFalse(options.toString().contains("hunter2"), options.toString());
		assertTrue(options.toString().contains("verifyHostname=true"), options.toString());
	}
}
