package io.metaloom.graph.core.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.MigrationRequiredException;
import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.api.StoreFormat;
import io.metaloom.graph.core.migration.MigrationRegistry;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.util.Crc;
import io.metaloom.graph.core.util.Layouts;

/**
 * Store level metadata, held in two 4 KiB copies that are flipped atomically. The flip is the commit point for
 * metadata: geometry, store identity and, once the log lands, the checkpoint and WAL retention positions.
 * <p>
 * It is also where the <b>store format version</b> lives — the one number that names the whole on-disk layout and that
 * a migration step is keyed on ({@link StoreFormat#CURRENT_VERSION}). {@link #open} accepts exactly the current
 * version; {@link #read} and {@link #stampVersion} exist for the migration runner, which has to work on stores this
 * build cannot open.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 4
 * @see <a href="../../../../../../../../spec/MIGRATION.md">spec/MIGRATION.md</a>
 */
public final class Manifest implements AutoCloseable {

	/**
	 * The legacy encoding of store format version 4 in a store <b>without</b> vector storage.
	 * <p>
	 * Before the store format version had a definition of its own, this field was bumped per feature: 2 for the
	 * cell-based adjacency block, 3 for multi-label nodes, and 4 — written <em>only</em> by a store that has vector
	 * storage — for the {@code VEC} kind. So the field said two things at once, the layout and whether vectors were
	 * present. Both 3 and 4 describe the same layout, which is store format version 4; the vector question is answered
	 * by {@code vec_stride} and {@link #FEATURE_VECTORS}. While {@link StoreFormat#CURRENT_VERSION} was 4, a store with no
	 * vectors kept being written as 3 so that a build from before this definition still opened it. From version 5 on
	 * the field is the version and nothing else; 3 and 4 are only read, as the version 4 a migration starts from. See
	 * spec/MIGRATION.md section 2.
	 */
	static final int LEGACY_VERSION_NO_VECTORS = 3;

	/** The legacy encoding of store format version 4 in a store with vector storage. Also the version itself. */
	static final int LEGACY_VERSION_VECTORS = 4;

	/** Bit 0 of {@code feature_flags}: this store has vector storage. */
	public static final long FEATURE_VECTORS = 1L;

	/**
	 * Every feature flag this build understands. A store carrying any other bit is refused by name: a flag is how an
	 * optional feature that an older build must not touch is added <em>without</em> a version bump.
	 */
	static final long KNOWN_FEATURES = FEATURE_VECTORS;

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

			int active = activeCopy(mapped, path);
			int field = mapped.get(Layouts.INT, active + OFF_FORMAT_VERSION);
			int storedVecStride = mapped.get(Layouts.INT, active + OFF_VEC_STRIDE);
			int storedVecDimensions = mapped.get(Layouts.INT, active + OFF_VEC_DIMENSIONS);
			int version = decodeVersion(field, storedVecStride, path);
			requireReadable(storePath, version);
			long features = mapped.get(Layouts.LONG, active + OFF_FEATURE_FLAGS);
			requireKnownFeatures(path, features);
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

	// ---------------------------------------------------------------- the store format version

	/**
	 * The store format version a manifest's {@code format_version} field stands for.
	 *
	 * <p>
	 * 1 and 2 were real, older layouts and decode as themselves. 3 and 4 are the two legacy encodings of version 4
	 * (see {@link #LEGACY_VERSION_NO_VECTORS}), and they have to agree with the vector stride — the two disagreeing
	 * means one of them is corrupt, and guessing which would be guessing whether the property blocks point at blobs or
	 * at vector records. From 5 on the field is the version.
	 * </p>
	 */
	public static int decodeVersion(int field, int vectorStride, Path where) {
		if (field == LEGACY_VERSION_NO_VECTORS || field == LEGACY_VERSION_VECTORS) {
			if ((field == LEGACY_VERSION_NO_VECTORS) != (vectorStride == 0)) {
				throw new PageCorruptException("Manifest at " + where + " declares format version " + field
					+ " with a vector stride of " + vectorStride + "; " + LEGACY_VERSION_NO_VECTORS
					+ " means no vector storage and " + LEGACY_VERSION_VECTORS + " means some");
			}
			return LEGACY_VERSION_VECTORS;
		}
		return field;
	}

	/**
	 * The {@code format_version} field to write for a store format version: the version itself, for every store.
	 *
	 * <p>
	 * Until version 4 was current, a store without vectors was written as {@link #LEGACY_VERSION_NO_VECTORS} so that
	 * builds from before the version had a definition could still open it. That ended with version 5, which no such
	 * build accepts anyway. {@link #decodeVersion} keeps reading 3 and 4 as version 4 for as long as a migration step
	 * from 4 exists. See spec/MIGRATION.md section 2.2.
	 * </p>
	 */
	public static int encodeVersion(int version) {
		return version;
	}

	/**
	 * Refuses a store this build cannot read, by the version alone, before anything else in the manifest is trusted.
	 *
	 * <p>
	 * Older is a {@link MigrationRequiredException} naming the command that fixes it, and never an upgrade on the
	 * spot: a migration copies the store, needs room for that copy and keeps the original beside it, none of which is
	 * a side effect a server start should have. Newer is refused outright, because there is no downgrade.
	 * </p>
	 */
	private static void requireReadable(Path storePath, int version) {
		if (version > StoreFormat.CURRENT_VERSION) {
			throw new FormatVersionException("Store at " + storePath + " has format version " + version
				+ ", which is newer than this build (it reads version " + StoreFormat.CURRENT_VERSION
				+ "). Open it with the build that wrote it, or a newer one. There is no downgrade; a dump taken with "
				+ "that build ('metaloom-graph-cli database backup') can be restored into an older one.");
		}
		if (version < StoreFormat.CURRENT_VERSION) {
			throw new MigrationRequiredException(storePath, version, StoreFormat.CURRENT_VERSION,
				MigrationRegistry.standard().hasPath(version));
		}
	}

	private static void requireKnownFeatures(Path where, long features) {
		long unknown = features & ~KNOWN_FEATURES;
		if (unknown != 0) {
			throw new FormatVersionException("Store at " + where.getParent() + " uses features this build does not know"
				+ " (feature flags 0x" + Long.toHexString(unknown) + "). It was written by a newer build; open it with"
				+ " that one.");
		}
	}

	/**
	 * The active copy of a manifest, read without creating, mapping or locking anything.
	 *
	 * <p>
	 * For the questions that must be answerable about a store this build cannot open: which version it is, what its
	 * geometry is, where its checkpoint is. {@link #open} creates a manifest when there is none and refuses any version
	 * but the current one, so it can answer neither. Only bytes 0..11 and the CRC at 4092 are read before the version
	 * is known, and those positions are frozen across every version (spec/MIGRATION.md section 2.3); everything else is
	 * decoded with this build's layout and is meaningful only when the version is one this build understands.
	 * </p>
	 *
	 * @throws GraphStoreException
	 *             if there is no manifest, which means the directory is not a store
	 * @throws PageCorruptException
	 *             if neither copy is valid
	 */
	public static Header read(Path storePath) throws IOException {
		Path path = storePath.resolve("MANIFEST");
		if (!Files.isRegularFile(path)) {
			throw new GraphStoreException("No store at " + storePath + ": there is no MANIFEST");
		}
		byte[] bytes;
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			if (channel.size() < 2L * COPY_BYTES) {
				throw new PageCorruptException("Manifest at " + path + " is " + channel.size() + " bytes, not "
					+ 2 * COPY_BYTES);
			}
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2 * COPY_BYTES);
			while (buffer.hasRemaining() && channel.read(buffer, buffer.position()) > 0) {
				// read until full
			}
			bytes = buffer.array();
		}
		MemorySegment mapped = MemorySegment.ofArray(bytes);
		int active = activeCopy(mapped, path);
		int field = mapped.get(Layouts.INT, active + OFF_FORMAT_VERSION);
		int vecStride = mapped.get(Layouts.INT, active + OFF_VEC_STRIDE);
		return new Header(field, decodeVersion(field, vecStride, path), mapped.get(Layouts.INT, active + OFF_PAGE_SIZE),
			mapped.get(Layouts.LONG, active + OFF_SEGMENT_CAPACITY),
			new UUID(mapped.get(Layouts.LONG, active + OFF_UUID_HI), mapped.get(Layouts.LONG, active + OFF_UUID_LO)),
			mapped.get(Layouts.LONG, active + OFF_SEQUENCE), mapped.get(Layouts.LONG, active + OFF_CHECKPOINT_LSN),
			mapped.get(Layouts.LONG, active + OFF_WAL_MIN_LSN), mapped.get(Layouts.LONG, active + OFF_NEXT_TXN_ID),
			vecStride, mapped.get(Layouts.INT, active + OFF_VEC_DIMENSIONS),
			mapped.get(Layouts.LONG, active + OFF_FEATURE_FLAGS));
	}

	/**
	 * Rewrites the store format version of a closed store, in both copies, each with its CRC.
	 *
	 * <p>
	 * The migration runner's last act after each step, and the only write to a manifest outside {@link #flush}. Both
	 * copies rather than the active one, so that a copy which later wins the A/B choice cannot carry the old version;
	 * it runs on the migration's own working copy, never on a store anything has open, so a torn write here costs a
	 * rerun of the migration rather than a store.
	 * </p>
	 */
	public static void stampVersion(Path storePath, int version) throws IOException {
		Path path = storePath.resolve("MANIFEST");
		Header header = read(storePath);
		int field = encodeVersion(version);
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2 * COPY_BYTES);
			while (buffer.hasRemaining() && channel.read(buffer, buffer.position()) > 0) {
				// read until full
			}
			MemorySegment copies = MemorySegment.ofArray(buffer.array());
			for (int base : new int[] { 0, COPY_BYTES }) {
				if (!isValid(copies, base)) {
					continue;
				}
				copies.set(Layouts.INT, base + OFF_FORMAT_VERSION, field);
				copies.set(Layouts.INT, base + OFF_CRC, Crc.crc32c(copies, base, OFF_CRC));
			}
			channel.write(java.nio.ByteBuffer.wrap(buffer.array()), 0);
			channel.force(true);
		}
	}

	private static int activeCopy(MemorySegment mapped, Path path) {
		boolean validA = isValid(mapped, 0);
		boolean validB = isValid(mapped, COPY_BYTES);
		if (!validA && !validB) {
			throw new PageCorruptException("Both manifest copies at " + path + " are invalid");
		}
		long seqA = validA ? mapped.get(Layouts.LONG, OFF_SEQUENCE) : Long.MIN_VALUE;
		long seqB = validB ? mapped.get(Layouts.LONG, COPY_BYTES + OFF_SEQUENCE) : Long.MIN_VALUE;
		return seqA >= seqB ? 0 : COPY_BYTES;
	}

	/**
	 * The fields of a manifest's active copy, as {@link #read} found them.
	 *
	 * @param versionField
	 *            the raw {@code format_version} field
	 * @param version
	 *            the store format version it encodes, see {@link #decodeVersion}
	 */
	public record Header(int versionField, int version, int pageSize, long segmentCapacity, UUID storeUuid,
		long sequence, long checkpointLsn, long walMinLsn, long nextTxnId, int vectorStride, int vectorDimensions,
		long featureFlags) {
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
		copy.set(Layouts.INT, OFF_FORMAT_VERSION, encodeVersion(StoreFormat.CURRENT_VERSION));
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

	/** The store format version of this store, which an open manifest guarantees is the current one. */
	public int formatVersion() {
		return StoreFormat.CURRENT_VERSION;
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
