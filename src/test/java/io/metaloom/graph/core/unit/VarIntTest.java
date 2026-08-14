package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.util.VarInt;

@Tag("unit")
public class VarIntTest {

	@Test
	public void testRoundTrip() {
		Random random = new Random(99L);
		long[] out = new long[1];
		byte[] buf = new byte[16];
		long[] values = new long[1000];
		for (int i = 0; i < values.length; i++) {
			values[i] = i < 10 ? i : Math.abs(random.nextLong()) >>> random.nextInt(63);
		}
		for (long value : values) {
			int written = VarInt.write(buf, 0, value);
			assertEquals(VarInt.sizeOf(value), written, "sizeOf disagrees with write for " + value);
			int read = VarInt.read(buf, 0, written, out);
			assertEquals(value, out[0]);
			assertEquals(written, read);
		}
	}

	@Test
	public void testSizeBoundaries() {
		assertEquals(1, VarInt.sizeOf(0));
		assertEquals(1, VarInt.sizeOf(127));
		assertEquals(2, VarInt.sizeOf(128));
		assertEquals(2, VarInt.sizeOf(16383));
		assertEquals(3, VarInt.sizeOf(16384));
	}

	@Test
	public void testZigZag() {
		for (long value : new long[] { 0, 1, -1, 42, -42, Long.MAX_VALUE, Long.MIN_VALUE }) {
			assertEquals(value, VarInt.zigZagDecode(VarInt.zigZagEncode(value)));
		}
		// Small negatives must stay small, which is the point of the encoding.
		assertEquals(1, VarInt.sizeOf(VarInt.zigZagEncode(-1)));
	}

	@Test
	public void testTruncatedInputIsRejected() {
		byte[] buf = new byte[4];
		VarInt.write(buf, 0, 100_000);
		assertThrows(IllegalArgumentException.class, () -> VarInt.read(buf, 0, 1, new long[1]));
	}
}
