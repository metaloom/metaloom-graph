package io.metaloom.graph.core.wal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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

import io.metaloom.graph.core.api.GraphStoreOptions.IoEngineKind;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.io.DirectIoEngine;
import io.metaloom.graph.core.io.IoEngine;
import io.metaloom.graph.core.metrics.IoCounters;
import io.metaloom.graph.core.nativelib.NativeLib;

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

	private final DirectLog direct;

	public WalWriter(Path storePath, long segmentBytes, long startLsn, WalSyncMode syncMode, long syncIntervalMillis)
		throws IOException {
		this(storePath, segmentBytes, startLsn, syncMode, syncIntervalMillis, IoEngineKind.MMAP);
	}

	public WalWriter(Path storePath, long segmentBytes, long startLsn, WalSyncMode syncMode, long syncIntervalMillis,
		IoEngineKind engine) throws IOException {
		this(storePath, segmentBytes, startLsn, syncMode, syncIntervalMillis, engine, null);
	}

	/**
	 * The same, with the engine supplied rather than chosen.
	 *
	 * <p>
	 * The seam the fault-injection tests write through: two of the six device faults in spec/TESTING.md section 6 are
	 * behaviours rather than disk states — a write that fails, and a write that succeeds followed by a failing fsync —
	 * so they can only be produced by an engine that misbehaves while the log is being written. This class is not
	 * exported by the module; the injection point is not public API.
	 * </p>
	 */
	public WalWriter(Path storePath, long segmentBytes, long startLsn, WalSyncMode syncMode, long syncIntervalMillis,
		IoEngineKind engine, IoEngine supplied) throws IOException {
		this.dir = storePath.resolve("wal");
		this.segmentBytes = segmentBytes;
		this.syncMode = syncMode;
		this.syncIntervalNanos = syncIntervalMillis * 1_000_000L;
		this.lastSyncNanos = System.nanoTime();
		Files.createDirectories(dir);
		this.nextLsn = startLsn;
		this.durableLsn = startLsn;
		this.direct = supplied != null || resolve(engine) ? new DirectLog(supplied) : null;
		if (direct != null) {
			logger.debug("The log is on the direct engine, block size {}", DirectLog.BLOCK);
		}
	}

	/**
	 * Whether the log should bypass the page cache.
	 * <p>
	 * {@code AUTO} resolves to buffered, not direct, and that is a measured choice rather than a conservative default:
	 * see {@link DirectLog} for what direct costs on this workload.
	 */
	private static boolean resolve(IoEngineKind engine) {
		return switch (engine) {
			case DIRECT -> DirectIoEngine.isSupported();
			case MMAP, AUTO -> false;
		};
	}

	/** True when this log is bypassing the page cache. */
	public boolean isDirect() {
		return direct != null;
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
		if (direct != null) {
			direct.append(lsn, record);
		} else {
			FileChannel target = channelFor(lsn);
			target.position(offsetInSegment);
			ByteBuffer buffer = ByteBuffer.wrap(record);
			while (buffer.hasRemaining()) {
				target.write(buffer);
			}
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
		if (direct != null) {
			if (dirty) {
				// Padding moves the stream on, so the next record starts in a block that has never been written.
				nextLsn = direct.flushAndPad(nextLsn);
				direct.force();
				dirty = false;
				counters.walSynced();
			}
		} else if (dirty && channel != null) {
			channel.force(true);
			dirty = false;
			counters.walSynced();
		}
		// Everything appended is on the device now, whatever the sync mode. Leaving durableLsn behind made the lag
		// gauge report a permanent backlog after every checkpoint, which is the one moment it should read zero.
		durableLsn = nextLsn;
	}

	/**
	 * The log written through {@code O_DIRECT}, in whole aligned blocks.
	 *
	 * <p>
	 * <b>The rule that shapes everything here: a partly filled block is never rewritten in place.</b> Once a block has
	 * been written and fsynced it is immutable. Appending more records into the free space at its end would mean
	 * re-issuing a write over bytes that a commit has already been acknowledged on, and a torn write of that block
	 * during a crash would destroy a transaction the caller was told was durable. So a flush rounds up: the tail of
	 * the block is filled with a {@link WalRecord#TYPE_PADDING} record and the write position moves to the next block.
	 * </p>
	 *
	 * <p>
	 * The padding is part of the logical stream, not a gap in it, because an LSN <em>is</em> a byte offset. A reader
	 * that met an unaccounted-for gap would have to guess; instead it meets a valid, CRC-covered record that redo
	 * ignores.
	 * </p>
	 *
	 * <p>
	 * <b>What it costs.</b> Up to one block of waste per fsync, which under {@code FSYNC_ON_COMMIT} means up to 4,095
	 * bytes per commit. That is why {@code AUTO} does not select it: bypassing the cache is worth it for a log nobody
	 * reads twice, and worth less than the write amplification it adds to a workload of many small commits. Choose it
	 * deliberately, with {@code GROUP_COMMIT}, or when the log is competing with the data path for page cache.
	 * </p>
	 */
	private final class DirectLog implements AutoCloseable {

		private static final int BLOCK = NativeLib.DIRECT_IO_ALIGNMENT;
		/** Staged bytes. Must hold the largest record whole; grows if one ever exceeds it. */
		private static final int INITIAL_CAPACITY = 64 * 1024;

		private final IoEngine engine;
		private Arena arena = Arena.ofShared();
		private MemorySegment buffer;
		private int capacity = INITIAL_CAPACITY;
		private IoEngine.FileHandle handle;
		private long handleBaseLsn = -1;

		/** File offset that byte 0 of the buffer will be written to. Always block aligned. */
		private long bufferOffset;
		/** Bytes staged in the buffer. */
		private int fill;

		DirectLog(IoEngine supplied) {
			this.engine = supplied != null ? supplied : new DirectIoEngine();
			this.buffer = arena.allocate(capacity, BLOCK);
		}

		/** Stages one record, flushing whole blocks when the buffer fills. */
		void append(long lsn, byte[] record) throws IOException {
			ensureHandle(lsn);
			long offsetInSegment = lsn % segmentBytes;
			if (fill == 0) {
				bufferOffset = offsetInSegment - offsetInSegment % BLOCK;
				int partial = (int) (offsetInSegment % BLOCK);
				if (partial > 0) {
					// Resuming into a block that already holds records. This happens once per open, after a crash left
					// the log ending mid-block — a clean close always pads to a boundary, and so does every flush.
					//
					// Those earlier bytes have to be read back before anything is staged on top of them, or the write
					// would lay a buffer full of zeros over committed records. This is the one read-modify-write of a
					// written block in the design, and it is why it is confined to a single block at open: every
					// subsequent block is written exactly once.
					handle.read(buffer, BLOCK, bufferOffset);
				}
				fill = partial;
			}
			if (fill + record.length > capacity) {
				flushWholeBlocks();
			}
			while (fill + record.length > capacity) {
				grow();
			}
			MemorySegment.copy(MemorySegment.ofArray(record), 0, buffer, fill, record.length);
			fill += record.length;
		}

		/**
		 * Writes every complete block that is staged, keeping the partial tail for the next append.
		 * <p>
		 * Used when the buffer runs out of room, where there is no need to pad: more records are coming, and the tail
		 * block has not been written yet so it is still free to be filled.
		 */
		private void flushWholeBlocks() throws IOException {
			int whole = fill / BLOCK * BLOCK;
			if (whole == 0) {
				return;
			}
			handle.write(buffer, whole, bufferOffset);
			int remainder = fill - whole;
			if (remainder > 0) {
				MemorySegment.copy(buffer, whole, buffer, 0, remainder);
			}
			bufferOffset += whole;
			fill = remainder;
		}

		/**
		 * Writes everything staged, padding the last block out, and returns the LSN the stream continues at.
		 * <p>
		 * After this the current block is immutable, so the caller's next record must start in the following one.
		 */
		long flushAndPad(long lsn) throws IOException {
			if (fill == 0) {
				return lsn;
			}
			int gap = (BLOCK - fill % BLOCK) % BLOCK;
			long continuation = lsn;
			if (gap > 0) {
				// A record cannot be shorter than its header, so a gap too small to hold one takes the whole next block
				// with it.
				int padLength = gap < WalRecord.HEADER_BYTES ? gap + BLOCK : gap;
				while (fill + padLength > capacity) {
					grow();
				}
				byte[] padding = WalRecord.encode(lsn, 0, 0, WalRecord.TYPE_PADDING,
					new byte[padLength - WalRecord.HEADER_BYTES]);
				MemorySegment.copy(MemorySegment.ofArray(padding), 0, buffer, fill, padLength);
				fill += padLength;
				continuation = lsn + padLength;
				counters.walAppended(padLength);
			}
			handle.write(buffer, fill, bufferOffset);
			bufferOffset += fill;
			fill = 0;
			return continuation;
		}

		void force() throws IOException {
			if (handle != null) {
				handle.force();
			}
		}

		private void grow() {
			int larger = capacity * 2;
			Arena replacement = Arena.ofShared();
			MemorySegment target = replacement.allocate(larger, BLOCK);
			MemorySegment.copy(buffer, 0, target, 0, fill);
			arena.close();
			arena = replacement;
			buffer = target;
			capacity = larger;
		}

		private void ensureHandle(long lsn) throws IOException {
			long base = baseLsnOf(lsn);
			if (handle != null && base == handleBaseLsn) {
				return;
			}
			if (handle != null) {
				handle.force();
				handle.close();
			}
			Path path = segmentPath(base);
			boolean fresh = !Files.exists(path);
			handle = engine.open(path, true);
			if (fresh) {
				// Preallocate, so that appending never extends the file and pays a metadata write per commit. One
				// aligned block at the far end is enough: the filesystem makes the rest a hole, exactly as the
				// buffered path's one-byte write does.
				MemorySegment zeros = arena.allocate(BLOCK, BLOCK);
				handle.write(zeros, BLOCK, segmentBytes - BLOCK);
				handle.force();
				logger.debug("Created WAL segment {} for direct IO", path);
			}
			handleBaseLsn = base;
			fill = 0;
		}

		@Override
		public void close() throws IOException {
			try {
				if (handle != null) {
					handle.force();
					handle.close();
					handle = null;
				}
			} finally {
				arena.close();
				engine.close();
			}
		}
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
		if (direct != null) {
			forceNow();
			direct.close();
		}
		if (channel != null) {
			channel.force(true);
			channel.close();
			channel = null;
		}
	}
}
