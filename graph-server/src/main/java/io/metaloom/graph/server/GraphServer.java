package io.metaloom.graph.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.CypherResult;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.parse.CypherSyntaxException;
import io.metaloom.graph.server.demo.DemoData;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

/**
 * An HTTP front end: a Cypher endpoint and the browser UI that uses it.
 *
 * <pre>
 * java -jar graph-server-0.0.1-SNAPSHOT.jar /path/to/store 8080          # the shaded jar, everything included
 * java -jar graph-server-0.0.1-SNAPSHOT.jar --demo /tmp/demo 8080        # with a graph to look at
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
 * boundary, which is why {@link Cypher#query} materialises its result rather than streaming it.
 * </p>
 *
 * <h2>Read-only</h2>
 *
 * <p>
 * The query language is read-only, so this server cannot modify a store however it is addressed. That is worth
 * knowing before exposing it: it still reads everything, and it has no authentication. Bind it to localhost unless
 * something else is providing access control.
 * </p>
 */
public final class GraphServer implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(GraphServer.class);

	/** Past this, a query is abandoned rather than allowed to hold a worker thread and a read view indefinitely. */
	private static final long QUERY_TIMEOUT_MILLIS = 30_000;

	private final Vertx vertx;
	private final GraphStore store;
	private final boolean ownsStore;
	private final SchemaScanner schema;
	private HttpServer server;

	public GraphServer(GraphStore store) {
		this(Vertx.vertx(), store, false);
	}

	private GraphServer(Vertx vertx, GraphStore store, boolean ownsStore) {
		this.vertx = vertx;
		this.store = store;
		this.ownsStore = ownsStore;
		this.schema = new SchemaScanner(store);
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

		GraphStore store = GraphStore.open(options.store());
		seedIfAsked(store, options);

		GraphServer server = new GraphServer(Vertx.vertx(), store, true);
		int port = server.listen(options.port()).toCompletionStage().toCompletableFuture().join();
		logger.info("Graph server on http://localhost:{} over store {}", port, options.store());
		Runtime.getRuntime().addShutdownHook(new Thread(server::closeQuietly));
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
			logger.info("--demo: {} already holds {} nodes, leaving it alone",
				options.store(), store.stats().nodeCount());
			return;
		}
		logger.info("--demo: generating an air route network of {} airports in {}",
			options.demoAirports(), options.store());
		DemoData.seed(store, options.demoAirports());
	}

	public Future<Integer> listen(int port) {
		Router router = Router.router(vertx);
		router.route().handler(BodyHandler.create().setBodyLimit(1 << 20));

		router.post("/api/query").handler(this::query);
		router.get("/api/stats").handler(this::stats);
		router.get("/api/schema").handler(this::schema);

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

	private void query(RoutingContext context) {
		JsonObject body = context.body().asJsonObject();
		if (body == null || body.getString("query") == null || body.getString("query").isBlank()) {
			fail(context, 400, "empty", "Send {\"query\": \"MATCH (n) RETURN n LIMIT 25\"}");
			return;
		}
		String query = body.getString("query");
		Map<String, Object> parameters = body.getJsonObject("parameters", new JsonObject()).getMap();

		// executeBlocking, because a query is bounded by the data rather than by the request. Unordered, so two
		// queries from one browser do not serialise behind each other.
		vertx.<JsonObject>executeBlocking(() -> {
			long started = System.nanoTime();
			CypherResult result = Cypher.query(store, query, parameters);
			return ResultJson.of(result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
		}, false).onSuccess(json -> context.json(json))
			.onFailure(error -> respondToFailure(context, error));
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
	private void respondToFailure(RoutingContext context, Throwable error) {
		switch (error) {
			case CypherSyntaxException syntax ->
				fail(context, 400, "syntax", syntax.getMessage(), syntax.line(), syntax.column());
			case CypherTypeException type -> fail(context, 400, "type", type.getMessage());
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
		boolean refresh = "true".equals(context.request().getParam("refresh"));
		vertx.<JsonObject>executeBlocking(() -> schema.schema(refresh), false)
			.onSuccess(context::json)
			.onFailure(error -> respondToFailure(context, error));
	}

	private void stats(RoutingContext context) {
		vertx.<JsonObject>executeBlocking(() -> {
			StoreStats stats = store.stats();
			return new JsonObject()
				.put("nodes", stats.nodeCount())
				.put("relationships", stats.relationshipCount())
				.put("labels", stats.labelCount())
				.put("propertyKeys", stats.keyCount())
				.put("storageBytes", stats.storageBytes())
				.put("openViews", store.snapshotStats().openViews());
		}, false).onSuccess(context::json)
			.onFailure(error -> respondToFailure(context, error));
	}

	private static void fail(RoutingContext context, int status, String kind, String message) {
		fail(context, status, kind, message, 0, 0);
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
		<!doctype html><meta charset="utf-8"><title>Graph browser</title>
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
		if (server != null) {
			server.close().toCompletionStage().toCompletableFuture().join();
		}
		vertx.close().toCompletionStage().toCompletableFuture().join();
		if (ownsStore) {
			store.close();
		}
	}

	private void closeQuietly() {
		try {
			close();
		} catch (IOException e) {
			logger.warn("Shutdown was not clean", e);
		}
	}
}
