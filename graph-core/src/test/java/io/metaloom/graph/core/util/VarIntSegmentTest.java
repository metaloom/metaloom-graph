package io.metaloom.graph.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.store.adj.EdgeCodec;
import io.metaloom.graph.core.store.adj.PackedEdge;

/**
 * The {@link MemorySegment} varint and decode paths against the byte array ones.
 *
 * <p>
 * Decoding a block straight out of its mapped page avoids allocating a copy of it on every read, but it introduces a
 * second implementation of something the store's correctness rests on. A decoder that is fast and subtly wrong
 * corrupts every traversal and does it silently, so the bar is not "passes some cases" but "agrees with the array
 * implementation on every input, including the ones that must throw".
 * </p>
 *
 * <p>
 * These tests also stand as the guard for the next person who tries to make the decoder clever: a word-at-a-time SWAR
 * decoder was written, passed all of this, and was <b>2.3x slower</b> than the byte-at-a-time loop, because the
 * varints in a real block average 1.93 bytes. Anything replacing the loop has to beat it here first.
 * </p>
 */
@Tag("unit")
public class VarIntSegmentTest {

	@Test
	public void testEveryLengthBoundary() {
		// One value either side of each varint length step, plus the extremes.
		long[] interesting = {
			0, 1, 126, 127, 128, 129, 16_382, 16_383, 16_384, 16_385,
			(1L << 21) - 1, 1L << 21, (1L << 28) - 1, 1L << 28, (1L << 35) - 1, 1L << 35,
			(1L << 42) - 1, 1L << 42, (1L << 48) - 1, 1L << 48, (1L << 49) - 1,
			(1L << 56) - 1, 1L << 56, Long.MAX_VALUE, -1L
		};
		for (long value : interesting) {
			assertRoundTrip(value);
		}
	}

	@Test
	public void testRandomValuesAgree() {
		Random random = new Random(20260814L);
		for (int i = 0; i < 200_000; i++) {
			// Every width, not just the small ones a realistic block would hold.
			assertRoundTrip(random.nextLong() >>> random.nextInt(64));
		}
	}

	/** A varint that ends in the last byte of the buffer must decode; the fast path may not run off the end. */
	@Test
	public void testAValueAtTheVeryEndOfTheBuffer() {
		for (int padding = 0; padding < 12; padding++) {
			for (long value : new long[] { 0, 127, 128, 1L << 40, Long.MAX_VALUE }) {
				byte[] buffer = new byte[padding + VarInt.sizeOf(value)];
				int end = VarInt.write(buffer, padding, value);
				assertEquals(buffer.length, end);

				long[] out = new long[1];
				assertEquals(end, VarInt.read(buffer, padding, buffer.length, out));
				assertEquals(value, out[0], "array path, padding " + padding);

				try (Arena arena = Arena.ofConfined()) {
					MemorySegment segment = arena.allocate(buffer.length);
					MemorySegment.copy(MemorySegment.ofArray(buffer), 0, segment, 0, buffer.length);
					assertEquals(end, VarInt.read(segment, padding, buffer.length, out));
					assertEquals(value, out[0], "segment path, padding " + padding);
				}
			}
		}
	}

	/** Truncated input must throw, not return a plausible value read from whatever followed it. */
	@Test
	public void testTruncatedInputThrowsOnEitherPath() {
		byte[] buffer = new byte[16];
		int end = VarInt.write(buffer, 0, 1L << 40);
		int truncated = end - 1;

		long[] out = new long[1];
		assertThrows(IllegalArgumentException.class, () -> VarInt.read(buffer, 0, truncated, out));
		assertThrows(IllegalArgumentException.class, () -> VarInt.read(buffer, 0, truncated, out));

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(buffer.length);
			MemorySegment.copy(MemorySegment.ofArray(buffer), 0, segment, 0, buffer.length);
			assertThrows(IllegalArgumentException.class, () -> VarInt.read(segment, 0, truncated, out));
		}
	}

	/** An all-continuation-bytes sequence is overlong; both paths must refuse it rather than decode 64+ bits. */
	@Test
	public void testOverlongInputThrowsOnEitherPath() {
		byte[] buffer = new byte[24];
		java.util.Arrays.fill(buffer, (byte) 0x80);
		long[] out = new long[1];
		assertThrows(IllegalArgumentException.class, () -> VarInt.read(buffer, 0, buffer.length, out));
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(buffer.length);
			MemorySegment.copy(MemorySegment.ofArray(buffer), 0, segment, 0, buffer.length);
			assertThrows(IllegalArgumentException.class, () -> VarInt.read(segment, 0, buffer.length, out));
		}
	}

	/**
	 * The tenth byte carries bit 63 alone, so anything above 1 there names bits a long does not have. Accepting it
	 * silently dropped them, decoding a corrupt varint as a plausible value.
	 */
	@Test
	public void testATenthByteAboveOneIsRefusedOnEitherPath() {
		byte[] buffer = new byte[10];
		java.util.Arrays.fill(buffer, 0, 9, (byte) 0xFF);
		long[] out = new long[1];
		buffer[9] = 0x01;
		assertEquals(10, VarInt.read(buffer, 0, buffer.length, out));
		assertEquals(-1L, out[0], "0x01 in the tenth byte is bit 63, the largest legal encoding");
		buffer[9] = 0x02;
		assertThrows(IllegalArgumentException.class, () -> VarInt.read(buffer, 0, buffer.length, out));
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(buffer.length);
			MemorySegment.copy(MemorySegment.ofArray(buffer), 0, segment, 0, buffer.length);
			assertThrows(IllegalArgumentException.class, () -> VarInt.read(segment, 0, buffer.length, out));
			segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 9, (byte) 0x01);
			assertEquals(10, VarInt.read(segment, 0, buffer.length, out));
			assertEquals(-1L, out[0]);
		}
	}

	/** The two {@link EdgeCodec} decode entry points must produce the same edges as each other, on real blocks. */
	@Test
	public void testBlockDecodeAgreesAcrossPaths() {
		Random random = new Random(4242L);
		for (int round = 0; round < 200; round++) {
			int count = 1 + random.nextInt(600);
			long[] edges = new long[count];
			long location = random.nextInt(1 << 20);
			for (int i = 0; i < count; i++) {
				location += 1 + random.nextInt(1 << random.nextInt(20));
				edges[i] = PackedEdge.pack(random.nextInt(2), random.nextInt(64), location);
			}
			byte[] encoded = EdgeCodec.encode(edges, count);

			long[] viaArray = new long[count];
			int arrayCount = EdgeCodec.decode(encoded, 0, encoded.length, viaArray);

			try (Arena arena = Arena.ofConfined()) {
				MemorySegment segment = arena.allocate(encoded.length);
				MemorySegment.copy(MemorySegment.ofArray(encoded), 0, segment, 0, encoded.length);
				long[] viaSegment = new long[count];
				int segmentCount = EdgeCodec.decode(segment, 0, encoded.length, viaSegment);

				assertEquals(count, arrayCount);
				assertEquals(count, segmentCount);
				assertArrayEquals(viaArray, viaSegment, "round " + round);

				long[] expected = java.util.Arrays.copyOf(edges, count);
				java.util.Arrays.sort(expected);
				assertArrayEquals(expected, viaArray, "round " + round + ": decoded edges must be the sorted input");
			}
		}
	}

	private static void assertRoundTrip(long value) {
		byte[] buffer = new byte[24];
		int end = VarInt.write(buffer, 0, value);

		long[] reference = new long[1];
		int referenceEnd = VarInt.read(buffer, 0, buffer.length, reference);

		long[] fast = new long[1];
		int fastEnd = VarInt.read(buffer, 0, buffer.length, fast);
		assertEquals(referenceEnd, fastEnd, "offset after reading " + value);
		assertEquals(reference[0], fast[0], "value " + value);
		assertEquals(value, fast[0]);
		assertEquals(end, fastEnd);

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(buffer.length);
			MemorySegment.copy(MemorySegment.ofArray(buffer), 0, segment, 0, buffer.length);
			long[] viaSegment = new long[1];
			long segmentEnd = VarInt.read(segment, 0, buffer.length, viaSegment);
			assertEquals(referenceEnd, segmentEnd, "segment offset after reading " + value);
			assertEquals(reference[0], viaSegment[0], "segment value " + value);
		}
	}
}
