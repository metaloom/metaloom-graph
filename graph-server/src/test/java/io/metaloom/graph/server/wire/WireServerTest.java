package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.client.ClientOptions;
import io.metaloom.graph.client.GraphClient;
import io.metaloom.graph.client.GraphClientException;
import io.metaloom.graph.client.QueryFailedException;
import io.metaloom.graph.client.QueryOptions;
import io.metaloom.graph.client.Row;
import io.metaloom.graph.client.RowStream;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.protocol.Frames;
import io.metaloom.graph.protocol.Protocol;
import io.metaloom.graph.protocol.QuerySummary;
import io.metaloom.graph.protocol.WireError;
import io.metaloom.graph.protocol.WireValueCodec;
import io.vertx.core.buffer.Buffer;

/**
 * The protocol's mechanics: what happens when things go wrong.
 *
 * <p>
 * The happy path is {@link WireVersusEmbeddedTest}'s job. What is here is the behaviour an operator and a client author
 * actually depend on — that a query slot comes back, that a deadline is enforced against a client that has stopped
 * reading rather than only between rows, that one bad connection does not take the server with it, and that a read view
 * is never left open.
 * </p>
 *
 * <h2>The fixture is shaped for backpressure</h2>
 *
 * <p>
 * Two thousand documents of two kilobytes each: four megabytes on the wire from two thousand rows. A test that produced
 * the same volume from a million small rows would spend its time in the executor and its memory on boxed longs, and
 * would be measuring the wrong thing.
 * </p>
 */
@Tag("unit")
public class WireServerTest {

	private static final int DOCUMENTS = 2_000;
	private static final String BODY = "x".repeat(2_048);

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphStore store;
	private WireServer server;
	private int port;

	@BeforeEach
	public void start() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		store = databases.database(GraphDatabases.DEFAULT_NAME);
		try (Transaction txn = store.begin()) {
			store.createNode(List.of("Person"), Properties.of().set("name", "Alice").set("age", 34L));
			for (int i = 0; i < DOCUMENTS; i++) {
				store.createNode(List.of("Document"), Properties.of().set("n", (long) i).set("body", BODY));
			}
			txn.commit();
		}
	}

	@AfterEach
	public void stop() throws Exception {
		if (server != null) {
			server.close();
		}
		if (databases != null) {
			// Every stream opens a read view and every view must be closed by the thread that opened it. A view left
			// open holds back reclamation for as long as it lives, so this is the assertion that matters most.
			assertEquals(0, store.snapshotStats().openViews(), "a read view outlived its query");
			databases.close();
		}
	}

	private void listen(WireServerOptions options) {
		listen(options, false);
	}

	private void listen(WireServerOptions options, boolean allowWrite) {
		server = new WireServer(io.vertx.core.Vertx.vertx(), databases, GraphDatabases.DEFAULT_NAME,
			options.withPort(0), allowWrite);
		port = server.listen().toCompletionStage().toCompletableFuture().join();
	}

	private void listen() {
		listen(WireServerOptions.defaults());
	}

	private void listenAllowingWrites() {
		listen(WireServerOptions.defaults(), true);
	}

	private GraphClient connect() {
		return GraphClient.connect("127.0.0.1", port);
	}

	/**
	 * A client with both of its buffers wound down, for the tests about a peer that stops reading.
	 *
	 * <p>
	 * The kernel receive buffer has to come down with the heap one. Left to autotune, a loopback socket will hold
	 * megabytes, and a whole four-megabyte result reaches the client before the server ever notices it is not being
	 * read — which is exactly how three of these tests passed while proving nothing.
	 * </p>
	 */
	private static ClientOptions tightClient() {
		return ClientOptions.defaults()
			.withReadBufferBytes(64 * 1024)
			.withSocketReceiveBufferBytes(32 * 1024);
	}

	// ---------------------------------------------------------------- writes

	@Test
	public void testAWriteIsRefusedWithoutTheFlag() {
		listen();
		try (GraphClient client = connect()) {
			QueryFailedException thrown = assertThrows(QueryFailedException.class,
				() -> client.execute("CREATE (n:City {name: 'Bonn'})"));
			// Its own code: the query is fine and the same text against a server with the flag would run, so a client
			// should report this rather than retry it.
			assertEquals(WireError.READ_ONLY, thrown.error());
			assertTrue(thrown.getMessage().contains("--allow-write"), thrown.getMessage());
			assertEquals(0, store.nodesByLabel("City").size());
		}
	}

	@Test
	public void testAWriteRunsAndReportsWhatItChanged() {
		listenAllowingWrites();
		try (GraphClient client = connect()) {
			QuerySummary summary = client.execute("CREATE (a:City {name: 'Bonn'})-[:IN]->(b:Country {name: 'DE'})");

			assertEquals(0, summary.rows(), "a query with no RETURN produces none");
			assertEquals(2, summary.nodesCreated());
			assertEquals(1, summary.relationshipsCreated());
			assertEquals(2, summary.propertiesSet());
			assertEquals(2, summary.labelsAdded());
			assertTrue(summary.containsUpdates());
			assertEquals(1, store.nodesByLabel("City").size());
		}
	}

	@Test
	public void testAWriteWithAReturnStreamsItsRowsAndItsSummary() {
		listenAllowingWrites();
		try (GraphClient client = connect(); RowStream rows = client.query(
			"MATCH (n:Person) SET n.age = 35 RETURN n.name AS name, n.age AS age")) {

			assertEquals(List.of("name", "age"), rows.columns());
			assertTrue(rows.hasNext());
			Row row = rows.next();
			assertEquals("Alice", row.string("name"));
			assertEquals(35L, row.longValue("age"), "the row must carry the value the same query just wrote");
			assertFalse(rows.hasNext());

			QuerySummary summary = rows.summary();
			assertEquals(1, summary.rows());
			assertEquals(1, summary.propertiesSet());
		}
	}

	@Test
	public void testAReadReportsNoUpdates() {
		listenAllowingWrites();
		try (GraphClient client = connect()) {
			QuerySummary summary = client.execute("MATCH (n:Person) RETURN n");
			assertEquals(1, summary.rows());
			assertFalse(summary.containsUpdates());
			assertTrue(summary.elapsedMicros() >= 0);
		}
	}

	@Test
	public void testAFailedWriteRollsBackAndLeavesTheStoreUsable() {
		listenAllowingWrites();
		try (GraphClient client = connect()) {
			// Creates a node, then fails on a property a node cannot hold.
			assertThrows(QueryFailedException.class,
				() -> client.execute("MATCH (p:Person) CREATE (c:Copy) SET c.owner = p"));
			assertEquals(0, store.nodesByLabel("Copy").size(), "the created node must have gone with the failure");

			// And the writer lock came back, which is what the next query proves.
			assertEquals(1, client.execute("CREATE (n:After)").nodesCreated());
		}
	}

	/**
	 * Two writers on one store, from two connections.
	 *
	 * <p>
	 * The engine serialises them on one lock, so what matters is that both finish and neither hangs — the permit exists
	 * to bound the wait, not to reject work that can proceed.
	 * </p>
	 */
	@Test
	public void testTwoConnectionsWritingToOneDatabaseBothSucceed() throws Exception {
		listenAllowingWrites();
		try (GraphClient one = connect(); GraphClient two = connect()) {
			java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
			try {
				var first = pool.submit(() -> one.execute("UNWIND range(1, 50) AS i CREATE (:Bulk {i: i})"));
				var second = pool.submit(() -> two.execute("UNWIND range(1, 50) AS i CREATE (:Bulk {i: i})"));
				assertEquals(50, first.get(30, java.util.concurrent.TimeUnit.SECONDS).nodesCreated());
				assertEquals(50, second.get(30, java.util.concurrent.TimeUnit.SECONDS).nodesCreated());
			} finally {
				pool.shutdownNow();
			}
			assertEquals(100, store.nodesByLabel("Bulk").size());
		}
	}

	@Test
	public void testAWriteLeavesNoViewAndNoLockBehind() throws Exception {
		listenAllowingWrites();
		try (GraphClient client = connect()) {
			client.execute("CREATE (n:City)");
			assertEquals(0, store.snapshotStats().openViews(), "a write query opens no read view at all");
			// A leaked write transaction would block every writer in the process, and openViews() cannot see it.
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				txn.rollback();
			}
		}
	}

	// ---------------------------------------------------------------- the handshake

	@Test
	public void testTheHandshakeReportsTheServersLimits() {
		listen(WireServerOptions.defaults().withMaxConcurrentStreams(3));
		try (GraphClient client = connect()) {
			assertEquals(Protocol.VERSION, client.server().version());
			assertEquals(Frames.MAX_FRAME_BYTES, client.server().maxFrameBytes());
			assertEquals(3, client.server().maxConcurrentStreams(),
				"a client that had to guess the stream limit would find it one OVERLOADED at a time");
		}
	}

	@Test
	public void testTheConnectionIsBoundToTheDatabaseItAskedFor() throws Exception {
		listen();
		databases.create("sales");
		try (Transaction txn = databases.database("sales").begin()) {
			databases.database("sales").createNode("Widget", Properties.of().set("name", "Widget"));
			txn.commit();
		}

		// Two connections, two databases, from one process. The default sees Alice and not Widget; sales the reverse.
		try (GraphClient onDefault = connect();
			GraphClient onSales = GraphClient.connect("127.0.0.1", port, "sales")) {

			assertEquals("default", onDefault.database());
			assertEquals("sales", onSales.database(), "WELCOME echoes what the server resolved");

			try (RowStream stream = onDefault.query("MATCH (n:Person) RETURN n.name")) {
				assertTrue(stream.hasNext());
				assertEquals("Alice", stream.next().string("n.name"));
			}
			try (RowStream stream = onSales.query("MATCH (n:Person) RETURN n.name")) {
				assertEquals(false, stream.hasNext(), "sales has no Person; the connections are genuinely separate");
			}
			try (RowStream stream = onSales.query("MATCH (n:Widget) RETURN n.name")) {
				assertTrue(stream.hasNext());
				assertEquals("Widget", stream.next().string("n.name"));
			}
		}
		awaitIdle();
	}

	@Test
	public void testAnEmptyNameMeansTheServersDefault() {
		// A client that hard-coded 'default' would query the wrong data on a server started with --database=sales.
		listen();
		try (GraphClient client = GraphClient.connect("127.0.0.1", port, GraphClient.DEFAULT_DATABASE)) {
			assertEquals("default", client.database());
		}
	}

	@Test
	public void testAnUnknownDatabaseIsRefusedAtTheHandshake() {
		listen();
		GraphClientException thrown = assertThrows(GraphClientException.class,
			() -> GraphClient.connect("127.0.0.1", port, "nope"));
		assertInstanceOf(QueryFailedException.class, thrown);
		assertEquals(WireError.NO_SUCH_DATABASE, ((QueryFailedException) thrown).error());
		assertTrue(thrown.getMessage().contains("nope"), thrown.getMessage());
		assertServerStillWorks();
	}

	@Test
	public void testADatabaseNameThatIsNotANameIsRefusedBeforeItBecomesAPath() {
		listen();
		QueryFailedException thrown = assertThrows(QueryFailedException.class,
			() -> GraphClient.connect("127.0.0.1", port, "../escaped"));
		assertEquals(WireError.NO_SUCH_DATABASE, thrown.error());
		assertServerStillWorks();
	}

	@Test
	public void testAnIncompatibleVersionGetsAnErrorRatherThanAHang() throws Exception {
		listen();
		try (RawConnection raw = new RawConnection(port)) {
			WireValueCodec.Writer hello = new WireValueCodec.Writer(32);
			hello.i32(Protocol.VERSION + 99);
			WireValueCodec.encode(hello, "");
			raw.write(Frames.frame(Frames.HELLO, Frames.CONNECTION_STREAM, hello));

			RawFrame answer = raw.read();
			assertEquals(Frames.ERROR, answer.type());
			assertEquals(WireError.UNSUPPORTED_VERSION, answer.error());
			assertTrue(answer.message().contains("version"), answer.message());
			assertEquals(-1, raw.in.read(), "the server must close a connection it cannot speak to");
		}
		assertServerStillWorks();
	}

	@Test
	public void testAQueryBeforeHelloIsRefused() throws Exception {
		listen();
		try (RawConnection raw = new RawConnection(port)) {
			raw.write(Frames.frame(Frames.QUERY, 1, queryPayload("RETURN 1", 0)));
			RawFrame answer = raw.read();
			assertEquals(Frames.ERROR, answer.type());
			assertEquals(WireError.PROTOCOL, answer.error());
		}
		assertServerStillWorks();
	}

	@Test
	public void testAnOversizedFrameClosesOneConnectionAndNotTheServer() throws Exception {
		listen();
		try (RawConnection raw = new RawConnection(port)) {
			// A header claiming more than the cap, with nothing behind it. The point is that the server refuses it
			// before allocating for it — a length prefix off a socket is the textbook unvalidated length field.
			ByteBuffer header = ByteBuffer.allocate(Frames.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
			header.putInt(Frames.MAX_FRAME_BYTES + 1);
			header.put((byte) Frames.QUERY);
			header.put((byte) 0);
			header.putShort((short) 1);
			raw.out.write(header.array());
			raw.out.flush();

			RawFrame answer = raw.read();
			assertEquals(Frames.ERROR, answer.type());
			assertEquals(WireError.PROTOCOL, answer.error());
		}
		// The whole point: everybody else is unaffected.
		assertServerStillWorks();
	}

	private void assertServerStillWorks() {
		try (GraphClient client = connect(); RowStream stream = client.query("MATCH (n:Person) RETURN n.name")) {
			assertTrue(stream.hasNext());
			assertEquals("Alice", stream.next().string("n.name"));
		}
	}

	// ---------------------------------------------------------------- errors

	@Test
	public void testASyntaxErrorNamesItsPosition() {
		listen();
		try (GraphClient client = connect(); RowStream stream = client.query("MATCH (n RETURN n")) {
			QueryFailedException failure = assertThrows(QueryFailedException.class, stream::hasNext);
			assertEquals(WireError.SYNTAX, failure.error());
			assertNotEquals(0, failure.line(), "a syntax error without a position is much less useful");
		}
		assertEquals(0, server.runningStreams(), "a query that never ran must not have taken a slot");
	}

	@Test
	public void testASyntaxErrorAlsoWakesACallerBlockedOnColumns() {
		// columns() blocks for a header that a failed query will never send, so the error has to arrive there too.
		listen();
		try (GraphClient client = connect(); RowStream stream = client.query("MATCH (n RETURN n")) {
			assertThrows(QueryFailedException.class, stream::columns);
		}
	}

	@Test
	public void testATypeErrorIsReportedAsOneRatherThanAsAServerFault() {
		listen();
		try (GraphClient client = connect(); RowStream stream = client.query("UNWIND 5 AS x RETURN x")) {
			QueryFailedException failure = assertThrows(QueryFailedException.class, stream::hasNext);
			assertEquals(WireError.TYPE, failure.error(),
				"a query the caller can fix must not be reported as INTERNAL");
		}
	}

	@Test
	public void testAnUnknownFunctionFailsTheStreamAndLeavesTheServerUsable() {
		listen();
		try (GraphClient client = connect()) {
			try (RowStream stream = client.query("MATCH (n) RETURN frobnicate(n)")) {
				assertThrows(QueryFailedException.class, stream::hasNext);
			}
			try (RowStream stream = client.query("MATCH (n:Person) RETURN n.name")) {
				assertTrue(stream.hasNext(), "the connection has to survive a failed query on it");
			}
		}
	}

	// ---------------------------------------------------------------- bounds

	@Test
	public void testTheDeadlineFiresAgainstAClientThatHasStoppedReading() {
		// The case a between-rows check cannot cover. The producer is parked waiting for a socket that will not
		// drain, and the deadline has to reach it there or the query holds a worker and a read view indefinitely.
		listen(WireServerOptions.defaults()
			.withWriteQueueMaxBytes(64 * 1024)
			.withSocketSendBufferBytes(32 * 1024));
		try (GraphClient client = GraphClient.connect("127.0.0.1", port, tightClient())) {
			RowStream stream = client.query("MATCH (n:Document) RETURN n.body",
				java.util.Map.of(), QueryOptions.deadline(Duration.ofMillis(400)));

			QueryFailedException failure = assertThrows(QueryFailedException.class, () -> {
				// Drain slowly enough that the server's queue stays full. The rows that did arrive are not a result:
				// the stream throws at the row where the error caught up with it.
				while (stream.hasNext()) {
					stream.next();
					sleep(2);
				}
				fail("the query should have been abandoned");
			});
			assertEquals(WireError.TIMEOUT, failure.error());
			assertTrue(failure.getMessage().contains("400 ms"), failure.getMessage());
			stream.close();
		}
		awaitIdle();
	}

	@Test
	public void testAClientThatStopsReadingCannotMakeTheServerBufferMoreThanItsBound() throws Exception {
		WireServerOptions options = WireServerOptions.defaults()
			.withMaxConcurrentStreams(2)
			.withWriteQueueMaxBytes(64 * 1024)
			.withSocketSendBufferBytes(32 * 1024);
		listen(options);

		try (GraphClient client = GraphClient.connect("127.0.0.1", port, tightClient())) {
			RowStream stream = client.query("MATCH (n:Document) RETURN n.body");
			// Read nothing. The client's queue fills, it pauses the socket, the receive window closes and the
			// server's write queue fills behind it.
			long peak = 0;
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (System.nanoTime() - deadline < 0) {
				peak = Math.max(peak, server.bufferedBytes());
				if (peak >= options.writeQueueMaxBytes()) {
					break;
				}
				sleep(5);
			}
			peak = Math.max(peak, server.bufferedBytes());
			assertTrue(peak > 0, "the server never buffered anything; the fixture is not big enough to test this");
			assertTrue(peak <= options.maxWriteQueueBytes(),
				"peaked at " + peak + " buffered bytes against a bound of " + options.maxWriteQueueBytes()
					+ "; the producer got ahead of the event loop");

			// And it is not stuck: reading again lets the whole result through.
			long rows = 0;
			while (stream.hasNext()) {
				stream.next();
				rows++;
			}
			assertEquals(DOCUMENTS, rows);
			stream.close();
		}
		awaitIdle();
	}

	@Test
	public void testTheQuerySlotComesBackAfterACancel() {
		listen(WireServerOptions.defaults().withMaxConcurrentStreams(1));
		try (GraphClient client = connect()) {
			try (RowStream stream = client.query("MATCH (n:Document) RETURN n.body")) {
				assertTrue(stream.hasNext());
				stream.next();
				// Closing mid-stream cancels. The clause that matters is the next one: a permit that was taken and
				// not given back would make every later query fail, and only a second query can see that.
			}
			awaitIdle();
			try (RowStream stream = client.query("MATCH (n:Person) RETURN n.name")) {
				assertTrue(stream.hasNext(), "the slot was not released after a cancel");
				assertEquals("Alice", stream.next().string("n.name"));
			}
		}
		awaitIdle();
	}

	@Test
	public void testTheStreamAfterTheLimitIsRefusedRatherThanQueued() throws Exception {
		WireServerOptions options = WireServerOptions.defaults()
			.withMaxConcurrentStreams(1)
			.withWriteQueueMaxBytes(64 * 1024)
			.withSocketSendBufferBytes(32 * 1024);
		listen(options);

		try (GraphClient holder = GraphClient.connect("127.0.0.1", port, tightClient())) {
			RowStream held = holder.query("MATCH (n:Document) RETURN n.body");
			// Wait until the one permit is genuinely taken and the producer is parked on a full write queue, so that
			// the refusal below is about the limit rather than about timing.
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (server.bufferedBytes() < options.writeQueueMaxBytes() && System.nanoTime() - deadline < 0) {
				sleep(5);
			}
			assertEquals(1, server.runningStreams());

			try (GraphClient second = connect(); RowStream refused = second.query("MATCH (n:Person) RETURN n.name")) {
				QueryFailedException failure = assertThrows(QueryFailedException.class, refused::hasNext);
				assertEquals(WireError.OVERLOADED, failure.error());
				assertTrue(failure.getMessage().contains("retry"), failure.getMessage());
			}

			held.close();
		}
		awaitIdle();
	}

	@Test
	public void testTwoQueriesRunAtOnceOnOneConnection() {
		listen(WireServerOptions.defaults().withMaxConcurrentStreams(2));
		try (GraphClient client = connect();
			RowStream first = client.query("MATCH (n:Document) RETURN n.n ORDER BY n.n");
			RowStream second = client.query("MATCH (n:Person) RETURN n.name")) {

			// Interleaved on purpose: multiplexing that only works when one stream finishes before the other starts
			// is not multiplexing.
			assertTrue(first.hasNext());
			assertTrue(second.hasNext());
			assertEquals("Alice", second.next().string("n.name"));
			long rows = 0;
			while (first.hasNext()) {
				first.next();
				rows++;
			}
			assertEquals(DOCUMENTS, rows);
		}
		awaitIdle();
	}

	@Test
	public void testManyQueriesInARowLeaveNothingBehind() {
		listen();
		try (GraphClient client = connect()) {
			for (int i = 0; i < 50; i++) {
				if (i % 5 == 0) {
					try (RowStream stream = client.query("MATCH (n RETURN n")) {
						assertThrows(QueryFailedException.class, stream::hasNext);
					}
				} else if (i % 5 == 1) {
					// Cancelled after one row.
					try (RowStream stream = client.query("MATCH (n:Document) RETURN n.body")) {
						assertTrue(stream.hasNext());
						stream.next();
					}
				} else {
					try (RowStream stream = client.query("MATCH (n:Person) RETURN n.name")) {
						for (Row row : stream) {
							assertEquals("Alice", row.string("n.name"));
						}
					}
				}
			}
		}
		awaitIdle();
		assertEquals(0, server.runningStreams());
		assertEquals(0, store.snapshotStats().openViews());
	}

	// ---------------------------------------------------------------- helpers

	/** Streams end asynchronously — the terminal frame goes out before the permit is released. */
	private void awaitIdle() {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while ((server.runningStreams() > 0 || store.snapshotStats().openViews() > 0)
			&& System.nanoTime() - deadline < 0) {
			sleep(5);
		}
		assertEquals(0, server.runningStreams(), "a query slot was never released");
		assertEquals(0, store.snapshotStats().openViews(), "a read view was never closed");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static Buffer queryPayload(String query, long deadlineMillis) {
		WireValueCodec.Writer writer = new WireValueCodec.Writer();
		WireValueCodec.encode(writer, query);
		WireValueCodec.encode(writer, java.util.Map.of());
		writer.i64(deadlineMillis);
		return Buffer.buffer(writer.toArray());
	}

	/**
	 * A socket with no client library behind it, for the frames a correct client would never send.
	 *
	 * <p>
	 * Going through {@code GraphClient} for these would test the client's refusal to send them rather than the
	 * server's refusal to accept them.
	 * </p>
	 */
	private static final class RawConnection implements AutoCloseable {

		private final Socket socket;
		final OutputStream out;
		final DataInputStream in;

		RawConnection(int port) throws IOException {
			socket = new Socket();
			socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
			socket.setSoTimeout(5_000);
			out = socket.getOutputStream();
			in = new DataInputStream(socket.getInputStream());
		}

		void write(Buffer frame) throws IOException {
			out.write(frame.getBytes());
			out.flush();
		}

		RawFrame read() throws IOException {
			byte[] header = new byte[Frames.HEADER_BYTES];
			in.readFully(header);
			ByteBuffer view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			int length = view.getInt();
			int type = view.get() & 0xFF;
			byte[] payload = new byte[length];
			in.readFully(payload);
			return new RawFrame(type, payload);
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}

	private record RawFrame(int type, byte[] payload) {

		WireError error() {
			return WireError.fromCode(new WireValueCodec.Reader(payload).i32());
		}

		String message() {
			WireValueCodec.Reader reader = new WireValueCodec.Reader(payload);
			reader.i32();
			return (String) WireValueCodec.decode(reader);
		}
	}
}
