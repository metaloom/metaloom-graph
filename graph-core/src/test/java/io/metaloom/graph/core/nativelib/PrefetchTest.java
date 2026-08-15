package io.metaloom.graph.core.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.io.IoEngine;
import io.metaloom.graph.core.io.MmapIoEngine;

/**
 * {@code madvise(MADV_WILLNEED)} — capability N3, and what prefetch means on a memory mapped data path.
 *
 * <p>
 * There is no assertion here that prefetching made anything faster, and there deliberately is not. The kernel is free
 * to ignore the advice; on a warm page cache there is nothing to fetch; and on the filesystem this repository is
 * developed against, cache eviction does not work at all — which {@code DeviceReadBudgetTest} proves before it trusts
 * any cold number. What is testable is that the call is correct: it accepts what it should, refuses what it should,
 * and never changes what a read returns.
 * </p>
 */
@Tag("unit")
public class PrefetchTest {

	@TempDir
	Path dir;

	private static final int SIZE = 1 << 20;

	@Test
	public void testPrefetchDoesNotChangeWhatIsRead() throws Exception {
		byte[] data = new byte[SIZE];
		new Random(20260814L).nextBytes(data);
		Path path = dir.resolve("prefetch.bin");
		Files.write(path, data);

		try (IoEngine engine = new MmapIoEngine(); Arena arena = Arena.ofConfined()) {
			MemorySegment mapping = engine.map(path, SIZE, arena);

			// Advice over the whole mapping, then over a range that starts and ends mid-page.
			engine.prefetch(mapping, 0, SIZE);
			engine.prefetch(mapping, 1234, 5678);

			byte[] read = mapping.asSlice(0, SIZE).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
			org.junit.jupiter.api.Assertions.assertArrayEquals(data, read,
				"advice must not disturb a single byte of the mapping");
		}
	}

	/** A range outside the mapping is refused rather than passed to the kernel. */
	@Test
	public void testOutOfRangeIsRefused() throws Exception {
		Path path = dir.resolve("bounds.bin");
		Files.write(path, new byte[SIZE]);
		try (IoEngine engine = new MmapIoEngine(); Arena arena = Arena.ofConfined()) {
			MemorySegment mapping = engine.map(path, SIZE, arena);
			assertFalse(engine.prefetch(mapping, 0, SIZE + 1), "a length past the end must be refused");
			assertFalse(engine.prefetch(mapping, -1, 16), "a negative offset must be refused");
			assertFalse(engine.prefetch(mapping, 0, 0), "an empty range has nothing to advise");
			assertFalse(engine.prefetch(mapping, SIZE - 16, 32), "a range straddling the end must be refused");
		}
	}

	/**
	 * The advice reaches the kernel and is accepted.
	 * <p>
	 * Skipped rather than failed where the binding is unavailable, because every capability here has a pure-Java
	 * fallback and a store must work without it.
	 */
	@Test
	public void testTheKernelAcceptsTheAdvice() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(NativeLib.isMadviseAvailable(),
			"madvise is unavailable: " + NativeLib.describe());
		Path path = dir.resolve("accepted.bin");
		Files.write(path, new byte[SIZE]);
		try (IoEngine engine = new MmapIoEngine(); Arena arena = Arena.ofConfined()) {
			MemorySegment mapping = engine.map(path, SIZE, arena);
			assertTrue(engine.prefetch(mapping, 0, SIZE), "madvise(WILLNEED) over a whole mapping must be accepted");
			// Unaligned on both ends: the engine rounds out to page boundaries, so this must be accepted too.
			assertTrue(engine.prefetch(mapping, 17, 4099), "an unaligned range must be rounded, not refused");
		}
	}

	/** Advice is a hint, so a caller must not be able to tell whether it was taken. */
	@Test
	public void testTheSameBytesComeBackWithAndWithoutAdvice() throws Exception {
		byte[] data = new byte[SIZE];
		new Random(99L).nextBytes(data);
		Path path = dir.resolve("identical.bin");
		Files.write(path, data);

		long withAdvice;
		long without;
		try (IoEngine engine = new MmapIoEngine(); Arena arena = Arena.ofConfined()) {
			MemorySegment mapping = engine.map(path, SIZE, arena);
			engine.prefetch(mapping, 0, SIZE);
			withAdvice = checksum(mapping);
		}
		try (IoEngine engine = new MmapIoEngine(); Arena arena = Arena.ofConfined()) {
			without = checksum(engine.map(path, SIZE, arena));
		}
		assertEquals(without, withAdvice);
	}

	private static long checksum(MemorySegment mapping) {
		long sum = 0;
		for (long i = 0; i + 8 <= mapping.byteSize(); i += 8) {
			sum = sum * 31 + mapping.get(io.metaloom.graph.core.util.Layouts.LONG, i);
		}
		return sum;
	}
}
