package io.metaloom.graph.server;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.server.metrics.GraphMetrics;

/**
 * The slow query log: one line for a query that took longer than it should have.
 *
 * <h2>Why this exists when there is a histogram</h2>
 *
 * <p>
 * The house convention is that timing goes to metrics and not to logs, and this is the documented exception.
 * {@code graph_query_duration} answers <em>how often, and how bad</em> — and it cannot answer <em>which query</em>,
 * because a Prometheus label may not be a query string without producing one time series per distinct query. The
 * histogram tells you the p99 doubled; only this tells you what to go and index. Both are fed from the same clock, and
 * every line here also increments {@code graph_queries_slow}.
 * </p>
 *
 * <h2>Parameter values are never logged</h2>
 *
 * <p>
 * <b>The query text is logged and the parameter values are not.</b> {@code MATCH (u:User {token: $t})} puts the secret
 * in the parameter map, not in the text, and the slow query log is the file most likely to be shipped whole to a log
 * aggregator that a different set of people can read. The parameter <em>names</em> are logged, because they are schema
 * rather than data and because knowing a query was parameterised at all is most of what makes the line actionable.
 * </p>
 *
 * <p>
 * The text is collapsed to one line and truncated. A generated query can be megabytes; one of those per second is a
 * full disk, and a log line nobody can read in a terminal is not much use anyway.
 * </p>
 */
public final class SlowQueryLog {

	private static final Logger logger = LoggerFactory.getLogger(SlowQueryLog.class);

	/** Long enough to hold any query a person wrote, short enough that a generated one cannot fill a disk. */
	static final int MAX_TEXT = 500;

	private SlowQueryLog() {
	}

	/**
	 * Logs one line if the query was slow enough, and counts it.
	 *
	 * @param thresholdMillis
	 *            0 disables logging entirely, which is the default for an embedded server
	 * @param frontend
	 *            {@code http}, {@code wire} or {@code mcp}
	 * @param outcome
	 *            {@code ok}, or why it ended. An abandoned query is the most interesting line in this log — it is the
	 *            one that cost the most and returned nothing — so failures are logged as well as successes
	 */
	public static void maybeLog(long thresholdMillis, String frontend, String database, String queryText,
		Map<String, Object> parameters, long elapsedNanos, long rows, String outcome, GraphMetrics metrics) {
		if (thresholdMillis <= 0) {
			return;
		}
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
		if (elapsedMillis < thresholdMillis) {
			return;
		}
		metrics.recordSlowQuery(frontend);
		logger.warn("Slow query: {} ms, {} rows, outcome {}, {} front end, database '{}', params {} — {}",
			elapsedMillis, rows, outcome, frontend, database, parameterKeys(parameters), abbreviate(queryText));
	}

	/** Collapses a query to one line and caps its length, so a log line is readable and bounded. */
	static String abbreviate(String text) {
		if (text == null) {
			return "";
		}
		String collapsed = text.strip().replaceAll("\\s+", " ");
		if (collapsed.length() <= MAX_TEXT) {
			return collapsed;
		}
		return collapsed.substring(0, MAX_TEXT) + "... (" + collapsed.length() + " chars)";
	}

	/**
	 * The parameter <b>names</b>, never the values.
	 *
	 * <p>
	 * Sorted, so that two runs of the same query produce the same line and a reader can group them by eye.
	 * </p>
	 */
	static String parameterKeys(Map<String, Object> parameters) {
		if (parameters == null || parameters.isEmpty()) {
			return "[]";
		}
		return parameters.keySet().stream().sorted().toList().toString();
	}
}
