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
		int available = (int) Math.min(segmentBytes - offset, buffer.length);
		if (available < WalRecord.HEADER_BYTES) {
			// The tail of the segment cannot hold a record: continue in the next one.
			lsn = base + segmentBytes;
			return next();
		}
		ByteBuffer view = ByteBuffer.wrap(buffer, 0, available);
		channel.position(offset);
		int read = 0;
		while (view.hasRemaining()) {
			int n = channel.read(view);
			if (n < 0) {
				break;
			}
			read += n;
		}
		if (read < WalRecord.HEADER_BYTES) {
			return null;
		}
		int length = WalRecord.validate(buffer, 0, read, lsn);
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
		System.arraycopy(buffer, 0, record, 0, length);
		lsn += length;
		return record;
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
