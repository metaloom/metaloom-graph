package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.nativelib.NativeLib;

/**
 * The native layer and the space reclamation it enables.
 * <p>
 * Hole punching is the id-safe half of reclamation: the pages stay addressable and read back as zeros, so no
 * reference anywhere in the store changes. Compaction, which moves records and therefore breaks ids, is the expensive
 * half and is not implemented.
 *
 * @see <a href="../../../../../../../../spec/NATIVE_FFM.md">spec/NATIVE_FFM.md</a>
 */
@Tag("unit")
public class NativeReclaimTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testNativeLayerReportsItsCapabilities() {
		// On Linux this must be available; elsewhere the fallback is a no-op and the store still works.
		System.out.println("native layer: " + NativeLib.describe());
		assertTrue(NativeLib.describe() != null);
	}

	@Test
	public void testPunchHoleActuallyReturnsBlocks() throws Exception {
		assumeTrue(NativeLib.isAvailable(), "native fallocate is unavailable here");
		Path file = Files.createTempFile("punch-", ".bin");
		try {
			byte[] data = new byte[1 << 20];
			Files.write(file, data);
			long before = allocatedBlocks(file);
			assertTrue(before > 0, "the file should hold real blocks first");

			assertTrue(NativeLib.punchHole(file, 0, data.length));

			long after = allocatedBlocks(file);
			assertTrue(after < before, "punching must return blocks: " + before + " -> " + after);
			assertEquals(data.length, Files.size(file), "the file keeps its length");
			assertArrayEqualsZero(Files.readAllBytes(file));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	public void testReclaimAfterMassDeletion() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				// Enough nodes to fill many pages: 128 nodes per 8 KiB page.
				for (int i = 0; i < 4000; i++) {
					ids.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			store.checkpoint();

			try (Transaction txn = store.begin()) {
				for (long id : ids) {
					store.deleteNode(id);
				}
				txn.commit();
			}
			assertEquals(0, store.stats().nodeCount());

			int punched = store.reclaim();
			if (NativeLib.isAvailable()) {
				assertTrue(punched > 0, "emptied pages must be reclaimable, punched " + punched);
			}
			// Reclamation must not break anything: every surviving structure still verifies.
			store.verify();

			// And the store must still be usable afterwards.
			long fresh = store.createNode("Fresh", Properties.of().set("k", "v"));
			assertTrue(store.nodeExists(fresh));
			assertEquals("v", store.nodeProperties(fresh).getString("k"));
			store.verify();
		}
	}

	/**
	 * A page punched once is not punched again. Its checksum is then the CRC of a zero page, not zero, so a skip that
	 * only recognised zero sent every reclaim back over every earlier hole — a file open, an fallocate and an 8 KiB CRC
	 * per page, and a punched-page count that grew with every call.
	 */
	@Test
	public void testASecondReclaimPunchesNothing() throws Exception {
		assumeTrue(NativeLib.isAvailable(), "without the native layer nothing is punched and there is nothing to test");
		try (GraphStore store = stores.open()) {
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 4000; i++) {
					ids.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (long id : ids) {
					store.deleteNode(id);
				}
				txn.commit();
			}
			assertTrue(store.reclaim() > 0, "the first reclaim has emptied pages to give back");
			assertEquals(0, store.reclaim(), "a second reclaim with nothing newly emptied must punch nothing");
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
		}
	}

	/**
	 * A page punched with its checksum update lost — a crash between the two — is an empty page, not a corrupt one.
	 * Simulated by punching directly through the native layer, which is what the crash leaves on disk.
	 */
	@Test
	public void testAPunchedPageWithAStaleChecksumStillVerifies() throws Exception {
		assumeTrue(NativeLib.isAvailable(), "without the native layer nothing is punched and there is nothing to test");
		try (GraphStore store = stores.open()) {
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 1000; i++) {
					ids.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				// The first page's records only: 128 nodes per 8 KiB page.
				for (int i = 0; i < 128; i++) {
					store.deleteNode(ids.get(i));
				}
				txn.commit();
			}
			store.checkpoint();
			io.metaloom.graph.core.segment.SegmentFile segment = ((io.metaloom.graph.core.store.GraphStoreImpl) store)
				.segments(io.metaloom.graph.core.api.Kind.NODE).segments().get(0);
			io.metaloom.graph.core.segment.Geometry geometry = ((io.metaloom.graph.core.store.GraphStoreImpl) store)
				.geometry();
			assertTrue(NativeLib.punchHole(segment.path(), geometry.dataStart(io.metaloom.graph.core.api.Kind.NODE),
				geometry.pageSize()), "the punch itself must succeed");
			store.verify(io.metaloom.graph.core.api.VerifyLevel.FULL);
			assertEquals(1000 - 128, store.stats().nodeCount());
		}
	}

	/**
	 * A punched page must not restart its generation counters.
	 *
	 * <p>
	 * This is the one thing hole punching can get wrong that no other assertion here would notice. The generation of
	 * a slot lives <em>inside</em> its record, on the data page, and punching returns that page's blocks to the
	 * filesystem — so it reads back as zeros, counters included. Left alone, the slot restarts at generation 1 and the
	 * next allocation hands out an id byte-for-byte identical to one deleted earlier. Nothing throws. The stale id
	 * simply resolves, to somebody else's record.
	 * </p>
	 *
	 * <p>
	 * That is strictly worse than an id being broken, and it is the failure {@code StaleIdException} exists to
	 * prevent, so the counters are saved into the page metadata array — a separate region, never punched — before the
	 * punch destroys them.
	 * </p>
	 *
	 * @see <a href="../../../../../../../../spec/ID_ENCODING.md">spec/ID_ENCODING.md</a> section 4
	 */
	@Test
	public void testAPunchedPageDoesNotHandOutADeletedIdAgain() throws Exception {
		assumeTrue(NativeLib.isAvailable(), "without the native layer nothing is punched and there is nothing to test");
		try (GraphStore store = stores.open()) {
			// Exactly one page: 8 KiB / 64 byte node record. A partly live page cannot be punched, so filling it and
			// emptying it completely is what puts the test on the path it is about.
			int perPage = 128;
			List<Long> before = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < perPage; i++) {
					before.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (long id : before) {
					store.deleteNode(id);
				}
				txn.commit();
			}
			assertTrue(store.reclaim() > 0, "the emptied page should have been punched");

			// Refill the same slots and collect what the allocator hands out.
			Set<Long> after = new HashSet<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < perPage; i++) {
					after.add(store.createNode("M", Properties.of().set("i", i)));
				}
				txn.commit();
			}

			for (long stale : before) {
				assertFalse(after.contains(stale),
					"the id " + ElementId.toString(stale) + " was deleted and has been handed out again; a caller"
						+ " holding it now reads a different record with no error");
				assertFalse(store.nodeExists(stale),
					"the deleted id " + ElementId.toString(stale) + " resolves again after its page was punched");
			}
			store.verify();
		}
	}

	/**
	 * A floor at the top of the range must not pin the slot there.
	 *
	 * <p>
	 * The generation counter wraps from 4095 to 1, which
	 * <a href="../../../../../../../../spec/ID_ENCODING.md">ID_ENCODING.md §4</a> documents as a one-in-4095
	 * detector rather than a guarantee. A floor applied as {@code max(recordGen, floor)} would turn that into a
	 * certainty: {@code free()} wraps the record to 1, the maximum puts it straight back to 4095, and every
	 * delete-then-recreate on the page returns the identical id forever. So the floor is consulted only when the
	 * counter is <em>gone</em> — zero, which is what a punch leaves — and never against a live one.
	 * </p>
	 */
	@Test
	public void testAFloorAtTheTopOfTheRangeStillWraps() throws Exception {
		assumeTrue(NativeLib.isAvailable(), "without the native layer nothing is punched and there is nothing to test");
		try (GraphStore store = stores.open()) {
			// The floor has to land on exactly MAX_GEN, or the pinning does not bite and this proves nothing.
			// free() writes (gen % MAX_GEN) + 1, so the record reaches 4095 by deleting an id whose own generation
			// is 4094. One node, so the page stays punchable; one transaction, so this is cheap cycles and not
			// thousands of commits.
			boolean reachedTheTop = false;
			// One transaction per cycle, not one transaction for all of them. A slot only becomes reusable when the
			// delete that freed it commits: while it is uncommitted the advanced generation lives in the transaction's
			// overlay and the mapping still holds the old one, and handing the slot back before those agree is exactly
			// the reuse that broke the conserved-total invariant under concurrent writers. So the turnover this test
			// needs is a cross-transaction property now, and it is written as one.
			for (int i = 0; i < 4 * ElementId.MAX_GEN && !reachedTheTop; i++) {
				try (Transaction txn = store.begin()) {
					long id = store.createNode("N", Properties.of().set("i", i));
					reachedTheTop = ElementId.gen(id) == ElementId.MAX_GEN - 1;
					store.deleteNode(id);
					txn.commit();
				}
			}
			assertTrue(reachedTheTop, "the slot's counter never reached the top of its range");
			assertTrue(store.reclaim() > 0, "the emptied page should have been punched");

			// Two cycles after the punch must produce two different ids. With a maximum they would be identical
			// whenever the floor had reached the top of the range. A transaction per cycle, for the reason above: the
			// slot only comes back once the delete that freed it is committed.
			long first;
			long second;
			try (Transaction txn = store.begin()) {
				first = store.createNode("M", Properties.of().set("i", 1));
				store.deleteNode(first);
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				second = store.createNode("M", Properties.of().set("i", 2));
				txn.commit();
			}
			assertEquals(ElementId.slot(first), ElementId.slot(second), "the test needs both to land on one slot");
			assertNotEquals(first, second,
				"the slot is pinned at generation " + ElementId.gen(first)
					+ "; delete-then-recreate returns the same id every time");
			store.verify();
		}
	}

	/** The floor has to outlive the process, or the first reopen after a punch reintroduces the collision. */
	@Test
	public void testTheGenerationFloorSurvivesAReopen() throws Exception {
		assumeTrue(NativeLib.isAvailable(), "without the native layer nothing is punched and there is nothing to test");
		Path dir = stores.path();
		List<Long> before = new ArrayList<>();
		try (GraphStore store = GraphStore.open(dir, TempStore.options())) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 128; i++) {
					before.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				for (long id : before) {
					store.deleteNode(id);
				}
				txn.commit();
			}
			assertTrue(store.reclaim() > 0);
		}
		try (GraphStore store = GraphStore.open(dir, TempStore.options())) {
			Set<Long> after = new HashSet<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 128; i++) {
					after.add(store.createNode("M", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			for (long stale : before) {
				assertFalse(after.contains(stale),
					"after a reopen the id " + ElementId.toString(stale) + " was handed out a second time");
			}
			store.verify();
		}
	}

	@Test
	public void testReclaimIsSafeWithLiveData() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> keep = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 500; i++) {
					keep.add(store.createNode("Keep", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			store.checkpoint();
			store.reclaim();
			for (int i = 0; i < keep.size(); i++) {
				assertTrue(store.nodeExists(keep.get(i)), "node " + i + " must survive reclamation");
				assertEquals(i, store.nodeProperties(keep.get(i)).getInt("i"));
			}
			store.verify();
		}
	}

	private static void assertArrayEqualsZero(byte[] data) {
		for (byte b : data) {
			assertEquals(0, b, "a punched region must read back as zeros");
		}
	}

	/** Real allocated blocks, via {@code du}-style accounting the JDK does not expose. */
	private static long allocatedBlocks(Path file) throws IOException {
		try (Stream<String> lines = Files.lines(Path.of("/proc/self/stat"))) {
			// Not usable for a file; fall back to stat via the shell-free route below.
			lines.findAny();
		} catch (IOException ignored) {
			// Ignored: the real measurement follows.
		}
		Process process = new ProcessBuilder("stat", "-c", "%b", file.toString())
			.redirectErrorStream(true).start();
		try (var reader = process.inputReader()) {
			String line = reader.readLine();
			return line == null ? -1 : Long.parseLong(line.trim());
		} catch (NumberFormatException e) {
			return -1;
		} finally {
			try {
				process.waitFor();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
