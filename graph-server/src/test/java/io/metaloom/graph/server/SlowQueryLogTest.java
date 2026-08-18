package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.server.metrics.GraphMetrics;

/**
 * The slow query log, and above all the one thing it must never do.
 */
@Tag("unit")
public class SlowQueryLogTest {

	@Test
	public void testAParameterValueIsNeverInTheOutput() {
		// The reason this file exists. `MATCH (u:User {token: $t})` puts the secret in the parameter map, not in the
		// query text, and this log is the file most likely to be shipped whole to an aggregator that a different set
		// of people can read.
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("token", "hunter2");
		parameters.put("email", "someone@example.com");

		String keys = SlowQueryLog.parameterKeys(parameters);

		assertFalse(keys.contains("hunter2"), "a parameter value reached the log: " + keys);
		assertFalse(keys.contains("someone@example.com"), "a parameter value reached the log: " + keys);
		assertTrue(keys.contains("token"), "the names are logged, because they are schema rather than data");
		assertTrue(keys.contains("email"));
	}

	@Test
	public void testTheKeysAreSortedSoTwoRunsOfOneQueryLookAlike() {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("since", 2019);
		parameters.put("city", "Bonn");
		assertEquals("[city, since]", SlowQueryLog.parameterKeys(parameters));
		assertEquals("[]", SlowQueryLog.parameterKeys(Map.of()));
		assertEquals("[]", SlowQueryLog.parameterKeys(null));
	}

	@Test
	public void testTheTextIsCollapsedAndCapped() {
		assertEquals("MATCH (n) RETURN n", SlowQueryLog.abbreviate("  MATCH (n)\n  RETURN n  "),
			"a multi-line query has to become one log line");

		String long_ = "RETURN " + "x".repeat(SlowQueryLog.MAX_TEXT * 2);
		String abbreviated = SlowQueryLog.abbreviate(long_);
		assertTrue(abbreviated.length() < long_.length(), "a generated query must not be able to fill a disk");
		assertTrue(abbreviated.endsWith(" chars)"), "and the line has to say that it was truncated");
		assertTrue(abbreviated.contains(String.valueOf(long_.length())), "and by how much");

		assertEquals("", SlowQueryLog.abbreviate(null));
	}

	@Test
	public void testOnlyAQueryOverTheThresholdIsCounted() {
		CountingMetrics metrics = new CountingMetrics();

		SlowQueryLog.maybeLog(100, "http", "default", "MATCH (n) RETURN n", Map.of(),
			TimeUnit.MILLISECONDS.toNanos(99), 1, "ok", metrics);
		assertEquals(0, metrics.slow.get(), "just under the threshold is not slow");

		SlowQueryLog.maybeLog(100, "http", "default", "MATCH (n) RETURN n", Map.of(),
			TimeUnit.MILLISECONDS.toNanos(100), 1, "ok", metrics);
		assertEquals(1, metrics.slow.get(), "at the threshold is");
	}

	@Test
	public void testZeroDisablesItEntirely() {
		// The documented way to turn it off, and the default for an embedded server: a library that logged warnings
		// about its caller's queries without being asked would be a poor guest.
		CountingMetrics metrics = new CountingMetrics();
		SlowQueryLog.maybeLog(0, "http", "default", "MATCH (n) RETURN n", Map.of(),
			TimeUnit.SECONDS.toNanos(60), 1, "ok", metrics);
		assertEquals(0, metrics.slow.get());
	}

	/** Counting the meter rather than capturing the log line: deterministic, and no appender to install. */
	private static final class CountingMetrics implements GraphMetrics {

		private final AtomicInteger slow = new AtomicInteger();

		@Override
		public void recordSlowQuery(String frontend) {
			slow.incrementAndGet();
		}

		@Override
		public void recordWireConnection(boolean tls) {
			// Not what this class counts.
		}

		@Override
		public void recordQuery(String frontend, String kind, String outcome, long durationNanos, long rows) {
		}

		@Override
		public void recordQueryRejected(String frontend, String reason) {
		}

		@Override
		public void recordWriteStats(CypherStats stats) {
		}

		@Override
		public void recordMcpToolCall(String tool, String outcome) {
		}

		@Override
		public void bindGauge(String name, String database, Supplier<Number> value) {
		}

		@Override
		public void bindCounter(String name, String database, String tagKey, String tagValue,
			Supplier<Number> monotonicTotal) {
		}
	}
}
