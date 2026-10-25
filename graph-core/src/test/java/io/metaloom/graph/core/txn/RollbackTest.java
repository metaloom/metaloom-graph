package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * {@code RollbackTest} of [TRANSACTIONS.md §9](../../../../../../../../spec/TRANSACTIONS.md): perform every mutation
 * type, roll back, and assert the store is <b>byte-identical</b> to before.
 *
 * <p>
 * The assertion is deliberately made on the files rather than on the answers the API gives, and without a flush in
 * between. Under the no-steal design a rolled-back transaction should never have touched a data file at all, so the
 * bytes on disk are the strongest available statement of that: not "the store behaves as though nothing happened" but
 * "nothing happened". A logical comparison would pass just as well against an engine that wrote and then carefully
 * undid, which is the design this one exists to avoid.
 * </p>
 */
@Tag("unit")
public class RollbackTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testEveryMutationTypeRollsBackToTheSameBytes() throws Exception {
		try (GraphStore store = stores.open()) {
			// A baseline with something of every kind in it, so the rollback below has real state to disturb.
			long alice = store.createNode("Person", Properties.of().set("name", "alice").set("age", 30L));
			long bob = store.createNode("Person", Properties.of().set("name", "bob"));
			long hub = store.createNode("Hub");
			long rel = store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2020L));
			store.createEdge(alice, "LIKES", bob);
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 300; i++) {
					store.createEdge(hub, "LINK", store.createNode("Leaf"));
				}
				txn.commit();
			}
			// A blob in the baseline, so that every segment kind already has a file. A kind touched for the first
			// time inside the rolled-back transaction would have its segment created and forced immediately — see
			// SegmentSet.addSegment, which does that deliberately, because a segment whose header never reached disk
			// would fail to open after a crash. That empty file is the one trace a rollback can leave, and it is a
			// property of first touch rather than of rollback.
			byte[] seed = new byte[40_000];
			new java.util.Random(99).nextBytes(seed);
			store.setNodeProperties(hub, Properties.of().set("seed", seed));
			store.createUniqueConstraint("name");
			store.checkpoint();

			String before = digestSegments(stores.path());
			long nodesBefore = store.stats().nodeCount();

			try (Transaction txn = store.begin()) {
				// Create, of every kind.
				long fresh = store.createNode("Temp", Properties.of().set("name", "temp"));
				store.createRelationship(fresh, "TO", alice);
				store.createEdge(fresh, "ALSO", bob);
				for (int i = 0; i < 300; i++) {
					store.createEdge(hub, "LINK", store.createNode("More"));
				}
				// Update.
				store.setNodeProperties(alice, Properties.of().set("name", "alice2").set("age", 31L));
				store.setRelationshipProperties(rel, Properties.of().set("since", 2021L));
				// Delete, including the cascade through adjacency and properties.
				store.deleteEdge(alice, "LIKES", bob);
				store.deleteNode(bob);
				// A blob, which lands in its own segment kind.
				byte[] payload = new byte[40_000];
				new java.util.Random(1).nextBytes(payload);
				store.setNodeProperties(fresh, Properties.of().set("blob", payload));

				txn.rollback();
			}

			assertEquals(before, digestSegments(stores.path()),
				"a rolled-back transaction must not have written a single byte to a data file");
			assertEquals(nodesBefore, store.stats().nodeCount());

			// And the store is not merely unchanged on disk, it is still coherent in memory.
			assertEquals("alice", store.nodeProperty(alice, "name"));
			assertEquals(30L, store.nodeProperty(alice, "age"));
			assertTrue(store.nodeExists(bob), "a rolled-back delete must leave the node alive");
			assertEquals(2020L, store.relationshipProperties(rel).get("since"));
			assertEquals(2, store.neighbours(alice, Direction.OUT).size(), "the rolled-back edge delete too");
			assertEquals(300, store.degreeOut(hub));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testRollbackReleasesTheWriterForTheNextTransaction() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("Doomed");
				txn.rollback();
			}
			long survivor = store.createNode("Survivor");
			assertTrue(store.nodeExists(survivor));
			assertEquals(1, store.stats().nodeCount(), "only the committed node may exist");
		}
	}

	@Test
	public void testAnUnclosedTransactionRollsBackOnClose() throws Exception {
		try (GraphStore store = stores.open()) {
			long before = store.stats().nodeCount();
			try (Transaction txn = store.begin()) {
				store.createNode("Abandoned");
				// No commit and no explicit rollback: close() must do it.
			}
			assertEquals(before, store.stats().nodeCount(),
				"a transaction that falls out of try-with-resources uncommitted must not persist");
		}
	}

	/**
	 * Repeated rollbacks must not ratchet the allocator forward.
	 *
	 * <p>
	 * The high water mark is what every full scan and every space figure is bounded by, and it is held in a Java field
	 * that {@code allocate()} advances eagerly. If a rollback failed to restore it, each attempt would abandon its
	 * slots above the mark and the store would grow in proportion to work it did not do — a leak that no counter would
	 * report, because the live count would stay correct.
	 * </p>
	 *
	 * <p>
	 * Measured after the first round rather than before it, because the first round legitimately creates the
	 * {@code prop} segment file: 131,146 bytes to 204,889 on the first attempt, and then 204,889 for the next
	 * nineteen. It is first touch that costs, not rollback.
	 * </p>
	 */
	@Test
	public void testRepeatedRollbacksDoNotRatchetTheAllocator() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Anchor");
			store.checkpoint();

			long afterFirstRound = 0;
			for (int round = 0; round < 20; round++) {
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < 100; i++) {
						store.createNode("Doomed", Properties.of().set("i", i));
					}
					txn.rollback();
				}
				if (round == 0) {
					afterFirstRound = store.stats().storageBytes();
				}
			}

			assertEquals(afterFirstRound, store.stats().storageBytes(),
				"nineteen further rounds of a hundred rolled-back allocations must cost nothing");
			assertEquals(1, store.stats().nodeCount(), "and none of the two thousand nodes may exist");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The one trace a rollback can leave: a segment file for a kind it was the first to touch.
	 *
	 * <p>
	 * Recorded rather than treated as a defect. The file is empty, valid and reused by the next transaction, and the
	 * alternative — creating segment files lazily without forcing their headers — trades it for a store that fails to
	 * open after a crash.
	 * </p>
	 */
	@Test
	public void testAFirstTouchLeavesAnEmptySegmentBehind() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Anchor");
			store.checkpoint();
			assertFalse(Files.exists(stores.path().resolve("seg/prop/prop-000000.seg")),
				"no property has been written yet");

			try (Transaction txn = store.begin()) {
				store.createNode("Doomed", Properties.of().set("i", 1));
				txn.rollback();
			}

			assertTrue(Files.exists(stores.path().resolve("seg/prop/prop-000000.seg")),
				"the segment file survives the rollback, by design");
			assertEquals(1, store.stats().nodeCount(), "but it holds nothing");
			assertEquals(0, store.stats().propertyCellCount());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * Rollback with a read view open: the view must be unaffected, because a rollback is not a commit and has no LSN.
	 */
	@Test
	public void testRollbackDoesNotDisturbAnOpenView() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("n", 0L));

			try (io.metaloom.graph.core.api.ReadTransaction view = store.beginRead()) {
				long retainedBefore = store.snapshotStats().retainedBytes();
				try (Transaction txn = store.begin()) {
					store.setNodeProperties(node, Properties.of().set("n", 99L));
					txn.rollback();
				}
				assertEquals(retainedBefore, store.snapshotStats().retainedBytes(),
					"a rollback must not ask the view to retain anything, because nothing was superseded");
				assertEquals(0L, store.nodeProperty(node, "n"));
			}
			assertEquals(0L, store.nodeProperty(node, "n"), "and the value never changed for anyone else either");
		}
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * A digest over every segment file's bytes.
	 * <p>
	 * The segment directory only: the log and the manifest legitimately move when a checkpoint runs, and this test is
	 * about the data files.
	 */
	static String digestSegments(Path storeDir) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		Path segments = storeDir.resolve("seg");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(segments)) {
			walk.filter(Files::isRegularFile).sorted().forEach(files::add);
		}
		for (Path file : files) {
			digest.update(file.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	@Test
	public void testTheDigestActuallyNoticesAChange() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Anchor");
			store.checkpoint();
			String before = digestSegments(stores.path());

			store.createNode("Committed");
			store.checkpoint();

			assertFalse(before.equals(digestSegments(stores.path())),
				"a committed write must change the files, or the assertion above proves nothing");
		}
	}

	/**
	 * A rolled-back run allocation that had to skip a page tail must leave the high water mark where it found it.
	 * The mark was recorded for the rollback after the skip, so a rollback with no other writer put it back to the
	 * skipped position instead — a state a crash at the same point would not have produced.
	 */
	@Test
	public void testARolledBackRunThatSkippedAPageTailRestoresTheMark() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			store.createNode("Small", Properties.of().set("a", 1L));
			io.metaloom.graph.core.segment.SegmentFile props = impl.segments(io.metaloom.graph.core.api.Kind.PROP)
				.segments().get(0);
			int cellsPerPage = impl.geometry().recordsPerPage(io.metaloom.graph.core.api.Kind.PROP);
			// Single-cell blocks until the next multi-cell block can no longer fit in the current page.
			while (cellsPerPage - props.superblock().highWaterSlot() % cellsPerPage > 2) {
				store.createNode("Small", Properties.of().set("a", 1L));
			}
			long before = props.superblock().highWaterSlot();
			assertTrue(before % cellsPerPage != 0, "the mark must sit inside a page for the tail to be skipped");

			try (Transaction txn = store.begin()) {
				store.createNode("Large", Properties.of().set("text", "x".repeat(200)));
				assertTrue(props.superblock().highWaterSlot() > before + 2, "the block must have skipped the tail");
				txn.rollback();
			}
			assertEquals(before, props.superblock().highWaterSlot(),
				"a rollback with no other writer must restore the mark, skipped tail included");
			store.verify(VerifyLevel.FULL);
		}
	}
}
