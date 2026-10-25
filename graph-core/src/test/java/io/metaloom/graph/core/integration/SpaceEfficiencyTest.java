package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.api.Kind;

/**
 * The headline space number. The proof of concept spent 1080 bytes on a node record alone, before its property
 * records and its 32 byte per edge adjacency links.
 *
 * @see <a href="../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> section 7
 */
@Tag("unit")
public class SpaceEfficiencyTest {

	/** Node record plus a two property block, per node, in the old layout. */
	private static final int POC_BYTES_PER_NODE = 1080 + 64;

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testBytesPerNodeAndEdge() throws Exception {
		int nodeCount = 5000;
		try (GraphStore store = stores.open()) {
			long[] ids = new long[nodeCount];
			// One transaction for the whole load: an fsync per node is the wrong way to build a graph.
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < nodeCount; i++) {
					ids[i] = store.createNode("Person", Properties.of()
						.set("name", "person-" + i)
						.set("rank", i));
				}
				// One property free edge each: a chain, so every node has degree one or two and stays inline.
				for (int i = 1; i < nodeCount; i++) {
					store.createEdge(ids[i - 1], "NEXT", ids[i]);
				}
				txn.commit();
			}
			store.checkpoint();

			StoreStats stats = store.stats();
			// Element storage is what the 64 byte record layout is about; the secondary indexes are separate and are
			// reported on their own, because they are a deliberate space-for-lookup trade.
			double elementBytesPerNode = (stats.nodeCount() * 64.0 + stats.propertyCellCount() * 32.0) / nodeCount;
			double indexBytesPerNode = (double) stats.indexPageCount() * 8192 / nodeCount;

			System.out.printf(
				"Space: %d nodes + %d edges -> %.1f element bytes/node, %.1f index bytes/node, "
					+ "%d committed bytes (%.1f per node), %d adjacency blocks%n",
				nodeCount, nodeCount - 1, elementBytesPerNode, indexBytesPerNode,
				stats.storageBytes(), stats.bytesPerNode(), stats.adjacencyCellCount());

			assertTrue(elementBytesPerNode < 200,
				"A node plus its two property block should stay under 200 bytes, got " + elementBytesPerNode);

			assertTrue(elementBytesPerNode < POC_BYTES_PER_NODE / 3.0,
				"Expected at least a threefold improvement over the old layout, got " + elementBytesPerNode
					+ " bytes per node against " + POC_BYTES_PER_NODE);
			assertTrue(stats.adjacencyCellCount() == 0,
				"A chain graph has degree at most two, so no adjacency block should exist");
			assertTrue(stats.relationshipCount() == 0,
				"Property free edges must not create relationship records");
			store.verify();
		}
	}

	@Test
	public void testDenseAdjacencyCostPerEdge() throws Exception {
		int degree = 20_000;
		try (GraphStore store = stores.open()) {
			long hub;
			long before;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				long[] others = new long[degree];
				for (int i = 0; i < degree; i++) {
					others[i] = store.createNode("N");
				}
				before = store.stats().adjacencyCellCount();
				for (int i = 0; i < degree; i++) {
					store.createEdge(hub, "T", others[i]);
				}
				txn.commit();
			}
			long cells = store.stats().adjacencyCellCount() - before;
			// Every edge is stored twice, once per endpoint; the spokes keep theirs inline. Cells, not blocks: a
			// block is a run of one or more 512 byte cells, so the space it costs is the cell count times the cell.
			double bytesPerEdge = (double) cells * Kind.ADJ_CELL_BYTES / degree;
			System.out.printf("Adjacency: %d edges in %d cells (%.2f bytes per directed entry)%n",
				degree, cells, bytesPerEdge);
			assertTrue(bytesPerEdge < 8.0,
				"Compressed adjacency must beat a raw eight byte entry, got " + bytesPerEdge);
			store.verify();
		}
	}
}
