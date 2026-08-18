package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * The query layer under concurrency.
 *
 * <p>
 * {@code CypherSnapshotTest} establishes that a query opens a view and closes it. This asks the harder question: with
 * many queries and many writers running at once, does the view actually <em>buy</em> anything? A query is dozens of
 * storage calls — scan, expand, read properties off the far end — and the whole reason it runs inside a
 * {@code SNAPSHOT} view is so that a writer committing between call twelve and call thirteen cannot make it return a
 * result that describes no state the database was ever in.
 * </p>
 *
 * <p>
 * The oracle is a conserved total: accounts whose balances always sum to the same number, moved around by writers
 * going through the Java API while the queries read through Cypher. A query that returned a partial transfer would be
 * a query that saw two instants, and no amount of internal consistency in the executor would show it.
 * </p>
 *
 * <p>
 * Writes-inside-a-view are refused, and that is covered by {@code CypherWriteTransactionTest} rather than repeated
 * here.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a> section 4.1
 */
@Tag("unit")
public class CypherConcurrencyTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int ACCOUNTS = 12;
	private static final long OPENING_BALANCE = 1_000L;
	private static final long TOTAL = ACCOUNTS * OPENING_BALANCE;

	@Test
	public void testConcurrentQueriesNeverSeeATransferHalfDone() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> accounts = openAccounts(store);

			AtomicBoolean stop = new AtomicBoolean();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicLong queries = new AtomicLong();
			List<Thread> readers = new ArrayList<>();

			for (int r = 0; r < 3; r++) {
				readers.add(Thread.ofPlatform().name("cypher-reader-" + r).start(() -> {
					try {
						while (!stop.get()) {
							CypherResult result = Cypher.query(store, "MATCH (a:Account) RETURN a.balance");
							assertEquals(ACCOUNTS, result.size(),
								"a query saw " + result.size() + " accounts rather than " + ACCOUNTS
									+ ", so its scan and its reads disagreed about which nodes exist");
							long total = 0;
							for (int i = 0; i < result.size(); i++) {
								total += result.row(i).longValue("a.balance");
							}
							if (total != TOTAL) {
								throw new AssertionError("a query totalled " + total + " rather than " + TOTAL
									+ ", so it read some accounts before a transfer and others after it");
							}
							queries.incrementAndGet();
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				}));
			}

			try {
				transfers(store, accounts).run().assertRan("transfer");
			} finally {
				stop.set(true);
				for (Thread reader : readers) {
					reader.join(60_000);
				}
			}

			if (failure.get() != null) {
				throw new AssertionError("a Cypher query observed a transfer half applied", failure.get());
			}
			assertTrue(queries.get() > 20, "only " + queries.get() + " queries ran, too few to have tested anything");
			assertEquals(0, store.snapshotStats().openViews(), "a query left its read view open");
			System.out.println("[cypher-concurrency] queries=" + queries.get());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * Write queries from many threads. Each is one transaction, so the count at the end is exact — a write query that
	 * committed twice, or partially, would show up as a number other than the one asked for.
	 */
	@Test
	public void testConcurrentWriteQueriesEachCommitExactlyOnce() throws Exception {
		try (GraphStore store = stores.open()) {
			AtomicLong issued = new AtomicLong();

			Hammer.named("cypher-writes")
				.threads(4)
				.opsPerThread(60)
				.op("create", 3, rnd -> {
					long n = issued.incrementAndGet();
					Cypher.query(store, "CREATE (w:Written {n: " + n + "})");
				})
				.op("read", 2, rnd -> Cypher.query(store, "MATCH (w:Written) RETURN w.n"))
				.run()
				.assertRan("create", "read");

			CypherResult all = Cypher.query(store, "MATCH (w:Written) RETURN w.n");
			assertEquals(issued.get(), all.size(),
				"every write query is one transaction, so exactly " + issued.get() + " nodes should exist");

			// And every one of them is distinct: a query that committed twice would duplicate an n.
			java.util.Set<Long> seen = new java.util.HashSet<>();
			for (int i = 0; i < all.size(); i++) {
				long n = all.row(i).longValue("w.n");
				assertTrue(seen.add(n), "value " + n + " was written twice, so a write query committed more than once");
			}

			assertEquals(0, store.snapshotStats().openViews(), "a query left its read view open");
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Reads and writes interleaved through the query layer, with the store whole at the end. */
	@Test
	public void testMixedQueryLoadLeavesTheStoreConsistent() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> accounts = openAccounts(store);

			Hammer.named("cypher-mixed")
				.threads(5)
				.opsPerThread(60)
				.op("match", 3, rnd -> Cypher.query(store, "MATCH (a:Account) RETURN a.balance"))
				.op("matchWhere", 2, rnd -> Cypher.query(store,
					"MATCH (a:Account) WHERE a.balance > " + rnd.nextInt(2000) + " RETURN a.balance"))
				.op("create", 2, rnd -> Cypher.query(store, "CREATE (t:Trace {n: " + rnd.nextInt(10_000) + "})"))
				.op("expand", 1, rnd -> Cypher.query(store, "MATCH (a:Account)-[:PAID]->(b:Account) RETURN b.balance"))
				.op("transfer", 2, rnd -> transferOnce(store, accounts, rnd))
				.run()
				.assertRan("match", "matchWhere", "create", "expand", "transfer");

			CypherResult result = Cypher.query(store, "MATCH (a:Account) RETURN a.balance");
			long total = 0;
			for (int i = 0; i < result.size(); i++) {
				total += result.row(i).longValue("a.balance");
			}
			assertEquals(TOTAL, total, "value was created or destroyed under mixed query load");
			assertEquals(0, store.snapshotStats().openViews(), "a query left its read view open");
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- workload

	private Hammer transfers(GraphStore store, List<Long> accounts) {
		return Hammer.named("cypher-transfers")
			.threads(3)
			.opsPerThread(100)
			.op("transfer", 1, rnd -> transferOnce(store, accounts, rnd));
	}

	private void transferOnce(GraphStore store, List<Long> accounts, java.util.Random rnd) throws Exception {
		int fromIndex = rnd.nextInt(accounts.size());
		int toIndex = rnd.nextInt(accounts.size());
		if (fromIndex == toIndex) {
			return;
		}
		long from = accounts.get(fromIndex);
		long to = accounts.get(toIndex);
		// inTransaction, not begin(): the writers of this test are the storage API rather than Cypher, and with
		// concurrent writers two transfers touching one account are a genuine conflict — each read a balance the other
		// has since replaced. Retrying is the contract. What the test is about is the reader: a Cypher query is dozens
		// of storage calls, and the view is what stops it spanning two states however many attempts the writers take.
		store.inTransaction(() -> {
			long fromBalance = (Long) store.nodeProperty(from, "balance");
			long amount = fromBalance <= 1 ? 0 : 1 + rnd.nextInt((int) Math.min(50, fromBalance - 1));
			if (amount == 0) {
				return null;
			}
			long toBalance = (Long) store.nodeProperty(to, "balance");
			store.setNodeProperties(from, Properties.of().set("balance", fromBalance - amount));
			store.setNodeProperties(to, Properties.of().set("balance", toBalance + amount));
			store.createEdge(from, "PAID", to);
			return null;
		}, 200);
	}

	private List<Long> openAccounts(GraphStore store) throws Exception {
		List<Long> accounts = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < ACCOUNTS; i++) {
				accounts.add(store.createNode("Account", Properties.of().set("balance", OPENING_BALANCE)));
			}
			txn.commit();
		}
		return accounts;
	}
}
