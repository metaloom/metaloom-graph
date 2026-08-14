package io.metaloom.graph.core.util;

/**
 * The LZ4 block format, decompression only, in pure Java.
 *
 * <p>
 * <b>Why decompression lives here and compression does not.</b> Compression is optional: a store that cannot compress
 * simply writes its blobs whole. Decompression is not — once a compressed blob is on disk, every future reader must be
 * able to read it, including one running on a machine where the Rust library is absent or refuses to load. Putting the
 * compressor behind the native boundary and the decompressor in front of it is what keeps
 * [NATIVE_FFM.md](../../../../../../../../spec/NATIVE_FFM.md) section 5's rule true: no native capability may be
 * load-bearing for reading a store back.
 * </p>
 *
 * <p>
 * The format is a sequence of blocks, each a token byte followed by literals and then a back-reference:
 * </p>
 *
 * <pre>
 * token     high nibble = literal length, low nibble = match length - 4
 *           a nibble of 15 means "add the following bytes until one is not 255"
 * literals  literal_length bytes, copied straight out
 * offset    2 bytes little endian, distance back into the output
 * match     match_length bytes copied from that distance, overlapping allowed
 * </pre>
 *
 * <p>
 * The final block has literals and no match. Every length and offset here comes off disk and is therefore untrusted:
 * each one is checked against the bounds of both buffers before it is used, so a corrupt blob raises
 * {@link IllegalArgumentException} rather than reading or writing outside an array.
 * </p>
 */
public final class Lz4 {

	private static final int MIN_MATCH = 4;

	private Lz4() {
	}

	/**
	 * Decompresses exactly {@code dst.length} bytes.
	 *
	 * @param src
	 *            compressed bytes
	 * @param srcOffset
	 *            where the compressed data starts
	 * @param srcLength
	 *            how many compressed bytes there are
	 * @param dst
	 *            output, sized to the known original length
	 * @return the number of bytes written, always {@code dst.length} on success
	 * @throws IllegalArgumentException
	 *             if the input is malformed or does not produce exactly {@code dst.length} bytes
	 */
	public static int decompress(byte[] src, int srcOffset, int srcLength, byte[] dst) {
		if (srcOffset < 0 || srcLength < 0 || srcOffset + srcLength > src.length) {
			throw new IllegalArgumentException("Compressed range " + srcOffset + ".." + (srcOffset + srcLength)
				+ " lies outside a " + src.length + " byte buffer");
		}
		int in = srcOffset;
		int end = srcOffset + srcLength;
		int out = 0;

		while (in < end) {
			int token = src[in++] & 0xFF;

			int literals = token >>> 4;
			if (literals == 15) {
				literals += readLength(src, in, end);
				in += lengthBytes(src, in, end);
			}
			if (literals > 0) {
				if (in + literals > end || out + literals > dst.length) {
					throw new IllegalArgumentException("Literal run of " + literals
						+ " bytes overruns the compressed input or the output buffer");
				}
				System.arraycopy(src, in, dst, out, literals);
				in += literals;
				out += literals;
			}
			if (in == end) {
				// The last sequence is literals only, with no back-reference after it.
				break;
			}
			if (in + 2 > end) {
				throw new IllegalArgumentException("Truncated match offset at " + in);
			}
			int offset = (src[in] & 0xFF) | ((src[in + 1] & 0xFF) << 8);
			in += 2;
			if (offset <= 0 || offset > out) {
				throw new IllegalArgumentException("Match offset " + offset
					+ " points outside the " + out + " bytes decoded so far");
			}

			int matchLength = token & 0x0F;
			if (matchLength == 15) {
				matchLength += readLength(src, in, end);
				in += lengthBytes(src, in, end);
			}
			matchLength += MIN_MATCH;
			if (out + matchLength > dst.length) {
				throw new IllegalArgumentException("Match of " + matchLength + " bytes overruns a "
					+ dst.length + " byte output at " + out);
			}
			// Byte at a time, deliberately: a match may overlap its own output, which is how LZ4 encodes runs, and
			// arraycopy would read ahead of what has been written.
			int from = out - offset;
			for (int i = 0; i < matchLength; i++) {
				dst[out++] = dst[from++];
			}
		}

		if (out != dst.length) {
			throw new IllegalArgumentException("Decompressed " + out + " bytes, expected exactly " + dst.length);
		}
		return out;
	}

	/** The variable-length extension: 255s accumulate, the first non-255 terminates. */
	private static int readLength(byte[] src, int offset, int end) {
		int total = 0;
		int i = offset;
		while (true) {
			if (i >= end) {
				throw new IllegalArgumentException("Truncated length extension at " + i);
			}
			int b = src[i++] & 0xFF;
			total += b;
			if (b != 255) {
				return total;
			}
			if (total > (1 << 30)) {
				throw new IllegalArgumentException("Length extension exceeds any plausible blob");
			}
		}
	}

	/** How many bytes {@link #readLength} consumed. */
	private static int lengthBytes(byte[] src, int offset, int end) {
		int i = offset;
		while (i < end && (src[i] & 0xFF) == 255) {
			i++;
		}
		return i - offset + 1;
	}
}
