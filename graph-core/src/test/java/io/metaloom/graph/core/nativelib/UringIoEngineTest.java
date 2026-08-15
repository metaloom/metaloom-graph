package io.metaloom.graph.core.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.io.DirectIoEngine;
import io.metaloom.graph.core.io.IoEngine;
import io.metaloom.graph.core.io.UringIoEngine;

/**
 * io_uring — capability N1, and the one the whole native argument rests on.
 *
 * <p>
 * The claim in spec/NATIVE_FFM.md section 1 is that the JVM cannot express "fetch these eight pages concurrently",
 * and that this is "the single largest performance lever in the design". Both halves are testable: the first by
 * correctness, the second by measurement — and the measurement is allowed to come out against the claim, as the N4
 * one did.
 * </p>
 */
@Tag("unit")
public class UringIoEngineTest {

	@TempDir
	Path dir;

	private static final int BLOCK = 4096;
	private static final int BLOCKS = 4096;

	private Path fixture() throws Exception {
		Path path = dir.resolve("uring-fixture.bin");
		if (!Files.exists(path)) {
			byte[] data = new byte[BLOCK * BLOCKS];
			new Random(20260814L).nextBytes(data);
			Files.write(path, data);
		}
		return path;
	}

	@Test
	public void testTheRingReadsWhatTheOtherEnginesWrote() throws Exception {
		assumeTrue(UringIoEngine.isSupported(), "io_uring is unavailable: " + NativeCapabilities.status());
		Path path = fixture();
		byte[] expected = Files.readAllBytes(path);

		try (IoEngine engine = new UringIoEngine(32); Arena arena = Arena.ofConfined();
			IoEngine.FileHandle handle = engine.open(path, false)) {

			int count = 16;
			MemorySegment[] buffers = new MemorySegment[count];
			long[] lengths = new long[count];
			long[] offsets = new long[count];
			for (int i = 0; i < count; i++) {
				buffers[i] = handle.buffer(arena, BLOCK);
				lengths[i] = BLOCK;
				// Scattered, not sequential: a batch that reads consecutive blocks is a readahead test.
				offsets[i] = (long) ((i * 977) % BLOCKS) * BLOCK;
			}

			long[] read = engine.readBatch(handle, buffers, lengths, offsets);

			for (int i = 0; i < count; i++) {
				assertEquals(BLOCK, read[i], "request " + i + " must be fully served");
				byte[] actual = buffers[i].asSlice(0, BLOCK).toArray(ValueLayout.JAVA_BYTE);
				byte[] slice = new byte[BLOCK];
				System.arraycopy(expected, (int) offsets[i], slice, 0, BLOCK);
				assertArrayEquals(slice, actual,
					"request " + i + " at offset " + offsets[i] + " returned the wrong bytes");
			}
		}
	}

	/** More requests than the ring is deep must be served in waves, not refused. */
	@Test
	public void testABatchLargerThanTheRingIsServedInWaves() throws Exception {
		assumeTrue(UringIoEngine.isSupported(), "io_uring is unavailable");
		Path path = fixture();

		try (IoEngine engine = new UringIoEngine(8); Arena arena = Arena.ofConfined();
			IoEngine.FileHandle handle = engine.open(path, false)) {

			assertEquals(8, engine.queueDepth());
			int count = 40;
			MemorySegment[] buffers = new MemorySegment[count];
			long[] lengths = new long[count];
			long[] offsets = new long[count];
			for (int i = 0; i < count; i++) {
				buffers[i] = handle.buffer(arena, BLOCK);
				lengths[i] = BLOCK;
				offsets[i] = (long) i * BLOCK;
			}

			long[] read = engine.readBatch(handle, buffers, lengths, offsets);
			for (int i = 0; i < count; i++) {
				assertEquals(BLOCK, read[i], "request " + i + " must be served despite exceeding the ring depth");
			}
		}
	}

	/**
	 * The measurement the capability is claimed on: scattered reads, issued one at a time against issued together.
	 *
	 * <p>
	 * Prints rather than asserts a speedup. What the ring wins depends entirely on the device and on whether the pages
	 * are resident, and on a warm page cache there is nothing to overlap — so a gate here would be measuring the
	 * machine, not the engine.
	 * </p>
	 */
	@Test
	public void testHowMuchBatchingBuys() throws Exception {
		assumeTrue(UringIoEngine.isSupported(), "io_uring is unavailable");
		Path path = fixture();
		int batch = 64;
		int rounds = 200;

		double sequential;
		double batched;

		try (IoEngine engine = new DirectIoEngine(); Arena arena = Arena.ofConfined();
			IoEngine.FileHandle handle = engine.open(path, false)) {
			sequential = time(engine, handle, arena, batch, rounds);
		}
		try (IoEngine engine = new UringIoEngine(batch); Arena arena = Arena.ofConfined();
			IoEngine.FileHandle handle = engine.open(path, false)) {
			batched = time(engine, handle, arena, batch, rounds);
		}

		System.out.printf("%nscattered %d x 4 KiB reads: pread loop %,.0f us, io_uring batch %,.0f us = %.2fx%n",
			batch, sequential, batched, sequential / batched);
		System.out.println("  (a warm page cache has nothing to overlap; the ring earns its keep when the reads "
			+ "actually reach the device)");
		assertTrue(sequential > 0 && batched > 0);
	}

	private static double time(IoEngine engine, IoEngine.FileHandle handle, Arena arena, int batch, int rounds)
		throws Exception {
		MemorySegment[] buffers = new MemorySegment[batch];
		long[] lengths = new long[batch];
		for (int i = 0; i < batch; i++) {
			buffers[i] = handle.buffer(arena, BLOCK);
			lengths[i] = BLOCK;
		}
		Random random = new Random(4242L);
		long[] offsets = new long[batch];

		// Warm the JIT and the descriptor.
		for (int i = 0; i < 20; i++) {
			fill(offsets, random);
			engine.readBatch(handle, buffers, lengths, offsets);
		}

		long start = System.nanoTime();
		for (int round = 0; round < rounds; round++) {
			fill(offsets, random);
			engine.readBatch(handle, buffers, lengths, offsets);
		}
		return (System.nanoTime() - start) / 1000.0 / rounds;
	}

	private static void fill(long[] offsets, Random random) {
		for (int i = 0; i < offsets.length; i++) {
			offsets[i] = (long) random.nextInt(BLOCKS) * BLOCK;
		}
	}
}
