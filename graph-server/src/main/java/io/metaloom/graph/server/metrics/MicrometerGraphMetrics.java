package io.metaloom.graph.server.metrics;

import java.time.Duration;
import java.util.function.Supplier;

import io.metaloom.graph.cypher.CypherStats;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * The one class in this project that knows what Micrometer is.
 *
 * @see GraphMetrics for the naming rule and the gauge-versus-counter distinction
 */
public final class MicrometerGraphMetrics implements GraphMetrics {

	private final PrometheusMeterRegistry registry;

	public MicrometerGraphMetrics(PrometheusMeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void recordQuery(String frontend, String kind, String outcome, long durationNanos, long rows) {
		registry.counter("graph_queries", "frontend", frontend, "kind", kind, "outcome", outcome).increment();
		// Only successful queries go in the histogram. A syntax error is fast and a timeout is exactly as long as the
		// budget, and both would say more about the mix of failures than about how long the engine takes to answer.
		if ("ok".equals(outcome)) {
			Timer.builder("graph_query_duration")
				.tag("frontend", frontend)
				.tag("kind", kind)
				.publishPercentileHistogram()
				.register(registry)
				.record(Duration.ofNanos(durationNanos));
			DistributionSummary.builder("graph_query_rows")
				.tag("frontend", frontend)
				.register(registry)
				.record(rows);
		}
	}

	@Override
	public void recordQueryRejected(String frontend, String reason) {
		registry.counter("graph_queries_rejected", "frontend", frontend, "reason", reason).increment();
	}

	@Override
	public void recordSlowQuery(String frontend) {
		registry.counter("graph_queries_slow", "frontend", frontend).increment();
	}

	/**
	 * One counter with an {@code operation} tag, rather than seven separately named counters.
	 *
	 * <p>
	 * <b>Not a style choice — the obvious naming does not survive the renderer.</b> The Prometheus client strips the
	 * reserved suffixes {@code _created}, {@code _total}, {@code _count}, {@code _sum}, {@code _bucket} and
	 * {@code _info} before it re-adds the type suffix, so a counter called {@code graph_nodes_created} is published as
	 * {@code graph_nodes_total} — silently, with no warning, and reading exactly like a count of all nodes rather than
	 * of the ones this server created. {@code graph_relationships_created} collapses the same way.
	 * {@code MetricsCatalogScrapeTest} is what found it.
	 * </p>
	 *
	 * <p>
	 * Tagging sidesteps the whole class of collision, and a dashboard wanting one of them writes
	 * {@code graph_write_operations_total{operation="nodes_created"}}.
	 * </p>
	 */
	@Override
	public void recordWriteStats(CypherStats stats) {
		// Only when non-zero, so a read-only server's scrape does not carry seven series that will never leave zero.
		add("nodes_created", stats.nodesCreated());
		add("nodes_deleted", stats.nodesDeleted());
		add("relationships_created", stats.relationshipsCreated());
		add("relationships_deleted", stats.relationshipsDeleted());
		add("properties_set", stats.propertiesSet());
		add("labels_added", stats.labelsAdded());
		add("labels_removed", stats.labelsRemoved());
	}

	private void add(String operation, long amount) {
		if (amount > 0) {
			registry.counter("graph_write_operations", "operation", operation).increment(amount);
		}
	}

	@Override
	public void recordMcpToolCall(String tool, String outcome) {
		registry.counter("graph_mcp_tool_calls", "tool", tool, "outcome", outcome).increment();
	}

	/**
	 * {@code graph_wire_connections{tls="true"|"false"}}.
	 *
	 * <p>
	 * Both series are registered on the first connection of each kind rather than up front, which means a server that
	 * has never been reached in the clear publishes no {@code tls="false"} series at all. That reads correctly:
	 * absence is the good state, and a zero that has always been zero is indistinguishable from a meter nobody wired.
	 * </p>
	 */
	@Override
	public void recordWireConnection(boolean tls) {
		registry.counter("graph_wire_connections", "tls", Boolean.toString(tls)).increment();
	}

	@Override
	public void bindGauge(String name, String database, Supplier<Number> value) {
		// strongReference, because the supplier closes over a lookup rather than over a long-lived object and there is
		// nothing else holding it. Without it the gauge reports NaN as soon as the lambda is collected — which happens
		// under load and not in a test, so it is exactly the failure that reaches production.
		Gauge.builder(name, value)
			.tags(tags(database))
			.strongReference(true)
			.register(registry);
	}

	@Override
	public void bindCounter(String name, String database, String tagKey, String tagValue,
		Supplier<Number> monotonicTotal) {
		FunctionCounter.builder(name, monotonicTotal, supplier -> supplier.get().doubleValue())
			.tags(tags(database).and(tagKey == null ? Tags.empty() : Tags.of(tagKey, tagValue)))
			.register(registry);
	}

	private static Tags tags(String database) {
		return database == null ? Tags.empty() : Tags.of("database", database);
	}
}
