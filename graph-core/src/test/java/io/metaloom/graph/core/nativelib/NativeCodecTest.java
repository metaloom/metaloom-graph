package io.metaloom.graph.core.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.store.adj.EdgeCodec;
import io.metaloom.graph.core.store.adj.PackedEdge;
import io.metaloom.graph.core.util.Crc;

/**
 * The differential test the Rust crate has been owed since Phase 6, plus the measurement that decides whether any of
 * it is worth shipping.
 *
 * <p>
 * The crate is not on the engine's path: nothing in {@code GraphStoreImpl} calls it, and {@link NativeLib} binds only
 * libc. So this test loads {@code libmetaloom_graph_native.so} directly through the FFM linker and asks the two
 * questions that decide the crate's future:
 * </p>
 * <ol>
 * <li><b>Does it agree with Java, byte for byte?</b> The Java implementations are the reference — a native codec that
 * is merely <em>similar</em> is a corruption engine, because the same block would decode differently depending on
 * which path read it.</li>
 * <li><b>Is it faster?</b> Section N4 of the native spec says the SIMD hot loops must be justified by measurement
 * before they ship. This is that measurement, and it is allowed to come out against the crate.</li>
 * </ol>
 *
 * <p>
 * Skips when the shared object has not been built, which is the normal state of a machine without {@code cargo}.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/NATIVE_FFM.md">spec/NATIVE_FFM.md</a> section 1, capability N4
 */
@Tag("unit")
public class NativeCodecTest {

	private static final Path LIBRARY = Path.of("native-rust-lib/target/release/libmetaloom_graph_native.so");

	private static Arena arena;
	private static MethodHandle version;
	private static MethodHandle capabilities;
	private static MethodHandle crc32c;
	private static MethodHandle encode;
	private static MethodHandle decode;
	private static MethodHandle xxh3;
	private static MethodHandle lz4Compress;
	private static MethodHandle lz4Decompress;

	@BeforeAll
	public static void load() {
		if (!Files.isRegularFile(LIBRARY)) {
			return;
		}
		arena = Arena.ofShared();
		Linker linker = Linker.nativeLinker();
		SymbolLookup library = SymbolLookup.libraryLookup(LIBRARY.toAbsolutePath(), arena);
		version = linker.downcallHandle(library.findOrThrow("mlg_version"),
			FunctionDescriptor.of(ValueLayout.JAVA_INT));
		capabilities = linker.downcallHandle(library.findOrThrow("mlg_capabilities"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG));
		crc32c = linker.downcallHandle(library.findOrThrow("mlg_crc32c"),
			FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
		encode = linker.downcallHandle(library.findOrThrow("mlg_edges_encode"),
			FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		decode = linker.downcallHandle(library.findOrThrow("mlg_edges_decode"),
			FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		xxh3 = linker.downcallHandle(library.findOrThrow("mlg_xxh3"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
				ValueLayout.JAVA_LONG));
		lz4Compress = linker.downcallHandle(library.findOrThrow("mlg_lz4_compress"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
				ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
		lz4Decompress = linker.downcallHandle(library.findOrThrow("mlg_lz4_decompress"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
				ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
	}

	private static void assumeLoaded() {
		assumeTrue(version != null, LIBRARY + " has not been built; install cargo and run `mvn generate-resources`");
	}

	// ---------------------------------------------------------------- ABI

	@Test
	public void testAbiVersionAndCapabilities() throws Throwable {
		assumeLoaded();
		assertEquals(NativeCapabilities.SUPPORTED_ABI_VERSION, (int) version.invokeExact(),
			"ABI version the Java side expects");
		long caps = (long) capabilities.invokeExact();
		assertTrue((caps & 0b1111) == 0b1111,
			"crc32c, the edge codec, xxh3 and lz4 are unconditional; got " + Long.toBinaryString(caps));
		System.out.println("crate capabilities: " + NativeCapabilities.describeCapabilities(caps));
	}

	// ---------------------------------------------------------------- differential: CRC32C

	@Test
	public void testCrcMatchesTheJdkOnEveryLength() throws Throwable {
		assumeLoaded();
		Random random = new Random(20260814L);
		try (Arena scope = Arena.ofConfined()) {
			for (int length : new int[] { 0, 1, 2, 3, 4, 7, 8, 15, 16, 17, 63, 64, 65, 255, 256, 1024, 8192 }) {
				byte[] data = new byte[length];
				random.nextBytes(data);
				MemorySegment buffer = scope.allocate(Math.max(length, 1));
				MemorySegment.copy(data, 0, buffer, ValueLayout.JAVA_BYTE, 0, length);

				int expected = Crc.crc32c(data);
				int actual = (int) crc32c.invokeExact(buffer, (long) length, 0);
				assertEquals(expected, actual, "CRC32C over " + length + " bytes");
			}
		}
	}

	// ---------------------------------------------------------------- differential: the edge codec

	@Test
	public void testNativeEncodingIsByteIdenticalToJava() throws Throwable {
		assumeLoaded();
		Random random = new Random(4242L);
		try (Arena scope = Arena.ofConfined()) {
			for (int count : new int[] { 1, 2, 7, 64, 500, 4000 }) {
				long[] edges = randomEdges(random, count);

				byte[] expected = EdgeCodec.encode(edges, count);
				byte[] actual = nativeEncode(scope, edges, count);

				assertArrayEquals(expected, actual,
					"the native encoder must be byte-identical to Java for " + count + " edges");
			}
		}
	}

	@Test
	public void testEachSideDecodesTheOthersOutput() throws Throwable {
		assumeLoaded();
		Random random = new Random(77L);
		try (Arena scope = Arena.ofConfined()) {
			long[] edges = randomEdges(random, 1500);
			long[] sorted = Arrays.copyOf(edges, edges.length);
			Arrays.sort(sorted);

			byte[] javaBytes = EdgeCodec.encode(edges, edges.length);
			byte[] nativeBytes = nativeEncode(scope, edges, edges.length);

			long[] javaFromNative = new long[edges.length];
			assertEquals(edges.length, EdgeCodec.decode(nativeBytes, 0, nativeBytes.length, javaFromNative));
			assertArrayEquals(sorted, javaFromNative, "Java must read what Rust wrote");

			assertArrayEquals(sorted, nativeDecode(scope, javaBytes, edges.length), "Rust must read what Java wrote");
		}
	}

	/** A corrupt block must come back as an error from the native side too, not an out-of-bounds read. */
	@Test
	public void testTruncatedInputIsRejected() throws Throwable {
		assumeLoaded();
		try (Arena scope = Arena.ofConfined()) {
			long[] edges = randomEdges(new Random(9L), 200);
			byte[] encoded = nativeEncode(scope, edges, edges.length);

			MemorySegment src = scope.allocate(encoded.length);
			MemorySegment.copy(encoded, 0, src, ValueLayout.JAVA_BYTE, 0, encoded.length - 1);
			MemorySegment dst = scope.allocate(ValueLayout.JAVA_LONG, edges.length);

			int result = (int) decode.invokeExact(src, encoded.length - 1, dst, edges.length);
			assertTrue(result < 0, "a truncated block must be rejected, got " + result);
		}
	}

	/** lz4 must round-trip exactly. A compressor that loses a byte is a corruption engine with a good ratio. */
	@Test
	public void testLz4RoundTripsAndCompresses() throws Throwable {
		assumeLoaded();
		// Blob-shaped data. What BlobStore actually holds is large property values — text, serialised documents,
		// vectors — so a synthetic payload of uniform random bytes would measure the wrong thing entirely: lz4
		// cannot compress noise and neither can anything else.
		StringBuilder text = new StringBuilder();
		Random random = new Random(11L);
		String[] words = { "asset", "collection", "remix", "person", "tag", "metaloom", "graph", "storage" };
		while (text.length() < 64 * 1024) {
			text.append("{\"kind\":\"").append(words[random.nextInt(words.length)])
				.append("\",\"id\":").append(random.nextInt(1000))
				.append(",\"label\":\"").append(words[random.nextInt(words.length)]).append("\"}\n");
		}
		byte[] payload = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

		try (Arena scope = Arena.ofConfined()) {
			MemorySegment src = scope.allocate(payload.length);
			MemorySegment.copy(payload, 0, src, ValueLayout.JAVA_BYTE, 0, payload.length);
			MemorySegment compressed = scope.allocate(payload.length * 2L);

			long written = (long) lz4Compress.invokeExact(src, (long) payload.length, compressed,
				compressed.byteSize());
			assertTrue(written > 0, "compression returned " + written);
			assertTrue(written < payload.length / 2,
				"document-shaped input must compress to under half: " + written + " of " + payload.length);

			MemorySegment restored = scope.allocate(payload.length);
			long back = (long) lz4Decompress.invokeExact(compressed, written, restored, (long) payload.length);
			assertEquals(payload.length, back, "decompression must restore every byte");
			assertArrayEquals(payload, restored.toArray(ValueLayout.JAVA_BYTE));

			System.out.printf("lz4: %,d bytes -> %,d (%.1f%% of original)%n",
				payload.length, written, 100.0 * written / payload.length);
		}
	}

	/** xxh3 is opaque to the Java side, so what has to hold is determinism and distribution. */
	@Test
	public void testXxh3IsDeterministicAndSeeded() throws Throwable {
		assumeLoaded();
		try (Arena scope = Arena.ofConfined()) {
			byte[] data = "metaloom graph storage".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			MemorySegment buffer = scope.allocate(data.length);
			MemorySegment.copy(data, 0, buffer, ValueLayout.JAVA_BYTE, 0, data.length);

			long first = (long) xxh3.invokeExact(buffer, (long) data.length, 0L);
			long again = (long) xxh3.invokeExact(buffer, (long) data.length, 0L);
			long seeded = (long) xxh3.invokeExact(buffer, (long) data.length, 1L);

			assertEquals(first, again, "the same bytes and seed must hash the same");
			assertTrue(first != seeded, "the seed must change the result");
			assertTrue(first != 0, "a real hash of real bytes is not zero");
		}
	}

	// ---------------------------------------------------------------- the measurement

	/**
	 * Java versus Rust on the two hot loops, including the cost of getting the bytes across the boundary — which is
	 * the only comparison that means anything, because the engine holds its data in Java arrays and mapped segments,
	 * not in native buffers.
	 */
	@Test
	public void testHowMuchFasterIsTheNativeCodec() throws Throwable {
		assumeLoaded();
		int iterations = 20_000;
		long[] edges = randomEdges(new Random(1L), 1000);

		try (Arena scope = Arena.ofConfined()) {
			MemorySegment src = scope.allocate(ValueLayout.JAVA_LONG, edges.length);
			MemorySegment dst = scope.allocate(64 * 1024);

			// Warm both sides.
			for (int i = 0; i < 2000; i++) {
				EdgeCodec.encode(edges, edges.length);
				nativeEncodeInto(src, dst, edges, edges.length);
			}

			long start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				EdgeCodec.encode(edges, edges.length);
			}
			double javaNanos = (System.nanoTime() - start) / (double) iterations;

			start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				nativeEncodeInto(src, dst, edges, edges.length);
			}
			double nativeNanos = (System.nanoTime() - start) / (double) iterations;

			System.out.printf("%nedge encode, %d entries: java %,.0f ns, rust %,.0f ns = %.2fx%n",
				edges.length, javaNanos, nativeNanos, javaNanos / nativeNanos);

			// CRC32C, where the JDK is already hardware-backed and the Rust is a bitwise loop.
			byte[] page = new byte[8192];
			new Random(2L).nextBytes(page);
			MemorySegment pageSegment = scope.allocate(page.length);
			MemorySegment.copy(page, 0, pageSegment, ValueLayout.JAVA_BYTE, 0, page.length);

			for (int i = 0; i < 2000; i++) {
				Crc.crc32c(page);
				int ignored = (int) crc32c.invokeExact(pageSegment, (long) page.length, 0);
			}
			start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				Crc.crc32c(page);
			}
			double javaCrc = (System.nanoTime() - start) / (double) iterations;

			start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				int ignored = (int) crc32c.invokeExact(pageSegment, (long) page.length, 0);
			}
			double nativeCrc = (System.nanoTime() - start) / (double) iterations;

			System.out.printf("crc32c, 8 KiB page:     java %,.0f ns, rust %,.0f ns = %.2fx%n",
				javaCrc, nativeCrc, javaCrc / nativeCrc);
			System.out.println("  (both now use the hardware CRC32 instruction: the JDK through its intrinsic, the "
				+ "crate through the crc32c crate. The first measurement had a bitwise loop on the Rust side and "
				+ "reported 200x slower, which said nothing about the language)");
		}
	}

	// ---------------------------------------------------------------- helpers

	private static long[] randomEdges(Random random, int count) {
		long[] edges = new long[count];
		for (int i = 0; i < count; i++) {
			int dir = random.nextInt(2);
			int type = random.nextInt(8);
			long location = random.nextInt(1 << 24);
			edges[i] = PackedEdge.pack(dir, type, location);
		}
		return edges;
	}

	private static byte[] nativeEncode(Arena scope, long[] edges, int count) throws Throwable {
		MemorySegment src = scope.allocate(ValueLayout.JAVA_LONG, count);
		MemorySegment dst = scope.allocate(16 + count * 32L);
		int written = nativeEncodeInto(src, dst, edges, count);
		assertTrue(written > 0, "native encode returned " + written);
		return dst.asSlice(0, written).toArray(ValueLayout.JAVA_BYTE);
	}

	private static int nativeEncodeInto(MemorySegment src, MemorySegment dst, long[] edges, int count)
		throws Throwable {
		MemorySegment.copy(edges, 0, src, ValueLayout.JAVA_LONG, 0, count);
		return (int) encode.invokeExact(src, count, dst, (int) dst.byteSize());
	}

	private static long[] nativeDecode(Arena scope, byte[] encoded, int capacity) throws Throwable {
		MemorySegment src = scope.allocate(encoded.length);
		MemorySegment.copy(encoded, 0, src, ValueLayout.JAVA_BYTE, 0, encoded.length);
		MemorySegment dst = scope.allocate(ValueLayout.JAVA_LONG, capacity);
		int written = (int) decode.invokeExact(src, encoded.length, dst, capacity);
		assertTrue(written >= 0, "native decode returned " + written);
		return dst.asSlice(0, written * 8L).toArray(ValueLayout.JAVA_LONG);
	}
}
