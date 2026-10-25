package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.client.ClientOptions;
import io.metaloom.graph.client.GraphClient;
import io.metaloom.graph.client.GraphClientException;
import io.metaloom.graph.client.QueryFailedException;
import io.metaloom.graph.client.Row;
import io.metaloom.graph.client.RowStream;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.protocol.WireError;

/**
 * The Java client against a real server, on the paths an application actually walks: one connection reused for
 * thousands of queries, many threads sharing a client, parameters of every type round-tripping through the engine,
 * a write cut off by a disconnect, and a database dropped under an open connection.
 *
 * <p>
 * {@link WireServerTest} is about the server's bounds; this is about what a well-behaved caller is promised. Same
 * class-level {@link Timeout} for the same reason: a defect here presents as a parked thread.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class WireClientEndToEndTest {

	private static final int DOCUMENTS = 500;

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
			store.createNode(List.of("Person"), Properties.of().set("name", "Alice"));
			for (int i = 0; i < DOCUMENTS; i++) {
				store.createNode(List.of("Document"), Properties.of().set("n", (long) i).set("body", "b".repeat(1_000)));
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
			assertEquals(0, store.snapshotStats().openViews(), "a read view outlived its query");
			databases.close();
		}
	}

	private void listen(WireServerOptions options, boolean allowWrite) {
		server = new WireServer(databases, GraphDatabases.DEFAULT_NAME, options.withPort(0), allowWrite);
		port = server.listen().toCompletionStage().toCompletableFuture().join();
	}

	private GraphClient connect() {
		return GraphClient.connect("127.0.0.1", port);
	}

	private void awaitIdle() {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while ((server.runningStreams() > 0 || store.snapshotStats().openViews() > 0)
			&& System.nanoTime() - deadline < 0) {
			sleep(5);
		}
		assertEquals(0, server.runningStreams(), "a stream is still running");
		assertEquals(0, store.snapshotStats().openViews(), "a view is still open");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	// ---------------------------------------------------------------- the stream limit, as a client sees it

	/**
	 * A client that never has more streams open than the server said it may is never refused for having too many.
	 *
	 * <p>
	 * The terminal frame went out from the producer thread and the permit came back later, in the completion callback
	 * on the event loop. A client that saw {@code COMPLETE} and sent its next query at once could reach the server
	 * between the two and be answered {@code OVERLOADED} with one stream open against a limit of one. The existing
	 * tests waited for the server to go idle between queries, which no real client can do.
	 * </p>
	 */
	@Test
	public void testBackToBackQueriesAtTheStreamLimitAreNeverRefused() throws Exception {
		listen(WireServerOptions.defaults().withMaxConcurrentStreams(2), false);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try (GraphClient client = connect()) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int t = 0; t < 2; t++) {
				results.add(pool.submit(() -> {
					int refused = 0;
					for (int i = 0; i < 2_000; i++) {
						try {
							client.execute("MATCH (n:Person) RETURN n.name");
						} catch (QueryFailedException e) {
							if (e.error() != WireError.OVERLOADED) {
								throw e;
							}
							refused++;
						}
					}
					return refused;
				}));
			}
			int refused = 0;
			for (Future<Integer> result : results) {
				refused += result.get(50, TimeUnit.SECONDS);
			}
			assertEquals(0, refused, "queries refused as OVERLOADED with no more streams open than the server allows");
		} finally {
			pool.shutdownNow();
		}
		awaitIdle();
	}

	/**
	 * A stream id may be used again the moment its terminal frame has been read, and the server agrees.
	 *
	 * <p>
	 * The Java client cycles through all 65,535 ids, so it almost never reuses one quickly; the protocol allows any
	 * client to. The server freed the id just after queueing the terminal frame, and a client that read the frame and
	 * reused the id at once could reach the event loop first: "already running a query", a protocol error, and the
	 * connection closed under a client that had done nothing wrong.
	 * </p>
	 */
	@Test
	public void testAStreamIdReusedTheMomentItEndsIsAccepted() throws Exception {
		listen(WireServerOptions.defaults(), false);
		try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
			socket.setTcpNoDelay(true);
			java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
				socket.getInputStream()));
			java.io.OutputStream out = socket.getOutputStream();

			io.metaloom.graph.protocol.WireValueCodec.Writer hello = new io.metaloom.graph.protocol.WireValueCodec.Writer();
			hello.i32(io.metaloom.graph.protocol.Protocol.VERSION);
			io.metaloom.graph.protocol.WireValueCodec.encode(hello, "");
			out.write(io.metaloom.graph.protocol.Frames.frame(io.metaloom.graph.protocol.Frames.HELLO, 0, hello)
				.getBytes());
			assertEquals(io.metaloom.graph.protocol.Frames.WELCOME, readFrame(in)[0]);

			io.metaloom.graph.protocol.WireValueCodec.Writer query = new io.metaloom.graph.protocol.WireValueCodec.Writer();
			io.metaloom.graph.protocol.WireValueCodec.encode(query, "MATCH (n:Person) RETURN n.name");
			io.metaloom.graph.protocol.WireValueCodec.encode(query, Map.of());
			query.i64(0);
			byte[] frame = io.metaloom.graph.protocol.Frames.frame(io.metaloom.graph.protocol.Frames.QUERY, 1, query)
				.getBytes();
			for (int i = 0; i < 3_000; i++) {
				out.write(frame);
				int type;
				do {
					int[] header = readFrame(in);
					type = header[0];
					if (type == io.metaloom.graph.protocol.Frames.ERROR) {
						throw new AssertionError("query " + i + " on a just-freed id was refused, stream " + header[1]);
					}
				} while (type != io.metaloom.graph.protocol.Frames.COMPLETE);
			}
		}
		awaitIdle();
	}

	/** One frame off a blocking socket: {type, streamId}; the payload is read and dropped. */
	private static int[] readFrame(java.io.DataInputStream in) throws java.io.IOException {
		byte[] header = new byte[io.metaloom.graph.protocol.Frames.HEADER_BYTES];
		in.readFully(header);
		java.nio.ByteBuffer view = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		int length = view.getInt(0);
		int type = header[4] & 0xFF;
		int stream = view.getShort(6) & 0xFFFF;
		in.readFully(new byte[length]);
		return new int[] { type, stream };
	}

	/** The same promise for a writer: a write that finished has given its permit and its writer slot back. */
	@Test
	public void testBackToBackWritesAreNeverRefused() {
		listen(WireServerOptions.defaults().withMaxConcurrentStreams(1), true);
		try (GraphClient client = connect()) {
			for (int i = 0; i < 500; i++) {
				assertEquals(1, client.execute("CREATE (:Tick {i: $i})", Map.of("i", (long) i)).nodesCreated());
			}
		}
		assertEquals(500, store.nodesByLabel("Tick").size());
		awaitIdle();
	}

	// ---------------------------------------------------------------- sharing a client

	@Test
	public void testManyThreadsShareOneClient() throws Exception {
		// Headroom over the thread count: a stream closed mid-result holds its server slot until the cancel lands, which
		// the client does not wait for. Being refused for that is the documented trade, and not what this is about.
		listen(WireServerOptions.defaults().withWriteQueueMaxBytes(512 * 1024).withMaxConcurrentStreams(64), false);
		ExecutorService pool = Executors.newFixedThreadPool(12);
		try (GraphClient client = GraphClient.connect("127.0.0.1", port,
			ClientOptions.defaults().withReadBufferBytes(256 * 1024))) {
			List<Future<?>> results = new ArrayList<>();
			for (int t = 0; t < 12; t++) {
				int thread = t;
				results.add(pool.submit(() -> {
					for (int i = 0; i < 60; i++) {
						switch ((thread + i) % 4) {
							case 0 -> {
								long n = (thread * 31L + i) % DOCUMENTS;
								try (RowStream rows = client.query("MATCH (d:Document) WHERE d.n = $n RETURN d.n AS n",
									Map.of("n", n))) {
									Row row = rows.next();
									assertEquals(n, row.longValue("n"), "a row from another stream");
									assertFalse(rows.hasNext());
								}
							}
							case 1 -> {
								// Read part of a large result, then close it mid-stream.
								try (RowStream rows = client.query("MATCH (d:Document) RETURN d.body")) {
									for (int r = 0; r < 3 && rows.hasNext(); r++) {
										rows.next();
									}
								}
							}
							case 2 -> {
								try (RowStream rows = client.query("MATCH (d:Document) RETURN count(d) AS c")) {
									assertEquals((long) DOCUMENTS, rows.next().longValue("c"));
								}
							}
							default -> {
								QueryFailedException failure = assertThrows(QueryFailedException.class,
									() -> client.execute("MATCH (n RETURN n"));
								assertEquals(WireError.SYNTAX, failure.error());
							}
						}
					}
					return null;
				}));
			}
			for (Future<?> result : results) {
				result.get(50, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
		awaitIdle();
	}

	/** A stream neither read nor closed is released by closing the client, and costs the server nothing after. */
	@Test
	public void testAnAbandonedStreamIsReleasedWhenTheClientCloses() {
		listen(WireServerOptions.defaults()
			.withWriteQueueMaxBytes(64 * 1024)
			.withSocketSendBufferBytes(32 * 1024), false);
		GraphClient client = GraphClient.connect("127.0.0.1", port, ClientOptions.defaults()
			.withReadBufferBytes(64 * 1024).withSocketReceiveBufferBytes(32 * 1024));
		RowStream abandoned = client.query("MATCH (d:Document) RETURN d.body");
		assertTrue(abandoned.hasNext());
		// Neither drained nor closed; the server's producer is parked on a full socket.
		client.close();
		awaitIdle();
		// What had already arrived may still be read; the end of it is a failure, not the end of a result.
		assertThrows(GraphClientException.class, () -> {
			while (abandoned.hasNext()) {
				abandoned.next();
			}
		});
	}

	// ---------------------------------------------------------------- values through the engine

	/**
	 * Every value a client can send comes back from {@code RETURN $p} as the value that was sent.
	 *
	 * <p>
	 * Through the engine and back, so this covers the parameter normalisation on the way in and the conversion at the
	 * server's edge on the way out, not only the codec. Integers come back as Long and floats as Double, which is the
	 * documented normalisation and the only difference allowed.
	 * </p>
	 */
	@Test
	public void testParametersOfEveryTypeComeBackUnchanged() {
		listen(WireServerOptions.defaults(), false);
		Random random = new Random(20261024L);
		try (GraphClient client = connect()) {
			for (int i = 0; i < 300; i++) {
				Object value = randomParameter(random, 0);
				Map<String, Object> parameters = new java.util.HashMap<>();
				parameters.put("p", value);
				try (RowStream rows = client.query("RETURN $p AS p", parameters)) {
					Object back = rows.next().get("p");
					assertEquals(normalised(value), back, "value " + i + ": " + value);
				}
			}
		}
		awaitIdle();
	}

	private static Object randomParameter(Random random, int depth) {
		return switch (random.nextInt(depth > 3 ? 6 : 8)) {
			case 0 -> null;
			case 1 -> random.nextBoolean();
			case 2 -> switch (random.nextInt(4)) {
				case 0 -> Long.MAX_VALUE;
				case 1 -> Long.MIN_VALUE;
				case 2 -> 0L;
				default -> random.nextLong() >> random.nextInt(64);
			};
			case 3 -> switch (random.nextInt(4)) {
				case 0 -> Double.MAX_VALUE;
				case 1 -> -0.0;
				case 2 -> Double.MIN_VALUE;
				default -> random.nextGaussian() * 1e6;
			};
			case 4 -> random.nextInt();
			case 5 -> {
				StringBuilder text = new StringBuilder();
				for (int c = random.nextInt(random.nextInt(10) == 0 ? 2_000 : 20); c > 0; c--) {
					text.appendCodePoint(random.nextInt(3) == 0 ? 0x1F600 + random.nextInt(50) : 0x20 + random.nextInt(0x2000));
				}
				yield text.toString();
			}
			case 6 -> {
				List<Object> list = new ArrayList<>();
				for (int n = random.nextInt(5); n > 0; n--) {
					list.add(randomParameter(random, depth + 1));
				}
				yield list;
			}
			default -> {
				Map<String, Object> map = new LinkedHashMap<>();
				for (int n = random.nextInt(4); n > 0; n--) {
					map.put("k" + random.nextInt(1_000), randomParameter(random, depth + 1));
				}
				yield map;
			}
		};
	}

	private static Object normalised(Object value) {
		return switch (value) {
			case null -> null;
			case Integer i -> i.longValue();
			case List<?> list -> {
				List<Object> out = new ArrayList<>();
				for (Object element : list) {
					out.add(normalised(element));
				}
				yield out;
			}
			case Map<?, ?> map -> {
				Map<String, Object> out = new LinkedHashMap<>();
				map.forEach((k, v) -> out.put(String.valueOf(k), normalised(v)));
				yield out;
			}
			default -> value;
		};
	}

	/**
	 * A string the wire cannot carry is an error, not a different string.
	 *
	 * <p>
	 * A Cypher {@code \\u} escape could make a lone surrogate, and the wire encoder used to send it as {@code '?'}: the
	 * embedded API and HTTP answered one thing and the binary port another, with nothing to say so. The lexer now
	 * refuses such a literal before it runs, so over the wire it is a syntax error; the encoder's own refusal, for a
	 * string that reaches it some other way, is covered by {@code WireCodecPropertyTest}.
	 * </p>
	 */
	@Test
	public void testALoneSurrogateInAResultIsRefusedNotTurnedIntoAQuestionMark() {
		listen(WireServerOptions.defaults(), false);
		try (GraphClient client = connect()) {
			QueryFailedException failure = assertThrows(QueryFailedException.class,
				() -> client.query("RETURN '\\uD800' AS s").next());
			assertEquals(WireError.SYNTAX, failure.error(), failure.getMessage());
			assertTrue(failure.getMessage().contains("surrogate"), failure.getMessage());

			// A real pair is a real character, and the connection is still fine.
			try (RowStream rows = client.query("RETURN '\\uD83D\\uDE00' AS s")) {
				assertEquals("😀", rows.next().string("s"));
			}
		}
		awaitIdle();
	}

	// ---------------------------------------------------------------- writes and disconnects

	/**
	 * A write whose client disconnects mid-flight is all or nothing, and leaves the writer free.
	 *
	 * <p>
	 * There is no BEGIN on this wire: every query is its own transaction, and a write commits before its first byte
	 * is sent. So the only question a disconnect can raise is whether the transaction it interrupted is whole, and
	 * whether the lock it held came back.
	 * </p>
	 */
	@Test
	public void testAWriteCutOffByADisconnectIsAllOrNothing() throws Exception {
		listen(WireServerOptions.defaults(), true);
		for (int attempt = 0; attempt < 6; attempt++) {
			String label = "Bulk" + attempt;
			GraphClient client = connect();
			client.query("UNWIND range(1, 5000) AS i CREATE (:" + label + " {i: i})");
			sleep(attempt * 3L);
			client.close();
			awaitIdle();
			int created = store.nodesByLabel(label).size();
			assertTrue(created == 0 || created == 5000, "attempt " + attempt + " left " + created + " of 5000 nodes");
			// The writer lock came back.
			try (Transaction txn = store.begin()) {
				txn.rollback();
			}
		}
		// And the port still writes.
		try (GraphClient client = connect()) {
			assertEquals(1, client.execute("CREATE (:After)").nodesCreated());
		}
	}

	/** A write that fails part way commits nothing, and the error says which kind of failure it was. */
	@Test
	public void testAFailedWriteInsideOneQueryRollsBackEverything() throws Exception {
		listen(WireServerOptions.defaults(), true);
		store.createUniqueConstraint("k");
		try (GraphClient client = connect()) {
			QueryFailedException failure = assertThrows(QueryFailedException.class,
				() -> client.execute("UNWIND [1, 2, 3, 2] AS i CREATE (:Partial {k: i})"));
			assertEquals(WireError.CONSTRAINT_VIOLATION, failure.error(), failure.getMessage());
			assertEquals(0, store.nodesByLabel("Partial").size(), "nodes created before the violation must go with it");
			assertEquals(3, client.execute("UNWIND [1, 2, 3] AS i CREATE (:Partial {k: i})").nodesCreated());
		}
		awaitIdle();
	}

	// ---------------------------------------------------------------- shutdown

	/**
	 * A query in flight when shutdown begins is given its grace period and its answer.
	 *
	 * <p>
	 * {@code shutdown} promises to stop accepting work and let what is running finish. Closing the listener must
	 * therefore not close the connections the running queries will answer on: a client whose query finished inside the
	 * grace period is owed the rows, not a dropped connection.
	 * </p>
	 */
	@Test
	public void testAQueryInFlightAtShutdownFinishesWithinTheGracePeriod() throws Exception {
		listen(WireServerOptions.defaults().withQueryTimeoutMillis(0), false);
		String slow = "MATCH (a:Document), (b:Document) WHERE a.n + b.n >= 0 RETURN count(*) AS c";
		try (GraphClient client = connect()) {
			long started = System.nanoTime();
			assertEquals((long) DOCUMENTS * DOCUMENTS, client.query(slow).next().longValue("c"));
			long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			assertTrue(millis >= 50, "the fixture query must take long enough to be caught in flight: " + millis + " ms");

			ExecutorService pool = Executors.newSingleThreadExecutor();
			try {
				Future<Long> inFlight = pool.submit(() -> client.query(slow).next().longValue("c"));
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (server.runningStreams() == 0 && System.nanoTime() < deadline) {
					Thread.sleep(1);
				}
				assertEquals(1, server.runningStreams(), "the query is running when shutdown begins");
				server.shutdown(30_000);
				assertEquals((long) DOCUMENTS * DOCUMENTS, inFlight.get(30, TimeUnit.SECONDS),
					"a query that finished inside the grace period gets its answer");
			} finally {
				pool.shutdownNow();
			}
		}
	}

	// ---------------------------------------------------------------- databases changing under a connection

	/**
	 * A database dropped while a connection is bound to it ends that connection's queries with a typed error.
	 *
	 * <p>
	 * The connection holds the store it resolved at the handshake. After a drop that store is closed, and a query on
	 * the connection must fail as a store error — not hang, not take the server with it — and other databases keep
	 * serving.
	 * </p>
	 */
	@Test
	public void testADatabaseDroppedUnderAnOpenConnection() throws Exception {
		GraphStore sales = databases.create("sales");
		try (Transaction txn = sales.begin()) {
			for (int i = 0; i < 2_000; i++) {
				sales.createNode("Order", Properties.of().set("n", (long) i).set("body", "o".repeat(500)));
			}
			txn.commit();
		}
		listen(WireServerOptions.defaults(), false);
		try (GraphClient onSales = GraphClient.connect("127.0.0.1", port, "sales");
			GraphClient onDefault = connect()) {
			assertEquals(2_000L, onSales.query("MATCH (o:Order) RETURN count(o) AS c").next().longValue("c"));

			// A stream in flight while the drop happens: it either finishes or fails, and does not hang.
			RowStream inFlight = onSales.query("MATCH (o:Order) RETURN o.body");
			inFlight.next();
			assertTrue(databases.drop("sales"));
			try {
				while (inFlight.hasNext()) {
					inFlight.next();
				}
			} catch (QueryFailedException acceptable) {
				// Abandoned by the store going away; what matters is that it ended.
			}
			inFlight.close();

			QueryFailedException after = assertThrows(QueryFailedException.class,
				() -> onSales.execute("MATCH (o:Order) RETURN count(o)"));
			assertEquals(WireError.STORE, after.error(), after.getMessage());
			assertEquals(1L, onDefault.query("MATCH (p:Person) RETURN count(p) AS c").next().longValue("c"));
		}
		awaitIdle();
	}
}
