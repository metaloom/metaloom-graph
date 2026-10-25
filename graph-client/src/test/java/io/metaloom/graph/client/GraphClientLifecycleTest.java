package io.metaloom.graph.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.metaloom.graph.protocol.Frames;
import io.metaloom.graph.protocol.WireError;

/**
 * The client's lifecycle against a server that misbehaves on cue.
 *
 * <p>
 * Every test here is about a path where the natural failure is a thread parked for ever rather than a wrong value, so
 * the class carries a {@link Timeout}: a hang is a red test with a stack trace, not a stalled build. See
 * spec/WIRE.md section 5.1 for why that is not belt and braces.
 * </p>
 */
@Tag("unit")
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class GraphClientLifecycleTest {

	private ScriptedServer server;

	@AfterEach
	public void stop() {
		if (server != null) {
			server.close();
		}
	}

	private static List<List<Object>> rows(int count, int width) {
		List<List<Object>> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			rows.add(List.of((long) i, "x".repeat(width)));
		}
		return rows;
	}

	// ---------------------------------------------------------------- a closed stream

	/**
	 * A stream used after {@code close()} answers at once instead of waiting for frames that will never be routed to it.
	 *
	 * <p>
	 * Closing unregisters the stream, so nothing the server sends for it reaches its queue again — not the rows, and
	 * not the terminal frame. A {@code hasNext()} on it used to park in {@code take()} for ever.
	 * </p>
	 */
	@Test
	public void testAClosedStreamDoesNotHangWhenAskedAgain() {
		// Columns and one batch, then nothing until the client cancels.
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("n", "s"));
			peer.rows(id, rows(3, 10));
		});
		try (GraphClient client = server.connect()) {
			RowStream rows = client.query("anything");
			assertTrue(rows.hasNext());
			rows.next();
			rows.close();

			assertThrows(IllegalStateException.class, rows::hasNext);
			assertThrows(IllegalStateException.class, rows::next);
			assertThrows(IllegalStateException.class, rows::summary);
		}
	}

	@Test
	public void testColumnsOfAStreamClosedBeforeTheyArrivedDoNotHang() {
		// Never sends COLUMNS: the query is "still running" when the caller gives up on it.
		server = new ScriptedServer((peer, id, text, parameters) -> {
		});
		try (GraphClient client = server.connect()) {
			RowStream rows = client.query("slow");
			rows.close();
			assertThrows(IllegalStateException.class, rows::columns);
		}
	}

	/** Columns that did arrive stay readable after close: they are already the caller's. */
	@Test
	public void testColumnsThatArrivedStayReadableAfterClose() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("a"));
			peer.complete(id, 0);
		});
		try (GraphClient client = server.connect(); RowStream rows = client.query("q")) {
			assertEquals(List.of("a"), rows.columns());
			rows.close();
			assertEquals(List.of("a"), rows.columns());
		}
	}

	// ---------------------------------------------------------------- the connection budget

	/**
	 * Closing streams while their rows are still arriving gives every byte back to the connection's budget.
	 *
	 * <p>
	 * The event loop looks a stream up, counts the batch, then queues it. A consumer closing the stream between the
	 * lookup and the queueing released what it found in the queue — not the batch that was about to land — and that
	 * batch stayed counted for ever. Each one is up to a batch of budget gone; once the leaked total sits above half of
	 * {@code readBufferBytes}, the next pause has nothing that will ever lift it and the connection hangs.
	 * </p>
	 */
	@Test
	public void testClosingStreamsMidFlightLeaksNoBudget() throws Exception {
		// A stream of batches per query, written as fast as the loop can, so they land while the client is closing.
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("n", "s"));
			for (int i = 0; i < 8; i++) {
				peer.rows(id, rows(4, 2_000));
			}
			peer.complete(id, 32);
		});
		ClientOptions tight = ClientOptions.defaults().withReadBufferBytes(64 * 1024)
			.withSocketReceiveBufferBytes(32 * 1024);
		try (GraphClient client = server.connect(tight)) {
			for (int i = 0; i < 3_000; i++) {
				RowStream rows = client.query("q" + i);
				if (i % 3 == 0) {
					rows.hasNext();
				}
				rows.close();
			}
			// Every stream is closed; whatever was in flight for them has to be dropped or given back. A query that is
			// read to the end then proves the socket still moves.
			try (RowStream last = client.query("last")) {
				int count = 0;
				while (last.hasNext()) {
					last.next();
					count++;
				}
				assertEquals(32, count);
			}
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (client.queuedBytes() != 0 && System.nanoTime() < deadline) {
				Thread.sleep(5);
			}
			assertEquals(0, client.queuedBytes(), "bytes counted against the budget that no stream will give back");
			assertEquals(0, client.streamIdsInUse(), "every stream has had its terminal frame, so no id may stay held");
		}
	}

	/** Rows for a stream nobody opened are dropped without being counted. */
	@Test
	public void testRowsForAnUnknownStreamAreDroppedAndCostNothing() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			// Ten megabytes for a stream that does not exist, then the real answer.
			for (int i = 0; i < 160; i++) {
				peer.rows(9_999, rows(32, 2_000));
			}
			peer.columns(id, List.of("n", "s"));
			peer.rows(id, rows(2, 10));
			peer.complete(id, 2);
		});
		ClientOptions tight = ClientOptions.defaults().withReadBufferBytes(64 * 1024);
		try (GraphClient client = server.connect(tight); RowStream rows = client.query("q")) {
			int count = 0;
			for (Row ignored : rows) {
				count++;
			}
			assertEquals(2, count);
			assertEquals(0, client.queuedBytes());
		}
	}

	/** A stream that is abandoned unread — neither consumed nor closed — holds its bytes, and closing it frees them. */
	@Test
	public void testAnAbandonedStreamHoldsTheBudgetUntilItIsClosed() throws Exception {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("n", "s"));
			for (int i = 0; i < 40; i++) {
				peer.rows(id, rows(8, 2_000));
			}
			peer.complete(id, 320);
		});
		ClientOptions tight = ClientOptions.defaults().withReadBufferBytes(64 * 1024)
			.withSocketReceiveBufferBytes(32 * 1024);
		try (GraphClient client = server.connect(tight)) {
			RowStream abandoned = client.query("abandoned");
			abandoned.columns();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (client.queuedBytes() <= 64 * 1024 && System.nanoTime() < deadline) {
				Thread.sleep(5);
			}
			assertTrue(client.queuedBytes() > 64 * 1024, "the socket pauses past the budget, so it fills to it");
			assertTrue(client.queuedBytes() < 64 * 1024 + 2 * 20_000, "and not much past it: " + client.queuedBytes());

			// The per-connection budget is the documented trade: a second stream waits behind the first...
			abandoned.close();
			// ...and closing the first is what lets the socket move again.
			try (RowStream next = client.query("next")) {
				assertEquals(320, next.summary().rows());
			}
		}
	}

	// ---------------------------------------------------------------- the connection going away

	@Test
	public void testAServerThatDropsTheConnectionMidResultFailsTheStream() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("n", "s"));
			peer.rows(id, rows(5, 10));
			// Flushed, then gone: the rows arrive and the terminal frame never does.
			peer.socket.end();
		});
		try (GraphClient client = server.connect()) {
			RowStream rows = client.query("q");
			int read = 0;
			QueryFailedException thrown = null;
			try {
				while (rows.hasNext()) {
					rows.next();
					read++;
				}
			} catch (QueryFailedException e) {
				thrown = e;
			}
			assertEquals(5, read, "the rows that arrived are handed out");
			assertTrue(thrown != null, "and then the stream fails: a result cut short is not a short result");
			// The failure is not reported a second time by close.
			rows.close();
			// And the connection says it is over rather than accepting a query nobody will answer.
			assertThrows(GraphClientException.class, () -> client.query("after"));
		}
	}

	@Test
	public void testAnErrorAfterRowsIsThrownAtThatRowAndOnlyOnce() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("n", "s"));
			peer.rows(id, rows(2, 10));
			peer.error(id, WireError.TIMEOUT, "budget");
		});
		try (GraphClient client = server.connect()) {
			RowStream rows = client.query("q");
			rows.next();
			rows.next();
			QueryFailedException thrown = assertThrows(QueryFailedException.class, rows::hasNext);
			assertEquals(WireError.TIMEOUT, thrown.error());
			// Asked again, it says so again; closed, it does not say it a third time.
			assertThrows(QueryFailedException.class, rows::hasNext);
			rows.close();
		}
	}

	@Test
	public void testAnErrorNobodyAskedAboutIsReportedByClose() throws Exception {
		server = new ScriptedServer((peer, id, text, parameters) -> peer.error(id, WireError.SYNTAX, "no"));
		try (GraphClient client = server.connect()) {
			RowStream rows = client.query("q");
			// Long enough for the frame to land; close() looks without blocking.
			Thread.sleep(300);
			QueryFailedException thrown = assertThrows(QueryFailedException.class, rows::close);
			assertEquals(WireError.SYNTAX, thrown.error());
			// Once: a second close is a no-op.
			rows.close();
		}
	}

	/**
	 * The server ending the connection with a reason hands that reason to every stream open on it.
	 *
	 * <p>
	 * A user who is disabled while their connection is open gets {@code UNAUTHENTICATED} on stream 0 and the server
	 * closes. A query in flight on that connection used to fail with {@code PROTOCOL} — the code for "your client sent
	 * something that is not this protocol" — which sends somebody to debug their client for an account change.
	 * </p>
	 */
	@Test
	public void testAConnectionLevelErrorKeepsItsCodeOnTheStreamsItEnds() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("n"));
			peer.error(Frames.CONNECTION_STREAM, WireError.UNAUTHENTICATED, "'alice' is no longer an enabled user");
			peer.socket.close();
		});
		try (GraphClient client = server.connect(); RowStream rows = client.query("q")) {
			QueryFailedException thrown = assertThrows(QueryFailedException.class, rows::hasNext);
			assertEquals(WireError.UNAUTHENTICATED, thrown.error(), thrown.getMessage());
			assertTrue(thrown.getMessage().contains("no longer"), thrown.getMessage());
		}
	}

	@Test
	public void testClosingTheClientWakesAConsumerBlockedInAnotherThread() throws Exception {
		server = new ScriptedServer((peer, id, text, parameters) -> peer.columns(id, List.of("n")));
		GraphClient client = server.connect();
		RowStream rows = client.query("never finishes");
		rows.columns();
		AtomicReference<Throwable> seen = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Thread consumer = new Thread(() -> {
			try {
				rows.hasNext();
			} catch (Throwable t) {
				seen.set(t);
			} finally {
				done.countDown();
			}
		});
		consumer.start();
		Thread.sleep(100);
		client.close();
		assertTrue(done.await(10, TimeUnit.SECONDS), "close() must wake a consumer blocked on the connection");
		assertInstanceOf(GraphClientException.class, seen.get());
		assertThrows(GraphClientException.class, () -> client.query("after close"));
	}

	@Test
	public void testAnInterruptedConsumerGetsAnExceptionAndKeepsItsFlag() throws Exception {
		server = new ScriptedServer((peer, id, text, parameters) -> peer.columns(id, List.of("n")));
		try (GraphClient client = server.connect()) {
			RowStream rows = client.query("never finishes");
			AtomicReference<Throwable> seen = new AtomicReference<>();
			AtomicReference<Boolean> flag = new AtomicReference<>();
			Thread consumer = new Thread(() -> {
				try {
					rows.hasNext();
				} catch (Throwable t) {
					seen.set(t);
					flag.set(Thread.currentThread().isInterrupted());
				}
			});
			consumer.start();
			Thread.sleep(100);
			consumer.interrupt();
			consumer.join(10_000);
			assertFalse(consumer.isAlive());
			assertInstanceOf(GraphClientException.class, seen.get());
			assertTrue(flag.get(), "the interrupt is the caller's, and it is kept");
			rows.close();
		}
	}

	// ---------------------------------------------------------------- what the server sends that it should not

	@Test
	public void testARowBatchThatDoesNotDecodeEndsTheStreamLoudly() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("n"));
			// Claims two rows, carries a reserved tag.
			peer.raw(Frames.ROWS, id, new byte[] { 2, 0, 0, 0, 7, 7, 7 });
			peer.complete(id, 2);
		});
		try (GraphClient client = server.connect(); RowStream rows = client.query("q")) {
			assertTrue(rows.hasNext());
			assertThrows(GraphClientException.class, rows::next);
			assertFalse(rows.hasNext(), "nothing after an undecodable batch can be trusted");
		}
	}

	@Test
	public void testATruncatedCompleteFrameFailsTheConnectionRatherThanHangingTheStream() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("n"));
			peer.raw(Frames.COMPLETE, id, new byte[] { 1, 2, 3 });
		});
		try (GraphClient client = server.connect(); RowStream rows = client.query("q")) {
			assertThrows(QueryFailedException.class, rows::hasNext);
		}
	}

	@Test
	public void testAnUnexpectedFrameTypeFailsTheConnection() {
		server = new ScriptedServer((peer, id, text, parameters) -> peer.raw(Frames.QUERY, id, new byte[0]));
		try (GraphClient client = server.connect(); RowStream rows = client.query("q")) {
			QueryFailedException thrown = assertThrows(QueryFailedException.class, rows::columns);
			assertTrue(thrown.getMessage().contains("QUERY"), thrown.getMessage());
		}
	}

	@Test
	public void testAServerThatNeverSaysWelcomeTimesOutTheConnect() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
		}).silent();
		long started = System.nanoTime();
		GraphClientException thrown = assertThrows(GraphClientException.class, () -> server
			.connect(ClientOptions.defaults().withConnectTimeout(Duration.ofMillis(300))));
		assertTrue(thrown.getMessage().contains("did not answer HELLO"), thrown.getMessage());
		assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "bounded by the connect timeout");
	}

	@Test
	public void testNothingListeningIsAFastTypedFailure() throws Exception {
		int port;
		try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
			port = probe.getLocalPort();
		}
		assertThrows(GraphClientException.class, () -> GraphClient.connect("127.0.0.1", port));
	}

	// ---------------------------------------------------------------- a query the client cannot send

	/**
	 * A query that cannot be encoded fails in the caller and costs the connection nothing.
	 *
	 * <p>
	 * The stream id was allocated and the stream registered before the parameters were encoded, so a parameter with no
	 * wire type left a registered stream no server frame would ever end. 65,535 of them and the connection refused
	 * every query with "all stream ids are in use".
	 * </p>
	 */
	@Test
	public void testAQueryThatCannotBeEncodedLeaksNoStreamId() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("ok"));
			peer.complete(id, 0);
		});
		try (GraphClient client = server.connect()) {
			for (int i = 0; i < 0x10000; i++) {
				assertThrows(IllegalArgumentException.class,
					() -> client.query("RETURN $p", Map.of("p", new Object())));
			}
			assertThrows(IllegalArgumentException.class, () -> client.query("RETURN '\uD800'"));
			assertThrows(NullPointerException.class, () -> client.query(null));
			assertEquals(0, client.streamIdsInUse(), "a query that was never sent holds no stream id");
			assertEquals(0, client.execute("RETURN 1").rows());
		}
	}

	@Test
	public void testAQueryOverTheFrameLimitIsRefusedInTheCaller() {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			peer.columns(id, List.of("ok"));
			peer.complete(id, 0);
		});
		try (GraphClient client = server.connect()) {
			String huge = "x".repeat(Frames.MAX_FRAME_BYTES);
			GraphClientException thrown = assertThrows(GraphClientException.class, () -> client.query(huge));
			assertTrue(thrown.getMessage().contains("frame"), thrown.getMessage());
			assertEquals(0, client.execute("RETURN 1").rows(), "and the connection is still usable");
		}
	}

	// ---------------------------------------------------------------- many threads, one client

	@Test
	public void testManyThreadsShareOneClient() throws Exception {
		server = new ScriptedServer((peer, id, text, parameters) -> {
			int n = Integer.parseInt(text);
			peer.columns(id, List.of("n", "s"));
			// Rows that say which query they belong to, so a frame routed to the wrong stream is caught.
			List<List<Object>> rows = new ArrayList<>();
			for (int i = 0; i < n % 50; i++) {
				rows.add(List.of((long) n, "y".repeat(n % 7 * 300)));
			}
			if (!rows.isEmpty()) {
				peer.rows(id, rows.subList(0, rows.size() / 2));
				peer.rows(id, rows.subList(rows.size() / 2, rows.size()));
			}
			if (n % 13 == 0) {
				peer.error(id, WireError.TYPE, "query " + n);
			} else {
				peer.complete(id, rows.size());
			}
		});
		ClientOptions tight = ClientOptions.defaults().withReadBufferBytes(64 * 1024);
		try (GraphClient client = server.connect(tight)) {
			ExecutorService pool = Executors.newFixedThreadPool(16);
			try {
				List<Future<Integer>> results = new ArrayList<>();
				for (int t = 0; t < 16; t++) {
					int thread = t;
					results.add(pool.submit(() -> {
						int checked = 0;
						for (int q = 0; q < 300; q++) {
							int n = thread * 1_000 + q;
							try (RowStream rows = client.query(String.valueOf(n))) {
								int count = 0;
								for (Row row : rows) {
									assertEquals((long) n, row.longValue("n"), "a row from another stream");
									count++;
								}
								assertEquals(n % 50, count);
								assertFalse(n % 13 == 0, "query " + n + " should have failed");
							} catch (QueryFailedException e) {
								assertEquals(0, n % 13, e.getMessage());
								assertEquals("query " + n, e.getMessage());
							}
							checked++;
						}
						return checked;
					}));
				}
				for (Future<Integer> result : results) {
					assertEquals(300, result.get(25, TimeUnit.SECONDS));
				}
			} finally {
				pool.shutdownNow();
			}
			assertEquals(0, client.queuedBytes());
		}
	}
}
