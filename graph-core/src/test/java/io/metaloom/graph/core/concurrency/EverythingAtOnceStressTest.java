package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.ScanOptions;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.fixture.StressRun;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * Everything the engine does concurrently, at once, against one store: transfers at {@code SNAPSHOT} and
 * {@code SERIALIZABLE}, a churning writer that gives compaction work, vector inserts and searches, snapshot audits, a
 * paced backup-shaped scan, and a maintenance thread cycling checkpoint, hole punching, compaction and verification.
 *
 * <p>
 * Each piece has its own test elsewhere. What only this one can find is an interaction: compaction relocating a record a
 * writer is about to look up, a vacuum under a paced scan, a checkpoint between a commit's log and its apply, a
 * maintenance refusal leaving something half done. The oracles are application-level — a conserved total, one ledger
 * edge per committed transfer, a unique key that stays unique, indexes equal to a scan in the same snapshot, and an
 * exact model of the churned nodes — followed by {@code verify(FULL)}.
 * </p>
 *
 * <p>
 * Ids are never held across operations: compaction renumbers records, so every writer finds its accounts through the
 * unique key each time, which is what an application would have to do too.
 * </p>
 *
 * <p>
 * {@code @Tag("slow")}: about twenty seconds by default, {@code -Dgraph.stress.millis} for a longer run.
 * </p>
 */
@Tag("slow")
public class EverythingAtOnceStressTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int ACCOUNTS = 24;
	private static final long OPENING = 1_000;
	private static final long TOTAL = ACCOUNTS * OPENING;
	private static final int DIMS = 4;

	@Test
	public void testEverythingAtOnce() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(DIMS)
			.retainedVersionBytes(4L * 1024 * 1024)
			.walSyncMode(WalSyncMode.ASYNC)
			.build();
		try (GraphStore store = stores.open(options)) {
			store.createUniqueConstraint("Account", "k");
			store.createIndex("Junk", "j");
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "emb", DIMS, VectorMetric.EUCLIDEAN));
			try (Transaction txn = store.begin()) {
				for (int a = 0; a < ACCOUNTS; a++) {
					store.createNode("Account", Properties.of().set("k", (long) a).set("balance", OPENING));
				}
				txn.commit();
			}

			AtomicLong transfers = new AtomicLong();
			AtomicLong audits = new AtomicLong();
			AtomicLong pacedScans = new AtomicLong();
			AtomicLong searches = new AtomicLong();
			AtomicLong compactions = new AtomicLong();
			AtomicLong refusals = new AtomicLong();
			AtomicLong abandoned = new AtomicLong();
			// The churner is a single thread, so its model is exact: the j values of the Junk nodes that exist.
			Set<Long> junk = new HashSet<>();
			long[] nextJunk = { 0 };
			Set<Long> docs = ConcurrentHashMap.newKeySet();
			long[] nextDoc = { 0 };

			StressRun.named("everything")
				.millis(20_000)
				.watchdogSeconds(120)
				.platform("transfer", 3, rnd -> {
					transfer(store, rnd, IsolationLevel.SNAPSHOT, transfers);
					return true;
				})
				.virtual("transfer", 2, rnd -> {
					transfer(store, rnd, IsolationLevel.SNAPSHOT, transfers);
					return true;
				})
				.platform("serializable-transfer", 1, rnd -> {
					transfer(store, rnd, IsolationLevel.SERIALIZABLE, transfers);
					return true;
				})
				.platform("churner", 1, rnd -> {
					if (junk.size() < 50 || rnd.nextInt(5) < 3) {
						int count = 1 + rnd.nextInt(20);
						int pad = rnd.nextInt(80);
						// Into the model only once committed: a refused attempt created nothing. inStatement rather than
						// inTransaction, because a batch of twenty index inserts beside a stream of short transfers is
						// exactly the shape that can lose every optimistic attempt (spec/TRANSACTIONS.md §6.3), and
						// only the statement path guarantees it finishes.
						List<Long> made = store.inStatement(() -> {
							List<Long> js = new ArrayList<>();
							for (int i = 0; i < count; i++) {
								long j = nextJunk[0]++;
								store.createNode("Junk", Properties.of().set("j", j).set("pad", "x".repeat(pad)));
								js.add(j);
							}
							return js;
						});
						junk.addAll(made);
					} else {
						List<Long> victims = new ArrayList<>(junk).subList(0, Math.min(junk.size(), 1 + rnd.nextInt(30)));
						for (long j : victims) {
							// Looked up and deleted in one statement: between two, compaction may renumber the node.
							store.inStatement(() -> {
								for (long id : store.nodesByLabelAndProperty("Junk", "j", j)) {
									store.deleteNode(id);
								}
								return null;
							});
							junk.remove(j);
						}
					}
					return true;
				})
				.virtual("vectors", 1, rnd -> {
					if (docs.size() < 30 || rnd.nextBoolean()) {
						long d = nextDoc[0]++;
						store.createNode("Doc", Properties.of().set("d", d).set("emb", vector(rnd)));
						docs.add(d);
					} else {
						long d = docs.iterator().next();
						docs.remove(d);
						store.inStatement(() -> {
							for (long id : store.nodesByProperty("d", d)) {
								store.deleteNode(id);
							}
							return null;
						});
					}
					return true;
				})
				.platform("auditor", 2, rnd -> {
					try (ReadTransaction view = store.beginRead()) {
						List<String> problems = audit(store);
						if (!problems.isEmpty()) {
							throw new AssertionError("a view at LSN " + view.snapshotLsn() + ":\n  "
								+ String.join("\n  ", problems));
						}
						audits.incrementAndGet();
					} catch (SnapshotTooOldException e) {
						abandoned.incrementAndGet();
					}
					// Gaps between views, so maintenance — refused while any view is open — gets windows.
					LockSupport.parkNanos(rnd.nextInt(3_000_000));
					return true;
				})
				.virtual("searcher", 1, rnd -> {
					try (ReadTransaction view = store.beginRead()) {
						float[] query = vector(rnd);
						List<Neighbour> found = store.nearest("Doc", "emb", query, 5, null);
						Map<Long, Double> exact = new HashMap<>();
						for (Neighbour n : store.nearestByScan("Doc", "emb", query, Integer.MAX_VALUE, VectorMetric.EUCLIDEAN)) {
							exact.put(n.nodeId(), n.score());
						}
						for (Neighbour n : found) {
							Double score = exact.get(n.nodeId());
							if (score == null || Math.abs(score - n.score()) > 1e-4) {
								throw new AssertionError("a view at LSN " + view.snapshotLsn() + " was offered " + n
									+ "; the same snapshot scores that node " + score);
							}
						}
						searches.incrementAndGet();
					} catch (SnapshotTooOldException e) {
						abandoned.incrementAndGet();
					}
					LockSupport.parkNanos(rnd.nextInt(2_000_000));
					return true;
				})
				.platform("paced-scan", 1, rnd -> {
					// The shape of the online backup: one view, a scan that yields the latch every batch so writers
					// keep committing underneath, and a total that must still come out exact.
					try (ReadTransaction view = store.beginRead()) {
						long[] total = { 0 };
						List<Long> ids = new ArrayList<>();
						store.forEachNodeWhile(id -> {
							ids.add(id);
							return true;
						}, ScanOptions.builder().batchSize(64).pauseMicrosPerBatch(50).build());
						for (long id : ids) {
							if (store.nodeHasLabel(id, "Account")) {
								total[0] += (Long) store.nodeProperty(id, "balance");
							}
						}
						if (total[0] != TOTAL) {
							throw new AssertionError("a paced scan in a view at LSN " + view.snapshotLsn() + " totalled "
								+ total[0]);
						}
						pacedScans.incrementAndGet();
					} catch (SnapshotTooOldException e) {
						abandoned.incrementAndGet();
					}
					LockSupport.parkNanos(rnd.nextInt(5_000_000));
					return true;
				})
				.platform("maintenance", 1, rnd -> {
					try {
						switch (rnd.nextInt(5)) {
							case 0 -> store.checkpoint();
							case 1 -> store.reclaim();
							case 2, 3 -> {
								store.compact(CompactionOptions.builder()
									.livenessThreshold(0.95)
									.maxRelocationsPerTransaction(32)
									.build());
								compactions.incrementAndGet();
							}
							default -> store.verify(VerifyLevel.CHECKSUMS);
						}
					} catch (GraphStoreException e) {
						if (!String.valueOf(e.getMessage()).contains("read view")) {
							throw e;
						}
						// Refused because a view was open, which is the documented answer.
						refusals.incrementAndGet();
					}
					LockSupport.parkNanos(rnd.nextInt(20_000_000));
					return true;
				})
				.run();

			System.out.println("[everything] transfers=" + transfers.get() + " audits=" + audits.get() + " paced="
				+ pacedScans.get() + " searches=" + searches.get() + " compactions=" + compactions.get()
				+ " refusals=" + refusals.get() + " abandoned=" + abandoned.get() + " junk=" + junk.size()
				+ " docs=" + docs.size() + " " + store.stats());

			List<String> problems = audit(store);
			assertTrue(problems.isEmpty(), "quiescent store: " + problems);
			long ledger = 0;
			for (long id : store.nodesByLabel("Account")) {
				ledger += store.degreeOut(id);
			}
			assertEquals(transfers.get(), ledger, "every committed transfer leaves exactly one ledger edge");
			Set<Long> junkInStore = new TreeSet<>();
			for (long id : store.nodesByLabel("Junk")) {
				assertTrue(junkInStore.add((Long) store.nodeProperty(id, "j")), "two Junk nodes share a j value");
			}
			assertEquals(new TreeSet<>(junk), junkInStore, "the churned nodes are not what the churner committed");
			Set<Long> docsInStore = new TreeSet<>();
			for (long id : store.nodesByLabel("Doc")) {
				docsInStore.add((Long) store.nodeProperty(id, "d"));
			}
			assertEquals(new TreeSet<>(docs), docsInStore);
			assertTrue(transfers.get() > 100 && audits.get() > 10 && searches.get() > 10 && pacedScans.get() > 2,
				"the run did too little to have tested anything");
			store.verify(VerifyLevel.FULL);
			store.compact(CompactionOptions.always());
			assertTrue(audit(store).isEmpty(), "after a final compaction: " + audit(store));
			store.verify(VerifyLevel.FULL);
		}
	}

	private static void transfer(GraphStore store, java.util.Random rnd, IsolationLevel level, AtomicLong transfers)
		throws Exception {
		long fromKey = rnd.nextInt(ACCOUNTS);
		long toKey = rnd.nextInt(ACCOUNTS);
		if (fromKey == toKey) {
			return;
		}
		long amount = 1 + rnd.nextInt(40);
		boolean moved;
		try {
			moved = attemptTransfer(store, fromKey, toKey, amount, level);
		} catch (io.metaloom.graph.core.api.TransactionConflictException starved) {
			// A bounded retry budget running out against a stream of competitors is a documented outcome of
			// inTransaction (spec/TRANSACTIONS.md §6.3); nothing was committed, so nothing is owed to the ledger.
			return;
		}
		if (moved) {
			transfers.incrementAndGet();
		}
	}

	private static boolean attemptTransfer(GraphStore store, long fromKey, long toKey, long amount,
		IsolationLevel level) throws Exception {
		return store.inTransaction(() -> {
			long from = only(store.nodesByLabelAndProperty("Account", "k", fromKey));
			long to = only(store.nodesByLabelAndProperty("Account", "k", toKey));
			long fromBalance = (Long) store.nodeProperty(from, "balance");
			if (fromBalance < amount) {
				return false;
			}
			long toBalance = (Long) store.nodeProperty(to, "balance");
			store.setNodeProperties(from, Properties.of().set("k", fromKey).set("balance", fromBalance - amount));
			store.setNodeProperties(to, Properties.of().set("k", toKey).set("balance", toBalance + amount));
			store.createEdge(from, "PAID", to);
			return true;
		}, 200, level);
	}

	private static long only(List<Long> ids) {
		if (ids.size() != 1) {
			throw new AssertionError("the unique key resolved to " + ids.size() + " accounts: " + ids);
		}
		return ids.get(0);
	}

	private static List<String> audit(GraphStore store) {
		List<String> problems = new ArrayList<>();
		List<Long> accounts = store.nodesByLabel("Account");
		if (accounts.size() != ACCOUNTS) {
			problems.add(accounts.size() + " accounts through the label index");
		}
		long total = 0;
		long out = 0;
		long in = 0;
		Set<Long> keys = new HashSet<>();
		for (long id : accounts) {
			if (!store.nodeExists(id)) {
				problems.add("the label index names " + id + ", which does not exist");
				continue;
			}
			long k = (Long) store.nodeProperty(id, "k");
			if (!keys.add(k)) {
				problems.add("two accounts with k=" + k);
			}
			List<Long> byKey = store.nodesByLabelAndProperty("Account", "k", k);
			if (!byKey.equals(List.of(id))) {
				problems.add("k=" + k + " resolves to " + byKey + " rather than " + id);
			}
			total += (Long) store.nodeProperty(id, "balance");
			out += store.degreeOut(id);
			in += store.degreeIn(id);
		}
		if (total != TOTAL) {
			problems.add("total " + total + " rather than " + TOTAL);
		}
		if (out != in) {
			problems.add("ledger edges: " + out + " leave accounts and " + in + " arrive");
		}
		return problems;
	}

	private static float[] vector(java.util.Random rnd) {
		float[] v = new float[DIMS];
		for (int i = 0; i < DIMS; i++) {
			v[i] = rnd.nextFloat();
		}
		return v;
	}
}
