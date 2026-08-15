package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.metaloom.graph.client.QueryFailedException;
import io.metaloom.graph.client.QueryOptions;
import io.metaloom.graph.client.Row;
import io.metaloom.graph.client.RowStream;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.protocol.Frames;
import io.metaloom.graph.protocol.Protocol;
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

	private GraphStore store;
	private WireServer server;
	private int port;

	@BeforeEach
	public void start() throws Exception {
		store = stores.open();
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
		if (store != null) {
			// Every stream opens a read view and every view must be closed by the thread that opened it. A view left
			// open holds back reclamation for as long as it lives, so this is the assertion that matters most.
			assertEquals(0, store.snapshotStats().openViews(), "a read view outlived its query");
			store.close();
		}
	}

	private void listen(WireServerOptions options) {
		server = new WireServer(store, options.withPort(0));
		port = server.listen().toCompletionStage().toCompletableFuture().join();
	}

	private void listen() {
		listen(WireServerOptions.defaults());
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
	public void testAnIncompatibleVersionGetsAnErrorRatherThanAHang() throws Exception {
		listen();
		try (RawConnection raw = new RawConnection(port)) {
			WireValueCodec.Writer hello = new WireValueCodec.Writer(8);
			hello.i32(Protocol.VERSION + 99);
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
