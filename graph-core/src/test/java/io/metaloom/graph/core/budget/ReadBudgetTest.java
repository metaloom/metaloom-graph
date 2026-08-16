package io.metaloom.graph.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.store.GraphStoreImpl;
import io.metaloom.graph.core.store.adj.PackedEdge;

/**
 * Reads per operation, enforced as build failures.
 * <p>
 * These are the tests that keep the project honest about its central claim. "Reading an element by id costs zero index
 * probes and zero extra seeks" is a sentence in a README until something asserts that reading a node touches exactly
 * one node record and <b>zero</b> index, adjacency, property and relationship records. A change that adds a lookup to
 * the read path passes every functional test in the suite and fails here, which is the entire point.
 * <p>
 * <b>What the counter measures.</b> One record-slice acquisition, per kind. That is not the same as one device read:
 * several records of the same page cost one device read, and a warm page costs none at all. So the budgets below are
 * an upper bound on device reads and an exact measure of how much structure an operation has to walk. The
 * zero-valued ones carry the design claim and are exact either way; measuring true device reads needs cold-cache
 * instrumentation and the direct-I/O engine, which is Phase 6 work that does not exist.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
@Tag("unit")
public class ReadBudgetTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** Costs one operation, having warmed everything it touches so no first-touch allocation is counted. */
	private IoStats cost(GraphStoreImpl store, Runnable operation) {
		operation.run();
		IoStats before = store.counters().snapshot();
		operation.run();
		return store.counters().snapshot().since(before);
	}

	// ---------------------------------------------------------------- the addressing claim

	@Test
	public void testReadingANodeByIdTouchesNothingButTheNode() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long id;
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 5000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				id = store.createNode("Target", Properties.of().set("i", -1));
				txn.commit();
			}

			IoStats label = cost(impl, () -> store.nodeLabel(id));
			assertEquals(1, label.recordReads(Kind.NODE), "a label read is one node record: " + label);
			assertEquals(0, label.recordReads(Kind.INDEX), "no index probe: " + label);
			assertEquals(0, label.recordReads(Kind.ADJ), "no adjacency read: " + label);
			assertEquals(0, label.recordReads(Kind.PROP), "no property read: " + label);
			assertEquals(0, label.recordReads(Kind.REL), "no relationship read: " + label);
			assertEquals(0, label.recordWrites(Kind.NODE), "a read must not dirty a page: " + label);

			IoStats exists = cost(impl, () -> store.nodeExists(id));
			assertEquals(1, exists.recordReads(Kind.NODE), "an existence check is one node record: " + exists);
			assertEquals(0, exists.totalRecordReads() - 1, "and nothing else: " + exists);

			IoStats properties = cost(impl, () -> store.nodeProperties(id));
			assertEquals(1, properties.recordReads(Kind.NODE), "one node record: " + properties);
			assertEquals(0, properties.recordReads(Kind.INDEX), "no index probe: " + properties);
			assertEquals(1, properties.recordReads(Kind.PROP),
				"a property block is one contiguous run and costs exactly one touch: " + properties);
		}
	}

	@Test
	public void testTheBudgetDoesNotGrowWithTheStore() throws Exception {
		// The point of arithmetic addressing: cost is independent of how much is in the store.
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long small;
			try (Transaction txn = store.begin()) {
				small = store.createNode("N", Properties.of().set("i", 0));
				for (int i = 1; i < 100; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}
			long atHundred = cost(impl, () -> store.nodeLabel(small)).totalRecordReads();

			try (Transaction txn = store.begin()) {
				for (int i = 100; i < 100_000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}
			long atHundredThousand = cost(impl, () -> store.nodeLabel(small)).totalRecordReads();

			assertEquals(atHundred, atHundredThousand,
				"a thousandfold larger store must not make a read by id cost more");
		}
	}

	// ---------------------------------------------------------------- adjacency tiers

	@Test
	public void testLowDegreeNeighboursCostNoAdjacencyReads() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long a;
			try (Transaction txn = store.begin()) {
				a = store.createNode("A");
				store.createEdge(a, "E", store.createNode("B"));
				store.createEdge(a, "E", store.createNode("C"));
				txn.commit();
			}
			assertEquals(2, store.neighbours(a, Direction.OUT).size());

			IoStats stats = cost(impl, () -> store.neighbours(a, Direction.OUT));
			assertEquals(0, stats.recordReads(Kind.ADJ),
				"both edges are inline in the node record, so adjacency must not be touched at all: " + stats);
			assertEquals(0, stats.recordReads(Kind.INDEX), "and no index probe: " + stats);
		}
	}

	@Test
	public void testAFourThousandDegreeNodeCostsOneAdjacencyRead() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 4000; i++) {
					store.createEdge(hub, "E", store.createNode("Spoke"));
				}
				txn.commit();
			}
			assertEquals(4000, store.neighbours(hub, Direction.OUT).size());

			IoStats stats = cost(impl, () -> store.neighbours(hub, Direction.OUT));
			assertEquals(1, stats.recordReads(Kind.ADJ),
				"4000 edges fit one 8 KiB block, so enumerating them is one block read: " + stats);
		}
	}

	@Test
	public void testSupernodeScanCostIsBoundedByBlockCount() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 100_000; i++) {
					store.createEdge(hub, "E", store.createNode("Spoke"));
				}
				txn.commit();
			}
			int blocks = impl.adjacencyStore().blockCount(hub);
			IoStats stats = cost(impl, () -> store.neighbours(hub, Direction.OUT));

			// One acquisition per block — the run from its first cell to the end of its page, which holds both the
			// header and the payload — so the bound is loose on purpose. The budget is that the scan is linear in
			// blocks, not in edges. One read per edge is what the proof of concept did.
			assertTrue(stats.recordReads(Kind.ADJ) <= 2L * blocks + 2,
				"a 100,000 edge scan must cost O(blocks), not O(edges): " + blocks + " blocks, " + stats);
			assertTrue(stats.recordReads(Kind.ADJ) < 100_000 / 100,
				"and must be at least two orders of magnitude below one read per edge: " + stats);
		}
	}

	/**
	 * The IDX-4 claim: taking one edge off a supernode costs one block, however dense the node is.
	 * <p>
	 * This is the budget the list form cannot meet by construction. {@code neighbours} decodes the whole chain and
	 * builds an {@code Edge} per entry before the caller sees the first row, so a query that wants one row out of a
	 * node of degree 10^6 pays for 10^6 — the last of the three supernode costs INDEX_TASKS.md §1 enumerates. A cursor
	 * decodes a block only when the entries of the previous one have run out, so a caller that stops after one row has
	 * touched exactly the head block.
	 * <p>
	 * <b>The comparison between the two degrees is the assertion, not the absolute number.</b> One read is the same
	 * whether the chain is one block long or thirteen, which is what "the cost does not grow with the degree" means
	 * and what a {@code LIMIT 1} needs to be true. The hub here is 100,000 edges rather than the 10^6 the task names:
	 * what carries the claim is that the chain has a length at all, and thirteen blocks demonstrate it in a second
	 * rather than a minute.
	 */
	@Test
	public void testTakingOneEdgeOffASupernodeReadsOneBlock() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long sparse;
			long hub;
			try (Transaction txn = store.begin()) {
				sparse = fanOut(store, "E", 4000);
				hub = fanOut(store, "E", 100_000);
				txn.commit();
			}
			int blocks = impl.adjacencyStore().blockCount(hub);
			assertEquals(1, impl.adjacencyStore().blockCount(sparse), "4000 edges are one block");
			assertTrue(blocks >= 10, "the claim needs a chain; this node has " + blocks + " block(s)");

			IoStats dense = cost(impl, () -> firstEdge(store, hub, null));
			assertEquals(1, dense.recordReads(Kind.ADJ),
				"one row off a " + blocks + " block chain must read the head block and no other: " + dense);
			assertEquals(1, dense.adjacencyBlocksDecoded(), "and must decode it once: " + dense);
			assertEquals(0, dense.adjacencyBlocksSkipped(), "with nothing to skip on an unfiltered walk: " + dense);

			IoStats one = cost(impl, () -> firstEdge(store, sparse, null));
			assertEquals(one.recordReads(Kind.ADJ), dense.recordReads(Kind.ADJ),
				"a twenty-five fold denser node must not make the first row cost more: " + one + " against " + dense);

			// The contrast is what the task is about: the list form owes the whole chain before it returns a row.
			IoStats list = cost(impl, () -> store.neighbours(hub, Direction.OUT));
			assertTrue(list.recordReads(Kind.ADJ) >= blocks,
				"the list form reads every block of the chain: " + list);
			assertEquals(blocks, list.adjacencyBlocksDecoded(), "and decodes every one of them: " + list);
		}
	}

	/**
	 * The same claim on the filtered path, which is where IDX-3 and IDX-4 have to compose.
	 * <p>
	 * The rare edges are written last and so land in the head block, which the block-level bitmap admits. A cursor
	 * stops there; the list form still walks past every other block of the chain to prove there is nothing else — it
	 * skips their decode, but the chain's {@code next} pointer is inside each block's header, so it owes the read
	 * either way (§5.3). That difference — one read against one per block — is the whole of what streaming buys a
	 * filtered {@code LIMIT 1}.
	 */
	@Test
	public void testTakingOneFilteredEdgeOffASupernodeStopsAtTheFirstAdmittingBlock() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			try (Transaction txn = store.begin()) {
				hub = fanOut(store, "COMMON", 30_000);
				// Appended last, so they sit in the head block: new blocks are pushed onto the head of the chain.
				for (int i = 0; i < 5; i++) {
					store.createEdge(hub, "RARE", store.createNode("Spoke"));
				}
				txn.commit();
			}
			int blocks = impl.adjacencyStore().blockCount(hub);
			assertTrue(blocks >= 3, "the claim needs a chain; this node has " + blocks + " block(s)");

			java.util.Set<String> rare = java.util.Set.of("RARE");
			assertEquals("RARE", firstEdge(store, hub, rare).type(), "the filter must admit the rare edge");

			IoStats cursor = cost(impl, () -> firstEdge(store, hub, rare));
			assertEquals(1, cursor.recordReads(Kind.ADJ),
				"the head block admits the filter, so the walk must stop there: " + cursor);
			assertEquals(1, cursor.adjacencyBlocksDecoded(), "decoding one block: " + cursor);

			IoStats list = cost(impl, () -> store.neighbours(hub, Direction.OUT, rare));
			assertTrue(list.recordReads(Kind.ADJ) >= blocks,
				"the list form has to reach the end of the chain to know there is no more: " + list);
			assertTrue(list.adjacencyBlocksSkipped() >= blocks - 2,
				"skipping the decode of every block whose bitmap excludes RARE, but not the read: " + list);
		}
	}

	/** Opens a cursor, takes one edge, closes it. The operation the two budgets above measure. */
	private static Edge firstEdge(GraphStore store, long nodeId, java.util.Set<String> types) {
		try (NeighbourCursor cursor = store.neighbourCursor(nodeId, Direction.OUT, types)) {
			assertTrue(cursor.next(), "the node must have at least one admitted edge");
			return cursor.edge();
		}
	}

	/**
	 * The IDX-2 claim, in the only unit that matters: a type-filtered traversal over a supernode of <b>materialised</b>
	 * edges must read the relationship records of the edges that <em>survive</em> the filter, not of every edge it
	 * walks past.
	 * <p>
	 * Before the type was cached in the adjacency entry, the filter could not run until the record had been read, so a
	 * query for five rare edges among five thousand paid five thousand random 64 byte reads — the supernode cost this
	 * whole line of work is about. Each surviving candidate still costs four REL touches: its generation, its type, and
	 * its two endpoints.
	 */
	@Test
	public void testATypeFilteredSupernodeReadsOnlyTheSurvivingRelationships() throws Exception {
		int common = 5000;
		int rare = 5;
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < common; i++) {
					store.createRelationship(hub, "COMMON", store.createNode("Spoke"));
				}
				for (int i = 0; i < rare; i++) {
					store.createRelationship(hub, "RARE", store.createNode("Spoke"));
				}
				txn.commit();
			}
			assertEquals(rare, store.neighbours(hub, Direction.OUT, java.util.Set.of("RARE")).size(),
				"the filter must still return every rare edge");

			IoStats filtered = cost(impl, () -> store.neighbours(hub, Direction.OUT, java.util.Set.of("RARE")));
			assertTrue(filtered.recordReads(Kind.REL) <= 4L * rare,
				"a filtered scan must read only the surviving relationship records, at most four touches each: "
					+ filtered);

			// The contrast is the point: with no filter every entry is resolved, which is what the filtered scan used
			// to cost as well.
			IoStats unfiltered = cost(impl, () -> store.neighbours(hub, Direction.OUT));
			assertTrue(unfiltered.recordReads(Kind.REL) >= common,
				"an unfiltered scan does resolve every entry: " + unfiltered);
			assertTrue(filtered.recordReads(Kind.REL) * 100L < unfiltered.recordReads(Kind.REL),
				"filtering must be orders of magnitude cheaper, got " + filtered.recordReads(Kind.REL) + " against "
					+ unfiltered.recordReads(Kind.REL));
		}
	}

	/**
	 * The IDX-3 claim: a type-filtered scan must walk past the blocks whose bitmap rules the filter out, instead of
	 * decoding every block of the chain as it did.
	 * <p>
	 * <b>Read in the right unit.</b> The saving is in blocks <em>decoded</em>, not blocks read, and the assertion that
	 * the two {@code ADJ} read counts are equal is as much the point as the one that the decode counts differ. A chain
	 * is a linked list whose {@code next} pointer lives in each block's own header, so a walk acquires every block
	 * before it can judge any of them — the record read is owed either way. What the skip removes is the varint decode
	 * of up to 8,000 entries and the {@code long[]} they land in.
	 */
	@Test
	public void testATypeFilteredScanSkipsTheBlocksWhoseBitmapExcludesTheType() throws Exception {
		int common = 30_000;
		int rare = 5;
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < common; i++) {
					store.createEdge(hub, "COMMON", store.createNode("Spoke"));
				}
				// Appended last, so they land in the head block and no older block's bitmap carries the RARE bit.
				for (int i = 0; i < rare; i++) {
					store.createEdge(hub, "RARE", store.createNode("Spoke"));
				}
				txn.commit();
			}
			int blocks = impl.adjacencyStore().blockCount(hub);
			assertTrue(blocks >= 3, "the skip only exists on a chain; this node has " + blocks + " block(s)");
			assertEquals(rare, store.neighbours(hub, Direction.OUT, java.util.Set.of("RARE")).size(),
				"the filter must still return every rare edge");

			IoStats unfiltered = cost(impl, () -> store.neighbours(hub, Direction.OUT));
			IoStats filtered = cost(impl, () -> store.neighbours(hub, Direction.OUT, java.util.Set.of("RARE")));

			assertEquals(blocks, unfiltered.adjacencyBlocksDecoded(),
				"an unfiltered scan has to decode every block: " + unfiltered);
			assertEquals(0, unfiltered.adjacencyBlocksSkipped(),
				"and has nothing to skip on: " + unfiltered);

			assertEquals(blocks, filtered.adjacencyBlocksDecoded() + filtered.adjacencyBlocksSkipped(),
				"every block of the chain is accounted for as decoded or skipped: " + filtered);
			assertTrue(filtered.adjacencyBlocksDecoded() < unfiltered.adjacencyBlocksDecoded(),
				"a filtered scan must decode fewer blocks than an unfiltered one over the same node: "
					+ filtered.adjacencyBlocksDecoded() + " against " + unfiltered.adjacencyBlocksDecoded());
			// At most two, not exactly one: the rare edges are appended last and so sit in the head block, unless that
			// block happened to fill while they were being written and pushed a new one in front of it.
			assertTrue(filtered.adjacencyBlocksDecoded() <= 2,
				"only the block that actually holds a RARE entry may be decoded: " + filtered);
			assertTrue(filtered.adjacencyBlocksSkipped() >= blocks - 2,
				"every other block must be skipped: " + filtered);

			assertEquals(unfiltered.recordReads(Kind.ADJ), filtered.recordReads(Kind.ADJ),
				"the chain pointer is in each block's header, so skipping saves the decode and not the read; "
					+ "filtered=" + filtered + " unfiltered=" + unfiltered);
		}
	}

	/**
	 * The zero-type rule, in the place it can silently produce a wrong answer.
	 * <p>
	 * A materialised entry that caches no type contributes bit 0 to its block's bitmap rather than the bit of its real
	 * type (RELATIONSHIP_STORAGE.md §3.1), so a block holding one is under-described by its own bitmap. Skipping on
	 * that bitmap would drop a real edge from the answer — not a missed optimisation, a wrong result. The block
	 * therefore records whether every materialised entry in it caches a type, and the skip only applies where it does.
	 * <p>
	 * The control hub is what makes this an assertion rather than an anecdote: both chains are filtered on a type
	 * neither of them holds, and the one whose head block was written in the pre-cache shape is the one that still has
	 * to decode it.
	 */
	@Test
	public void testABlockHoldingAnEntryWithNoCachedTypeIsNeverSkipped() throws Exception {
		int common = 20_000;
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			long control;
			try (Transaction txn = store.begin()) {
				// Interned so that the filter below resolves to a real type id rather than to nothing.
				store.createEdge(store.createNode("N"), "ABSENT", store.createNode("N"));
				hub = fanOut(store, "COMMON", common);
				control = fanOut(store, "COMMON", common);
				txn.commit();
			}
			long relId = store.createRelationship(hub, "RARE", store.createNode("Spoke"));
			// Rewrite the hub's entry into the shape every materialised entry of a pre-IDX-2 store has: dir 3, type 0.
			try (Transaction txn = store.begin()) {
				assertTrue(impl.adjacencyStore().removeMaterialised(hub, relId));
				impl.adjacencyStore().add(hub, PackedEdge.ofRelationship(relId));
				txn.commit();
			}
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);

			int blocks = impl.adjacencyStore().blockCount(hub);
			assertTrue(blocks >= 2, "the skip only exists on a chain; this node has " + blocks + " block(s)");
			assertEquals(1, store.neighbours(hub, Direction.OUT, java.util.Set.of("RARE")).size(),
				"a block whose bitmap does not carry the RARE bit still holds a RARE edge, and skipping it would "
					+ "drop that edge from the answer");

			IoStats guarded = cost(impl, () -> store.neighbours(hub, Direction.OUT, java.util.Set.of("ABSENT")));
			IoStats skipped =
				cost(impl, () -> store.neighbours(control, Direction.OUT, java.util.Set.of("ABSENT")));

			assertEquals(0, skipped.adjacencyBlocksDecoded(),
				"a chain whose every entry caches its type is skipped whole on a type it does not hold: " + skipped);
			assertEquals(1, guarded.adjacencyBlocksDecoded(),
				"but the one block holding an entry that caches no type must be decoded even though its bitmap "
					+ "excludes the filter: " + guarded);
			assertEquals(blocks - 1, guarded.adjacencyBlocksSkipped(),
				"and only that block: the rest of the chain is still skipped: " + guarded);
		}
	}

	/** A hub with the given number of lightweight out-edges, each to a fresh node. */
	private long fanOut(GraphStore store, String type, int degree) throws IOException {
		long hub = store.createNode("Hub");
		for (int i = 0; i < degree; i++) {
			store.createEdge(hub, type, store.createNode("Spoke"));
		}
		return hub;
	}

	/**
	 * {@code hasEdge} is the operation {@code min_location} / {@code max_location} were written for on the read path:
	 * a block whose recorded range excludes the target cannot hold a lightweight entry naming it.
	 * <p>
	 * It is a pruned scan, not an index: RELATIONSHIP_STORAGE.md §5.3 used to claim O(log blocks) block reads and a
	 * binary search, which the format does not support — blocks are pushed onto the head of the chain, so block order
	 * is insertion order and there is nothing sorted to search over.
	 */
	@Test
	public void testHasEdgeSkipsTheBlocksThatCannotHoldTheTarget() throws Exception {
		int degree = 30_000;
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long hub;
			long newest;
			long stranger;
			try (Transaction txn = store.begin()) {
				hub = fanOut(store, "E", degree - 1);
				newest = store.createNode("Spoke");
				store.createEdge(hub, "E", newest);
				// Created last, so its location is above every block's recorded maximum.
				stranger = store.createNode("Stranger");
				txn.commit();
			}
			int blocks = impl.adjacencyStore().blockCount(hub);
			assertTrue(blocks >= 3, "the skip only exists on a chain; this node has " + blocks + " block(s)");

			assertTrue(store.hasEdge(hub, newest), "the newest neighbour is an edge");
			assertFalse(store.hasEdge(hub, stranger), "a node the hub was never linked to is not");

			IoStats miss = cost(impl, () -> store.hasEdge(hub, stranger));
			assertEquals(0, miss.adjacencyBlocksDecoded(),
				"no block can hold a location above every recorded maximum, so none is decoded: " + miss);
			assertEquals(blocks, miss.adjacencyBlocksSkipped(), "and all of them are skipped: " + miss);

			IoStats hit = cost(impl, () -> store.hasEdge(hub, newest));
			assertEquals(1, hit.adjacencyBlocksDecoded(),
				"the newest neighbour is in the head block, and the walk stops at the first match: " + hit);

			IoStats scan = cost(impl, () -> store.neighbours(hub, Direction.OUT));
			assertEquals(scan.recordReads(Kind.ADJ), miss.recordReads(Kind.ADJ),
				"a miss still acquires every block, because the chain pointer is inside each of them; what the range "
					+ "check saves is the decode: " + miss + " against " + scan);
			assertTrue(hit.recordReads(Kind.ADJ) < scan.recordReads(Kind.ADJ),
				"a hit is the one case that does cut reads, by stopping at the first match: " + hit + " against "
					+ scan);
		}
	}

	// ---------------------------------------------------------------- indexed lookup

	@Test
	public void testIndexedLookupIsLogarithmic() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 50_000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}
			int depth = impl.indexCatalog().tree().depth(impl.indexCatalog().propertyRootForTest());
			IoStats stats = cost(impl, () -> store.nodesByProperty("i", 12345));

			// Descend the tree, read the leaf, then verify the one candidate against its stored property.
			assertTrue(stats.recordReads(Kind.INDEX) <= 2L * depth + 2,
				"a point lookup over 50,000 nodes must cost about the tree depth (" + depth + "): " + stats);
			// Two, not one: the candidate is checked for liveness and then read for its property, and each resolves
			// the record separately. It is the same page both times, and the cost is bounded by the number of
			// candidates rather than by the size of the store, which is what the budget is really about.
			assertEquals(2, stats.recordReads(Kind.NODE),
				"verifying one candidate must cost a bounded number of node reads: " + stats);
		}
	}

	// ---------------------------------------------------------------- writes

	@Test
	public void testCreatingANodeHasABoundedWriteFootprint() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 1000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}

			IoStats before = impl.counters().snapshot();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 1000; i++) {
					store.createNode("N", Properties.of().set("i", 1_000_000 + i));
				}
				txn.commit();
			}
			IoStats stats = impl.counters().snapshot().since(before);

			assertTrue(stats.recordWrites(Kind.NODE) <= 4000,
				"a node create should be a handful of record touches, not more: " + stats);
			assertEquals(0, stats.recordWrites(Kind.ADJ), "a node with no edges must not touch adjacency: " + stats);
			assertEquals(0, stats.recordWrites(Kind.BLOB), "small properties must not escape to a blob: " + stats);
			assertEquals(1, stats.walSyncs(), "one transaction is one fsync: " + stats);
		}
	}

	@Test
	public void testEdgeCreationTouchesBothEndpointsAndNothingElse() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					ids.add(store.createNode("N"));
				}
				txn.commit();
			}

			IoStats before = impl.counters().snapshot();
			try (Transaction txn = store.begin()) {
				for (int i = 1; i < ids.size(); i++) {
					store.createEdge(ids.get(i - 1), "E", ids.get(i));
				}
				txn.commit();
			}
			IoStats stats = impl.counters().snapshot().since(before);

			assertEquals(0, stats.recordWrites(Kind.REL),
				"a property-free edge must not materialise a relationship record: " + stats);
			assertEquals(0, stats.recordWrites(Kind.ADJ),
				"and at degree two it must not allocate an adjacency block: " + stats);
			assertEquals(0, stats.recordWrites(Kind.PROP), "nor a property block: " + stats);
		}
	}

	@Test
	public void testCountersAreVisibleThroughTheMetricsApi() throws IOException {
		try (GraphStore store = stores.open()) {
			store.createNode("N", Properties.of().set("i", 1));
			store.checkpoint();
			var metrics = store.metrics();
			assertTrue(metrics.io().walBytesAppended() > 0, "the log must have recorded the write: " + metrics);
			assertTrue(metrics.io().checkpoints() >= 1, "the checkpoint must be counted: " + metrics);
			assertTrue(metrics.averageCheckpointMillis() >= 0, metrics.toString());
			assertEquals(0, metrics.walLagBytes(), "FSYNC_ON_COMMIT leaves nothing unsynced: " + metrics);
			assertTrue(metrics.toString().contains("StoreMetrics"));
		}
	}
}
