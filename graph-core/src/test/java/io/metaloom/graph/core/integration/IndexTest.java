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
