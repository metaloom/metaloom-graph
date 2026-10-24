package io.metaloom.graph.core.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.GraphStoreOptions.IoEngineKind;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.io.DirectIoEngine;
import io.metaloom.graph.core.nativelib.NativeLib;
import io.metaloom.graph.core.store.GraphStoreImpl;
import io.metaloom.graph.core.wal.WalReader;
import io.metaloom.graph.core.wal.WalRecord;
import io.metaloom.graph.core.wal.WalWriter;

/**
 * The write-ahead log on the direct engine — capability N2 applied to the one file it was meant for.
 *
 * <p>
 * The log is written once and read only by recovery, so every byte of it that lands in the page cache evicts a byte
 * some reader wanted. Bypassing the cache for it is the textbook use of {@code O_DIRECT}. What makes it delicate is
 * alignment: records are neither 4,096 bytes long nor 4,096-byte aligned, so a commit that ends part way through a
 * block has to round up — and <b>the block it rounded over must never be written again</b>, or a torn write during a
 * crash would destroy a transaction the caller was already told was durable.
 * </p>
 *
 * <p>
 * These tests hold that rule to account: the padding parses, recovery ignores it, and a store written this way comes
 * back with exactly what was committed to it.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a>
 */
@Tag("unit")
public class DirectWalTest {

	@TempDir
	Path dir;

	private static GraphStoreOptions direct() {
		return GraphStoreOptions.builder()
			.walSyncMode(WalSyncMode.FSYNC_ON_COMMIT)
			.ioEngine(IoEngineKind.DIRECT)
			.build();
	}

	@Test
	public void testAStoreOnTheDirectLogReadsBackWhatItCommitted() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "direct IO is unavailable: " + NativeLib.describe());

		long[] ids = new long[400];
		try (GraphStore store = GraphStore.open(dir, direct())) {
			for (int i = 0; i < ids.length; i++) {
				// One commit each, so every one of them pads a block. This is the worst case for the design and the
				// case that matters: FSYNC_ON_COMMIT with small transactions.
				ids[i] = store.createNode("Person", Properties.of().set("i", i).set("name", "n" + i));
			}
			try (Transaction txn = store.begin()) {
				for (int i = 1; i < ids.length; i++) {
					store.createEdge(ids[i - 1], "KNOWS", ids[i]);
				}
				txn.commit();
			}
		}

		try (GraphStore store = GraphStore.open(dir, direct())) {
			assertEquals(ids.length, store.stats().nodeCount());
			for (int i = 0; i < ids.length; i++) {
				assertEquals((long) i, store.nodeProperty(ids[i], "i"), "node " + i + " lost its property");
			}
			for (int i = 1; i < ids.length; i++) {
				assertEquals(1, store.neighbours(ids[i - 1], Direction.OUT).size(), "edge " + i + " is missing");
			}
		}
	}

	/** Reopening with the buffered writer must find the same store, since only the writing differs. */
	@Test
	public void testTheTwoWritersProduceAnInterchangeableLog() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "direct IO is unavailable");

		long first;
		try (GraphStore store = GraphStore.open(dir, direct())) {
			first = store.createNode("Person", Properties.of().set("i", 1));
		}
		long second;
		try (GraphStore store = GraphStore.open(dir, GraphStoreOptions.defaults())) {
			assertEquals(1L, store.nodeProperty(first, "i"));
			second = store.createNode("Person", Properties.of().set("i", 2));
		}
		try (GraphStore store = GraphStore.open(dir, direct())) {
			assertEquals(1L, store.nodeProperty(first, "i"));
			assertEquals(2L, store.nodeProperty(second, "i"), "the buffered writer's records must survive");
			assertEquals(2, store.stats().nodeCount());
		}
	}

	/**
	 * Every record in the log parses, and the padding is a real record rather than a gap.
	 * <p>
	 * A gap would make an LSN ambiguous — it is a byte offset into the stream — so the filler has to carry a header
	 * and a CRC like everything else.
	 */
	@Test
	public void testPaddingIsAValidRecordAndTheStreamHasNoGaps() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "direct IO is unavailable");

		GraphStoreOptions options = direct();
		try (GraphStore store = GraphStore.open(dir, options)) {
			for (int i = 0; i < 50; i++) {
				store.createNode("Person", Properties.of().set("i", i));
			}
		}

		List<Integer> types = new ArrayList<>();
		long expectedLsn = 0;
		try (WalReader reader = new WalReader(dir, options.walSegmentBytes(), 0)) {
			byte[] record;
			while ((record = reader.next()) != null) {
				assertEquals(expectedLsn, WalRecord.lsn(record, 0),
					"the stream must be gapless: every record starts where the previous one ended");
				types.add(WalRecord.type(record, 0));
				expectedLsn += WalRecord.length(record, 0);
			}
		}

		assertTrue(types.contains(WalRecord.TYPE_PADDING),
			"fifty single-record commits must have produced alignment padding");
		assertTrue(types.contains(WalRecord.TYPE_TXN_COMMIT), "the commits themselves must be there");
		// Every block boundary crossed is one padding record, and each is a whole number of blocks.
		long paddings = types.stream().filter(t -> t == WalRecord.TYPE_PADDING).count();
		assertTrue(paddings >= 50, "expected one padding record per commit, found " + paddings);
	}

	/** The log file exists, is preallocated, and no commit extended it. */
	@Test
	public void testTheSegmentIsPreallocated() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "direct IO is unavailable");

		GraphStoreOptions options = direct();
		try (GraphStore store = GraphStore.open(dir, options)) {
			store.createNode("Person");
		}
		Path segment = dir.resolve("wal").resolve(String.format("wal-%016x.log", 0L));
		assertTrue(Files.exists(segment), "the first log segment must exist");
		assertEquals(options.walSegmentBytes(), Files.size(segment),
			"the segment must be preallocated to its full size, so appending never extends it");
	}

	/** A kill -9 in the middle leaves every acknowledged commit intact. */
	@Test
	public void testAnAbruptStopKeepsEveryAcknowledgedCommit() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "direct IO is unavailable");

		List<Long> committed = new ArrayList<>();
		GraphStoreImpl store = (GraphStoreImpl) GraphStore.open(dir, direct());
		for (int i = 0; i < 120; i++) {
			committed.add(store.createNode("Person", Properties.of().set("i", i)));
		}
		// abandon() drops the store without a checkpoint or a flush, which is the state a kill -9 leaves: the last
		// commit is fsynced into the log and nothing has been tidied up.
		store.abandon();

		try (GraphStore recovered = GraphStore.open(dir, direct())) {
			assertEquals(committed.size(), recovered.stats().nodeCount(),
				"every commit that returned must survive an abrupt stop");
			for (int i = 0; i < committed.size(); i++) {
				assertNotNull(recovered.nodeLabel(committed.get(i)));
				assertEquals((long) i, recovered.nodeProperty(committed.get(i), "i"));
			}
		}
	}

	/**
	 * Records staged for one segment must reach it when the stream rolls into the next.
	 *
	 * <p>
	 * The writer stages records and only writes when a buffer fills or a sync pads it out. Rolling over to a new
	 * segment used to drop whatever was staged for the old one, and the sync that followed then forced only the new
	 * file. Read before {@code close()}, because close flushes the current segment and would hide nothing — the loss
	 * is in the old one.
	 * </p>
	 */
	@Test
	public void testRecordsStagedBeforeASegmentRolloverReachTheDisk() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "direct IO is unavailable");

		long segment = 64 * 1024;
		int records = 100;
		try (WalWriter writer = new WalWriter(dir, segment, 0, WalSyncMode.FSYNC_ON_COMMIT, 0, IoEngineKind.DIRECT)) {
			for (int i = 0; i < records; i++) {
				// About 1 KiB each, so the stream crosses into the second segment around record 61.
				writer.append(0, 7, WalRecord.TYPE_PAGE_IMAGE, new byte[1024]);
			}
			writer.append(0, 7, WalRecord.TYPE_TXN_COMMIT, new byte[0]);
			writer.sync();

			int found = 0;
			try (WalReader reader = new WalReader(dir, segment, 0)) {
				byte[] record;
				while ((record = reader.next()) != null) {
					if (WalRecord.type(record, 0) != WalRecord.TYPE_PADDING) {
						found++;
					}
				}
			}
			assertEquals(records + 1, found, "every record acknowledged by sync() must be on disk, in either segment");
		}
	}

	/**
	 * Under {@code GROUP_COMMIT} a commit that returned is in the kernel's hands, even when its fsync is deferred.
	 *
	 * <p>
	 * The buffered writer gets this for free: every append is a {@code write()}, so a {@code kill -9} loses nothing
	 * that returned and only a power cut can take the unforced tail. The direct writer staged records in its own
	 * buffer until a force, so a process crash took every commit of the interval with it — while the data pages those
	 * commits had already been copied into, being a shared mapping, survived. A torn or unlogged transaction from a
	 * plain process kill. Read here without closing the writer, because close would flush the buffer.
	 * </p>
	 */
	@Test
	public void testGroupCommitHandsEveryCommitToTheDevice() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "direct IO is unavailable");

		long segment = 1L << 20;
		try (WalWriter writer = new WalWriter(dir, segment, 0, WalSyncMode.GROUP_COMMIT, 3_600_000,
			IoEngineKind.DIRECT)) {
			for (int i = 0; i < 10; i++) {
				writer.append(0, i, WalRecord.TYPE_TXN_COMMIT, new byte[100]);
				writer.sync();
			}
			int found = 0;
			try (WalReader reader = new WalReader(dir, segment, 0)) {
				byte[] record;
				while ((record = reader.next()) != null) {
					if (WalRecord.type(record, 0) != WalRecord.TYPE_PADDING) {
						found++;
					}
				}
			}
			assertEquals(10, found, "every commit that returned must have been written, if not yet forced");
			assertTrue(writer.durableLsn() < writer.nextLsn(), "and none of it may be called durable");
		}
	}

	/**
	 * One transaction whose records span a segment boundary is recovered whole.
	 *
	 * <p>
	 * The data files are rolled back to a snapshot taken before the transaction, so only the log can bring it back. A
	 * log missing the part staged in the first segment replays the second half alone: the transaction comes back
	 * torn, which is worse than not at all.
	 * </p>
	 */
	@Test
	public void testATransactionAcrossASegmentBoundaryIsRecoveredWhole() throws Exception {
		assumeTrue(DirectIoEngine.isSupported(), "direct IO is unavailable");

		GraphStoreOptions options = GraphStoreOptions.builder()
			.walSyncMode(WalSyncMode.FSYNC_ON_COMMIT)
			.ioEngine(IoEngineKind.DIRECT)
			.walSegmentBytes(64 * 1024)
			.segmentCapacity(io.metaloom.graph.core.fixture.TempStore.TEST_SEGMENT_CAPACITY)
			.build();
		Path snapshot = dir.resolve("snapshot");
		Path storeDir = dir.resolve("store");
		try (GraphStore store = GraphStore.open(storeDir, options)) {
			store.createNode("Seed");
		}
		copyDataFiles(storeDir, snapshot);

		List<Long> ids = new ArrayList<>();
		GraphStoreImpl store = (GraphStoreImpl) GraphStore.open(storeDir, options);
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 500; i++) {
				ids.add(store.createNode("Person", Properties.of().set("i", i).set("name", "person-" + i)));
			}
			txn.commit();
		}
		assertTrue(store.wal().nextLsn() > 64 * 1024, "the transaction must have crossed a segment boundary");
		store.abandon();
		copyDataFiles(snapshot, storeDir);

		try (GraphStore recovered = GraphStore.open(storeDir, options)) {
			assertEquals(ids.size() + 1, recovered.stats().nodeCount(), "the transaction must come back whole");
			for (int i = 0; i < ids.size(); i++) {
				assertEquals((long) i, recovered.nodeProperty(ids.get(i), "i"), "node " + i + " was torn");
			}
			recovered.verify();
		}
	}

	/** Copies the data segments and the manifest, leaving the log alone, as RecoveryTest does. */
	private static void copyDataFiles(Path from, Path to) throws java.io.IOException {
		Path source = from.resolve("seg");
		try (java.util.stream.Stream<Path> walk = Files.walk(source)) {
			for (Path entry : walk.toList()) {
				Path destination = to.resolve("seg").resolve(source.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(destination);
				} else {
					Files.copy(entry, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		Files.copy(from.resolve("MANIFEST"), to.resolve("MANIFEST"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
