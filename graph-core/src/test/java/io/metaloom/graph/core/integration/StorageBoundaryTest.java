package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.id.StaleIdException;
import io.metaloom.graph.core.nativelib.NativeLib;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The edges of the storage layer, each taken to the boundary and one step past it, and then through a crash.
 *
 * <p>
 * The randomised harnesses reach these only by luck: a property block that fills its page to the byte, a segment
 * filled to its last slot, an id handed out again after a crash, a value that is empty rather than absent. Each case
 * here goes to the boundary on purpose and then asks what recovery, compaction and {@code verify(FULL)} make of it.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a>
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a>
 */
@Tag("unit")
public class StorageBoundaryTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static GraphStoreOptions smallSegments() {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(1024 * 1024)
			.build();
	}

	/**
	 * A property block filled to the last byte its page can hold is accepted and survives a crash; one byte more is
	 * refused and leaves nothing behind.
	 */
	@Test
	public void testAPropertyBlockAtExactlyThePageLimit() throws Exception {
		GraphStore store = stores.open();
		boolean abandoned = false;
		try {
			// Inline strings of up to 200 bytes, so the whole block stays in one page and nothing escapes to a blob.
			// Binary search for the largest payload accepted.
			int low = 1000;
			int high = 3 * Geometry.DEFAULT_PAGE_SIZE;
			while (high - low > 1) {
				int middle = (low + high) >>> 1;
				try {
					store.deleteNode(store.createNode("Probe", inlineStrings(middle)));
					low = middle;
				} catch (IllegalArgumentException refused) {
					high = middle;
				}
			}
			int length = low;
			assertTrue(length > Geometry.DEFAULT_PAGE_SIZE - 1500, "the limit is far below the page: " + length);
			Properties largest = inlineStrings(length);
			assertEquals(0, store.stats().nodeCount(), "a refused block left a node behind");
			assertEquals(0, store.stats().blobExtentCount());
			store.verify(VerifyLevel.FULL);

			long id = store.createNode("Full", largest);
			((GraphStoreImpl) store).abandon();
			abandoned = true;
			try (GraphStore reopened = stores.open()) {
				assertEquals(largest.asMap(), reopened.nodeProperties(id).asMap());
				// And the update path, which frees the old block in the same statement that writes the new one.
				reopened.setNodeProperties(id, inlineStrings(length - 300));
				reopened.setNodeProperties(id, largest);
				assertEquals(largest.asMap(), reopened.nodeProperties(id).asMap());
				assertThrows(IllegalArgumentException.class,
					() -> reopened.setNodeProperties(id, inlineStrings(length + 1)));
				assertEquals(largest.asMap(), reopened.nodeProperties(id).asMap(), "a refused update changed the node");
				reopened.verify(VerifyLevel.FULL);
			}
		} finally {
			if (!abandoned) {
				store.close();
			}
		}
	}

	/** {@code count} bytes of string payload spread over keys of at most 200 bytes each. */
	private static Properties inlineStrings(int count) {
		Properties props = Properties.of();
		int key = 0;
		while (count > 0) {
			int take = Math.min(200, count);
			props.set("k" + key++, "s".repeat(take));
			count -= take;
		}
		return props;
	}

	/**
	 * A segment filled to its last slot, then crashed. Recovery has to rebuild a full segment; a slot freed in it must
	 * then be found by the exhausted-segment scan — the free ring is empty after every reopen — and handed out under
	 * an advanced generation read back from the recovered record, so the id freed before the crash stays stale.
	 */
	@Test
	public void testAFullSegmentSurvivesACrashAndReusesItsHoleAfterIt() throws Exception {
		List<Long> ids = new ArrayList<>();
		GraphStore store = stores.open(smallSegments());
		int slots = ((GraphStoreImpl) store).geometry().slotsPerSegment(Kind.NODE);
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < slots; i++) {
				ids.add(store.createNode("N", Properties.of().set("i", (long) i)));
			}
			txn.commit();
		}
		long last = ids.get(ids.size() - 1);
		assertEquals(0, ElementId.segment(last), "the fill must stop exactly at the end of segment 0");
		assertEquals(slots - 1, ElementId.slot(last));
		long hole = ids.get(100);
		store.deleteNode(hole);
		((GraphStoreImpl) store).abandon();

		GraphStore recovered = stores.open(smallSegments());
		try {
			assertEquals(slots - 1, recovered.stats().nodeCount());
			assertEquals(slots - 1, recovered.nodeProperties(last).getLong("i"));
			assertFalse(recovered.nodeExists(hole));
			long reused = recovered.createNode("R");
			assertEquals(0, ElementId.segment(reused), "a full segment's hole was not reused after the crash");
			assertEquals(ElementId.slot(hole), ElementId.slot(reused));
			assertNotEquals(ElementId.gen(hole), ElementId.gen(reused), "the generation did not survive the crash");
			assertThrows(StaleIdException.class, () -> recovered.nodeLabel(hole));
			long next = recovered.createNode("N");
			assertEquals(1, ElementId.segment(next), "with the hole filled the next node must open segment 1");
			assertEquals(0, ElementId.slot(next));
			recovered.verify(VerifyLevel.FULL);
		} finally {
			((GraphStoreImpl) recovered).abandon();
		}
		try (GraphStore reopened = stores.open(smallSegments())) {
			assertEquals(slots + 1, reopened.stats().nodeCount());
			assertTrue(reopened.nodesByLabel("R").size() == 1);
			reopened.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A transaction that crosses the segment boundary and is rolled back, alone in the store: everything it created
	 * is gone, including from both indexes, and the store verifies. The second segment's file stays — it was made
	 * durable outside the transaction, as a crash would also leave it — so the next node is allocated there.
	 */
	@Test
	public void testARolledBackTransactionAcrossTheSegmentBoundary() throws Exception {
		try (GraphStore store = stores.open(smallSegments())) {
			int slots = ((GraphStoreImpl) store).geometry().slotsPerSegment(Kind.NODE);
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < slots - 5; i++) {
					store.createNode("N");
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 20; i++) {
					store.createNode("Gone", Properties.of().set("i", (long) i));
				}
				txn.rollback();
			}
			assertEquals(slots - 5, store.stats().nodeCount());
			assertTrue(store.nodesByLabel("Gone").isEmpty());
			assertTrue(store.nodesByProperty("i", 3L).isEmpty());
			store.verify(VerifyLevel.FULL);
			long next = store.createNode("N", Properties.of().set("i", 3L));
			assertTrue(store.nodeExists(next));
			assertEquals(List.of(next), store.nodesByProperty("i", 3L));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Generations are durable: a slot reused before a crash keeps its advanced generation after it. */
	@Test
	public void testIdReuseSurvivesACrash() throws Exception {
		GraphStore store = stores.open();
		long first = store.createNode("A", Properties.of().set("x", 1L));
		store.createNode("Keep");
		store.deleteNode(first);
		long reused = store.createNode("B", Properties.of().set("x", 2L));
		assertEquals(ElementId.slot(first), ElementId.slot(reused), "the freed slot was not reused");
		assertNotEquals(ElementId.gen(first), ElementId.gen(reused), "the generation did not advance");
		((GraphStoreImpl) store).abandon();

		try (GraphStore reopened = stores.open()) {
			assertFalse(reopened.nodeExists(first));
			assertThrows(StaleIdException.class, () -> reopened.nodeLabel(first));
			assertEquals("B", reopened.nodeLabel(reused));
			assertTrue(reopened.nodesByProperty("x", 1L).isEmpty());
			assertEquals(List.of(reused), reopened.nodesByProperty("x", 2L));
			reopened.verify(VerifyLevel.FULL);
		}
	}

	/** Empty is a value, not an absence: an empty string, list or byte array is stored, indexed and found. */
	@Test
	public void testEmptyValuesAreValues() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("E", Properties.of().set("s", "").set("l", List.of()).set("b", new byte[0]));
			long b = store.createNode("E", Properties.of().set("s", "x"));
			assertEquals("", store.nodeProperties(a).getString("s"));
			assertEquals(List.of(), store.nodeProperties(a).get("l"));
			assertArrayEquals(new byte[0], store.nodeProperties(a).getBytes("b"));
			assertEquals(List.of(a), store.nodesByProperty("s", ""));
			assertEquals(List.of(a), store.nodesByProperty("l", List.of()));
			assertEquals(List.of(a), store.nodesByProperty("b", new byte[0]));
			store.createIndex("E", "s");
			assertEquals(List.of(a), store.nodesByLabelAndProperty("E", "s", ""));
			assertEquals(List.of(b), store.nodesByLabelAndProperty("E", "s", "x"));
			store.compact(CompactionOptions.always());
			store.verify(VerifyLevel.FULL);
			assertEquals(1, store.nodesByProperty("s", "").size());
		}
	}

	/**
	 * An explicit null is stored and read back as null, and is not an index entry: {@code verify(FULL)} used to report
	 * the entry the property tree kept for it as dangling on a healthy store.
	 */
	@Test
	public void testAnExplicitNullIsStoredButNotIndexed() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("N", Properties.of().setNull("v").set("w", 1L));
			assertTrue(store.nodeProperties(id).isNull("v"));
			store.verify(VerifyLevel.FULL);
			assertTrue(store.nodesByProperty("v", null).isEmpty(), "a null matches nothing");
			store.setNodeProperties(id, Properties.of().set("v", 5L));
			assertEquals(List.of(id), store.nodesByProperty("v", 5L));
			store.setNodeProperties(id, Properties.of().setNull("v"));
			assertTrue(store.nodesByProperty("v", 5L).isEmpty());
			store.verify(VerifyLevel.FULL);
			store.deleteNode(id);
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A string of several megabytes is a chain of hundreds of page-sized extents. Shrunk, regrown, compacted and
	 * crashed, it must come back byte for byte with no extent left behind.
	 */
	@Test
	public void testAMultiMegabyteStringThroughCompactionAndACrash() throws Exception {
		String big = "abcdefghij".repeat(300_000);
		long keep;
		GraphStore store = stores.open();
		long id = store.createNode("B", Properties.of().set("text", big));
		keep = store.createNode("B", Properties.of().set("text", "small"));
		store.setNodeProperties(id, Properties.of().set("text", "short now"));
		store.setNodeProperties(id, Properties.of().set("text", big + "!"));
		store.deleteNode(keep);
		store.compact(CompactionOptions.always());
		store.verify(VerifyLevel.FULL);
		List<Long> found = store.nodesByLabel("B");
		assertEquals(1, found.size());
		long moved = found.get(0);
		((GraphStoreImpl) store).abandon();
		try (GraphStore reopened = stores.open()) {
			assertEquals(big + "!", reopened.nodeProperties(moved).getString("text"));
			assertEquals(List.of(moved), reopened.nodesByProperty("text", big + "!"));
			reopened.verify(VerifyLevel.FULL);
			reopened.deleteNode(moved);
			reopened.verify(VerifyLevel.FULL);
			assertEquals(0, reopened.stats().blobExtentCount(), "the extents outlived their owner");
		}
	}

	/**
	 * A page punched while the log still holds records for it, then a crash: recovery replays those records onto a
	 * page of zeros. Nothing may come back to life, the deleted ids must stay stale, and the store must verify.
	 */
	@Test
	public void testAPunchedPageSurvivesACrashThatReplaysTheLog() throws Exception {
		assumeTrue(NativeLib.isAvailable(), "without the native layer nothing is punched and there is nothing to test");
		List<Long> before = new ArrayList<>();
		GraphStore store = stores.open();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 128; i++) {
				before.add(store.createNode("N", Properties.of().set("i", (long) i).set("s", "x".repeat(40))));
			}
			txn.commit();
		}
		store.checkpoint();
		// Two commits the data files have not seen yet, both touching the pages about to be punched.
		try (Transaction txn = store.begin()) {
			for (long id : before) {
				store.setNodeProperties(id, Properties.of().set("i", -1L).set("s", "y".repeat(60)));
			}
			txn.commit();
		}
		try (Transaction txn = store.begin()) {
			for (long id : before) {
				store.deleteNode(id);
			}
			txn.commit();
		}
		long keep = store.createNode("Keep", Properties.of().set("i", 7L));
		assertTrue(store.reclaim() > 0, "the emptied pages should have been punched");
		((GraphStoreImpl) store).abandon();

		try (GraphStore reopened = stores.open()) {
			reopened.verify(VerifyLevel.FULL);
			assertEquals(1, reopened.stats().nodeCount());
			assertEquals(List.of(keep), reopened.nodesByLabel("Keep"));
			assertTrue(reopened.nodesByLabel("N").isEmpty());
			assertTrue(reopened.nodesByProperty("i", -1L).isEmpty());
			java.util.Set<Long> after = new java.util.HashSet<>();
			try (Transaction txn = reopened.begin()) {
				for (int i = 0; i < 300; i++) {
					after.add(reopened.createNode("M", Properties.of().set("i", (long) i)));
				}
				txn.commit();
			}
			for (long stale : before) {
				assertFalse(after.contains(stale), "the deleted id " + ElementId.toString(stale)
					+ " was handed out again after a punch and a replay");
				assertFalse(reopened.nodeExists(stale));
			}
			reopened.verify(VerifyLevel.FULL);
		}
	}

	/** Thousands of keys and labels, interned across a crash: the dictionaries are appended outside transactions. */
	@Test
	public void testDictionaryGrowthAcrossACrash() throws Exception {
		GraphStore store = stores.open();
		List<Long> ids = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 1500; i++) {
				ids.add(store.createNode(List.of("L" + i, "L" + (i + 1)), Properties.of().set("key" + i, (long) i)));
			}
			txn.commit();
		}
		// A label interned by a write that is then rolled back stays in the dictionary; it must not break anything.
		try (Transaction txn = store.begin()) {
			store.createNode("RolledBack", Properties.of().set("rolledBackKey", 1L));
			txn.rollback();
		}
		((GraphStoreImpl) store).abandon();
		try (GraphStore reopened = stores.open()) {
			for (int i = 0; i < 1500; i += 97) {
				long id = ids.get(i);
				assertEquals(List.of("L" + i, "L" + (i + 1)), reopened.nodeLabels(id));
				assertEquals(i, reopened.nodeProperties(id).getLong("key" + i));
				assertEquals(List.of(id), reopened.nodesByProperty("key" + i, (long) i));
			}
			assertEquals(2, reopened.nodesByLabel("L700").size());
			assertTrue(reopened.nodesByLabel("RolledBack").isEmpty());
			long late = reopened.createNode("L1499", Properties.of().set("key1499", -1L));
			assertEquals(3, reopened.nodesByLabel("L1499").size());
			assertEquals(List.of(late), reopened.nodesByProperty("key1499", -1L));
			reopened.verify(VerifyLevel.FULL);
		}
	}
}
