package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The streaming half of the traversal API (IDX-4).
 * <p>
 * <b>The list form is the oracle for almost all of this.</b> {@code neighbours} and {@code neighbourCursor} run the
 * same walk, the same two filter levels and the same entry-to-{@link Edge} resolution — that is the point of building
 * one on the other — so what has to be asserted is that they cannot drift, over every shape of adjacency the engine
 * has: inline entries, a block chain, lightweight edges beside materialised ones, both directions, and the self-loop
 * that turns one stored entry into two edges. What is left over are the things a cursor has and a list does not: a
 * position, a read view, and a close.
 * <p>
 * The read-budget claim — that stopping early costs one block — lives in {@code ReadBudgetTest}, because that is where
 * the counters are.
 *
 * @see <a href="../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> section 5.4
 */
@Tag("unit")
public class NeighbourCursorTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static List<Edge> drain(GraphStore store, long nodeId, Direction direction, Set<String> types) {
		List<Edge> edges = new ArrayList<>();
		try (NeighbourCursor cursor = store.neighbourCursor(nodeId, direction, types)) {
			while (cursor.next()) {
				edges.add(cursor.edge());
			}
		}
		return edges;
	}

	/**
	 * Builds a node whose adjacency covers every tier and every entry shape at once: two inline entries, a chain of
	 * blocks, lightweight and materialised edges mixed, edges in both directions, and a materialised self-loop.
	 */
	private static long mixedHub(GraphStore store) throws IOException {
		long hub = store.createNode("Hub");
		// The inline tier, and the first entry of it is materialised so that the tier is not uniformly lightweight.
		store.createRelationship(hub, "T1", store.createNode("Spoke"));
		store.createEdge(hub, "T2", store.createNode("Spoke"));
		// Enough to chain: a page holds several thousand compressed entries, so a few hundred would all land in one
		// block and the walk would never cross a block boundary at all.
		for (int i = 0; i < 20_000; i++) {
			if (i % 3 == 0) {
				store.createRelationship(hub, "T1", store.createNode("Spoke"));
			} else {
				store.createEdge(hub, i % 3 == 1 ? "T2" : "T3", store.createNode("Spoke"));
			}
		}
		for (int i = 0; i < 50; i++) {
			// Incoming, so the direction filter has something to reject.
			store.createEdge(store.createNode("Source"), "T4", hub);
		}
		store.createRelationship(hub, "LOOP", hub);
		return hub;
	}

	@Test
	public void testTheCursorYieldsExactlyWhatTheListDoes() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			try (Transaction txn = store.begin()) {
				hub = mixedHub(store);
				txn.commit();
			}
			assertTrue(impl.adjacencyStore().blockCount(hub) >= 2,
				"the comparison is only interesting over a chain");

			List<Set<String>> filters = new ArrayList<>();
			filters.add(null);
			filters.add(Set.of("T1"));
			filters.add(Set.of("T2", "T3"));
			filters.add(Set.of("LOOP"));
			filters.add(Set.of("T4"));
			// A type no edge carries, and one that was never interned at all: the two ways a filter can be empty.
			filters.add(Set.of("ABSENT"));

			for (Direction direction : Direction.values()) {
				for (Set<String> types : filters) {
					String what = direction + " " + types;
					assertEquals(store.neighbours(hub, direction, types), drain(store, hub, direction, types),
						"the cursor and the list must agree, edge for edge and in order, for " + what);
				}
			}
			assertEquals(store.neighbours(hub, Direction.BOTH), drain(store, hub, Direction.BOTH, null),
				"including through the two-argument overload, which passes no filter at all");
		}
	}

	/**
	 * One stored entry, two edges.
	 * <p>
	 * A materialised self-loop makes its node both endpoints, so it is an outgoing edge and an incoming edge of the
	 * same node and {@code materialise} appends two. A cursor that assumed one edge per adjacency entry would serve
	 * whichever came first and silently drop the other — which is why the walk buffers what an entry produced instead
	 * of returning it.
	 */
	@Test
	public void testAMaterialisedSelfLoopYieldsBothOfItsDirections() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("N");
			long loop = store.createRelationship(node, "LOOP", node);

			List<Edge> both = drain(store, node, Direction.BOTH, null);
			assertEquals(2, both.size(), "one entry, two edges: " + both);
			assertEquals(Set.of(Direction.OUT, Direction.IN),
				Set.of(both.get(0).direction(), both.get(1).direction()), "one of each: " + both);
			assertTrue(both.stream().allMatch(edge -> edge.relationshipId() == loop && edge.neighbourId() == node));

			assertEquals(1, drain(store, node, Direction.OUT, null).size(), "and the direction filter halves it");
			assertEquals(1, drain(store, node, Direction.IN, null).size());
		}
	}

	@Test
	public void testACursorOverANodeWithNoEdgesYieldsNothing() throws Exception {
		try (GraphStore store = stores.open()) {
			long lonely = store.createNode("N");
			try (NeighbourCursor cursor = store.neighbourCursor(lonely, Direction.BOTH, null)) {
				assertFalse(cursor.next(), "no edges, no rows");
				assertFalse(cursor.next(), "and asking again must stay false rather than restart the walk");
			}
		}
	}

	@Test
	public void testAnUnpositionedCursorRefusesToHandBackAnEdge() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("N");
			store.createEdge(node, "T", store.createNode("N"));
			try (NeighbourCursor cursor = store.neighbourCursor(node, Direction.OUT, null)) {
				assertThrows(IllegalStateException.class, cursor::edge,
					"before the first next() there is no current edge");
				assertTrue(cursor.next());
				assertEquals("T", cursor.edge().type());
				assertFalse(cursor.next());
				assertThrows(IllegalStateException.class, cursor::edge,
					"and past the end there is no current edge either");
			}
		}
	}

	@Test
	public void testClosingTwiceIsHarmless() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("N");
			store.createEdge(node, "T", store.createNode("N"));
			NeighbourCursor cursor = store.neighbourCursor(node, Direction.OUT, null);
			cursor.close();
			cursor.close();
			// A read latch released twice does not throw, it corrupts the lock, so the assertion is that the store is
			// still usable rather than that close() survived.
			assertEquals(1, store.neighbours(node, Direction.OUT).size(),
				"the store must still be readable after a double close");
			store.createNode("After");
		}
	}

	/**
	 * A cursor that fails to open must not keep the read view it took to look.
	 * <p>
	 * The check happens after the latch is acquired, so a missing node throws from inside the acquisition. If the
	 * latch leaked, nothing would fail here — the next commit would simply never take the apply lock, and the store
	 * would hang rather than break. Hence the writer on its own thread: a hang is only detectable as a thread that
	 * does not finish.
	 */
	@Test
	public void testACursorThatCannotOpenReleasesItsReadView() throws Exception {
		try (GraphStore store = stores.open()) {
			long ghost = store.createNode("Ghost");
			store.deleteNode(ghost);

			assertThrows(GraphStoreException.class, () -> store.neighbourCursor(ghost, Direction.OUT, null));

			Thread writer = new Thread(() -> {
				try {
					store.createNode("After");
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			writer.setDaemon(true);
			writer.start();
			writer.join(30_000);
			assertFalse(writer.isAlive(),
				"a commit blocked for thirty seconds means the failed cursor never released the read latch");
		}
	}

	/**
	 * Inside the caller's own write transaction the cursor takes no latch at all — the overlay is already a consistent
	 * view — which is what lets it see uncommitted edges and what keeps the writer from excluding itself.
	 */
	@Test
	public void testACursorInsideAWriteTransactionSeesTheUncommittedEdges() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub = store.createNode("Hub");
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 4000; i++) {
					store.createEdge(hub, "T", store.createNode("Spoke"));
				}
				assertEquals(4000, drain(store, hub, Direction.OUT, null).size(),
					"the writer's own uncommitted edges are visible to it");
				// Writing while a cursor is open is only safe here, and this is the assertion that it is: outside a
				// transaction the same sequence would deadlock the thread against its own read latch.
				try (NeighbourCursor cursor = store.neighbourCursor(hub, Direction.OUT, null)) {
					assertTrue(cursor.next());
					store.createEdge(hub, "T", store.createNode("Spoke"));
				}
				txn.commit();
			}
			assertEquals(4001, store.neighbours(hub, Direction.OUT).size());
		}
	}

	/**
	 * A removed edge is tombstoned in place rather than moved, so the entry is still there to be walked past. The
	 * cursor drops it at the same point the array form does — {@code PackedEdge.isTombstone} — and not at some later
	 * one where a caller could see it.
	 */
	@Test
	public void testDeletedEdgesAreNotYielded() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub;
			List<Long> spokes = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 4000; i++) {
					long spoke = store.createNode("Spoke");
					spokes.add(spoke);
					store.createEdge(hub, "T", spoke);
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 4000; i += 2) {
					assertTrue(store.deleteEdge(hub, "T", spokes.get(i)));
				}
				txn.commit();
			}
			List<Edge> streamed = drain(store, hub, Direction.OUT, null);
			assertEquals(2000, streamed.size(), "half the edges are gone");
			assertEquals(store.neighbours(hub, Direction.OUT), streamed);
		}
	}

	/**
	 * The cursor is a view, not a copy, and its lifetime is the one thing a caller has to think about. Two of them
	 * over the same node on the same thread is the ordinary case — both take a read latch, which is shared — and it is
	 * worth pinning because a lease that took anything exclusive would deadlock here rather than in production.
	 */
	@Test
	public void testTwoCursorsMayBeOpenAtOnce() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 4000; i++) {
					store.createEdge(hub, "T", store.createNode("Spoke"));
				}
				txn.commit();
			}
			try (NeighbourCursor first = store.neighbourCursor(hub, Direction.OUT, null);
				NeighbourCursor second = store.neighbourCursor(hub, Direction.OUT, null)) {
				int rows = 0;
				while (first.next() && second.next()) {
					assertEquals(first.edge(), second.edge(), "two walks of one node must agree at every step");
					rows++;
				}
				assertEquals(4000, rows);
			}
		}
	}
}
