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

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The secondary indexes. The load-bearing assertion is the index-versus-scan differential: an indexed query must
 * return exactly what a full scan would.
 *
 * @see <a href="../../../../../../../../spec/PLAN.md">spec/PLAN.md</a> Phase 7
 */
@Tag("unit")
public class IndexTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testLabelIndex() throws Exception {
		try (GraphStore store = stores.open()) {
			Set<Long> people = new HashSet<>();
			Set<Long> vehicles = new HashSet<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 500; i++) {
					people.add(store.createNode("Person", Properties.of().set("i", i)));
					if (i % 5 == 0) {
						vehicles.add(store.createNode("Vehicle"));
					}
				}
				txn.commit();
			}
			assertEquals(people, new HashSet<>(store.nodesByLabel("Person")));
			assertEquals(vehicles, new HashSet<>(store.nodesByLabel("Vehicle")));
			assertEquals(List.of(), store.nodesByLabel("Nonexistent"));
			store.verify();
		}
	}

	@Test
	public void testPropertyIndex() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> matching = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 400; i++) {
					long id = store.createNode("N", Properties.of()
						.set("city", i % 4 == 0 ? "Berlin" : "Hamburg")
						.set("rank", i));
					if (i % 4 == 0) {
						matching.add(id);
					}
				}
				txn.commit();
			}
			assertEquals(new HashSet<>(matching), new HashSet<>(store.nodesByProperty("city", "Berlin")));
			assertEquals(1, store.nodesByProperty("rank", 7).size());
			assertEquals(0, store.nodesByProperty("city", "Paris").size());
			assertEquals(0, store.nodesByProperty("nosuchkey", "x").size());
			store.verify();
		}
	}

	@Test
	public void testIndexMatchesAFullScan() throws Exception {
		// The differential test: whatever the index says must equal what a scan of every node says.
		Random random = new Random(20260813L);
		try (GraphStore store = stores.open()) {
			List<Long> all = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 800; i++) {
					all.add(store.createNode(i % 3 == 0 ? "A" : "B",
						Properties.of().set("bucket", random.nextInt(10))));
				}
				txn.commit();
			}
			for (int bucket = 0; bucket < 10; bucket++) {
				Set<Long> scanned = new HashSet<>();
				for (long id : all) {
					Object value = store.nodeProperty(id, "bucket");
					if (value instanceof Long l && l == bucket) {
						scanned.add(id);
					}
				}
				assertEquals(scanned, new HashSet<>(store.nodesByProperty("bucket", bucket)),
					"index and scan disagree for bucket " + bucket);
			}
			for (String label : List.of("A", "B")) {
				Set<Long> scanned = new HashSet<>();
				for (long id : all) {
					if (label.equals(store.nodeLabel(id))) {
						scanned.add(id);
					}
				}
				assertEquals(scanned, new HashSet<>(store.nodesByLabel(label)));
			}
		}
	}

	@Test
	public void testIndexFollowsUpdatesAndDeletes() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("Person", Properties.of().set("city", "Berlin"));
			assertEquals(List.of(id), store.nodesByProperty("city", "Berlin"));

			store.setNodeProperties(id, Properties.of().set("city", "Hamburg"));
			assertEquals(List.of(), store.nodesByProperty("city", "Berlin"), "The stale entry must be gone");
			assertEquals(List.of(id), store.nodesByProperty("city", "Hamburg"));

			store.deleteNode(id);
			assertEquals(List.of(), store.nodesByProperty("city", "Hamburg"));
			assertEquals(List.of(), store.nodesByLabel("Person"));
			store.verify();
		}
	}

	@Test
	public void testIndexSurvivesAReopen() throws Exception {
		List<Long> ids = new ArrayList<>();
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 300; i++) {
					ids.add(store.createNode("Person", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			store.checkpoint();
		}
		try (GraphStore store = stores.open()) {
			assertEquals(new HashSet<>(ids), new HashSet<>(store.nodesByLabel("Person")));
			assertEquals(1, store.nodesByProperty("i", 42).size());
			store.verify();
		}
	}

	/**
	 * A rolled back transaction that would have created the index must leave no trace of it.
	 * <p>
	 * The catalog page is allocated lazily by the first write, so the very first transaction against a fresh store
	 * creates it — and a rollback frees it again while the in-memory pointer to it survives. Every indexed lookup
	 * afterwards used to fail with a {@code StaleIdException} on a perfectly healthy store, which reads as corruption
	 * and is not.
	 */
	@Test
	public void testIndexLookupsWorkAfterAnInitialRollback() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("Person", Properties.of().set("name", "rolled back"));
				// No commit.
			}

			assertTrue(store.nodesByLabel("Person").isEmpty());
			assertTrue(store.nodesByProperty("name", "rolled back").isEmpty());

			// And the index still builds correctly afterwards.
			long kept = store.createNode("Person", Properties.of().set("name", "kept"));
			assertEquals(List.of(kept), store.nodesByLabel("Person"));
			assertEquals(List.of(kept), store.nodesByProperty("name", "kept"));
			store.verify();
		}
	}

	/**
	 * A vector property is indexed by content, so an equality lookup finds it and a delete takes its entry with it.
	 * <p>
	 * Both halves used to fail, and neither made a noise: {@code float[].hashCode()} is the identity hash, so the
	 * lookup hashed the caller's array and matched nothing, and the delete hashed a third array — the one read back
	 * from the store — and left the entry behind for {@code verify(FULL)} to find later as a dangling reference.
	 *
	 * @see <a href="../../../../../../../../spec/INDEX_TASKS.md">spec/INDEX_TASKS.md</a> IDX-1
	 */
	@Test
	public void testAVectorPropertyIsFoundByItsValue() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long labelsBefore = impl.indexCatalog().labelIndexSize();
			long propsBefore = impl.indexCatalog().propertyIndexSize();

			float[] embedding = { 0.5f, -1.25f, 3f, 0f };
			long id = store.createNode("Face", Properties.of().set("embedding", embedding));
			long other = store.createNode("Face", Properties.of().set("embedding", new float[] { 9f, 9f }));

			// A different instance holding the same values — the case the identity hash could never answer.
			assertEquals(List.of(id), store.nodesByProperty("embedding", new float[] { 0.5f, -1.25f, 3f, 0f }));
			assertEquals(List.of(other), store.nodesByProperty("embedding", new float[] { 9f, 9f }));
			assertEquals(List.of(), store.nodesByProperty("embedding", new float[] { 0.5f, -1.25f, 3f }));
			store.verify(VerifyLevel.FULL);

			store.deleteNode(id);
			store.deleteNode(other);
			assertEquals(List.of(), store.nodesByProperty("embedding", embedding));
			assertEquals(labelsBefore, impl.indexCatalog().labelIndexSize(), "a label entry outlived its node");
			assertEquals(propsBefore, impl.indexCatalog().propertyIndexSize(), "a vector entry outlived its node");
			store.verify(VerifyLevel.FULL);
		}
	}

	/** A vector inside a list is the same defect one level down: {@code List::hashCode} delegates to its elements. */
	@Test
	public void testAListHoldingAVectorIsFoundByItsValue() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long propsBefore = impl.indexCatalog().propertyIndexSize();

			long id = store.createNode("Face",
				Properties.of().set("embeddings", List.of(new float[] { 1f, 2f }, new float[] { 3f, 4f })));
			assertEquals(List.of(id), store.nodesByProperty("embeddings",
				List.of(new float[] { 1f, 2f }, new float[] { 3f, 4f })));
			assertEquals(List.of(), store.nodesByProperty("embeddings", List.of(new float[] { 1f, 2f })));

			store.deleteNode(id);
			assertEquals(propsBefore, impl.indexCatalog().propertyIndexSize());
			store.verify(VerifyLevel.FULL);
		}
	}

	/** A list of integers reads back as a list of longs, so the lookup has to widen inside the list as well. */
	@Test
	public void testAListOfIntegersIsFoundByItsValue() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("N", Properties.of().set("tags", List.of(1, 2, 3)));
			assertEquals(List.of(id), store.nodesByProperty("tags", List.of(1, 2, 3)));
			assertEquals(List.of(id), store.nodesByProperty("tags", List.of(1L, 2L, 3L)));
			assertEquals(List.of(), store.nodesByProperty("tags", List.of(1, 2)));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** The property value changes but the array instance does not — the update hook has to see the difference. */
	@Test
	public void testAVectorPropertyFollowsAnUpdate() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("Face", Properties.of().set("embedding", new float[] { 1f, 2f }));
			store.setNodeProperties(id, Properties.of().set("embedding", new float[] { 7f, 8f }));

			assertEquals(List.of(), store.nodesByProperty("embedding", new float[] { 1f, 2f }),
				"the stale entry must be gone");
			assertEquals(List.of(id), store.nodesByProperty("embedding", new float[] { 7f, 8f }));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testTreeStaysShallow() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 5000; i++) {
					store.createNode("Person", Properties.of().set("i", i));
				}
				txn.commit();
			}
			// 5000 label entries plus 5000 property entries at a fan-out of 339 must stay very shallow.
			assertTrue(impl.indexCatalog().labelIndexSize() >= 5000);
			assertTrue(impl.indexCatalog().propertyIndexSize() >= 5000);
			assertTrue(impl.indexCatalog().tree().fanout() > 300,
				"fan-out is " + impl.indexCatalog().tree().fanout());
			store.verify();
		}
	}
}
