package io.metaloom.graph.core.budget;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.io.PageFaults;
import io.metaloom.graph.core.nativelib.NativeLib;

/**
 * Reads that actually reach the device, on a store whose pages have been evicted from the operating system's cache.
 *
 * <p>
 * <b>This is the measurement two performance targets waited three phases for.</b> {@code ReadBudgetTest} counts
 * <em>record</em> touches, which is the right unit for the addressing invariant and says nothing about I/O: a record
 * touch on a resident page costs nothing. Whether it cost a trip to the device is something only the kernel knows, and
 * it counts — a <b>major fault</b> is a fault that had to read from the disk.
 * </p>
 *
 * <p>
 * Two capabilities make it possible, and both arrived with the Phase 6 I/O engine:
 * </p>
 * <ul>
 * <li>{@code posix_fadvise(POSIX_FADV_DONTNEED)} through FFM, which evicts a file from the page cache without the root
 * that {@code /proc/sys/vm/drop_caches} needs;</li>
 * <li>{@link PageFaults}, which reads the major fault counter out of {@code /proc/self/stat}.</li>
 * </ul>
 *
 * <p>
 * The counter is process-wide, so every measurement here is single threaded and brackets one operation. Skips rather
 * than fails where either capability is missing, because a cold-read number measured on a warm cache is worse than no
 * number at all.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
@Tag("unit")
public class DeviceReadBudgetTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static void assumeMeasurable() throws Exception {
		assumeTrue(PageFaults.isAvailable(), "no /proc/self/stat; major faults cannot be counted");
		assumeTrue(NativeLib.isDirectIoAvailable(), "no posix_fadvise; the cache cannot be dropped");
		assumeTrue(evictionActuallyWorks(),
			"posix_fadvise is accepted but does not evict on this filesystem, so nothing here would be a cold read");
	}

	/**
	 * Proves that eviction does something before trusting a number that depends on it.
	 * <p>
	 * <b>Without this the whole class is a lie.</b> On an overlay filesystem {@code posix_fadvise(DONTNEED)} returns
	 * success and leaves every page resident: measured here, touching 16,384 pages of a freshly evicted 64 MiB file
	 * produced <em>one</em> major fault. The tests then reported 0.15 device reads per cold node read, which is not a
	 * good result — it is a warm cache wearing a cold label.
	 * <p>
	 * So: write a scratch file larger than any readahead window, evict it, touch every page, and require that a real
	 * fraction of those touches faulted. Anything less and the measurement is not available on this machine.
	 */
	private static boolean evictionActuallyWorks() throws Exception {
		int pages = 4096;
		int size = pages * 4096;
		Path probe = Files.createTempFile("evict-probe-", ".bin");
		try {
			byte[] data = new byte[size];
			new Random(1).nextBytes(data);
			Files.write(probe, data);
			if (!NativeLib.evictFromPageCache(probe)) {
				return false;
			}
			try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(probe,
				java.nio.file.StandardOpenOption.READ);
				java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
				java.lang.foreign.MemorySegment mapped = channel.map(
					java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, size, arena);
				long before = PageFaults.major();
				long sink = 0;
				for (long offset = 0; offset < size; offset += 4096) {
					sink += mapped.get(java.lang.foreign.ValueLayout.JAVA_BYTE, offset);
				}
				long faults = PageFaults.major() - before;
				// Readahead legitimately serves most of a sequential scan, so the bar is deliberately low: one fault
				// per 64 pages still proves the pages were gone. A no-op fadvise produces one fault in total.
				return sink != Long.MIN_VALUE && faults >= pages / 64;
			}
		} finally {
			Files.deleteIfExists(probe);
		}
	}

	/**
	 * A node read by id must cost <b>one</b> device read on a cold store: the page its record is on, and nothing else.
	 * That is the addressing invariant stated in the only unit that matters to a disk.
	 */
	@Test
	public void testColdNodeReadCostsOneDeviceRead() throws Exception {
		assumeMeasurable();
		int count = 50_000;
		long[] ids = new long[count];

		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					ids[i] = store.createNode("Person", Properties.of().set("i", i));
				}
				txn.commit();
			}
			store.checkpoint();
		}

		// Evict *before* opening. posix_fadvise cannot drop a page that an active mapping still references, so
		// evicting a store that is open barely evicts anything — which is how the first version of this test measured
		// 0.15 device reads per read and looked like very good news.
		assumeTrue(evictAll(stores.path()) > 0, "nothing could be evicted; this filesystem ignores fadvise");
		try (GraphStore store = stores.open()) {
			// Uniformly random, not strided. A stride of one page looks sequential to the kernel, which then reads
			// ahead and serves fifteen probes out of sixteen for free — a real effect, and the wrong one to measure
			// when the question is what one isolated read costs.
			List<Integer> probes = randomProbes(count, 200, 20260814L);

			long before = PageFaults.major();
			for (int index : probes) {
				store.nodeLabel(ids[index]);
			}
			long faults = PageFaults.major() - before;

			double perRead = (double) faults / probes.size();
			System.out.printf("cold nodeLabel: %d probes, %d major faults = %.2f device reads per read%n",
				probes.size(), faults, perRead);

			// One per read is the target. The bound is generous upward only because the kernel may have read ahead
			// and served some probes for free, which biases this number down rather than up.
			assertTrue(perRead <= 1.05,
				"a cold node read must cost at most one device read, got " + perRead);
		}
	}

	/**
	 * The Phase 7 exit criterion: an indexed lookup costs at most four device reads.
	 * <p>
	 * The B+tree is shallow by construction — fan-out 339, so a hundred million entries fit in three levels — and the
	 * fourth read is the record the lookup resolves to. It was written as an exit criterion in the plan and never
	 * measured, because nothing could count a device read until now.
	 */
	@Test
	public void testColdIndexedLookupStaysWithinFourDeviceReads() throws Exception {
		assumeMeasurable();
		int count = 100_000;

		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					store.createNode("Person", Properties.of().set("i", i).set("name", "person-" + i));
				}
				txn.commit();
			}
			store.checkpoint();
		}

		assumeTrue(evictAll(stores.path()) > 0, "nothing could be evicted; this filesystem ignores fadvise");
		try (GraphStore store = stores.open()) {
			Random random = new Random(4242L);
			int probes = 100;
			long before = PageFaults.major();
			int found = 0;
			for (int i = 0; i < probes; i++) {
				found += store.nodesByProperty("i", random.nextInt(count)).size();
			}
			long faults = PageFaults.major() - before;

			double perLookup = (double) faults / probes;
			System.out.printf("cold nodesByProperty: %d lookups, %d major faults = %.2f device reads per lookup "
				+ "(%d matched)%n", probes, faults, perLookup, found);

			assertTrue(found == probes, "every probe should match exactly one node, matched " + found);
			assertTrue(perLookup <= 4.0,
				"an indexed lookup must cost at most four device reads, got " + perLookup);
		}
	}

	/**
	 * Enumerating the neighbours of a low-degree node must cost <b>no</b> adjacency read at all: its edges are inline
	 * in the node record the traversal has already paid for. The record-level budget asserts this too; here it is in
	 * device reads, which is what the claim in the README actually means.
	 */
	@Test
	public void testColdNeighboursOfALowDegreeNodeCostsOneDeviceRead() throws Exception {
		assumeMeasurable();
		int count = 20_000;
		long[] ids = new long[count];

		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					ids[i] = store.createNode("N");
				}
				for (int i = 1; i < count; i++) {
					store.createEdge(ids[i - 1], "NEXT", ids[i]);
				}
				txn.commit();
			}
			store.checkpoint();
		}

		assumeTrue(evictAll(stores.path()) > 0, "nothing could be evicted");
		try (GraphStore store = stores.open()) {
			List<Integer> probes = randomProbes(count - 2, 100, 77L);

			long before = PageFaults.major();
			int seen = 0;
			for (int index : probes) {
				seen += store.neighbours(ids[index], io.metaloom.graph.core.api.Direction.BOTH).size();
			}
			long faults = PageFaults.major() - before;

			double perProbe = (double) faults / probes.size();
			System.out.printf("cold neighbours (degree 2): %d probes, %d major faults = %.2f device reads, "
				+ "%d neighbours%n", probes.size(), faults, perProbe, seen);

			assertTrue(seen == probes.size() * 2, "each probe has two neighbours, saw " + seen);
			assertTrue(perProbe <= 1.05,
				"an inline-edge traversal must cost only the node's own page, got " + perProbe);
		}
	}

	/** Distinct indices drawn uniformly, so no two probes are predictably adjacent. */
	private static List<Integer> randomProbes(int bound, int wanted, long seed) {
		Random random = new Random(seed);
		java.util.LinkedHashSet<Integer> picked = new java.util.LinkedHashSet<>();
		while (picked.size() < wanted) {
			picked.add(1 + random.nextInt(bound));
		}
		return new ArrayList<>(picked);
	}

	/** Evicts every file of the store from the page cache. Returns how many were accepted. */
	private static long evictAll(Path root) throws Exception {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).filter(NativeLib::evictFromPageCache).count();
		}
	}
}
