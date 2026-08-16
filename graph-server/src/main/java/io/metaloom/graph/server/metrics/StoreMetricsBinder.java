package io.metaloom.graph.server.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.SnapshotStats;
import io.metaloom.graph.core.api.StoreMetrics;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.id.Kind;

/**
 * Turns the engine's four snapshot records into meters.
 *
 * <p>
 * This is where {@code spec/OPERATIONS.md} §4 stops being a table a human reads during an incident and becomes
 * something an alert rule can evaluate. Every signal named there has a meter here, and §6 of {@code spec/METRICS.md}
 * maps one to the other.
 * </p>
 *
 * <h2>Two rules that are not obvious</h2>
 *
 * <p>
 * <b>A supplier resolves its store inside the lambda</b>, never over a captured handle. A database can be dropped and
 * recreated while the server runs; a gauge holding the old {@code GraphStore} would then report, forever, on a closed
 * store — plausible numbers from a store nobody is using, which is worse than no numbers.
 * </p>
 *
 * <p>
 * <b>Every supplier is wrapped so it cannot throw.</b> Micrometer collects the whole registry into one response, so a
 * single gauge throwing does not lose one series, it loses the entire scrape — and the store legitimately throws when a
 * database has just been dropped. {@link Double#NaN} is the honest answer and Prometheus already understands it as "no
 * sample".
 * </p>
 */
public final class StoreMetricsBinder {

	private final GraphMetrics metrics;

	private final GraphDatabases databases;

	private final StoreStatsCache cache;

	/** Databases already bound. Micrometer is idempotent by name and tags, but the suppliers are not free to build. */
	private final Set<String> bound = ConcurrentHashMap.newKeySet();

	public StoreMetricsBinder(GraphMetrics metrics, GraphDatabases databases, StoreStatsCache cache) {
		this.metrics = metrics;
		this.databases = databases;
		this.cache = cache;
	}

	/**
	 * Binds every per-database meter, once.
	 *
	 * <p>
	 * Called lazily, from the first request that touches a database, rather than eagerly for every directory under the
	 * root. A root with fifty databases of which two are in use should not open the other forty-eight to report that
	 * nothing is happening in them.
	 * </p>
	 */
	public void bind(String database) {
		if (!bound.add(database)) {
			return;
		}
		cache.track(database);

		// ---- counts, from the cached tier. See StoreStatsCache for why these are not read live.
		stat(database, "graph_store_nodes", StoreStats::nodeCount);
		stat(database, "graph_store_relationships", StoreStats::relationshipCount);
		stat(database, "graph_store_adjacency_cells", StoreStats::adjacencyCellCount);
		stat(database, "graph_store_property_cells", StoreStats::propertyCellCount);
		stat(database, "graph_store_blob_extents", StoreStats::blobExtentCount);
		stat(database, "graph_store_index_pages", StoreStats::indexPageCount);
		stat(database, "graph_store_labels", StoreStats::labelCount);
		stat(database, "graph_store_property_keys", StoreStats::keyCount);
		stat(database, "graph_store_segment_files", StoreStats::segmentFileCount);
		stat(database, "graph_store_record_bytes", StoreStats::recordBytes);
		stat(database, "graph_store_bytes", StoreStats::storageBytes);
		// The staleness of everything above, so that a count nobody can date is never presented as if it were live.
		metrics.bindGauge("graph_store_stats_age_seconds", database, () -> cache.ageSeconds(database));

		// ---- durability and the log, read live: all in-memory counters.
		storeMetric(database, "graph_store_wal_lag_bytes", StoreMetrics::walLagBytes);
		storeMetric(database, "graph_store_wal_bytes_since_checkpoint", StoreMetrics::walBytesSinceCheckpoint);
		storeMetric(database, "graph_store_unflushed_pages", StoreMetrics::unflushedPages);
		storeMetric(database, "graph_store_checkpoint_millis_avg", StoreMetrics::averageCheckpointMillis);
		storeMetric(database, "graph_store_recovery_millis", StoreMetrics::lastRecoveryMillis);

		// ---- readers and the retention horizon, read live.
		snapshot(database, "graph_store_open_views", SnapshotStats::openViews);
		snapshot(database, "graph_store_oldest_view_age_millis", SnapshotStats::oldestOpenAgeMillis);
		snapshot(database, "graph_store_retained_bytes", SnapshotStats::retainedBytes);
		snapshot(database, "graph_store_retained_entries", SnapshotStats::retainedEntries);
		snapshot(database, "graph_store_versioned_pages", SnapshotStats::versionedPages);
		counter(database, "graph_store_abandoned_views", stats -> stats.snapshotStats().abandonedViews());

		// ---- I/O totals. FunctionCounter and not Gauge: these only ever go up, and rate() over a gauge goes sharply
		// negative on every restart.
		io(database, "graph_store_wal_syncs", io -> io.walSyncs());
		io(database, "graph_store_wal_records", io -> io.walRecords());
		io(database, "graph_store_wal_bytes_appended", io -> io.walBytesAppended());
		io(database, "graph_store_page_applies", io -> io.pageApplies());
		io(database, "graph_store_bytes_applied", io -> io.bytesApplied());
		io(database, "graph_store_data_forces", io -> io.dataForces());
		io(database, "graph_store_checkpoints", io -> io.checkpoints());
		io(database, "graph_store_pages_punched", io -> io.pagesPunched());
		io(database, "graph_store_compactions", io -> io.compactions());
		io(database, "graph_store_records_relocated", io -> io.recordsRelocated());
		io(database, "graph_store_adjacency_block_decodes", io -> io.adjacencyBlocksDecoded());
		io(database, "graph_store_adjacency_block_skips", io -> io.adjacencyBlocksSkipped());

		// Per kind, because the budget OPERATIONS.md §4 states is "reads per operation for one kind" — summing them
		// would hide exactly the regression ReadBudgetTest exists to catch.
		for (Kind kind : Kind.values()) {
			if (kind == Kind.NULL) {
				continue;
			}
			String name = kind.name().toLowerCase();
			metrics.bindCounter("graph_store_record_reads", database, "kind", name,
				() -> safe(database, store -> store.metrics().io().recordReads(kind)));
			metrics.bindCounter("graph_store_record_writes", database, "kind", name,
				() -> safe(database, store -> store.metrics().io().recordWrites(kind)));
		}
	}

	/** Binds the meters that belong to the process rather than to a database. */
	public void bindServer(Supplier<Number> httpInFlight, Supplier<Number> wireRunningStreams,
		Supplier<Number> wireBufferedBytes) {
		metrics.bindGauge("graph_http_inflight", null, httpInFlight);
		metrics.bindGauge("graph_wire_running_streams", null, wireRunningStreams);
		metrics.bindGauge("graph_wire_buffered_bytes", null, wireBufferedBytes);
	}

	private void stat(String database, String name, ToDoubleFunction<StoreStats> value) {
		metrics.bindGauge(name, database, () -> {
			StoreStats stats = cache.stats(database);
			return stats == null ? Double.NaN : value.applyAsDouble(stats);
		});
	}

	private void storeMetric(String database, String name, ToDoubleFunction<StoreMetrics> value) {
		metrics.bindGauge(name, database, () -> safe(database, store -> value.applyAsDouble(store.metrics())));
	}

	private void snapshot(String database, String name, ToDoubleFunction<SnapshotStats> value) {
		metrics.bindGauge(name, database, () -> safe(database, store -> value.applyAsDouble(store.snapshotStats())));
	}

	private void io(String database, String name, ToDoubleFunction<io.metaloom.graph.core.api.IoStats> value) {
		metrics.bindCounter(name, database, () -> safe(database, store -> value.applyAsDouble(store.metrics().io())));
	}

	private void counter(String database, String name, Function<GraphStore, Number> value) {
		metrics.bindCounter(name, database, () -> safe(database, store -> value.apply(store).doubleValue()));
	}

	/**
	 * Reads one number off a database, resolving the store now and never letting it throw.
	 *
	 * @return NaN if the database is gone, closed, or unreadable — which Prometheus reads as "no sample", and which is
	 *         the only answer that does not take the rest of the scrape down with it
	 */
	private double safe(String database, ToDoubleFunction<GraphStore> reader) {
		try {
			return reader.applyAsDouble(databases.database(database));
		} catch (Exception | LinkageError failure) {
			return Double.NaN;
		}
	}
}
