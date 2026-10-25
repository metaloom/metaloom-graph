package io.metaloom.graph.server.wire;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.client.GraphClient;
import io.metaloom.graph.client.Row;
import io.metaloom.graph.client.RowStream;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;

/**
 * Round-trip latency and streaming throughput of the binary port, client and server in one process over loopback.
 *
 * <p>
 * {@code @Tag("bench")}, so it is not part of the default build. Run it with {@code -Pbench} or as a plain
 * {@code main}, which is what an interleaved before/after comparison wants: the same harness against two class
 * directories, alternated, so drift in the machine lands on both sides. Each figure is the median of the rounds.
 * </p>
 *
 * <ul>
 * <li><b>rtt</b> — {@code RETURN 1}, one at a time on one connection: microseconds per query, mean and p99
 * <li><b>small</b> — 100,000 one-integer rows: rows per second
 * <li><b>large</b> — 2,000 rows of a 2 KiB string: megabytes per second
 * <li><b>nodes</b> — 20,000 whole nodes with three properties: rows per second
 * </ul>
 */
@Tag("bench")
public class WireBenchmark {

	private static final int SMALL = 100_000;
	private static final int DOCS = 2_000;
	private static final int NODES = 20_000;

	@Test
	public void testMeasure() throws Exception {
		main(new String[0]);
	}

	public static void main(String[] args) throws Exception {
		int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
		Path root = Files.createTempDirectory("wire-bench");
		try (GraphDatabases databases = GraphDatabases.open(root)) {
			GraphStore store = databases.database(GraphDatabases.DEFAULT_NAME);
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < SMALL; i++) {
					store.createNode(List.of("Small"), Properties.of().set("i", (long) i));
				}
				String body = "b".repeat(2_048);
				for (int i = 0; i < DOCS; i++) {
					store.createNode(List.of("Doc"), Properties.of().set("n", (long) i).set("body", body));
				}
				for (int i = 0; i < NODES; i++) {
					store.createNode(List.of("Person", "Employee"), Properties.of().set("name", "person-" + i)
						.set("age", (long) (i % 90)).set("score", i * 0.5));
				}
				txn.commit();
			}
			WireServer server = new WireServer(databases, GraphDatabases.DEFAULT_NAME,
				WireServerOptions.defaults().withPort(0));
			int port = server.listen().toCompletionStage().toCompletableFuture().join();
			try (GraphClient client = GraphClient.connect("127.0.0.1", port)) {
				// Warm-up: the JIT and the engine's caches, so round one measures the same thing round five does.
				for (int i = 0; i < 5_000; i++) {
					client.execute("RETURN 1");
				}
				for (int i = 0; i < 3; i++) {
					drain(client, "MATCH (n:Small) RETURN n.i");
					drain(client, "MATCH (d:Doc) RETURN d.body");
					drain(client, "MATCH (p:Person) RETURN p");
				}

				double[] rttMean = new double[rounds];
				double[] rttP99 = new double[rounds];
				double[] small = new double[rounds];
				double[] large = new double[rounds];
				double[] nodes = new double[rounds];
				for (int r = 0; r < rounds; r++) {
					long[] samples = new long[20_000];
					for (int i = 0; i < samples.length; i++) {
						long t0 = System.nanoTime();
						client.execute("RETURN 1");
						samples[i] = System.nanoTime() - t0;
					}
					Arrays.sort(samples);
					rttMean[r] = Arrays.stream(samples).average().orElse(0) / 1_000.0;
					rttP99[r] = samples[(int) (samples.length * 0.99)] / 1_000.0;

					small[r] = rate(client, "MATCH (n:Small) RETURN n.i", 5, SMALL);
					long t0 = System.nanoTime();
					long bytes = 0;
					for (int i = 0; i < 10; i++) {
						bytes += drainBytes(client, "MATCH (d:Doc) RETURN d.body");
					}
					large[r] = bytes / 1e6 / ((System.nanoTime() - t0) / 1e9);
					nodes[r] = rate(client, "MATCH (p:Person) RETURN p", 5, NODES);
				}
				System.out.printf("WIREBENCH rtt_mean_us=%.1f rtt_p99_us=%.1f small_rows_s=%.0f large_mb_s=%.1f"
					+ " nodes_rows_s=%.0f%n", median(rttMean), median(rttP99), median(small), median(large),
					median(nodes));
			} finally {
				server.close();
			}
		} finally {
			try (var walk = Files.walk(root)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
	}

	private static double rate(GraphClient client, String query, int repeats, int expected) {
		long t0 = System.nanoTime();
		for (int i = 0; i < repeats; i++) {
			long rows = drain(client, query);
			if (rows != expected) {
				throw new AssertionError(query + " returned " + rows + " rows, not " + expected);
			}
		}
		return (double) repeats * expected / ((System.nanoTime() - t0) / 1e9);
	}

	private static long drain(GraphClient client, String query) {
		long rows = 0;
		try (RowStream stream = client.query(query)) {
			for (Row row : stream) {
				row.get(0);
				rows++;
			}
		}
		return rows;
	}

	private static long drainBytes(GraphClient client, String query) {
		long bytes = 0;
		try (RowStream stream = client.query(query)) {
			for (Row row : stream) {
				bytes += ((String) row.get(0)).length();
			}
		}
		return bytes;
	}

	private static double median(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}
}
