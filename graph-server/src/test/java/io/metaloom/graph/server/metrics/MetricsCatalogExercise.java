package io.metaloom.graph.server.metrics;

import java.util.Map;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.cypher.CypherStats;
import io.vertx.core.Vertx;

/**
 * Drives every meter in the catalogue at least once, through the <b>production</b> code that owns it.
 *
 * <p>
 * The important word is production. A test that registered the gauges itself would prove that Micrometer works and
 * nothing about whether {@link StoreMetricsBinder} binds what {@code spec/METRICS.md} says it binds — the exact drift
 * the catalogue test exists to catch. So this constructs the real binder against a real store, and calls the real
 * record methods.
 * </p>
 */
final class MetricsCatalogExercise {

	private MetricsCatalogExercise() {
	}

	/**
	 * @param database
	 *            a database that exists in {@code databases}, so the gauges bind against something real rather than
	 *            reporting NaN
	 */
	static void exerciseEverything(GraphMetrics metrics, Vertx vertx, GraphDatabases databases, String database) {
		try (StoreStatsCache cache = new StoreStatsCache(vertx, databases)) {
			StoreMetricsBinder binder = new StoreMetricsBinder(metrics, databases, cache);
			binder.bind(database);
			binder.bindServer(() -> 0, () -> 0, () -> 0L);

			// Every front end, so that a meter which exists only on one of them is still covered.
			for (String frontend : new String[] { "http", "wire", "mcp" }) {
				metrics.recordQuery(frontend, "read", "ok", 1_000_000L, 3);
				metrics.recordQuery(frontend, "write", "syntax", 0, 0);
				metrics.recordQueryRejected(frontend, "readonly");
				metrics.recordSlowQuery(frontend);
			}
			metrics.recordMcpToolCall("graph_query", "ok");
			// Non-zero on every component: the write counters are only registered when the value is above zero, so a
			// CypherStats.NONE here would leave all seven of them out of the scrape and out of the test's reach.
			metrics.recordWriteStats(new CypherStats(1, 1, 1, 1, 1, 1, 1));

			// The cache refreshes on a timer and on track(), both asynchronous. Waiting is what makes
			// graph_store_stats_age_seconds report a number rather than NaN — and a NaN gauge is omitted from a
			// Prometheus scrape entirely, which would make this test fail for a reason that is not a real defect.
			awaitFirstRefresh(cache, database);
		}
	}

	private static void awaitFirstRefresh(StoreStatsCache cache, String database) {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
		while (cache.stats(database) == null && System.nanoTime() - deadline < 0) {
			java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
		}
	}

	/** The parameter map the slow query log is handed, kept here so both tests describe the same shape of query. */
	static Map<String, Object> parameters() {
		return Map.of("city", "Bonn", "since", 2019);
	}
}
