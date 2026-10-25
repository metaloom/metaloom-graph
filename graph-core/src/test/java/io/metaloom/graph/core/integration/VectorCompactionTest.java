package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.CompactionReport;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.segment.Geometry;

/**
 * Compaction moves vector records, and the ANN graph is made of links between them.
 *
 * <p>
 * The links and the index's entry point were deliberately left unrewritten on the grounds that the graph is
 * rebuildable — but nothing rebuilt it, and a moved record keeps its linked flag, so a manual build skipped it too.
 * Every link to a moved record named a freed slot: the record became unreachable, and when the entry point itself
 * moved the index answered nothing at all. {@code VECTOR.md} names "ids move, the index still answers" as the test this
 * needed.
 * </p>
 */
@Tag("unit")
public class VectorCompactionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int DIMENSIONS = 8;

	private static GraphStoreOptions withVectors() {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(DIMENSIONS)
			.build();
	}

	@Test
	public void testTheIndexStillAnswersAfterVectorRecordsMove() throws Exception {
		try (GraphStore store = stores.open(withVectors())) {
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "embedding", DIMENSIONS, VectorMetric.EUCLIDEAN));
			Random random = new Random(20261024L);
			List<Long> early = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					early.add(store.createNode(List.of("Filler"),
						Properties.of().set("i", (long) i).set("embedding", vector(random))));
				}
				txn.commit();
			}
			Set<Long> kept = new HashSet<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					kept.add(store.createNode(List.of("Doc"),
						Properties.of().set("i", (long) (1000 + i)).set("embedding", vector(random))));
				}
				txn.commit();
			}
			// Frees the low vector slots, which belong to nodes outside the index, so the members are what compaction
			// moves down into them — the entry point among them, since it was the first member linked.
			try (Transaction txn = store.begin()) {
				for (long id : early) {
					store.deleteNode(id);
				}
				txn.commit();
			}
			assertEquals(200, store.nearest("Doc", "embedding", vector(random), 200, null).size(),
				"control: every survivor is reachable before compaction");

			CompactionReport report = store.compact(CompactionOptions.always());
			assertTrue(report.relocated().getOrDefault(Kind.VEC, 0L) > 0, "vector records must have moved: " + report);
			store.verify(VerifyLevel.FULL);

			Set<Integer> found = new HashSet<>();
			for (Neighbour neighbour : store.nearest("Doc", "embedding", vector(random), 200, null)) {
				found.add(store.nodeProperties(neighbour.nodeId()).getInt("i"));
			}
			assertEquals(200, found.size(), "every member must still be reachable after its record moved");
		}
	}

	private static float[] vector(Random random) {
		float[] vector = new float[DIMENSIONS];
		for (int i = 0; i < DIMENSIONS; i++) {
			vector[i] = random.nextFloat();
		}
		return vector;
	}
}
