package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * The anomaly matrix of [TRANSACTIONS.md §9](../../../../../../../../spec/TRANSACTIONS.md): each classic anomaly, at
 * each level, asserted to happen or not happen.
 *
 * <p>
 * A test suite that only shows the anomalies being prevented is worth much less than one that also shows them
 * <b>occurring where the level permits them</b>. Otherwise a level that silently behaves like a stricter one passes,
 * and nobody learns that the cheap option was never cheap. So the read-committed cases below assert the anomaly
 * happens.
 * </p>
 *
 * <h2>The result</h2>
 *
 * <table border="1">
 * <caption>Anomalies by level</caption>
 * <tr><th>Anomaly</th><th>READ_COMMITTED</th><th>SNAPSHOT</th></tr>
 * <tr><td>Dirty read</td><td>prevented</td><td>prevented</td></tr>
 * <tr><td>Non-repeatable read</td><td><b>possible</b></td><td>prevented</td></tr>
 * <tr><td>Phantom</td><td><b>possible</b></td><td>prevented</td></tr>
 * <tr><td>Lost update</td><td>prevented</td><td>prevented</td></tr>
 * <tr><td>Write skew</td><td>prevented</td><td>prevented</td></tr>
 * </table>
 *
 * <p>
 * The last two rows are stronger than [TRANSACTIONS.md §3](../../../../../../../../spec/TRANSACTIONS.md) predicts,
 * which lists write skew as snapshot isolation's exposure and the reason `SERIALIZABLE` exists. <b>It cannot occur
 * here</b>, and the reason is structural rather than clever: write transactions are serialised by one writer lock, and
 * a writer reads the current mapping rather than any view — see {@code SegmentFile.readSlice}. Two writers therefore
 * never overlap, so neither can decide on stale input. Writes are serializable; reads get snapshot isolation. SSI has
 * nothing left to detect, which is why it is not built rather than being an unfinished item.
 * </p>
 */
@Tag("unit")
public class SnapshotIsolationTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	// ---------------------------------------------------------------- dirty read

	@Test
	public void testDirtyReadIsPreventedAtEveryLevel() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("name", "committed"));

			for (IsolationLevel level : IsolationLevel.values()) {
				CountDownLatch written = new CountDownLatch(1);
				CountDownLatch checked = new CountDownLatch(1);
				AtomicReference<Object> seen = new AtomicReference<>();
				AtomicReference<Throwable> failed = new AtomicReference<>();

				Thread reader = new Thread(() -> {
					try {
						written.await();
						try (ReadTransaction view = store.beginRead(level)) {
							seen.set(store.nodeProperty(node, "name"));
						}
					} catch (Throwable t) {
						failed.set(t);
					} finally {
						checked.countDown();
					}
				});
				reader.start();

				try (Transaction txn = store.begin()) {
					store.setNodeProperties(node, Properties.of().set("name", "uncommitted"));
					written.countDown();
					checked.await();
					txn.rollback();
				}
				reader.join();

				assertEquals(null, failed.get(), "reader failed at " + level);
				assertEquals("committed", seen.get(),
					"at " + level + " a reader must never see a value that was never committed");
			}
		}
	}

	// ---------------------------------------------------------------- non-repeatable read

	@Test
	public void testNonRepeatableReadIsPossibleUnderReadCommitted() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("age", 30L));

			try (ReadTransaction view = store.beginRead(IsolationLevel.READ_COMMITTED)) {
				Object first = store.nodeProperty(node, "age");
				store.setNodeProperties(node, Properties.of().set("age", 31L));
				Object second = store.nodeProperty(node, "age");

				assertEquals(30L, first);
				assertNotEquals(first, second, "read committed permits this, and must actually exhibit it");
				assertEquals(31L, second);
			}
		}
	}

	@Test
	public void testNonRepeatableReadIsPreventedUnderSnapshot() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("age", 30L));

			try (ReadTransaction view = store.beginRead(IsolationLevel.SNAPSHOT)) {
				Object first = store.nodeProperty(node, "age");
				store.setNodeProperties(node, Properties.of().set("age", 31L));
				Object second = store.nodeProperty(node, "age");

				assertEquals(30L, first);
				assertEquals(first, second, "a snapshot view must read the same value twice");
			}
		}
	}

	// ---------------------------------------------------------------- phantom

	@Test
	public void testPhantomIsPossibleUnderReadCommitted() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Person", Properties.of().set("city", "Bonn"));

			try (ReadTransaction view = store.beginRead(IsolationLevel.READ_COMMITTED)) {
				int first = store.nodesByProperty("city", "Bonn").size();
				store.createNode("Person", Properties.of().set("city", "Bonn"));
				int second = store.nodesByProperty("city", "Bonn").size();

				assertEquals(1, first);
				assertEquals(2, second, "a row that was not there before is exactly what a phantom is");
			}
		}
	}

	@Test
	public void testPhantomIsPreventedUnderSnapshot() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Person", Properties.of().set("city", "Bonn"));

			try (ReadTransaction view = store.beginRead(IsolationLevel.SNAPSHOT)) {
				int first = store.nodesByProperty("city", "Bonn").size();
				store.createNode("Person", Properties.of().set("city", "Bonn"));
				int second = store.nodesByProperty("city", "Bonn").size();

				assertEquals(1, first);
				assertEquals(first, second, "the index must be read as of the view, or phantoms come back through it");
				assertEquals(1, store.nodesByLabel("Person").size(), "and through the label index too");
			}
			assertEquals(2, store.nodesByProperty("city", "Bonn").size());
		}
	}

	// ---------------------------------------------------------------- lost update

	/**
	 * Two read-modify-write cycles run concurrently. Neither may be lost.
	 *
	 * <p>
	 * This is the anomaly the writer-reads-current rule exists for. If a writer read through an open view instead of
	 * the mapping, the second increment would compute 1 from a stale 0 and overwrite the first — silently, with no
	 * conflict to report, because the two never touch the same lock.
	 * </p>
	 */
	@Test
	public void testLostUpdateIsPrevented() throws Exception {
		try (GraphStore store = stores.open()) {
			long counter = store.createNode("Counter", Properties.of().set("n", 0L));
			int threads = 4;
			int perThread = 25;

			Thread[] workers = new Thread[threads];
			AtomicReference<Throwable> failed = new AtomicReference<>();
			for (int t = 0; t < threads; t++) {
				workers[t] = new Thread(() -> {
					try {
						// A view is open throughout, to prove it does not contaminate the writes made beside it.
						try (ReadTransaction view = store.beginRead()) {
							for (int i = 0; i < perThread; i++) {
								try (Transaction txn = store.begin()) {
									long current = (Long) store.nodeProperty(counter, "n");
									store.setNodeProperties(counter, Properties.of().set("n", current + 1));
									txn.commit();
								}
							}
						}
					} catch (Throwable e) {
						failed.compareAndSet(null, e);
					}
				});
			}
			for (Thread worker : workers) {
				worker.start();
			}
			for (Thread worker : workers) {
				worker.join();
			}

			assertEquals(null, failed.get(), String.valueOf(failed.get()));
			assertEquals((long) threads * perThread, store.nodeProperty(counter, "n"),
				"every increment must survive; a lower number means one read a value another had already replaced");
		}
	}

	// ---------------------------------------------------------------- write skew

	/**
	 * The constraint "at least one of the two must stay on duty", attacked from both sides at once.
	 *
	 * <p>
	 * Under textbook snapshot isolation both transactions read both rows, each sees the other on duty, and each takes
	 * itself off — leaving nobody, with no conflict detected. That is write skew, and it is why the spec lists SSI as
	 * the fix. Here the writer lock serialises the two transactions and each reads the mapping rather than a view, so
	 * the second one sees the first's commit and refuses.
	 * </p>
	 */
	@Test
	public void testWriteSkewIsPrevented() throws Exception {
		try (GraphStore store = stores.open()) {
			long alice = store.createNode("Doctor", Properties.of().set("onCall", true));
			long bob = store.createNode("Doctor", Properties.of().set("onCall", true));

			CountDownLatch start = new CountDownLatch(1);
			AtomicReference<Throwable> failed = new AtomicReference<>();

			Thread first = new Thread(withdraw(store, alice, bob, start, failed));
			Thread second = new Thread(withdraw(store, bob, alice, start, failed));
			first.start();
			second.start();
			start.countDown();
			first.join();
			second.join();

			assertEquals(null, failed.get(), String.valueOf(failed.get()));
			boolean aliceOn = (Boolean) store.nodeProperty(alice, "onCall");
			boolean bobOn = (Boolean) store.nodeProperty(bob, "onCall");
			assertTrue(aliceOn || bobOn, "both doctors went off duty, which is write skew");
			assertFalse(aliceOn && bobOn, "one of them should have succeeded");
		}
	}

	/** Takes {@code self} off duty only if {@code other} is still on, inside one transaction. */
	private static Runnable withdraw(GraphStore store, long self, long other, CountDownLatch start,
		AtomicReference<Throwable> failed) {
		return () -> {
			try {
				start.await();
				try (Transaction txn = store.begin()) {
					if ((Boolean) store.nodeProperty(other, "onCall")) {
						store.setNodeProperties(self, Properties.of().set("onCall", false));
					}
					txn.commit();
				}
			} catch (Throwable e) {
				failed.compareAndSet(null, e);
			}
		};
	}
}
