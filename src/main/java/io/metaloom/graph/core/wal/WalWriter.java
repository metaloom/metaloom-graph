package io.metaloom.graph.core.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.metrics.IoCounters;

/**
 * Appends records to the log and makes them durable.
 * <p>
 * The LSN is the byte offset of a record in the logical, gapless stream, which also serves as the commit timestamp:
 * one counter instead of two removes an entire class of ordering bug. Segment files are named by the LSN of their
 * first byte and are preallocated, so appending never extends a file.
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a>
 */
public final class WalWriter implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(WalWriter.class);

	private final Path dir;
	private final long segmentBytes;
	private final WalSyncMode syncMode;
	private final long syncIntervalNanos;
	private long lastSyncNanos;

	private FileChannel channel;
	private long channelBaseLsn = -1;

	private long nextLsn;
	private long durableLsn;
	private boolean dirty;

	private IoCounters counters = new IoCounters();

	public WalWriter(Path storePath, long segmentBytes, long startLsn, WalSyncMode syncMode, long syncIntervalMillis)
		throws IOException {
		this.dir = storePath.resolve("wal");
		this.segmentBytes = segmentBytes;
		this.syncMode = syncMode;
		this.syncIntervalNanos = syncIntervalMillis * 1_000_000L;
		this.lastSyncNanos = System.nanoTime();
		Files.createDirectories(dir);
		this.nextLsn = startLsn;
		this.durableLsn = startLsn;
	}

	public void counters(IoCounters counters) {
		this.counters = counters;
	}

	public long nextLsn() {
		return nextLsn;
	}

	public long durableLsn() {
		return durableLsn;
	}

	public Path segmentPath(long baseLsn) {
		return dir.resolve(String.format("wal-%016x.log", baseLsn));
	}

	private long baseLsnOf(long lsn) {
		return lsn / segmentBytes * segmentBytes;
	}

	private FileChannel channelFor(long lsn) throws IOException {
		long base = baseLsnOf(lsn);
		if (channel != null && base == channelBaseLsn) {
			return channel;
		}
		if (channel != null) {
			channel.force(true);
			channel.close();
		}
		Path path = segmentPath(base);
		boolean fresh = !Files.exists(path);
		channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
			StandardOpenOption.WRITE);
		if (fresh) {
			// Preallocate so that appending never extends the file, which would be a metadata write per commit.
			channel.position(segmentBytes - 1);
			channel.write(ByteBuffer.wrap(new byte[1]));
			channel.force(true);
			logger.debug("Created WAL segment {}", path);
		}
		channelBaseLsn = base;
		return channel;
	}

	/**
	 * Appends one record and returns its LSN. A record never straddles a segment boundary: if it would, the remainder
	 * of the segment is skipped.
	 */
	public long append(long prevLsn, long txnId, int type, byte[] payload) throws IOException {
		int length = WalRecord.HEADER_BYTES + payload.length;
		if (length > segmentBytes) {
			throw new IllegalArgumentException("WAL record of " + length + " bytes exceeds the segment size");
		}
		long lsn = nextLsn;
		long offsetInSegment = lsn % segmentBytes;
		if (offsetInSegment + length > segmentBytes) {
			lsn = baseLsnOf(lsn) + segmentBytes;
			offsetInSegment = 0;
		}
		byte[] record = WalRecord.encode(lsn, prevLsn, txnId, type, payload);
		FileChannel target = channelFor(lsn);
		target.position(offsetInSegment);
		ByteBuffer buffer = ByteBuffer.wrap(record);
		while (buffer.hasRemaining()) {
			target.write(buffer);
		}
		nextLsn = lsn + length;
		dirty = true;
		counters.walAppended(length);
		return lsn;
	}

	/**
	 * Makes everything appended so far durable, subject to the configured sync mode. This is the commit point.
	 */
	public void sync() throws IOException {
		switch (syncMode) {
			case FSYNC_ON_COMMIT -> forceNow();
			case GROUP_COMMIT -> {
				long now = System.nanoTime();
				if (now - lastSyncNanos >= syncIntervalNanos) {
					lastSyncNanos = now;
					forceNow();
				}
			}
			case ASYNC -> {
				// Ordering still holds; only the fsync is skipped.
			}
		}
		durableLsn = nextLsn;
	}

	/** Forces regardless of the sync mode. Used by checkpoint and close. */
	public void forceNow() throws IOException {
		if (dirty && channel != null) {
			channel.force(true);
			dirty = false;
			counters.walSynced();
		}
		// Everything appended is on the device now, whatever the sync mode. Leaving durableLsn behind made the lag
		// gauge report a permanent backlog after every checkpoint, which is the one moment it should read zero.
		durableLsn = nextLsn;
	}

	/** Deletes every log segment entirely below the given LSN. */
	public void trim(long minLsn) throws IOException {
		long keepBase = baseLsnOf(minLsn);
		List<Path> stale = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(p -> p.getFileName().toString().startsWith("wal-")).forEach(p -> {
				String name = p.getFileName().toString();
				try {
					long base = Long.parseUnsignedLong(name.substring(4, name.indexOf('.')), 16);
					if (base < keepBase) {
						stale.add(p);
					}
				} catch (NumberFormatException e) {
					// Not one of ours; leave it alone.
				}
			});
		}
		for (Path path : stale) {
			Files.deleteIfExists(path);
			logger.debug("Trimmed WAL segment {}", path);
		}
	}

	@Override
	public void close() throws IOException {
		if (channel != null) {
			channel.force(true);
			channel.close();
			channel = null;
		}
	}
}
