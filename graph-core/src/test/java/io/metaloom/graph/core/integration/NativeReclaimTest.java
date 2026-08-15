package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
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
