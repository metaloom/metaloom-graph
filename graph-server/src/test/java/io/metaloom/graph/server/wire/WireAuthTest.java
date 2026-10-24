package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.client.ClientOptions;
import io.metaloom.graph.client.GraphClient;
import io.metaloom.graph.client.GraphClientException;
import io.metaloom.graph.client.QueryFailedException;
import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.protocol.WireError;
import io.metaloom.graph.server.GraphServerOptions;
import io.metaloom.graph.server.auth.AuthPolicy;

/**
 * The binary port's handshake, and what it refuses.
 *
 * <h2>Two class-level guards, both load-bearing</h2>
 *
 * <p>
 * A {@link Timeout}, because the failure mode of an authentication handshake is <b>not answering</b> — a client parked
 * waiting for a WELCOME that is never coming. Without it that presents as a build that stops with no output, which
 * this project has already paid for once: a missing {@code volatile} on {@code GraphClient.readsPaused} hung a reactor
 * build for twenty-five minutes.
 * </p>
 *
 * <p>
 * And an event-loop count, because {@code WireServer} has two constructor families and the caller-owned-Vert.x one
 * never closes it. Reaching for that one in a test leaks an event loop per method, invisibly to every other assertion
 * here, until something unrelated hangs under load.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WireAuthTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphStore store;
	private AuthStore identities;
	private WireServer server;
	private int port;

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
		databases.create("sales");
		try (Transaction txn = store.begin()) {
			store.createNode(List.of("Person"), Properties.of().set("name", "Alice"));
			txn.commit();
		}

		identities = AuthStore.open(stores.path().resolve("root"));
		identities.bootstrap("admin-pw".toCharArray());
		identities.createRole("reader");
		identities.grantToRole("reader", Grant.on(Privilege.MATCH));
		identities.createUser("reader", "reader-pw".toCharArray(), List.of("reader"));
		identities.createRole("sales-only");
		identities.grantToRole("sales-only", new Grant(Privilege.MATCH, "sales"));
		identities.createUser("salesreader", "sales-pw".toCharArray(), List.of("sales-only"));
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
		assertEquals(eventLoopsAtStart, eventLoops(), "this class leaked a Vert.x event loop");
	}

	/** The owned-Vert.x constructor, deliberately: the other family never closes what it is given. */
	private void listen(boolean authenticated, boolean allowWrite) {
		server = new WireServer(databases, GraphDatabases.DEFAULT_NAME,
			WireServerOptions.defaults().withPort(0), allowWrite);
		if (authenticated) {
			AuthPolicy policy = AuthPolicy.enabled(identities, GraphServerOptions.DEFAULT_SESSION_TIMEOUT_MILLIS);
			server.authPolicy(() -> policy);
		}
		port = server.listen().toCompletionStage().toCompletableFuture().join();
	}

	private static ClientOptions with(String user, String password) {
		return ClientOptions.defaults().withCredentials(user, password);
	}

	/** Drains a stream and counts it. RowStream is an iterator, so consuming it is what makes the query run out. */
	private static int drain(GraphClient client, String cypher) {
		try (io.metaloom.graph.client.RowStream rows = client.query(cypher)) {
			int count = 0;
			while (rows.hasNext()) {
				rows.next();
				count++;
			}
			return count;
		}
	}

	// ---------------------------------------------------------------- the handshake

	@Test
	public void testAClientWithCredentialsConnectsAndIsToldWhoItIs() throws Exception {
		listen(true, false);
		try (GraphClient client = GraphClient.login("127.0.0.1", port, "", "reader", "reader-pw")) {
			assertTrue(client.server().authenticated());
			assertEquals("reader", client.server().principal());
			assertTrue(client.server().grants().contains("MATCH ON *"), client.server().grants().toString());
			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"));
		}
	}

	/**
	 * No credentials, and a clean failure rather than a hang.
	 *
	 * <p>
	 * This is the whole reason the server announces the requirement with a frame instead of waiting to be guessed at.
	 * The class timeout would catch a hang; the point is that it does not have to.
	 * </p>
	 */
	@Test
	public void testAClientWithNoCredentialsFailsRatherThanHanging() {
		listen(true, false);
		GraphClientException refused = assertThrows(GraphClientException.class,
			() -> GraphClient.connect("127.0.0.1", port, "", ClientOptions.defaults()));
		assertTrue(refused.getMessage().contains("requires a login"), refused.getMessage());
	}

	@Test
	public void testAWrongPasswordEndsTheConnection() {
		listen(true, false);
		GraphClientException refused = assertThrows(GraphClientException.class,
			() -> GraphClient.connect("127.0.0.1", port, "", with("reader", "not-the-password")));
		assertTrue(refused.getMessage().contains("do not match") || refused.getMessage().contains("closed"),
			refused.getMessage());
	}

	/** An unknown user is refused exactly as a wrong password is, so the port is not a user enumerator. */
	@Test
	public void testAnUnknownUserLooksLikeAWrongPassword() {
		listen(true, false);
		GraphClientException unknown = assertThrows(GraphClientException.class,
			() -> GraphClient.connect("127.0.0.1", port, "", with("nobody", "any-password")));
		GraphClientException wrong = assertThrows(GraphClientException.class,
			() -> GraphClient.connect("127.0.0.1", port, "", with("reader", "wrong")));
		assertEquals(unknown.getMessage(), wrong.getMessage());
	}

	/**
	 * With authentication off the handshake is what it always was, and the password never leaves the client.
	 *
	 * <p>
	 * A client that holds credentials and meets a server that does not want them completes the handshake without
	 * sending them. That is the property that decided against putting them in {@code HELLO}.
	 * </p>
	 */
	@Test
	public void testAgainstAnUnauthenticatedServerTheCredentialsAreNeverSent() throws Exception {
		listen(false, false);
		try (GraphClient client = GraphClient.connect("127.0.0.1", port, "", with("reader", "reader-pw"))) {
			assertFalse(client.server().authenticated());
			assertEquals("", client.server().principal());
			assertTrue(client.server().grants().isEmpty());
			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"));
		}
	}

	// ---------------------------------------------------------------- authorization

	/**
	 * An ungranted query is refused per stream, and the connection survives it.
	 *
	 * <p>
	 * Structurally the same as {@code READ_ONLY}: the next query on the connection may well be one this principal may
	 * run, so ending the connection would be the wrong shape of refusal.
	 * </p>
	 */
	@Test
	public void testAnUngrantedQueryIsRefusedAndTheConnectionSurvives() throws Exception {
		listen(true, true);
		try (GraphClient client = GraphClient.login("127.0.0.1", port, "", "reader", "reader-pw")) {
			QueryFailedException refused = assertThrows(QueryFailedException.class,
				() -> drain(client, "CREATE (n:Person)"));
			assertEquals(WireError.UNAUTHORIZED, refused.error());
			assertTrue(refused.getMessage().contains("CREATE"), refused.getMessage());

			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"),
				"the connection is still usable after a refused stream");
		}
	}

	/**
	 * A revoke or a disable reaches a connection that is already open.
	 *
	 * <p>
	 * Grants are resolved per query, as HTTP resolves them per request ({@code spec/AUTH.md} section 6.1). Freezing
	 * them at AUTH would let a revoked user keep a long-lived connection — exactly what a pooled client is — for as
	 * long as it stays up.
	 * </p>
	 */
	@Test
	public void testARevokeAndADisableReachAnOpenConnection() throws Exception {
		listen(true, false);
		try (GraphClient client = GraphClient.login("127.0.0.1", port, "", "reader", "reader-pw")) {
			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"));

			identities.revokeFromRole("reader", Grant.on(Privilege.MATCH));
			QueryFailedException revoked = assertThrows(QueryFailedException.class,
				() -> drain(client, "MATCH (n:Person) RETURN n.name"));
			assertEquals(WireError.UNAUTHORIZED, revoked.error());

			identities.grantToRole("reader", Grant.on(Privilege.MATCH));
			assertEquals(1, drain(client, "MATCH (n:Person) RETURN n.name"), "and a grant reaches it too");

			identities.setEnabled("reader", false);
			GraphClientException disabled = assertThrows(GraphClientException.class,
				() -> drain(client, "MATCH (n:Person) RETURN n.name"));
			assertFalse(disabled instanceof QueryFailedException q && q.error() == WireError.UNAUTHORIZED,
				"a disabled user is not merely unauthorized: " + disabled.getMessage());
		}
	}

	/** A per-database grant is per database on this port too. */
	@Test
	public void testAGrantOnOneDatabaseDoesNotReachAnother() throws Exception {
		listen(true, false);
		try (GraphClient sales = GraphClient.login("127.0.0.1", port, "sales", "salesreader", "sales-pw")) {
			assertEquals(0, drain(sales, "MATCH (n) RETURN n"));
		}
		// The same principal, the same query, a different database on the connection.
		try (GraphClient other = GraphClient.login("127.0.0.1", port, GraphDatabases.DEFAULT_NAME,
			"salesreader", "sales-pw")) {
			QueryFailedException refused = assertThrows(QueryFailedException.class,
				() -> drain(other, "MATCH (n) RETURN n"));
			assertEquals(WireError.UNAUTHORIZED, refused.error());
		}
	}

	/**
	 * The flag still bounds an administrator who holds everything.
	 *
	 * <p>
	 * And the refusal is the flag's, {@code READ_ONLY}, not the grant's — the same distinction the HTTP route makes,
	 * for the same reason: the remedy is a restart and only an operator can perform it.
	 * </p>
	 */
	@Test
	public void testAGrantCannotWidenWhatTheFlagAllows() throws Exception {
		listen(true, false);
		try (GraphClient client = GraphClient.login("127.0.0.1", port, "", "admin", "admin-pw")) {
			QueryFailedException refused = assertThrows(QueryFailedException.class,
				() -> drain(client, "CREATE (n:Person)"));
			assertEquals(WireError.READ_ONLY, refused.error(),
				"the flag refused, not the grant: the administrator holds CREATE ON *");
		}
	}

	/**
	 * Before AUTH, a missing database and a present one are answered alike.
	 *
	 * <p>
	 * HELLO names the database, and resolving it there answered {@code NO_SUCH_DATABASE} to a peer that had presented
	 * nothing — a list of the databases for anybody who can reach the port, and a store opened on its behalf. With
	 * authentication on the name is resolved only once the credential has been checked.
	 * </p>
	 */
	@Test
	public void testHelloDoesNotRevealWhetherADatabaseExistsBeforeAuth() throws Exception {
		listen(true, false);
		assertEquals(io.metaloom.graph.protocol.Frames.AUTH_REQUIRED, firstAnswerToHello("nosuch"));
		assertEquals(io.metaloom.graph.protocol.Frames.AUTH_REQUIRED, firstAnswerToHello("sales"));

		// Once the caller has said who they are, a missing name is still reported as missing.
		QueryFailedException missing = assertThrows(QueryFailedException.class,
			() -> GraphClient.login("127.0.0.1", port, "nosuch", "reader", "reader-pw").close());
		assertEquals(WireError.NO_SUCH_DATABASE, missing.error());
	}

	/**
	 * More concurrent AUTH frames than there are processors are answered {@code OVERLOADED}, not queued.
	 *
	 * <p>
	 * One verification is a hundred milliseconds of CPU, so a burst of them queued behind each other is a CPU-bound
	 * outage — and it was queued on the query pool, so every query waited behind the hashing too. The right password
	 * every time, so the failure throttle (which also answers OVERLOADED) cannot be what refuses.
	 * </p>
	 */
	@Test
	public void testABurstOfAuthFramesIsBoundedRatherThanQueued() throws Exception {
		listen(true, false);
		int peers = Runtime.getRuntime().availableProcessors() * 4 + 4;
		List<java.net.Socket> sockets = new java.util.ArrayList<>();
		try {
			for (int i = 0; i < peers; i++) {
				java.net.Socket socket = new java.net.Socket();
				sockets.add(socket);
				socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5_000);
				socket.setSoTimeout(30_000);
				io.metaloom.graph.protocol.WireValueCodec.Writer hello =
					new io.metaloom.graph.protocol.WireValueCodec.Writer(64);
				hello.i32(io.metaloom.graph.protocol.Protocol.VERSION);
				io.metaloom.graph.protocol.WireValueCodec.encode(hello, "");
				socket.getOutputStream().write(io.metaloom.graph.protocol.Frames.frame(
					io.metaloom.graph.protocol.Frames.HELLO, 0, hello).getBytes());
				assertEquals(io.metaloom.graph.protocol.Frames.AUTH_REQUIRED, readFrame(socket).type);
			}
			// Every AUTH as close together as a loop can send them.
			io.metaloom.graph.protocol.WireValueCodec.Writer auth =
				new io.metaloom.graph.protocol.WireValueCodec.Writer(64);
			auth.i32(io.metaloom.graph.protocol.Frames.MECHANISM_PASSWORD);
			io.metaloom.graph.protocol.WireValueCodec.encode(auth, "reader");
			io.metaloom.graph.protocol.WireValueCodec.encode(auth, "reader-pw");
			byte[] frame = io.metaloom.graph.protocol.Frames.frame(
				io.metaloom.graph.protocol.Frames.AUTH, 0, auth).getBytes();
			for (java.net.Socket socket : sockets) {
				socket.getOutputStream().write(frame);
				socket.getOutputStream().flush();
			}
			int welcomed = 0;
			int overloaded = 0;
			for (java.net.Socket socket : sockets) {
				Frame answer = readFrame(socket);
				if (answer.type == io.metaloom.graph.protocol.Frames.WELCOME) {
					welcomed++;
				} else if (answer.type == io.metaloom.graph.protocol.Frames.ERROR
					&& WireError.fromCode(new io.metaloom.graph.protocol.WireValueCodec.Reader(answer.payload).i32())
						== WireError.OVERLOADED) {
					overloaded++;
				}
			}
			assertTrue(overloaded > 0, "no AUTH was refused: " + welcomed + " of " + peers + " were verified");
			assertTrue(welcomed > 0, "and some were still served");
			assertEquals(peers, welcomed + overloaded);
		} finally {
			for (java.net.Socket socket : sockets) {
				socket.close();
			}
		}
	}

	private record Frame(int type, byte[] payload) {
	}

	private static Frame readFrame(java.net.Socket socket) throws java.io.IOException {
		java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
		byte[] header = new byte[8];
		in.readFully(header);
		int length = java.nio.ByteBuffer.wrap(header, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
		byte[] payload = new byte[length];
		in.readFully(payload);
		return new Frame(header[4] & 0xFF, payload);
	}

	/** The type of the first frame a server sends after a HELLO naming {@code database}. */
	private int firstAnswerToHello(String database) throws Exception {
		try (java.net.Socket socket = new java.net.Socket()) {
			socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5_000);
			socket.setSoTimeout(10_000);
			io.metaloom.graph.protocol.WireValueCodec.Writer hello =
				new io.metaloom.graph.protocol.WireValueCodec.Writer(64);
			hello.i32(io.metaloom.graph.protocol.Protocol.VERSION);
			io.metaloom.graph.protocol.WireValueCodec.encode(hello, database);
			socket.getOutputStream().write(io.metaloom.graph.protocol.Frames.frame(
				io.metaloom.graph.protocol.Frames.HELLO, 0, hello).getBytes());
			socket.getOutputStream().flush();
			byte[] header = new byte[8];
			new java.io.DataInputStream(socket.getInputStream()).readFully(header);
			return header[4] & 0xFF;
		}
	}

	// ---------------------------------------------------------------- version

	/**
	 * A version mismatch is still a clean refusal, and it happens before any credential is asked for.
	 *
	 * <p>
	 * Asserted through a raw socket, because a client of this build cannot speak the old version.
	 * </p>
	 */
	@Test
	public void testAnOlderProtocolVersionIsRefusedBeforeAnyCredentialIsRequested() throws Exception {
		listen(true, false);
		try (java.net.Socket socket = new java.net.Socket()) {
			socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5_000);
			socket.setSoTimeout(10_000);

			io.metaloom.graph.protocol.WireValueCodec.Writer hello =
				new io.metaloom.graph.protocol.WireValueCodec.Writer(64);
			hello.i32(3);
			io.metaloom.graph.protocol.WireValueCodec.encode(hello, "");
			io.vertx.core.buffer.Buffer frame = io.metaloom.graph.protocol.Frames.frame(
				io.metaloom.graph.protocol.Frames.HELLO, 0, hello);
			socket.getOutputStream().write(frame.getBytes());
			socket.getOutputStream().flush();

			java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
			byte[] header = new byte[8];
			in.readFully(header);
			int type = header[4] & 0xFF;
			assertEquals(io.metaloom.graph.protocol.Frames.ERROR, type,
				"a version mismatch must not be answered with AUTH_REQUIRED");

			int length = java.nio.ByteBuffer.wrap(header, 0, 4)
				.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
			byte[] payload = new byte[length];
			in.readFully(payload);
			io.metaloom.graph.protocol.WireValueCodec.Reader reader =
				new io.metaloom.graph.protocol.WireValueCodec.Reader(payload);
			assertEquals(WireError.UNSUPPORTED_VERSION, WireError.fromCode(reader.i32()));
		}
	}

	// ---------------------------------------------------------------- pipelining

	/**
	 * A QUERY sent before AUTH is refused rather than run.
	 *
	 * <p>
	 * A raw socket again, because a correct client would never do this — which is precisely why the server has to
	 * decide what happens when one does. Without the explicit state, a query could reach a worker while the credential
	 * check that would have refused it was still running.
	 * </p>
	 */
	@Test
	public void testAQueryBeforeAuthIsRefused() throws Exception {
		listen(true, false);
		try (java.net.Socket socket = new java.net.Socket()) {
			socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5_000);
			socket.setSoTimeout(10_000);

			io.metaloom.graph.protocol.WireValueCodec.Writer hello =
				new io.metaloom.graph.protocol.WireValueCodec.Writer(64);
			hello.i32(io.metaloom.graph.protocol.Protocol.VERSION);
			io.metaloom.graph.protocol.WireValueCodec.encode(hello, "");
			socket.getOutputStream().write(io.metaloom.graph.protocol.Frames.frame(
				io.metaloom.graph.protocol.Frames.HELLO, 0, hello).getBytes());

			// AUTH_REQUIRED comes back; ignore it and send a query instead.
			java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
			byte[] header = new byte[8];
			in.readFully(header);
			assertEquals(io.metaloom.graph.protocol.Frames.AUTH_REQUIRED, header[4] & 0xFF);
			int length = java.nio.ByteBuffer.wrap(header, 0, 4)
				.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
			in.readFully(new byte[length]);

			io.metaloom.graph.protocol.WireValueCodec.Writer query =
				new io.metaloom.graph.protocol.WireValueCodec.Writer(64);
			io.metaloom.graph.protocol.WireValueCodec.encode(query, "MATCH (n) RETURN n");
			io.metaloom.graph.protocol.WireValueCodec.encode(query, java.util.Map.of());
			query.i64(0);
			socket.getOutputStream().write(io.metaloom.graph.protocol.Frames.frame(
				io.metaloom.graph.protocol.Frames.QUERY, 1, query).getBytes());
			socket.getOutputStream().flush();

			in.readFully(header);
			assertEquals(io.metaloom.graph.protocol.Frames.ERROR, header[4] & 0xFF,
				"a query before AUTH must be refused, not run");
		}
	}
}
