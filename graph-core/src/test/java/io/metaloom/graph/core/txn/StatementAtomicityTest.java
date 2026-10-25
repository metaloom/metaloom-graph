package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * A statement that fails inside an explicit transaction must not leave half of itself behind for the commit.
 *
 * <p>
 * An auto-commit statement is atomic for free: it is its own transaction, and a failure rolls the whole thing back.
 * Inside a caller's transaction there is no such boundary, and catching a refusal and carrying on is the ordinary way
 * to use a constraint — so a mutation that refused <em>after</em> it had started writing used to commit the part it
 * had done. {@code setNodeProperties} wrote the new property block and freed the old one before checking the unique
 * constraint, so a caught violation committed the violating value with the indexes still describing the old one: the
 * constraint broken, and {@code nodesByProperty} wrong in both directions. Found by {@code StorageModelTest}.
 * </p>
 *
 * <p>
 * Two halves, as in {@link NestedTransactionTest}. A refusal the engine can make before touching anything leaves the
 * transaction fully usable; a failure it can only discover partway marks the transaction rollback-only, so the commit
 * is refused rather than making a half-applied statement durable.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 4.1
 */
@Tag("unit")
public class StatementAtomicityTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testACaughtUniqueViolationOnSetLeavesTheNodeUnchanged() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("uid");
			long a = store.createNode("N", Properties.of().set("uid", 1L).set("name", "a"));
			long b = store.createNode("N", Properties.of().set("uid", 2L).set("name", "b"));
			try (Transaction txn = store.begin()) {
				assertThrows(ConstraintViolationException.class,
					() -> store.setNodeProperties(b, Properties.of().set("uid", 1L).set("name", "b2")));
				// The transaction carries on, as a caller handling the refusal would.
				store.setNodeProperties(a, Properties.of().set("uid", 1L).set("name", "a2"));
				txn.commit();
			}
			assertEquals(2L, store.nodeProperties(b).getLong("uid"), "the refused value was committed");
			assertEquals("b", store.nodeProperties(b).getString("name"), "the refused statement was half applied");
			assertEquals("a2", store.nodeProperties(a).getString("name"), "the rest of the transaction was lost");
			assertEquals(List.of(a), store.nodesByProperty("uid", 1L));
			assertEquals(List.of(b), store.nodesByProperty("uid", 2L));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testACaughtLabelScopedViolationOnSetLeavesTheNodeUnchanged() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("Person", "email");
			store.createNode("Person", Properties.of().set("email", "x@y"));
			long b = store.createNode("Person", Properties.of().set("email", "b@y"));
			try (Transaction txn = store.begin()) {
				assertThrows(ConstraintViolationException.class,
					() -> store.setNodeProperties(b, Properties.of().set("email", "x@y")));
				txn.commit();
			}
			assertEquals("b@y", store.nodeProperties(b).getString("email"));
			assertEquals(List.of(b), store.nodesByLabelAndProperty("Person", "email", "b@y"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A failure only discoverable partway: the block is encoded value by value and a blob is written for the first
	 * before the second turns out not to be storable. Nothing points at that blob, so committing it would leave an
	 * orphan for {@code verify(FULL)} to report.
	 */
	@Test
	public void testAFailureAfterAWriteMakesTheTransactionRollbackOnly() throws Exception {
		try (GraphStore store = stores.open()) {
			long keep = store.createNode("N", Properties.of().set("name", "before"));
			try (Transaction txn = store.begin()) {
				store.setNodeProperties(keep, Properties.of().set("name", "inside"));
				Properties broken = Properties.of().set("a", "y".repeat(5000)).set("b", new Object());
				assertThrows(IllegalArgumentException.class, () -> store.createNode("N", broken));
				GraphStoreException refused = assertThrows(GraphStoreException.class, txn::commit,
					"a transaction holding half a statement must not commit");
				assertTrue(refused.getMessage().contains("rollback-only"), refused.getMessage());
			}
			assertEquals("before", store.nodeProperties(keep).getString("name"), "the refused commit applied");
			assertEquals(1, store.stats().nodeCount());
			store.verify(VerifyLevel.FULL);
			// And the store is not wedged: the next transaction commits normally.
			try (Transaction txn = store.begin()) {
				store.setNodeProperties(keep, Properties.of().set("name", "after"));
				txn.commit();
			}
			assertEquals("after", store.nodeProperties(keep).getString("name"));
		}
	}

	/** An oversized block is refused after its blobs were already written, which is the same shape. */
	@Test
	public void testAnOversizedBlockAfterABlobMakesTheTransactionRollbackOnly() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				Properties props = Properties.of().set("a", "z".repeat(4000));
				for (int i = 0; i < 40; i++) {
					props.set("k" + i, "w".repeat(255));
				}
				assertThrows(IllegalArgumentException.class, () -> store.createNode("N", props));
				assertThrows(GraphStoreException.class, txn::commit);
			}
			assertEquals(0, store.stats().nodeCount());
			store.verify(VerifyLevel.FULL);
		}
	}

	/** A refusal made before anything is written leaves the transaction usable, which is the other half. */
	@Test
	public void testARefusalBeforeAnyWriteLeavesTheTransactionUsable() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("uid");
			store.createNode("N", Properties.of().set("uid", 1L));
			try (Transaction txn = store.begin()) {
				long fresh = store.createNode("N", Properties.of().set("uid", 2L));
				assertThrows(ConstraintViolationException.class,
					() -> store.createNode("N", Properties.of().set("uid", 1L)));
				assertThrows(IllegalArgumentException.class, () -> store.addNodeLabel(fresh, null));
				txn.commit();
				assertTrue(store.nodeExists(fresh));
			}
			assertEquals(2, store.stats().nodeCount());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * Work joined to the caller's transaction through {@code inOpenTransaction} gets the same statement boundary as a
	 * single mutation: a failure after it wrote makes the transaction rollback-only, a failure before it wrote leaves
	 * the transaction usable, and with no transaction to join it is refused.
	 */
	@Test
	public void testInOpenTransactionDrawsAStatementBoundaryAroundSeveralCalls() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(IllegalStateException.class, () -> store.inOpenTransaction(() -> store.createNode("N")));

			try (Transaction txn = store.begin()) {
				store.createNode("Kept");
				assertThrows(IllegalStateException.class, () -> store.inOpenTransaction(() -> {
					throw new IllegalStateException("refused before writing");
				}));
				txn.commit();
			}
			assertEquals(1, store.nodesByLabel("Kept").size(), "a statement that wrote nothing must not poison the transaction");

			try (Transaction txn = store.begin()) {
				store.createNode("Lost");
				assertThrows(IllegalStateException.class, () -> store.inOpenTransaction(() -> {
					store.createNode("Half");
					throw new IllegalStateException("refused after writing");
				}));
				GraphStoreException refused = assertThrows(GraphStoreException.class, txn::commit);
				assertTrue(refused.getMessage().contains("rollback-only"), refused.getMessage());
			}
			assertEquals(List.of(), store.nodesByLabel("Half"), "half a statement was committed");
			assertEquals(List.of(), store.nodesByLabel("Lost"), "the rollback-only transaction committed");
			store.verify(VerifyLevel.FULL);
		}
	}
}
