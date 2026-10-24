package io.metaloom.graph.core.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Forward scan over the log with validation.
 * <p>
 * The scan stops at the first record whose CRC fails, whose self-declared LSN does not match its own offset, or whose
 * length would run past the segment. All three mean the same thing: end of log.
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 6.1
 */
public final class WalReader implements AutoCloseable {

	private final Path dir;
	private final long segmentBytes;

	private FileChannel channel;
	private long channelBaseLsn = -1;
	private byte[] buffer = new byte[1 << 16];
	/** Offset in the open segment of {@code buffer[0]}, and how many bytes from there are valid. */
	private int windowOffset;
	private int windowLength;

	private long lsn;

	public WalReader(Path storePath, long segmentBytes, long startLsn) {
		this.dir = storePath.resolve("wal");
		this.segmentBytes = segmentBytes;
		this.lsn = startLsn;
	}

	public long lsn() {
		return lsn;
	}

	/**
	 * Reads the next valid record into a fresh array, or returns null at end of log.
	 */
	public byte[] next() throws IOException {
		long base = lsn / segmentBytes * segmentBytes;
		int offset = (int) (lsn % segmentBytes);
		if (!openSegment(base)) {
			return null;
		}
		long remaining = segmentBytes - offset;
		if (remaining < WalRecord.HEADER_BYTES) {
			// The tail of the segment cannot hold a record: continue in the next one.
			lsn = base + segmentBytes;
			return next();
		}
		int pos = window(offset, WalRecord.HEADER_BYTES);
		if (pos < 0) {
			return null;
		}
		int declared = WalRecord.length(buffer, pos);
		if (declared > windowLength - pos && declared <= remaining && WalRecord.lsn(buffer, pos) == lsn) {
			// A record that runs past the window, or past the whole buffer. Checked against its own LSN first, so
			// stale bytes declaring some huge length cannot make the buffer grow.
			pos = window(offset, declared);
			if (pos < 0) {
				return null;
			}
		}
		int length = WalRecord.validate(buffer, pos, windowLength - pos, lsn);
		if (length < 0) {
			// Either genuine end of log, or a record that starts in the skipped tail of a segment.
			if (offset != 0) {
				long nextBase = base + segmentBytes;
				if (Files.exists(segmentPath(nextBase))) {
					long probe = lsn;
					lsn = nextBase;
					byte[] candidate = next();
					if (candidate != null) {
						return candidate;
					}
					lsn = probe;
				}
			}
			return null;
		}
		byte[] record = new byte[length];
		System.arraycopy(buffer, pos, record, 0, length);
		lsn += length;
		return record;
	}

	/**
	 * Makes {@code need} bytes from {@code offset} of the open segment resident and returns where they start in the
	 * buffer, or -1 when the file holds fewer than a header's worth there.
	 *
	 * <p>
	 * A sliding window: the buffer is refilled only when the bytes are not already in it. Reading a full buffer for
	 * every record, as this once did, copied 64 KiB from the page cache per record of typically a hundred bytes, and
	 * made recovery time proportional to the record count times the buffer rather than to the log.
	 * </p>
	 */
	private int window(int offset, int need) throws IOException {
		if (offset >= windowOffset && (long) offset + need <= (long) windowOffset + windowLength) {
			return offset - windowOffset;
		}
		if (need > buffer.length) {
			buffer = new byte[Math.max(buffer.length * 2, need)];
		}
		int size = (int) Math.min(segmentBytes - offset, buffer.length);
		ByteBuffer view = ByteBuffer.wrap(buffer, 0, size);
		long position = offset;
		while (view.hasRemaining()) {
			int n = channel.read(view, position);
			if (n < 0) {
				break;
			}
			position += n;
		}
		windowOffset = offset;
		windowLength = view.position();
		return windowLength < WalRecord.HEADER_BYTES ? -1 : 0;
	}

	private Path segmentPath(long baseLsn) {
		return dir.resolve(String.format("wal-%016x.log", baseLsn));
	}

	private boolean openSegment(long base) throws IOException {
		if (channel != null && base == channelBaseLsn) {
			return true;
		}
		if (channel != null) {
			channel.close();
			channel = null;
		}
		windowLength = 0;
		Path path = segmentPath(base);
		if (!Files.exists(path)) {
			return false;
		}
		channel = FileChannel.open(path, StandardOpenOption.READ);
		channelBaseLsn = base;
		return true;
	}

	@Override
	public void close() throws IOException {
		if (channel != null) {
			channel.close();
			channel = null;
		}
	}
}
