package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * The index catalog page read through a view, by the entry points that consult it before their real work.
 *
 * <p>
 * {@code nearest()} looked its index up — ordinal, definition, state — directly, outside the read wrapper every other
 * read goes through. Without a view that is a read of the live mapping with no exclusion from a commit's apply, so it
 * can be torn. With a view it is worse and deterministic: once anything changes the catalog page after the view began,
 * the view has to rebuild the page, the rebuild needs the apply latch, and nobody is holding it — so the engine's
 * internal "retry me under the lock" signal, {@code NeedsExclusionException}, escaped to the caller. Found by
 * {@code IsolationAnomalyStressTest}, whose searchers failed with it while inserts moved the catalog page.
 * </p>
 */
@Tag("unit")
public class ViewCatalogReadTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int DIMS = 4;

	@Test
	public void testNearestInsideAViewAfterTheCatalogPageChanged() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(DIMS)
			.build();
		try (GraphStore store = stores.open(options)) {
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "emb", DIMS, VectorMetric.COSINE));
			long first = store.createNode("Doc", Properties.of().set("emb", new float[] { 1, 0, 0, 0 }));

			Thread other;
			try (ReadTransaction view = store.beginRead()) {
				// Any change to the catalog page after the view began: here a second declaration, from another thread
				// because a view and a write share nothing.
				other = Thread.ofPlatform().start(() -> {
					try {
						store.createIndex("Other", "x");
						store.createNode("Doc", Properties.of().set("emb", new float[] { 0, 1, 0, 0 }));
					} catch (java.io.IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				});
				other.join();

				List<Neighbour> found = store.nearest("Doc", "emb", new float[] { 1, 0, 0, 0 }, 5, null);
				assertEquals(1, found.size(), "the view holds one Doc: " + found);
				assertEquals(first, found.get(0).nodeId());
				assertFalse(store.hasIndex("Other", "x"), "the declaration is after the view");
			}
			assertEquals(2, store.nearest("Doc", "emb", new float[] { 1, 0, 0, 0 }, 5, null).size());
			// The lookup failures still mean what they say through the wrapper.
			assertThrows(IllegalStateException.class, () -> store.nearest("Doc", "nope", new float[DIMS], 1, null));
		}
	}

	@Test
	public void testBuildVectorIndexBesideAViewOnTheSameThread() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(DIMS)
			.build();
		try (GraphStore store = stores.open(options)) {
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "emb", DIMS, VectorMetric.COSINE));
			try (ReadTransaction view = store.beginRead()) {
				Thread other = Thread.ofPlatform().start(() -> {
					try {
						store.createIndex("Other", "x");
					} catch (java.io.IOException e) {
						throw new java.io.UncheckedIOException(e);
					}
				});
				other.join();
				// Resumable and idempotent; what matters is that the lookup in front of it does not leak the
				// engine's internal retry signal.
				try {
					store.buildVectorIndex("Doc", "emb");
				} catch (io.metaloom.graph.core.api.GraphStoreException refused) {
					// A write inside an open view may be refused outright, which is a legitimate answer.
				}
			}
		}
	}
}
