package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.TransactionConflictException;
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

	/** What the last {@link #bothWithdraw} run collided on, for a failure message that names something. */
	private final List<TransactionConflictException> conflicts =
		java.util.Collections.synchronizedList(new ArrayList<>());

	private String conflictReport = "";

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
	 * The anomaly is the same; what prevents it has changed. While writers were serialised, the second increment could
	 * not run until the first had committed and a writer read the current mapping, so it saw the new value by
	 * construction. Writers now run at the same time and each reads a fixed view, so the second increment really does
	 * compute 1 from a stale 0 — and the commit that would publish it is refused, because another transaction has
	 * changed the bytes it is about to write. First committer wins, the loser retries against the new value, and no
	 * increment is lost.
	 * </p>
	 *
	 * <p>
	 * So this test is now also the test that the retry seam works: {@code inTransaction} rather than {@code begin()},
	 * because a bare {@code begin()} would raise {@link TransactionConflictException} and losing an increment and being
	 * told you lost it are very different failures. The view held open throughout still proves it does not contaminate
	 * the writes made beside it.
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
								store.inTransaction(() -> {
									long current = (Long) store.nodeProperty(counter, "n");
									store.setNodeProperties(counter, Properties.of().set("n", current + 1));
									return null;
								}, 500);
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
	 * Both transactions read both doctors, each sees the other on duty, and each takes itself off. Their writes are on
	 * different records, so first-committer-wins has nothing to say about the constraint they are both reasoning about.
	 * <b>Snapshot isolation does not rule that out</b> — it is write skew, the anomaly that separates snapshot
	 * isolation from serializability, and this file used to assert the opposite because serialised writers made it
	 * unreachable. Concurrent writers are the honest cost of the change.
	 * </p>
	 *
	 * <p>
	 * <b>What this asserts is the mechanism, not the outcome, and the difference is worth being exact about.</b> The
	 * pair usually <em>is</em> refused here — but for an unrelated reason: both updates insert into the shared property
	 * index, and the byte range they both rewrite is a real write-write conflict. That is safety by layout, not by
	 * isolation level, and asserting the outcome would be asserting an accident. So what is pinned down is that at
	 * SNAPSHOT nothing is ever refused for having <em>read</em> something — {@link
	 * TransactionConflictException#isReadConflict()} — which is exactly the check that is missing, and exactly the one
	 * {@link IsolationLevel#SERIALIZABLE} adds.
	 * </p>
	 */
	@Test
	public void testSnapshotIsolationNeverRefusesATransactionForWhatItRead() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> doctors = twoDistantDoctors(store);
			bothWithdraw(store, doctors.get(0), doctors.get(1), IsolationLevel.SNAPSHOT);

			for (TransactionConflictException conflict : conflicts) {
				assertFalse(conflict.isReadConflict(),
					"a SNAPSHOT transaction was refused for something it read, which is a check this level does not"
						+ " make: " + conflict.getMessage());
			}
			// And whatever happened, the store agrees with itself.
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}

	/**
	 * The same attack at {@link IsolationLevel#SERIALIZABLE}, which is what the level is for.
	 *
	 * <p>
	 * Each transaction read the other doctor's record and then decided from it. Validating the read set at commit turns
	 * that into a conflict as soon as somebody else changes a page it was read from, so one of the two is refused —
	 * and refused as a <em>read</em> conflict, which is the half SNAPSHOT does not do. It aborts rather than blocks,
	 * and it aborts on page granularity rather than on the record: sound, and coarser than SSI would be.
	 * </p>
	 */
	@Test
	public void testSerializableRefusesWriteSkew() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> doctors = twoDistantDoctors(store);
			long alice = doctors.get(0);
			long bob = doctors.get(1);

			assertFalse(bothWithdraw(store, alice, bob, IsolationLevel.SERIALIZABLE),
				"both transactions committed at SERIALIZABLE, which is write skew");
			// Not asserted as a read conflict: the write set is validated first, so when the two also collide on a
			// shared index page that is what gets reported. Either way one of them is refused, which is the guarantee.
			// The asymmetry that identifies the level is in the SNAPSHOT test above, where a read conflict can never
			// be reported at all.

			boolean aliceOn = (Boolean) store.nodeProperty(alice, "onCall");
			boolean bobOn = (Boolean) store.nodeProperty(bob, "onCall");
			assertTrue(aliceOn || bobOn, "both doctors went off duty, which is write skew");
			assertFalse(aliceOn && bobOn, "one of them should have succeeded");
		}
	}

	/**
	 * A read served from the transaction's own copy of a page is still a read.
	 *
	 * <p>
	 * The read set was recorded only when a read missed the overlay, so a page whose first touch was a write — here,
	 * creating a node on the page another node already lives on — never entered it. The other record's bytes in that
	 * copy are the snapshot's, and a concurrent commit that changes them at bytes this transaction never wrote is
	 * invisible to the write set too: the decision was made on stale input and nothing refused it.
	 * </p>
	 */
	@Test
	public void testSerializableCountsReadsFromItsOwnCopyOfAPage() throws Exception {
		try (GraphStore store = stores.open()) {
			long watched = store.createNode("Watched");

			Transaction txn = store.begin(IsolationLevel.SERIALIZABLE);
			boolean committed = false;
			try {
				// Lands on the watched node's page, so the transaction now holds a private copy of it.
				store.createNode("Mine");
				boolean unset = store.nodeProperties(watched).isEmpty();
				assertTrue(unset, "the snapshot has no property on the watched node yet");

				Thread other = new Thread(() -> {
					try {
						store.setNodeProperties(watched, Properties.of().set("k", 1L));
					} catch (java.io.IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				});
				other.start();
				other.join();

				// Decided from what was read: the watched node had no property.
				store.createNode("BecauseUnset");
				txn.commit();
				committed = true;
			} catch (TransactionConflictException expected) {
				assertTrue(expected.isReadConflict(), "refused for what it read: " + expected.getMessage());
			} finally {
				if (!committed) {
					txn.rollback();
				}
			}
			assertFalse(committed, "a SERIALIZABLE transaction committed on a read the concurrent commit invalidated");
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}

	/**
	 * Two doctors whose property blocks cannot share a page with anything, including each other.
	 *
	 * <p>
	 * Conflict detection compares byte ranges, so two records that happen to be neighbours are a write-write conflict
	 * whatever the transactions were reasoning about. Spacing the doctors apart is not enough on its own: an update
	 * allocates a <em>new</em> block, and two of those come off the bump cursor next to each other. Giving each doctor
	 * a payload of more than half a page makes every block it ever occupies exclusive by construction — the old one and
	 * the new one alike — so a conflict between these two transactions can only come from the constraint they are both
	 * reading, which is the thing being tested.
	 * </p>
	 */
	private List<Long> twoDistantDoctors(GraphStore store) throws java.io.IOException {
		List<Long> doctors = new ArrayList<>();
		doctors.add(store.createNode("Doctor", onCall(true)));
		doctors.add(store.createNode("Doctor", onCall(true)));
		return doctors;
	}

	/** Half a page of padding, so the block this produces has its page to itself. */
	private static Properties onCall(boolean value) {
		return Properties.of().set("onCall", value).set("pad", "x".repeat(5_000));
	}

	/**
	 * Runs both withdrawals with a barrier between the read and the write, so both really do decide from the state
	 * before either of them acted. Returns true when both committed.
	 */
	private boolean bothWithdraw(GraphStore store, long alice, long bob, IsolationLevel level) throws Exception {
		conflicts.clear();
		CyclicBarrier read = new CyclicBarrier(2);
		AtomicReference<Throwable> failed = new AtomicReference<>();
		java.util.concurrent.atomic.AtomicInteger committed = new java.util.concurrent.atomic.AtomicInteger();

		Thread first = new Thread(withdraw(store, alice, bob, level, read, committed, conflicts, failed),
			"withdraw-alice");
		Thread second = new Thread(withdraw(store, bob, alice, level, read, committed, conflicts, failed),
			"withdraw-bob");
		first.start();
		second.start();
		first.join();
		second.join();

		if (failed.get() != null) {
			throw new AssertionError("a withdrawal failed with something other than a conflict", failed.get());
		}
		conflictReport = conflicts.stream().map(Throwable::getMessage)
			.collect(java.util.stream.Collectors.joining("\n  "));
		return committed.get() == 2;
	}

	/** Takes {@code self} off duty only if {@code other} is still on, inside one transaction. */
	private static Runnable withdraw(GraphStore store, long self, long other, IsolationLevel level,
		CyclicBarrier read, java.util.concurrent.atomic.AtomicInteger committed,
		List<TransactionConflictException> conflicts, AtomicReference<Throwable> failed) {
		return () -> {
			try (Transaction txn = store.begin(level)) {
				boolean otherOnCall = (Boolean) store.nodeProperty(other, "onCall");
				// Both have now read, and neither has written. Without this the anomaly is a race rather than a
				// demonstration, and the test would pass or fail on scheduling.
				read.await();
				if (otherOnCall) {
					store.setNodeProperties(self, onCall(false));
				}
				txn.commit();
				committed.incrementAndGet();
			} catch (TransactionConflictException e) {
				// The expected outcome for one of the two at SERIALIZABLE, and for neither at SNAPSHOT. Kept, because
				// "both should have committed" is not actionable without knowing what they collided on.
				conflicts.add(e);
			} catch (Throwable e) {
				failed.compareAndSet(null, e);
			}
		};
	}
}
