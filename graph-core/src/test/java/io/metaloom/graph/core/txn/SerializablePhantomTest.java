package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.TransactionConflictException;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Write skew through a predicate read, at {@code SERIALIZABLE}, scheduled so that the two transactions write
 * <b>disjoint</b> bytes — nothing but the read set can refuse either of them.
 *
 * <p>
 * {@code SnapshotIsolationTest.testSerializableRefusesWriteSkew} proves the level refuses the classic two-doctor write
 * skew, but those transactions also share index pages, so a write-write conflict can be what saves it — the spec calls
 * relying on that "relying on an accident". The cases here are the shapes where no write collides:
 * </p>
 *
 * <ul>
 * <li>T1 evaluates a predicate; T2 reads what T1 is about to write, finds it not yet done, and makes T1's predicate
 * false; T2 commits; T1 acts on its predicate and commits. Each read what the other wrote, so no serial order explains
 * both, and one of them must be refused.</li>
 * <li>The predicate is answered without touching a page — a label or property key the dictionary has never seen — or
 * from the transaction's own copy of a page it wrote blindly first. Those are the reads a page-granular read set can
 * miss. The known-label and full-scan cases are the controls, answered by pages the read set does record.</li>
 * </ul>
 *
 * <p>
 * Page-granular validation is allowed to refuse more than this — the free-slot bitmap is one page for thousands of
 * records, and reading it is enough to be refused for any allocation in the segment — so several cases are caught by
 * a read of a metadata page rather than of the predicate itself. That is sound and asserted as such: the property is
 * that both never commit.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 6.4
 */
@Tag("unit")
public class SerializablePhantomTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/**
	 * A label nobody has used yet. {@code nodesByLabel} answers that from the dictionary without reading any index
	 * page, so unless something stands in for that read, the creation of the first such node is invisible to T1's
	 * validation. T1's decision is a relationship property, which reads no NODE or INDEX page at all.
	 */
	@Test
	public void testPhantomOnALabelNeverSeenBefore() throws Exception {
		try (GraphStore store = stores.open()) {
			long rel = decisionRelationship(store);
			assertWriteSkewRefused(store, rel,
				() -> store.nodesByLabel("Doctor").isEmpty(),
				() -> store.createNode("Doctor"));
		}
	}

	/** The same through the composite lookup, which short-circuits on an unknown label the same way. */
	@Test
	public void testPhantomOnALabelAndPropertyNeverSeenBefore() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Other", Properties.of().set("ward", 1L));
			long rel = decisionRelationship(store);
			assertWriteSkewRefused(store, rel,
				() -> store.nodesByLabelAndProperty("Nurse", "ward", 3L).isEmpty(),
				() -> store.createNode("Nurse", Properties.of().set("ward", 3L)));
		}
	}

	/**
	 * A point read rather than a predicate: the property of one node, under a key nobody has used. The miss returns
	 * before the node's record is read at all, so T2 setting it changes nothing T1's read set holds.
	 */
	@Test
	public void testStaleReadOfAPropertyKeyNeverSeenBefore() throws Exception {
		try (GraphStore store = stores.open()) {
			long watched = store.createNode("Watched");
			long rel = decisionRelationship(store);
			assertWriteSkewRefused(store, rel,
				() -> store.nodeProperty(watched, "flag") == null,
				() -> store.setNodeProperties(watched, Properties.of().set("flag", true)));
		}
	}

	/** The same for a label test on one node. */
	@Test
	public void testStaleReadOfALabelNeverSeenBefore() throws Exception {
		try (GraphStore store = stores.open()) {
			long watched = store.createNode("Watched");
			long rel = decisionRelationship(store);
			assertWriteSkewRefused(store, rel,
				() -> !store.nodeHasLabel(watched, "Retired"),
				() -> store.addNodeLabel(watched, "Retired"));
		}
	}

	/**
	 * What the fix costs, pinned so it stays bounded: a transaction that misses a name and then introduces it itself
	 * has nobody to conflict with, but the commit cannot tell who introduced the name and refuses it once. The retry
	 * finds the name in the dictionary — which outlives a rollback — and commits. A transaction that already knew the
	 * name is never refused for it.
	 */
	@Test
	public void testCreateIfAbsentOnANewNameCommitsAfterOneRetry() throws Exception {
		try (GraphStore store = stores.open()) {
			int[] attempts = { 0 };
			store.inTransaction(() -> {
				attempts[0]++;
				if (store.nodesByLabel("Singleton").isEmpty()) {
					store.createNode("Singleton");
				}
				return null;
			}, 3, IsolationLevel.SERIALIZABLE);
			assertTrue(attempts[0] <= 2, "a create-if-absent on a new name took " + attempts[0] + " attempts");
			assertTrue(store.nodesByLabel("Singleton").size() == 1);

			attempts[0] = 0;
			store.inTransaction(() -> {
				attempts[0]++;
				if (store.nodesByLabel("Singleton").size() == 1) {
					store.createNode("Singleton");
				}
				return null;
			}, 3, IsolationLevel.SERIALIZABLE);
			assertTrue(attempts[0] == 1, "a name already known must not cost a retry, took " + attempts[0]);
			assertTrue(store.nodesByLabel("Singleton").size() == 2);
		}
	}

	/** A property key the dictionary has never seen. */
	@Test
	public void testPhantomOnAPropertyKeyNeverSeenBefore() throws Exception {
		try (GraphStore store = stores.open()) {
			long rel = decisionRelationship(store);
			assertWriteSkewRefused(store, rel,
				() -> store.nodesByProperty("badge", 7L).isEmpty(),
				() -> store.createNode("Other", Properties.of().set("badge", 7L)));
		}
	}

	/** The label is known, so the predicate read walks the label tree: the control for the cases above. */
	@Test
	public void testPhantomOnAKnownLabel() throws Exception {
		try (GraphStore store = stores.open()) {
			store.deleteNode(store.createNode("Doctor"));
			long rel = decisionRelationship(store);
			assertWriteSkewRefused(store, rel,
				() -> store.nodesByLabel("Doctor").isEmpty(),
				() -> store.createNode("Doctor"));
		}
	}

	/** A whole-store scan, which reads the free-slot bitmap through the transaction's view. */
	@Test
	public void testPhantomOnAFullScan() throws Exception {
		try (GraphStore store = stores.open()) {
			long rel = decisionRelationship(store);
			int[] before = new int[1];
			store.forEachNode(id -> before[0]++);
			int expected = before[0];
			assertWriteSkewRefused(store, rel,
				() -> {
					int[] count = new int[1];
					store.forEachNode(id -> count[0]++);
					return count[0] == expected;
				},
				() -> store.createNode("Late"));
		}
	}

	/**
	 * A blind write first, then a read of a <em>different</em> record on the same page.
	 *
	 * <p>
	 * Creating a node copies the record page into the transaction's overlay, and every later read of that page is
	 * served from the copy. The copy's base is what the transaction read, so another transaction changing a different
	 * record on the page is a stale read exactly as if the read had gone through the view, and has to be validated as
	 * one.
	 * </p>
	 */
	@Test
	public void testStaleReadFromTheOverlayAfterABlindWrite() throws Exception {
		try (GraphStore store = stores.open()) {
			// Every record on one page, each more than a merge gap from the next, so no two writes can be reported as
			// overlapping: the ledger pair T1 links, the node T2 changes, and the tail T1's blind create lands on.
			long ledgerA = store.createNode("Ledger");
			spacers(store);
			long ledgerB = store.createNode("Ledger");
			spacers(store);
			long watched = store.createNode("Watched");
			spacers(store);
			store.createEdge(store.createNode("Warm"), "DECIDED", store.createNode("Warm"));
			spacers(store);

			assertWriteSkewRefused(store,
				() -> {
					// The blind write: allocating a node copies the record page into the overlay without reading it.
					store.createNode("Blind");
					return store.nodeProperties(watched).isEmpty();
				},
				() -> store.createEdge(ledgerA, "DECIDED", ledgerB),
				() -> store.degreeOut(ledgerA) == 0,
				() -> store.setNodeProperties(watched, Properties.of().set("k", 1L)));
		}
	}

	// ---------------------------------------------------------------- helpers

	@FunctionalInterface
	interface Check {
		boolean holds() throws IOException;
	}

	@FunctionalInterface
	interface Work {
		void run() throws IOException;
	}

	private static void spacers(GraphStore store) throws IOException {
		for (int i = 0; i < 3; i++) {
			store.createNode("Spacer");
		}
	}

	/** The relationship T1 records its decision on. Created up front so T1's write allocates nothing T2 touches. */
	private static long decisionRelationship(GraphStore store) throws IOException {
		long a = store.createNode("Ledger");
		long b = store.createNode("Ledger");
		long rel = store.createRelationship(a, "DECIDED", b, Properties.of().set("decided", 0L));
		// Property cells after the decision's, so whatever T2 allocates lands more than a merge gap from the cells T1
		// frees when it rewrites the decision.
		store.createNode("Pad", Properties.of().set("pad", "x".repeat(200)));
		return rel;
	}

	/** The decision is the relationship property, and T2's check is that it is still unset. */
	private void assertWriteSkewRefused(GraphStore store, long rel, Check predicate, Work falsify) throws Exception {
		assertWriteSkewRefused(store, predicate,
			() -> store.setRelationshipProperties(rel, Properties.of().set("decided", 1L)),
			() -> (Long) store.relationshipProperties(rel).get("decided") == 0L,
			falsify);
	}

	/**
	 * T1 reads {@code predicate}; T2 evaluates {@code undecided} and, finding T1's decision not made, runs
	 * {@code falsify} and commits; T1 then runs {@code decide} and commits. Each read what the other wrote, so at most
	 * one may commit.
	 */
	private void assertWriteSkewRefused(GraphStore store, Check predicate, Work decide, Check undecided, Work falsify)
		throws Exception {
		Transaction t1 = store.begin(IsolationLevel.SERIALIZABLE);
		boolean t1Committed = false;
		TransactionConflictException refusal = null;
		AtomicReference<Throwable> t2Failure = new AtomicReference<>();
		boolean[] t2Committed = { false };
		try {
			assertTrue(predicate.holds(), "the predicate must hold in T1's snapshot");

			Thread t2 = Thread.ofPlatform().name("t2").start(() -> {
				try (Transaction txn = store.begin(IsolationLevel.SERIALIZABLE)) {
					assertTrue(undecided.holds(), "T2 must not see T1's uncommitted decision");
					falsify.run();
					txn.commit();
					t2Committed[0] = true;
				} catch (TransactionConflictException e) {
					// T2 losing is a legitimate outcome as well; what matters is that both do not win.
				} catch (Throwable e) {
					t2Failure.set(e);
				}
			});
			t2.join(30_000);
			if (t2.isAlive()) {
				throw new AssertionError("T2 did not finish within 30 s");
			}
			if (t2Failure.get() != null) {
				throw new AssertionError("T2 failed", t2Failure.get());
			}

			decide.run();
			t1.commit();
			t1Committed = true;
		} catch (TransactionConflictException e) {
			refusal = e;
		} finally {
			if (!t1Committed) {
				t1.rollback();
			}
		}
		assertTrue(t2Committed[0], "T2 ran alone at its commit and should have won");
		assertFalse(t1Committed, "both SERIALIZABLE transactions committed although each read what the other wrote"
			+ " — write skew through a read the read set did not cover");
		assertTrue(refusal != null && refusal.isReadConflict(), "T1 wrote nothing T2 wrote, so only its reads can"
			+ " refuse it: " + refusal);
		System.out.println("[serializable] refused: " + refusal.getMessage());
		store.verify(VerifyLevel.FULL);
	}
}
