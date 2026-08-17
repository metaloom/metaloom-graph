package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.fixture.Witness;

/**
 * Everything at once, on purpose.
 *
 * <p>
 * The other tests in this package each hold one seam still and push on it. This one runs the whole public API
 * concurrently — auto-commit writes, multi-operation transactions, deliberate rollbacks, both isolation levels,
 * traversals, cursors, index lookups, retries, checkpoints and refused maintenance — and asks only that nothing
 * anywhere comes apart. It is the test most likely to find the defect nobody predicted, and the least likely to
 * explain it, which is why the others exist too.
 * </p>
 *
 * <p>
 * Every property value is a {@link Witness}: it names its owner and checksums itself, so a read that splices bytes
 * from two commits, or resolves to the wrong record, is caught by the value rather than needing a model. That is the
 * only oracle available for the optimistic read path, which runs the entire operation with no lock held and validates
 * afterwards.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 1
 */
@Tag("unit")
public class HammerTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int NODES = 60;

	@Test
	public void testTheWholeApiUnderConcurrentLoad() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Hammered", "bucket");
			List<Long> nodes = seed(store);

			AtomicLong generation = new AtomicLong();
			AtomicLong torn = new AtomicLong();
			List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());

			Hammer.Result result = Hammer.named("everything")
				.threads(6)
				.opsPerThread(150)
				.op("autoCommitWrite", 4, rnd -> {
					long id = nodes.get(rnd.nextInt(nodes.size()));
					store.setNodeProperties(id, Properties.of()
						.set("w", Witness.shortForm(id, generation.incrementAndGet()))
						.set("bucket", (long) rnd.nextInt(8)));
				})
				.op("blobWrite", 1, rnd -> {
					long id = nodes.get(rnd.nextInt(nodes.size()));
					store.setNodeProperties(id, Properties.of()
						.set("w", Witness.longForm(id, generation.incrementAndGet()))
						.set("bucket", (long) rnd.nextInt(8)));
				})
				// inTransaction, not begin(): six writers hammering one store conflict constantly, and every conflict
				// here is real — two of them writing the same node, or inserting into one index leaf. Retrying is the
				// contract. The single-statement ops above need no such wrapper, because an auto-commit mutation
				// retries its own conflicts inside the engine.
				.op("multiOpTransaction", 2, rnd -> store.inTransaction(() -> {
					long a = nodes.get(rnd.nextInt(nodes.size()));
					long b = nodes.get(rnd.nextInt(nodes.size()));
					store.setNodeProperties(a, Properties.of()
						.set("w", Witness.shortForm(a, generation.incrementAndGet()))
						.set("bucket", (long) rnd.nextInt(8)));
					if (a != b) {
						store.createEdge(a, "LINKS", b);
					}
					return null;
				}, 200))
				.op("rollback", 1, rnd -> {
					try (Transaction txn = store.begin()) {
						long id = nodes.get(rnd.nextInt(nodes.size()));
						store.setNodeProperties(id, Properties.of().set("w", "ROLLED-BACK-MUST-NEVER-BE-VISIBLE"));
						txn.rollback();
					}
				})
				.op("snapshotRead", 4, rnd -> {
					try (ReadTransaction ignored = store.beginRead()) {
						checkAll(store, nodes, problems, torn);
					}
				})
				.op("readCommittedRead", 2, rnd -> {
					try (ReadTransaction ignored = store.beginRead(IsolationLevel.READ_COMMITTED)) {
						checkAll(store, nodes, problems, torn);
					}
				})
				.op("traverse", 2, rnd -> store.traverse(nodes.get(rnd.nextInt(nodes.size())), Direction.OUT,
					java.util.Set.of("LINKS"), 2))
				.op("cursor", 2, rnd -> {
					long id = nodes.get(rnd.nextInt(nodes.size()));
					try (NeighbourCursor cursor = store.neighbourCursor(id, Direction.OUT, null)) {
						int seen = 0;
						while (cursor.next() && seen < 32) {
							Edge edge = cursor.edge();
							if (edge == null) {
								problems.add("cursor on " + id + " returned a null edge after next() said true");
							}
							seen++;
						}
					}
				})
				.op("indexLookup", 2, rnd -> store.nodesByLabelAndProperty("Hammered", "bucket",
					(long) rnd.nextInt(8)))
				.op("retryingWrite", 1, rnd -> store.inTransaction(() -> {
					long id = nodes.get(rnd.nextInt(nodes.size()));
					store.setNodeProperties(id, Properties.of()
						.set("w", Witness.shortForm(id, generation.incrementAndGet()))
						.set("bucket", (long) rnd.nextInt(8)));
					return null;
					// The default three attempts is not enough with six writers on one index: this op exists to
					// exercise the retry seam, so it is given room to actually retry.
				}, 200))
				.op("checkpoint", 1, rnd -> store.checkpoint())
				.op("maintenanceWhileViewsOpen", 1, rnd -> {
					// reclaim() is refused while any view is open. Whether one is open right now is a race, so both
					// answers are legal — what is not legal is any other failure, or it quietly succeeding in a way
					// that pulls the mapping out from under a reader.
					try {
						store.reclaim();
					} catch (GraphStoreException e) {
						if (!e.getMessage().contains("cannot run while")) {
							throw e;
						}
					}
				})
				.op("stats", 1, rnd -> {
					store.stats();
					store.snapshotStats();
					store.metrics();
				})
				.run();

			result.assertRan("autoCommitWrite", "blobWrite", "multiOpTransaction", "rollback", "snapshotRead",
				"readCommittedRead", "traverse", "cursor", "indexLookup", "retryingWrite", "checkpoint");

			assertEquals(0, torn.get(), "a read returned bytes no single commit ever wrote, "
				+ problems.size() + " problem(s): " + firstFew(problems));
			assertTrue(problems.isEmpty(), "problems during the run: " + firstFew(problems));

			// Nothing rolled back may have survived, and the store must be whole.
			for (long id : nodes) {
				Object value = store.nodeProperty(id, "w");
				assertTrue(value == null || !value.toString().startsWith("ROLLED-BACK"),
					"node " + id + " kept a value from a rolled-back transaction");
			}
			assertEquals(0, store.snapshotStats().openViews(), "a read view was left open");
			store.verify(VerifyLevel.FULL);
		}

		// And it survives the round trip.
		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- helpers

	private void checkAll(GraphStore store, List<Long> nodes, List<String> problems, AtomicLong torn) {
		for (long id : nodes) {
			Object value = store.nodeProperty(id, "w");
			if (value == null) {
				continue;
			}
			if (value.toString().startsWith("ROLLED-BACK")) {
				problems.add("node " + id + " exposed a value from a rolled-back transaction");
				continue;
			}
			String problem = Witness.problemWith(value, id);
			if (problem != null) {
				torn.incrementAndGet();
				problems.add(problem);
			}
		}
	}

	private List<Long> seed(GraphStore store) throws Exception {
		List<Long> nodes = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < NODES; i++) {
				long id = store.createNode("Hammered", Properties.of().set("bucket", (long) (i % 8)));
				nodes.add(id);
			}
			txn.commit();
		}
		// The witness has to name the node, so it can only be written once the id exists.
		try (Transaction txn = store.begin()) {
			for (long id : nodes) {
				store.setNodeProperties(id, Properties.of()
					.set("w", Witness.shortForm(id, 0))
					.set("bucket", id % 8));
			}
			txn.commit();
		}
		for (int i = 1; i < nodes.size(); i++) {
			store.createEdge(nodes.get(i - 1), "LINKS", nodes.get(i));
		}
		return nodes;
	}

	private String firstFew(List<String> problems) {
		synchronized (problems) {
			return problems.stream().limit(5).toList() + (problems.size() > 5 ? " (+" + (problems.size() - 5) + ")" : "");
		}
	}
}
