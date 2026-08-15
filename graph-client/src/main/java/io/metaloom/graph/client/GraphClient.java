package io.metaloom.graph.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.metaloom.graph.protocol.FrameReader;
import io.metaloom.graph.protocol.Frames;
import io.metaloom.graph.protocol.Protocol;
import io.metaloom.graph.protocol.WireError;
import io.metaloom.graph.protocol.WireFormatException;
import io.metaloom.graph.protocol.WireValueCodec;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

/**
 * A connection to a graph server's binary port.
 *
 * <pre>{@code
 * try (GraphClient client = GraphClient.connect("localhost", 8081);
 *      RowStream rows = client.query("MATCH (a:Airport) RETURN a.code, a.name")) {
 *     for (Row row : rows) {
 *         System.out.println(row.string("a.code") + " " + row.string("a.name"));
 *     }
 * }
 * }</pre>
 *
 * <h2>What this client is for</h2>
 *
 * <p>
 * The HTTP route builds the entire result — every row, plus a graph projection — into one JSON object before a byte
 * reaches the caller. That is right for a browser drawing two hundred nodes and useless for anything pulling a million
 * rows. This is the other shape: rows arrive as they are produced, the client holds one batch at a time, and a slow
 * consumer becomes backpressure rather than heap.
 * </p>
 *
 * <h2>Threading</h2>
 *
 * <p>
 * {@link #query} may be called from any thread and returns immediately; the returned {@link RowStream} blocks the
 * consuming thread while it waits for rows. Several streams may run at once over one connection, each consumed by its
 * own thread — but read {@link RowStream}'s note on why consuming two streams at very different speeds is a reason for
 * two connections.
 * </p>
 *
 * <h2>No reconnect</h2>
 *
 * <p>
 * A connection that drops mid-stream ends every query on it with an error, and nothing is retried. Reconnecting would
 * silently resume against a <em>different</em> snapshot, so a result that spanned the break would mix two points in
 * time with nothing to say so. Whether to run the query again is the caller's decision, and it is a real decision.
 * </p>
 *
 * <h2>No authentication and no transport security</h2>
 *
 * <p>
 * There is neither. The port is unauthenticated and the bytes are in the clear, so the server binds loopback by
 * default. Anything else needs something in front of it. See spec/OPERATIONS.md.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
public final class GraphClient implements AutoCloseable {

	private final Vertx vertx;
	private final boolean ownsVertx;
	private final NetClient netClient;
	private final NetSocket socket;
	private final ClientOptions options;

	private final Map<Integer, RowStream> streams = new ConcurrentHashMap<>();
	/** Ids of streams the caller closed, held back from reuse until the server's terminal frame arrives. */
	private final Set<Integer> retired = ConcurrentHashMap.newKeySet();
	private final AtomicInteger nextStreamId = new AtomicInteger(1);
	private final AtomicLong queuedBytes = new AtomicLong();
	private final CompletableFuture<Welcome> handshake = new CompletableFuture<>();

	/**
	 * The socket's own event loop, captured from the first frame that arrives on it.
	 *
	 * <p>
	 * Not {@code vertx.getOrCreateContext()} from the connecting thread: that manufactures a fresh context on whatever
	 * thread happened to call {@code connect}, and resuming a socket from a different event loop than the one reading
	 * it is the kind of thing that works until it does not. WELCOME arrives before any query can, so this is set before
	 * anything needs it.
	 * </p>
	 */
	private volatile Context socketContext;

	/** Only ever written from the socket's event loop, so the pause and the resume cannot cross. */
	private boolean readsPaused;
	private volatile Welcome server;
	private volatile boolean closed;

	/** What the server said about itself, so the client does not have to assume. */
	public record Welcome(int version, int maxFrameBytes, int maxConcurrentStreams) {
	}

	private GraphClient(Vertx vertx, boolean ownsVertx, NetClient netClient, NetSocket socket, ClientOptions options) {
		this.vertx = vertx;
		this.ownsVertx = ownsVertx;
		this.netClient = netClient;
		this.socket = socket;
		this.options = options;
	}

	public static GraphClient connect(String host, int port) {
		return connect(host, port, ClientOptions.defaults());
	}

	/**
	 * Connects and completes the version handshake.
	 *
	 * <p>
	 * Both, before returning. A client that started sending queries without waiting for {@link Frames#WELCOME} would
	 * discover a version mismatch as a stream of frames it could not read, with no explanation of why.
	 * </p>
	 */
	public static GraphClient connect(String host, int port, ClientOptions options) {
		Vertx vertx = Vertx.vertx();
		try {
			return connect(vertx, true, host, port, options);
		} catch (RuntimeException e) {
			vertx.close();
			throw e;
		}
	}

	/** For a caller that already runs a Vert.x instance and would rather not have a second one. */
	public static GraphClient connect(Vertx vertx, String host, int port, ClientOptions options) {
		return connect(vertx, false, host, port, options);
	}

	private static GraphClient connect(Vertx vertx, boolean ownsVertx, String host, int port, ClientOptions options) {
		long timeoutMillis = options.connectTimeout().toMillis();
		NetClient netClient = vertx.createNetClient(new NetClientOptions()
			.setConnectTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE))
			// SO_RCVBUF explicitly: autotuning would let the kernel hold megabytes past the point where the client
			// thinks it has stopped reading, and a bound the kernel can walk past is not a bound.
			.setReceiveBufferSize(options.socketReceiveBufferBytes())
			.setTcpNoDelay(true));

		NetSocket socket;
		try {
			socket = netClient.connect(port, host)
				.toCompletionStage().toCompletableFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			netClient.close();
			throw new GraphClientException("Interrupted while connecting to " + host + ":" + port, e);
		} catch (ExecutionException | TimeoutException e) {
			netClient.close();
			throw new GraphClientException("Could not connect to " + host + ":" + port, e);
		}

		GraphClient client = new GraphClient(vertx, ownsVertx, netClient, socket, options);
		client.start();
		try {
			client.server = client.handshake.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			client.close();
			throw new GraphClientException("Interrupted during the handshake with " + host + ":" + port, e);
		} catch (TimeoutException e) {
			client.close();
			throw new GraphClientException(host + ":" + port + " accepted the connection but did not answer HELLO"
				+ " within " + timeoutMillis + " ms; is it a graph server?", e);
		} catch (ExecutionException e) {
			client.close();
			throw e.getCause() instanceof RuntimeException runtime
				? runtime
				: new GraphClientException("The handshake with " + host + ":" + port + " failed", e.getCause());
		}
		return client;
	}

	private void start() {
		socket.closeHandler(v -> connectionLost(new GraphClientException("The server closed the connection")));
		socket.exceptionHandler(t -> connectionLost(new GraphClientException("The connection failed", t)));
		FrameReader.attach(socket, this::onFrame, malformed -> {
			connectionLost(new GraphClientException("The server sent something that is not this protocol", malformed));
			socket.close();
		});

		WireValueCodec.Writer hello = new WireValueCodec.Writer(8);
		hello.i32(Protocol.VERSION);
		socket.write(Frames.frame(Frames.HELLO, Frames.CONNECTION_STREAM, hello));
	}

	/** What the server told us about itself in its WELCOME. */
	public Welcome server() {
		return server;
	}

	// ---------------------------------------------------------------- queries

	public RowStream query(String cypher) {
		return query(cypher, Map.of(), QueryOptions.SERVER_DEFAULT);
	}

	public RowStream query(String cypher, Map<String, Object> parameters) {
		return query(cypher, parameters, QueryOptions.SERVER_DEFAULT);
	}

	/**
	 * Starts a query and returns before a single row has arrived.
	 *
	 * <p>
	 * Nothing is validated here. A syntax error comes back as a {@link QueryFailedException} from the first
	 * {@link RowStream#hasNext()} or {@link RowStream#columns()}, because the parser lives on the server and asking it
	 * is the only way to know.
	 * </p>
	 */
	public RowStream query(String cypher, Map<String, Object> parameters, QueryOptions queryOptions) {
		if (closed) {
			throw new GraphClientException("This client is closed");
		}
		int streamId = allocateStreamId();
		RowStream stream = new RowStream(this, streamId);
		streams.put(streamId, stream);

		WireValueCodec.Writer payload = new WireValueCodec.Writer(cypher.length() + 64);
		WireValueCodec.encode(payload, cypher);
		WireValueCodec.encode(payload, parameters);
		payload.i64(queryOptions.deadlineMillis());
		socket.write(Frames.frame(Frames.QUERY, streamId, payload));
		return stream;
	}

	/**
	 * A number no open stream is using.
	 *
	 * <p>
	 * An id is not freed when the caller closes a stream — it is freed when the server's terminal frame for it
	 * arrives. Reusing it earlier would let frames still in flight for the old query be routed into the new one, which
	 * is a wrong answer rather than a crash and therefore the worse kind of bug.
	 * </p>
	 */
	private int allocateStreamId() {
		for (int attempt = 0; attempt <= 0xFFFF; attempt++) {
			int id = nextStreamId.getAndUpdate(current -> current >= 0xFFFF ? 1 : current + 1);
			if (!streams.containsKey(id) && !retired.contains(id)) {
				return id;
			}
		}
		throw new GraphClientException("All 65535 stream ids on this connection are in use");
	}

	// ---------------------------------------------------------------- frame dispatch, on the event loop

	private void onFrame(int type, int streamId, Buffer payload) {
		if (socketContext == null) {
			socketContext = Vertx.currentContext();
		}
		switch (type) {
			case Frames.WELCOME -> {
				WireValueCodec.Reader reader = new WireValueCodec.Reader(payload.getBytes());
				handshake.complete(new Welcome(reader.i32(), reader.i32(), reader.i32()));
			}
			case Frames.COLUMNS -> {
				RowStream stream = streams.get(streamId);
				if (stream != null) {
					stream.onColumns(readColumns(payload));
				}
			}
			case Frames.ROWS -> {
				RowStream stream = streams.get(streamId);
				if (stream != null) {
					byte[] bytes = payload.getBytes();
					stream.onRows(bytes);
					queued(bytes.length);
				}
			}
			case Frames.COMPLETE -> {
				RowStream stream = streams.remove(streamId);
				retired.remove(streamId);
				if (stream != null) {
					stream.onComplete();
				}
			}
			case Frames.ERROR -> onError(streamId, payload);
			default -> {
				connectionLost(new GraphClientException(
					"The server sent an unexpected " + Frames.typeName(type) + " frame"));
				socket.close();
			}
		}
	}

	private void onError(int streamId, Buffer payload) {
		QueryFailedException failure;
		try {
			WireValueCodec.Reader reader = new WireValueCodec.Reader(payload.getBytes());
			WireError code = WireError.fromCode(reader.i32());
			String message = WireValueCodec.expect(WireValueCodec.decode(reader), String.class, "an error message");
			failure = new QueryFailedException(code, message, reader.i32(), reader.i32());
		} catch (WireFormatException e) {
			failure = new QueryFailedException(WireError.PROTOCOL, "The server sent an unreadable error frame", 0, 0);
		}
		if (streamId == Frames.CONNECTION_STREAM) {
			// An error about the connection rather than about a query: a version mismatch, or a frame the server
			// could not parse. Nothing on this connection continues.
			handshake.completeExceptionally(failure);
			connectionLost(failure);
			socket.close();
			return;
		}
		RowStream stream = streams.remove(streamId);
		retired.remove(streamId);
		if (stream != null) {
			stream.onError(failure);
		}
	}

	private static List<String> readColumns(Buffer payload) {
		Object decoded = WireValueCodec.decode(new WireValueCodec.Reader(payload.getBytes()));
		List<?> raw = WireValueCodec.expect(decoded, List.class, "the column list");
		return raw.stream().map(name -> WireValueCodec.expect(name, String.class, "a column name")).toList();
	}

	private void connectionLost(GraphClientException cause) {
		if (!handshake.isDone()) {
			handshake.completeExceptionally(cause);
		}
		// Every stream on this connection ends, and ends as a failure. A stream that simply stopped producing rows
		// would look to its consumer exactly like one that had finished, which is a truncated result reported as a
		// complete one.
		for (Integer id : List.copyOf(streams.keySet())) {
			RowStream stream = streams.remove(id);
			if (stream != null) {
				stream.onError(new QueryFailedException(WireError.PROTOCOL, cause.getMessage(), 0, 0));
			}
		}
		retired.clear();
	}

	// ---------------------------------------------------------------- backpressure

	/**
	 * Called on the event loop when a batch is handed to a stream.
	 *
	 * <p>
	 * The budget is per <em>connection</em> rather than per stream, because the thing being rationed — the socket's
	 * receive window — is per connection. Pausing is the client's only way to say "not so fast", and it says it for
	 * everything on the wire at once.
	 * </p>
	 */
	void queued(long bytes) {
		long total = queuedBytes.addAndGet(bytes);
		if (!readsPaused && total > options.readBufferBytes()) {
			readsPaused = true;
			socket.pause();
		}
	}

	/** Called from a consuming thread as a batch is decoded, so the resume has to be hopped onto the event loop. */
	void consumed(long bytes) {
		long total = queuedBytes.addAndGet(-bytes);
		Context loop = socketContext;
		if (loop != null && readsPaused && total <= options.readBufferBytes() / 2) {
			// Half, not the mark itself: resuming at the threshold makes the socket flap between paused and running
			// once per batch, which costs more than it saves.
			loop.runOnContext(v -> {
				if (readsPaused && queuedBytes.get() <= options.readBufferBytes() / 2) {
					readsPaused = false;
					socket.resume();
				}
			});
		}
	}

	/** A stream the caller has closed: stop the query if it is still running. */
	void cancel(int streamId) {
		if (!closed) {
			socket.write(Frames.frame(Frames.CANCEL, streamId, Buffer.buffer(0)));
		}
	}

	/**
	 * A closed stream gives back whatever it had queued and stops being routed to.
	 *
	 * <p>
	 * It is unregistered but its id is not released. Frames for it are already in flight and will keep arriving until
	 * the server acknowledges the cancel; handing that id to a new query in the meantime would deliver one query's rows
	 * to another, which is a wrong answer rather than a crash and therefore the worse kind of bug.
	 * </p>
	 */
	void discard(int streamId, long unreadBytes) {
		if (streams.remove(streamId) != null) {
			retired.add(streamId);
		}
		if (unreadBytes > 0) {
			consumed(unreadBytes);
		}
	}

	// ---------------------------------------------------------------- lifecycle

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		connectionLost(new GraphClientException("The client was closed"));
		socket.close();
		netClient.close();
		if (ownsVertx) {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}
	}
}
