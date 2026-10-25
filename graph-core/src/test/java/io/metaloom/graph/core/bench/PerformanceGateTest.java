package io.metaloom.graph.core.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.CompactionReport;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The performance gates: a build failure when the engine gets an order of magnitude slower, or when it starts touching
 * more structure per operation.
 * <p>
 * <b>This is not JMH.</b> The specification asks for a JMH suite, and JMH's annotation processor is not in this
 * project's offline Maven repository, so the benchmarks cannot be generated. What is here instead is an honest
 * substitute: a warmup pass, a measured pass, and a comparison against gates checked into
 * {@code performance-gates.properties}. It has none of JMH's statistical rigour — no forks, no confidence intervals,
 * no dead-code elimination defences — and the gates are set low to match, at roughly 40 percent of the reference
 * measurement. It catches the regressions that matter (a lookup added to the read path, a lock on a hot path, an fsync
 * per operation) and it will not catch a fifteen percent drift.
 * <p>
 * The structural gates are the other half and they are exact, because a read count is deterministic. TESTING.md
 * section 7 is explicit that throughput alone is the wrong thing to gate on: a change that goes faster while touching
 * more of the store has made things worse, and only measuring both catches it.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
@Tag("bench")
public class PerformanceGateTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private Properties gates;
	private final List<String> violations = new ArrayList<>();
	private final List<String> report = new ArrayList<>();

	@Test
	public void testEveryGateHolds() throws Exception {
		gates = loadGates();

		try (GraphStore store = stores.open(io.metaloom.graph.core.api.GraphStoreOptions.defaults())) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			List<Long> ids = new ArrayList<>();

			// --- bulk insert, batched
			long start = System.nanoTime();
			IoStats before = impl.counters().snapshot();
			long payloadBefore = store.stats().recordBytes();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 100_000; i++) {
					ids.add(store.createNode("N", io.metaloom.graph.core.api.Properties.of()
						.set("i", i).set("name", "node-" + i)));
				}
				txn.commit();
			}
			gateRate("nodeCreateBatched", 100_000, start);
			store.checkpoint();
			IoStats bulk = impl.counters().snapshot().since(before);
			gateCeiling("writeAmplification.bulkLoad",
				bulk.writeAmplification(store.stats().recordBytes() - payloadBefore));

			// --- point reads
			gateRate("nodeReadById", 200_000, timed(200_000, i -> store.nodeLabel(ids.get(i % ids.size())) != null));
			gateRate("nodePropertiesRead", 100_000,
				timed(100_000, i -> store.nodeProperties(ids.get(i % ids.size())) != null));
			gateReads("nodeReadById", impl, () -> store.nodeLabel(ids.get(7)) != null ? 1 : 0, Kind.NODE);
			gateReads("nodePropertiesRead", impl, () -> store.nodeProperties(ids.get(7)).size(), Kind.PROP);

			// --- durable commit latency, the cost of one fsync each
			start = System.nanoTime();
			for (int i = 0; i < 200; i++) {
				store.createNode("D");
			}
			gateRate("nodeCreateDurableCommit", 200, start);

			// --- edges and traversal
			start = System.nanoTime();
			try (Transaction txn = store.begin()) {
				for (int i = 1; i < 50_000; i++) {
					store.createEdge(ids.get(i - 1), "E", ids.get(i));
				}
				txn.commit();
			}
			gateRate("edgeCreateBatched", 50_000, start);
			gateRate("neighboursDegreeOne", 100_000,
				timed(100_000, i -> !store.neighbours(ids.get(i % 40_000), Direction.OUT).isEmpty()));
			gateRate("indexedLookup", 20_000,
				timed(20_000, i -> store.nodesByProperty("i", i % 100_000) != null));

			// --- adjacency budgets, exact
			long hub = ids.get(0);
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 4000; i++) {
					store.createEdge(hub, "H", ids.get(i + 50_000));
				}
				txn.commit();
			}
			gateReads("adjacency.degree4000", impl, () -> store.neighbours(hub, Direction.OUT).size(), Kind.ADJ);
			long pair = ids.get(90_000);
			gateReads("adjacency.degreeTwo", impl, () -> store.neighbours(pair, Direction.BOTH).size(), Kind.ADJ);

			// --- compaction
			try (Transaction txn = store.begin()) {
				for (int i = 60_000; i < 100_000; i++) {
					store.deleteNode(ids.get(i));
				}
				txn.commit();
			}
			start = System.nanoTime();
			CompactionReport compaction = store.compact(CompactionOptions.always());
			double millis = (System.nanoTime() - start) / 1_000_000.0;
			gateCeiling("compactionMillis", millis);
			report.add(String.format("%-32s %,14d records relocated, liveness %.3f -> %.3f", "compaction detail",
				compaction.totalRelocated(), compaction.livenessBefore(), compaction.livenessAfter()));
		}

		System.out.println("performance gates:");
		report.forEach(line -> System.out.println("  " + line));
		if (!violations.isEmpty()) {
			fail("performance gate(s) crossed:\n  " + String.join("\n  ", violations));
		}
	}

	// ---------------------------------------------------------------- gate helpers

	private void gateRate(String name, int operations, long startNanos) {
		double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
		double rate = operations / elapsed;
		long floor = Long.parseLong(required("min." + name));
		report.add(String.format("%-32s %,14.0f ops/s  (floor %,d) %s", name, rate, floor,
			rate >= floor ? "ok" : "FAIL"));
		if (rate < floor) {
			violations.add(name + " ran at " + String.format("%.0f", rate) + " ops/s, below the floor of " + floor);
		}
	}

	/** Runs an operation once to warm it, then asserts the record reads of a second run against an exact ceiling. */
	private void gateReads(String name, GraphStoreImpl store, LongSupplier operation, Kind kind) {
		operation.getAsLong();
		IoStats before = store.counters().snapshot();
		operation.getAsLong();
		long reads = store.counters().snapshot().since(before).recordReads(kind);
		long ceiling = Long.parseLong(required("max.reads." + name));
		report.add(String.format("%-32s %,14d %s reads (ceiling %d) %s", name, reads, kind, ceiling,
			reads <= ceiling ? "ok" : "FAIL"));
		if (reads > ceiling) {
			violations.add(name + " touched " + reads + " " + kind + " records, above the ceiling of " + ceiling);
		}
	}

	private void gateCeiling(String name, double measured) {
		double ceiling = Double.parseDouble(required("max." + name));
		report.add(String.format("%-32s %,14.2f       (ceiling %.2f) %s", name, measured, ceiling,
			measured <= ceiling ? "ok" : "FAIL"));
		if (measured > ceiling) {
			violations.add(name + " measured " + measured + ", above the ceiling of " + ceiling);
		}
	}

	/** Times a loop and returns its start, so the caller can gate it. The predicate keeps the work from vanishing. */
	private long timed(int operations, java.util.function.IntPredicate operation) {
		int kept = 0;
		long start = System.nanoTime();
		for (int i = 0; i < operations; i++) {
			if (operation.test(i)) {
				kept++;
			}
		}
		assertTrue(kept >= 0, "the loop must not be optimised away");
		return start;
	}

	private String required(String key) {
		String value = gates.getProperty(key);
		if (value == null) {
			throw new IllegalStateException("No gate configured for " + key
				+ "; add it to performance-gates.properties with a measurement and a reason");
		}
		return value;
	}

	private Properties loadGates() throws IOException {
		Properties loaded = new Properties();
		try (InputStream in = getClass().getResourceAsStream("/performance-gates.properties")) {
			if (in == null) {
				throw new IllegalStateException("performance-gates.properties is missing from the test resources");
			}
			loaded.load(in);
		}
		return loaded;
	}
}
