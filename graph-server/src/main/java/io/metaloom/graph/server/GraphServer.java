package io.metaloom.graph.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.CompositeIndex;
import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.api.StoreFormat;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.nativelib.NativeCapabilities;
import io.metaloom.graph.cli.dump.GraphBackup;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.CypherResult;
import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.cypher.QueryAbort;
import io.metaloom.graph.cypher.QueryAbortedException;
import io.metaloom.graph.cypher.RowSink;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.io.CsvAccessException;
import io.metaloom.graph.cypher.io.CsvFormatException;
import io.metaloom.graph.cypher.io.CsvSource;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;
import io.metaloom.graph.server.demo.DemoData;
import io.metaloom.graph.server.io.SandboxedFileCsvSource;
import io.metaloom.graph.server.mcp.McpConstants;
import io.metaloom.graph.server.mcp.McpJsonRpcHandler;
import io.metaloom.graph.server.mcp.McpServer;
import io.metaloom.graph.server.mcp.tool.GraphMcpTools;
import io.metaloom.graph.server.auth.AuthPolicy;
import io.metaloom.graph.server.auth.Bootstrap;
import io.metaloom.graph.server.auth.AuthRoutes;
import io.metaloom.graph.server.metrics.GraphMetrics;
import io.metaloom.graph.server.metrics.MicrometerGraphMetrics;
import io.metaloom.graph.server.metrics.StoreMetricsBinder;
import io.metaloom.graph.server.metrics.StoreStatsCache;
import io.metaloom.graph.server.wire.WireServer;
import io.metaloom.graph.server.wire.WireServerOptions;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.micrometer.MicrometerMetricsFactory;
import io.vertx.micrometer.MicrometerMetricsOptions;

/**
 * An HTTP front end: a Cypher endpoint and the browser UI that uses it.
 *
 * <pre>
 * java -jar graph-server.jar /path/to/store 8080          # the shaded jar, everything included
 * java -jar graph-server.jar --demo /tmp/demo 8080        # with a graph to look at
 * mvn -pl graph-server exec:java -Dexec.args="--demo /tmp/demo"          # from the reactor
 * </pre>
 *
 * <h2>Threading</h2>
 *
 * <p>
 * <b>Queries run on a worker thread, never on the event loop.</b> A Cypher query over an unindexed pattern is a full
 * scan of the store — bounded by the data, not by the request — and running that on an event loop would stall every
 * other connection behind it. Vert.x would eventually log a blocked-thread warning; the user would just see a server
 * that stopped responding.
 * </p>
 *
 * <p>
 * The store's read views are thread-confined, which fits: each query gets a worker thread, opens its view there, and
 * closes it before the thread is returned. What is <em>not</em> safe is holding a view across an asynchronous
 * boundary — so a result is never handed out lazily, and this route materialises before it answers.
 * </p>
 *
 * <h2>The timeout, and why it needs {@link io.metaloom.graph.cypher.QueryAbort}</h2>
 *
 * <p>
 * {@code executeBlocking} cannot interrupt a task that has already started. A route that merely gave up waiting would
 * abandon the <em>answer</em> while the scan kept its worker thread and its read view for as long as the data took —
 * which is the failure a timeout exists to prevent, not a fix for it. So the query is run through
 * {@link Cypher#stream} with a deadline the engine itself consults while it scans, and a query past its budget stops
 * where it is and gives the thread and the snapshot back.
 * </p>
 *
 * <h2>Writes, and why they are off by default</h2>
 *
 * <p>
 * The query language can write, so this server can modify a store — but only when it was started with
 * {@code --allow-write}. Off is the only defensible default: there is no authentication on this port, it binds every
 * interface, and the difference between handing out a copy of somebody's data and handing out the ability to delete it
 * is worth an explicit act. A write query on a server without the flag is refused with 403 before it reaches a worker
 * thread.
 * </p>
 *
 * <p>
 * Reading everything is still unauthenticated whatever the flag says. Bind it to localhost unless something else is
 * providing access control.
 * </p>
 */
public final class GraphServer implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(GraphServer.class);

	private final Vertx vertx;
	private final GraphDatabases databases;
	private final String defaultDatabase;
	private final boolean ownsDatabases;
	/** One scanner per database, made on first use. A scan is cached, and the cache is per store. */
	private final Map<String, SchemaScanner> schemas = new ConcurrentHashMap<>();
	/** Whether DELETE /api/databases/{name} exists at all. Off unless an operator asked for it. */
	private final boolean allowDrop;
	/** Whether a query may change a store. Off unless an operator asked for it. */
	private final boolean allowWrite;

	/** Where LOAD CSV may read. Refuses everything unless {@link #csvSource} has been called. */
	private CsvSource csv = CsvSource.DENY;

	/** Cached from {@link #csv}, so the event-loop refusal is a field read rather than a comparison. */
	private boolean allowLoadCsv;

	/** Whether GET /api/databases/{name}/backup exists. Off unless an operator asked for it. */
	private boolean allowBackup;

	/**
	 * Who a request is from, and whether that is even asked.
	 *
	 * <p>
	 * {@link AuthPolicy#disabled()} until {@link #authPolicy} says otherwise, so every constructor and every existing
	 * embedder gets the behaviour it had before authentication existed. {@link #main} is the only caller that turns it
	 * on, which is what keeps "on by default" a property of the command line rather than of the API.
	 * </p>
	 */
	private AuthPolicy auth = AuthPolicy.disabled();
	/** Past this, a query is abandoned rather than allowed to hold a worker thread and a read view indefinitely. */
	private final long queryTimeoutMillis;
	/** How long an abandoned query is given to unwind before the stores are closed regardless. */
	private static final long ABORT_UNWIND_MILLIS = 2_000;
	private HttpServer server;
	/** Set by {@link #main} when the binary port is on, so that one shutdown hook closes both front ends. */
	private WireServer wire;
	/** Set once shutdown begins: new queries are refused rather than accepted and then abandoned. */
	private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
	/**
	 * Set once the grace period has run out: from here a running query is told to stop.
	 *
	 * <p>
	 * Its own flag rather than {@link #closing}. The abort signal composed into every query used to read {@code closing},
	 * so every query in flight was abandoned the instant shutdown began and the grace period protected nobody — a
	 * query a millisecond from its answer got a 503.
	 * </p>
	 */
	private volatile boolean abandoning;
	/** Queries on a worker thread right now. The store may not be closed while this is above zero. */
	private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
	/**
	 * Where this front end records. {@link GraphMetrics#NOOP} unless {@link #main} turned monitoring on, so that an
	 * embedder constructing a server directly gets no meters and no Micrometer on its hot path.
	 */
	private GraphMetrics metrics = GraphMetrics.NOOP;
	/** Binds a database's meters the first time it is resolved. Null when monitoring is off. */
	private StoreMetricsBinder binder;
	/** Owned only when {@link #main} made it, and cancelled on shutdown so its timer does not outlive the stores. */
	private StoreStatsCache statsCache;
	/** Set by {@link #main} when the monitoring port is on, so that one shutdown hook closes every front end. */
	private MetricsServer monitoring;
	/** Set by {@link #main} when the MCP port is on. Off by default — see {@link McpServer}. */
	private McpServer mcp;
	/**
	 * Past this a query is written to the slow query log. Zero — off — for an embedded server, because a library that
	 * logged warnings about its caller's queries without being asked would be a poor guest. {@link #main} sets it.
	 */
	private long slowQueryMillis;

	public GraphServer(GraphDatabases databases) {
		this(databases, GraphDatabases.DEFAULT_NAME);
	}

	public GraphServer(GraphDatabases databases, String defaultDatabase) {
		this(Vertx.vertx(), databases, defaultDatabase, false,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, false, false);
	}

	/** @param queryTimeoutMillis how long one query may run, or 0 for no limit */
	public GraphServer(GraphDatabases databases, String defaultDatabase, long queryTimeoutMillis, boolean allowDrop) {
		this(Vertx.vertx(), databases, defaultDatabase, false, queryTimeoutMillis, allowDrop, false);
	}

	public GraphServer(GraphDatabases databases, String defaultDatabase, long queryTimeoutMillis, boolean allowDrop,
		boolean allowWrite) {
		this(Vertx.vertx(), databases, defaultDatabase, false, queryTimeoutMillis, allowDrop, allowWrite);
	}

	private GraphServer(Vertx vertx, GraphDatabases databases, String defaultDatabase, boolean ownsDatabases,
		long queryTimeoutMillis, boolean allowDrop, boolean allowWrite) {
		this.vertx = vertx;
		this.databases = databases;
		this.defaultDatabase = defaultDatabase;
		this.ownsDatabases = ownsDatabases;
		this.queryTimeoutMillis = queryTimeoutMillis;
		this.allowDrop = allowDrop;
		this.allowWrite = allowWrite;
	}

	/**
	 * Enables {@code LOAD CSV}, against a source that decides which files it may read.
	 *
	 * <p>
	 * A setter rather than a constructor parameter, for the same reason {@code slowQueryMillis} is one: the
	 * constructors are a public surface several tests and embedders already use, and this is off in all of them. Not
	 * calling it leaves {@link CsvSource#DENY} in place, which is the only safe default for a port with no
	 * authentication — see {@code SandboxedFileCsvSource} for what the enabled case then permits.
	 * </p>
	 */
	/**
	 * Enables the backup route.
	 *
	 * <p>
	 * A setter for the same reason {@link #csvSource} is one, and off in every constructor: handing out a whole
	 * database in one request from an unauthenticated port is not something an embedder should get by default.
	 * </p>
	 */
	public void allowBackup(boolean allowBackup) {
		this.allowBackup = allowBackup;
	}

	/**
	 * Requires a login on this front end.
	 *
	 * <p>
	 * A setter for the same reason {@link #csvSource} and {@link #allowBackup} are: the four constructors are a public
	 * surface that several tests and embedders already use, and adding a parameter to them would make every one of
	 * those call sites choose something about authentication. Not calling this leaves every request anonymous and
	 * permitted, which is what those callers already had.
	 * </p>
	 */
	public void authPolicy(AuthPolicy policy) {
		this.auth = policy == null ? AuthPolicy.disabled() : policy;
	}

	/** The policy, for the wire and MCP front ends that share this server's identity store. */
	public AuthPolicy authPolicy() {
		return auth;
	}

	public void csvSource(CsvSource csv) {
		this.csv = csv == null ? CsvSource.DENY : csv;
		this.allowLoadCsv = this.csv != CsvSource.DENY;
	}

	public static void main(String[] args) throws Exception {
		GraphServerOptions options;
		try {
			options = GraphServerOptions.parse(args);
		} catch (GraphServerOptions.HelpRequested help) {
			System.out.println(GraphServerOptions.usage());
			return;
		} catch (IllegalArgumentException invalid) {
			System.err.println(invalid.getMessage());
			System.err.println();
			System.err.println(GraphServerOptions.usage());
			System.exit(2);
			return;
		}

		GraphDatabases databases;
		try {
			databases = GraphDatabases.open(options.root());
		} catch (GraphStoreException refused) {
			// The one-store-per-directory layout is gone, and a directory holding a store is not a root of them.
			// Refused with the commands to fix it rather than migrated: moving somebody's data on their behalf, on a
			// disk that might be full, is not a decision a start-up path gets to make.
			System.err.println(refused.getMessage());
			System.exit(2);
			return;
		}
		if (!databases.exists(options.database())) {
			databases.create(options.database());
			logger.info("Created database '{}'", options.database());
		}
		seedIfAsked(databases.database(options.database()), options);

		// Before any port binds. A server that opened its listeners and then discovered it had nobody who could log in
		// would be answering requests it could not serve, and a refusal here is one an operator can act on.
		AuthPolicy authPolicy = AuthPolicy.disabled();
		if (options.auth()) {
			AuthStore identities = AuthStore.open(options.root());
			Bootstrap.ensureAdministrator(identities, options.initialPassword(), System.out);
			authPolicy = AuthPolicy.enabled(identities, options.sessionTimeoutMillis());
			logger.info("Authentication is on. The identity file is {}", identities.path());
		} else {
			logger.warn("Authentication is OFF (--no-auth). Every request is anonymous and may do anything the "
				+ "capability flags allow.");
		}

		// The registry is built BEFORE Vert.x and handed to it. That order is the whole trick: Vert.x instruments its
		// own event loops, pools and HTTP servers into whatever registry it is given at construction, and a registry
		// created afterwards gets the application's meters and none of the runtime's.
		PrometheusMeterRegistry registry = null;
		GraphMetrics metrics = GraphMetrics.NOOP;
		Vertx vertx;
		if (options.metrics()) {
			registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
			new ClassLoaderMetrics().bindTo(registry);
			new JvmMemoryMetrics().bindTo(registry);
			new JvmGcMetrics().bindTo(registry);
			new JvmThreadMetrics().bindTo(registry);
			new ProcessorMetrics().bindTo(registry);
			new UptimeMetrics().bindTo(registry);
			// This store memory-maps a file per segment, so file descriptors are a real limit here rather than a
			// generic JVM statistic — running out of them is a documented way for the store to stop opening.
			new FileDescriptorMetrics().bindTo(registry);
			metrics = new MicrometerGraphMetrics(registry);
			// setJvmMetricsEnabled(false) because the bindings above are already registered; leaving it on registers
			// a second, differently named copy of every JVM meter.
			vertx = Vertx.builder()
				.with(new VertxOptions().setMetricsOptions(
					new MicrometerMetricsOptions().setEnabled(true).setJvmMetricsEnabled(false)))
				.withMetrics(new MicrometerMetricsFactory(registry))
				.build();
		} else {
			// One Vert.x for every front end. They serve the same databases in the same process and there is nothing
			// to be gained from several sets of event loops competing for the same cores.
			vertx = Vertx.vertx();
		}

		GraphServer server = new GraphServer(vertx, databases, options.database(), true,
			options.queryTimeoutMillis(), options.allowDrop(), options.allowWrite());
		server.slowQueryMillis = options.slowQueryMillis();
		server.authPolicy(authPolicy);
		server.allowBackup(options.allowBackup());
		if (options.allowLoadCsv()) {
			// Constructed here rather than lazily, so an unreadable or missing import directory is a start-up failure
			// naming the path — not a 500 on the first query somebody tries months later.
			server.csvSource(new SandboxedFileCsvSource(options.importDir()));
			logger.info("LOAD CSV enabled, reading only under {}", options.importDir().toRealPath());
		}
		if (options.slowQueryMillis() > 0 && options.queryTimeoutMillis() > 0
			&& options.slowQueryMillis() >= options.queryTimeoutMillis()) {
			// A warning and not a refusal: --query-timeout=0 is legal and this combination is merely useless, not
			// wrong. Silence would mean an operator who set both waits for lines that can never be written.
			logger.warn("--slow-query-millis ({}) is not below --query-timeout ({}), so no query can ever be logged "
				+ "slow: it is abandoned first", options.slowQueryMillis(), options.queryTimeoutMillis());
		}
		if (registry != null) {
			server.metrics = metrics;
			StoreStatsCache cache = new StoreStatsCache(vertx, databases);
			server.binder = new StoreMetricsBinder(metrics, databases, cache);
			server.statsCache = cache;
		}

		int port = server.listen(options.port()).toCompletionStage().toCompletableFuture().join();
		logger.info("Graph server on http://localhost:{} over {} ({} database(s), default '{}')",
			port, options.root(), databases.names().size(), options.database());

		if (options.wire()) {
			WireServer wire = new WireServer(vertx, databases, options.database(), WireServerOptions.defaults()
				.withHost(options.wireHost())
				.withPort(options.wirePort())
				.withMaxConcurrentStreams(options.maxConcurrentStreams())
				.withQueryTimeoutMillis(options.queryTimeoutMillis())
				.withTls(options.wireTls())
				.withPlaintext(options.wirePlaintext()), options.allowWrite());
			// The same source object, so there is one sandbox for the process rather than one per front end.
			wire.csvSource(server.csv);
			wire.listen().toCompletionStage().toCompletableFuture().join();
			if (wire.port() >= 0) {
				logger.info("Binary streaming port on {}:{} — see graph-client", wire.options().host(), wire.port());
			}
			if (wire.tlsPort() >= 0) {
				logger.info("Binary streaming port over TLS on {}:{} — graph-client needs"
					+ " ClientOptions.withTls(...)", options.wireTls().host(), wire.tlsPort());
				if (wire.port() >= 0) {
					// Said once, at the level an operator reads, because the reason to add a certificate is almost
					// always to stop credentials crossing in the clear and the plaintext listener is still doing it.
					// Single braces: SLF4J substitutes only the empty pair, so a metric name carrying a label is
					// safe here — and doubling them, which is String.format's escape, prints them doubled.
					logger.warn("The binary port also still accepts unencrypted connections on {}:{}. Pass"
						+ " --wire-tls-only once every client has moved; graph_wire_connections{tls=\"false\"}"
						+ " counts the ones that have not", wire.options().host(), wire.port());
				}
			}
			// The same policy object as the HTTP front end, so one identity store backs every port and a reset is seen
			// by all of them at once.
			wire.authPolicy(server::authPolicy);
			wire.instrument(metrics, options.slowQueryMillis());
			server.wire = wire;
		}

		if (options.mcp()) {
			McpServer mcp = new McpServer(vertx,
				new McpJsonRpcHandler(GraphMcpTools.all(vertx, databases, options.database(), options.allowWrite(),
					options.queryTimeoutMillis()), options.allowWrite(), metrics, options.database()),
				options.mcpHost());
			mcp.authPolicy(server::authPolicy);
			int mcpPort = mcp.listen(options.mcpPort()).toCompletionStage().toCompletableFuture().join();
			logger.info("MCP on http://{}:{}{} (and {}) — {}", options.mcpHost(), mcpPort, McpConstants.SSE_PATH,
				McpConstants.WEBSOCKET_PATH, options.allowWrite() ? "writable" : "read-only");
			server.mcp = mcp;
		}

		if (registry != null) {
			// Bound after the other front ends exist, so the gauges can read them. They resolve through `server`
			// rather than closing over the objects, so the null case before one is created is a branch, not a race.
			server.binder.bindServer(server.inFlight::get,
				() -> server.wire == null ? 0 : server.wire.runningStreams(),
				() -> server.wire == null ? 0L : server.wire.bufferedBytes());
			metrics.bindGauge("graph_mcp_sessions", null,
				() -> Integer.valueOf(server.mcp == null ? 0 : server.mcp.sessionCount()));

			MetricsServer monitoring = new MetricsServer(vertx, registry, () -> !server.isClosing());
			int metricsPort = monitoring.listen(options.metricsPort())
				.toCompletionStage().toCompletableFuture().join();
			logger.info("Monitoring on http://localhost:{}/metrics, /health and /ready", metricsPort);
			server.monitoring = monitoring;
		}

		// Named, because a thread dump taken during a slow shutdown should say what is holding it. The hook is the
		// only shutdown path for a server killed with SIGTERM, which is how every container runtime stops one.
		Runtime.getRuntime().addShutdownHook(new Thread(server::closeQuietly, "graph-server-shutdown"));
	}

	/**
	 * The one write path in this application, and it is opt-in and runs once.
	 *
	 * <p>
	 * <b>Only into an empty store.</b> Seeding on top of existing data would work — nothing in the generator deletes
	 * anything — and that is exactly why it is refused: invented routes silently mixed into somebody's real graph is
	 * not a mistake they would notice quickly. A store that already has nodes is left alone and told about, which
	 * also makes {@code --demo} safe to leave in a start script.
	 * </p>
	 */
	private static void seedIfAsked(GraphStore store, GraphServerOptions options) throws IOException {
		if (!options.demo()) {
			return;
		}
		if (!DemoData.isEmpty(store)) {
			logger.info("--demo: database '{}' already holds {} nodes, leaving it alone",
				options.database(), store.stats().nodeCount());
			return;
		}
		logger.info("--demo: generating an air route network of {} airports in database '{}'",
			options.demoAirports(), options.database());
		DemoData.seed(store, options.demoAirports());
	}

	public Future<Integer> listen(int port) {
		Router router = Router.router(vertx);
		// First of all: once shutdown has begun every request is refused, because the listener now stays bound through
		// the grace period so that the queries in flight can be answered (see shutdown). A backup or a drop started in
		// that window would otherwise run against stores about to close.
		router.route().handler(context -> {
			if (!closing.get()) {
				context.next();
				return;
			}
			if ("/api/query".equals(context.normalizedPath())) {
				metrics.recordQueryRejected("http", "shutdown");
			}
			context.response().putHeader("connection", "close");
			fail(context, 503, "shutdown", "The server is shutting down and is not accepting requests");
		});
		router.route().handler(BodyHandler.create().setBodyLimit(1 << 20));

		// The CSRF half that SameSite cannot cover: with --no-auth there is no cookie to withhold. A form can POST
		// text/plain, urlencoded or multipart cross-origin without a preflight, and a text/plain body can be valid
		// JSON; application/json cannot be sent cross-origin without one. PUT and DELETE always need a preflight.
		router.post("/api/*").handler(GraphServer::requireJson);

		// Before every other /api route, because Vert.x matches in order and a filter that ran after them would not be
		// one. Mounted on /api/* only: the login page is served by StaticHandler from this same origin, and a filter
		// over /* would refuse the bundle to a browser that has not logged in yet — a blank page, no error, nothing in
		// the log.
		new AuthRoutes(this::authPolicy, vertx).mount(router);

		router.post("/api/query").handler(this::query);
		router.get("/api/stats").handler(this::stats);
		router.get("/api/schema").handler(this::schema);
		// Plural for the collection, and the name in the path for one of them. Everything else takes ?database=,
		// because scoping a query to a database is a parameter of the query rather than a different resource.
		router.get("/api/databases").handler(this::listDatabases);
		router.put("/api/databases/:name").handler(this::createDatabase);
		router.delete("/api/databases/:name").handler(this::dropDatabase);

		// The declared composite indexes of one database. A collection, so it takes ?database= like everything else,
		// and one of them is named by its two path segments — the pair is the identity, not a generated id.
		// Registered before BodyHandler would matter and streamed by hand: a dump is not a JSON body and the 1 MiB
		// body limit above has nothing to do with it.
		router.get("/api/databases/:name/backup").handler(this::backupDatabase);
		router.get("/api/indexes").handler(this::listIndexes);
		router.post("/api/indexes").handler(this::createIndex);
		router.delete("/api/indexes/:label/:key").handler(this::dropIndex);

		if (uiIsBundled()) {
			// Cached in production, never in a build tree: an edited UI file should show up on reload.
			router.route("/*").handler(StaticHandler.create("webroot").setCachingEnabled(false).setIndexPage("index.html"));
		} else {
			// The UI is a React bundle built by npm during `mvn package`. A build with -Dui.skip=true, or on a machine
			// with no npm, produces a working API and no bundle — so say which one this is instead of serving a 404
			// that looks like the server is broken.
			logger.warn("No UI bundle on the classpath: the API is up, the browser UI is not. See graph-server/README");
			router.route("/*").handler(context -> context.response()
				.putHeader("content-type", "text/html; charset=utf-8")
				.setStatusCode(503)
				.end(NO_UI_PAGE));
		}

		server = vertx.createHttpServer();
		return server.requestHandler(router).listen(port).map(HttpServer::actualPort);
	}

	public int port() {
		return server.actualPort();
	}

	// ---------------------------------------------------------------- handlers

	/**
	 * The request body as a JSON object, or null after answering 400.
	 *
	 * <p>
	 * {@code body().asJsonObject()} throws on text that is not JSON and on JSON that is not an object, and an exception
	 * out of a handler is the router's 500 — "our fault, retry" for a request that can never succeed, logged as if
	 * something had broken. Every route that reads a body reads it through this.
	 * </p>
	 */
	private static JsonObject jsonBody(RoutingContext context, String example) {
		Object parsed;
		try {
			io.vertx.core.buffer.Buffer raw = context.body().buffer();
			parsed = raw == null || raw.length() == 0 ? null : io.vertx.core.json.Json.decodeValue(raw);
		} catch (io.vertx.core.json.DecodeException malformed) {
			fail(context, 400, "json", "The body is not JSON. Send " + example);
			return null;
		}
		if (!(parsed instanceof JsonObject object)) {
			fail(context, 400, "json", "The body has to be a JSON object. Send " + example);
			return null;
		}
		return object;
	}

	private void query(RoutingContext context) {
		String example = "{\"query\": \"MATCH (n) RETURN n LIMIT 25\"}";
		JsonObject body = jsonBody(context, example);
		if (body == null) {
			return;
		}
		// Types checked rather than trusted: getString turns a number into its text and getJsonObject throws on an
		// array, which reached the router as a 500.
		Object text = body.getValue("query");
		Object rawParameters = body.getValue("parameters");
		if (!(text instanceof String queryText) || queryText.isBlank()) {
			fail(context, 400, "empty", "Send " + example);
			return;
		}
		if (rawParameters != null && !(rawParameters instanceof JsonObject)) {
			fail(context, 400, "parameters", "\"parameters\" has to be a JSON object of name to value, not "
				+ rawParameters.getClass().getSimpleName());
			return;
		}
		Map<String, Object> parameters = rawParameters == null ? Map.of() : ((JsonObject) rawParameters).getMap();

		// Parsed here, on the event loop, because parsing is pure and cheap and running is neither. A syntax error is
		// much the most common failure and now costs no worker thread and no read view.
		Ast.Query parsed;
		try {
			parsed = Cypher.parse(body.getString("query"));
		} catch (RuntimeException e) {
			// Counted, with no duration, because it never ran: a syntax error is much the most common failure and a
			// dashboard that could not see it would show a server answering nothing and failing nothing.
			metrics.recordQuery("http", "unknown", outcomeOf(e), 0, 0);
			respondToFailure(context, e);
			return;
		}

		// What the query needs, before what the server permits. Both have to say yes, and this order is what decides
		// which refusal a caller sees: somebody who simply lacks MERGE is told that, rather than being advised to
		// restart the server with --allow-write, which is not a change they can make and would not help if they did.
		for (Privilege privilege : parsed.requiredPrivileges()) {
			if (!permit(context, privilege)) {
				return;
			}
		}

		// Refused here, on the event loop, for the same reason a syntax error is: the tree already knows whether the
		// query writes, so a refusal costs no worker thread, no transaction and no writer lock.
		if (parsed.isWrite() && !allowWrite) {
			// A rejection rather than an outcome: it cost nothing, and counting it as a fast query would drag the
			// duration histogram towards zero — a server refusing every write would look like one answering instantly.
			metrics.recordQueryRejected("http", "readonly");
			fail(context, 403, "readonly", "This server does not accept write queries. Start it with --allow-write "
				+ "to enable CREATE, SET, REMOVE and DELETE.");
			return;
		}

		// The same shape as the write refusal above, and needed because it is NOT the same question: a
		// `LOAD CSV ... RETURN row` creates nothing, so isWrite() is false for it and the check above lets it through.
		// Without this, a server deliberately left read-only would read files for anyone who can reach the port.
		if (parsed.readsFiles() && !allowLoadCsv) {
			metrics.recordQueryRejected("http", "loadcsv");
			fail(context, 403, "loadcsv", "This server does not read files. Start it with --allow-load-csv and "
				+ "--import-dir=<path> to enable LOAD CSV, which may then read only what is under that directory.");
			return;
		}

		if (closing.get()) {
			// 503 with Connection: close, so a client behind a load balancer is taken out of rotation rather than
			// retried onto a server that is going away.
			metrics.recordQueryRejected("http", "shutdown");
			context.response().putHeader("connection", "close");
			fail(context, 503, "shutdown", "The server is shutting down and is not accepting queries");
			return;
		}

		String database = nameFor(context);

		// executeBlocking, because a query is bounded by the data rather than by the request. Unordered, so two
		// queries from one browser do not serialise behind each other.
		inFlight.incrementAndGet();
		vertx.<JsonObject>executeBlocking(() -> {
			// Resolved only now: after the privilege check, so a missing name answers 404 only to somebody who may
			// see it (otherwise 403 vs 404 lists the databases), and on a worker, because the first open of a store
			// maps its segments and holds the GraphDatabases monitor while it does.
			GraphStore store = storeFor(context);
			long started = System.nanoTime();
			QueryAbort deadline = queryTimeoutMillis <= 0
				? QueryAbort.NEVER
				: QueryAbort.deadline(started + TimeUnit.MILLISECONDS.toNanos(queryTimeoutMillis),
					queryTimeoutMillis);
			// Composed rather than replaced: a query keeps its own budget and gains a second reason to stop. Without
			// this a query with --query-timeout=0 would hold the store open for as long as it liked, and the shutdown
			// would have nothing to do but wait for it.
			deadline = deadline.or(this::shutdownReason);
			// The streaming form even though this route materialises anyway: it is the one path through the engine,
			// and it is the only one that can be stopped. `executeBlocking` cannot interrupt a task that has started,
			// so without a cooperative check a timeout here would abandon the answer while the scan kept its thread
			// and its snapshot — which is the thing the timeout exists to prevent.
			String kind = parsed.isWrite() ? "write" : "read";
			try {
				Collector collector = new Collector();
				Cypher.stream(store, parsed, parameters, collector, deadline, csv);
				CypherResult result = new CypherResult(collector.columns, collector.rows, collector.stats);
				if (result.stats().containsUpdates()) {
					// The schema is a 60-second cache over a full scan, so without this the sidebar would keep
					// reporting labels and counts from before the write for up to a minute — and the UI has no other
					// reason to re-fetch it.
					SchemaScanner scanner = schemas.get(database);
					if (scanner != null) {
						scanner.invalidate();
					}
				}
				long elapsed = System.nanoTime() - started;
				metrics.recordQuery("http", kind, "ok", elapsed, result.size());
				metrics.recordWriteStats(result.stats());
				SlowQueryLog.maybeLog(slowQueryMillis, "http", database, body.getString("query"), parameters,
					elapsed, result.size(), "ok", metrics);
				return ResultJson.of(result, TimeUnit.NANOSECONDS.toMillis(elapsed));
			} catch (RuntimeException failure) {
				// Recorded here rather than in respondToFailure, because this is where the clock is. An abandoned
				// query is the single most interesting thing in a slow query log — it is the one that cost the most
				// and returned nothing — so it is logged on this path too.
				long elapsed = System.nanoTime() - started;
				String outcome = outcomeOf(failure);
				metrics.recordQuery("http", kind, outcome, elapsed, 0);
				SlowQueryLog.maybeLog(slowQueryMillis, "http", database, body.getString("query"), parameters,
					elapsed, 0, outcome, metrics);
				throw failure;
			}
		}, false)
			.onSuccess(json -> context.json(json))
			.onFailure(error -> respondToFailure(context, error))
			// After the response is written, not before: shutdown closes the listener once this reaches zero, and a
			// count that fell first could let it close the connection under an answer not yet handed to it.
			.onComplete(outcome -> inFlight.decrementAndGet());
	}

	/** Why a running query should stop: once shutdown has run out of grace, and null until then. */
	private String shutdownReason() {
		return abandoning ? "the server is shutting down" : null;
	}

	/** Gathers a streamed result back into the materialised one this route answers with. */
	private static final class Collector implements RowSink {

		private List<String> columns = List.of();
		private final List<List<Object>> rows = new ArrayList<>();
		private CypherStats stats = CypherStats.NONE;

		@Override
		public void columns(List<String> columns) {
			this.columns = columns;
		}

		@Override
		public boolean row(List<Object> values) {
			rows.add(values);
			return true;
		}

		@Override
		public void stats(CypherStats stats) {
			this.stats = stats;
		}
	}

	/**
	 * Maps a failure onto a status the browser can act on.
	 *
	 * <p>
	 * The distinction that matters to a user writing a query is between "your query is wrong" and "the server broke",
	 * so a syntax or type error is a 400 carrying the engine's own message — including, for a syntax error, the caret
	 * line that names the position. Anything else is a 500 and gets logged, because it is the server's problem.
	 * </p>
	 */
	/**
	 * The same short reason {@link #respondToFailure} puts in the error body, as a metric label.
	 *
	 * <p>
	 * Deliberately next to that method and using the same words: a dashboard that grouped failures differently from
	 * the way the API reports them would make "the browser says syntax, the graph says internal" a real conversation.
	 * The label set is closed and small, which is what keeps it a usable Prometheus label — an exception class name
	 * would be neither.
	 * </p>
	 */
	private static String outcomeOf(Throwable error) {
		return switch (error) {
			case CypherSyntaxException ignored -> "syntax";
			case CypherTypeException ignored -> "type";
			case QueryAbortedException ignored -> "timeout";
			case NoSuchDatabaseException ignored -> "database";
			case ConstraintViolationException ignored -> "constraint";
			case GraphStoreException ignored -> "store";
			case IllegalArgumentException ignored -> "argument";
			default -> "internal";
		};
	}

	private void respondToFailure(RoutingContext context, Throwable error) {
		switch (error) {
			// 403 and not 400: the query is well formed and this server declines to serve it. The message never says
			// whether the file exists — see SandboxedFileCsvSource on why that distinction is not offered.
			case CsvAccessException refused -> fail(context, 403, "loadcsv", refused.getMessage());
			// 400: the file is the caller's input, and a malformed one is their mistake to fix. The message carries a
			// line number, which is the only thing that makes a million-row file actionable.
			case CsvFormatException malformed -> fail(context, 400, "csv", malformed.getMessage());
			case CypherSyntaxException syntax ->
				fail(context, 400, "syntax", syntax.getMessage(), syntax.line(), syntax.column());
			case CypherTypeException type -> fail(context, 400, "type", type.getMessage());
			// 503 rather than 500: the query was not wrong and the server is not broken, it declined to keep
			// spending on this one. Retrying a narrower query is the useful advice, so the engine's message —
			// which names the budget — is passed through rather than replaced.
			case QueryAbortedException aborted -> fail(context, 503, "timeout", aborted.getMessage());
			// 404 and not 409: the name is wrong, so listing or creating is the next step. A 409 would say the store
			// refused and invite a retry that cannot work.
			case NoSuchDatabaseException missing -> fail(context, 404, "database", missing.getMessage());
			// 404 for the same reason, one resource down: the pair is not declared, so listing is the next step and
			// a retry of this request never becomes right.
			case NoSuchIndexException missing -> fail(context, 404, "index", missing.getMessage());
			case IllegalArgumentException invalid -> fail(context, 400, "database", invalid.getMessage());
			// 409 and named separately from a generic store failure: a unique constraint is about the data the caller
			// sent meeting data that is already there, and the message names the key, the value and the node holding
			// it — which is what makes the next request fixable.
			case ConstraintViolationException conflict -> fail(context, 409, "constraint", conflict.getMessage());
			case GraphStoreException store -> fail(context, 409, "store", store.getMessage());
			default -> {
				logger.error("Query failed", error);
				fail(context, 500, "internal", String.valueOf(error.getMessage()));
			}
		}
	}

	/**
	 * The labels and relationship types in the store, with counts.
	 *
	 * <p>
	 * A scan, cached — see {@link SchemaScanner}. {@code ?refresh=true} forces a fresh one, which is what the UI's
	 * rescan button sends.
	 * </p>
	 */
	private void schema(RoutingContext context) {
		// INTROSPECT rather than MATCH: this is a scan of every label and relationship type in the store, which is a
		// different disclosure from a query somebody wrote themselves.
		if (!permit(context, Privilege.INTROSPECT)) {
			return;
		}
		boolean refresh = "true".equals(context.request().getParam("refresh"));
		SchemaScanner scanner;
		try {
			scanner = scannerFor(context);
		} catch (RuntimeException e) {
			respondToFailure(context, e);
			return;
		}
		vertx.<JsonObject>executeBlocking(() -> scanner.schema(refresh), false)
			.onSuccess(context::json)
			.onFailure(error -> respondToFailure(context, error));
	}

	private void stats(RoutingContext context) {
		if (!permit(context, Privilege.INTROSPECT)) {
			return;
		}
		GraphStore store;
		String name;
		try {
			name = nameFor(context);
			store = storeFor(context);
		} catch (RuntimeException e) {
			respondToFailure(context, e);
			return;
		}
		vertx.<JsonObject>executeBlocking(() -> {
			StoreStats stats = store.stats();
			return new JsonObject()
				.put("database", name)
				.put("nodes", stats.nodeCount())
				.put("relationships", stats.relationshipCount())
				.put("labels", stats.labelCount())
				.put("propertyKeys", stats.keyCount())
				.put("storageBytes", stats.storageBytes())
				.put("openViews", store.snapshotStats().openViews())
				// The store format version. A store this server has open is always at the one its build reads, so this
				// is that build's number; `metaloom-graph-cli database info` reads it off a store without opening it.
				.put("storeFormatVersion", StoreFormat.CURRENT_VERSION)
				// Which of the two paths in spec/NATIVE_FFM.md section 4 this process is on: "ABI 2" when the Rust
				// crate loaded, "not built" or "disabled" otherwise. The store works either way, which is exactly
				// why it is worth being able to ask — and in a GraalVM binary it is the only way to find out.
				.put("nativeLayer", NativeCapabilities.status());
		}, false).onSuccess(context::json)
			.onFailure(error -> respondToFailure(context, error));
	}

	// ---------------------------------------------------------------- databases

	/**
	 * Which database this request is about.
	 *
	 * <p>
	 * A parameter rather than a path segment, because scoping a query to a database is a property of the query and not
	 * a different resource — {@code /api/query?database=sales} rather than {@code /api/databases/sales/query}. The
	 * collection at {@code /api/databases} is the exception, because there it genuinely is the resource.
	 * </p>
	 */
	private String nameFor(RoutingContext context) {
		String requested = context.request().getParam("database");
		return requested == null || requested.isBlank() ? defaultDatabase : requested;
	}

	private GraphStore storeFor(RoutingContext context) {
		return lookup(nameFor(context));
	}

	/**
	 * Whether this request may do {@code privilege} to the database it named.
	 *
	 * <p>
	 * Answers the refusal itself and returns false, so a handler reads
	 * {@code if (!permit(context, Privilege.MATCH)) { return; }} — one line, and the same line everywhere. With
	 * authentication off the principal is {@code ANONYMOUS} and this is always true, so the shape does not change
	 * between the two configurations.
	 * </p>
	 *
	 * <p>
	 * <b>This is not the whole gate.</b> The capability flags are checked separately and first; see
	 * {@code spec/AUTH.md} section 5 for why a grant can never widen what the flags allow.
	 * </p>
	 */
	private boolean permit(RoutingContext context, Privilege privilege) {
		return permit(context, privilege, nameFor(context));
	}

	private boolean permit(RoutingContext context, Privilege privilege, String database) {
		if (AuthRoutes.principal(context).may(privilege, database)) {
			return true;
		}
		metrics.recordQueryRejected("http", "forbidden");
		AuthRoutes.forbidden(context, privilege, database);
		return false;
	}

	private SchemaScanner scannerFor(RoutingContext context) {
		String name = nameFor(context);
		GraphStore store = lookup(name);
		// One scanner per database and not per request: a scan is O(nodes + edges) and its whole value is the cache
		// in front of it, which a fresh scanner would throw away every time the sidebar refreshed.
		return schemas.computeIfAbsent(name, ignored -> new SchemaScanner(store));
	}

	private GraphStore lookup(String name) {
		try {
			GraphStore store = databases.database(name);
			// Bound here, from the one place a database is resolved, rather than eagerly for every directory under
			// the root: a root holding fifty databases of which two are used should not open the other forty-eight
			// in order to report that nothing is happening in them. Idempotent, so the cost is one set lookup.
			if (binder != null) {
				binder.bind(name);
			}
			return store;
		} catch (GraphStoreException e) {
			// A legal name that is not there: 404, and listing or creating is the next step.
			throw new NoSuchDatabaseException(e.getMessage());
		} catch (IOException e) {
			throw new GraphStoreException("Could not open database '" + name + "': " + e.getMessage());
		}
		// An IllegalArgumentException propagates and becomes a 400. The distinction is worth keeping: 400 says the
		// name is not a name, 404 says there is no such database, and a UI reacts to those differently — one is a
		// typo in a field, the other is a database somebody dropped.
	}

	/**
	 * Every database under the root, with the counts a picker wants to show.
	 *
	 * <p>
	 * The counts come from stores that are already open; a database nobody has touched is listed without them rather
	 * than opened to find out. Listing a root of fifty databases should not map fifty sets of segment files.
	 * </p>
	 *
	 * <h2>Filtered, not refused</h2>
	 *
	 * <p>
	 * A principal sees the databases they hold any privilege on, and the route itself needs none. That is why there is
	 * no {@code LIST_DATABASES} privilege: refusing the whole list would leave a picker with nothing to draw and tell
	 * the caller nothing they could act on, while filtering answers the question they actually asked — which
	 * databases are there <em>for me</em>. It also means the existence of a database is not disclosed to somebody who
	 * cannot use it.
	 * </p>
	 */
	private void listDatabases(RoutingContext context) {
		Principal principal = AuthRoutes.principal(context);
		vertx.<JsonObject>executeBlocking(() -> {
			JsonArray array = new JsonArray();
			for (String name : databases.names()) {
				if (!visibleTo(principal, name)) {
					continue;
				}
				JsonObject entry = new JsonObject()
					.put("name", name)
					.put("default", name.equals(defaultDatabase))
					.put("open", databases.isOpen(name));
				if (databases.isOpen(name)) {
					StoreStats stats = databases.database(name).stats();
					entry.put("nodes", stats.nodeCount()).put("relationships", stats.relationshipCount());
				}
				array.add(entry);
			}
			return new JsonObject()
				.put("databases", array)
				.put("default", defaultDatabase)
				.put("allowDrop", allowDrop)
				// The UI has to be told, or it offers an editor whose every write comes back 403.
				.put("allowWrite", allowWrite);
		}, false).onSuccess(context::json).onFailure(error -> respondToFailure(context, error));
	}

	/**
	 * Whether a principal holds any privilege at all on a database.
	 *
	 * <p>
	 * Any, rather than a specific one: the question a picker asks is "is this database mine to work with", and
	 * somebody granted only {@code BACKUP} on it still needs to see that it is there. Public because {@code
	 * graph_list_databases} answers the same question and must not answer it differently.
	 * </p>
	 */
	public static boolean visibleTo(Principal principal, String database) {
		for (Privilege privilege : Privilege.values()) {
			if (!privilege.isGlobalOnly() && principal.may(privilege, database)) {
				return true;
			}
		}
		return false;
	}

	private void createDatabase(RoutingContext context) {
		String name = context.pathParam("name");
		// Scoped to the name being created, so a grant can name a database agreed in advance rather than having to be
		// a licence to create any database at all.
		if (!permit(context, Privilege.CREATE_DATABASE, name)) {
			return;
		}
		vertx.<JsonObject>executeBlocking(() -> {
			GraphDatabases.validate(name);
			databases.create(name);
			logger.info("Created database '{}'", name);
			return new JsonObject().put("name", name).put("created", true);
		}, false).onSuccess(created -> context.response().setStatusCode(201)
			.putHeader("content-type", "application/json").end(created.encode()))
			.onFailure(error -> respondToFailure(context, error));
	}

	/**
	 * Deletes a database and everything in it.
	 *
	 * <p>
	 * <b>Off unless {@code --allow-drop} was given.</b> This is the only route in the server that destroys data, on a
	 * port with no authentication, and the query language it sits beside cannot delete a single node. Making it
	 * opt-in is the least that difference deserves.
	 * </p>
	 */
	private void dropDatabase(RoutingContext context) {
		String name = context.pathParam("name");
		if (!permit(context, Privilege.DROP_DATABASE, name)) {
			return;
		}
		if (!allowDrop) {
			fail(context, 403, "forbidden", "This server was started without --allow-drop, so it will not delete a"
				+ " database. Stop it and remove the directory, or restart it with the flag.");
			return;
		}
		vertx.<JsonObject>executeBlocking(() -> {
			GraphDatabases.validate(name);
			if (!databases.drop(name)) {
				throw new NoSuchDatabaseException("No database '" + name + "'");
			}
			schemas.remove(name);
			logger.warn("Dropped database '{}' and everything in it", name);
			return new JsonObject().put("name", name).put("dropped", true);
		}, false).onSuccess(dropped -> context.json(dropped))
			.onFailure(error -> respondToFailure(context, error));
	}

	/**
	 * Streams a database out as a dump, while it is being used.
	 *
	 * <p>
	 * <b>Its own flag.</b> {@code --allow-backup} rather than {@code --allow-write}, because a backup is a read that
	 * hands over <em>every byte</em> of a database in one request, on a port with no authentication. That is a
	 * different decision from "answer queries", in exactly the way {@code --allow-drop} is a different decision from
	 * "accept writes", and folding it into either would mean an operator enabling one had silently enabled this.
	 * </p>
	 *
	 * <p>
	 * <b>The whole walk runs inside one {@code executeBlocking} body, on one thread.</b> It has to: the read view that
	 * makes the dump a single point in time is thread-confined, so a walk that hopped threads would silently start
	 * reading current data instead. This is the one route in this server that streams while holding a view — every
	 * other one materialises first, for the reason this class's javadoc gives — and it is safe here only because the
	 * view never leaves the worker.
	 * </p>
	 *
	 * <p>
	 * The query deadline deliberately does <b>not</b> apply. It exists to stop a runaway query from holding a worker
	 * and a view; a backup holds both on purpose, for as long as the data takes, and thirty seconds is not a bound
	 * anybody wants on a backup. What it costs is stated in spec/BACKUP.md rather than hidden behind a timeout.
	 * </p>
	 */
	private void backupDatabase(RoutingContext context) {
		if (!permit(context, Privilege.BACKUP, context.pathParam("name"))) {
			return;
		}
		if (!allowBackup) {
			metrics.recordQueryRejected("http", "backup");
			fail(context, 403, "backup", "This server does not serve backups. Start it with --allow-backup, which is "
				+ "separate from --allow-write because a backup hands over every byte of a database in one request "
				+ "and no port here has authentication.");
			return;
		}
		String name = context.pathParam("name");
		GraphStore store;
		try {
			GraphDatabases.validate(name);
			// Through lookup, like every other route: a missing database is 404. Opened directly it surfaced as the
			// store's own GraphStoreException, a 409 "store" that invites a retry which can never succeed.
			store = lookup(name);
		} catch (RuntimeException e) {
			respondToFailure(context, e);
			return;
		}

		HttpServerResponse response = context.response();
		response.putHeader("content-type", "application/octet-stream");
		response.putHeader("content-disposition", "attachment; filename=\"" + name + ".dump\"");
		// Chunked, because the size is not known until the walk finishes and buffering a database in memory to find
		// it out would defeat the point of streaming.
		response.setChunked(true);

		vertx.<GraphBackup.Summary>executeBlocking(() -> {
			try (OutputStream out = new ResponseStream(response)) {
				return GraphBackup.write(store, name, out, System.currentTimeMillis());
			}
			// Unordered: a backup is long, and making it share an ordered queue with other blocking work would let one
			// backup delay every schema scan behind it.
		}, false).onSuccess(summary -> {
			// Sent as headers rather than appended to the body: the body IS the dump, and a summary inside it would
			// have to be parsed past by every reader. Headers are written before the first chunk goes out.
			response.putTrailer("x-graph-nodes", String.valueOf(summary.nodes()));
			response.putTrailer("x-graph-relationships", String.valueOf(summary.relationships()));
			response.putTrailer("x-graph-lightweight-edges", String.valueOf(summary.lightweightEdges()));
			response.putTrailer("x-graph-snapshot-lsn", String.valueOf(summary.snapshotLsn()));
			response.end();
			logger.info("Backed up '{}': {} nodes, {} relationships, {} lightweight edges, {} bytes",
				name, summary.nodes(), summary.relationships(), summary.lightweightEdges(), summary.bytes());
		}).onFailure(error -> {
			logger.error("Backup of '{}' failed", name, error);
			if (response.headWritten()) {
				// Bytes are already on the wire, so there is no status left to change. Killing the connection is the
				// only way to tell the client the dump is incomplete — and it must be told, because a truncated dump
				// that arrived with a 200 would look like a valid short one until its checksum was checked.
				response.reset();
			} else {
				respondToFailure(context, error);
			}
		});
	}

	/** Vert.x's response as an OutputStream, with backpressure honoured on the worker thread that is filling it. */
	private static final class ResponseStream extends OutputStream {

		private final HttpServerResponse response;

		ResponseStream(HttpServerResponse response) {
			this.response = response;
		}

		@Override
		public void write(int b) {
			write(new byte[] { (byte) b }, 0, 1);
		}

		@Override
		public void write(byte[] bytes, int off, int len) {
			// Copied, because the dump writer reuses its buffer and Buffer.buffer(byte[]) wraps rather than copies.
			response.write(Buffer.buffer(java.util.Arrays.copyOfRange(bytes, off, off + len)));
			// The whole point of doing this on a worker: when the socket cannot keep up, this thread waits instead of
			// queueing the rest of a database in memory. An event loop could not block here, which is the other reason
			// the walk is not on one.
			while (response.writeQueueFull()) {
				// Woken by the drain rather than polling on a fixed sleep. The handler is set after the check, so a
				// drain landing in between is missed — hence the re-check, and a bounded wait as the backstop.
				java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
				response.drainHandler(ignored -> drained.countDown());
				if (!response.writeQueueFull()) {
					break;
				}
				try {
					drained.await(50, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new java.io.UncheckedIOException(new IOException("Interrupted writing the backup"));
				}
			}
		}

		@Override
		public void close() {
			// The response is ended by the success handler, which is also where the trailers go.
		}
	}

	// ---------------------------------------------------------------- composite indexes

	/**
	 * The declared {@code (label, key)} indexes of one database.
	 *
	 * <p>
	 * Not part of {@code /api/schema}, and the reason is that they are different questions answered at different
	 * costs. The schema is a <b>scan</b> of the data, cached for a minute because it is O(nodes + edges); the declared
	 * indexes are a read of one catalog page and are exact right now. Folding them together would either put a
	 * one-page read behind a minute of cache or make a panel that lists three declarations pay for a full scan.
	 * </p>
	 *
	 * <p>
	 * {@code allowWrite} rides along for the same reason the database list carries it: a UI that cannot know whether
	 * this server accepts writes offers buttons whose every click comes back 403.
	 * </p>
	 */
	private void listIndexes(RoutingContext context) {
		if (!permit(context, Privilege.INTROSPECT)) {
			return;
		}
		GraphStore store;
		String name;
		try {
			name = nameFor(context);
			store = storeFor(context);
		} catch (RuntimeException e) {
			respondToFailure(context, e);
			return;
		}
		vertx.<JsonObject> executeBlocking(() -> {
			JsonArray indexes = new JsonArray();
			for (CompositeIndex index : store.compositeIndexes()) {
				indexes.add(new JsonObject()
					.put("label", index.label())
					.put("key", index.key())
					.put("unique", index.unique()));
			}
			JsonArray constraints = new JsonArray();
			for (String key : store.uniqueConstraints()) {
				constraints.add(key);
			}
			return new JsonObject()
				.put("database", name)
				.put("indexes", indexes)
				// The store-wide constraints beside them, because "no two nodes share an email" and "no two :Person
				// do" are the same decision made at two scopes, and a panel that showed only one of them would read
				// as if the other did not exist.
				.put("uniqueConstraints", constraints)
				.put("allowWrite", allowWrite);
		}, false).onSuccess(context::json).onFailure(error -> respondToFailure(context, error));
	}

	/**
	 * Declares a composite index, optionally as a label-scoped unique constraint.
	 *
	 * <p>
	 * <b>Behind {@code --allow-write}</b>, because it writes: the declaration goes on the catalog page and the
	 * backfill scan puts an entry in the tree for every live node that satisfies the pair. It is a smaller write than
	 * a Cypher {@code CREATE}, but it is not a read, and this port has no authentication.
	 * </p>
	 *
	 * <p>
	 * A duplicate declaration is a 200 rather than a 409: {@code createIndex} is idempotent by design, and answering
	 * "conflict" would invite a retry that changes nothing. Declaring uniqueness over data that already breaks it is
	 * the real conflict, and that arrives as the 409 {@link ConstraintViolationException} already maps to.
	 * </p>
	 */
	private void createIndex(RoutingContext context) {
		if (!permit(context, Privilege.CREATE_INDEX)) {
			return;
		}
		String example = "{\"label\": \"Person\", \"key\": \"email\", \"unique\": false}";
		JsonObject body = jsonBody(context, example);
		if (body == null) {
			return;
		}
		Object rawLabel = body.getValue("label");
		Object rawKey = body.getValue("key");
		Object rawUnique = body.getValue("unique");
		if (!(rawLabel instanceof String label) || label.isBlank() || !(rawKey instanceof String key) || key.isBlank()
			|| rawUnique != null && !(rawUnique instanceof Boolean)) {
			fail(context, 400, "empty", "Send " + example);
			return;
		}
		if (!allowWrite) {
			fail(context, 403, "forbidden", "This server was started without --allow-write, so it will not declare"
				+ " an index. Restart it with the flag.");
			return;
		}
		boolean unique = Boolean.TRUE.equals(rawUnique);
		GraphStore store;
		try {
			store = storeFor(context);
		} catch (RuntimeException e) {
			respondToFailure(context, e);
			return;
		}
		vertx.<JsonObject> executeBlocking(() -> {
			if (unique) {
				store.createUniqueConstraint(label, key);
			} else {
				store.createIndex(label, key);
			}
			logger.info("Declared a composite index on :{}.{}{}", label, key, unique ? " (unique)" : "");
			return new JsonObject().put("label", label).put("key", key).put("unique", unique).put("created", true);
		}, false).onSuccess(context::json).onFailure(error -> respondToFailure(context, error));
	}

	/**
	 * Drops a declared composite index and every entry it owns.
	 *
	 * <p>
	 * Behind {@code --allow-write} rather than {@code --allow-drop}: this destroys an index, which the store can
	 * rebuild from the data with one call, and not a database, which nothing can bring back. The flags are about how
	 * much a mistake costs.
	 * </p>
	 *
	 * <p>
	 * If the declaration is a label-scoped unique constraint, that goes with it — the two are one declaration
	 * ([INDEX.md §6]). The UI says so before it sends this.
	 * </p>
	 */
	private void dropIndex(RoutingContext context) {
		if (!permit(context, Privilege.DROP_INDEX)) {
			return;
		}
		String label = context.pathParam("label");
		String key = context.pathParam("key");
		if (!allowWrite) {
			fail(context, 403, "forbidden", "This server was started without --allow-write, so it will not drop an"
				+ " index. Restart it with the flag.");
			return;
		}
		GraphStore store;
		try {
			store = storeFor(context);
		} catch (RuntimeException e) {
			respondToFailure(context, e);
			return;
		}
		vertx.<JsonObject> executeBlocking(() -> {
			if (!store.hasIndex(label, key)) {
				throw new NoSuchIndexException("No index on :" + label + "." + key);
			}
			store.dropIndex(label, key);
			logger.info("Dropped the composite index on :{}.{}", label, key);
			return new JsonObject().put("label", label).put("key", key).put("dropped", true);
		}, false).onSuccess(context::json).onFailure(error -> respondToFailure(context, error));
	}

	private static void fail(RoutingContext context, int status, String kind, String message) {
		fail(context, status, kind, message, 0, 0);
	}

	/**
	 * Refuses a POST whose body is not declared as JSON, 415.
	 *
	 * <p>
	 * Logout is exempt: it carries no body, the UI sends none, and ending a session is not something a forged request
	 * gains from.
	 * </p>
	 */
	private static void requireJson(RoutingContext context) {
		if ("/api/logout".equals(context.normalizedPath())) {
			context.next();
			return;
		}
		String type = context.request().getHeader("content-type");
		String media = type == null ? "" : type.split(";", 2)[0].trim();
		if (media.equalsIgnoreCase("application/json")) {
			context.next();
			return;
		}
		fail(context, 415, "contenttype", "Send the body as Content-Type: application/json"
			+ (type == null ? "" : ", not '" + type + "'") + ".");
	}

	private static void fail(RoutingContext context, int status, String kind, String message, int line, int column) {
		context.response().setStatusCode(status).putHeader("content-type", "application/json")
			.end(new JsonObject()
				.put("error", kind)
				.put("message", message)
				.put("line", line)
				.put("column", column)
				.encode());
	}

	// ---------------------------------------------------------------- ui

	/** The bundle is a build output, so whether it exists is a fact about this jar rather than about the source tree. */
	private static boolean uiIsBundled() {
		return GraphServer.class.getResource("/webroot/index.html") != null;
	}

	private static final String NO_UI_PAGE = """
		<!doctype html><meta charset="utf-8"><title>MetaLoom // Graph</title>
		<style>body{font:14px system-ui;margin:3rem auto;max-width:40rem;color:#dfe6ee;background:#10151b}
		code{background:#1e2732;padding:.1rem .35rem;border-radius:3px}</style>
		<h1>No UI bundle in this build</h1>
		<p>The Cypher API is running. The browser UI was not built into this jar, which happens when the build ran
		with <code>-Dui.skip=true</code> or on a machine without <code>npm</code>.</p>
		<p>Build it with <code>mvn -pl graph-server package</code>, or run the API directly:</p>
		<pre>curl -XPOST localhost:8080/api/query -H 'content-type: application/json' \\
		  -d '{"query":"MATCH (n) RETURN n LIMIT 10"}'</pre>
		""";

	// ---------------------------------------------------------------- lifecycle

	@Override
	public void close() throws IOException {
		// Sessions go first and unconditionally: they are in-memory credentials, and a shutdown that failed part way
		// through should not leave them behind for whatever inherits the process.
		auth.close();
		shutdown(WireServer.DEFAULT_GRACE_MILLIS);
	}

	/**
	 * Stops serving, lets running queries finish, and only then closes the stores.
	 *
	 * <p>
	 * <b>The order is the safety property.</b> A store's segments are memory-mapped, and closing one unmaps them — so a
	 * worker still inside a query at that moment is reading an address the process no longer owns. Refusing new work
	 * first is what makes the set of in-flight queries shrink; waiting is what lets it reach zero; and the abort signal
	 * is what stops a query with no deadline from holding the process open indefinitely, which would turn a shutdown
	 * into a hang and get the server killed with {@code -9} instead.
	 * </p>
	 *
	 * <p>
	 * <b>Nothing committed is at risk regardless.</b> The log is fsynced before any data file is touched, so a write
	 * interrupted at any instant either committed or did not. What this buys is not durability — that is the log's job —
	 * but a clean close: a checkpoint written, the log trimmed, the {@code LOCK} released, and no client left holding a
	 * half-sent result with no explanation.
	 * </p>
	 *
	 * <p>
	 * Idempotent, because it is reachable from both the shutdown hook and an ordinary {@code close()} and a JVM exiting
	 * on a signal runs both.
	 * </p>
	 */
	public void shutdown(long graceMillis) throws IOException {
		if (!closing.compareAndSet(false, true)) {
			return;
		}
		long graceEnds = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMillis);
		if (wire != null) {
			// First, and with the same grace period: it holds worker threads of its own against the same stores.
			wire.shutdown(graceMillis);
		}
		// The HTTP listener is NOT closed here. Closing a Vert.x HttpServer closes the connections of requests still
		// being answered, so a query that finished inside the grace period had nobody left to answer: its client got
		// an EOF. It stays bound until the queries are done; meanwhile the flag answers every new request 503 (see
		// listen).
		if (mcp != null) {
			// With the other listeners: an agent mid-conversation should be told the server is going rather than
			// have its stream cut when the stores close.
			mcp.closeQuietly();
		}
		if (statsCache != null) {
			// Before the stores close, because its timer opens databases. A refresh landing after close() would be a
			// blocking call against an unmapped segment — which is the one failure mode this whole ordering exists
			// to prevent.
			statsCache.close();
		}
		if (monitoring != null) {
			// Last of the listeners rather than first: /ready has been answering 503 since the flag was set, which is
			// what takes this pod out of a Service's endpoints, and it should keep saying so for as long as possible.
			monitoring.closeQuietly();
		}

		// What is left of the grace period after the binary port used its share: one deadline for the process, not one
		// per front end.
		if (!awaitQuiet(Math.max(0, TimeUnit.NANOSECONDS.toMillis(graceEnds - System.nanoTime())))) {
			logger.warn("Shutting down with {} quer{} still running after {} ms; abandoning {}", inFlight.get(),
				inFlight.get() == 1 ? "y" : "ies", graceMillis, inFlight.get() == 1 ? "it" : "them");
			// The signal is composed into every running query's abort, and only now does it say stop. Give them a moment
			// to notice it and unwind — each one closes its own read view or rolls back its own transaction on the way out.
			abandoning = true;
			if (!awaitQuiet(ABORT_UNWIND_MILLIS)) {
				logger.warn("{} quer{} did not unwind; closing the stores anyway", inFlight.get(),
					inFlight.get() == 1 ? "y has" : "ies have");
			}
		}
		if (server != null) {
			server.close().toCompletionStage().toCompletableFuture().join();
		}

		vertx.close().toCompletionStage().toCompletableFuture().join();
		if (ownsDatabases) {
			// Last. Every reader is gone by now, or has been given every chance to be.
			databases.close();
		}
	}

	/** @return true once no query is on a worker thread, false if the deadline passed first */
	private boolean awaitQuiet(long millis) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		while (inFlight.get() > 0) {
			if (System.nanoTime() - deadline >= 0) {
				return false;
			}
			java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		}
		return true;
	}

	/** Whether this server has begun shutting down, and is therefore refusing new queries. */
	public boolean isClosing() {
		return closing.get();
	}

	private void closeQuietly() {
		try {
			logger.info("Shutting down: refusing new queries, then waiting up to {} ms for the running ones",
				WireServer.DEFAULT_GRACE_MILLIS);
			close();
			logger.info("Shutdown complete; every store is checkpointed and unlocked");
		} catch (IOException e) {
			// Logged rather than rethrown: an exception out of a shutdown hook is printed by the JVM with no context
			// and stops the remaining hooks from running.
			logger.warn("Shutdown was not clean", e);
		} catch (RuntimeException e) {
			logger.warn("Shutdown failed", e);
		}
	}
}
