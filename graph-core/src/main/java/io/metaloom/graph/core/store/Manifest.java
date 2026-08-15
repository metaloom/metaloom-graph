package io.metaloom.graph.core.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.util.Crc;
import io.metaloom.graph.core.util.Layouts;

/**
 * Store level metadata, held in two 4 KiB copies that are flipped atomically. The flip is the commit point for
 * metadata: geometry, store identity and, once the log lands, the checkpoint and WAL retention positions.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 4
 */
public final class Manifest implements AutoCloseable {

	/**
	 * Bumped to 3 when a node gained the ability to carry several labels.
	 * <p>
	 * <b>No data migration.</b> The label set reuses the record's dead {@code version_ptr} and {@code xmin} bytes,
	 * which every version 2 store wrote as zero, so an old node decodes as exactly one label. The bump exists because
	 * the reverse is not true: a version 2 build reading a multi-label node would see its extra labels as an undo
	 * pointer and a commit timestamp.
	 */
	public static final int FORMAT_VERSION = 3;
	private static final long MAGIC = 0x004E414D5347_4C4DL;
	private static final int COPY_BYTES = 4096;

	private static final int OFF_MAGIC = 0;
	private static final int OFF_FORMAT_VERSION = 8;
	private static final int OFF_PAGE_SIZE = 12;
	private static final int OFF_SEGMENT_CAPACITY = 16;
	private static final int OFF_UUID_HI = 24;
	private static final int OFF_UUID_LO = 32;
	private static final int OFF_SEQUENCE = 40;
	private static final int OFF_CHECKPOINT_LSN = 48;
	private static final int OFF_WAL_MIN_LSN = 56;
	private static final int OFF_NEXT_TXN_ID = 64;
	private static final int OFF_CRC = COPY_BYTES - 4;

	private final FileChannel channel;
	private final Arena arena;
	private final MemorySegment mapped;

	private int activeOffset;
	private long sequence;

	private final int pageSize;
	private final long segmentCapacity;
	private final UUID storeUuid;

	private long checkpointLsn;
	private long walMinLsn;
	private long nextTxnId = 1;

	private Manifest(FileChannel channel, Arena arena, MemorySegment mapped, int activeOffset, long sequence,
		int pageSize, long segmentCapacity, UUID storeUuid) {
		this.channel = channel;
		this.arena = arena;
		this.mapped = mapped;
		this.activeOffset = activeOffset;
		this.sequence = sequence;
		this.pageSize = pageSize;
		this.segmentCapacity = segmentCapacity;
		this.storeUuid = storeUuid;
	}

	/**
	 * Opens the manifest, creating it if absent. When it exists, the requested geometry is validated against the
	 * recorded one and a mismatch is refused rather than silently misread.
	 */
	public static Manifest open(Path storePath, int pageSize, long segmentCapacity) throws IOException {
		Path path = storePath.resolve("MANIFEST");
		FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
			StandardOpenOption.WRITE);
		Arena arena = Arena.ofShared();
		try {
			boolean fresh = channel.size() < 2L * COPY_BYTES;
			if (fresh) {
				channel.truncate(0);
				channel.position(2L * COPY_BYTES - 1);
				channel.write(java.nio.ByteBuffer.wrap(new byte[1]));
			}
			MemorySegment mapped = channel.map(MapMode.READ_WRITE, 0, 2L * COPY_BYTES, arena);
			if (fresh) {
				UUID uuid = UUID.randomUUID();
				Manifest manifest = new Manifest(channel, arena, mapped, COPY_BYTES, 0, pageSize, segmentCapacity, uuid);
				manifest.flush();
				manifest.flush();
				return manifest;
			}

			boolean validA = isValid(mapped, 0);
			boolean validB = isValid(mapped, COPY_BYTES);
			if (!validA && !validB) {
				throw new PageCorruptException("Both manifest copies at " + path + " are invalid");
			}
			long seqA = validA ? mapped.get(Layouts.LONG, OFF_SEQUENCE) : Long.MIN_VALUE;
			long seqB = validB ? mapped.get(Layouts.LONG, COPY_BYTES + OFF_SEQUENCE) : Long.MIN_VALUE;
			int active = seqA >= seqB ? 0 : COPY_BYTES;

			int version = mapped.get(Layouts.INT, active + OFF_FORMAT_VERSION);
			if (version != FORMAT_VERSION) {
				throw new FormatVersionException("Store format version " + version + ", this build supports " + FORMAT_VERSION);
			}
			int storedPageSize = mapped.get(Layouts.INT, active + OFF_PAGE_SIZE);
			long storedCapacity = mapped.get(Layouts.LONG, active + OFF_SEGMENT_CAPACITY);
			if (storedPageSize != pageSize) {
				throw new FormatVersionException("Store was created with page size " + storedPageSize
					+ " but was opened with " + pageSize + ". The page size is fixed at creation.");
			}
			if (storedCapacity != segmentCapacity) {
				throw new FormatVersionException("Store was created with segment capacity " + storedCapacity
					+ " but was opened with " + segmentCapacity);
			}
			UUID uuid = new UUID(mapped.get(Layouts.LONG, active + OFF_UUID_HI),
				mapped.get(Layouts.LONG, active + OFF_UUID_LO));
			long seq = mapped.get(Layouts.LONG, active + OFF_SEQUENCE);
			Manifest manifest = new Manifest(channel, arena, mapped, active, seq, storedPageSize, storedCapacity, uuid);
			manifest.checkpointLsn = mapped.get(Layouts.LONG, active + OFF_CHECKPOINT_LSN);
			manifest.walMinLsn = mapped.get(Layouts.LONG, active + OFF_WAL_MIN_LSN);
			manifest.nextTxnId = Math.max(1, mapped.get(Layouts.LONG, active + OFF_NEXT_TXN_ID));
			return manifest;
		} catch (RuntimeException | IOException e) {
			arena.close();
			channel.close();
			throw e;
		}
	}

	private static boolean isValid(MemorySegment mapped, int base) {
		if (mapped.get(Layouts.LONG, base + OFF_MAGIC) != MAGIC) {
			return false;
		}
		return mapped.get(Layouts.INT, base + OFF_CRC) == Crc.crc32c(mapped, base, OFF_CRC);
	}

	/** Writes the current state into the inactive copy and flips to it. */
	public void flush() {
		int target = activeOffset == 0 ? COPY_BYTES : 0;
		MemorySegment copy = mapped.asSlice(target, COPY_BYTES);
		copy.fill((byte) 0);
		copy.set(Layouts.LONG, OFF_MAGIC, MAGIC);
		copy.set(Layouts.INT, OFF_FORMAT_VERSION, FORMAT_VERSION);
		copy.set(Layouts.INT, OFF_PAGE_SIZE, pageSize);
		copy.set(Layouts.LONG, OFF_SEGMENT_CAPACITY, segmentCapacity);
		copy.set(Layouts.LONG, OFF_UUID_HI, storeUuid.getMostSignificantBits());
		copy.set(Layouts.LONG, OFF_UUID_LO, storeUuid.getLeastSignificantBits());
		copy.set(Layouts.LONG, OFF_SEQUENCE, ++sequence);
		copy.set(Layouts.LONG, OFF_CHECKPOINT_LSN, checkpointLsn);
		copy.set(Layouts.LONG, OFF_WAL_MIN_LSN, walMinLsn);
		copy.set(Layouts.LONG, OFF_NEXT_TXN_ID, nextTxnId);
		copy.set(Layouts.INT, OFF_CRC, Crc.crc32c(copy, 0, OFF_CRC));
		activeOffset = target;
	}

	public Geometry geometry() {
		return new Geometry(pageSize, segmentCapacity);
	}

	public UUID storeUuid() {
		return storeUuid;
	}

	public long checkpointLsn() {
		return checkpointLsn;
	}

	public long walMinLsn() {
		return walMinLsn;
	}

	public long nextTxnId() {
		return nextTxnId;
	}

	public void checkpoint(long checkpointLsn, long walMinLsn, long nextTxnId) {
		this.checkpointLsn = checkpointLsn;
		this.walMinLsn = walMinLsn;
		this.nextTxnId = nextTxnId;
	}

	public long sequence() {
		return sequence;
	}

	public void force() throws IOException {
		flush();
		mapped.force();
		channel.force(true);
	}

	/** Releases the mapping without flushing, as a crash would. */
	public void abandon() throws IOException {
		arena.close();
		channel.close();
	}

	@Override
	public void close() throws IOException {
		try {
			force();
		} finally {
			arena.close();
			channel.close();
		}
	}
}
