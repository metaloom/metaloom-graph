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

import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.cypher.CypherWriteException;
import io.metaloom.graph.cypher.QueryAbortedException;
import io.metaloom.graph.cypher.RowSink;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.io.CsvSource;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;
import io.metaloom.graph.protocol.FrameReader;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.protocol.Frames;
import io.metaloom.graph.protocol.Protocol;
import io.metaloom.graph.protocol.QuerySummary;
import io.metaloom.graph.protocol.WireError;
import io.metaloom.graph.protocol.WireFormatException;
import io.metaloom.graph.protocol.WireValueCodec;
import io.metaloom.graph.server.SlowQueryLog;
import io.metaloom.graph.server.auth.AuthPolicy;
import io.metaloom.graph.server.metrics.GraphMetrics;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemKeyCertOptions;

/**
 * The binary port: a Cypher result, streamed as it is produced.
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
 * <h2>Writes, and what they do not stream</h2>
 *
 * <p>
 * Write queries are accepted only when the server was started with {@code --allow-write}; a write on a server without
 * it is refused on the event loop with {@link WireError#READ_ONLY}, before a permit or a worker thread is spent.
 * </p>
 *
 * <p>
 * <b>A write query does not stream.</b> Its rows are produced, its transaction commits, and only then does the first
 * byte go out — because a client that had already received rows when the commit failed would hold a result from a
 * transaction that no longer exists, and bytes on a socket cannot be recalled. That ordering also removes this port's
 * worst hazard: the producer parks against a slow client, and parking while holding the engine's single writer lock
 * would freeze every writer in the process. By the time anything can park, the transaction is closed.
 * </p>
 *
 * <p>
 * The remaining wait is for the writer lock itself, which the engine takes with no deadline and no message. A permit
 * per store puts a bound on it, so a second writer answers {@link WireError#OVERLOADED} rather than hanging past its
 * own budget.
 * </p>
 *
 * <h2>Cancellation never closes a view from another thread</h2>
 *
 * <p>
 * {@code Snapshot.close()} does not check its owner, so closing it from the cancelling thread would leave the producer
 * holding a closed snapshot and break that thread's <em>next</em> query. A cancel sets a flag the producer polls; the
 * view is only ever closed by the thread that opened it, on its way out of {@link Cypher#stream}.
 * </p>
 *
 * <h2>Two listeners, one server</h2>
 *
 * <p>
 * With {@link WireServerOptions#tls} set, this opens a <b>second</b> listener that terminates TLS, on a port of its
 * own. Everything behind the socket is shared — the stream permits, the worker pool, the write gates, the watchdog —
 * so {@link WireServerOptions#maxBufferedBytes()} still describes the whole server and not one of two halves. A
 * connection's encryption is decided by which port it reached and never changes after that; the frames are identical
 * either way. {@link WireTlsOptions} has the reasoning, including why this is not a negotiated upgrade.
 * </p>
 *
 * <p>
 * Both are off-by-default in the sense that matters: TLS needs a certificate an operator supplies, and the plaintext
 * listener still binds {@link WireServerOptions#DEFAULT_HOST} — loopback — because a password crosses it in the clear.
 * Authentication ({@code spec/AUTH.md}) and transport security are complementary and neither replaces the other. See
 * spec/OPERATIONS.md.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
public final class WireServer implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(WireServer.class);

	/** How long the producer parks before asking again whether the socket has drained. */
	private static final long PARK_NANOS = TimeUnit.MICROSECONDS.toNanos(50);

	/**
	 * How long {@link #close()} lets a running query finish before abandoning it.
	 *
	 * <p>
	 * Five seconds because a shutdown has its own deadline above it — a container runtime sends SIGKILL some seconds
	 * after SIGTERM — and a grace period longer than that one buys nothing except a less clean death.
	 * </p>
	 */
	public static final long DEFAULT_GRACE_MILLIS = 5_000;

	/** How long an abandoned query is given to unwind before the store is closed regardless. */
	private static final long ABORT_UNWIND_MILLIS = 2_000;

	/**
	 * The ceiling on one frame until WELCOME.
	 *
	 * <p>
	 * HELLO and AUTH are a few hundred bytes, a long password included, and nothing else may be sent before the
	 * handshake. {@link Frames#MAX_FRAME_BYTES} applies from then on; before it, a peer that has presented nothing could
	 * have the server buffer sixteen megabytes per connection.
	 * </p>
	 */
	static final int HANDSHAKE_MAX_FRAME_BYTES = 16 * 1024;

	/**
	 * How long a peer has to send its HELLO and, when asked, its AUTH.
	 *
	 * <p>
	 * Counted only while the server is waiting on the <em>peer</em> — not while it verifies a password or opens a store,
	 * which is the server's own time. A connection that never speaks otherwise holds a socket and its buffers forever.
	 * There is deliberately no idle timeout after the handshake: the protocol has no keep-alive, and a pooled client
	 * idle between queries is normal.
	 * </p>
	 */
	public static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000;

	private volatile long handshakeTimeoutMillis = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;

	/** For a test that cannot wait ten seconds. Applies to connections accepted afterwards; zero disables it. */
	void handshakeTimeoutMillis(long millis) {
		this.handshakeTimeoutMillis = millis;
	}

	private final Vertx vertx;
	private final boolean ownsVertx;
	private final GraphDatabases databases;
	private final String defaultDatabase;
	private final WireServerOptions options;
	private final WorkerExecutor workers;
	private final Semaphore permits;
	private final Map<Long, Stream> running = new ConcurrentHashMap<>();
	private final AtomicLong nextStreamKey = new AtomicLong();
	private final ScheduledExecutorService watchdog;
	/** Whether a query may change a store. Off unless an operator asked for it. */
	private final boolean allowWrite;

	/** Where LOAD CSV may read on this port. Refuses everything unless {@link #csvSource} has been called. */
	private CsvSource csv = CsvSource.DENY;

	/** Cached from {@link #csv}, so the event-loop refusal is a field read. */
	private boolean allowLoadCsv;
	/**
	 * One permit per store, so a writer's wait has a deadline and a name.
	 *
	 * <p>
	 * The engine already serialises writers on one lock per store, and that lock has neither: a second writer waits
	 * inside {@code begin()} indefinitely, past its own budget, with nothing to report when it gives up. This adds no
	 * serialisation that was not already there — it puts a bound on the wait and lets the stream answer OVERLOADED
	 * instead of hanging. Keyed per store because the engine's lock is.
	 * </p>
	 */
	private final Map<GraphStore, Semaphore> writeGates = new ConcurrentHashMap<>();
	/** Set once shutdown begins: new queries are refused, and the ones running are given a bounded chance to finish. */
	private final AtomicBoolean closing = new AtomicBoolean();

	/** Where this port records. {@link GraphMetrics#NOOP} until {@link #instrument} says otherwise. */
	private volatile GraphMetrics metrics = GraphMetrics.NOOP;

	/** Past this a query is written to the slow query log. Zero — off — unless {@link #instrument} says otherwise. */
	private volatile long slowQueryMillis;

	private NetServer server;

	/** The encrypted listener, or null when no certificate was configured. Shares everything behind the socket. */
	private NetServer tlsServer;

	public WireServer(GraphDatabases databases, String defaultDatabase) {
		this(Vertx.vertx(), true, databases, defaultDatabase, WireServerOptions.defaults(), false);
	}

	public WireServer(GraphDatabases databases, String defaultDatabase, WireServerOptions options) {
		this(Vertx.vertx(), true, databases, defaultDatabase, options, false);
	}

	/**
	 * A server that owns its Vert.x, and closes it.
	 *
	 * <p>
	 * The distinction is not decoration. The overload below takes a Vert.x the caller owns and deliberately does
	 * <em>not</em> close it, because the HTTP server shares one. Reaching for that overload just to pass this flag
	 * leaks an acceptor thread, an event loop and a worker pool per instance — which is exactly what happened when
	 * {@code allowWrite} was added, and which showed up not as a failure but as a suite that passed in isolation and
	 * hung under load with a dozen Vert.x instances still resident.
	 * </p>
	 */
	public WireServer(GraphDatabases databases, String defaultDatabase, WireServerOptions options,
		boolean allowWrite) {
		this(Vertx.vertx(), true, databases, defaultDatabase, options, allowWrite);
	}

	/** For a caller that already runs a Vert.x instance — the HTTP server, for one. */
	public WireServer(Vertx vertx, GraphDatabases databases, String defaultDatabase, WireServerOptions options) {
		this(vertx, false, databases, defaultDatabase, options, false);
	}

	public WireServer(Vertx vertx, GraphDatabases databases, String defaultDatabase, WireServerOptions options,
		boolean allowWrite) {
		this(vertx, false, databases, defaultDatabase, options, allowWrite);
	}

	private WireServer(Vertx vertx, boolean ownsVertx, GraphDatabases databases, String defaultDatabase,
		WireServerOptions options, boolean allowWrite) {
		this.vertx = vertx;
		this.ownsVertx = ownsVertx;
		this.databases = databases;
		this.defaultDatabase = defaultDatabase;
		this.options = options;
		this.allowWrite = allowWrite;
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

	/**
	 * Binds every listener this server was configured with.
	 *
	 * @return the port a client would connect to: the plaintext one when it is open, otherwise the TLS one. Both are
	 *         logged, and {@link #port()} and {@link #tlsPort()} report them separately
	 */
	public Future<Integer> listen() {
		if (options.retentionSoftLimitBytes() > 0) {
			// On a thread of its own rather than a Vert.x timer: it reads store statistics, and "this call is
			// certainly cheap enough for an event loop" is exactly the assumption that ages badly.
			watchdog.scheduleWithFixedDelay(this::checkRetention, options.watchdogIntervalMillis(),
				options.watchdogIntervalMillis(), TimeUnit.MILLISECONDS);
		}
		Future<Integer> plain = options.plaintext()
			? bind(false, options.host(), options.port()).map(bound -> {
				server = bound;
				logger.info("Binary port on {}:{}, up to {} concurrent streams x ({} write queue + {} send buffer)"
					+ " = {} bytes held for clients that stop reading",
					options.host(), bound.actualPort(), options.maxConcurrentStreams(), options.writeQueueMaxBytes(),
					options.socketSendBufferBytes(), options.maxBufferedBytes());
				return bound.actualPort();
			})
			: Future.succeededFuture(-1);

		WireTlsOptions tls = options.tls();
		if (tls == null) {
			return plain;
		}
		// Sequentially rather than in parallel: a certificate that cannot be loaded should fail before anything is
		// reachable, and a half-bound server whose other half failed is a state nobody wants to reason about.
		return plain.compose(plainPort -> bind(true, tls.host(), tls.port())
			.map(bound -> {
				tlsServer = bound;
				logger.info("Binary port over TLS on {}:{} (certificate {}){}", tls.host(), bound.actualPort(),
					tls.certificate(),
					options.plaintext() ? "" : " — the plaintext listener is off, this is the only binary port");
				return plainPort >= 0 ? plainPort : bound.actualPort();
			})
			// The failure this exists for is the one that would be worst to get wrong: a bad certificate leaves the
			// plaintext listener bound, and Vert.x's event loops are not daemon threads, so a caller that let the
			// exception escape would have a process still serving in the clear after announcing that TLS failed.
			// "Encryption did not start, so we served without it" is precisely the outcome this feature must not have.
			.recover(failure -> {
				if (server != null) {
					logger.error("The encrypted binary port could not be opened, so the plaintext one on {}:{} is"
						+ " being closed too rather than left serving in the clear", options.host(), port());
					NetServer opened = server;
					server = null;
					return opened.close().transform(ignored -> Future.failedFuture(failure));
				}
				return Future.failedFuture(failure);
			}));
	}

	/**
	 * One listener.
	 *
	 * <p>
	 * The non-TLS settings are applied to both, because they are properties of this server rather than of a transport:
	 * the send buffer is in {@link WireServerOptions#maxBufferedBytes()} whichever port the peer reached, and a
	 * connection that skipped {@code TCP_NODELAY} because it happened to be encrypted would be a latency difference
	 * nobody would think to look for.
	 * </p>
	 */
	private Future<NetServer> bind(boolean tls, String host, int port) {
		// SO_SNDBUF explicitly, because Linux autotunes a loopback socket's buffers into the megabytes under a fast
		// writer — enough for a four-megabyte result to go out in full against a client that was reading nothing,
		// with the write queue never once looking full. See WireServerOptions.
		NetServerOptions netOptions = new NetServerOptions()
			.setSendBufferSize(options.socketSendBufferBytes())
			.setTcpNoDelay(true);
		if (tls) {
			WireTlsOptions certificate = options.tls();
			netOptions.setSsl(true)
				.setKeyCertOptions(new PemKeyCertOptions()
					.setCertPath(certificate.certificate().toString())
					.setKeyPath(certificate.privateKey().toString()));
		}
		NetServer bound = vertx.createNetServer(netOptions);
		bound.connectHandler(socket -> {
			if (closing.get()) {
				// The listener is still bound during the shutdown grace period, so that the connections running
				// queries are not cut off (see shutdown). A peer arriving now is told why rather than served.
				WireValueCodec.Writer refusal = new WireValueCodec.Writer(96);
				refusal.i32(WireError.OVERLOADED.code());
				WireValueCodec.encodeString(refusal, "The server is shutting down and is not accepting connections");
				refusal.i32(0);
				refusal.i32(0);
				socket.write(Frames.frame(Frames.ERROR, Frames.CONNECTION_STREAM, refusal))
					.onComplete(ignored -> socket.close());
				return;
			}
			socket.setWriteQueueMaxSize(options.writeQueueMaxBytes());
			// Asked of the socket rather than closed over from this lambda, so the counter reports what the transport
			// actually did rather than what this server intended.
			metrics.recordWireConnection(socket.isSsl());
			new Connection(socket).attach();
		});
		return bound.listen(port, host)
			.recover(failure -> Future.failedFuture(tls
				// Naming the certificate here is the difference between a two-minute fix and an afternoon: Netty's
				// message for a malformed or mismatched key pair says nothing about which file it came from.
				? new IllegalStateException("Could not open the encrypted binary port on " + host + ":" + port
					+ " with certificate " + options.tls().certificate() + " and key " + options.tls().privateKey()
					+ ". Both must be unencrypted PEM, and the key must match the certificate", failure)
				: failure))
			.map(bound);
	}

	/** The unencrypted listener's port, or -1 when {@code plaintext} is off. */
	public int port() {
		return server == null ? -1 : server.actualPort();
	}

	/** The encrypted listener's port, or -1 when no certificate was configured. */
	public int tlsPort() {
		return tlsServer == null ? -1 : tlsServer.actualPort();
	}

	public WireServerOptions options() {
		return options;
	}

	/**
	 * Turns on recording and the slow query log for this port.
	 *
	 * <p>
	 * Set after construction rather than taken as a constructor argument, for the same reason {@code GraphServer}
	 * does it: this class has four public constructors and every test uses one of them, so threading two more
	 * parameters through all of them would change a lot of call sites in order to pass {@code NOOP} and {@code 0}.
	 * Only {@code GraphServer.main} calls this.
	 * </p>
	 *
	 * @param slowQueryMillis
	 *            0 to log nothing
	 */
	/**
	 * Enables {@code LOAD CSV} on this port, against the same sandbox the HTTP front end uses.
	 *
	 * <p>
	 * The flag is a property of the server rather than of a port, so both front ends get it or neither does. Not
	 * calling this leaves {@link CsvSource#DENY}, which is what an embedder constructing a wire server directly gets.
	 * </p>
	 */
	public void csvSource(CsvSource csv) {
		this.csv = csv == null ? CsvSource.DENY : csv;
		this.allowLoadCsv = this.csv != CsvSource.DENY;
	}

	/**
	 * Who a connection has to be.
	 *
	 * <p>
	 * A supplier and not a value, for the reason {@code AuthRoutes} holds one: the policy is set through a setter that
	 * a caller may reach for before or after {@code listen()}, and capturing it would make the later case silently do
	 * nothing. A setter rather than a constructor argument because there are already five public constructors and
	 * every existing test and embedder uses one of them.
	 * </p>
	 */
	private java.util.function.Supplier<AuthPolicy> auth = AuthPolicy::disabled;

	public void authPolicy(java.util.function.Supplier<AuthPolicy> policy) {
		this.auth = policy == null ? AuthPolicy::disabled : policy;
	}

	public void instrument(GraphMetrics metrics, long slowQueryMillis) {
		this.metrics = metrics;
		this.slowQueryMillis = slowQueryMillis;
	}

	/**
	 * A wire error code as the same metric label the HTTP route uses.
	 *
	 * <p>
	 * Mapped rather than lower-cased, so that {@code READ_ONLY} and {@code NO_SUCH_DATABASE} come out as the words
	 * {@code GraphServer.outcomeOf} already produces. One dashboard covers both front ends only if they agree on the
	 * label set.
	 * </p>
	 */
	private static String outcomeOf(WireError code) {
		return switch (code) {
			case SYNTAX -> "syntax";
			case TYPE -> "type";
			case TIMEOUT, CANCELLED -> "timeout";
			case NO_SUCH_DATABASE -> "database";
			case CONSTRAINT_VIOLATION -> "constraint";
			case STORE -> "store";
			case READ_ONLY -> "readonly";
			case OVERLOADED -> "overloaded";
			case PROTOCOL, UNSUPPORTED_VERSION -> "protocol";
			// The same two words GraphServer's refusals use, so one dashboard covers both front ends.
			case UNAUTHENTICATED -> "unauthenticated";
			case UNAUTHORIZED -> "forbidden";
			case INTERNAL -> "internal";
		};
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
		shutdown(DEFAULT_GRACE_MILLIS);
	}

	/**
	 * Stops accepting work, lets what is running finish, and only then lets go.
	 *
	 * <p>
	 * The order is the whole of it. A store's segment files are memory-mapped, and closing one unmaps them: a worker
	 * still inside a query when that happens is not reading stale data, it is reading an address that no longer belongs
	 * to the process. So the store may only be closed once nothing is touching it, and this is what establishes that.
	 * </p>
	 *
	 * <ol>
	 * <li>Refuse new queries, so the set of in-flight ones can only shrink.
	 * <li>Stop accepting connections.
	 * <li>Wait for the running queries, up to the grace period. Most finish in microseconds.
	 * <li>Abort whatever is left through the signal the engine already consults, which unwinds each query, closes its
	 * read view or rolls back its transaction, and sends the client a terminal frame saying why.
	 * <li>Wait again, briefly, for those to unwind.
	 * </ol>
	 *
	 * <p>
	 * <b>Aborting rather than waiting indefinitely is the point.</b> A query with no deadline, or one whose client has
	 * stopped reading, would otherwise hold the process open for as long as it liked — and a shutdown that does not
	 * complete is indistinguishable from a hang, which is what makes an operator reach for {@code kill -9} and lose the
	 * clean close entirely.
	 * </p>
	 *
	 * <p>
	 * Nothing committed is at risk either way: the log is fsynced before any data file is touched, so a write killed at
	 * any instant either committed or did not.
	 * </p>
	 *
	 * @param graceMillis
	 *            how long a running query may take to finish before it is abandoned. Zero abandons immediately
	 */
	public void shutdown(long graceMillis) {
		if (!closing.compareAndSet(false, true)) {
			return;
		}
		watchdog.shutdownNow();
		// The listeners stay up through the grace period, and that is deliberate. Closing a Vert.x NetServer closes every
		// connection it accepted, at once — a graceful NetServer.shutdown does too, because a NetSocket has no shutdown
		// behaviour of its own — so closing them first made every running query "the client disconnected" and handed
		// its client a dropped connection: the grace period this method promises was never given to anybody. New
		// work is refused by the flag instead: a QUERY is answered OVERLOADED, and a connection accepted from here on is
		// told so and closed (see bind).
		if (!awaitQuiet(graceMillis)) {
			int remaining = running.size();
			logger.warn("Shutting down with {} quer{} still running after {} ms; abandoning {}", remaining,
				remaining == 1 ? "y" : "ies", graceMillis, remaining == 1 ? "it" : "them");
			for (Stream stream : running.values()) {
				stream.abortReason = "the server is shutting down";
				stream.abortCode = WireError.OVERLOADED;
			}
			// The engine consults the signal on a fraction of store touches, so unwinding is quick but not instant.
			if (!awaitQuiet(ABORT_UNWIND_MILLIS)) {
				logger.warn("{} quer{} did not unwind; closing anyway", running.size(),
					running.size() == 1 ? "y has" : "ies have");
			}
		}

		if (server != null) {
			server.close().toCompletionStage().toCompletableFuture().join();
		}
		if (tlsServer != null) {
			// The encrypted listener is a second acceptor over the same everything, so it has to stop accepting too.
			// Leaving it open would keep the process reachable through a shutdown that had already been announced.
			tlsServer.close().toCompletionStage().toCompletableFuture().join();
		}
		workers.close();
		if (ownsVertx) {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}
	}

	/** @return true once no query is running, false if the deadline passed first */
	private boolean awaitQuiet(long millis) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		while (!running.isEmpty()) {
			if (System.nanoTime() - deadline >= 0) {
				return false;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		}
		return true;
	}

	/** Whether this server has begun shutting down, and is therefore refusing new queries. */
	public boolean isClosing() {
		return closing.get();
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
			// Per database, not summed. Retention is a property of one store's version chain, and a reader on a
			// busy database must not be abandoned because a different one is holding pages.
			for (GraphStore store : databases.openDatabases()) {
				long retained = store.snapshotStats().retainedBytes();
				if (retained < options.retentionSoftLimitBytes()) {
					continue;
				}
				Stream oldest = null;
				for (Stream stream : running.values()) {
					if (stream.connection.store != store) {
						continue;
					}
					// Readers only. Retention is held by open read views, and a write query does not open one — it
					// runs in a transaction and reads the current mapping. Abandoning a writer would roll back its
					// work without releasing a single retained byte, which is a cost with no benefit.
					if (stream.writing) {
						continue;
					}
					if (oldest == null || stream.startedNanos - oldest.startedNanos < 0) {
						oldest = stream;
					}
				}
				if (oldest != null) {
					logger.warn("Retention on '{}' is at {} bytes, over the {} byte limit: abandoning the oldest"
						+ " stream, open {} ms", oldest.connection.database, retained,
						options.retentionSoftLimitBytes(),
						TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - oldest.startedNanos));
					oldest.abortReason = "the server abandoned this query: it was the oldest open read view and the"
						+ " database was retaining " + retained + " bytes to keep it readable";
					oldest.abortCode = WireError.OVERLOADED;
				}
			}
		} catch (RuntimeException e) {
			logger.warn("The retention watchdog failed; streams are still bounded by their deadlines", e);
		}
	}

	/** The write permit for one store, made on first use. Never removed: a dropped database's entry is one object. */
	private Semaphore writeGate(GraphStore store) {
		return writeGates.computeIfAbsent(store, ignored -> new Semaphore(1));
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

		/**
		 * Where this connection is in the handshake.
		 *
		 * <p>
		 * Three states rather than a boolean, and {@link State#AUTHENTICATING} is the one that earns the change: a
		 * password is verified on a worker thread, so between the AUTH frame arriving and the answer coming back there
		 * is a window in which a client could pipeline a QUERY. Refusing everything in that window is what stops a
		 * query racing an unfinished credential check.
		 * </p>
		 *
		 * <p>
		 * {@link State#OPENING} is the same window for the database: it is opened on a worker, because a first open
		 * maps a whole store, and nothing may run against a store that is not there yet.
		 * </p>
		 */
		private enum State {
			NEW, GREETED, AUTHENTICATING, OPENING, READY
		}

		private State state = State.NEW;

		/**
		 * Resolved at AUTH, or {@link Principal#ANONYMOUS} when this server does not ask. The <em>name</em> is fixed for
		 * the connection; the grants are re-read at every query.
		 */
		private Principal principal = Principal.ANONYMOUS;
		/**
		 * Opened once, just before WELCOME, and fixed for the life of the connection. See {@link Protocol}. After AUTH
		 * when the server asks for one, so a peer that has presented nothing cannot learn which names exist.
		 */
		private GraphStore store;
		private String database;

		Connection(NetSocket socket) {
			this.socket = socket;
		}

		void attach() {
			long deadline = handshakeTimeoutMillis;
			long timer = deadline <= 0 ? -1 : vertx.setTimer(deadline, id -> {
				// Only while waiting on the peer: AUTHENTICATING and OPENING are the server's own time.
				if (open && (state == State.NEW || state == State.GREETED)) {
					fail(Frames.CONNECTION_STREAM, WireError.PROTOCOL,
						"The handshake was not completed within " + deadline + " ms", 0, 0);
					open = false;
					socket.close();
				}
			});
			socket.closeHandler(v -> {
				open = false;
				if (timer >= 0) {
					vertx.cancelTimer(timer);
				}
			});
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
			}, () -> state == State.READY ? Frames.MAX_FRAME_BYTES : HANDSHAKE_MAX_FRAME_BYTES);
		}

		private void frame(int type, int streamId, Buffer payload) {
			try {
				switch (state) {
					case NEW -> {
						if (type != Frames.HELLO) {
							throw new WireFormatException("Expected HELLO, got " + Frames.typeName(type));
						}
					}
					case GREETED -> {
						if (type != Frames.AUTH) {
							throw new WireFormatException("Expected AUTH, got " + Frames.typeName(type));
						}
					}
					case AUTHENTICATING -> {
						// Nothing at all until the answer is back. A QUERY pipelined behind an AUTH would otherwise
						// race a password check still running on a worker.
						fail(Frames.CONNECTION_STREAM, WireError.UNAUTHENTICATED,
							"This connection is still being authenticated", 0, 0);
						open = false;
						socket.close();
						return;
					}
					case OPENING -> {
						// Pipelined behind the handshake: the database it would run against is still being opened.
						fail(Frames.CONNECTION_STREAM, WireError.PROTOCOL,
							"A frame arrived before WELCOME; wait for it", 0, 0);
						open = false;
						socket.close();
						return;
					}
					case READY -> {
						if (type == Frames.HELLO || type == Frames.AUTH) {
							throw new WireFormatException(Frames.typeName(type) + " after the handshake");
						}
					}
				}
				switch (type) {
					case Frames.HELLO -> hello(payload);
					case Frames.AUTH -> authenticate(payload);
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
			WireValueCodec.Reader reader = new WireValueCodec.Reader(payload.getBytes());
			int version = reader.i32();
			if (version != Protocol.VERSION) {
				fail(Frames.CONNECTION_STREAM, WireError.UNSUPPORTED_VERSION,
					"This server speaks protocol version " + Protocol.VERSION + " and the client asked for "
						+ version,
					0, 0);
				open = false;
				socket.close();
				return;
			}
			String requested = WireValueCodec.expect(WireValueCodec.decode(reader), String.class,
				"the database name");
			// Only remembered here. Opening it now would answer NO_SUCH_DATABASE to a peer that has presented nothing,
			// which lists the databases for anybody who can reach the port; it is opened once the credential is
			// checked, or straight away when the server asks for none.
			database = requested.isEmpty() ? defaultDatabase : requested;
			if (auth.get().isEnabled()) {
				// The credential is asked for rather than assumed, which is what lets a client with one talk to a
				// server without one and never send it.
				state = State.GREETED;
				WireValueCodec.Writer required = new WireValueCodec.Writer(64);
				required.i32(Frames.MECHANISM_PASSWORD);
				WireValueCodec.encode(required, "metaloom-graph");
				socket.write(Frames.frame(Frames.AUTH_REQUIRED, Frames.CONNECTION_STREAM, required));
				return;
			}
			openThenWelcome();
		}

		/**
		 * Opens the connection's database on a worker, then ends the handshake.
		 *
		 * <p>
		 * A map lookup after the first time; the first time maps a whole store and holds the {@code GraphDatabases}
		 * monitor while it does, which on an event loop would stall every other connection sharing it. The default
		 * worker pool rather than the query pool, so a handshake never waits behind running queries.
		 * </p>
		 */
		private void openThenWelcome() {
			state = State.OPENING;
			String name = database;
			vertx.<GraphStore>executeBlocking(() -> databases.database(name), false).onComplete(outcome -> {
				if (!open) {
					return;
				}
				if (outcome.succeeded()) {
					store = outcome.result();
					state = State.READY;
					welcome();
					return;
				}
				Throwable e = outcome.cause();
				if (e instanceof IllegalArgumentException || e instanceof GraphStoreException) {
					// A name that is not a name and a name that is not a database are the same thing to a client: it
					// asked for something that is not there, and listing the databases is the next step either way.
					fail(Frames.CONNECTION_STREAM, WireError.NO_SUCH_DATABASE, e.getMessage(), 0, 0);
				} else if (e instanceof java.io.IOException) {
					fail(Frames.CONNECTION_STREAM, WireError.STORE, "Could not open '" + name + "': " + e.getMessage(),
						0, 0);
				} else {
					fail(Frames.CONNECTION_STREAM, WireError.INTERNAL, "Could not open '" + name + "'", 0, 0);
				}
				open = false;
				socket.close();
			});
		}

		/** The frame that ends the handshake, whichever way it got here. */
		private void welcome() {
			WireValueCodec.Writer welcome = new WireValueCodec.Writer(128);
			welcome.i32(Protocol.VERSION);
			welcome.i32(Frames.MAX_FRAME_BYTES);
			welcome.i32(options.maxConcurrentStreams());
			WireValueCodec.encode(welcome, database);
			WireValueCodec.encode(welcome, principal.authenticated() ? principal.name() : "");
			// Grant names, so a client can say what it may do without carrying the enum that defines it.
			java.util.List<Object> grants = new java.util.ArrayList<>();
			for (Grant grant : principal.grants()) {
				grants.add(grant.toString());
			}
			WireValueCodec.encode(welcome, grants);
			socket.write(Frames.frame(Frames.WELCOME, Frames.CONNECTION_STREAM, welcome));
		}

		/**
		 * Verifies the credential, on a worker.
		 *
		 * <p>
		 * PBKDF2 is a hundred milliseconds of CPU and this is an event loop, so the derivation cannot happen here. The
		 * connection sits in {@link State#AUTHENTICATING} until the answer arrives and refuses everything meanwhile.
		 * </p>
		 */
		private void authenticate(Buffer payload) {
			WireValueCodec.Reader reader = new WireValueCodec.Reader(payload.getBytes());
			int mechanism = reader.i32();
			if (mechanism != Frames.MECHANISM_PASSWORD) {
				throw new WireFormatException("This server does not know authentication mechanism " + mechanism);
			}
			String user = WireValueCodec.expect(WireValueCodec.decode(reader), String.class, "the user name");
			String secret = WireValueCodec.expect(WireValueCodec.decode(reader), String.class, "the password");

			String address = socket.remoteAddress() == null ? "unknown" : socket.remoteAddress().host();
			long wait = auth.get().throttle().retryAfterMillis(user, address);
			if (wait > 0) {
				// OVERLOADED rather than UNAUTHENTICATED: nothing is wrong with the credential, the server is simply
				// declining to spend more on this caller for now, which is exactly what that code means elsewhere.
				fail(Frames.CONNECTION_STREAM, WireError.OVERLOADED,
					"Too many failed logins. Try again in " + ((wait + 999) / 1000) + " seconds.", 0, 0);
				open = false;
				socket.close();
				return;
			}

			// The same bound HTTP's login uses, and the same semaphore: the CPU being rationed is the process's.
			// Refused rather than queued — and on the default worker pool, not the query pool, so a burst of
			// handshakes cannot make running queries wait behind PBKDF2 (spec/AUTH.md section 4.1).
			AuthPolicy policy = auth.get();
			if (!policy.tryBeginVerification()) {
				fail(Frames.CONNECTION_STREAM, WireError.OVERLOADED,
					"This server is verifying as many passwords as it can at once. Retry.", 0, 0);
				open = false;
				socket.close();
				return;
			}
			state = State.AUTHENTICATING;
			char[] characters = secret.toCharArray();
			vertx.executeBlocking(() -> policy.authenticate(user, characters), false)
				.onComplete(outcome -> {
					policy.endVerification();
					java.util.Arrays.fill(characters, '\0');
					if (outcome.failed()) {
						logger.error("Verifying a password failed", outcome.cause());
						fail(Frames.CONNECTION_STREAM, WireError.INTERNAL, "Could not verify that password", 0, 0);
						open = false;
						socket.close();
						return;
					}
					if (outcome.result().isEmpty()) {
						auth.get().throttle().recordFailure(user, address);
						logger.info("A failed login for '{}' from {} on the binary port", user, address);
						// One message for every reason, so the port is not a user enumerator.
						fail(Frames.CONNECTION_STREAM, WireError.UNAUTHENTICATED,
							"That user name and password do not match.", 0, 0);
						open = false;
						socket.close();
						return;
					}
					auth.get().throttle().recordSuccess(user);
					principal = outcome.result().get();
					openThenWelcome();
				});
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
				// No duration: it never ran. Counted anyway, because a syntax error is much the most common failure
				// and a dashboard that could not see it would show a port answering nothing and failing nothing.
				metrics.recordQuery("wire", "unknown", "syntax", 0, 0);
				fail(streamId, WireError.SYNTAX, e.getMessage(), e.line(), e.column());
				return;
			} catch (RuntimeException e) {
				metrics.recordQuery("wire", "unknown", "internal", 0, 0);
				fail(streamId, WireError.INTERNAL, String.valueOf(e.getMessage()), 0, 0);
				return;
			}

			if (closing.get()) {
				// Refused rather than accepted and then abandoned a moment later: a client that is told now can
				// reconnect somewhere else, where one cut off mid-result cannot tell a shutdown from a crash.
				metrics.recordQueryRejected("wire", "shutdown");
				fail(streamId, WireError.OVERLOADED, "The server is shutting down and is not accepting queries", 0, 0);
				return;
			}

			// Refused on the event loop, like a syntax error: the tree already knows, so this costs no permit, no
			// worker thread and no writer lock.
			// What the query needs, before what the server permits. Grant first so a principal who simply lacks MERGE
			// is told that, rather than being advised to restart a server they cannot restart.
			if (principal.authenticated()) {
				// Re-resolved per query rather than frozen at AUTH, as HTTP does per request: a revoke or a disable
				// must reach a pooled connection that stays open for days (spec/AUTH.md section 6.1).
				java.util.Optional<Principal> current = auth.get().principalFor(principal.name());
				if (current.isEmpty()) {
					metrics.recordQueryRejected("wire", "unauthenticated");
					fail(Frames.CONNECTION_STREAM, WireError.UNAUTHENTICATED,
						"'" + principal.name() + "' is no longer an enabled user here", 0, 0);
					open = false;
					socket.close();
					return;
				}
				principal = current.get();
			}
			for (Privilege privilege : parsed.requiredPrivileges()) {
				if (!principal.may(privilege, database)) {
					metrics.recordQueryRejected("wire", "forbidden");
					fail(streamId, WireError.UNAUTHORIZED, "'" + principal.name() + "' has not been granted "
						+ privilege + " on '" + database + "'", 0, 0);
					return;
				}
			}

			if (parsed.isWrite() && !allowWrite) {
				// A rejection and not an outcome: it cost nothing, and counting it as a very fast query would drag
				// the duration histogram towards zero.
				metrics.recordQueryRejected("wire", "readonly");
				fail(streamId, WireError.READ_ONLY, "This server does not accept write queries. Start it with "
					+ "--allow-write to enable CREATE, SET, REMOVE and DELETE.", 0, 0);
				return;
			}

			// Not covered by the check above: a `LOAD CSV ... RETURN row` writes nothing, so isWrite() is false and a
			// read-only server would otherwise read files for any client that can reach this port.
			if (parsed.readsFiles() && !allowLoadCsv) {
				metrics.recordQueryRejected("wire", "loadcsv");
				fail(streamId, WireError.READ_ONLY, "This server does not read files. Start it with "
					+ "--allow-load-csv and --import-dir=<path> to enable LOAD CSV.", 0, 0);
				return;
			}

			// Fail fast rather than queue. A client waiting on a queued query cannot tell a busy server from a stuck
			// one, and the whole point of the bound is that the server stays predictable when it is at its limit.
			if (!permits.tryAcquire()) {
				// The one meter that says the bound in WireServerOptions is being hit rather than merely configured.
				metrics.recordQueryRejected("wire", "overloaded");
				fail(streamId, WireError.OVERLOADED,
					"All " + options.maxConcurrentStreams() + " query slots are in use; retry shortly", 0, 0);
				return;
			}

			Stream stream = new Stream(this, streamId, deadlineNanos(requestedDeadline), requestedDeadline,
				parsed.isWrite(), text, parameters);
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
				// Normally already given back with the terminal frame; this is the backstop for a run that never sent one.
				stream.releaseSlots();
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
			// Made encodable rather than refused: a message may quote the query that failed, and a lone surrogate in it
			// would otherwise throw here, on the way to the one frame that ends the stream, and leave the client waiting.
			WireValueCodec.encodeString(payload, WireValueCodec.wellFormed(message == null ? error.name() : message));
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
		/** Whether this query changes the store, which decides both the write gate and the watchdog's victim. */
		private final boolean writing;
		/** Kept for the slow query log, which needs the text a person wrote rather than the tree it parsed into. */
		private final String text;
		/** Kept for the slow query log, which logs the <b>names</b> only. See {@link SlowQueryLog}. */
		private final Map<String, Object> parameters;
		/** The deadline timer, the cancel handler and the producer's error path all race to end a stream. */
		private final AtomicBoolean terminated = new AtomicBoolean();

		/** Given back once, by whichever of the terminal frame or the completion callback gets there first. */
		private final AtomicBoolean permitReleased = new AtomicBoolean();
		/** The write gate while this stream holds it. Touched only by the producer thread. */
		private Semaphore heldGate;

		private volatile boolean cancelled;
		private volatile String abortReason;
		private volatile WireError abortCode = WireError.TIMEOUT;

		Stream(Connection connection, int streamId, long deadlineNanos, long budgetMillis, boolean writing,
			String text, Map<String, Object> parameters) {
			this.connection = connection;
			this.streamId = streamId;
			this.deadlineNanos = deadlineNanos;
			this.budgetMillis = budgetMillis;
			this.writing = writing;
			this.text = text;
			this.parameters = parameters;
		}

		void run(Ast.Query parsed, Map<String, Object> parameters) {
			BatchingSink sink = new BatchingSink(this);
			Semaphore gate = writing ? writeGate(connection.store) : null;
			try {
				if (gate != null) {
					// The engine serialises writers on one lock with no deadline and no message, so a second writer
					// would wait inside store.begin() past this stream's own budget with nothing to report. The
					// semaphore does not add serialisation — it puts a bound and a name on the wait that already
					// exists.
					boolean held = gate.tryAcquire(waitBudgetMillis(), TimeUnit.MILLISECONDS);
					if (held) {
						heldGate = gate;
					} else {
						error(WireError.OVERLOADED, "Another query is writing to database '" + connection.database
							+ "'; this one gave up waiting for it", 0, 0);
						return;
					}
				}
				Cypher.stream(connection.store, parsed, parameters, sink, this::abortReason, csv);
				sink.flush();
				complete(sink.rowsSent, sink.stats);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				error(WireError.INTERNAL, "Interrupted while waiting to write", 0, 0);
			} catch (CypherWriteException e) {
				// Cannot arise here — the server holds no read view — but mapping it keeps the failure legible if the
				// dispatch above ever changes.
				error(WireError.INTERNAL, e.getMessage(), 0, 0);
			} catch (QueryAbortedException e) {
				// Not a failure of the query and not a failure of the store: the answer was never wrong, it was
				// abandoned. Which of the three bounds fired decides the code.
				error(cancelled ? WireError.CANCELLED : abortCode, e.getMessage(), 0, 0);
			} catch (CypherSyntaxException e) {
				error(WireError.SYNTAX, e.getMessage(), e.line(), e.column());
			} catch (CypherTypeException e) {
				error(WireError.TYPE, e.getMessage(), 0, 0);
			} catch (ConstraintViolationException e) {
				// Before GraphStoreException, which it extends: this is the one store refusal a caller can act on.
				error(WireError.CONSTRAINT_VIOLATION, e.getMessage(), 0, 0);
			} catch (GraphStoreException e) {
				error(WireError.STORE, e.getMessage(), 0, 0);
			} catch (RuntimeException e) {
				logger.error("Stream {} failed", streamId, e);
				error(WireError.INTERNAL, String.valueOf(e.getMessage()), 0, 0);
			} finally {
				// Normally released already, with the terminal frame. Idempotent, so this only acts on a path that
				// never sent one.
				releaseSlots();
			}
		}

		/**
		 * Gives back the stream permit and, for a writer, the write gate — before the terminal frame is written.
		 *
		 * <p>
		 * <b>Before, and that order is the fix.</b> The permit used to come back in the completion callback, which runs
		 * on the event loop some time after the producer has written {@code COMPLETE}. A client that read it and sent
		 * its next query straight away reached the server inside that window and was answered {@code OVERLOADED} with
		 * a single stream open against a limit of one — a refusal a well-behaved client cannot avoid, only retry. The
		 * write gate had the same window for back-to-back writes. Nothing still needs either by the time a terminal
		 * frame goes out: the read view was closed and the transaction ended on the way out of {@code Cypher.stream}.
		 * </p>
		 */
		void releaseSlots() {
			Semaphore gate = heldGate;
			if (gate != null) {
				heldGate = null;
				gate.release();
			}
			if (permitReleased.compareAndSet(false, true)) {
				permits.release();
			}
		}

		/** How long to wait for the writer, bounded by this stream's own deadline so the two cannot disagree. */
		private long waitBudgetMillis() {
			if (deadlineNanos == 0) {
				return Long.MAX_VALUE;
			}
			return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
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

		void complete(long rows, CypherStats stats) {
			if (!terminated.compareAndSet(false, true)) {
				return;
			}
			long elapsed = System.nanoTime() - startedNanos;
			WireValueCodec.Writer payload = new WireValueCodec.Writer(QuerySummary.BYTES);
			new QuerySummary(rows, TimeUnit.NANOSECONDS.toMicros(elapsed),
				stats.nodesCreated(), stats.nodesDeleted(), stats.relationshipsCreated(),
				stats.relationshipsDeleted(), stats.propertiesSet(), stats.labelsAdded(), stats.labelsRemoved())
					.write(payload);
			// Everything the client may reuse is given back before the frame that tells it it may. See releaseSlots and
			// release.
			releaseSlots();
			release();
			write(Frames.COMPLETE, payload);
			// Recorded inside the CAS, so a stream ended twice by a race between the deadline timer and the producer
			// is counted once. That guarantee already existed for the frame; the meters get it for free.
			metrics.recordQuery("wire", writing ? "write" : "read", "ok", elapsed, rows);
			metrics.recordWriteStats(stats);
			SlowQueryLog.maybeLog(slowQueryMillis, "wire", connection.database, text, parameters, elapsed, rows, "ok",
				metrics);
		}

		void error(WireError code, String message, int line, int column) {
			if (!terminated.compareAndSet(false, true)) {
				return;
			}
			long elapsed = System.nanoTime() - startedNanos;
			releaseSlots();
			release();
			connection.fail(streamId, code, message, line, column);
			String outcome = outcomeOf(code);
			metrics.recordQuery("wire", writing ? "write" : "read", outcome, elapsed, 0);
			// An abandoned query is the most interesting line in a slow query log: it cost the most and returned
			// nothing.
			SlowQueryLog.maybeLog(slowQueryMillis, "wire", connection.database, text, parameters, elapsed, 0, outcome,
				metrics);
		}

		/**
		 * Frees the stream id before the terminal frame goes out, not afterwards.
		 *
		 * <p>
		 * A client is entitled to reuse an id the moment it has seen that stream end. Releasing it in the completion
		 * callback would leave a window in which the client's next query is refused as a duplicate — and a duplicate is
		 * a protocol error that closes the connection. Releasing it just <em>after</em> writing the frame, as this did,
		 * left the same window narrower rather than shut: {@code socket.write} from the producer only queues the frame,
		 * and a client fast enough to read it and reuse the id reached the event loop before the producer reached this
		 * line. {@code WireClientEndToEndTest.testAStreamIdReusedTheMomentItEndsIsAccepted} is that client.
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
		private CypherStats stats = CypherStats.NONE;

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
		public void stats(CypherStats stats) {
			this.stats = stats;
		}

		@Override
		public boolean row(List<Object> values) {
			for (Object value : values) {
				try {
					WireValueCodec.encode(writer, CypherWireValues.of(value));
				} catch (WireValueCodec.UnpairedSurrogateException e) {
					// A value the query made and the wire cannot carry: the query's problem, as a type error is, rather than
					// the server's. It used to leave as '?', which the embedded API and HTTP do not answer.
					throw new CypherTypeException("A result value cannot be sent: " + e.getMessage());
				}
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
