package io.metaloom.graph.core.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * What each {@link WalSyncMode} actually promises, checked by crashing and counting.
 *
 * <p>
 * The three modes are a durability/throughput dial and the documentation for them is a sentence each. This turns those
 * sentences into assertions, because "may discard commits from the last interval" and "loses everything since the last
 * checkpoint" are indistinguishable until something measures them.
 * </p>
 *
 * <p>
 * The workload records its own acknowledgements — every commit whose {@code commit()} returned — so the test can tell
 * <b>"lost a commit"</b> apart from <b>"never committed"</b>. Without that distinction a durability test cannot say
 * anything at all, which is why {@code spec/TESTING.md} section 6 makes it point 3.
 * </p>
 *
 * <p>
 * One property is required of <em>every</em> mode and is the more important of the two: survivors must form a
 * <b>prefix</b>. Losing the tail is a documented trade; losing commit 40 while keeping 41 would mean the log had been
 * applied out of order, and no mode is allowed that.
 * </p>
 *
 * <p>
 * <b>What this harness cannot show.</b> {@code abandon()} is an in-process crash: the process keeps running and the
 * operating system's page cache is untouched, so an unsynced write is still there to be read back. In practice
 * {@code GROUP_COMMIT} and {@code ASYNC} therefore recover <em>everything</em> here, and the printed numbers must not
 * be read as evidence that they are as durable as {@code FSYNC_ON_COMMIT} — they are not, and the difference only
 * appears when the page cache dies with the machine. What these two cases really assert is the prefix property and a
 * clean recovery, which is what an in-process crash can honestly test. Separating the modes needs
 * {@code tools/kill9-loop.sh} for process loss, and the manual procedure in {@code spec/OPERATIONS.md} section 8 for
 * power loss.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 4
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 6
 */
@Tag("crash")
public class SyncModeDurabilityTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int COMMITS = 300;

	/** No acknowledged commit may ever be lost. This is the mode that makes the durability claim. */
	@Test
	public void testFsyncOnCommitLosesNothingItAcknowledged() throws Exception {
		Survivors survivors = crashAfterCommitting(WalSyncMode.FSYNC_ON_COMMIT, 0);
		assertEquals(survivors.acknowledged(), survivors.recovered().size(),
			"FSYNC_ON_COMMIT lost " + (survivors.acknowledged() - survivors.recovered().size())
				+ " acknowledged commit(s) of " + survivors.acknowledged()
				+ ", which is the one thing this mode exists to prevent");
	}

	/** May lose the last interval, must not lose more than it wrote, and must still lose only a suffix. */
	@Test
	public void testGroupCommitLosesAtMostATail() throws Exception {
		Survivors survivors = crashAfterCommitting(WalSyncMode.GROUP_COMMIT, 200);
		assertTrue(survivors.recovered().size() <= survivors.acknowledged(),
			"recovery invented commits that were never acknowledged");
		System.out.println("[sync-mode] GROUP_COMMIT kept " + survivors.recovered().size() + " of "
			+ survivors.acknowledged() + " acknowledged");
	}

	/** The weakest mode. It may lose a great deal; it may not lose a hole. */
	@Test
	public void testAsyncStillRecoversToAConsistentPrefix() throws Exception {
		Survivors survivors = crashAfterCommitting(WalSyncMode.ASYNC, 0);
		assertTrue(survivors.recovered().size() <= survivors.acknowledged(),
			"recovery invented commits that were never acknowledged");
		System.out.println("[sync-mode] ASYNC kept " + survivors.recovered().size() + " of "
			+ survivors.acknowledged() + " acknowledged");
	}

	// ---------------------------------------------------------------- harness

	private record Survivors(int acknowledged, TreeSet<Long> recovered) {
	}

	/**
	 * Commits {@link #COMMITS} single-node transactions, each numbered, then crashes without a checkpoint and reopens.
	 * The prefix property and a clean {@code verify(FULL)} are asserted here because they hold for every mode.
	 */
	private Survivors crashAfterCommitting(WalSyncMode mode, long syncIntervalMillis) throws Exception {
		GraphStoreOptions.Builder builder = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.walSyncMode(mode);
		if (syncIntervalMillis > 0) {
			builder.walSyncIntervalMillis(syncIntervalMillis);
		}

		List<Long> acknowledged = new ArrayList<>();
		GraphStore store = stores.open(builder.build());
		try {
			for (int i = 0; i < COMMITS; i++) {
				long sequence = i;
				try (Transaction txn = store.begin()) {
					store.createNode("Numbered", Properties.of().set("seq", sequence));
					txn.commit();
				}
				// Only after commit() returned. That is what "the client observed as committed" means.
				acknowledged.add(sequence);
			}
			((io.metaloom.graph.core.store.GraphStoreImpl) store).abandon();
		} finally {
			try {
				store.close();
			} catch (Exception ignored) {
				// An abandoned store refuses an orderly close; that is the point of abandoning it.
			}
		}

		TreeSet<Long> recovered = new TreeSet<>();
		try (GraphStore reopened = stores.open(builder.build())) {
			reopened.verify(VerifyLevel.FULL);
			List<Long> ids = new ArrayList<>();
			reopened.forEachNode(ids::add);
			for (long id : ids) {
				Object seq = reopened.nodeProperty(id, "seq");
				if (seq != null) {
					recovered.add((Long) seq);
				}
			}
			assertEquals(ids.size(), recovered.size(), "two recovered nodes carry the same sequence number");
		}

		assertPrefix(mode, recovered);
		return new Survivors(acknowledged.size(), recovered);
	}

	/**
	 * Survivors must be {@code 0..k-1} with no hole. A gap means the log was applied out of order, which would make
	 * every mode's promise meaningless rather than merely weak.
	 */
	private void assertPrefix(WalSyncMode mode, TreeSet<Long> recovered) {
		long expected = 0;
		for (long seq : recovered) {
			if (seq != expected) {
				throw new AssertionError(mode + " recovered a store with a hole in it: expected sequence " + expected
					+ " but the next survivor is " + seq + ". Survivors must be a prefix — losing the tail is a"
					+ " documented trade, losing the middle means commits were applied out of order");
			}
			expected++;
		}
	}
}
