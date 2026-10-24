package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorIndexState;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * The declared vector index: what it covers, what it refuses, and how its membership follows the data.
 *
 * <p>
 * The load-bearing test is {@link #testTheIndexAgreesWithAScan} — the same differential the label and property indexes
 * are held to. An index that agrees with itself proves nothing; one that agrees with a scan of the same data over a
 * workload neither of them chose is the only evidence worth having.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
@Tag("unit")
public class VectorIndexTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static GraphStoreOptions withVectors(int dimensions) {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(dimensions)
			.build();
	}

	private static VectorIndexDefinition definition() {
		return VectorIndexDefinition.of("Doc", "embedding", 3, VectorMetric.COSINE);
	}

	private static long doc(GraphStore store, String name, float... vector) throws Exception {
		return store.createNode(List.of("Doc"), Properties.of().set("name", name).set("embedding", vector));
	}

	private static List<Long> ids(List<Neighbour> neighbours) {
		List<Long> ids = new ArrayList<>(neighbours.size());
		for (Neighbour neighbour : neighbours) {
			ids.add(neighbour.nodeId());
		}
		return ids;
	}

	@Test
	public void testDeclareListAndDrop() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			assertTrue(store.vectorIndexes().isEmpty());
			store.createVectorIndex(definition());

			List<VectorIndexDefinition> declared = store.vectorIndexes();
			assertEquals(1, declared.size());
			assertEquals("Doc", declared.get(0).label());
			assertEquals("embedding", declared.get(0).key());
			assertEquals(VectorMetric.COSINE, declared.get(0).metric());
			assertEquals(VectorIndexState.READY, declared.get(0).state());

			assertTrue(assertThrows(IllegalStateException.class, () -> store.createVectorIndex(definition()))
				.getMessage().contains("already exists"), "a second declaration on the same pair is refused");

			store.dropVectorIndex("Doc", "embedding");
			assertTrue(store.vectorIndexes().isEmpty());
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testTheDeclarationSurvivesAReopen() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			store.createVectorIndex(definition());
			try (Transaction txn = store.begin()) {
				doc(store, "east", 1f, 0f, 0f);
				txn.commit();
			}
			store.checkpoint();
		}
		try (GraphStore store = stores.open(withVectors(3))) {
			assertEquals(1, store.vectorIndexes().size());
			assertEquals(1, store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 5, null).size(),
				"and its members are still members");
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Declaring over data that already breaks the declaration is refused, exactly as a unique constraint is. */
	@Test
	public void testADeclarationOverAWrongLengthVectorIsRefused() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			try (Transaction txn = store.begin()) {
				doc(store, "fine", 1f, 0f, 0f);
				store.createNode(List.of("Doc"), Properties.of().set("embedding", new float[] { 1f, 0f }));
				txn.commit();
			}
			IllegalArgumentException refused =
				assertThrows(IllegalArgumentException.class, () -> store.createVectorIndex(definition()));
			assertTrue(refused.getMessage().contains("2 dimension vector"), refused.getMessage());
		}
	}

	@Test
	public void testAStoreWithoutVectorStorageCannotHaveAVectorIndex() throws Exception {
		try (GraphStore store = stores.open()) {
			assertTrue(assertThrows(IllegalStateException.class, () -> store.createVectorIndex(definition()))
				.getMessage().contains("vectorDimensions"), "and the message says how to fix it");
		}
	}

	/** The index and the store's records have to hold the same shape; there is only one stride. */
	@Test
	public void testAnIndexOfADifferentDimensionIsRefused() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			assertTrue(assertThrows(IllegalArgumentException.class,
				() -> store.createVectorIndex(VectorIndexDefinition.of("Doc", "embedding", 8, VectorMetric.COSINE)))
					.getMessage().contains("3 dimensions"));
		}
	}

	/** "No index" and "no matches" are opposite answers and must not look the same. */
	@Test
	public void testQueryingAnUndeclaredIndexFailsRatherThanReturningNothing() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			IllegalStateException absent = assertThrows(IllegalStateException.class,
				() -> store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 5, null));
			assertTrue(absent.getMessage().contains("nearestByScan"), "and it points at what does work: "
				+ absent.getMessage());
		}
	}

	@Test
	public void testNearestReturnsMembersNearestFirst() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			long east;
			long eastish;
			long north;
			try (Transaction txn = store.begin()) {
				east = doc(store, "east", 1f, 0f, 0f);
				eastish = doc(store, "east-ish", 0.9f, 0.1f, 0f);
				north = doc(store, "north", 0f, 1f, 0f);
				// A different label, so not a member however near it is.
				store.createNode(List.of("Other"), Properties.of().set("embedding", new float[] { 1f, 0f, 0f }));
				txn.commit();
			}
			store.createVectorIndex(definition());

			List<Neighbour> near = store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 5, null);
			assertEquals(List.of(east, eastish, north), ids(near));
			assertEquals(1.0, near.get(0).score(), 1e-6);
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The filter is the reason to build this inside the graph engine rather than beside it: an external index knows
	 * nothing about the graph, so it must over-fetch and filter afterwards with no way to know how far.
	 */
	@Test
	public void testTheFilterIsAppliedToResults() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			long east;
			long north;
			try (Transaction txn = store.begin()) {
				east = doc(store, "east", 1f, 0f, 0f);
				north = doc(store, "north", 0f, 1f, 0f);
				txn.commit();
			}
			store.createVectorIndex(definition());

			List<Neighbour> onlyNorth = store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 5,
				nodeId -> nodeId == north);
			assertEquals(List.of(north), ids(onlyNorth), "the nearest is filtered out, the next one is returned");
			assertEquals(2, store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 5, null).size());
			assertEquals(east, store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 5, id -> true).get(0)
				.nodeId());
		}
	}

	/**
	 * Membership is a function of the labels <em>and</em> the property, and both move independently. This is the hook
	 * the differential harness does not cover, so it is covered here explicitly.
	 */
	@Test
	public void testMembershipFollowsLabelsAndProperties() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			store.createVectorIndex(definition());
			float[] query = { 1f, 0f, 0f };

			long node;
			try (Transaction txn = store.begin()) {
				// Created without the label, so not a member however good its vector is.
				node = store.createNode(List.of("Draft"), Properties.of().set("embedding", new float[] { 1f, 0f, 0f }));
				txn.commit();
			}
			assertTrue(store.nearest("Doc", "embedding", query, 5, null).isEmpty());

			try (Transaction txn = store.begin()) {
				store.addNodeLabel(node, "Doc");
				txn.commit();
			}
			assertEquals(List.of(node), ids(store.nearest("Doc", "embedding", query, 5, null)),
				"adding the label made it a member");

			try (Transaction txn = store.begin()) {
				store.removeNodeLabel(node, "Doc");
				txn.commit();
			}
			assertTrue(store.nearest("Doc", "embedding", query, 5, null).isEmpty(),
				"and removing it stopped it being one");

			try (Transaction txn = store.begin()) {
				store.addNodeLabel(node, "Doc");
				store.setNodeProperties(node, Properties.of().set("embedding", new float[] { 0f, 1f, 0f }));
				txn.commit();
			}
			List<Neighbour> moved = store.nearest("Doc", "embedding", query, 5, null);
			assertEquals(List.of(node), ids(moved));
			assertEquals(0.0, moved.get(0).score(), 1e-6, "and the new vector is what is scored");

			try (Transaction txn = store.begin()) {
				store.deleteNode(node);
				txn.commit();
			}
			assertTrue(store.nearest("Doc", "embedding", query, 5, null).isEmpty(), "a deleted node is not a member");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A record that leaves an index is not returned by it, even though its old neighbours still link to it.
	 *
	 * <p>
	 * Leaving unlinks the record's own lists and repairs the entry point, but the neighbours' links to it are left in
	 * place — rewriting them would be a random write per link. So the walk still reaches it, and the only thing that can
	 * keep it out of an answer is the search asking whether the record still belongs to this index. With one member the
	 * entry-point repair hid that; with three the departed one is found through a neighbour.
	 * </p>
	 */
	@Test
	public void testARecordThatLeftTheIndexIsNotReturned() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			long a;
			long b;
			long c;
			try (Transaction txn = store.begin()) {
				a = doc(store, "a", 1f, 0f, 0f);
				b = doc(store, "b", 0.9f, 0.1f, 0f);
				c = doc(store, "c", 0f, 1f, 0f);
				txn.commit();
			}
			store.createVectorIndex(definition());
			store.removeNodeLabel(b, "Doc");

			List<Long> found = ids(store.nearest("Doc", "embedding", new float[] { 0.9f, 0.1f, 0f }, 5, null));
			assertEquals(List.of(a, c), found, "b is no longer a :Doc and must not be answered for one");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A record relabelled into a second index must not become a bridge between the two graphs.
	 *
	 * <p>
	 * The stale link from a {@code :Doc} member reaches it, and its own links now belong to the {@code :Other} graph. A
	 * walk that followed them would answer a {@code :Doc} query with {@code :Other} members.
	 * </p>
	 */
	@Test
	public void testARelabelledRecordDoesNotBridgeTwoIndexes() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			List<Long> docs = new ArrayList<>();
			long bridge;
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 6; i++) {
					docs.add(doc(store, "d" + i, 1f, i * 0.05f, 0f));
				}
				bridge = doc(store, "bridge", 0f, 1f, 0f);
				for (int i = 0; i < 6; i++) {
					store.createNode(List.of("Other"),
						Properties.of().set("embedding", new float[] { -0.1f * i, 1f, 0.05f * i }));
				}
				txn.commit();
			}
			store.createVectorIndex(definition());
			store.createVectorIndex(VectorIndexDefinition.of("Other", "embedding", 3, VectorMetric.COSINE));
			store.removeNodeLabel(bridge, "Doc");
			store.addNodeLabel(bridge, "Other");

			List<Long> found = ids(store.nearest("Doc", "embedding", new float[] { 0f, 1f, 0f }, 20, null));
			assertEquals(docs.size(), found.size(), "every :Doc member and nothing else: " + found);
			assertTrue(docs.containsAll(found), "a :Doc query answered with a non-member: " + found);
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The build resumes rather than restarting, and calling it again on a finished index is a no-op.
	 * <p>
	 * The resume marker is per record — a flag in the record itself — rather than a position in a list. Ids are not
	 * ordered by insertion once a compaction has run, so a watermark would resume in the wrong place and leave the
	 * members between the two silently unlinked.
	 */
	@Test
	public void testTheBuildIsResumable() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 40; i++) {
					doc(store, "d" + i, i, i + 1f, i + 2f);
				}
				txn.commit();
			}
			store.createVectorIndex(definition());
			List<Long> first = ids(store.nearest("Doc", "embedding", new float[] { 1f, 2f, 3f }, 5, null));

			// Running it again must change nothing: every member is already linked and is skipped.
			store.buildVectorIndex("Doc", "embedding");
			assertEquals(first, ids(store.nearest("Doc", "embedding", new float[] { 1f, 2f, 3f }, 5, null)));
			assertEquals(VectorIndexState.READY, store.vectorIndexes().get(0).state());

			// A member added after the build is linked by the hooks, not left for a later build. Its direction is one
			// no d_i shares — cosine ignores magnitude, so an all-positive vector would tie with several of them.
			try (Transaction txn = store.begin()) {
				doc(store, "late", -1f, 1f, 0f);
				txn.commit();
			}
			assertEquals("late", store.nodeProperty(
				store.nearest("Doc", "embedding", new float[] { -1f, 1f, 0f }, 1, null).get(0).nodeId(), "name"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The differential. The index and a brute-force scan of the same label must agree, for every metric, over data
	 * neither of them chose.
	 */
	@Test
	public void testTheIndexAgreesWithAScan() throws Exception {
		java.util.Random random = new java.util.Random(20260816L);
		try (GraphStore store = stores.open(withVectors(3))) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 300; i++) {
					doc(store, "d" + i, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1,
						random.nextFloat() * 2 - 1);
				}
				txn.commit();
			}
			for (VectorMetric metric : VectorMetric.values()) {
				try (Transaction txn = store.begin()) {
					store.createVectorIndex(VectorIndexDefinition.of("Doc", "embedding", 3, metric));
					txn.commit();
				}
				float[] query = { 0.25f, -0.5f, 0.75f };
				assertEquals(ids(store.nearestByScan("Doc", "embedding", query, 10, metric)),
					ids(store.nearest("Doc", "embedding", query, 10, null)),
					"the index disagrees with a scan under " + metric);
				store.dropVectorIndex("Doc", "embedding");
			}
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * Every member's vector record is freed in turn, the entry point's included, and the index must still reach every
	 * survivor. Freeing the entry point without handing it over leaves a search with nowhere to start, and the next
	 * insert then takes the empty-graph branch and links to nothing.
	 */
	@Test
	public void testOverwritingEveryVectorKeepsTheIndexReachable() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			store.createVectorIndex(definition());
			List<Long> nodes = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 30; i++) {
					nodes.add(doc(store, "d" + i, spread(i)));
				}
				txn.commit();
			}
			for (int i = 0; i < nodes.size(); i++) {
				store.setNodeProperties(nodes.get(i), Properties.of().set("name", "d" + i).set("embedding", spread(i + 7)));
			}
			assertEquals(30, store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 30, null).size(),
				"overwriting every vector, the entry point's included, must leave every member reachable");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testDeletingTheEntryPointKeepsTheIndexReachable() throws Exception {
		try (GraphStore store = stores.open(withVectors(3))) {
			store.createVectorIndex(definition());
			List<Long> nodes = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 30; i++) {
					nodes.add(doc(store, "d" + i, spread(i)));
				}
				txn.commit();
			}
			for (int i = 0; i < nodes.size(); i++) {
				store.deleteNode(nodes.get(i));
				int left = nodes.size() - i - 1;
				assertEquals(left, store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 30, null).size(),
					"after deleting " + (i + 1) + " members every survivor must still be found");
			}
			store.verify(VerifyLevel.FULL);
		}
	}

	private static float[] spread(int i) {
		double angle = i * 0.2;
		return new float[] { (float) Math.cos(angle), (float) Math.sin(angle), 0.05f * (i % 5) };
	}
}
