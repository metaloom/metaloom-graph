package io.metaloom.graph.core.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.MigrationRequiredException;
import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.StoreFormat;
import io.metaloom.graph.core.api.StoreMigration;
import io.metaloom.graph.core.fixture.StoreFiles;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.Manifest;

/**
 * The store format version as it is on disk: how it is encoded, what {@link GraphStore#open} does with each value,
 * and what {@link StoreFormat#inspect} reports without opening anything.
 */
@Tag("unit")
public class StoreFormatVersionTest {

	@TempDir
	Path root;

	private Path create(String name, GraphStoreOptions options) throws IOException {
		Path store = root.resolve(name);
		try (GraphStore graph = GraphStore.open(store, options)) {
			graph.createNode("Person", Properties.of().set("name", "a"));
		}
		return store;
	}

	private static GraphStoreOptions withVectors() {
		return GraphStoreOptions.builder().pageSize(TempStore.options().pageSize())
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY).vectorDimensions(4).build();
	}

	/** The raw field at offset 8 of the active copy. */
	private static int versionField(Path store) throws IOException {
		return Manifest.read(store).versionField();
	}

	/**
	 * From version 5 on the field is the version, for every store: the legacy encoding of version 4 — 3 for a store
	 * without vectors — ended with the bump (spec/MIGRATION.md section 2.2), and the field no longer says whether vectors
	 * are present.
	 */
	@Test
	public void testTheVersionIsWrittenAsItselfWithAndWithoutVectors() throws IOException {
		Path plain = create("plain", TempStore.options());
		Path vectors = create("vectors", withVectors());
		assertEquals(StoreFormat.CURRENT_VERSION, versionField(plain));
		assertEquals(StoreFormat.CURRENT_VERSION, versionField(vectors));
		StoreFormat.Info plainInfo = StoreFormat.inspect(plain);
		StoreFormat.Info vectorInfo = StoreFormat.inspect(vectors);
		assertEquals(StoreFormat.CURRENT_VERSION, plainInfo.version());
		assertEquals(StoreFormat.CURRENT_VERSION, vectorInfo.version());
		assertTrue(plainInfo.isCurrent() && vectorInfo.isCurrent());
		assertEquals(0, plainInfo.vectorStride());
		assertEquals(4, vectorInfo.vectorDimensions());
		assertEquals(Manifest.FEATURE_VECTORS, vectorInfo.featureFlags());
		assertFalse(plainInfo.describeVersion().contains("written as"), plainInfo.describeVersion());
		assertFalse(vectorInfo.describeVersion().contains("written as"), vectorInfo.describeVersion());
	}

	@Test
	public void testTheEncodingRoundTrips() {
		assertEquals(5, Manifest.encodeVersion(5), "from 5 on the field is the version");
		for (int version : new int[] { 1, 2, 5, 6, 100 }) {
			for (int stride : new int[] { 0, 512 }) {
				assertEquals(version, Manifest.decodeVersion(Manifest.encodeVersion(version), stride, root));
			}
		}
		// The legacy encoding of version 4 is still read, for as long as there is a step from 4.
		assertEquals(4, Manifest.decodeVersion(3, 0, root), "3 without vectors is version 4");
		assertEquals(4, Manifest.decodeVersion(4, 512, root), "4 with vectors is version 4");
		assertThrows(PageCorruptException.class, () -> Manifest.decodeVersion(3, 512, root),
			"3 claims no vectors, so a stride means one of the two is corrupt");
		assertThrows(PageCorruptException.class, () -> Manifest.decodeVersion(4, 0, root));
	}

	@Test
	public void testInspectReadsAStoreWithoutChangingIt() throws IOException {
		Path store = create("graph", TempStore.options());
		Map<String, String> before = StoreFiles.digest(store);
		StoreFormat.Info info = StoreFormat.inspect(store);
		assertEquals(TempStore.options().pageSize(), info.pageSize());
		assertEquals(TempStore.TEST_SEGMENT_CAPACITY, info.segmentCapacity());
		assertEquals(GraphStoreOptions.DEFAULT_WAL_SEGMENT_BYTES, info.walSegmentBytes());
		assertTrue(info.checkpointLsn() > 0);
		assertEquals(before, StoreFiles.digest(store));
		try (GraphStore graph = GraphStore.open(store, info.options())) {
			assertEquals(1, graph.nodesByLabel("Person").size(), "the options it reports open the store");
		}
	}

	@Test
	public void testInspectingADirectoryThatIsNotAStoreCreatesNothing() throws IOException {
		Path empty = Files.createDirectories(root.resolve("empty"));
		assertThrows(GraphStoreException.class, () -> StoreFormat.inspect(empty));
		assertEquals(List.of(), StoreFiles.list(empty));
	}

	/**
	 * An older store is refused before anything in it is read or written, with the exception that names what to do.
	 * Version 2 has no migration path in this build, so the message says how to move the data instead.
	 */
	@Test
	public void testAnOlderStoreIsRefusedWithATypedException() throws IOException {
		Path store = create("graph", TempStore.options());
		Manifest.stampVersion(store, 2);
		Map<String, String> before = StoreFiles.digest(store);
		MigrationRequiredException refused = assertThrows(MigrationRequiredException.class,
			() -> GraphStore.open(store, TempStore.options()).close());
		assertEquals(2, refused.storeVersion());
		assertEquals(StoreFormat.CURRENT_VERSION, refused.currentVersion());
		assertFalse(refused.migratable());
		assertTrue(refused.getMessage().contains("no migration path from version 2"), refused.getMessage());
		assertTrue(refused.getMessage().contains("database backup"), refused.getMessage());
		assertEquals(before, StoreFiles.digest(store), "the refused open changed nothing");
		assertTrue(StoreFormat.inspect(store).isOlder());

		StoreMigration.Plan plan = StoreMigration.standard().plan(store);
		assertFalse(plan.canProceed());
		assertTrue(plan.blockers().get(0).contains("no migration step from format version 2"),
			plan.blockers().toString());
	}

	/** The message for a store the build can migrate: the exact command, the dry run, and where the original goes. */
	@Test
	public void testTheMessageForAMigratableStoreNamesTheCommand() {
		Path store = root.resolve("sales");
		String message = new MigrationRequiredException(store, 4, 5, true).getMessage();
		assertTrue(message.contains("metaloom-graph-cli database migrate sales --path=" + root + " --dry-run"),
			message);
		assertTrue(message.contains("metaloom-graph-cli database migrate sales --path=" + root + "\n"), message);
		assertTrue(message.contains("sales.pre-migration-v4"), message);
	}

	@Test
	public void testANewerStoreIsRefusedWithoutADowngrade() throws IOException {
		Path store = create("graph", TempStore.options());
		Manifest.stampVersion(store, StoreFormat.CURRENT_VERSION + 1);
		FormatVersionException refused = assertThrows(FormatVersionException.class,
			() -> GraphStore.open(store, TempStore.options()).close());
		assertFalse(refused instanceof MigrationRequiredException);
		assertTrue(refused.getMessage().contains("newer than this build"), refused.getMessage());
		assertTrue(StoreFormat.inspect(store).isNewer());
		assertThrows(FormatVersionException.class, () -> StoreMigration.standard().migrate(store));
	}

	@Test
	public void testStampingAndRestampingLeavesAnOpenableStore() throws IOException {
		Path store = create("graph", withVectors());
		Manifest.stampVersion(store, 2);
		assertEquals(2, versionField(store));
		Manifest.stampVersion(store, StoreFormat.CURRENT_VERSION);
		assertEquals(StoreFormat.CURRENT_VERSION, versionField(store));
		try (GraphStore graph = GraphStore.open(store, withVectors())) {
			assertEquals(1, graph.nodesByLabel("Person").size());
		}
	}

	/** A feature bit this build does not know is refused by name rather than ignored. */
	@Test
	public void testAnUnknownFeatureFlagIsRefused() throws IOException {
		Path store = create("graph", TempStore.options());
		setFeatureFlags(store, 0x2);
		FormatVersionException refused = assertThrows(FormatVersionException.class,
			() -> GraphStore.open(store, TempStore.options()).close());
		assertTrue(refused.getMessage().contains("features this build does not know"), refused.getMessage());
	}

	/** Sets {@code feature_flags} (offset 80) in both copies and re-seals each CRC, as a newer build would. */
	private static void setFeatureFlags(Path store, long flags) throws IOException {
		try (RandomAccessFile file = new RandomAccessFile(store.resolve("MANIFEST").toFile(), "rw")) {
			byte[] bytes = new byte[8192];
			file.readFully(bytes);
			ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			for (int base : new int[] { 0, 4096 }) {
				buffer.putLong(base + 80, flags);
				CRC32C crc = new CRC32C();
				crc.update(bytes, base, 4092);
				buffer.putInt(base + 4092, (int) crc.getValue());
			}
			file.seek(0);
			file.write(bytes);
		}
	}

	@Test
	public void testTheStandardMigrationLeavesACurrentStoreAlone() throws IOException {
		Path store = create("graph", TempStore.options());
		Map<String, String> before = StoreFiles.digest(store);
		StoreMigration migration = StoreMigration.standard();
		assertEquals(StoreFormat.CURRENT_VERSION, migration.targetVersion());
		assertTrue(migration.plan(store).upToDate());
		assertFalse(migration.migrate(store).changed());
		assertEquals(before, StoreFiles.digest(store));
		assertEquals(List.of("graph"), StoreFiles.list(root));
	}
}
