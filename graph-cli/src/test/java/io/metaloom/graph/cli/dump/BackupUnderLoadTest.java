package io.metaloom.graph.cli.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;

/**
 * A backup taken while a writer is committing: the claim that makes this feature worth having.
 *
 * <p>
 * Two things have to be true at once, and they pull against each other. The dump has to be a <b>single point in
 * time</b> — otherwise it is not a backup, it is a sample — and the writer has to <b>keep making progress</b> —
 * otherwise it is a stop-the-world copy with extra steps. This asserts both, in one run, against a writer that is
 * creating, linking and deleting throughout.
 * </p>
 */
@Tag("unit")
public class BackupUnderLoadTest {

	@TempDir
	Path tmp;

	private static GraphStoreOptions options() {
		return GraphStoreOptions.builder().pageSize(8192).segmentCapacity(8L * 1024 * 1024).build();
	}

	/**
	 * The invariant that catches a torn dump.
	 *
	 * <p>
	 * Every node is created with {@code gen} set to the generation it belongs to, and a node is only ever linked to
	 * another node of the <em>same</em> generation, inside one transaction. So a dump that mixed two instants would
	 * contain an edge across generations, or an edge whose far end is missing — and the restore's own dangling-endpoint
	 * check would catch the second. This catches the first.
	 * </p>
	 */
	@Test
	public void testADumpTakenUnderLoadIsOneConsistentInstant() throws Exception {
		try (GraphStore store = GraphStore.open(tmp.resolve("source"), options())) {
			// A base generation, so the backup has something substantial to walk while the writer works.
			seedGeneration(store, 0, 2_000);

			AtomicInteger generation = new AtomicInteger(1);
			AtomicInteger committed = new AtomicInteger();
			AtomicBoolean stop = new AtomicBoolean();
			Thread writer = new Thread(() -> {
				while (!stop.get()) {
					int g = generation.getAndIncrement();
					try {
						seedGeneration(store, g, 50);
						committed.incrementAndGet();
					} catch (Exception e) {
						return;
					}
				}
			});
			writer.start();

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			GraphBackup.Summary summary;
			try {
				summary = GraphBackup.write(store, "under-load", out, 0);
			} finally {
				stop.set(true);
				writer.join();
			}

			assertTrue(committed.get() > 0,
				"the writer never committed, so this proves nothing about being online; it managed "
					+ committed.get());

			byte[] dump = out.toByteArray();
			try (GraphStore target = GraphStore.open(tmp.resolve("target"), options())) {
				GraphRestore.Summary restored;
				try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
					restored = GraphRestore.read(target, in);
				}
				assertEquals(summary.nodes(), restored.nodes());
				target.verify(VerifyLevel.FULL);

				// Every edge joins two nodes of one generation. An edge across generations would mean the walk had
				// seen two instants.
				int[] edges = { 0 };
				target.forEachNode(id -> {
					long from = (long) target.nodeProperties(id).get("gen");
					for (Edge edge : target.neighbours(id, Direction.OUT)) {
						long to = (long) target.nodeProperties(edge.neighbourId()).get("gen");
						assertEquals(from, to, "an edge spans two generations, so the dump is not one instant");
						edges[0]++;
					}
				});
				assertTrue(edges[0] > 0, "there were no edges to check");

				// And every generation present is present *whole*: a generation is written in one transaction, so a
				// dump containing part of one would be a dump that saw half a commit.
				java.util.Map<Long, Integer> perGeneration = new java.util.HashMap<>();
				target.forEachNode(id -> perGeneration.merge((Long) target.nodeProperties(id).get("gen"), 1,
					Integer::sum));
				for (java.util.Map.Entry<Long, Integer> entry : perGeneration.entrySet()) {
					int expected = entry.getKey() == 0L ? 2_000 : 50;
					assertEquals(expected, entry.getValue().intValue(),
						"generation " + entry.getKey() + " is only partly present, so a commit was torn");
				}
			}
		}
	}

	/** One transaction: n nodes tagged with the generation, chained so that every edge is within it. */
	private static void seedGeneration(GraphStore store, int generation, int count) throws Exception {
		try (Transaction txn = store.begin()) {
			long previous = -1;
			for (int i = 0; i < count; i++) {
				long id = store.createNode(List.of("G" + (generation % 8)),
					Properties.of().set("gen", (long) generation).set("i", (long) i));
				if (previous >= 0) {
					store.createEdge(previous, "NEXT", id);
				}
				previous = id;
			}
			txn.commit();
		}
	}
}
