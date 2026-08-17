package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The one thing a transaction makes durable before it commits.
 *
 * <p>
 * {@code commit()} runs {@code catalog.force()} <em>before</em> {@code wal.logCommit}, and it has to: the label and
 * key ids are embedded in the records being logged, so a log record naming id 7 is meaningless if the dictionary entry
 * that says what 7 means is not on disk yet. The consequence is that a brand new label survives the transaction that
 * introduced it, even when that transaction is rolled back or lost to a crash.
 * </p>
 *
 * <p>
 * That is a deliberate leak and these cases pin it as <b>harmless</b> rather than pretend it does not happen:
 * {@code RollbackTest} already asserts that rollback leaves the segment files byte-identical, and it is worth being
 * explicit that the catalog is the one file that claim does not cover. What must hold is that the orphaned id is
 * invisible in every way that matters — no node carries it, {@code verify(FULL)} is clean, the store reopens, and
 * reusing the label later gets the same id back rather than allocating a second one.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 4.1
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@Tag("unit")
public class CatalogDurabilityWindowTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testALabelFromARolledBackTransactionIsHarmless() throws Exception {
		try (GraphStore store = stores.open()) {
			long survivor = store.createNode("Kept", Properties.of().set("n", 1L));

			try (Transaction txn = store.begin()) {
				store.createNode("Typo", Properties.of().set("misspelled", "yes"));
				txn.rollback();
			}

			assertEquals(0, store.nodesByLabel("Typo").size(), "a rolled-back label must name no nodes");
			assertEquals(0, store.nodesByProperty("misspelled", "yes").size(),
				"a rolled-back property key must match no nodes");
			assertTrue(store.nodeExists(survivor), "the earlier commit must survive");
			store.verify(VerifyLevel.FULL);

			// Reusing the label must work, and must not allocate a second id for the same name.
			long reused = store.createNode("Typo", Properties.of().set("misspelled", "no"));
			assertEquals(List.of(reused), store.nodesByLabel("Typo"),
				"the label should resolve to exactly the node that now carries it");
			store.verify(VerifyLevel.FULL);
		}

		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL);
			assertEquals(1, reopened.nodesByLabel("Typo").size(), "the reused label should survive the reopen");
		}
	}

	/**
	 * The same window, but reached by a crash rather than a rollback — the dictionary entry is on disk and its
	 * transaction never reached {@code TXN_COMMIT}, so recovery has to discard the records while the catalog keeps the
	 * name.
	 */
	@Test
	public void testALabelFromACrashedTransactionIsHarmless() throws Exception {
		List<Long> committed = new ArrayList<>();
		GraphStore store = stores.open();
		try {
			committed.add(store.createNode("Kept", Properties.of().set("n", 1L)));
			store.checkpoint();

			// Opened, writes a brand new label, and is never committed.
			Transaction txn = store.begin();
			store.createNode("Ghost", Properties.of().set("ghostly", "yes"));
			((GraphStoreImpl) store).abandon();
		} finally {
			try {
				store.close();
			} catch (Exception ignored) {
				// Abandoned stores are expected to refuse an orderly close.
			}
		}

		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL);
			assertEquals(0, reopened.nodesByLabel("Ghost").size(),
				"an uncommitted transaction's node must not survive the crash");
			assertEquals(0, reopened.nodesByProperty("ghostly", "yes").size(),
				"an uncommitted transaction's property must not survive the crash");
			for (long id : committed) {
				assertTrue(reopened.nodeExists(id), "committed work must survive the crash");
			}

			// The orphaned dictionary entry must be reusable rather than poisoned.
			long fresh = reopened.createNode("Ghost", Properties.of().set("ghostly", "no"));
			assertEquals(List.of(fresh), reopened.nodesByLabel("Ghost"));
			reopened.verify(VerifyLevel.FULL);
		}
	}
}
