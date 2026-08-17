package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * A single mutating call is a transaction, and has to look like one from outside.
 *
 * <p>
 * This matters because the smallest-looking calls are not small. {@code createRelationship} writes a REL record, two
 * adjacency entries and two degree counters, in four different places in the file; {@code deleteNode} unpicks all of
 * it. A caller that never writes {@code begin()} still gets all-or-nothing, and a reader must never catch one of these
 * part-way through — an edge whose mirror entry is missing, or a degree counter that disagrees with the list it counts.
 * </p>
 *
 * <p>
 * The check is run from a {@code SNAPSHOT} view while writers hammer, because that is the only vantage point from
 * which "part-way through" is even expressible.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 1
 * @see <a href="../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a>
 */
@Tag("unit")
public class AutoCommitAtomicityTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int NODES = 40;

	@Test
	public void testNoViewEverSeesAMultiRecordWriteHalfDone() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> nodes = seed(store);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicLong sweeps = new AtomicLong();
			List<Thread> readers = new ArrayList<>();

			for (int r = 0; r < 3; r++) {
				readers.add(Thread.ofPlatform().name("atomicity-reader-" + r).start(() -> {
					try {
						while (!stop.get()) {
							try (ReadTransaction view = store.beginRead()) {
								List<String> problems = halfDoneWrites(store, nodes);
								if (!problems.isEmpty()) {
									throw new AssertionError("view at LSN " + view.snapshotLsn()
										+ " caught an auto-commit write half applied:\n  "
										+ String.join("\n  ", problems));
								}
								sweeps.incrementAndGet();
							}
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				}));
			}

			try {
				// Every one of these is its own transaction, and every one touches several records.
				Hammer.named("auto-commit")
					.threads(4)
					.opsPerThread(120)
					.op("createRelationship", 3, rnd -> {
						long from = nodes.get(rnd.nextInt(nodes.size()));
						long to = nodes.get(rnd.nextInt(nodes.size()));
						if (from != to) {
							store.createRelationship(from, "REL", to, Properties.of().set("n", (long) rnd.nextInt(100)));
						}
					})
					.op("createEdge", 3, rnd -> {
						long from = nodes.get(rnd.nextInt(nodes.size()));
						long to = nodes.get(rnd.nextInt(nodes.size()));
						if (from != to) {
							store.createEdge(from, "LIGHT", to);
						}
					})
					.op("deleteEdge", 1, rnd -> {
						long from = nodes.get(rnd.nextInt(nodes.size()));
						List<Edge> out = store.neighbours(from, Direction.OUT);
						if (!out.isEmpty()) {
							Edge victim = out.get(rnd.nextInt(out.size()));
							store.deleteEdge(from, victim.type(), victim.neighbourId());
						}
					})
					.op("setProperties", 2, rnd -> store.setNodeProperties(nodes.get(rnd.nextInt(nodes.size())),
						Properties.of().set("n", (long) rnd.nextInt(1000))))
					.run()
					.assertRan("createRelationship", "createEdge", "deleteEdge");
			} finally {
				stop.set(true);
				for (Thread reader : readers) {
					reader.join(60_000);
				}
			}

			if (failure.get() != null) {
				throw new AssertionError("an auto-commit write was observed part-way through", failure.get());
			}
			assertTrue(sweeps.get() > 20, "only " + sweeps.get() + " sweeps ran, too few to have tested anything");
			assertEquals(0, store.snapshotStats().openViews(), "a view was left open");
			store.verify(VerifyLevel.FULL);
			System.out.println("[auto-commit] sweeps=" + sweeps.get());
		}
	}

	/**
	 * What "half done" looks like from a view: the two independent descriptions of a node's edges disagreeing, an edge
	 * pointing at a node the same view cannot resolve, or a materialised relationship whose endpoints do not match the
	 * adjacency entry that named it.
	 */
	private List<String> halfDoneWrites(GraphStore store, List<Long> nodes) {
		List<String> problems = new ArrayList<>();
		for (long id : nodes) {
			List<Edge> out = store.neighbours(id, Direction.OUT);
			if (out.size() != store.degreeOut(id)) {
				problems.add("node " + id + " has out-degree " + store.degreeOut(id) + " but enumerates " + out.size());
			}
			List<Edge> in = store.neighbours(id, Direction.IN);
			if (in.size() != store.degreeIn(id)) {
				problems.add("node " + id + " has in-degree " + store.degreeIn(id) + " but enumerates " + in.size());
			}
			for (Edge edge : out) {
				if (!store.nodeExists(edge.neighbourId())) {
					problems.add("edge " + id + " -> " + edge.neighbourId() + " points at a node this view cannot see");
					continue;
				}
				// The mirror entry is written by the same transaction as the forward one, so a view that sees one must
				// see the other.
				boolean mirrored = store.neighbours(edge.neighbourId(), Direction.IN).stream()
					.anyMatch(back -> back.neighbourId() == id && back.type().equals(edge.type()));
				if (!mirrored) {
					problems.add("edge " + id + " -" + edge.type() + "-> " + edge.neighbourId()
						+ " has no matching in-edge, so half of a relationship write is visible");
				}
				if (edge.isMaterialised()) {
					long rel = edge.relationshipId();
					if (!store.relationshipExists(rel)) {
						problems.add("edge " + id + " -> " + edge.neighbourId() + " names relationship " + rel
							+ ", which this view cannot resolve");
					} else if (store.relationshipFrom(rel) != id || store.relationshipTo(rel) != edge.neighbourId()) {
						problems.add("relationship " + rel + " says " + store.relationshipFrom(rel) + " -> "
							+ store.relationshipTo(rel) + " but was reached from " + id + " -> " + edge.neighbourId());
					}
				}
			}
		}
		return problems;
	}

	private List<Long> seed(GraphStore store) throws Exception {
		List<Long> nodes = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < NODES; i++) {
				nodes.add(store.createNode("Account", Properties.of().set("n", (long) i)));
			}
			txn.commit();
		}
		return nodes;
	}
}
