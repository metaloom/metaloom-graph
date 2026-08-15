package io.metaloom.graph.core.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.io.DirectIoEngine;
import io.metaloom.graph.core.io.IoEngine;
import io.metaloom.graph.core.io.MmapIoEngine;
import io.metaloom.graph.core.io.PageFaults;

/**
 * The Phase 6 I/O engines, and the equivalence between them.
 *
 * <p>
 * The interface was deferred from Phase 1 on the grounds that an abstraction with one implementation is a guess. This
 * is what makes it not a guess: the same bytes, written and read back through both engines, must be identical, and
 * the direct one must reject the misalignment that {@code O_DIRECT} genuinely cannot serve.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/NATIVE_FFM.md">spec/NATIVE_FFM.md</a> section 5
 */
@Tag("unit")
public class IoEngineTest {

	@TempDir
	Path dir;

	private static List<IoEngine> engines() {
		return DirectIoEngine.isSupported()
			? List.of(new MmapIoEngine(), new DirectIoEngine())
			: List.of(new MmapIoEngine());
	}

	/** Whatever an engine writes, both engines read back the same. That is the whole contract. */
	@Test
	public void testEveryEngineRoundTripsTheSameBytes() throws Exception {
		byte[] payload = new byte[64 * 1024];
		new Random(20260814L).nextBytes(payload);

		for (IoEngine writer : engines()) {
			Path path = dir.resolve("round-trip-" + writer.name() + ".bin");
			try (writer; Arena arena = Arena.ofConfined()) {
				try (IoEngine.FileHandle handle = writer.open(path, true)) {
					MemorySegment buffer = handle.buffer(arena, payload.length);
					MemorySegment.copy(payload, 0, buffer, ValueLayout.JAVA_BYTE, 0, payload.length);
					assertEquals(payload.length, handle.write(buffer, payload.length, 0));
					handle.force();
				}
			}

			for (IoEngine reader : engines()) {
				try (reader; Arena arena = Arena.ofConfined();
					IoEngine.FileHandle handle = reader.open(path, false)) {
					MemorySegment buffer = handle.buffer(arena, payload.length);
					assertEquals(payload.length, handle.read(buffer, payload.length, 0));
					byte[] read = buffer.asSlice(0, payload.length).toArray(ValueLayout.JAVA_BYTE);
					assertArrayEquals(payload, read,
						reader.name() + " must read back exactly what " + writer.name() + " wrote");
				}
			}
		}
	}

	@Test
	public void testEveryEngineMapsAFile() throws Exception {
		for (IoEngine engine : engines()) {
			Path path = dir.resolve("mapped-" + engine.name() + ".bin");
			try (engine; Arena arena = Arena.ofConfined()) {
				MemorySegment mapped = engine.map(path, 1 << 20, arena);
				assertEquals(1 << 20, mapped.byteSize());
				mapped.set(ValueLayout.JAVA_LONG, 4096, 0x0123456789ABCDEFL);
				assertEquals(0x0123456789ABCDEFL, mapped.get(ValueLayout.JAVA_LONG, 4096));
			}
			assertTrue(Files.size(path) >= 1 << 20);
		}
	}

	/** Direct IO is unforgiving about alignment, and the engine must say so rather than corrupt or silently short-write. */
	@Test
	public void testDirectIoRejectsMisalignedWrites() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "no direct IO on this platform");
		Path path = dir.resolve("aligned.bin");
		try (IoEngine engine = new DirectIoEngine(); Arena arena = Arena.ofConfined();
			IoEngine.FileHandle handle = engine.open(path, true)) {

			MemorySegment buffer = handle.buffer(arena, 8192);
			assertEquals(0, buffer.address() % engine.alignment(),
				"the engine's own buffer must be aligned; a heap array never is");

			// An aligned write is fine.
			handle.write(buffer, 8192, 0);

			// A misaligned length is not. On a filesystem that honours O_DIRECT this is EINVAL; on one that
			// silently downgraded to a buffered descriptor it succeeds, and either is a correct outcome — what
			// would not be is a short write reported as success.
			try {
				long written = handle.write(buffer, 100, 0);
				assertEquals(100, written, "a downgraded descriptor must still write everything it reports");
			} catch (java.io.IOException expected) {
				assertTrue(expected.getMessage().contains("multiples of"),
					"the failure must explain the alignment rule, got: " + expected.getMessage());
			}
		}
	}

	@Test
	public void testEnginesReportTheirCounts() throws Exception {
		Path path = dir.resolve("counted.bin");
		try (IoEngine engine = new MmapIoEngine(); Arena arena = Arena.ofConfined()) {
			IoEngine.IoCounts before = engine.counts();
			try (IoEngine.FileHandle handle = engine.open(path, true)) {
				MemorySegment buffer = handle.buffer(arena, 4096);
				handle.write(buffer, 4096, 0);
				handle.force();
				handle.read(buffer, 4096, 0);
			}
			IoEngine.IoCounts delta = engine.counts().since(before);
			assertEquals(1, delta.writes());
			assertEquals(1, delta.reads());
			assertEquals(1, delta.forces());
		}
	}

	/** The major fault counter has to work for the device-read budgets to mean anything. */
	@Test
	public void testMajorFaultCounterIsReadable() {
		assumeTrue(PageFaults.isAvailable(), "no /proc/self/stat here");
		long faults = PageFaults.major();
		assertTrue(faults >= 0, "major fault count must be readable, got " + faults);
	}

	/**
	 * The Rust crate is negotiated with rather than assumed, and the negotiation reports honestly whether the library
	 * is absent, the wrong ABI, or loaded.
	 */
	@Test
	public void testNativeCapabilityNegotiation() {
		assertNotNull(NativeCapabilities.status());
		if (NativeCapabilities.isLoaded()) {
			assertEquals(NativeCapabilities.SUPPORTED_ABI_VERSION, NativeCapabilities.abiVersion(),
				"a library is only loaded when its ABI matches");
			assertTrue(NativeCapabilities.has(NativeCapabilities.CAP_EDGE_CODEC));
			assertTrue(NativeCapabilities.library().isPresent());
		} else {
			assertTrue(NativeCapabilities.library().isEmpty());
			assertEquals(0, NativeCapabilities.capabilities(),
				"nothing may be claimed when nothing is loaded");
		}
		System.out.println("native layer: " + NativeLib.describe());
	}
}
