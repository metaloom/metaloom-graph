package io.metaloom.graph.server;

import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.micrometer.PrometheusScrapingHandler;

/**
 * The monitoring port: {@code /metrics}, {@code /health} and {@code /ready}.
 *
 * <h2>Why it is not on the HTTP port</h2>
 *
 * <p>
 * A liveness probe must not share a listener with the thing it reports on. If {@code /health} lived beside
 * {@code /api/query}, then a query load heavy enough to saturate the HTTP server would make the probe time out, and
 * Kubernetes would respond by killing a server whose only problem was that it was busy — turning a slow database into a
 * restart loop. A separate {@link HttpServer} has its own accept path, so the probe answers while the query port is
 * flat out.
 * </p>
 *
 * <p>
 * It is also the port you do <b>not</b> publish. {@code /metrics} exposes database names, node counts and store sizes,
 * and like every other port here it has no authentication.
 * </p>
 *
 * <h2>health versus ready</h2>
 *
 * <p>
 * {@code /health} answers 200 if an event loop is running at all — it is a liveness probe, and the only correct
 * response to it failing is a restart. {@code /ready} answers 503 once shutdown has begun, which is what takes a pod
 * out of a Service's endpoints <em>before</em> the listener closes; without it, every rolling update drops the
 * connections that arrived in the gap between "stopped accepting" and "endpoint removed".
 * </p>
 */
public final class MetricsServer {

	private static final Logger logger = LoggerFactory.getLogger(MetricsServer.class);

	private final Vertx vertx;

	private final PrometheusMeterRegistry registry;

	private final BooleanSupplier ready;

	private HttpServer server;

	/**
	 * @param ready
	 *            what {@code /ready} reports. Supplied rather than read from a field so this class does not need to
	 *            know about {@link GraphServer}, which would otherwise be a cycle
	 */
	public MetricsServer(Vertx vertx, PrometheusMeterRegistry registry, BooleanSupplier ready) {
		this.vertx = vertx;
		this.registry = registry;
		this.ready = ready;
	}

	public Future<Integer> listen(int port) {
		Router router = Router.router(vertx);
		router.route("/metrics").handler(PrometheusScrapingHandler.create(registry));
		router.get("/health").handler(context -> context.response()
			.putHeader("content-type", "text/plain")
			.end("ok\n"));
		router.get("/ready").handler(context -> {
			boolean up = ready.getAsBoolean();
			context.response()
				.setStatusCode(up ? 200 : 503)
				.putHeader("content-type", "text/plain")
				.end(up ? "ready\n" : "shutting down\n");
		});
		server = vertx.createHttpServer();
		return server.requestHandler(router).listen(port).map(HttpServer::actualPort);
	}

	public int port() {
		return server.actualPort();
	}

	public Future<Void> close() {
		return server == null ? Future.succeededFuture() : server.close();
	}

	public void closeQuietly() {
		try {
			close().toCompletionStage().toCompletableFuture().join();
		} catch (RuntimeException failure) {
			logger.debug("Monitoring port did not close cleanly", failure);
		}
	}
}
