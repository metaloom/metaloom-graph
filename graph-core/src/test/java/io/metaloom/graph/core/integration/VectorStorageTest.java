package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.GraphStoreImpl;
import io.metaloom.graph.core.store.vec.VectorRecord;

/**
 * Vectors stored as fixed-size records rather than as blobs.
 *
 * <p>
 * The reason for the whole kind is in {@link #testAVectorCostsARecordRatherThanAPage}: a blob extent is page sized, so
 * a 768-dimension vector costs 8 KiB there and 4 KiB here — and the record has room for the ANN links a search will
 * want in the same read. Everything else in this class is about the two paths coexisting without either leaking.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
@Tag("unit")
public class VectorStorageTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static GraphStoreOptions withVectors(int dimensions) {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(dimensions)
			.build();
	}

	@Test
	public void testAVectorOfTheStoresDimensionBecomesARecord() throws Exception {
		try (GraphStore store = stores.open(withVectors(4))) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long before = impl.segments(Kind.BLOB).liveCount();

			long nodeId;
			try (Transaction txn = store.begin()) {
				nodeId = store.createNode(List.of("Doc"),
					Properties.of().set("embedding", new float[] { 1f, 2f, 3f, 4f }));
				txn.commit();
			}

			assertEquals(1, impl.segments(Kind.VEC).liveCount(), "the vector went to a VEC record");
			assertEquals(before, impl.segments(Kind.BLOB).liveCount(), "and not to a blob");
			assertArrayEquals(new float[] { 1f, 2f, 3f, 4f }, store.nodeProperties(nodeId).getVector("embedding"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The record is fixed size, so it can only hold the one dimension the store was created for. Every other vector
	 * still escapes to a blob — which means both codecs stay live, and both stay tested.
	 */
	@Test
	public void testAVectorOfAnyOtherLengthStillGoesToABlob() throws Exception {
		try (GraphStore store = stores.open(withVectors(4))) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long blobsBefore = impl.segments(Kind.BLOB).liveCount();

			long nodeId;
			try (Transaction txn = store.begin()) {
				nodeId = store.createNode(List.of("Doc"),
					Properties.of().set("short", new float[] { 1f, 2f }).set("right", new float[] { 1f, 2f, 3f, 4f }));
				txn.commit();
			}

			assertEquals(1, impl.segments(Kind.VEC).liveCount());
			assertTrue(impl.segments(Kind.BLOB).liveCount() > blobsBefore, "the two-element vector took a blob");
			assertArrayEquals(new float[] { 1f, 2f }, store.nodeProperties(nodeId).getVector("short"));
			assertArrayEquals(new float[] { 1f, 2f, 3f, 4f }, store.nodeProperties(nodeId).getVector("right"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A vector record is owned by exactly one property block, so it has to die with it. This is the cascade the proof
	 * of concept never had, and the orphan it would otherwise leave is the silent kind: nothing reads it, nothing
	 * reports it, and it costs a record every time.
	 */
	@Test
	public void testDeletingTheOwnerFreesTheRecord() throws Exception {
		try (GraphStore store = stores.open(withVectors(4))) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long nodeId;
			try (Transaction txn = store.begin()) {
				nodeId = store.createNode(List.of("Doc"),
					Properties.of().set("embedding", new float[] { 1f, 2f, 3f, 4f }));
				txn.commit();
			}
			assertEquals(1, impl.segments(Kind.VEC).liveCount());

			try (Transaction txn = store.begin()) {
				store.deleteNode(nodeId);
				txn.commit();
			}
			assertEquals(0, impl.segments(Kind.VEC).liveCount(), "the vector record outlived its owner");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testUpdatingTheVectorReplacesTheRecord() throws Exception {
		try (GraphStore store = stores.open(withVectors(4))) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long nodeId;
			try (Transaction txn = store.begin()) {
				nodeId = store.createNode(List.of("Doc"),
					Properties.of().set("embedding", new float[] { 1f, 2f, 3f, 4f }));
				txn.commit();
			}
			try (Transaction txn = store.begin()) {
				store.setNodeProperties(nodeId, Properties.of().set("embedding", new float[] { 9f, 9f, 9f, 9f }));
				txn.commit();
			}
			assertEquals(1, impl.segments(Kind.VEC).liveCount(), "one record, not two");
			assertArrayEquals(new float[] { 9f, 9f, 9f, 9f }, store.nodeProperties(nodeId).getVector("embedding"));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testARelationshipCanOwnAVectorToo() throws Exception {
		try (GraphStore store = stores.open(withVectors(4))) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long relId;
			try (Transaction txn = store.begin()) {
				long a = store.createNode(List.of("Doc"));
				long b = store.createNode(List.of("Doc"));
				relId = store.createRelationship(a, "SIMILAR", b,
					Properties.of().set("embedding", new float[] { 1f, 0f, 0f, 0f }));
				txn.commit();
			}
			assertEquals(1, impl.segments(Kind.VEC).liveCount());
			assertArrayEquals(new float[] { 1f, 0f, 0f, 0f },
				store.relationshipProperties(relId).getVector("embedding"));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testVectorsSurviveAReopen() throws Exception {
		long nodeId;
		try (GraphStore store = stores.open(withVectors(4))) {
			try (Transaction txn = store.begin()) {
				nodeId = store.createNode(List.of("Doc"),
					Properties.of().set("embedding", new float[] { 1f, 2f, 3f, 4f }));
				txn.commit();
			}
			store.checkpoint();
		}
		// Reopened without asking for vectors at all: the manifest is the authority, not the options.
		try (GraphStore store = stores.open()) {
			assertArrayEquals(new float[] { 1f, 2f, 3f, 4f }, store.nodeProperties(nodeId).getVector("embedding"));
			assertEquals(1, ((GraphStoreImpl) store).segments(Kind.VEC).liveCount());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The riskiest ordering in the whole kind: a committed vector write is replayed by looking {@code VEC} up in the
	 * segment-set map, so that set has to exist <em>before</em> recovery runs. Build it later and an ordinary reopen
	 * after a crash reports "Recovery references VEC segment 0" and refuses to open the store at all — a healthy store
	 * that reads as corrupt.
	 * <p>
	 * That is also why the dimension lives in the manifest rather than anywhere transactional: the manifest is read
	 * before recovery, and everything else is read after it.
	 */
	@Test
	public void testACommittedVectorSurvivesACrash() throws Exception {
		long nodeId;
		GraphStoreImpl store = (GraphStoreImpl) stores.open(withVectors(4));
		try (Transaction txn = store.begin()) {
			nodeId = store.createNode(List.of("Doc"),
				Properties.of().set("embedding", new float[] { 5f, 6f, 7f, 8f }));
			txn.commit();
		}
		// A crash: the commit reached the log, the dirty pages never reached the device, and nothing was checkpointed.
		store.abandon();

		try (GraphStore reopened = stores.open(withVectors(4))) {
			assertArrayEquals(new float[] { 5f, 6f, 7f, 8f },
				reopened.nodeProperties(nodeId).getVector("embedding"), "the vector was not recovered from the log");
			reopened.verify(VerifyLevel.FULL);
		}
	}

	/** Fixed at creation, like the page size, and refused on reopen for the same reason. */
	@Test
	public void testTheDimensionCannotBeChangedOnReopen() throws Exception {
		try (GraphStore store = stores.open(withVectors(4))) {
			store.checkpoint();
		}
		FormatVersionException refused =
			assertThrows(FormatVersionException.class, () -> stores.open(withVectors(8)).close());
		assertTrue(refused.getMessage().contains("fixed at creation"), refused.getMessage());
	}

	/**
	 * A store with no vector storage is untouched by any of this: same format version, no segment directory, and a
	 * vector property behaves exactly as it did before the kind existed.
	 */
	@Test
	public void testAStoreWithoutVectorsIsUnchanged() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long nodeId;
			try (Transaction txn = store.begin()) {
				nodeId = store.createNode(List.of("Doc"),
					Properties.of().set("embedding", new float[] { 1f, 2f, 3f, 4f }));
				txn.commit();
			}
			assertArrayEquals(new float[] { 1f, 2f, 3f, 4f }, store.nodeProperties(nodeId).getVector("embedding"));
			assertTrue(impl.segments(Kind.BLOB).liveCount() > 0, "it took a blob, as it always did");
			assertEquals(null, impl.segments(Kind.VEC), "and there is no vector segment set at all");
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Ids move, and a vector record is reached through a payload — the reference class a sweep forgets. */
	@Test
	public void testVectorsSurviveCompaction() throws Exception {
		try (GraphStore store = stores.open(withVectors(4))) {
			List<Long> kept = new java.util.ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					long id = store.createNode(List.of("Doc"),
						Properties.of().set("name", "n" + i).set("embedding", new float[] { i, i + 1f, i + 2f, i + 3f }));
					kept.add(id);
				}
				txn.commit();
			}
			// Delete most of them, so compaction has holes to close and every survivor has to be relocated.
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < kept.size(); i++) {
					if (i % 4 != 0) {
						store.deleteNode(kept.get(i));
					}
				}
				txn.commit();
			}
			store.compact(CompactionOptions.always());
			store.verify(VerifyLevel.FULL);

			// The ids the caller held are gone, so the survivors are found by scanning — which is the honest way to
			// check that a compaction preserved content rather than merely not crashing.
			int seen = 0;
			for (long nodeId : ((GraphStoreImpl) store).segments(Kind.NODE).liveIds()) {
				String name = (String) store.nodeProperty(nodeId, "name");
				float[] vector = store.nodeProperties(nodeId).getVector("embedding");
				int i = Integer.parseInt(name.substring(1));
				assertArrayEquals(new float[] { i, i + 1f, i + 2f, i + 3f }, vector, name + " lost its vector");
				seen++;
			}
			assertEquals(50, seen);
		}
	}

	/** The measurement the kind exists for. */
	@Test
	public void testAVectorCostsARecordRatherThanAPage() {
		int pageSize = Geometry.DEFAULT_PAGE_SIZE;
		assertEquals(4096, VectorRecord.strideFor(768, pageSize),
			"a 768 dimension vector and its links fit a 4 KiB record");
		assertTrue(VectorRecord.strideFor(768, pageSize) < pageSize,
			"which is less than the whole page a blob extent costs");
		assertEquals(512, VectorRecord.strideFor(4, pageSize), "and a tiny vector costs a tiny record");

		// The interleaved record is strictly larger than its vector, so even an exactly-power-of-two dimension lands
		// in the next stride up. Worth pinning: it is the one part of the sizing that surprises people.
		assertEquals(4096, VectorRecord.strideFor(512, pageSize));
		assertEquals(912, VectorRecord.maxDimensions(4096), "4096 less the header and the reserved link slots");
		assertEquals(0, VectorRecord.strideFor(4000, pageSize), "and past the page there is no legal stride");
	}

	@Test
	public void testADimensionThatCannotFitIsRefusedAtBuildTime() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> GraphStoreOptions.builder().pageSize(4096).vectorDimensions(4000).build());
		assertTrue(refused.getMessage().contains("4000"), refused.getMessage());
		assertTrue(refused.getMessage().contains("pageSize"), refused.getMessage());
	}
}
