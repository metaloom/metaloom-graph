package io.metaloom.graph.cli.dump;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * The reading half of {@link DumpWriter}, over a stream that is <b>untrusted input</b>.
 *
 * <p>
 * A dump file is untrusted on the same terms a store directory is ({@code spec/OPERATIONS.md} section 9): it may have
 * been truncated by a full disk, corrupted by media, or written by somebody else entirely. Seven defects of exactly
 * that kind were found in this project's storage parsers and three more in the wire decoder, and every one of them was
 * a length field that was believed.
 * </p>
 *
 * <p>
 * So: <b>every length is checked against what is actually left before anything is allocated for it</b>
 * ({@link #checkAvailable}), the stream's total size is known up front, and a read past the end is an exception naming
 * the position rather than a negative array size or an {@code OutOfMemoryError}. The rule this enforces is the one the
 * hostile-input tests assert — decoding either produces a value or throws {@link DumpFormatException}, never
 * anything else, and always terminates.
 * </p>
 */
public final class DumpReader implements AutoCloseable {

	private final InputStream in;
	private final long size;
	private final CRC32C crc = new CRC32C();
	private final byte[] scratch = new byte[8];
	private boolean checksumming;
	private long position;

	/**
	 * @param size
	 *            the total bytes available, which is what makes a length field checkable. A stream whose length is
	 *            genuinely unknown may pass {@link Long#MAX_VALUE}, and gives up that protection
	 */
	public DumpReader(InputStream in, long size) {
		this.in = in;
		this.size = size;
	}

	public void beginChecksum() {
		checksumming = true;
	}

	public long checksum() {
		return crc.getValue();
	}

	public long position() {
		return position;
	}

	/** Bytes not yet read. */
	public long remaining() {
		return size == Long.MAX_VALUE ? Long.MAX_VALUE : size - position;
	}

	/**
	 * Refuses a length that cannot possibly be satisfied, before it is used to allocate.
	 *
	 * <p>
	 * The check is against the bytes genuinely left in the file, not against a constant: a cap large enough for a real
	 * dump would be far larger than most corrupt lengths, and one small enough to catch them would refuse real data.
	 * </p>
	 */
	public void checkAvailable(long bytes) {
		if (bytes < 0) {
			throw new DumpFormatException("A negative length (" + bytes + ") at byte " + position);
		}
		if (bytes > remaining()) {
			throw new DumpFormatException("A length of " + bytes + " at byte " + position
				+ ", but only " + remaining() + " bytes remain. The dump is truncated or corrupt.");
		}
	}

	public int u8() {
		fill(1);
		return scratch[0] & 0xFF;
	}

	public int u16() {
		fill(2);
		return (scratch[0] & 0xFF) | ((scratch[1] & 0xFF) << 8);
	}

	public int u32() {
		fill(4);
		int value = 0;
		for (int i = 0; i < 4; i++) {
			value |= (scratch[i] & 0xFF) << (8 * i);
		}
		return value;
	}

	public long u64() {
		fill(8);
		long value = 0;
		for (int i = 0; i < 8; i++) {
			value |= (long) (scratch[i] & 0xFF) << (8 * i);
		}
		return value;
	}

	/** LEB128, unsigned, bounded to {@code int} range because every use of it is a count or a length. */
	public int varInt() {
		long value = 0;
		int shift = 0;
		while (true) {
			// Ten groups of seven bits is more than a long holds, so a run of continuation bytes terminates here
			// rather than spinning to the end of the file.
			if (shift > 63) {
				throw new DumpFormatException("A varint at byte " + position + " does not terminate");
			}
			int b = u8();
			value |= (long) (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		if (value < 0 || value > Integer.MAX_VALUE) {
			throw new DumpFormatException("A count of " + value + " at byte " + position + " is out of range");
		}
		return (int) value;
	}

	public String string() {
		int length = varInt();
		checkAvailable(length);
		return new String(bytes(length), StandardCharsets.UTF_8);
	}

	public byte[] bytes(int length) {
		checkAvailable(length);
		byte[] value = new byte[length];
		int read = 0;
		while (read < length) {
			int n;
			try {
				n = in.read(value, read, length - read);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			if (n < 0) {
				throw new DumpFormatException("The dump ends after " + (position + read) + " bytes, mid-value");
			}
			read += n;
		}
		if (checksumming) {
			crc.update(value, 0, length);
		}
		position += length;
		return value;
	}

	private void fill(int length) {
		int read = 0;
		while (read < length) {
			int n;
			try {
				n = in.read(scratch, read, length - read);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			if (n < 0) {
				throw new DumpFormatException(new EOFException("The dump ends after " + (position + read)
					+ " bytes, mid-value").getMessage());
			}
			read += n;
		}
		if (checksumming) {
			crc.update(scratch, 0, length);
		}
		position += length;
	}

	@Override
	public void close() {
		try {
			in.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
