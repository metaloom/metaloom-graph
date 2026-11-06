package io.metaloom.graph.core.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.graph.core.store.Manifest;

/**
 * The store format version, and a way to read it off a store without opening the store.
 *
 * <h2>One number for the whole layout</h2>
 *
 * <p>
 * {@link #CURRENT_VERSION} names every byte this build writes into a store directory: the manifest, the segment files
 * and every record in them, the dictionaries and the write-ahead log. It is recorded in the manifest, and it is what a
 * migration step is keyed on — a step takes a store from version N to N + 1. Several structures carry a version of
 * their own as well (a segment superblock, for one); those are local guards that a reader of that structure checks, and
 * a change to any of them is also a change to this number. The inventory, and the rule for when each one moves, is
 * spec/MIGRATION.md section 1.
 * </p>
 *
 * <h2>Why the alpha baseline is 4 and not 1</h2>
 *
 * <p>
 * The manifest field already said 3 or 4 on every store written before the number had this meaning, and both of
 * those stores have exactly the layout described as version 4. Calling it 1 would have meant either rewriting every
 * existing manifest or carrying a second version field beside the first, and in both cases a store on disk would say
 * something different from what its bytes are.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/MIGRATION.md">spec/MIGRATION.md</a>
 */
public final class StoreFormat {

	/**
	 * The store format version this build reads and writes. A store at any other version is refused by
	 * {@link GraphStore#open}: an older one with {@link MigrationRequiredException}, a newer one with
	 * {@link FormatVersionException}.
	 *
	 * <p>
	 * <b>Bumping this</b> is never a one-line change: it needs a migration step registered in
	 * {@code MigrationRegistry}, a new golden fixture under {@code graph-core/src/test/resources/format/}, and the older
	 * fixture kept. {@code GoldenFixtureTest} fails until all three are there. spec/MIGRATION.md section 6.
	 * </p>
	 */
	public static final int CURRENT_VERSION = 5;

	private StoreFormat() {
	}

	/**
	 * Reads a store's format version and geometry from its manifest without opening, locking or creating anything.
	 *
	 * <p>
	 * Works on a store of any version, which {@link GraphStore#open} by design does not, and on a store a server has
	 * open — it reads 8 KiB and takes no lock.
	 * </p>
	 *
	 * @throws GraphStoreException
	 *             if the directory holds no manifest
	 * @throws PageCorruptException
	 *             if both manifest copies are invalid
	 */
	public static Info inspect(Path store) throws IOException {
		Manifest.Header header = Manifest.read(store);
		return new Info(store, header.version(), header.versionField(), header.pageSize(), header.segmentCapacity(),
			header.vectorStride(), header.vectorDimensions(), header.featureFlags(), header.checkpointLsn(),
			header.storeUuid(), walSegmentBytes(store));
	}

	/**
	 * The size of the store's log segments, read off the files: they are preallocated to exactly that size, and the
	 * manifest does not record it. 0 when there is no log.
	 */
	private static long walSegmentBytes(Path store) throws IOException {
		Path dir = store.resolve("wal");
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> {
				String name = p.getFileName().toString();
				return name.startsWith("wal-") && name.endsWith(".log");
			}).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0;
				}
			}).max().orElse(0);
		}
	}

	/**
	 * What a store's manifest says about it.
	 *
	 * @param path
	 *            the store directory
	 * @param version
	 *            the store format version
	 * @param versionField
	 *            the raw manifest field, which differs from {@code version} only in the legacy encoding: 3 is version
	 *            4 in a store with no vector storage
	 * @param vectorStride
	 *            the {@code VEC} record size, 0 without vector storage
	 * @param featureFlags
	 *            the manifest's feature bits; bit 0 is vector storage
	 * @param checkpointLsn
	 *            where recovery would start; a cleanly closed store has nothing to replay past it
	 * @param walSegmentBytes
	 *            the size of the store's log segment files, 0 when it has none
	 */
	public record Info(Path path, int version, int versionField, int pageSize, long segmentCapacity, int vectorStride,
		int vectorDimensions, long featureFlags, long checkpointLsn, UUID storeUuid, long walSegmentBytes) {

		public boolean isCurrent() {
			return version == CURRENT_VERSION;
		}

		public boolean isOlder() {
			return version < CURRENT_VERSION;
		}

		public boolean isNewer() {
			return version > CURRENT_VERSION;
		}

		/**
		 * Options that open this store: its own page size, segment capacity and vector dimension, which are fixed at
		 * creation, and its log segment size. Everything else is the default.
		 */
		public GraphStoreOptions options() {
			GraphStoreOptions.Builder builder = GraphStoreOptions.builder()
				.pageSize(pageSize)
				.segmentCapacity(segmentCapacity)
				.vectorDimensions(vectorDimensions);
			if (walSegmentBytes > 0) {
				builder.walSegmentBytes(walSegmentBytes);
			}
			return builder.build();
		}

		/** The version as an operator should read it, with the legacy encoding explained where it applies. */
		public String describeVersion() {
			String relation = isCurrent() ? "current" : isOlder() ? "older than this build's " + CURRENT_VERSION
				: "newer than this build's " + CURRENT_VERSION;
			String encoding = versionField == version ? ""
				: "; written as " + versionField + ", the encoding of " + version + " without vector storage";
			return version + " (" + relation + encoding + ")";
		}
	}
}
