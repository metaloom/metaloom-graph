package io.metaloom.graph.server.metrics;

import java.util.function.Supplier;

import io.metaloom.graph.cypher.CypherStats;

/**
 * Everything this server records, as one interface.
 *
 * <p>
 * <b>No call site touches Micrometer.</b> The query path, the wire server and the MCP handler all record through this,
 * and only {@link MicrometerGraphMetrics} knows what a registry is. Two things fall out of that and both were the
 * reason for it: the whole catalogue is readable in one file rather than reconstructed by grepping for
 * {@code registry.counter}, and a test or an embedder can pass {@link #NOOP} without a metrics framework on the
 * classpath.
 * </p>
 *
 * <h2>Naming</h2>
 *
 * <p>
 * Meter names deliberately omit the Prometheus {@code _total} and {@code _seconds} suffixes — the registry appends them
 * at scrape time, and a name carrying its own suffix comes out as {@code graph_queries_total_total}. Every name is
 * documented in {@code spec/METRICS.md}, and {@code MetricsCatalogScrapeTest} fails if that file and a real scrape
 * disagree in either direction.
 * </p>
 *
 * <h2>Gauges versus counters</h2>
 *
 * <p>
 * {@link #bindGauge} is for a level — something that goes up and down, like open views. {@link #bindCounter} is for a
 * monotonic total read out of an object this class does not own, like the {@code IoStats} family. The distinction is
 * not cosmetic: Prometheus computes {@code rate()} over a counter with reset detection, so publishing a total as a
 * gauge produces a rate that goes sharply negative every time the server restarts.
 * </p>
 */
public interface GraphMetrics {

	/** Records nothing. For tests, for an embedder, and for a server started with {@code --no-metrics}. */
	GraphMetrics NOOP = new NoopGraphMetrics();

	/**
	 * One finished query.
	 *
	 * @param frontend
	 *            {@code http}, {@code wire} or {@code mcp}
	 * @param kind
	 *            {@code read} or {@code write}, from the parsed tree rather than from what the query turned out to do
	 * @param outcome
	 *            {@code ok}, or the same short reason the HTTP route puts in its error body: {@code syntax},
	 *            {@code type}, {@code timeout}, {@code database}, {@code constraint}, {@code store}, {@code internal}
	 */
	void recordQuery(String frontend, String kind, String outcome, long durationNanos, long rows);

	/**
	 * A query refused before it ran.
	 *
	 * <p>
	 * Separate from an {@code outcome} on {@link #recordQuery} because these cost nothing and would otherwise drag the
	 * duration histogram towards zero — a server refusing every write would look like one answering very fast.
	 * </p>
	 *
	 * @param reason
	 *            {@code readonly}, {@code overloaded} or {@code shutdown}
	 */
	void recordQueryRejected(String frontend, String reason);

	/** A query that crossed the slow query threshold and was therefore logged. See {@code SlowQueryLog}. */
	void recordSlowQuery(String frontend);

	/** What a write query changed. Ignored when the query wrote nothing. */
	void recordWriteStats(CypherStats stats);

	/** One MCP tool call. {@code outcome} is {@code ok}, {@code refused} or {@code error}. */
	void recordMcpToolCall(String tool, String outcome);

	/**
	 * One connection accepted on the binary port.
	 *
	 * <p>
	 * The tag is the point of the meter. Once a deployment opens the encrypted listener alongside the plaintext one,
	 * the only question worth asking is whether anything is still arriving in the clear — and the answer decides when
	 * {@code --wire-tls-only} can be set. Without this it is answerable only by {@code ss} on the host, which is not
	 * an answer a dashboard can hold.
	 * </p>
	 *
	 * <p>
	 * A counter and not a gauge of open connections: connections are long-lived here, so a level would sit flat and
	 * say nothing about whether a client reconnected in the clear an hour ago.
	 * </p>
	 *
	 * @param tls
	 *            whether the socket terminated TLS, asked of the socket rather than assumed from configuration
	 */
	void recordWireConnection(boolean tls);

	/**
	 * A level, read fresh on every scrape.
	 *
	 * @param database
	 *            the {@code database} tag, or null for a server-wide meter
	 */
	void bindGauge(String name, String database, Supplier<Number> value);

	/**
	 * A monotonic total, read fresh on every scrape.
	 *
	 * @param database
	 *            the {@code database} tag, or null for a server-wide meter
	 */
	default void bindCounter(String name, String database, Supplier<Number> monotonicTotal) {
		bindCounter(name, database, null, null, monotonicTotal);
	}

	/**
	 * A monotonic total carrying one further tag, for the per-kind read and write counts.
	 *
	 * <p>
	 * {@code spec/OPERATIONS.md} §4 monitors {@code io.recordReads(NODE)} per operation specifically, so the counts
	 * have to stay separable by kind rather than being summed into one series.
	 * </p>
	 *
	 * @param tagKey
	 *            null for no extra tag, in which case {@code tagValue} is ignored
	 */
	void bindCounter(String name, String database, String tagKey, String tagValue, Supplier<Number> monotonicTotal);
}
