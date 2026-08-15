package io.metaloom.graph.server.wire;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.QueryAbortedException;
import io.metaloom.graph.cypher.RowSink;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;
import io.metaloom.graph.protocol.FrameReader;
import io.metaloom.graph.protocol.Frames;
import io.metaloom.graph.protocol.Protocol;
import io.metaloom.graph.protocol.WireError;
import io.metaloom.graph.protocol.WireFormatException;
import io.metaloom.graph.protocol.WireValueCodec;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;

/**
 * The binary port: a read-only Cypher result, streamed as it is produced.
 *
 * <pre>
 * java -jar graph-server.jar /path/to/store 8080          # HTTP on 8080, binary on 8081
 * java -jar graph-server.jar --no-wire /path/to/store     # HTTP only
 * </pre>
 *
 * <h2>What this bounds, and what it does not</h2>
 *
 * <p>
 * Rows are encoded and flushed as the engine produces them, so the <b>encoded payload</b> is never built in full and a
 * client never waits for the last row to see the first. It does <b>not</b> yet bound what the executor holds: the query
 * engine is clause-at-a-time over a list, so a query's peak server memory is still its widest intermediate result. That
 * is a property of the engine rather than of the transport, and fixing it is separate work — see spec/CYPHER.md.
 * {@code ORDER BY}, {@code DISTINCT}, aggregation and {@code UNION} buffer their whole input whatever the protocol
 * does, and always will; that is semantics.
 * </p>
 *
 * <h2>One query, one thread, one view</h2>
 *
 * <p>
 * A query is dispatched to a platform thread that opens the read view, produces rows, encodes them, and hands
 * <em>encoded bytes</em> to the socket. It never hands a snapshot or a store read to another thread, because read views
 * are thread-confined and a foreign thread does not get an error — it silently reads current committed data outside the
 * snapshot, producing a result that mixes two points in time with no exception anywhere.
 * </p>
 *
 * <p>
 * Not virtual threads: a page fault on a mapped segment blocks the carrier, and the store is built on mapped segments.
 * </p>
 *
 * <h2>Four bounds on a slow client, each sufficient alone</h2>
 *
 * <p>
 * The failure being prevented is worth naming, because it is not "the server runs out of memory". A slow client pins a
 * worker thread <em>and</em> a read view; the open view holds back reclamation, so retention grows with write volume
 * times duration; that blocks {@code reclaim()} and {@code compact()} entirely; and past its retention budget the store
 * abandons <b>the oldest</b> view, which need not be the slow client's. One misbehaving reader breaks a well-behaved
 * one.
 * </p>
 *
 * <ol>
 * <li>Bounded concurrency — {@link WireServerOptions#maxConcurrentStreams}, refused rather than queued.
 * <li>A hard wall-clock deadline no client behaviour can extend, which is also the bound on view age.
 * <li>No unbounded park anywhere in the producer: every wait for a writable socket is against the deadline.
 * <li>A retention watchdog that abandons the oldest stream before the <em>store</em> has to, so the client gets a typed
 * error naming the reason rather than a {@code SnapshotTooOldException} surfacing somewhere else.
 * </ol>
 *
 * <h2>Cancellation never closes a view from another thread</h2>
 *
 * <p>
 * {@code Snapshot.close()} does not check its owner, so closing it from the cancelling thread would leave the producer
 * holding a closed snapshot and break that thread's <em>next</em> query. A cancel sets a flag the producer polls; the
 * view is only ever closed by the thread that opened it, on its way out of {@link Cypher#stream}.
 * </p>
 *
 * <h2>No authentication, no transport security</h2>
 *
 * <p>
 * There is neither, which is why {@link WireServerOptions#DEFAULT_HOST} is loopback. See spec/OPERATIONS.md.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
public final class WireServer implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(WireServer.class);

	/** How long the producer parks before asking again whether the socket has drained. */
	private static final long PARK_NANOS = TimeUnit.MICROSECONDS.toNanos(50);

	private final Vertx vertx;
	private final boolean ownsVertx;
	private final GraphStore store;
	private final WireServerOptions options;
	private final WorkerExecutor workers;
	private final Semaphore permits;
	private final Map<Long, Stream> running = new ConcurrentHashMap<>();
	private final AtomicLong nextStreamKey = new AtomicLong();
	private final ScheduledExecutorService watchdog;

	private NetServer server;

	public WireServer(GraphStore store) {
		this(Vertx.vertx(), true, store, WireServerOptions.defaults());
	}

	public WireServer(GraphStore store, WireServerOptions options) {
		this(Vertx.vertx(), true, store, options);
	}

	/** For a caller that already runs a Vert.x instance — the HTTP server, for one. */
	public WireServer(Vertx vertx, GraphStore store, WireServerOptions options) {
		this(vertx, false, store, options);
	}

	private WireServer(Vertx vertx, boolean ownsVertx, GraphStore store, WireServerOptions options) {
		this.vertx = vertx;
		this.ownsVertx = ownsVertx;
		this.store = store;
		this.options = options;
		this.permits = new Semaphore(options.maxConcurrentStreams());
		// A pool of its own, sized to the permit count so an admitted query never waits for a thread — and, more to
		// the point, so a long scan cannot starve /stats and /schema, which share the default worker pool.
		this.workers = vertx.createSharedWorkerExecutor("graph-wire", options.maxConcurrentStreams(),
			options.queryTimeoutMillis() > 0 ? options.queryTimeoutMillis() + 1_000 : TimeUnit.HOURS.toMillis(1),
			TimeUnit.MILLISECONDS);
		this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "graph-wire-watchdog");
			thread.setDaemon(true);
			return thread;
		});
	}

	public Future<Integer> listen() {
		// SO_SNDBUF explicitly, because Linux autotunes a loopback socket's buffers into the megabytes under a fast
		// writer — enough for a four-megabyte result to go out in full against a client that was reading nothing,
		// with the write queue never once looking full. See WireServerOptions.
		server = vertx.createNetServer(new NetServerOptions()
			.setSendBufferSize(options.socketSendBufferBytes())
			.setTcpNoDelay(true));
		server.connectHandler(socket -> {
			socket.setWriteQueueMaxSize(options.writeQueueMaxBytes());
			new Connection(socket).attach();
		});
		if (options.retentionSoftLimitBytes() > 0) {
			// On a thread of its own rather than a Vert.x timer: it reads store statistics, and "this call is
			// certainly cheap enough for an event loop" is exactly the assumption that ages badly.
			watchdog.scheduleWithFixedDelay(this::checkRetention, options.watchdogIntervalMillis(),
				options.watchdogIntervalMillis(), TimeUnit.MILLISECONDS);
		}
		return server.listen(options.port(), options.host()).map(bound -> {
			logger.info("Binary port on {}:{}, up to {} concurrent streams x ({} write queue + {} send buffer)"
				+ " = {} bytes held for clients that stop reading",
				options.host(), bound.actualPort(), options.maxConcurrentStreams(), options.writeQueueMaxBytes(),
				options.socketSendBufferBytes(), options.maxBufferedBytes());
			return bound.actualPort();
		});
	}

	public int port() {
		return server.actualPort();
	}

	public WireServerOptions options() {
		return options;
	}

	/** Queries running right now. Zero after every test, which is how a leaked permit is caught. */
	public int runningStreams() {
		return running.size();
	}

	/** Bytes handed to sockets and not yet flushed, across every stream. Never above {@link WireServerOptions#maxBufferedBytes}. */
	public long bufferedBytes() {
		long total = 0;
		for (Stream stream : running.values()) {
			total += stream.buffered.get();
		}
		return total;
	}

	@Override
	public void close() {
		watchdog.shutdownNow();
		if (server != null) {
			server.close().toCompletionStage().toCompletableFuture().join();
		}
		workers.close();
		if (ownsVertx) {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}
	}

	/**
	 * Abandons the oldest running stream when the store is retaining too much on behalf of open views.
	 *
	 * <p>
	 * The oldest rather than the largest, because retention is held by the <em>earliest</em> open view: a younger
	 * reader's snapshot is reconstructible from less. It is also which one the store itself would abandon, so acting
	 * first changes who reports the problem, not who suffers it.
	 * </p>
	 */
	private void checkRetention() {
		try {
			long retained = store.snapshotStats().retainedBytes();
			if (retained < options.retentionSoftLimitBytes()) {
				return;
			}
			Stream oldest = null;
			for (Stream stream : running.values()) {
				if (oldest == null || stream.startedNanos - oldest.startedNanos < 0) {
					oldest = stream;
				}
			}
			if (oldest != null) {
				logger.warn("Retention is at {} bytes, over the {} byte limit: abandoning the oldest stream, open {} ms",
					retained, options.retentionSoftLimitBytes(),
					TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - oldest.startedNanos));
				oldest.abortReason = "the server abandoned this query: it was the oldest open read view and the store"
					+ " was retaining " + retained + " bytes to keep it readable";
				oldest.abortCode = WireError.OVERLOADED;
			}
		} catch (RuntimeException e) {
			logger.warn("The retention watchdog failed; streams are still bounded by their deadlines", e);
		}
	}

	// ---------------------------------------------------------------- one connection

	/**
	 * One socket, carrying many streams.
	 *
	 * <p>
	 * Everything here runs on the connection's event loop, including parsing. Parsing is pure and cheap and running is
	 * neither, so a syntax error — much the most common failure — costs no worker thread and no read view.
	 * </p>
	 */
	private final class Connection {

		private final NetSocket socket;
		private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
		private volatile boolean open = true;
		private boolean greeted;

		Connection(NetSocket socket) {
			this.socket = socket;
		}

		void attach() {
			socket.closeHandler(v -> open = false);
			socket.exceptionHandler(t -> {
				open = false;
				socket.close();
			});
			FrameReader.attach(socket, this::frame, malformed -> {
				// One connection's mistake ends one connection. Everybody else keeps being served.
				logger.debug("Closing a connection that sent a malformed frame: {}", malformed.getMessage());
				fail(Frames.CONNECTION_STREAM, WireError.PROTOCOL, malformed.getMessage(), 0, 0);
				open = false;
				socket.close();
			});
		}

		private void frame(int type, int streamId, Buffer payload) {
			try {
				if (!greeted && type != Frames.HELLO) {
					throw new WireFormatException("Expected HELLO, got " + Frames.typeName(type));
				}
				switch (type) {
					case Frames.HELLO -> hello(payload);
					case Frames.QUERY -> query(streamId, payload);
					case Frames.CANCEL -> cancel(streamId);
					default -> throw new WireFormatException(
						"A client may not send " + Frames.typeName(type));
				}
			} catch (WireFormatException e) {
				fail(Frames.CONNECTION_STREAM, WireError.PROTOCOL, e.getMessage(), 0, 0);
				open = false;
				socket.close();
			}
		}

		private void hello(Buffer payload) {
			int version = new WireValueCodec.Reader(payload.getBytes()).i32();
			if (version != Protocol.VERSION) {
				fail(Frames.CONNECTION_STREAM, WireError.UNSUPPORTED_VERSION,
					"This server speaks protocol version " + Protocol.VERSION + " and the client asked for "
						+ version,
					0, 0);
				open = false;
				socket.close();
				return;
			}
			greeted = true;
			WireValueCodec.Writer welcome = new WireValueCodec.Writer(16);
			welcome.i32(Protocol.VERSION);
			welcome.i32(Frames.MAX_FRAME_BYTES);
			welcome.i32(options.maxConcurrentStreams());
			socket.write(Frames.frame(Frames.WELCOME, Frames.CONNECTION_STREAM, welcome));
		}

		private void query(int streamId, Buffer payload) {
			if (streamId == Frames.CONNECTION_STREAM) {
				throw new WireFormatException("A query needs a stream id above zero");
			}
			if (streams.containsKey(streamId)) {
				throw new WireFormatException("Stream " + streamId + " is already running a query");
			}

			WireValueCodec.Reader reader = new WireValueCodec.Reader(payload.getBytes());
			String text = WireValueCodec.expect(WireValueCodec.decode(reader), String.class, "the query text");
			Map<String, Object> parameters = parameters(WireValueCodec.decode(reader));
			long requestedDeadline = reader.i64();

			Ast.Query parsed;
			try {
				parsed = Cypher.parse(text);
			} catch (CypherSyntaxException e) {
				fail(streamId, WireError.SYNTAX, e.getMessage(), e.line(), e.column());
				return;
			} catch (RuntimeException e) {
				fail(streamId, WireError.INTERNAL, String.valueOf(e.getMessage()), 0, 0);
				return;
			}

			// Fail fast rather than queue. A client waiting on a queued query cannot tell a busy server from a stuck
			// one, and the whole point of the bound is that the server stays predictable when it is at its limit.
			if (!permits.tryAcquire()) {
				fail(streamId, WireError.OVERLOADED,
					"All " + options.maxConcurrentStreams() + " query slots are in use; retry shortly", 0, 0);
				return;
			}

			Stream stream = new Stream(this, streamId, deadlineNanos(requestedDeadline), requestedDeadline);
			streams.put(streamId, stream);
			running.put(stream.key, stream);
			workers.executeBlocking(() -> {
				stream.run(parsed, parameters);
				return null;
			}, false).onComplete(outcome -> {
				if (outcome.failed()) {
					// run() catches every RuntimeException, so reaching here means an Error. Send a terminal frame
					// anyway: the CAS makes it a no-op if one already went, and without it the client waits forever
					// for a stream that has stopped existing.
					stream.error(WireError.INTERNAL, "The server failed while producing this result", 0, 0);
				}
				running.remove(stream.key);
				// Two-argument remove: by now the id may already belong to the client's next query.
				streams.remove(streamId, stream);
				permits.release();
			});
		}

		private void cancel(int streamId) {
			Stream stream = streams.get(streamId);
			if (stream != null) {
				// A flag, not a close. The view belongs to the producer thread and only it may close one.
				stream.cancelled = true;
			}
		}

		void write(Buffer frame) {
			if (open) {
				socket.write(frame);
			}
		}

		boolean writeQueueFull() {
			return open && socket.writeQueueFull();
		}

		void fail(int streamId, WireError error, String message, int line, int column) {
			WireValueCodec.Writer payload = new WireValueCodec.Writer(128);
			payload.i32(error.code());
			WireValueCodec.encodeString(payload, message == null ? error.name() : message);
			payload.i32(line);
			payload.i32(column);
			write(Frames.frame(Frames.ERROR, streamId, payload));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parameters(Object decoded) {
		if (decoded == null) {
			return Map.of();
		}
		return WireValueCodec.expect(decoded, Map.class, "the parameter map");
	}

	/**
	 * The deadline this query actually gets.
	 *
	 * <p>
	 * A client may ask for less than the server's limit and never for more. Otherwise the server's bound on how long a
	 * read view stays open would be whatever the most optimistic client asked for, which is not a bound.
	 * </p>
	 */
	private long deadlineNanos(long requestedMillis) {
		long serverMillis = options.queryTimeoutMillis();
		long effective;
		if (serverMillis <= 0) {
			effective = requestedMillis;
		} else if (requestedMillis <= 0) {
			effective = serverMillis;
		} else {
			effective = Math.min(serverMillis, requestedMillis);
		}
		return effective <= 0 ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effective);
	}

	// ---------------------------------------------------------------- one query

	/** One query, from admission to its single terminal frame. */
	private final class Stream {

		private final long key = nextStreamKey.incrementAndGet();
		private final Connection connection;
		private final int streamId;
		private final long deadlineNanos;
		private final long budgetMillis;
		private final long startedNanos = System.nanoTime();
		private final AtomicLong buffered = new AtomicLong();
		/** The deadline timer, the cancel handler and the producer's error path all race to end a stream. */
		private final AtomicBoolean terminated = new AtomicBoolean();

		private volatile boolean cancelled;
		private volatile String abortReason;
		private volatile WireError abortCode = WireError.TIMEOUT;

		Stream(Connection connection, int streamId, long deadlineNanos, long budgetMillis) {
			this.connection = connection;
			this.streamId = streamId;
			this.deadlineNanos = deadlineNanos;
			this.budgetMillis = budgetMillis;
		}

		void run(Ast.Query parsed, Map<String, Object> parameters) {
			BatchingSink sink = new BatchingSink(this);
			try {
				Cypher.stream(store, parsed, parameters, sink, this::abortReason);
				sink.flush();
				complete(sink.rowsSent);
			} catch (QueryAbortedException e) {
				// Not a failure of the query and not a failure of the store: the answer was never wrong, it was
				// abandoned. Which of the three bounds fired decides the code.
				error(cancelled ? WireError.CANCELLED : abortCode, e.getMessage(), 0, 0);
			} catch (CypherSyntaxException e) {
				error(WireError.SYNTAX, e.getMessage(), e.line(), e.column());
			} catch (CypherTypeException e) {
				error(WireError.TYPE, e.getMessage(), 0, 0);
			} catch (GraphStoreException e) {
				error(WireError.STORE, e.getMessage(), 0, 0);
			} catch (RuntimeException e) {
				logger.error("Stream {} failed", streamId, e);
				error(WireError.INTERNAL, String.valueOf(e.getMessage()), 0, 0);
			}
		}

		/**
		 * The one signal the engine consults while it scans.
		 *
		 * <p>
		 * It has to be here rather than only between rows: a full scan reaches the sink exactly once, at the end, so a
		 * between-rows check alone would let an abandoned query run to completion and only then notice.
		 * </p>
		 */
		String abortReason() {
			if (cancelled) {
				return "the client cancelled this query";
			}
			if (!connection.open) {
				return "the client disconnected";
			}
			if (deadlineNanos != 0 && System.nanoTime() - deadlineNanos >= 0) {
				return "the query exceeded its " + budgetMillis + " ms budget and was abandoned";
			}
			return abortReason;
		}

		void columns(List<String> names) {
			WireValueCodec.Writer payload = new WireValueCodec.Writer(64 + names.size() * 16);
			WireValueCodec.encode(payload, names);
			write(Frames.COLUMNS, payload);
		}

		void complete(long rows) {
			if (!terminated.compareAndSet(false, true)) {
				return;
			}
			WireValueCodec.Writer payload = new WireValueCodec.Writer(16);
			payload.i64(rows);
			payload.i64(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedNanos));
			write(Frames.COMPLETE, payload);
			release();
		}

		void error(WireError code, String message, int line, int column) {
			if (!terminated.compareAndSet(false, true)) {
				return;
			}
			connection.fail(streamId, code, message, line, column);
			release();
		}

		/**
		 * Frees the stream id as the terminal frame goes out, not afterwards.
		 *
		 * <p>
		 * A client is entitled to reuse an id the moment it has seen that stream end. Releasing it in the completion
		 * callback instead would leave a window in which the client's next query is refused as a duplicate — a race
		 * that would show up as an occasional dropped connection under load and nowhere in a test.
		 * </p>
		 */
		private void release() {
			connection.streams.remove(streamId, this);
		}

		void write(int type, WireValueCodec.Writer payload) {
			int length = payload.position();
			buffered.addAndGet(length);
			Buffer frame = Frames.frame(type, streamId, payload);
			if (!connection.open) {
				buffered.addAndGet(-length);
				return;
			}
			connection.socket.write(frame).onComplete(ignored -> buffered.addAndGet(-length));
		}

		/**
		 * Waits until this stream is under its byte budget, or gives up.
		 *
		 * <p>
		 * <b>Never an unbounded park.</b> That is the difference between a client that has stopped reading costing a
		 * worker thread and a read view for as long as it feels like, and costing them for at most the query's
		 * deadline. An open view holds back reclamation for exactly as long as it is open.
		 * </p>
		 *
		 * <h2>Why {@code writeQueueFull()} alone is not the bound</h2>
		 *
		 * <p>
		 * It was, and it did not hold. {@code socket.write} from a producer thread <em>queues a task</em> for the
		 * event loop; the bytes are not in the channel's outbound buffer yet, so {@code isWritable()} is still true
		 * and the producer runs ahead. Measured against a client that stopped reading, a 64 KiB write queue held
		 * 1,051,200 bytes — sixteen batches past its mark — because the producer wrote them all before the event loop
		 * processed the first.
		 * </p>
		 *
		 * <p>
		 * So the producer counts its own bytes in flight, and that count is the limit. Netty's water mark stays in the
		 * condition as a second signal, but the multiplication in {@link WireServerOptions} is only a real bound
		 * because of this counter.
		 * </p>
		 */
		void awaitWritable() {
			while (buffered.get() >= options.writeQueueMaxBytes() || connection.writeQueueFull()) {
				String reason = abortReason();
				if (reason != null) {
					throw new QueryAbortedException(reason);
				}
				LockSupport.parkNanos(PARK_NANOS);
			}
		}
	}

	/**
	 * Encodes rows into batches and hands the bytes to the socket.
	 *
	 * <p>
	 * Everything here runs on the producer thread, which is the thread that owns the read view. Only encoded bytes
	 * cross to the event loop.
	 * </p>
	 */
	private final class BatchingSink implements RowSink {

		private final Stream stream;
		private final WireValueCodec.Writer writer;
		private int batched;
		private long lastFlushNanos = System.nanoTime();
		private long rowsSent;

		BatchingSink(Stream stream) {
			this.stream = stream;
			this.writer = new WireValueCodec.Writer(options.rowBatchBytes() + 8192);
			startBatch();
		}

		private void startBatch() {
			writer.reset();
			// Four bytes for the row count, patched at flush: how many rows fit is decided by the bytes they reach.
			writer.i32(0);
			batched = 0;
		}

		@Override
		public void columns(List<String> names) {
			stream.columns(names);
		}

		@Override
		public boolean row(List<Object> values) {
			for (Object value : values) {
				WireValueCodec.encode(writer, CypherWireValues.of(value));
			}
			batched++;
			boolean full = writer.position() >= options.rowBatchBytes();
			boolean stale = System.nanoTime() - lastFlushNanos >= TimeUnit.MILLISECONDS
				.toNanos(options.flushIntervalMillis());
			if (full || stale) {
				flush();
			}
			// Never false. Stopping is expressed by throwing QueryAbortedException from awaitWritable, so that the
			// reason travels with it — a bare false would end the stream looking exactly like a query that finished.
			return true;
		}

		void flush() {
			if (batched == 0) {
				return;
			}
			stream.awaitWritable();
			writer.patchI32(0, batched);
			stream.write(Frames.ROWS, writer);
			rowsSent += batched;
			lastFlushNanos = System.nanoTime();
			startBatch();
		}
	}
}
