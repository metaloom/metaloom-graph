package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.nativelib.NativeCodec;
import io.metaloom.graph.core.util.Lz4;

/**
 * lz4 for blobs — capability N4, and the only one of the crate's codecs the engine actually calls.
 *
 * <p>
 * The asymmetry is the point and is what these tests defend: <b>the compressor is native and optional, the
 * decompressor is pure Java and mandatory</b>. A blob written on a machine with the Rust library has to be readable on
 * one without it, or the fallback rule in spec/NATIVE_FFM.md section 5 is a fiction and the native layer has quietly
 * become load-bearing for reading a store.
 * </p>
 */
@Tag("unit")
public class BlobCompressionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** Document-shaped bytes: the kind of thing a blob actually holds, and the kind lz4 is good at. */
	private static byte[] compressible(int approximateSize) {
		StringBuilder text = new StringBuilder();
		Random random = new Random(20260814L);
		String[] words = { "asset", "collection", "created", "false", "identifier", "metadata", "null", "owner",
			"published", "true", "updated", "version" };
		while (text.length() < approximateSize) {
			text.append("  \"").append(words[random.nextInt(words.length)]).append("\": \"")
				.append(words[random.nextInt(words.length)]).append("\",\n");
		}
		return text.toString().getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void testALargePropertyRoundTripsThroughCompression() throws Exception {
		byte[] payload = compressible(200_000);
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Doc", Properties.of().set("body", payload));
			assertArrayEquals(payload, (byte[]) store.nodeProperty(node, "body"));
		}
	}

	/** Reopening must produce the same bytes, which is what proves the compression byte was persisted. */
	@Test
	public void testCompressedBlobsSurviveAReopen() throws Exception {
		byte[] payload = compressible(300_000);
		long node;
		try (GraphStore store = stores.open()) {
			node = store.createNode("Doc", Properties.of().set("body", payload));
		}
		try (GraphStore store = stores.open()) {
			assertArrayEquals(payload, (byte[]) store.nodeProperty(node, "body"));
		}
	}

	/** Incompressible bytes must be stored whole rather than expanded. */
	@Test
	public void testRandomBytesAreNotCompressed() throws Exception {
		byte[] payload = new byte[200_000];
		new Random(7L).nextBytes(payload);
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Doc", Properties.of().set("body", payload));
			assertArrayEquals(payload, (byte[]) store.nodeProperty(node, "body"));
		}
	}

	/** Sizes around the extent boundary, where an off-by-one in the chain walk would show. */
	@Test
	public void testSizesAroundTheExtentBoundary() throws Exception {
		try (GraphStore store = stores.open()) {
			for (int size : new int[] { 0, 1, 100, 8_000, 8_192, 8_193, 16_384, 40_000 }) {
				byte[] payload = compressible(size);
				long node = store.createNode("Doc", Properties.of().set("body", payload));
				assertArrayEquals(payload, (byte[]) store.nodeProperty(node, "body"), "size " + size);
			}
		}
	}

	/**
	 * The Java decompressor against the Rust compressor, which is the pairing a store on disk actually depends on.
	 * <p>
	 * Skipped where the crate is absent, because then nothing compresses and there is nothing to check.
	 */
	@Test
	public void testTheJavaDecompressorReadsWhatTheRustCompressorWrote() {
		assumeTrue(NativeCodec.isCompressionAvailable(), "the native lz4 compressor is unavailable");

		Random random = new Random(99L);
		int compressedRounds = 0;
		for (int round = 0; round < 200; round++) {
			byte[] payload = compressible(1 + random.nextInt(60_000));
			byte[] compressed = NativeCodec.compress(payload);
			if (compressed == null) {
				continue;
			}
			compressedRounds++;
			byte[] restored = new byte[payload.length];
			assertEquals(payload.length, Lz4.decompress(compressed, 0, compressed.length, restored));
			assertArrayEquals(payload, restored, "round " + round);
		}
		// Without this the test passes by doing nothing. It did exactly that until the output buffer handed to
		// lz4_flex was found to be sized too tightly: every call returned EINVAL, `compressed` was always null, and a
		// loop of 200 rounds asserted precisely nothing while reporting success.
		assertTrue(compressedRounds > 150,
			"document-shaped payloads must actually compress; only " + compressedRounds + " of 200 did");
	}

	/** Runs, which lz4 encodes as matches that overlap their own output. */
	@Test
	public void testOverlappingMatchesDecodeCorrectly() {
		assumeTrue(NativeCodec.isCompressionAvailable(), "the native lz4 compressor is unavailable");
		byte[] payload = new byte[50_000];
		java.util.Arrays.fill(payload, (byte) 'a');
		byte[] compressed = NativeCodec.compress(payload);
		assertTrue(compressed != null, "a run of a single byte must compress to something");
		System.out.printf("a %,d byte run of one value compresses to %,d bytes (%.2f%%)%n",
			payload.length, compressed.length, 100.0 * compressed.length / payload.length);
		assertTrue(compressed.length < payload.length / 10,
			"a run of one byte should compress by at least 10x, got " + compressed.length);
		byte[] restored = new byte[payload.length];
		Lz4.decompress(compressed, 0, compressed.length, restored);
		assertArrayEquals(payload, restored,
			"the overlapping-match path is the one a run exercises, and it must be byte exact");
	}

	/** A corrupt compressed stream must be refused, not read out of bounds. */
	@Test
	public void testMalformedInputIsRefused() {
		byte[] output = new byte[64];
		// A token promising more literals than exist.
		assertThrows(IllegalArgumentException.class,
			() -> Lz4.decompress(new byte[] { (byte) 0xF0, 1, 2, 3 }, 0, 4, output));
		// A match offset pointing before the start of the output.
		assertThrows(IllegalArgumentException.class,
			() -> Lz4.decompress(new byte[] { 0x10, (byte) 0xFF, (byte) 0xFF }, 0, 3, output));
		// A range outside the source buffer.
		assertThrows(IllegalArgumentException.class,
			() -> Lz4.decompress(new byte[] { 0x10 }, 0, 8, output));
		// Well formed, but does not fill the declared output.
		assertThrows(IllegalArgumentException.class,
			() -> Lz4.decompress(new byte[] { 0x20, 'a', 'b' }, 0, 3, output));
	}
}
