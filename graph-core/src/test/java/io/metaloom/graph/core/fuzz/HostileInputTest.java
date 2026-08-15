package io.metaloom.graph.core.fuzz;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The format parsers against deliberately hostile input.
 * <p>
 * A store file is untrusted input. It can be corrupted by a failing device, truncated by a full filesystem, or handed
 * over by someone who wrote it themselves. The contract this file enforces is narrow and absolute:
 *
 * <ol>
 * <li>reading a corrupt store either returns correct data or throws one of the engine's own exceptions;</li>
 * <li>it never reads or writes outside the mapping;</li>
 * <li>it never allocates an amount of memory derived from an unvalidated field;</li>
 * <li>it never fails to terminate.</li>
 * </ol>
 *
 * Points 3 and 4 are the ones that make this a security concern rather than a robustness one. A length field read
 * straight out of a page and passed to {@code new byte[n]} is a one-byte edit away from an {@code OutOfMemoryError},
 * and a next-pointer followed without a bound is a one-byte edit away from a process that never returns. Both are
 * denial of service, and both were present before these tests.
 * <p>
 * Every case is wrapped in a hard timeout, because "the test hangs" is exactly the failure being tested for.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 6
 */
@Tag("unit")
public class HostileInputTest {

	private static final Duration LIMIT = Duration.ofSeconds(20);

	@RegisterExtension
	TempStore stores = new TempStore();

	// ---------------------------------------------------------------- unbounded allocation

	@Test
	public void testPropertyBlockWithAnAbsurdPayloadLength() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			long nodeId;
			long blockId;
			Geometry geometry;
			try (GraphStore store = stores.open()) {
				nodeId = store.createNode("N", Properties.of().set("k", "v"));
				blockId = ((GraphStoreImpl) store).nodeStore().propBlockId(nodeId);
				geometry = ((GraphStoreImpl) store).geometry();
			}
			// used_bytes is a u16 at offset 4 of the block header, and a page holds 8192 bytes.
			patchShort(segmentFile(Kind.PROP), geometry.byteOffset(Kind.PROP, ElementId.slot(blockId)) + 4, 0xFFFF);

			try (GraphStore store = stores.open()) {
				expectTypedFailure(() -> store.nodeProperties(nodeId),
					"a payload length larger than the page must be rejected, not allocated");
			}
		});
	}

	@Test
	public void testPropertyBlockClaimingMoreCellsThanAPageHolds() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			long nodeId;
			long blockId;
			Geometry geometry;
			try (GraphStore store = stores.open()) {
				nodeId = store.createNode("N", Properties.of().set("k", "v"));
				blockId = ((GraphStoreImpl) store).nodeStore().propBlockId(nodeId);
				geometry = ((GraphStoreImpl) store).geometry();
			}
			patchShort(segmentFile(Kind.PROP), geometry.byteOffset(Kind.PROP, ElementId.slot(blockId)), 0xFFFF);

			try (GraphStore store = stores.open()) {
				// Reading is unaffected: a block declares its payload length separately and the payload is intact.
				// The danger is in the free path, which would otherwise release 65535 slots belonging to other blocks
				// and hand them out again — the same slot in two records, which is the worst corruption possible.
				expectTypedFailure(() -> {
					try {
						store.deleteNode(nodeId);
					} catch (IOException e) {
						throw new IllegalStateException(e);
					}
				}, "a cell count that runs off the end of the page must be rejected before those slots are freed");
			}
		});
	}

	@Test
	public void testBlobClaimingTwoGigabytes() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			long nodeId;
			long blobId;
			Geometry geometry;
			try (GraphStore store = stores.open()) {
				// Past the inline limit, so the value lives in a blob extent whose header we can corrupt.
				nodeId = store.createNode("N", Properties.of().set("k", "x".repeat(1000)));
				GraphStoreImpl impl = (GraphStoreImpl) store;
				blobId = impl.propertyStore().blobIds(impl.nodeStore().propBlockId(nodeId)).get(0);
				geometry = impl.geometry();
			}
			// total_length is a long at offset 0 of the extent header.
			patchLong(segmentFile(Kind.BLOB), geometry.byteOffset(Kind.BLOB, ElementId.slot(blobId)),
				Integer.MAX_VALUE - 1);

			try (GraphStore store = stores.open()) {
				expectTypedFailure(() -> store.nodeProperties(nodeId),
					"a blob length far beyond what the chain can hold must be rejected before it is allocated");
			}
		});
	}

	@Test
	public void testAdjacencyBlockClaimingBillionsOfEntries() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			long hub;
			long blockId;
			Geometry geometry;
			try (GraphStore store = stores.open()) {
				try (Transaction txn = store.begin()) {
					hub = store.createNode("Hub");
					for (int i = 0; i < 50; i++) {
						store.createEdge(hub, "E", store.createNode("Spoke"));
					}
					txn.commit();
				}
				GraphStoreImpl impl = (GraphStoreImpl) store;
				blockId = impl.nodeStore().adjHeadId(hub);
				geometry = impl.geometry();
			}
			// sorted_count is an int at offset 32 of the block header.
			patchInt(segmentFile(Kind.ADJ), geometry.byteOffset(Kind.ADJ, ElementId.slot(blockId)) + 32,
				Integer.MAX_VALUE);

			try (GraphStore store = stores.open()) {
				expectTypedFailure(() -> store.neighbours(hub, Direction.OUT),
					"an entry count larger than the page could hold must be rejected before the array is allocated");
			}
		});
	}

	// ---------------------------------------------------------------- non-termination

	@Test
	public void testAdjacencyChainThatPointsAtItself() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			long hub;
			long blockId;
			Geometry geometry;
			try (GraphStore store = stores.open()) {
				try (Transaction txn = store.begin()) {
					hub = store.createNode("Hub");
					for (int i = 0; i < 50; i++) {
						store.createEdge(hub, "E", store.createNode("Spoke"));
					}
					txn.commit();
				}
				GraphStoreImpl impl = (GraphStoreImpl) store;
				blockId = impl.nodeStore().adjHeadId(hub);
				geometry = impl.geometry();
			}
			// next_block_id is a long at offset 8; point the block at itself.
			patchLong(segmentFile(Kind.ADJ), geometry.byteOffset(Kind.ADJ, ElementId.slot(blockId)) + 8, blockId);

			try (GraphStore store = stores.open()) {
				expectTypedFailure(() -> store.neighbours(hub, Direction.OUT),
					"a chain that never ends must be detected, not walked forever");
			}
		});
	}

	@Test
	public void testBlobChainThatPointsAtItself() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			long nodeId;
			long blobId;
			Geometry geometry;
			try (GraphStore store = stores.open()) {
				// Incompressible on purpose. A repeated character compresses to a couple of hundred bytes, which fits
				// one extent — and a blob of one extent has no chain to point at itself, so the test would patch a
				// pointer nothing follows and pass without exercising anything.
				byte[] payload = new byte[40_000];
				new java.util.Random(20260814L).nextBytes(payload);
				nodeId = store.createNode("N", Properties.of().set("k", payload));
				GraphStoreImpl impl = (GraphStoreImpl) store;
				blobId = impl.propertyStore().blobIds(impl.nodeStore().propBlockId(nodeId)).get(0);
				geometry = impl.geometry();
			}
			patchLong(segmentFile(Kind.BLOB), geometry.byteOffset(Kind.BLOB, ElementId.slot(blobId)) + 8, blobId);

			try (GraphStore store = stores.open()) {
				expectTypedFailure(() -> store.nodeProperties(nodeId),
					"a blob chain that loops must be detected, not walked forever");
			}
		});
	}

	// ---------------------------------------------------------------- structural damage

	@Test
	public void testGarbageInANodeRecord() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			long nodeId;
			Geometry geometry;
			try (GraphStore store = stores.open()) {
				try (Transaction txn = store.begin()) {
					nodeId = store.createNode("N", Properties.of().set("k", "v"));
					for (int i = 0; i < 20; i++) {
						store.createEdge(nodeId, "E", store.createNode("O"));
					}
					txn.commit();
				}
				geometry = ((GraphStoreImpl) store).geometry();
			}
			// Every pointer field of the record set to all-ones: prop block, adjacency head, inline edges.
			long base = geometry.byteOffset(Kind.NODE, ElementId.slot(nodeId));
			for (int offset : new int[] { 8, 16, 40, 48 }) {
				patchLong(segmentFile(Kind.NODE), base + offset, -1L);
			}

			try (GraphStore store = stores.open()) {
				// Any of these may fail; none may hang, and none may escape as a raw JDK exception.
				expectTypedFailureOrSuccess(() -> store.nodeProperties(nodeId));
				expectTypedFailureOrSuccess(() -> store.neighbours(nodeId, Direction.BOTH));
				expectTypedFailureOrSuccess(() -> store.verify(VerifyLevel.FULL));
			}
		});
	}

	@Test
	public void testTruncatedSegmentFile() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			try (GraphStore store = stores.open()) {
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < 500; i++) {
						store.createNode("N", Properties.of().set("i", i));
					}
					txn.commit();
				}
			}
			Path file = segmentFile(Kind.NODE);
			try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
				raw.setLength(raw.length() / 2);
			}
			// The file is remapped to its nominal size on open, so a truncated store must either come back
			// (the region reads as zeros) or refuse to open, and must not corrupt the process either way.
			try {
				try (GraphStore store = stores.open()) {
					expectTypedFailureOrSuccess(() -> store.stats());
					expectTypedFailureOrSuccess(() -> store.verify(VerifyLevel.CHECKSUMS));
				}
			} catch (GraphStoreException | IOException expected) {
				// Refusing to open a truncated store is a perfectly good answer.
			}
		});
	}

	@Test
	public void testBothSuperblockCopiesDestroyed() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			try (GraphStore store = stores.open()) {
				store.createNode("N");
			}
			Path file = segmentFile(Kind.NODE);
			try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
				byte[] garbage = new byte[Geometry.HEADER_BYTES];
				java.util.Arrays.fill(garbage, (byte) 0xA5);
				raw.seek(0);
				raw.write(garbage);
			}
			try {
				stores.open().close();
				fail("a segment with no valid superblock must not open silently");
			} catch (RuntimeException | IOException expected) {
				assertTrue(expected.getMessage() != null && !expected.getMessage().isBlank(),
					"and it must say why: " + expected);
			}
		});
	}

	@Test
	public void testCorruptPageIsCaughtByVerify() throws Exception {
		assertTimeoutPreemptively(LIMIT, () -> {
			long nodeId;
			Geometry geometry;
			try (GraphStore store = stores.open()) {
				nodeId = store.createNode("N", Properties.of().set("k", "v"));
				store.checkpoint();
				geometry = ((GraphStoreImpl) store).geometry();
			}
			// One byte, in the middle of a live record. The checksum exists precisely for this.
			patchShort(segmentFile(Kind.NODE), geometry.byteOffset(Kind.NODE, ElementId.slot(nodeId)), 0x1234);

			try (GraphStore store = stores.open()) {
				GraphStoreException failure = null;
				try {
					store.verify(VerifyLevel.CHECKSUMS);
				} catch (GraphStoreException e) {
					failure = e;
				}
				assertTrue(failure != null, "a flipped byte in a live page must fail the checksum check");
				assertTrue(failure.getMessage().contains("hecksum"), failure.getMessage());
			}
		});
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Asserts the call fails the way the engine promises: one of its own exceptions, carrying a message. A raw
	 * {@link IndexOutOfBoundsException} or {@link NullPointerException} escaping the parser is a failure of this test
	 * even though it is technically memory-safe, because it means the field was never validated.
	 */
	private void expectTypedFailure(Runnable call, String why) {
		try {
			call.run();
			fail(why + " (the call returned instead)");
		} catch (GraphStoreException expected) {
			assertTrue(expected.getMessage() != null && !expected.getMessage().isBlank(),
				"the failure must say what is wrong: " + expected);
		} catch (OutOfMemoryError e) {
			fail(why + " (allocated until the heap ran out)");
		} catch (RuntimeException e) {
			fail(why + " (escaped as an untyped " + e.getClass().getName() + ": " + e.getMessage() + ")");
		}
	}

	/** For damage whose effect is not determined: it may work or fail, but only in the ways promised above. */
	private void expectTypedFailureOrSuccess(Runnable call) {
		try {
			call.run();
		} catch (GraphStoreException expected) {
			// Any of the engine's own exceptions is fine; what matters is that it is one of them and that it says why.
			assertTrue(expected.getMessage() != null && !expected.getMessage().isBlank(),
				"a typed failure must still explain itself: " + expected);
		} catch (OutOfMemoryError e) {
			fail("an unvalidated length field was used to allocate memory");
		} catch (RuntimeException e) {
			fail("escaped as an untyped " + e.getClass().getName() + ": " + e.getMessage());
		}
	}

	private Path segmentFile(Kind kind) {
		return stores.path().resolve("seg").resolve(kind.dirName())
			.resolve(String.format("%s-%06d.seg", kind.dirName(), 0));
	}

	private void patchShort(Path file, long offset, int value) throws IOException {
		patch(file, offset, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
	}

	private void patchInt(Path file, long offset, int value) throws IOException {
		patch(file, offset, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
	}

	private void patchLong(Path file, long offset, long value) throws IOException {
		patch(file, offset, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
	}

	private void patch(Path file, long offset, byte[] bytes) throws IOException {
		try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
			raw.seek(offset);
			raw.write(bytes);
		}
	}
}
