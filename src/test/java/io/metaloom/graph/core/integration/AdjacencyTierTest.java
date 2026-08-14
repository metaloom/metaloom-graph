package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The three adjacency tiers and their promotion boundaries, and the cost model that motivates them.
 *
 * @see <a href="../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a>
 */
@Tag("unit")
public class AdjacencyTierTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testLowDegreeNodesUseNoAdjacencyStorageAtAll() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long a = store.createNode("A");
			long b = store.createNode("B");
			long c = store.createNode("C");

			store.createEdge(a, "T", b);
			assertEquals(0, impl.adjacencyStore().blockCount(a),
				"A degree one node must not touch the adjacency store");
			assertEquals(0, store.stats().adjacencyCellCount());

			store.createEdge(a, "T", c);
			assertEquals(0, impl.adjacencyStore().blockCount(a), "Two edges still fit inline");
			assertEquals(0, store.stats().adjacencyCellCount());
			assertEquals(2, store.neighbours(a, Direction.OUT).size());
		}
	}

	/**
	 * The middle of the degree range must not cost a page.
	 * <p>
	 * This is the regression test for the defect measured in spec/reports/SCALE.md: a node with three edges — one more
	 * than the two that fit inline — used to be given a whole 8 KiB block, and on a random graph of mean degree 4 that
	 * is most of the nodes and most of the store. The tiering is now inline, then a 512 byte cell that doubles as it
	 * fills, then a page, then a chain.
	 */
	@Test
	public void testASmallDegreeCostsASmallBlock() throws Exception {
		try (GraphStore store = stores.open()) {
			long[] spokes = new long[600];
			long hub = store.createNode("Hub");
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < spokes.length; i++) {
					spokes[i] = store.createNode("N");
				}
				txn.commit();
			}

			// Two edges stay inline and cost no adjacency at all.
			store.createEdge(hub, "T", spokes[0]);
			store.createEdge(hub, "T", spokes[1]);
			assertEquals(0, store.stats().adjacencyCellCount(), "degree two is inline");

			// The third is what used to cost 8192 bytes.
			store.createEdge(hub, "T", spokes[2]);
			assertEquals(1, store.stats().adjacencyCellCount(),
				"a node of degree three must cost one 512 byte cell, not a page");

			// And the block grows with the node rather than jumping straight to a page.
			int[] degrees = { 20, 60, 150, 400 };
			long previous = 1;
			for (int degree : degrees) {
				try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
					for (int i = (int) previous + 2; i < degree; i++) {
						store.createEdge(hub, "T", spokes[i]);
					}
					txn.commit();
				}
				long cells = store.stats().adjacencyCellCount();
				assertTrue(cells <= 16, "one node's block must never exceed a page, got " + cells + " cells");
				assertTrue(cells * 512 >= 64, "a block must at least hold its own header");
				System.out.printf("degree %4d -> %2d cells (%.2f bytes per directed entry)%n",
					degree, cells, cells * 512.0 / degree);
				previous = degree;
			}
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}

	@Test
	public void testPromotionToBlocksPreservesTheEdgeSet() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub = store.createNode("Hub");
			Set<Long> expected = new HashSet<>();
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < 50; i++) {
					long other = store.createNode("N");
					expected.add(other);
					store.createEdge(hub, "T", other);
				}
				txn.commit();
			}
			assertEquals(1, impl.adjacencyStore().blockCount(hub),
				"Fifty edges must fit a single block");

			Set<Long> actual = new HashSet<>();
			for (Edge edge : store.neighbours(hub, Direction.OUT)) {
				actual.add(edge.neighbourId());
			}
			assertEquals(expected, actual, "Promotion must not lose or duplicate an edge");
			assertEquals(50, store.degreeOut(hub));
		}
	}

	@Test
	public void testSupernodePacksThousandsOfEdgesPerBlock() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			int degree = 5000;
			List<Long> others = new ArrayList<>(degree);
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < degree; i++) {
					long other = store.createNode("N");
					others.add(other);
					store.createEdge(hub, "T", other);
				}
				txn.commit();
			}

			int blocks = impl.adjacencyStore().blockCount(hub);
			double edgesPerBlock = (double) degree / blocks;
			// The compressed layout must beat a raw eight byte entry layout, which would need at least five blocks.
			assertTrue(edgesPerBlock > 1500,
				"Expected well over 1500 edges per block, got " + edgesPerBlock + " (" + blocks + " blocks)");

			List<Edge> neighbours = store.neighbours(hub, Direction.OUT);
			assertEquals(degree, neighbours.size());
			Set<Long> seen = new HashSet<>();
			for (Edge edge : neighbours) {
				assertTrue(seen.add(edge.neighbourId()), "Duplicate neighbour " + edge.neighbourId());
			}
			assertEquals(new HashSet<>(others), seen);
			store.verify();
		}
	}

	@Test
	public void testEveryEdgeIsBidirectional() throws Exception {
		try (GraphStore store = stores.open()) {
			int nodeCount = 200;
			long[] nodes = new long[nodeCount];
			List<long[]> edges = new ArrayList<>();
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < nodeCount; i++) {
					nodes[i] = store.createNode("N");
				}
				// A deterministic pseudo random graph.
				int seed = 12345;
				for (int i = 0; i < 600; i++) {
					seed = seed * 1103515245 + 12345;
					int from = Math.floorMod(seed >> 8, nodeCount);
					seed = seed * 1103515245 + 12345;
					int to = Math.floorMod(seed >> 8, nodeCount);
					if (from == to) {
						continue;
					}
					store.createEdge(nodes[from], "T", nodes[to]);
					edges.add(new long[] { nodes[from], nodes[to] });
				}
				txn.commit();
			}

			for (long[] edge : edges) {
				boolean forward = store.neighbours(edge[0], Direction.OUT).stream()
					.anyMatch(e -> e.neighbourId() == edge[1]);
				boolean backward = store.neighbours(edge[1], Direction.IN).stream()
					.anyMatch(e -> e.neighbourId() == edge[0]);
				assertTrue(forward, "Missing out edge");
				assertTrue(backward, "Missing mirror in edge");
			}

			// Degree counters must agree with the actual adjacency.
			for (long node : nodes) {
				assertEquals(store.neighbours(node, Direction.OUT).size(), store.degreeOut(node));
				assertEquals(store.neighbours(node, Direction.IN).size(), store.degreeIn(node));
			}
			store.verify();
		}
	}

	@Test
	public void testDeletingEdgesFromABlockChain() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub;
			List<Long> others = new ArrayList<>();
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 300; i++) {
					long other = store.createNode("N");
					others.add(other);
					store.createEdge(hub, "T", other);
				}
				for (int i = 0; i < 300; i += 2) {
					assertTrue(store.deleteEdge(hub, "T", others.get(i)));
				}
				txn.commit();
			}
			assertEquals(150, store.neighbours(hub, Direction.OUT).size());
			assertEquals(150, store.degreeOut(hub));
			for (int i = 1; i < 300; i += 2) {
				long other = others.get(i);
				assertEquals(1, store.neighbours(other, Direction.IN).size());
			}
			store.verify();
		}
	}

	@Test
	public void testMixedLightweightAndMaterialisedEdges() throws Exception {
		try (GraphStore store = stores.open()) {
			long hub;
			int lightweight = 0;
			int materialised = 0;
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 100; i++) {
					long other = store.createNode("N");
					if (i % 2 == 0) {
						store.createEdge(hub, "LIGHT", other);
						lightweight++;
					} else {
						store.createRelationship(hub, "HEAVY", other,
							io.metaloom.graph.core.api.Properties.of().set("i", i));
						materialised++;
					}
				}
				txn.commit();
			}
			List<Edge> out = store.neighbours(hub, Direction.OUT);
			assertEquals(lightweight + materialised, out.size());
			assertEquals(materialised, out.stream().filter(Edge::isMaterialised).count());
			assertEquals(lightweight, out.stream().filter(e -> !e.isMaterialised()).count());
			assertEquals(materialised, store.stats().relationshipCount(),
				"Only property carrying edges get a relationship record");
			store.verify();
		}
	}
}
