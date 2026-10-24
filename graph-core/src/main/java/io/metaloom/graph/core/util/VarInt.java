package io.metaloom.graph.core.util;

import java.lang.foreign.MemorySegment;

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
				if (shift == 63 && b > 1) {
					// The tenth byte holds bit 63 alone; more than that is bits a long does not have, silently dropped.
					throw new IllegalArgumentException("Overlong varint at offset " + (offset - 1));
				}
				out[0] = result;
				return offset;
			}
			shift += 7;
		}
	}

	/** Writes the value into a segment and returns the new offset. */
	public static long write(MemorySegment dst, long offset, long value) {
		long v = value;
		long position = offset;
		while ((v & ~0x7FL) != 0) {
			dst.set(Layouts.BYTE, position++, (byte) ((v & 0x7F) | 0x80));
			v >>>= 7;
		}
		dst.set(Layouts.BYTE, position++, (byte) v);
		return position;
	}

	/**
	 * Reads a value straight out of a mapped segment, so a caller decoding a page needs no intermediate byte array.
	 * <p>
	 * Byte at a time, exactly like the array overload, and deliberately so. A word-at-a-time SWAR decoder was written
	 * and measured against this one: it is <b>2.3x slower</b> on real adjacency blocks, because the varints there
	 * average 1.93 bytes, so the loop below exits after one branch that the predictor gets right almost every time,
	 * while the branch-free version pays ten ALU operations per value whatever its length. The technique is sound; the
	 * data is the wrong shape for it. See spec/GRAPH_BENCHMARK.md.
	 *
	 * @throws IllegalArgumentException
	 *             if the encoding is truncated or overlong (corrupt input)
	 */
	public static long read(MemorySegment src, long offset, long limit, long[] out) {
		long result = 0;
		int shift = 0;
		long position = offset;
		while (true) {
			if (position >= limit) {
				throw new IllegalArgumentException("Truncated varint at offset " + position);
			}
			if (shift > 63) {
				throw new IllegalArgumentException("Overlong varint at offset " + position);
			}
			int b = Byte.toUnsignedInt(src.get(Layouts.BYTE, position++));
			result |= ((long) (b & 0x7F)) << shift;
			if ((b & 0x80) == 0) {
				if (shift == 63 && b > 1) {
					// The tenth byte holds bit 63 alone; more than that is bits a long does not have, silently dropped.
					throw new IllegalArgumentException("Overlong varint at offset " + (position - 1));
				}
				out[0] = result;
				return position;
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
