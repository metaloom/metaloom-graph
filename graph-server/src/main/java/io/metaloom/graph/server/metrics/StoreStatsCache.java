package io.metaloom.graph.server.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.StoreStats;
import io.vertx.core.Vertx;

/**
 * The cached half of the metrics, and the reason there are two halves.
 *
 * <p>
 * <b>{@code store.stats()} is not cheap.</b> It ends in {@code GraphStoreImpl.dictionaryBytes()}, which lists the
 * {@code dict/} directory and stats every file in it — which is why {@code GET /api/stats} already runs under
 * {@code executeBlocking}. A Prometheus scrape does not: {@code PrometheusScrapingHandler} collects gauges on the event
 * loop, so a gauge that called {@code stats()} would do filesystem I/O on an event loop, once per series, on every
 * scrape. At a fifteen-second scrape interval over three databases that is a blocked-thread warning every fifteen
 * seconds and a server that gets slower the more Prometheus watches it.
 * </p>
 *
 * <p>
 * So the counts are refreshed on a timer, off the event loop, and the scrape reads the last snapshot. The staleness
 * that introduces is real, so it is <b>exported</b> as {@code graph_store_stats_age_seconds} rather than hidden: a node
 * count that is up to fifteen seconds old is fine for a dashboard and would be misleading if nobody could tell.
 * </p>
 *
 * <p>
 * Everything else — {@code metrics()} and {@code snapshotStats()} — is read live on every scrape, because both are
 * in-memory counters and neither touches a file.
 * </p>
 */
public final class StoreStatsCache implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(StoreStatsCache.class);

	/** Half a typical scrape interval, so a scrape rarely sees the same snapshot twice. */
	public static final long DEFAULT_REFRESH_MILLIS = 15_000;

	private final Vertx vertx;

	private final GraphDatabases databases;

	private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

	private final long timerId;

	private static final class Snapshot {

		private volatile StoreStats stats;

		private volatile long takenNanos;
	}

	public StoreStatsCache(Vertx vertx, GraphDatabases databases) {
		this(vertx, databases, DEFAULT_REFRESH_MILLIS);
	}

	public StoreStatsCache(Vertx vertx, GraphDatabases databases, long refreshMillis) {
		this.vertx = vertx;
		this.databases = databases;
		this.timerId = vertx.setPeriodic(refreshMillis, id -> snapshots.keySet().forEach(this::refresh));
	}

	/**
	 * Starts keeping counts for a database, and refreshes them once immediately.
	 *
	 * <p>
	 * Idempotent, because the binder calls it from the request path the first time a database is queried and a
	 * database is usually queried more than once.
	 * </p>
	 */
	public void track(String database) {
		if (snapshots.putIfAbsent(database, new Snapshot()) == null) {
			refresh(database);
		}
	}

	/** The last snapshot taken, or null before the first refresh has completed. */
	public StoreStats stats(String database) {
		Snapshot snapshot = snapshots.get(database);
		return snapshot == null ? null : snapshot.stats;
	}

	/** How old {@link #stats} is, in seconds, or NaN when there has never been one. */
	public double ageSeconds(String database) {
		Snapshot snapshot = snapshots.get(database);
		if (snapshot == null || snapshot.stats == null) {
			return Double.NaN;
		}
		return (System.nanoTime() - snapshot.takenNanos) / (double) TimeUnit.SECONDS.toNanos(1);
	}

	private void refresh(String database) {
		Snapshot snapshot = snapshots.get(database);
		if (snapshot == null) {
			return;
		}
		vertx.<StoreStats>executeBlocking(() -> databases.database(database).stats(), false)
			.onSuccess(stats -> {
				snapshot.stats = stats;
				snapshot.takenNanos = System.nanoTime();
			})
			.onFailure(failure -> {
				// Left tracked rather than dropped, and the previous snapshot left in place. A database can be
				// unreadable for a moment; what tells the difference between that and a database that is gone is
				// graph_store_stats_age_seconds climbing, which is why it is exported.
				logger.debug("Could not refresh stats for database '{}': {}", database, failure.toString());
			});
	}

	@Override
	public void close() {
		vertx.cancelTimer(timerId);
		snapshots.clear();
	}
}
