package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Calls a thread can make against its own locks, which used to wedge the store instead of failing.
 *
 * <p>
 * The apply latch is a {@link java.util.concurrent.locks.StampedLock} and the write gate a read-write lock, and neither
 * upgrades: a commit waits for every read stamp including the caller's own, and an exclusive operation waits for every
 * gate read hold including the one the caller's open transaction has. Each of these hung forever, holding the commit
 * lock or the gate while it did, so every other thread on the store hung behind it. The rule now is that the call is
 * refused on the spot and the store stays usable.
 * </p>
 */
@Tag("unit")
@Timeout(30)
public class SelfDeadlockTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testAWriteWhileHoldingACursorIsRefusedRatherThanDeadlocking() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("A");
			long b = store.createNode("B");
			store.createEdge(a, "R", b);

			try (NeighbourCursor cursor = store.neighbourCursor(a, Direction.OUT, null)) {
				assertTrue(cursor.next());
				assertThrows(GraphStoreException.class, () -> store.createNode("C"),
					"an auto-commit write cannot commit while this thread holds the cursor's read latch");
			}
			long c = store.createNode("C");
			assertTrue(store.nodeExists(c), "the store must still accept writes once the cursor is closed");
			assertEquals(3, store.stats().nodeCount(), "the refused write must have left nothing behind");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAWriteInsideAScanCallbackIsRefusedRatherThanDeadlocking() throws Exception {
		try (GraphStore store = stores.open()) {
			for (int i = 0; i < 4; i++) {
				store.createNode("A", Properties.of().set("i", (long) i));
			}
			assertThrows(GraphStoreException.class,
				() -> store.forEachNode(id -> {
					try {
						store.setNodeProperties(id, Properties.of().set("i", -1L));
					} catch (java.io.IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				}));
			// The supported shape: collect, then write.
			java.util.List<Long> ids = new java.util.ArrayList<>();
			store.forEachNode(ids::add);
			for (long id : ids) {
				store.setNodeProperties(id, Properties.of().set("i", -1L));
			}
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testCommittingWithAnOpenCursorInsideATransactionIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("A");
			long b = store.createNode("B");
			store.createEdge(a, "R", b);

			Transaction txn = store.begin();
			store.createNode("Inside");
			NeighbourCursor cursor = store.neighbourCursor(a, Direction.OUT, null);
			assertThrows(GraphStoreException.class, txn::commit);
			cursor.close();
			assertFalse(txn.isActive(), "a refused commit ends the transaction, as a conflict does");
			assertFalse(store.hasWriteTransaction());
			assertEquals(2, store.stats().nodeCount(), "the refused transaction was rolled back");
			store.createNode("After");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testCheckpointAndVerifyInsideOwnTransactionAreRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("A");
				assertThrows(GraphStoreException.class, store::checkpoint);
				assertThrows(GraphStoreException.class, () -> store.verify(VerifyLevel.FULL));
				assertThrows(GraphStoreException.class, store::reclaim);
				txn.commit();
			}
			assertEquals(1, store.stats().nodeCount());
			store.checkpoint();
		}
	}

	@Test
	public void testCloseInsideOwnTransactionRollsItBackAndCloses() throws Exception {
		long node;
		try (GraphStore store = stores.open()) {
			node = store.createNode("Kept");
			Transaction txn = store.begin();
			store.createNode("Discarded");
			store.close();
			assertFalse(txn.isActive());
		}
		try (GraphStore store = stores.open()) {
			assertEquals(1, store.stats().nodeCount(), "the open transaction must not have been committed by close()");
			assertTrue(store.nodeExists(node));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testCommitFromAnotherThreadIsRefusedAndLeavesTheTransactionUsable() throws Exception {
		try (GraphStore store = stores.open()) {
			Transaction txn = store.begin();
			store.createNode("A");
			ExecutionException failure = assertThrows(ExecutionException.class,
				() -> CompletableFuture.runAsync(() -> {
					try {
						txn.commit();
					} catch (java.io.IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				}).get());
			assertTrue(failure.getCause() instanceof GraphStoreException, String.valueOf(failure.getCause()));
			assertTrue(txn.isActive(), "a refused foreign commit must not have ended the owner's transaction");
			txn.commit();
			assertEquals(1, store.stats().nodeCount());
			// The gate was released by the owner, so exclusive maintenance still runs.
			store.checkpoint();
		}
	}
}
