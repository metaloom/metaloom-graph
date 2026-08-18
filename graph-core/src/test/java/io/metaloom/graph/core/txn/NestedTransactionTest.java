package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * A second {@code begin()} on a thread that already has one open is refused.
 *
 * <p>
 * It used to be allowed, and that was the sharpest unguarded footgun in the transaction API. The writer lock is
 * reentrant, so the nesting succeeded; the inner transaction published its own page overlay; and committing the inner
 * one cleared that overlay while the outer transaction was still live. Every write the outer transaction made after
 * that point went straight to the mapping — <b>unlogged, and past the point where a rollback could take it back</b>.
 * Atomicity and durability were both lost, silently, with no error anywhere.
 * </p>
 *
 * <p>
 * The two halves of that are tested separately here, because a refusal is only worth having if it is complete: the
 * nesting must be refused, and the transaction that was already open must be entirely unharmed by the refusal — still
 * live, still holding its lock, still rollback-able.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 8
 */
@Tag("unit")
public class NestedTransactionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testASecondBeginIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				assertTrue(store.hasWriteTransaction(), "the first transaction should be open");
				GraphStoreException e = assertThrows(GraphStoreException.class, store::begin,
					"a nested begin() must be refused rather than silently opening a second transaction");
				assertTrue(e.getMessage().contains("already open"),
					"the refusal should say what is wrong; got: " + e.getMessage());
				txn.rollback();
			}
		}
	}

	/**
	 * The refusal must not damage the transaction that was already open — in particular it must not release the writer
	 * lock on the way out, which is the classic way a guard like this turns one bug into a worse one.
	 */
	@Test
	public void testTheOpenTransactionSurvivesARefusedNesting() throws Exception {
		try (GraphStore store = stores.open()) {
			long node;
			try (Transaction txn = store.begin()) {
				node = store.createNode("Outer", Properties.of().set("n", 1L));
				assertThrows(GraphStoreException.class, store::begin);

				// Still ours, still usable, and the writes still belong to this transaction.
				assertTrue(store.hasWriteTransaction(), "the outer transaction must still be open after the refusal");
				store.setNodeProperties(node, Properties.of().set("n", 2L));
				txn.commit();
			}
			assertEquals(2L, store.nodeProperty(node, "n"),
				"the outer transaction's writes must have committed normally");
			assertFalse(store.hasWriteTransaction(), "the transaction should be closed after commit");
		}
	}

	/**
	 * The half that used to be silently lost: a write made after a nested commit must still be inside the outer
	 * transaction, and must therefore disappear when that transaction is rolled back.
	 */
	@Test
	public void testWritesAfterARefusedNestingAreStillRollbackable() throws Exception {
		try (GraphStore store = stores.open()) {
			long survivor = store.createNode("Before", Properties.of().set("n", 1L));

			long doomed;
			try (Transaction txn = store.begin()) {
				doomed = store.createNode("Doomed", Properties.of().set("n", 1L));
				assertThrows(GraphStoreException.class, store::begin);
				// Under the old behaviour a committed nested transaction cleared the overlay here, and this write went
				// straight to the mapping where no rollback could reach it.
				store.setNodeProperties(doomed, Properties.of().set("n", 99L));
				txn.rollback();
			}

			assertFalse(store.nodeExists(doomed), "everything in the rolled-back transaction must be gone");
			assertTrue(store.nodeExists(survivor), "work committed before the transaction must survive");
		}
	}

	/** The writer lock must be released exactly once, so another thread can still get in afterwards. */
	@Test
	public void testARefusedNestingDoesNotLeakTheWriterLock() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				assertThrows(GraphStoreException.class, store::begin);
				txn.commit();
			}

			CountDownLatch acquired = new CountDownLatch(1);
			Thread other = Thread.ofPlatform().name("other-writer").start(() -> {
				try (Transaction txn = store.begin()) {
					txn.rollback();
					acquired.countDown();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
			assertTrue(acquired.await(30, TimeUnit.SECONDS),
				"another thread could not acquire the writer lock, so the refused nesting leaked it");
			other.join(30_000);
		}
	}

	/** Auto-commit mutations join an open transaction rather than beginning one, so they must not trip the guard. */
	@Test
	public void testAutoCommitMutationsStillJoinAnOpenTransaction() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				long a = store.createNode("A", Properties.of());
				long b = store.createNode("B", Properties.of());
				store.createEdge(a, "LINKS", b);
				txn.commit();
			}
			assertEquals(2, store.nodesByLabel("A").size() + store.nodesByLabel("B").size(),
				"the mutations should have joined the open transaction and committed with it");
		}
	}

	/**
	 * {@code inStatement} is refused inside an open transaction, for the same reason {@code begin} is.
	 *
	 * <p>
	 * It absorbs conflicts by re-running its work, which is only ever safe when the work is the whole transaction.
	 * Inside somebody else's unit of work it would re-run their statements and commit their transaction on its way
	 * past — so the refusal is the same judgement nesting gets, and is made for the same reason.
	 * </p>
	 */
	@Test
	public void testInStatementIsRefusedInsideATransaction() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				long id = store.createNode("Outer", Properties.of());

				assertThrows(IllegalStateException.class,
					() -> store.inStatement(() -> store.createNode("Inner", Properties.of())),
					"inStatement() inside an open transaction would commit that transaction to absorb a conflict");

				// The refusal has to leave the caller's transaction untouched, exactly as a refused nesting does.
				store.setNodeProperties(id, Properties.of().set("mark", 1L));
				txn.commit();
			}
			assertEquals(1, store.nodesByLabel("Outer").size(), "the outer transaction should still have committed");
			assertTrue(store.nodesByLabel("Inner").isEmpty(), "the refused statement must not have created anything");
		}
	}

}
