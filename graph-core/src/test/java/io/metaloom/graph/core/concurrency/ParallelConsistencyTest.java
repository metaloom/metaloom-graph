package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * A conserved quantity, checked continuously by readers while writers move it around.
 *
 * <p>
 * This is the classic ACID consistency test and it is here because it asks a different question from everything else
 * in this suite. The invariant checkers ask whether the <em>engine's own</em> bookkeeping agrees with itself. This
 * asks whether an <em>application</em> invariant — one the engine knows nothing about — survives concurrency. A total
 * that drifts means a transaction was observed half-applied, and no amount of internal self-consistency would show it.
 * </p>
 *
 * <p>
 * Each transaction moves value between two accounts and records the movement as an edge, so the money and the graph
 * have to move together or not at all. Readers hold a {@code SNAPSHOT} view and must always see the full total.
 * </p>
 *
 * <p>
 * The read-committed case asserts the opposite, on purpose: that the weaker level really is weaker and a reader can
 * catch a total mid-transfer. A level that silently behaved like a stricter one is a level nobody should be paying
 * for — the same argument {@code SnapshotIsolationTest} makes for the anomaly matrix.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 1
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 5
 */
@Tag("unit")
public class ParallelConsistencyTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int ACCOUNTS = 16;
	private static final long OPENING_BALANCE = 1_000L;
	private static final long TOTAL = ACCOUNTS * OPENING_BALANCE;

	/**
	 * How many times a transfer may lose a conflict before the test calls it a failure.
	 *
	 * <p>
	 * Four writers over sixteen accounts collide often, and every collision is real: both transactions read the same
	 * balance. Generous rather than unlimited, so a transfer that can never commit still fails the test instead of
	 * hanging it.
	 * </p>
	 */
	private static final int ATTEMPTS = 200;

	@Test
	public void testTheConservedTotalHoldsUnderSnapshotReads() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> accounts = openAccounts(store);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicLong audits = new AtomicLong();
			CountDownLatch ready = new CountDownLatch(3);
			List<Thread> auditors = new ArrayList<>();

			for (int a = 0; a < 3; a++) {
				auditors.add(Thread.ofPlatform().name("auditor-" + a).start(() -> {
					ready.countDown();
					try {
						while (!stop.get()) {
							try (ReadTransaction view = store.beginRead()) {
								long total = 0;
								for (long account : accounts) {
									total += (Long) store.nodeProperty(account, "balance");
								}
								if (total != TOTAL) {
									throw new AssertionError("a snapshot view at LSN " + view.snapshotLsn()
										+ " saw a total of " + total + " rather than " + TOTAL
										+ ", so it observed a transfer half applied");
								}
								audits.incrementAndGet();
							}
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				}));
			}

			ready.await();
			// Counted here rather than taken from the Hammer, which counts invocations: a transfer that picks the same
			// account twice, or finds nothing to move, commits nothing and must not be expected to leave a ledger edge.
			AtomicLong committed = new AtomicLong();
			long transfers;
			try {
				transfers(store, accounts, committed).run();
				transfers = committed.get();
			} finally {
				stop.set(true);
				for (Thread auditor : auditors) {
					auditor.join(60_000);
				}
			}

			if (failure.get() != null) {
				throw new AssertionError("the conserved total did not hold under concurrent transfers", failure.get());
			}
			assertTrue(audits.get() > 50, "only " + audits.get() + " audits ran, too few to have tested anything");
			assertTrue(transfers > 0, "no transfer was made");

			// Quiescent: the money and the ledger must agree exactly.
			long finalTotal = 0;
			long edges = 0;
			for (long account : accounts) {
				finalTotal += (Long) store.nodeProperty(account, "balance");
				edges += store.degreeOut(account);
			}
			assertEquals(TOTAL, finalTotal, "value was created or destroyed overall");
			assertEquals(transfers, edges, "every transfer must have left exactly one ledger edge");
			store.verify(VerifyLevel.FULL);
			System.out.println("[consistency] transfers=" + transfers + " audits=" + audits.get());
		}
	}

	/**
	 * The same workload under {@code READ_COMMITTED}. A reader there resolves each read independently, so it can pick
	 * up one account before a transfer and the other after it, and see a total that never existed.
	 */
	@Test
	public void testReadCommittedCanObserveATornTotal() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> accounts = openAccounts(store);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicBoolean sawTorn = new AtomicBoolean();
			AtomicLong reads = new AtomicLong();
			CountDownLatch ready = new CountDownLatch(1);

			Thread auditor = Thread.ofPlatform().name("rc-auditor").start(() -> {
				ready.countDown();
				while (!stop.get() && !sawTorn.get()) {
					try (ReadTransaction ignored = store.beginRead(IsolationLevel.READ_COMMITTED)) {
						long total = 0;
						for (long account : accounts) {
							total += (Long) store.nodeProperty(account, "balance");
							// Widens the window between the two halves of the read. The anomaly is real without it;
							// this only makes the test's runtime predictable rather than lucky.
							Thread.onSpinWait();
						}
						reads.incrementAndGet();
						if (total != TOTAL) {
							sawTorn.set(true);
						}
					}
				}
			});

			ready.await();
			try {
				transfers(store, accounts).opsPerThread(400).run();
			} finally {
				stop.set(true);
				auditor.join(60_000);
			}

			assertTrue(reads.get() > 0, "the read-committed auditor never ran");
			assertTrue(sawTorn.get(), "READ_COMMITTED never observed a torn total across " + reads.get()
				+ " reads. Either the level is quietly behaving like SNAPSHOT — in which case nobody should be paying"
				+ " for it — or this test no longer creates a wide enough window to catch it");

			// Whatever a reader saw, the store itself must still be whole.
			long finalTotal = 0;
			for (long account : accounts) {
				finalTotal += (Long) store.nodeProperty(account, "balance");
			}
			assertEquals(TOTAL, finalTotal, "the store's actual total must be conserved regardless of what a reader saw");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The engine's own structural invariants, checked <b>through an open read view</b> rather than against the store.
	 *
	 * <p>
	 * {@code verify(FULL)} cannot do this: it takes the writer lock and inspects the store as it currently is. A view
	 * reconstructs pages from the version chains, and the risk specific to that path is a view that rebuilds one page
	 * and not another — a degree counter and the adjacency block it counts live in different places, so a partial
	 * reconstruction shows up as a node disagreeing with itself.
	 * </p>
	 */
	@Test
	public void testStructuralInvariantsHoldThroughAnOpenView() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> accounts = openAccounts(store);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicLong sweeps = new AtomicLong();
			CountDownLatch ready = new CountDownLatch(2);
			List<Thread> checkers = new ArrayList<>();

			for (int c = 0; c < 2; c++) {
				checkers.add(Thread.ofPlatform().name("view-invariants-" + c).start(() -> {
					ready.countDown();
					try {
						while (!stop.get()) {
							try (ReadTransaction view = store.beginRead()) {
								List<String> problems = viewInvariants(store, accounts);
								if (!problems.isEmpty()) {
									throw new AssertionError("view at LSN " + view.snapshotLsn()
										+ " is not internally consistent:\n  " + String.join("\n  ", problems));
								}
								sweeps.incrementAndGet();
							}
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				}));
			}

			ready.await();
			try {
				transfers(store, accounts).run();
			} finally {
				stop.set(true);
				for (Thread checker : checkers) {
					checker.join(60_000);
				}
			}

			if (failure.get() != null) {
				throw new AssertionError("a read view was not internally consistent", failure.get());
			}
			assertTrue(sweeps.get() > 20, "only " + sweeps.get() + " sweeps ran, too few to have tested anything");
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * What a view can be asked about itself without a model: that the two independently maintained descriptions of a
	 * node's edges agree, and that every edge it can see lands on a node it can also see.
	 */
	private List<String> viewInvariants(GraphStore store, List<Long> accounts) {
		List<String> problems = new ArrayList<>();
		for (long account : accounts) {
			if (!store.nodeExists(account)) {
				problems.add("account " + account + " is not visible, but accounts are never deleted");
				continue;
			}
			List<Edge> out = store.neighbours(account, Direction.OUT);
			int degreeOut = store.degreeOut(account);
			if (out.size() != degreeOut) {
				problems.add("account " + account + " reports out-degree " + degreeOut + " but enumerates "
					+ out.size() + " outgoing edges");
			}
			for (Edge edge : out) {
				if (!store.nodeExists(edge.neighbourId())) {
					problems.add("account " + account + " has an edge to " + edge.neighbourId()
						+ ", which the same view cannot resolve");
				}
			}
			if (store.nodeProperty(account, "balance") == null) {
				problems.add("account " + account + " has no balance, which every account always has");
			}
		}
		return problems;
	}

	/**
	 * The transfer workload. Read-modify-write of both balances plus a ledger edge, all in one transaction — which is
	 * safe precisely because a writer reads the current mapping rather than any view, and would be a lost update if it
	 * ever stopped doing so.
	 */
	private Hammer transfers(GraphStore store, List<Long> accounts) {
		return transfers(store, accounts, new AtomicLong());
	}

	private Hammer transfers(GraphStore store, List<Long> accounts, AtomicLong committed) {
		return Hammer.named("transfers")
			.threads(4)
			.opsPerThread(120)
			.op("transfer", 1, rnd -> {
				int fromIndex = rnd.nextInt(accounts.size());
				int toIndex = rnd.nextInt(accounts.size());
				if (fromIndex == toIndex) {
					return;
				}
				long from = accounts.get(fromIndex);
				long to = accounts.get(toIndex);
				// inTransaction rather than begin(), because writers run concurrently and two transfers touching one
				// account are a genuine conflict: each read a balance the other has since replaced. Retrying is the
				// answer and is what the engine asks for — the point of the test is that the total is conserved
				// however many attempts it takes, not that every attempt succeeds first time.
				boolean moved = store.inTransaction(() -> {
					long fromBalance = (Long) store.nodeProperty(from, "balance");
					long amount = fromBalance <= 1 ? 0 : 1 + rnd.nextInt((int) Math.min(50, fromBalance - 1));
					if (amount == 0) {
						return false;
					}
					long toBalance = (Long) store.nodeProperty(to, "balance");
					store.setNodeProperties(from, Properties.of().set("balance", fromBalance - amount));
					store.setNodeProperties(to, Properties.of().set("balance", toBalance + amount));
					store.createEdge(from, "PAID", to);
					return true;
				}, ATTEMPTS);
				if (moved) {
					// After the commit: a transfer that threw or was refused left no edge either.
					committed.incrementAndGet();
				}
			});
	}

	private List<Long> openAccounts(GraphStore store) throws Exception {
		List<Long> accounts = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < ACCOUNTS; i++) {
				accounts.add(store.createNode("Account", Properties.of()
					.set("balance", OPENING_BALANCE)
					.set("n", (long) i)));
			}
			txn.commit();
		}
		return accounts;
	}
}
