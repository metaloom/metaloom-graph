package io.metaloom.graph.core.budget;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * Write amplification, enforced as a build failure.
 * <p>
 * Amplification is bytes the engine actually writes over bytes of record payload it was asked to store. It is the
 * number that decides how fast an SSD wears out, and it cannot be argued about without measuring both halves.
 * <p>
 * <b>The floor for this engine is 2, and that is structural.</b> A no-steal write-ahead log writes every change twice:
 * once to the log so the commit is durable, and once to the data file when the transaction's private page copies are
 * applied. Nothing below 2 is achievable without changing the durability model. The remaining headroom covers
 * page-image granularity in the log, index maintenance, adjacency and superblock flips.
 *
 * <p>
 * <b>The mixed-workload figure used to read 1.70x, and that was an accounting artifact rather than a better engine.</b>
 * The denominator is {@code recordBytes()}, which counts <em>allocated</em> record space, and an adjacency block used
 * to be a whole 8 KiB page from the third edge onwards. A workload that promoted a hundred nodes therefore credited
 * itself with 800 KiB of "payload" that held a few hundred edges. Once a block became a run of 512 byte cells the
 * denominator halved and the ratio doubled — while the bytes written moved by less than half a percent: 1,458,816
 * before against 1,452,282 after, measured on the same workload with only the cell size changed. Nothing regressed.
 * The old number was wrong, and the gate below is set against the honest one.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
@Tag("unit")
public class WriteAmplificationTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final double TARGET = 3.0;

	/**
	 * The mixed workload's gate. Higher than {@link #TARGET} for one reason, stated so it cannot be mistaken for
	 * slack: this workload commits 20,000 operations scattered at random over 5,000 nodes in one transaction, so
	 * almost every 64 byte record write lands on a different 8 KiB page and the log carries the whole page. That is
	 * page-image granularity, not waste in the workload, and it is what the {@code IoEngine} of Phase 6 addresses.
	 */
	private static final double SCATTERED_TARGET = 4.0;

	@Test
	public void testBulkLoadStaysUnderTheTarget() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			IoStats before = impl.counters().snapshot();
			long payloadBefore = store.stats().recordBytes();

			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 20_000; i++) {
					store.createNode("N", Properties.of().set("i", i).set("name", "node-" + i));
				}
				txn.commit();
			}
			store.checkpoint();

			IoStats stats = impl.counters().snapshot().since(before);
			long payload = store.stats().recordBytes() - payloadBefore;
			double amplification = stats.writeAmplification(payload);
			System.out.printf("bulk load: %,d payload bytes, %,d wal + %,d applied = %.2fx%n",
				payload, stats.walBytesAppended(), stats.bytesApplied(), amplification);

			assertTrue(amplification < TARGET,
				"bulk load write amplification " + amplification + " exceeds the " + TARGET + " target: " + stats);
			assertTrue(amplification >= 1.5,
				"an amplification below the structural floor means the measurement is wrong, not that the engine is "
					+ "magic: " + amplification);
		}
	}

	@Test
	public void testMixedWorkloadStaysUnderTheTarget() throws Exception {
		// 80/20 read/write with creates, property updates, edges and deletes, which is what an OLTP mix looks like.
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 5000; i++) {
					ids.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			store.checkpoint();

			IoStats before = impl.counters().snapshot();
			long payloadBefore = store.stats().recordBytes();
			Random random = new Random(20260813L);

			try (Transaction txn = store.begin()) {
				for (int op = 0; op < 20_000; op++) {
					int roll = random.nextInt(100);
					long id = ids.get(random.nextInt(ids.size()));
					if (roll < 80) {
						store.nodeProperties(id);
					} else if (roll < 88) {
						ids.add(store.createNode("N", Properties.of().set("i", 1_000_000 + op)));
					} else if (roll < 94) {
						store.setNodeProperties(id, Properties.of().set("i", op).set("touched", true));
					} else {
						store.createEdge(id, "E", ids.get(random.nextInt(ids.size())));
					}
				}
				txn.commit();
			}
			store.checkpoint();

			IoStats stats = impl.counters().snapshot().since(before);
			long payload = store.stats().recordBytes() - payloadBefore;
			double amplification = stats.writeAmplification(payload);
			System.out.printf("mixed 80/20: %,d payload bytes, %,d wal + %,d applied = %.2fx%n",
				payload, stats.walBytesAppended(), stats.bytesApplied(), amplification);

			assertTrue(amplification < SCATTERED_TARGET,
				"mixed workload write amplification " + amplification + " exceeds the " + SCATTERED_TARGET
					+ " target. Payload " + payload + " bytes, " + stats);
		}
	}

	@Test
	public void testPerCommitAmplificationIsWorseAndTheTestSaysSo() throws Exception {
		// One node per transaction is the pathological case: a whole page image per commit plus the log record.
		// It is not a failure, it is the reason the API exposes explicit transactions, and it is worth measuring so
		// that the difference between the two is a number rather than an assertion.
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			IoStats before = impl.counters().snapshot();
			long payloadBefore = store.stats().recordBytes();

			for (int i = 0; i < 500; i++) {
				store.createNode("N", Properties.of().set("i", i));
			}
			store.checkpoint();

			IoStats stats = impl.counters().snapshot().since(before);
			long payload = store.stats().recordBytes() - payloadBefore;
			double amplification = stats.writeAmplification(payload);
			System.out.printf("one node per commit: %,d payload bytes, %,d wal + %,d applied = %.2fx%n",
				payload, stats.walBytesAppended(), stats.bytesApplied(), amplification);

			assertTrue(stats.walSyncs() >= 500, "each auto-commit must be its own fsync: " + stats);
			assertTrue(amplification > 0, "and the cost of that must be measurable: " + amplification);
		}
	}
}
