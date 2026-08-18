package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.client.ClientOptions;
import io.metaloom.graph.client.ClientTls;
import io.metaloom.graph.client.GraphClient;
import io.metaloom.graph.client.GraphClientException;
import io.metaloom.graph.client.RowStream;
import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.server.GraphServerOptions;
import io.metaloom.graph.server.auth.AuthPolicy;
import io.metaloom.graph.server.metrics.GraphMetrics;

/**
 * TLS on the binary port: that it encrypts, and — the half that is actually easy to get wrong — that it verifies.
 *
 * <h2>What each half is worth on its own</h2>
 *
 * <p>
 * An encrypted connection to whoever answered is not security, it is ceremony: the password in the {@code AUTH} frame
 * goes to the impostor just as privately. So the assertions here are weighted towards refusal — an untrusted
 * certificate, a certificate for the wrong name, a client on the wrong port — because those are the paths where a
 * mistake produces something that looks like it is working.
 * </p>
 *
 * <h2>Two class-level guards, for the reasons {@code WireAuthTest} states</h2>
 *
 * <p>
 * A {@link Timeout}, because a failed TLS handshake could plausibly present as a socket that never answers rather than
 * as an exception, and that shape of failure costs a build with no output. And an event-loop count, because this class
 * opens two listeners per server and closing one of the two would leak an acceptor thread invisibly.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WireTlsTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphStore store;
	private AuthStore identities;
	private WireServer server;
	private TestCertificate certificate;

	private static int eventLoopsAtStart;

	@BeforeAll
	public static void countEventLoops() {
		eventLoopsAtStart = eventLoops();
	}

	private static int eventLoops() {
		int count = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.getName().startsWith("vert.x-eventloop") || thread.getName().startsWith("vert.x-acceptor")) {
				count++;
			}
		}
		return count;
	}

	@BeforeEach
	public void open() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		store = databases.database(GraphDatabases.DEFAULT_NAME);
		try (Transaction txn = store.begin()) {
			store.createNode(List.of("Person"), Properties.of().set("name", "Alice"));
			txn.commit();
		}
		certificate = TestCertificate.create(Files.createDirectories(stores.path().resolve("tls")));
	}

	@AfterEach
	public void stop() throws Exception {
		if (server != null) {
			server.close();
		}
		if (databases != null) {
			assertEquals(0, store.snapshotStats().openViews(), "a read view outlived its query");
			databases.close();
		}
		if (identities != null) {
			identities.close();
		}
		// Both listeners, or neither: a server that closed one of two acceptors would pass every other assertion here
		// and leak a thread per test method.
		assertEquals(eventLoopsAtStart, eventLoops(), "this class leaked a Vert.x event loop");
	}

	/** The owned-Vert.x constructor, deliberately: the other family never closes what it is given. */
	private void listen(boolean plaintext, boolean authenticated) {
		WireServerOptions options = WireServerOptions.defaults()
			.withPort(0)
			.withTls(new WireTlsOptions("127.0.0.1", 0, certificate.certificate(), certificate.privateKey()))
			.withPlaintext(plaintext);
		server = new WireServer(databases, GraphDatabases.DEFAULT_NAME, options);
		if (authenticated) {
			AuthPolicy policy = AuthPolicy.enabled(identities, GraphServerOptions.DEFAULT_SESSION_TIMEOUT_MILLIS);
			server.authPolicy(() -> policy);
		}
		server.listen().toCompletionStage().toCompletableFuture().join();
	}

	/** Trusts exactly the certificate this test generated, and still checks the name on it. */
	private ClientOptions trusting() {
		return ClientOptions.defaults().withTls(ClientTls.trusting(certificate.certificate()));
	}

	private static int drain(GraphClient client, String cypher) {
		try (RowStream rows = client.query(cypher)) {
			int count = 0;
			while (rows.hasNext()) {
				rows.next();
				count++;
			}
			return count;
		}
	}

	// ---------------------------------------------------------------- it works

	@Test
	public void testAQueryRunsOverAnEncryptedConnection() {
		listen(true, false);
		// "localhost" rather than 127.0.0.1, because the certificate names localhost and hostname verification is on.
		try (GraphClient client = GraphClient.connect("localhost", server.tlsPort(), "", trusting())) {
			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"));
		}
	}

	/**
	 * Both listeners at once, which is what makes adding TLS a migration rather than a cutover.
	 *
	 * <p>
	 * The same store through both, so the assertion is not merely that two ports are open but that they are two
	 * front doors to one server rather than two servers.
	 * </p>
	 */
	@Test
	public void testBothListenersServeTheSameStore() {
		listen(true, false);
		assertNotEquals(server.port(), server.tlsPort());
		try (GraphClient plain = GraphClient.connect("127.0.0.1", server.port(), "", ClientOptions.defaults());
			GraphClient encrypted = GraphClient.connect("localhost", server.tlsPort(), "", trusting())) {
			assertEquals(1, drain(plain, "MATCH (n:Person) RETURN n.name"));
			assertEquals(1, drain(encrypted, "MATCH (n:Person) RETURN n.name"));
		}
	}

	/** A login over the encrypted port, which is the whole reason to want one. */
	@Test
	public void testAPasswordCrossesTheEncryptedPort() throws Exception {
		identities = AuthStore.open(stores.path().resolve("root"));
		identities.bootstrap("admin-pw".toCharArray());
		listen(true, true);
		try (GraphClient client = GraphClient.connect("localhost", server.tlsPort(), "",
			trusting().withCredentials("admin", "admin-pw"))) {
			assertTrue(client.server().authenticated());
			assertEquals("admin", client.server().principal());
			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"));
		}
	}

	// ---------------------------------------------------------------- it refuses

	/**
	 * The assertion the whole feature rests on.
	 *
	 * <p>
	 * {@link ClientTls#system()} consults the JVM's trust store, which has never heard of a certificate generated
	 * moments ago. If this passed, then trust configuration was being ignored and every other test here would be
	 * proving only that bytes can be encrypted.
	 * </p>
	 */
	@Test
	public void testAnUntrustedCertificateIsRefused() {
		listen(true, false);
		GraphClientException refused = assertThrows(GraphClientException.class,
			() -> GraphClient.connect("localhost", server.tlsPort(), "",
				ClientOptions.defaults().withTls(ClientTls.system())));
		assertTrue(refused.getMessage().contains("over TLS"), refused.getMessage());
	}

	/**
	 * Hostname verification is on unless somebody turned it off.
	 *
	 * <p>
	 * The certificate names {@code localhost}; this dials {@code 127.0.0.1}, which is the same machine and a different
	 * name. Vert.x leaves this check off by default, so without the client setting it explicitly this connection would
	 * succeed — and so would a connection to an attacker holding any certificate a trusted CA ever issued.
	 * </p>
	 */
	@Test
	public void testACertificateForAnotherNameIsRefused() {
		listen(true, false);
		assertThrows(GraphClientException.class,
			() -> GraphClient.connect("127.0.0.1", server.tlsPort(), "", trusting()));
	}

	/** ...and the documented way out, for an address a certificate cannot name. */
	@Test
	public void testHostnameVerificationCanBeTurnedOffDeliberately() {
		listen(true, false);
		ClientOptions options = ClientOptions.defaults()
			.withTls(ClientTls.trusting(certificate.certificate()).withVerifyHostname(false));
		try (GraphClient client = GraphClient.connect("127.0.0.1", server.tlsPort(), "", options)) {
			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"));
		}
	}

	/**
	 * A plaintext client on the encrypted port fails, and fails <em>quickly</em>.
	 *
	 * <p>
	 * This is why the design uses two ports rather than one negotiated one. TLS speaks first and this protocol's
	 * server does not, so the mismatch is detected during the handshake instead of becoming a client parked forever
	 * waiting for a WELCOME.
	 * </p>
	 */
	@Test
	public void testAPlaintextClientOnTheEncryptedPortFails() {
		listen(true, false);
		assertThrows(GraphClientException.class,
			() -> GraphClient.connect("127.0.0.1", server.tlsPort(), "", ClientOptions.defaults()));
	}

	/** And the other way round, which is the likelier mistake once both ports exist. */
	@Test
	public void testAnEncryptedClientOnThePlaintextPortFails() {
		listen(true, false);
		GraphClientException refused = assertThrows(GraphClientException.class,
			() -> GraphClient.connect("localhost", server.port(), "", trusting()));
		assertTrue(refused.getMessage().contains("plaintext"), refused.getMessage());
	}

	/**
	 * A message that names the remedy, because the mistake is invisible from the client's side otherwise.
	 *
	 * <p>
	 * The failure does not arrive at {@code connect} — the TCP connection succeeds, and it is the HELLO that the
	 * server's TLS engine cannot make sense of. So what the client actually sees is a connection closed without a
	 * single frame on it, and turning that into a sentence naming the port mismatch is the whole point of the
	 * assertion.
	 * </p>
	 */
	@Test
	public void testAPlaintextClientIsToldThatTlsExists() {
		listen(true, false);
		GraphClientException refused = assertThrows(GraphClientException.class,
			() -> GraphClient.connect("127.0.0.1", server.tlsPort(), "", ClientOptions.defaults()));
		assertTrue(refused.getMessage().contains("withTls"), refused.getMessage());
	}

	// ---------------------------------------------------------------- tls only

	@Test
	public void testTlsOnlyOpensNoPlaintextListener() {
		listen(false, false);
		assertEquals(-1, server.port(), "a plaintext listener was opened after --wire-tls-only");
		assertTrue(server.tlsPort() > 0);
		try (GraphClient client = GraphClient.connect("localhost", server.tlsPort(), "", trusting())) {
			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"));
		}
	}

	/** Closing the server closes both acceptors, not the one it happened to remember. */
	@Test
	public void testCloseStopsBothListeners() throws Exception {
		listen(true, false);
		int plaintext = server.port();
		int encrypted = server.tlsPort();
		server.close();
		server = null;
		assertTrue(refusesConnections(plaintext), "the plaintext listener was still accepting after close()");
		assertTrue(refusesConnections(encrypted), "the encrypted listener was still accepting after close()");
	}

	private static boolean refusesConnections(int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
			return false;
		} catch (IOException expected) {
			return true;
		}
	}

	// ---------------------------------------------------------------- the operator's question

	/**
	 * "Is anything still connecting in the clear?" — the question adding TLS creates, and the one that decides when
	 * {@code --wire-tls-only} can be set.
	 *
	 * <p>
	 * Asked of the socket rather than inferred from configuration, so a listener that was meant to be encrypted and
	 * somehow was not would be counted honestly rather than reported as encrypted because that is what the options
	 * said.
	 * </p>
	 */
	@Test
	public void testConnectionsAreCountedByWhetherTheyWereEncrypted() {
		listen(true, false);
		ConnectionCounter counter = new ConnectionCounter();
		server.instrument(counter, 0);
		try (GraphClient plain = GraphClient.connect("127.0.0.1", server.port(), "", ClientOptions.defaults())) {
			assertEquals(1, drain(plain, "MATCH (n:Person) RETURN n.name"));
		}
		try (GraphClient encrypted = GraphClient.connect("localhost", server.tlsPort(), "", trusting())) {
			assertEquals(1, drain(encrypted, "MATCH (n:Person) RETURN n.name"));
		}
		assertEquals(1, counter.plaintext.get(), "one connection arrived in the clear and should be counted as one");
		assertEquals(1, counter.encrypted.get());
	}

	/** Counts the one meter this class is about, and records nothing else. The shape {@code SlowQueryLogTest} uses. */
	private static final class ConnectionCounter implements GraphMetrics {

		private final AtomicInteger plaintext = new AtomicInteger();
		private final AtomicInteger encrypted = new AtomicInteger();

		@Override
		public void recordWireConnection(boolean tls) {
			(tls ? encrypted : plaintext).incrementAndGet();
		}

		@Override
		public void recordQuery(String frontend, String kind, String outcome, long durationNanos, long rows) {
			// Not what this class counts.
		}

		@Override
		public void recordQueryRejected(String frontend, String reason) {
			// Not what this class counts.
		}

		@Override
		public void recordSlowQuery(String frontend) {
			// Not what this class counts.
		}

		@Override
		public void recordWriteStats(CypherStats stats) {
			// Not what this class counts.
		}

		@Override
		public void recordMcpToolCall(String tool, String outcome) {
			// Not what this class counts.
		}

		@Override
		public void bindGauge(String name, String database, Supplier<Number> value) {
			// Nothing, and deliberately without calling value.
		}

		@Override
		public void bindCounter(String name, String database, String tagKey, String tagValue,
			Supplier<Number> monotonicTotal) {
			// Nothing, and deliberately without calling monotonicTotal.
		}
	}

	/**
	 * A certificate that cannot be loaded closes the plaintext listener rather than leaving it serving.
	 *
	 * <p>
	 * The worst available outcome of a TLS misconfiguration is not a failed start — it is a process that announced
	 * the failure and kept serving in the clear, which is what would happen by default: Vert.x's event loops are not
	 * daemon threads, so an exception escaping {@code main} does not end the JVM.
	 * </p>
	 */
	@Test
	public void testAFailedTlsBindTakesThePlaintextListenerWithIt() throws Exception {
		Path notAPem = Files.writeString(stores.path().resolve("tls").resolve("garbage.crt"), "not a certificate");
		server = new WireServer(databases, GraphDatabases.DEFAULT_NAME, WireServerOptions.defaults()
			.withPort(0)
			.withTls(new WireTlsOptions("127.0.0.1", 0, notAPem, certificate.privateKey())));
		assertThrows(Exception.class, () -> server.listen().toCompletionStage().toCompletableFuture().join());
		assertEquals(-1, server.port(), "the plaintext listener was left open after the encrypted one failed");
	}

	// ---------------------------------------------------------------- configuration

	@Test
	public void testTurningOffThePlaintextListenerWithoutACertificateIsRefused() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> WireServerOptions.defaults().withPlaintext(false));
		assertTrue(refused.getMessage().contains("nothing to listen on"), refused.getMessage());
	}

	@Test
	public void testTheTwoListenersMayNotShareAPort() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> WireServerOptions.defaults().withPort(9099)
				.withTls(new WireTlsOptions("127.0.0.1", 9099, certificate.certificate(), certificate.privateKey())));
		assertTrue(refused.getMessage().contains("two listeners"), refused.getMessage());
	}

	/** Two zeroes are two "any free port"s, not a collision. */
	@Test
	public void testTwoEphemeralPortsAreNotAClash() {
		WireServerOptions.defaults().withPort(0)
			.withTls(new WireTlsOptions("127.0.0.1", 0, certificate.certificate(), certificate.privateKey()));
	}

	@Test
	public void testAMissingCertificateIsRefusedByPathRatherThanAtTheBind() {
		Path absent = stores.path().resolve("tls").resolve("nothing.crt");
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> new WireTlsOptions("127.0.0.1", 0, absent, certificate.privateKey()));
		assertTrue(refused.getMessage().contains(absent.toAbsolutePath().toString()), refused.getMessage());
	}

	/** A tls secret mounted at /tls is a directory, and pointing at it instead of at tls.crt is the near miss. */
	@Test
	public void testADirectoryIsRefusedWithAHintAboutTlsCrt() {
		Path directory = stores.path().resolve("tls");
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> new WireTlsOptions("127.0.0.1", 0, directory, certificate.privateKey()));
		assertTrue(refused.getMessage().contains("tls.crt"), refused.getMessage());
	}
}
