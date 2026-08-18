package io.metaloom.graph.server.metrics;

import java.util.function.Supplier;

import io.metaloom.graph.cypher.CypherStats;

/**
 * Records nothing, and in particular <b>does not call the suppliers it is given</b>.
 *
 * <p>
 * That last part is the whole reason this is a class rather than a lambda-heavy default: a binder hands over suppliers
 * that read {@code store.stats()}, which touches the filesystem. If the no-op implementation invoked them once at bind
 * time to "check" them, a server started with {@code --no-metrics} would still pay for the thing it turned off.
 * </p>
 */
final class NoopGraphMetrics implements GraphMetrics {

	@Override
	public void recordQuery(String frontend, String kind, String outcome, long durationNanos, long rows) {
		// Nothing.
	}

	@Override
	public void recordQueryRejected(String frontend, String reason) {
		// Nothing.
	}

	@Override
	public void recordSlowQuery(String frontend) {
		// Nothing.
	}

	@Override
	public void recordWriteStats(CypherStats stats) {
		// Nothing.
	}

	@Override
	public void recordMcpToolCall(String tool, String outcome) {
		// Nothing.
	}

	@Override
	public void recordWireConnection(boolean tls) {
		// Nothing.
	}

	@Override
	public void bindGauge(String name, String database, Supplier<Number> value) {
		// Nothing, and deliberately without calling value.
	}

	@Override
	public void bindCounter(String name, String database, String tagKey, String tagValue,
		Supplier<Number> monotonicTotal) {
		// Nothing, and deliberately without calling monotonicTotal.
	}
}
