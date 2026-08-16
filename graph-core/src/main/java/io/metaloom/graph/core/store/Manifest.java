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

	/**
	 * Written instead of {@link #FORMAT_VERSION} by a store that has vector storage, and accepted alongside it.
	 * <p>
	 * <b>The version is a function of the content, not of the build.</b> {@link #open} compares with {@code !=}, so a
	 * flat bump to 4 would refuse every store on disk — including the overwhelming majority that will never hold a
	 * vector — to describe a field they do not have. Writing 4 only when {@code vec_stride} is non-zero keeps those
	 * stores openable, and still refuses the one case that matters: a build without this code reading a store whose
	 * property blocks point at {@code VEC} records it cannot resolve.
	 */
	public static final int FORMAT_VERSION_VECTORS = 4;

	/** Bit 0 of {@code feature_flags}: this store has vector storage. */
	public static final long FEATURE_VECTORS = 1L;

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
	/**
	 * The vector geometry, added after the fields above. {@code flush} zero-fills the whole copy before writing it, so
	 * a manifest written by an older build reads zero here — which is exactly "no vector storage" and needs no
	 * migration.
	 */
	private static final int OFF_VEC_STRIDE = 72;
	private static final int OFF_VEC_DIMENSIONS = 76;
	/**
	 * Room for the next optional feature to be added without a version bump at all: a build that does not understand
	 * a flag can refuse on the flag rather than on the version number.
	 */
	private static final int OFF_FEATURE_FLAGS = 80;
	private static final int OFF_CRC = COPY_BYTES - 4;

	private final FileChannel channel;
	private final Arena arena;
	private final MemorySegment mapped;

	private int activeOffset;
	private long sequence;

	private final int pageSize;
	private final long segmentCapacity;
	private final UUID storeUuid;
	private final int vectorStride;
	private final int vectorDimensions;

	private long checkpointLsn;
	private long walMinLsn;
	private long nextTxnId = 1;

	private Manifest(FileChannel channel, Arena arena, MemorySegment mapped, int activeOffset, long sequence,
		int pageSize, long segmentCapacity, UUID storeUuid, int vectorStride, int vectorDimensions) {
		this.channel = channel;
		this.arena = arena;
		this.mapped = mapped;
		this.activeOffset = activeOffset;
		this.sequence = sequence;
		this.pageSize = pageSize;
		this.segmentCapacity = segmentCapacity;
		this.storeUuid = storeUuid;
		this.vectorStride = vectorStride;
		this.vectorDimensions = vectorDimensions;
	}

	/**
	 * Opens the manifest, creating it if absent. When it exists, the requested geometry is validated against the
	 * recorded one and a mismatch is refused rather than silently misread.
	 */
	public static Manifest open(Path storePath, int pageSize, long segmentCapacity) throws IOException {
		return open(storePath, pageSize, segmentCapacity, 0, 0);
	}

	/**
	 * @param vectorStride
	 *            the {@code VEC} record size for a store being created, or 0 for none. On an <b>existing</b> store the
	 *            recorded value wins when the caller asks for nothing, so a plain {@code open(path)} keeps working on a
	 *            store that has vectors; asking for a <em>different</em> non-zero value is refused, exactly as a
	 *            different page size is. The stride is fixed at creation because {@code Geometry} derives every
	 *            per-kind table from it, and because recovery needs the {@code VEC} segment set to exist before it can
	 *            replay a page image into it — which means the number has to be readable before any transaction is.
	 */
	public static Manifest open(Path storePath, int pageSize, long segmentCapacity, int vectorStride,
		int vectorDimensions) throws IOException {
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
				Manifest manifest = new Manifest(channel, arena, mapped, COPY_BYTES, 0, pageSize, segmentCapacity, uuid,
					vectorStride, vectorDimensions);
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
			if (version != FORMAT_VERSION && version != FORMAT_VERSION_VECTORS) {
				throw new FormatVersionException("Store format version " + version + ", this build supports "
					+ FORMAT_VERSION + " and " + FORMAT_VERSION_VECTORS);
			}
			int storedVecStride = mapped.get(Layouts.INT, active + OFF_VEC_STRIDE);
			int storedVecDimensions = mapped.get(Layouts.INT, active + OFF_VEC_DIMENSIONS);
			if ((version == FORMAT_VERSION) != (storedVecStride == 0)) {
				// The version is a function of the content, so the two disagreeing means one of them is corrupt — and
				// guessing which would be guessing whether the property blocks point at blobs or at vector records.
				throw new PageCorruptException("Manifest at " + path + " declares format version " + version
					+ " with a vector stride of " + storedVecStride + "; version " + FORMAT_VERSION
					+ " means no vector storage and " + FORMAT_VERSION_VECTORS + " means some");
			}
			// Both numbers, not just the stride. A stride is a power of two rounded up from a dimension, so a range of
			// dimensions shares one — 4 and 8 both land on 512. Checking only the stride would let a store created for
			// one be opened as the other, and every vector in it would then be read at the wrong length.
			if (vectorStride != 0 && (vectorStride != storedVecStride || vectorDimensions != storedVecDimensions)) {
				throw new FormatVersionException("Store was created for vectors of " + storedVecDimensions
					+ " dimensions but was opened for " + vectorDimensions
					+ ". The vector dimension is fixed at creation, like the page size.");
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
			Manifest manifest = new Manifest(channel, arena, mapped, active, seq, storedPageSize, storedCapacity, uuid,
				storedVecStride, storedVecDimensions);
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
		copy.set(Layouts.INT, OFF_FORMAT_VERSION, vectorStride == 0 ? FORMAT_VERSION : FORMAT_VERSION_VECTORS);
		copy.set(Layouts.INT, OFF_VEC_STRIDE, vectorStride);
		copy.set(Layouts.INT, OFF_VEC_DIMENSIONS, vectorDimensions);
		copy.set(Layouts.LONG, OFF_FEATURE_FLAGS, vectorStride == 0 ? 0L : FEATURE_VECTORS);
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
		return new Geometry(pageSize, segmentCapacity, vectorStride);
	}

	/** The {@code VEC} record size this store was created with, or 0 if it has no vector storage. */
	public int vectorStride() {
		return vectorStride;
	}

	/** The vector dimension this store was created with, or 0. Always {@code <= (vectorStride - 64) / 4}. */
	public int vectorDimensions() {
		return vectorDimensions;
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
