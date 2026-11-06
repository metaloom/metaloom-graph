package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.TransactionConflictException;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Deleting a node concurrently with a change to the same node's labels or properties.
 *
 * <p>
 * Found by {@code IsolationAnomalyStressTest.testIndexesAgreeWithAFullScanInEverySnapshot}, about once per hundred
 * seconds of load: a snapshot whose {@code nodesByLabel("Hot")} returned a node the scan did not have. The two writes
 * never met at commit. {@code deleteNode} removes the node from the index entries of the labels <i>its snapshot</i>
 * held and then frees the slot — which writes the allocation bitmap (a ledger, exempt from conflict detection) and the
 * slot's generation, not the label bytes. {@code addNodeLabel} writes the label bytes and the index. Different bytes,
 * so first-committer-wins saw nothing, both committed, and the index kept an entry for a node that no longer existed.
 * That is not the write skew {@code SNAPSHOT} is documented to allow between transactions' <i>data</i>; it breaks an
 * invariant of the engine itself, and {@code verify(FULL)} reports the store damaged.
 * </p>
 *
 * <p>
 * The two transactions are interleaved with latches rather than by chance, in both commit orders: whichever commits
 * second must be refused, and a retried delete must then see — and unindex — the label it missed.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DeleteRaceTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** Runs {@code change} in a transaction that has done its work but waits to commit until {@code release}. */
	private static CompletableFuture<Throwable> held(GraphStore store, Change change, CountDownLatch done,
		CountDownLatch release) {
		return CompletableFuture.supplyAsync(() -> {
			try (Transaction txn = store.begin()) {
				change.apply();
				done.countDown();
				release.await(30, TimeUnit.SECONDS);
				txn.commit();
				return null;
			} catch (Throwable t) {
				done.countDown();
				return t;
			}
		});
	}

	@FunctionalInterface
	private interface Change {
		void apply() throws Exception;
	}

	/**
	 * A store the size of the stress test's: four hundred items, a third of them already {@code Hot}, so the label
	 * and property index entries of the node under test sit on different B+tree leaves from each other. With a dozen
	 * nodes every index change lands on one leaf, the two writers collide there by accident, and the race this class
	 * is about cannot happen — the "layout rather than isolation" that {@code TRANSACTIONS.md} §3 warns about.
	 */
	private static long node(GraphStore store) throws Exception {
		long id;
		try (Transaction txn = store.begin()) {
			// First, so its entry is at the head of the Item range and far from the Hot range of the label tree.
			id = store.createNode("Item", Properties.of().set("bucket", 1L));
			txn.commit();
		}
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 400; i++) {
				long other = store.createNode("Item", Properties.of().set("bucket", (long) (i % 8)));
				if (i % 3 == 0) {
					store.addNodeLabel(other, "Hot");
				}
			}
			txn.commit();
		}
		return id;
	}

	@Test
	public void testALabelAddedUnderADeleteThatCommittedFirstIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Item", "bucket");
			long id = node(store);
			CountDownLatch added = new CountDownLatch(1);
			CountDownLatch deleted = new CountDownLatch(1);
			CompletableFuture<Throwable> labeller = held(store, () -> store.addNodeLabel(id, "Hot"), added, deleted);

			added.await(30, TimeUnit.SECONDS);
			try (Transaction txn = store.begin()) {
				store.deleteNode(id);
				txn.commit();
			}
			deleted.countDown();

			Throwable outcome = labeller.get(30, TimeUnit.SECONDS);
			System.out.println("[delete-race] label under committed delete: " + outcome);
			assertInstanceOf(TransactionConflictException.class, outcome,
				"the label write on a node deleted since its snapshot must not commit");
			assertFalse(store.nodesByLabel("Hot").contains(id), "no index entry for a deleted node");
			assertFalse(store.nodesByLabel("Item").contains(id));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testADeleteUnderALabelThatCommittedFirstIsRefusedAndItsRetryUnindexesTheLabel() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = node(store);
			CountDownLatch deleting = new CountDownLatch(1);
			CountDownLatch labelled = new CountDownLatch(1);
			CompletableFuture<Throwable> deleter = held(store, () -> store.deleteNode(id), deleting, labelled);

			deleting.await(30, TimeUnit.SECONDS);
			try (Transaction txn = store.begin()) {
				store.addNodeLabel(id, "Hot");
				txn.commit();
			}
			labelled.countDown();

			Throwable outcome = deleter.get(30, TimeUnit.SECONDS);
			System.out.println("[delete-race] delete under committed label: " + outcome);
			assertInstanceOf(TransactionConflictException.class, outcome,
				"a delete that did not see a label committed since its snapshot must not commit");
			assertTrue(store.nodesByLabel("Hot").contains(id));

			// The auto-commit statement retries on its own, against a snapshot that has the label.
			store.deleteNode(id);
			assertFalse(store.nodesByLabel("Hot").contains(id));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAPropertyChangeUnderADeleteThatCommittedFirstIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Item", "bucket");
			long id = node(store);
			CountDownLatch set = new CountDownLatch(1);
			CountDownLatch deleted = new CountDownLatch(1);
			CompletableFuture<Throwable> setter = held(store,
				() -> store.setNodeProperties(id, Properties.of().set("bucket", 7L)), set, deleted);

			set.await(30, TimeUnit.SECONDS);
			try (Transaction txn = store.begin()) {
				store.deleteNode(id);
				txn.commit();
			}
			deleted.countDown();

			assertInstanceOf(TransactionConflictException.class, setter.get(30, TimeUnit.SECONDS));
			assertFalse(store.nodesByProperty("bucket", 7L).contains(id));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAnEdgeToANodeDeletedSinceItsSnapshotIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = node(store);
			long other;
			try (Transaction txn = store.begin()) {
				other = store.createNode("Other");
				txn.commit();
			}
			CountDownLatch linked = new CountDownLatch(1);
			CountDownLatch deleted = new CountDownLatch(1);
			CompletableFuture<Throwable> linker = held(store, () -> store.createEdge(other, "KNOWS", id), linked,
				deleted);
			linked.await(30, TimeUnit.SECONDS);
			try (Transaction txn = store.begin()) {
				store.deleteNode(id);
				txn.commit();
			}
			deleted.countDown();
			assertInstanceOf(TransactionConflictException.class, linker.get(30, TimeUnit.SECONDS),
				"an edge must not be committed onto a node deleted since the writer's snapshot");
			assertTrue(store.neighbours(other, io.metaloom.graph.core.api.Direction.OUT).isEmpty(),
				"no adjacency entry pointing at a deleted node");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testADeleteUnderAnEdgeThatCommittedFirstIsRefusedAndItsRetryRemovesTheEdge() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = node(store);
			long other;
			try (Transaction txn = store.begin()) {
				other = store.createNode("Other");
				txn.commit();
			}
			CountDownLatch deleting = new CountDownLatch(1);
			CountDownLatch linked = new CountDownLatch(1);
			CompletableFuture<Throwable> deleter = held(store, () -> store.deleteNode(id), deleting, linked);
			deleting.await(30, TimeUnit.SECONDS);
			store.createEdge(other, "KNOWS", id);
			linked.countDown();
			assertInstanceOf(TransactionConflictException.class, deleter.get(30, TimeUnit.SECONDS));
			store.deleteNode(id);
			assertTrue(store.neighbours(other, io.metaloom.graph.core.api.Direction.OUT).isEmpty());
			store.verify(VerifyLevel.FULL);
		}
	}
}
