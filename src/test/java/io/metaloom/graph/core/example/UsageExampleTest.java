package io.metaloom.graph.core.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionReport;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.StoreMetrics;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.Kind;

/**
 * How to use the API, as executable documentation.
 *
 * <p>
 * The example in the README and in <a href="../../../../../../../../spec/API.md">spec/API.md</a> is here as a running,
 * asserting test, so an API change that would invalidate the documentation breaks the build instead. Read it top to
 * bottom: open, write in a transaction, read back, traverse, look up by index, delete, reclaim space, close.
 * </p>
 *
 * <p>
 * Two things about this engine drive most of the usage decisions and are called out where they bite:
 * </p>
 * <ul>
 * <li><b>An id is an address.</b> A {@code long} id decodes arithmetically into a file and a byte offset, so reading an
 * element by id costs no index probe. Ids are worth holding on to, and worth storing in your own structures.</li>
 * <li><b>Durability costs an fsync per commit.</b> A mutation outside a transaction commits on its own, so a bulk load
 * written that way pays one fsync per record. Batching is not a micro-optimisation here; it is the difference between
 * hundreds and tens of thousands of writes per second.</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../spec/API.md">spec/API.md</a>
 */
@Tag("unit")
public class UsageExampleTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/**
	 * The whole API in one pass, in the order a caller meets it.
	 */
	@Test
	public void testTheWalkthrough() throws Exception {
		// A store is a directory. Opening it creates it if it is not there, and takes an exclusive LOCK file, so
		// exactly one process at a time may have it open.
		//
		//   try (GraphStore store = GraphStore.open(Path.of("/data/mygraph"))) { ... }
		//
		// The fixture below does the same thing against a temporary directory, with small segments so the test
		// files stay tiny.
		try (GraphStore store = stores.open()) {

			long person;
			long vehicle;

			// One transaction for a bulk load: durability costs an fsync per commit, so batching matters. Every
			// mutating call joins the caller's transaction if there is one, and otherwise runs in its own.
			try (Transaction txn = store.begin()) {

				// Properties are stored as their real types, not as text. An absent key and a stored null are
				// different things, and reading a property as the wrong type throws rather than coercing.
				person = store.createNode("Person", Properties.of()
					.set("name", "Wes Anderson")
					.set("born", 1969));

				vehicle = store.createNode("Vehicle", Properties.of()
					.set("name", "VW Beetle"));

				// A property-free edge: 16 bytes in total, no relationship record, no id. Use this for the vast
				// majority of edges — it is what makes the store dense.
				store.createEdge(person, "OWNS", vehicle);

				// An edge that carries properties gets a record and a stable id, so it can be read, updated and
				// deleted on its own terms.
				long drove = store.createRelationship(person, "DROVE", vehicle, Properties.of()
					.set("since", Instant.parse("2019-04-01T00:00:00Z")));

				// Reads inside the transaction see its own writes.
				assertEquals("Person", store.nodeLabel(person));
				assertEquals("DROVE", store.relationshipType(drove));

				// Nothing above has reached a data file yet. commit() makes the redo log durable first, and only
				// then applies the change; a transaction closed without committing rolls back for free.
				txn.commit();
			}

			// ---------------------------------------------------------------- reading back

			assertTrue(store.nodeExists(person));
			assertEquals("Person", store.nodeLabel(person));

			Properties props = store.nodeProperties(person);
			assertEquals("Wes Anderson", props.getString("name"));
			assertEquals(1969, props.getInt("born"));

			// A single property, without materialising the whole block.
			assertEquals("VW Beetle", store.nodeProperty(vehicle, "name"));

			// ---------------------------------------------------------------- traversal

			// Neighbours of a node, filtered by direction and relationship type. At this degree the edges live
			// inline in the node record, so this costs zero extra reads.
			for (Edge edge : store.neighbours(person, Direction.OUT, Set.of("OWNS"))) {
				assertEquals("VW Beetle", store.nodeProperties(edge.neighbourId()).getString("name"));
			}

			List<Edge> outgoing = store.neighbours(person, Direction.OUT);
			assertEquals(2, outgoing.size(), "one lightweight edge and one materialised relationship");

			// Both directions are always maintained, at both endpoints. Nothing has to be asked for twice.
			assertEquals(2, store.neighbours(vehicle, Direction.IN).size());
			assertEquals(2, store.degreeOut(person));
			assertEquals(2, store.degreeIn(vehicle));

			// A lightweight edge has no relationship id; a materialised one does, and it resolves to the record.
			Edge owns = outgoing.stream().filter(e -> e.type().equals("OWNS")).findFirst().orElseThrow();
			Edge drove = outgoing.stream().filter(e -> e.type().equals("DROVE")).findFirst().orElseThrow();
			assertFalse(owns.isMaterialised(), "a property-free edge costs no record");
			assertTrue(drove.isMaterialised());
			assertEquals(Instant.parse("2019-04-01T00:00:00Z"),
				store.relationshipProperties(drove.relationshipId()).getInstant("since"));

			// ---------------------------------------------------------------- indexed lookup

			// Both indexes are maintained inside the writing transaction, so a lookup never lags a commit.
			List<Long> people = store.nodesByLabel("Person");
			assertEquals(List.of(person), people);

			List<Long> born69 = store.nodesByProperty("born", 1969);
			assertEquals(List.of(person), born69);

			// The property index is keyed on a hash of the value, so candidates are verified against the stored
			// property before being returned. A lookup never reports a node that does not really match.
			assertTrue(store.nodesByProperty("born", 1970).isEmpty());

			// ---------------------------------------------------------------- maintenance

			// Every structural invariant, on a live store. FULL also checks bidirectional adjacency, degree
			// counters, dangling references, chain termination and orphaned blocks. It throws listing every
			// violation it found, so a passing call is a real statement about the file.
			store.verify(VerifyLevel.FULL);

			StoreStats stats = store.stats();
			assertEquals(2, stats.nodeCount());
			assertEquals(1, stats.relationshipCount(), "the lightweight edge has no record");

			// Give space back. reclaim() moves nothing and keeps every id valid; compact() closes holes *inside*
			// pages and is the only thing in the engine that invalidates an id. Prefer reclaim().
			store.reclaim();
			CompactionReport report = store.compact();
			assertTrue(report.livenessAfter() >= report.livenessBefore());
		}
	}

	/**
	 * Ids survive a reopen, because nothing about them depends on process state. This is the property the whole
	 * design rests on, and it is why an id is worth storing in your own structures.
	 */
	@Test
	public void testIdsAreStableAcrossAReopen() throws Exception {
		long person;
		long vehicle;
		try (GraphStore store = stores.open()) {
			person = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));
			vehicle = store.createNode("Vehicle", Properties.of().set("name", "VW Beetle"));
			store.createEdge(person, "OWNS", vehicle);
			// Each call above committed on its own, because none of them was inside a transaction. That is three
			// fsyncs for three records — correct, and the slow way to load data.
			store.checkpoint();
		}

		try (GraphStore store = stores.open()) {
			assertEquals("Wes Anderson", store.nodeProperties(person).getString("name"));
			assertEquals(1, store.neighbours(person, Direction.OUT, Set.of("OWNS")).size());
			assertEquals(vehicle, store.neighbours(person, Direction.OUT).get(0).neighbourId());
		}
	}

	/**
	 * A rolled back transaction costs nothing and leaves nothing behind — the private page copies are simply
	 * discarded. Uncommitted data is structurally incapable of reaching a data file.
	 */
	@Test
	public void testAnUncommittedTransactionLeavesNothingBehind() throws Exception {
		try (GraphStore store = stores.open()) {
			long before = store.stats().nodeCount();

			try (Transaction txn = store.begin()) {
				store.createNode("Person", Properties.of().set("name", "never committed"));
				assertTrue(txn.isActive());
				assertTrue(txn.touchedPages() > 0, "the change exists, in private page copies");
				// No commit() — close() rolls back by discarding them, which costs nothing.
			}

			assertEquals(before, store.stats().nodeCount());
			assertTrue(store.nodesByProperty("name", "never committed").isEmpty());
		}
	}

	/**
	 * Deleting a node takes its properties, its adjacency and every relationship attached to it with it. There is no
	 * way to end up with an edge pointing at a node that is gone.
	 */
	@Test
	public void testDeletingANodeCascades() throws Exception {
		try (GraphStore store = stores.open()) {
			long person = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));
			long vehicle = store.createNode("Vehicle", Properties.of().set("name", "VW Beetle"));

			store.createEdge(person, "OWNS", vehicle);
			long drove = store.createRelationship(person, "DROVE", vehicle, Properties.of()
				.set("since", Instant.parse("2019-04-01T00:00:00Z")));

			assertEquals(2, store.degreeIn(vehicle));

			store.deleteNode(person);

			assertFalse(store.nodeExists(person));
			assertFalse(store.relationshipExists(drove), "the relationship went with its endpoint");
			assertTrue(store.nodeExists(vehicle), "the other endpoint did not");
			assertEquals(0, store.degreeIn(vehicle), "and its degree came back down");
			assertTrue(store.neighbours(vehicle, Direction.IN).isEmpty());

			// An individual edge can also be removed on its own. A lightweight edge is addressed by its endpoints
			// and type, because it has no id; a materialised one by its id.
			long another = store.createNode("Person", Properties.of().set("name", "Owen Wilson"));
			store.createEdge(another, "OWNS", vehicle);
			assertTrue(store.deleteEdge(another, "OWNS", vehicle));
			assertFalse(store.deleteEdge(another, "OWNS", vehicle), "false, not an exception, when there is none");

			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * Properties are typed and replaced wholesale. {@code setNodeProperties} is a replacement, not a merge — read,
	 * modify and write back when a merge is what you want.
	 */
	@Test
	public void testUpdatingProperties() throws Exception {
		try (GraphStore store = stores.open()) {
			long person = store.createNode("Person", Properties.of()
				.set("name", "Wes Anderson")
				.set("born", 1969));

			Properties updated = store.nodeProperties(person).set("born", 1970);
			store.setNodeProperties(person, updated);

			assertEquals(1970, store.nodeProperties(person).getInt("born"));
			assertEquals("Wes Anderson", store.nodeProperties(person).getString("name"));

			// The index followed the update, in the same transaction as the write.
			assertTrue(store.nodesByProperty("born", 1969).isEmpty());
			assertEquals(List.of(person), store.nodesByProperty("born", 1970));

			// An absent key reads as null; a stored null is a value in its own right, and the two are
			// distinguishable.
			store.setNodeProperties(person, Properties.of().set("name", "Wes Anderson").setNull("born"));
			Properties props = store.nodeProperties(person);
			assertTrue(props.has("born"));
			assertTrue(props.isNull("born"));
			assertFalse(props.has("died"));
		}
	}

	/**
	 * Breadth-first expansion to a depth, with the same direction and type filters as {@code neighbours}. The start
	 * node is not part of the result, and a node is reported once however many paths reach it.
	 */
	@Test
	public void testTraversingSeveralHops() throws Exception {
		try (GraphStore store = stores.open()) {
			long wes = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));
			long owen = store.createNode("Person", Properties.of().set("name", "Owen Wilson"));
			long bill = store.createNode("Person", Properties.of().set("name", "Bill Murray"));
			long beetle = store.createNode("Vehicle", Properties.of().set("name", "VW Beetle"));

			try (Transaction txn = store.begin()) {
				store.createEdge(wes, "KNOWS", owen);
				store.createEdge(owen, "KNOWS", bill);
				store.createEdge(bill, "OWNS", beetle);
				txn.commit();
			}

			assertEquals(List.of(owen), ids(store.traverse(wes, Direction.OUT, Set.of("KNOWS"), 1)));
			assertEquals(List.of(owen, bill), ids(store.traverse(wes, Direction.OUT, Set.of("KNOWS"), 2)));

			// Without a type filter the third hop reaches the vehicle too.
			assertEquals(List.of(owen, bill, beetle), ids(store.traverse(wes, Direction.OUT, null, 3)));

			// Direction is relative to the node being traversed from, so the same graph reads backwards from the
			// far end.
			assertEquals(List.of(bill, owen, wes), ids(store.traverse(beetle, Direction.IN, null, 3)));
		}
	}

	/**
	 * Compaction is the one operation that invalidates ids. A stale id is <b>rejected</b> rather than misresolved —
	 * the generation of a freed slot is advanced, so it can never quietly name a different record.
	 */
	@Test
	public void testWhatCompactionDoesToHeldIds() throws Exception {
		try (GraphStore store = stores.open()) {
			long keep = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));

			// Churn: create and delete enough records that the live ones are scattered below the high water mark.
			try (Transaction txn = store.begin()) {
				long[] doomed = new long[2000];
				for (int i = 0; i < doomed.length; i++) {
					doomed[i] = store.createNode("Extra", Properties.of().set("i", i));
				}
				for (long id : doomed) {
					store.deleteNode(id);
				}
				txn.commit();
			}

			CompactionReport report = store.compact();
			assertTrue(report.livenessAfter() > report.livenessBefore());

			// The record is still there and still correct — but do not assume the id you were holding still names
			// it. Re-find it through an index, which compaction rewrites in the same transaction as the move.
			List<Long> found = store.nodesByProperty("name", "Wes Anderson");
			assertEquals(1, found.size());
			assertEquals("Wes Anderson", store.nodeProperties(found.get(0)).getString("name"));

			// In this run nothing above it moved, so the held id happens to still be valid. Relying on that is the
			// mistake this test exists to name: the guarantee is detection, not stability.
			if (store.nodeExists(keep)) {
				assertEquals("Wes Anderson", store.nodeProperties(keep).getString("name"));
			}

			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The engine's own numbers, which are what you monitor. Subtracting two {@code metrics()} snapshots costs an
	 * interval.
	 */
	@Test
	public void testIntrospection() throws Exception {
		try (GraphStore store = stores.open()) {
			long person = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));
			long vehicle = store.createNode("Vehicle", Properties.of().set("name", "VW Beetle"));
			store.createEdge(person, "OWNS", vehicle);

			StoreStats stats = store.stats();
			assertEquals(2, stats.nodeCount());
			assertEquals(0, stats.relationshipCount());
			assertEquals(3, stats.labelCount(), "Person, Vehicle and OWNS share one dictionary");
			assertEquals(1, stats.keyCount(), "name");
			assertTrue(stats.bytesPerNode() > 0);

			// storageBytes is what the store has actually committed, not the nominal segment size — the files are
			// sparse, and the number to watch is this one.
			assertTrue(stats.storageBytes() >= stats.recordBytes());

			// metrics() is the other half: counters rather than contents. Two snapshots subtract into an interval.
			StoreMetrics before = store.metrics();
			store.nodeProperties(person);
			IoStats interval = store.metrics().io().since(before.io());
			assertTrue(interval.recordReads(Kind.NODE) > 0, "reads are counted per kind");
			assertNotEquals(0, store.metrics().io().totalRecordWrites());
		}
	}

	private static List<Long> ids(List<Edge> edges) {
		return edges.stream().map(Edge::neighbourId).toList();
	}

	/**
	 * The read-view paragraph of the README, executable.
	 *
	 * <p>
	 * The README uses an {@code assert} inside the view, which is disabled at runtime unless someone passes {@code -ea}
	 * and is therefore documentation rather than a check. Here it is a real assertion, so the claim the README makes —
	 * that a traversal repeated inside one view returns the same answer while a writer commits — cannot quietly stop
	 * being true.
	 * </p>
	 */
	@Test
	public void testTheReadViewWalkthrough() throws Exception {
		try (GraphStore store = stores.open()) {
			long person = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));
			store.createEdge(person, "OWNS", store.createNode("Vehicle", Properties.of().set("name", "Beetle")));

			try (ReadTransaction view = store.beginRead()) {
				List<Edge> before = store.traverse(person, Direction.OUT, null, 3);
				assertEquals(1, before.size());

				store.createEdge(person, "OWNS", store.createNode("Vehicle", Properties.of().set("name", "Bus")));

				assertEquals(before, store.traverse(person, Direction.OUT, null, 3),
					"the view must not move while the writer commits underneath it");
				assertEquals(1, store.snapshotStats().openViews());
				assertTrue(store.snapshotStats().retainedBytes() > 0,
					"and the store must report what holding it open costs");
			}

			assertEquals(2, store.traverse(person, Direction.OUT, null, 3).size(),
				"and the new edge is there once the view is closed");
			store.createNode("Trigger");
			assertEquals(0, store.snapshotStats().retainedBytes(), "closing the view releases what it held");
		}
	}

	/** {@code inTransaction} runs the work, commits it, and hands back what it returned. */
	@Test
	public void testInTransaction() throws Exception {
		try (GraphStore store = stores.open()) {
			long person = store.inTransaction(() -> {
				long id = store.createNode("Person", Properties.of().set("name", "Bill Murray"));
				store.createEdge(id, "OWNS", store.createNode("Vehicle"));
				return id;
			});

			assertTrue(store.nodeExists(person));
			assertEquals("Bill Murray", store.nodeProperties(person).getString("name"));
			assertEquals(1, store.degreeOut(person), "everything in the unit committed together");
		}
	}
}
