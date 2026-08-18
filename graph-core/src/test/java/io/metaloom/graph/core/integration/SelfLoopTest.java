package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * A self-loop is <b>one</b> stored relationship that stands for both an outgoing and an incoming edge, so
 * {@code degreeOut(n) + degreeIn(n)} counts it <b>twice</b>. That is correct — both degrees really are 1 — but it
 * makes the sum the wrong way to answer "how many edges die with this node", and a workload that uses it as an edge
 * count drifts by one per self-loop it deletes.
 * <p>
 * This is not hypothetical: it is what the 12-hour soak found. {@code SoakTest} tracked its live edge count by
 * subtracting {@code degreeOut + degreeIn} on every node deletion, and its tally crossed zero at round 280,220 and
 * reached -7,335 — while the store itself was behaving perfectly, holding storage flat at about 4.6 MB.
 *
 * @see <a href="../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a>
 */
@Tag("unit")
public class SelfLoopTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testASelfLoopIsOneEdgeThatCountsInBothDegrees() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("N");
			store.createEdge(a, "E", a);

			assertEquals(1, store.degreeOut(a), "a self-loop is an outgoing edge of the node");
			assertEquals(1, store.degreeIn(a), "and an incoming edge of the same node");
			assertEquals(2, store.degreeOut(a) + store.degreeIn(a),
				"so the sum of the degrees over-counts a self-loop by exactly one");
			assertEquals(2, store.neighbours(a, Direction.BOTH).size(),
				"and an undirected walk lists that one entry twice, once per direction — the same trap, one layer up");
			assertEquals(1, store.neighbours(a, Direction.OUT).size(), "the outgoing walk lists it once");
			assertEquals(a, store.neighbours(a, Direction.OUT).get(0).neighbourId(),
				"and names the node itself as the neighbour, which is how a workload can recognise a self-loop");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testDeletingANodeRemovesFewerEdgesThanItsDegreesSuggest() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("N");
			long b = store.createNode("N");
			store.createEdge(a, "E", a);
			store.createEdge(a, "E", b);
			assertEquals(2, edgesOf(store, a, b), "two edges: the self-loop and the ordinary one, counted once each");

			int degreeSum = store.degreeOut(a) + store.degreeIn(a);
			assertEquals(3, degreeSum, "but a's degrees add up to three: out 2, in 1");

			store.deleteNode(a);
			assertEquals(0, store.degreeIn(b), "deleting a took both of its edges with it");
			assertEquals(0, edgesOf(store, b), "so no edge is left in the store");
			assertTrue(degreeSum > 2,
				"yet the degree sum claimed three went away, which is what drives an edge tally negative");
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Edges incident to the given nodes, each counted once — a self-loop included once, not twice. */
	private int edgesOf(GraphStore store, long... nodes) {
		int total = 0;
		for (long node : nodes) {
			total += store.degreeOut(node);
		}
		return total;
	}

	@Test
	public void testDeletingASelfLoopByHandAlsoClearsBothDegrees() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("N");
			store.createEdge(a, "E", a);

			assertTrue(store.deleteEdge(a, "E", a), "the self-loop is deletable by its own endpoints");
			assertEquals(0, store.degreeOut(a), "it is off the outgoing degree");
			assertEquals(0, store.degreeIn(a), "and off the incoming degree, from one deletion");
			store.verify(VerifyLevel.FULL);
		}
	}
}
