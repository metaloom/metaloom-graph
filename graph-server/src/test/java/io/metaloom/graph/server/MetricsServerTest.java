package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Vertx;

/**
 * The monitoring port, against a real port.
 *
 * <p>
 * The readiness behaviour is the part worth a test: {@code /ready} turning 503 <em>before</em> the listener closes is
 * what takes a pod out of a Service's endpoints in time, and nothing else in the suite would notice if it stopped
 * doing that.
 * </p>
 */
@Tag("unit")
public class MetricsServerTest {

	private Vertx vertx;
	private MetricsServer monitoring;
	private HttpClient client;
	private String base;
	private final AtomicBoolean ready = new AtomicBoolean(true);

	@BeforeEach
	public void start() {
		vertx = Vertx.vertx();
		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		registry.counter("graph_test_counter").increment();
		monitoring = new MetricsServer(vertx, registry, ready::get);
		int port = monitoring.listen(0).toCompletionStage().toCompletableFuture().join();
		base = "http://localhost:" + port;
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@AfterEach
	public void stop() {
		monitoring.closeQuietly();
		vertx.close().toCompletionStage().toCompletableFuture().join();
	}

	@Test
	public void testTheScrapeIsPrometheusText() throws Exception {
		HttpResponse<String> response = get("/metrics");
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("graph_test_counter_total"),
			"the registry handed in has to be the one that is scraped");
		assertTrue(response.body().contains("# TYPE"), "and it has to be Prometheus text format");
	}

	@Test
	public void testHealthIsUpWhileTheProcessIs() throws Exception {
		HttpResponse<String> response = get("/health");
		assertEquals(200, response.statusCode());
		// Liveness, not readiness: the only correct reaction to this failing is a restart, so it must not consult
		// anything that a busy or draining server would fail.
		ready.set(false);
		assertEquals(200, get("/health").statusCode(), "health must not follow readiness");
	}

	@Test
	public void testReadyTurns503BeforeTheListenerCloses() throws Exception {
		assertEquals(200, get("/ready").statusCode());

		ready.set(false);
		HttpResponse<String> draining = get("/ready");
		assertEquals(503, draining.statusCode(),
			"a draining server has to say so while it is still answering, or the Service keeps sending it traffic");
		assertTrue(draining.body().contains("shutting down"), "and say why");
	}

	private HttpResponse<String> get(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}
}
