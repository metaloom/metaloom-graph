package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.ScanOptions;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * What a full scan sees when a writer is running underneath it.
 *
 * <p>
 * This is the property a logical backup rests on, and it is not obvious enough to assume. A backup walks every node
 * while writers keep committing; for the result to be a single point in time, the scan has to see <b>the node set as it
 * was when the view opened</b> — not merely each node's contents. That works only because the free-slot bitmap and the
 * generation the scan reads both go through {@code SegmentFile.readSlice}, which resolves through the snapshot's page
 * versions like any other read. If they did not, a backup would silently include nodes created after it started.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/BACKUP.md">spec/BACKUP.md</a>
 */
@Tag("unit")
public class SnapshotScanTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static List<Long> scan(GraphStore store) {
		List<Long> seen = new ArrayList<>();
		store.forEachNode(seen::add);
		return seen;
	}

	/** The load-bearing one: a node created after the view opened is not in the view's scan. */
	@Test
	public void testAScanUnderAViewDoesNotSeeNodesCommittedAfterIt() throws Exception {
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 50; i++) {
				store.createNode(List.of("Before"), Properties.of().set("i", (long) i));
			}
			txn.commit();
		}

		try (ReadTransaction view = store.beginRead(IsolationLevel.SNAPSHOT)) {
			assertEquals(50, scan(store).size(), "the view starts by seeing what was there");

			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 50; i++) {
					store.createNode(List.of("After"), Properties.of().set("i", (long) i));
				}
				txn.commit();
			}

			List<Long> after = scan(store);
			assertEquals(50, after.size(), "and still sees exactly those 50 — the node SET is snapshot-resolved, "
				+ "not just each node's contents");
			for (long id : after) {
				assertEquals(List.of("Before"), store.nodeLabels(id));
			}
		}

		assertEquals(100, scan(store).size(), "outside the view, both batches are there");
	}

	/** The other direction: a node deleted after the view opened is still in the view's scan. */
	@Test
	public void testAScanUnderAViewStillSeesNodesDeletedAfterIt() throws Exception {
		GraphStore store = stores.open();
		List<Long> ids = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 20; i++) {
				ids.add(store.createNode(List.of("Doomed"), Properties.of().set("i", (long) i)));
			}
			txn.commit();
		}

		try (ReadTransaction view = store.beginRead(IsolationLevel.SNAPSHOT)) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 10; i++) {
					store.deleteNode(ids.get(i));
				}
				txn.commit();
			}
			assertEquals(20, scan(store).size(), "a backup must not lose a node because it was deleted mid-walk");
		}

		assertEquals(10, scan(store).size());
	}

	/**
	 * A paced scan sees the same node set as an unpaced one, and lets writers through between batches.
	 *
	 * <p>
	 * Pacing is what makes a backup online: {@code forEachNode} otherwise holds the apply latch for its whole
	 * duration, which blocks every writer in the process. Dropping the latch between batches is only safe because of
	 * the property above — the snapshot, not the latch, is what fixes what the scan sees.
	 * </p>
	 */
	@Test
	public void testAPacedScanSeesTheSameSetAndLetsAWriterThrough() throws Exception {
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 5_000; i++) {
				store.createNode(List.of("Node"), Properties.of().set("i", (long) i));
			}
			txn.commit();
		}

		AtomicInteger committed = new AtomicInteger();
		Thread writer;
		List<Long> paced = new ArrayList<>();

		try (ReadTransaction view = store.beginRead(IsolationLevel.SNAPSHOT)) {
			writer = new Thread(() -> {
				// Keeps committing for as long as the scan runs. Without pacing every one of these would queue behind
				// the whole scan, and the count below would be 0 or 1.
				for (int i = 0; i < 200; i++) {
					try (Transaction txn = store.begin()) {
						store.createNode(List.of("Concurrent"), Properties.of().set("i", (long) i));
						txn.commit();
						committed.incrementAndGet();
					} catch (Exception e) {
						return;
					}
				}
			});
			writer.start();
			store.forEachNodeWhile(id -> {
				paced.add(id);
				return true;
			}, ScanOptions.throttled());
			writer.join();
		}

		assertEquals(5_000, paced.size(), "the paced scan saw the snapshot's node set, whole and unmixed");
		assertTrue(committed.get() > 0, "and the writer was not blocked for the whole scan; it committed "
			+ committed.get());
	}

	/** An unpaced scan is still available and still correct; it is the default. */
	@Test
	public void testTheDefaultScanIsUnpaced() throws Exception {
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			store.createNode(List.of("One"));
			txn.commit();
		}
		assertEquals(1, scan(store).size());
		List<Long> viaOptions = new ArrayList<>();
		store.forEachNodeWhile(id -> {
			viaOptions.add(id);
			return true;
		}, ScanOptions.uninterrupted());
		assertEquals(1, viaOptions.size());
	}

	/** A paced scan still stops early, or a LIMIT would cost what the batch size is rather than what it asked for. */
	@Test
	public void testAPacedScanStillStopsEarly() throws Exception {
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 3_000; i++) {
				store.createNode(List.of("Node"));
			}
			txn.commit();
		}
		AtomicInteger seen = new AtomicInteger();
		boolean complete = store.forEachNodeWhile(id -> seen.incrementAndGet() < 10,
			ScanOptions.builder().batchSize(4).build());
		assertEquals(10, seen.get());
		assertTrue(!complete, "and it reports that it stopped");
	}
}
