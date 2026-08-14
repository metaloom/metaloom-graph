package io.metaloom.graph.core.util;

/**
 * LEB128 unsigned variable length integers over byte arrays.
 * <p>
 * Used by the adjacency edge codec (spec/RELATIONSHIP_STORAGE.md section 5.2) and by the property value codec. The
 * decode path must be robust against corrupt input: it never reads past the supplied limit.
 */
public final class VarInt {

	private VarInt() {
	}

	/** Number of bytes {@link #write(byte[], int, long)} would emit for the value. */
	public static int sizeOf(long value) {
		int n = 1;
		long v = value >>> 7;
		while (v != 0) {
			n++;
			v >>>= 7;
		}
		return n;
	}

	/** Writes the value and returns the new offset. */
	public static int write(byte[] dst, int offset, long value) {
		long v = value;
		while ((v & ~0x7FL) != 0) {
			dst[offset++] = (byte) ((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		dst[offset++] = (byte) v;
		return offset;
	}

	/**
	 * Reads a value. The decoded value is placed in {@code out[0]} and the new offset is returned.
	 *
	 * @throws IllegalArgumentException
	 *             if the encoding is truncated or overlong (corrupt input)
	 */
	public static int read(byte[] src, int offset, int limit, long[] out) {
		long result = 0;
		int shift = 0;
		while (true) {
			if (offset >= limit) {
				throw new IllegalArgumentException("Truncated varint at offset " + offset);
			}
			if (shift > 63) {
				throw new IllegalArgumentException("Overlong varint at offset " + offset);
			}
			int b = src[offset++] & 0xFF;
			result |= ((long) (b & 0x7F)) << shift;
			if ((b & 0x80) == 0) {
				out[0] = result;
				return offset;
			}
			shift += 7;
		}
	}

	/** Zig-zag encoding so that small negative numbers stay small. */
	public static long zigZagEncode(long value) {
		return (value << 1) ^ (value >> 63);
	}

	public static long zigZagDecode(long value) {
		return (value >>> 1) ^ -(value & 1);
	}
}
