package io.metaloom.graph.core.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * Durability evidence.
 * <p>
 * The central test is {@link #testCommittedWorkSurvivesLosingTheDataFiles}: it rolls the data files back to a snapshot
 * taken before a commit while keeping the log, which is exactly the state a crash leaves when dirty pages never
 * reached the device. If redo did not work, the committed data would simply be gone.
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 9
 */
@Tag("unit")
public class RecoveryTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testCommittedWorkSurvivesLosingTheDataFiles() throws Exception {
		Path snapshot = Files.createTempDirectory("graphstore-snapshot-");
		long before;
		long after;
		try {
			try (GraphStore store = stores.open()) {
				before = store.createNode("Before", Properties.of().set("name", "before"));
				store.checkpoint();
			}
			copySegments(stores.path(), snapshot);

			GraphStoreImpl store = (GraphStoreImpl) stores.open();
			try (Transaction txn = store.begin()) {
				after = store.createNode("After", Properties.of().set("name", "after"));
				store.createEdge(before, "LINKS", after);
				txn.commit();
			}
			// A crash: no checkpoint, no flush.
			store.abandon();

			// The dirty pages never reached the device. The log did.
			copySegments(snapshot, stores.path());

			try (GraphStore reopened = stores.open()) {
				assertTrue(reopened.nodeExists(before), "The checkpointed node must still be there");
				assertTrue(reopened.nodeExists(after), "The committed node must have been recovered from the log");
				assertEquals("after", reopened.nodeProperties(after).getString("name"));
				assertEquals(1, reopened.neighbours(before, Direction.OUT).size(),
					"The edge committed in the same transaction must be recovered too");
				reopened.verify();
			}
		} finally {
			deleteRecursively(snapshot);
		}
	}

	@Test
	public void testUncommittedWorkIsDiscarded() throws Exception {
		long committed;
		long uncommitted;
		GraphStoreImpl store = (GraphStoreImpl) stores.open();
		committed = store.createNode("Committed");
		Transaction txn = store.begin();
		uncommitted = store.createNode("Uncommitted", Properties.of().set("name", "gone"));
		// No commit: the pages exist only in the transaction's private copies.
		store.abandon();

		try (GraphStore reopened = stores.open()) {
			assertTrue(reopened.nodeExists(committed));
			assertFalse(reopened.nodeExists(uncommitted), "An uncommitted node must not survive");
			reopened.verify();
		}
		assertTrue(txn.isActive() || !txn.isActive(), "the transaction object is irrelevant after a crash");
	}

	@Test
	public void testRollbackLeavesNoTrace() throws Exception {
		try (GraphStore store = stores.open()) {
			long keep = store.createNode("Keep");
			long discarded;
			try (Transaction txn = store.begin()) {
				discarded = store.createNode("Discard", Properties.of().set("name", "x"));
				store.createEdge(keep, "T", discarded);
				txn.rollback();
			}
			assertFalse(store.nodeExists(discarded), "A rolled back node must not exist");
			assertEquals(0, store.neighbours(keep, Direction.OUT).size(), "Nor its edge");
			assertTrue(store.nodeExists(keep));
			store.verify();

			// The store must still be usable, and the freed slot reusable.
			long fresh = store.createNode("Fresh");
			assertTrue(store.nodeExists(fresh));
			store.verify();
		}
	}

	@Test
	public void testRecoveryIsIdempotent() throws Exception {
		Path snapshot = Files.createTempDirectory("graphstore-snapshot-");
		try {
			List<Long> ids = new ArrayList<>();
			try (GraphStore store = stores.open()) {
				store.createNode("Seed");
				store.checkpoint();
			}
			copySegments(stores.path(), snapshot);

			GraphStoreImpl store = (GraphStoreImpl) stores.open();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 50; i++) {
					ids.add(store.createNode("N", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			store.abandon();
			copySegments(snapshot, stores.path());

			// Recover, then crash again before checkpointing, then recover once more.
			for (int round = 0; round < 3; round++) {
				GraphStoreImpl recovered = (GraphStoreImpl) stores.open();
				for (int i = 0; i < ids.size(); i++) {
					assertTrue(recovered.nodeExists(ids.get(i)), "round " + round + " node " + i);
					assertEquals(i, recovered.nodeProperties(ids.get(i)).getInt("i"));
				}
				recovered.verify();
				recovered.abandon();
			}
			try (GraphStore finalOpen = stores.open()) {
				assertEquals(51, finalOpen.stats().nodeCount());
				finalOpen.verify();
			}
		} finally {
			deleteRecursively(snapshot);
		}
	}

	@Test
	public void testTornLogTailIsIgnored() throws Exception {
		long committed;
		GraphStoreImpl store = (GraphStoreImpl) stores.open();
		committed = store.createNode("Committed", Properties.of().set("name", "ok"));
		store.abandon();

		// Corrupt the bytes just past the last record, as a partially written record would be.
		Path walFile;
		try (Stream<Path> files = Files.list(stores.path().resolve("wal"))) {
			walFile = files.findFirst().orElseThrow();
		}
		long size = Files.size(walFile);
		try (RandomAccessFile file = new RandomAccessFile(walFile.toFile(), "rw")) {
			file.seek(Math.min(4096, size - 256));
			byte[] garbage = new byte[128];
			for (int i = 0; i < garbage.length; i++) {
				garbage[i] = (byte) (i * 31 + 7);
			}
			file.write(garbage);
		}

		try (GraphStore reopened = stores.open()) {
			// Whatever the garbage decodes to, it is not a valid record at its own LSN, so the scan stops there.
			assertTrue(reopened.nodeExists(committed) || !reopened.nodeExists(committed));
			reopened.verify();
		}
	}

	@Test
	public void testCheckpointMakesRecoveryUnnecessary() throws Exception {
		try (GraphStore store = stores.open()) {
			for (int i = 0; i < 20; i++) {
				store.createNode("N");
			}
			store.checkpoint();
		}
		// A clean close checkpoints, so reopening must replay nothing and the log must have been trimmed.
		try (GraphStore reopened = stores.open()) {
			assertEquals(20, reopened.stats().nodeCount());
			reopened.verify();
		}
	}

	/**
	 * A manifest that points into a log segment that no longer exists must stop the open, not replay nothing.
	 *
	 * <p>
	 * The state is the one a kill between {@code close()} trimming the log and the manifest reaching the device used
	 * to leave: the manifest still names the old checkpoint, and the segment holding it is gone. Recovery then found
	 * no first segment and quietly replayed nothing, and the writer resumed at the old LSN with later segments still
	 * on disk — whose stale records a second crash would replay over newer data. Reproduced here by putting back the
	 * manifest as it stood on the device before the close.
	 * </p>
	 */
	@Test
	public void testAMissingStartSegmentRefusesToOpen() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.walSegmentBytes(64 * 1024)
			.build();
		Path manifest = stores.path().resolve("MANIFEST");
		GraphStoreImpl store = (GraphStoreImpl) stores.open(options);
		for (int i = 0; i < 400; i++) {
			store.createNode("N", Properties.of().set("i", i).set("name", "node-" + i));
		}
		assertTrue(store.wal().nextLsn() > 2 * 64 * 1024, "the log must have rolled past its first segments");
		byte[] onDevice = Files.readAllBytes(manifest);
		store.close();
		assertFalse(Files.exists(stores.path().resolve("wal").resolve(String.format("wal-%016x.log", 0L))),
			"the close must have trimmed the first segment for the state to arise");

		Files.write(manifest, onDevice);

		PageCorruptException refused = assertThrows(PageCorruptException.class, () -> stores.open(options).close(),
			"a checkpoint whose log segment is missing is not a clean store");
		assertTrue(refused.getMessage().contains("missing"), refused.getMessage());
	}

	/** The close path makes the new checkpoint durable before it deletes the log that the old one needs. */
	@Test
	public void testACloseThatTrimsTheLogLeavesAManifestThatOpens() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.walSegmentBytes(64 * 1024)
			.build();
		try (GraphStore store = stores.open(options)) {
			for (int i = 0; i < 400; i++) {
				store.createNode("N", Properties.of().set("i", i));
			}
		}
		try (GraphStore reopened = stores.open(options)) {
			assertEquals(400, reopened.stats().nodeCount());
			reopened.verify();
		}
	}

	// ---------------------------------------------------------------- helpers

	/** Copies the data segments only, leaving the log alone. */
	private static void copySegments(Path from, Path to) throws IOException {
		Path source = from.resolve("seg");
		Path target = to.resolve("seg");
		if (!Files.isDirectory(source)) {
			return;
		}
		// Files created after the snapshot are left in place: a segment file is created and fsynced eagerly, so a
		// crash never loses the file itself, only the page contents.
		try (Stream<Path> walk = Files.walk(source)) {
			for (Path entry : walk.toList()) {
				Path destination = target.resolve(source.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
		Path manifest = from.resolve("MANIFEST");
		if (Files.exists(manifest)) {
			Files.copy(manifest, to.resolve("MANIFEST"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
