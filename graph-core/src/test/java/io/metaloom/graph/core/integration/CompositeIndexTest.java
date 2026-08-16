package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.CompositeIndex;
import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The declared {@code (label, property)} index.
 *
 * <p>
 * Two things are tested that the label and property trees do not need: that a declaration <b>backfills</b>, because an
 * index covering only writes made after it would answer with a subset; and that the probe does less work than the
 * property probe it replaces, because every assertion about the <em>answer</em> would pass with the index ignored
 * entirely.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/INDEX.md">spec/INDEX.md</a> section 3.3
 */
@Tag("unit")
public class CompositeIndexTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testADeclarationBackfillsWhatIsAlreadyThere() throws Exception {
		try (GraphStore store = stores.open()) {
			Set<Long> berliners = new HashSet<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					long id = store.createNode(i % 2 == 0 ? "Person" : "Company",
						Properties.of().set("city", i % 3 == 0 ? "Berlin" : "Hamburg"));
					if (i % 2 == 0 && i % 3 == 0) {
						berliners.add(id);
					}
				}
				txn.commit();
			}

			assertFalse(store.hasIndex("Person", "city"));
			store.createIndex("Person", "city");
			assertTrue(store.hasIndex("Person", "city"));
			assertEquals(List.of(new CompositeIndex("Person", "city", false)), store.compositeIndexes());

			assertEquals(berliners, new HashSet<>(store.nodesByLabelAndProperty("Person", "city", "Berlin")));
			// The probe itself, not just the answer: every candidate the tree returns already carries the label.
			assertEquals(berliners.size(),
				((GraphStoreImpl) store).indexCatalog().nodesByLabelAndProperty("Person", "city", "Berlin").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The reason the index exists: a property that is nearly constant.
	 *
	 * <p>
	 * The property probe returns every node holding the value and the caller reads each one to check its label. The
	 * composite probe returns the labelled ones. The answers are identical — the difference is the number of candidate
	 * reads, which is what this asserts.
	 * </p>
	 */
	@Test
	public void testTheProbeIsNarrowerThanAPropertyProbe() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			Set<Long> activePeople = new HashSet<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 1000; i++) {
					// Every node is active; one in fifty is a :Person.
					long id = store.createNode(i % 50 == 0 ? "Person" : "Event", Properties.of().set("active", true));
					if (i % 50 == 0) {
						activePeople.add(id);
					}
				}
				txn.commit();
			}
			store.createIndex("Person", "active");

			int propertyCandidates = impl.indexCatalog().nodesByProperty("active", true).size();
			int compositeCandidates = impl.indexCatalog().nodesByLabelAndProperty("Person", "active", true).size();
			assertEquals(1000, propertyCandidates, "the property probe cannot narrow a constant property");
			assertEquals(20, compositeCandidates, "the composite probe must return only the labelled nodes");
			assertEquals(activePeople, new HashSet<>(store.nodesByLabelAndProperty("Person", "active", true)));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Without a declaration the same call still answers correctly, through the property probe plus a label filter. */
	@Test
	public void testTheAnswerIsTheSameWithoutADeclaration() throws Exception {
		try (GraphStore store = stores.open()) {
			long person = store.createNode("Person", Properties.of().set("city", "Berlin"));
			store.createNode("Company", Properties.of().set("city", "Berlin"));
			store.createNode("Person", Properties.of().set("city", "Hamburg"));

			assertFalse(store.hasIndex("Person", "city"));
			assertEquals(List.of(person), store.nodesByLabelAndProperty("Person", "city", "Berlin"));

			store.createIndex("Person", "city");
			assertEquals(List.of(person), store.nodesByLabelAndProperty("Person", "city", "Berlin"));
			assertEquals(List.of(), store.nodesByLabelAndProperty("Person", "city", "Paris"));
			assertEquals(List.of(), store.nodesByLabelAndProperty("Nonexistent", "city", "Berlin"));
			assertEquals(List.of(), store.nodesByLabelAndProperty("Person", "nosuchkey", "Berlin"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** The four hooks, one at a time: create, change a property, delete. */
	@Test
	public void testTheIndexFollowsWrites() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Person", "email");

			long id = store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			assertEquals(List.of(id), store.nodesByLabelAndProperty("Person", "email", "wes@example.com"));

			store.setNodeProperties(id, Properties.of().set("email", "owen@example.com"));
			assertEquals(List.of(), store.nodesByLabelAndProperty("Person", "email", "wes@example.com"),
				"the stale entry must be gone");
			assertEquals(List.of(id), store.nodesByLabelAndProperty("Person", "email", "owen@example.com"));

			store.deleteNode(id);
			assertEquals(List.of(), store.nodesByLabelAndProperty("Person", "email", "owen@example.com"));
			assertEquals(0, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize(),
				"a composite entry outlived its node");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The hook a composite index makes interesting for the first time.
	 *
	 * <p>
	 * Adding a label changes no property value, and before this index existed there was nothing for it to do beyond
	 * the label tree. Now it has to insert an entry for every declared key the node already carries — and removing the
	 * label has to take it away again, or the probe returns a node that no longer carries the label.
	 * </p>
	 */
	@Test
	public void testAddingALabelIndexesThePropertiesTheNodeAlreadyHas() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Person", "email");
			long id = store.createNode("Company", Properties.of().set("email", "info@example.com"));
			assertEquals(List.of(), store.nodesByLabelAndProperty("Person", "email", "info@example.com"));

			assertTrue(store.addNodeLabel(id, "Person"));
			assertEquals(List.of(id), store.nodesByLabelAndProperty("Person", "email", "info@example.com"));
			assertEquals(1, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());

			assertTrue(store.removeNodeLabel(id, "Person"));
			assertEquals(List.of(), store.nodesByLabelAndProperty("Person", "email", "info@example.com"));
			assertEquals(0, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize(),
				"the entry must go with the label");
			store.verify(VerifyLevel.FULL);
		}
	}

	/** A property written while the node carries the label, and one written before it did. */
	@Test
	public void testAPropertyChangeOnAnUnlabelledNodeIsNotIndexed() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Person", "email");
			long id = store.createNode("Company");
			store.setNodeProperties(id, Properties.of().set("email", "info@example.com"));
			assertEquals(0, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());

			store.addNodeLabel(id, "Person");
			store.setNodeProperties(id, Properties.of().set("email", "hello@example.com"));
			assertEquals(List.of(id), store.nodesByLabelAndProperty("Person", "email", "hello@example.com"));
			assertEquals(List.of(), store.nodesByLabelAndProperty("Person", "email", "info@example.com"));
			assertEquals(1, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The declaration is on the catalog page, so it is transactional like everything else on that page.
	 *
	 * <p>
	 * The first transaction against a fresh store is also the one that allocates the page, which is the case
	 * {@code ensureInitialised} re-derives its hint for — an index declared and rolled back has to read back as "not
	 * declared" rather than as a stale id.
	 * </p>
	 */
	@Test
	public void testARolledBackDeclarationLeavesNothing() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("Person", Properties.of().set("email", "wes@example.com"));
				store.createIndex("Person", "email");
				// No commit.
			}

			assertFalse(store.hasIndex("Person", "email"));
			assertEquals(List.of(), store.compositeIndexes());
			assertEquals(List.of(), store.nodesByLabelAndProperty("Person", "email", "wes@example.com"));

			long kept = store.createNode("Person", Properties.of().set("email", "kept@example.com"));
			store.createIndex("Person", "email");
			assertTrue(store.hasIndex("Person", "email"));
			assertEquals(List.of(kept), store.nodesByLabelAndProperty("Person", "email", "kept@example.com"));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testTheDeclarationSurvivesAReopen() throws Exception {
		List<Long> ids = new ArrayList<>();
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 300; i++) {
					ids.add(store.createNode("Person", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			store.createIndex("Person", "i");
			store.checkpoint();
		}
		try (GraphStore store = stores.open()) {
			assertTrue(store.hasIndex("Person", "i"));
			assertEquals(List.of(new CompositeIndex("Person", "i", false)), store.compositeIndexes());
			assertEquals(List.of(ids.get(42)), store.nodesByLabelAndProperty("Person", "i", 42));
			// And it is still maintained after the reopen.
			long fresh = store.createNode("Person", Properties.of().set("i", 9999));
			assertEquals(List.of(fresh), store.nodesByLabelAndProperty("Person", "i", 9999));
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * Compaction moves ids, so every entry naming one is rebuilt by {@code resetTrees} plus a re-insert.
	 * <p>
	 * The declaration itself must <b>not</b> be rebuilt away: it lives on the catalog page rather than in the tree.
	 */
	@Test
	public void testCompactionRebuildsTheCompositeEntries() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 400; i++) {
					ids.add(store.createNode("Person", Properties.of().set("city", "Berlin").set("i", i)));
				}
				txn.commit();
			}
			store.createIndex("Person", "city");
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 400; i += 2) {
					store.deleteNode(ids.get(i));
				}
				txn.commit();
			}

			store.compact(CompactionOptions.always());
			store.verify(VerifyLevel.FULL);

			assertTrue(store.hasIndex("Person", "city"), "the declaration is not part of the tree");
			List<Long> found = store.nodesByLabelAndProperty("Person", "city", "Berlin");
			assertEquals(200, found.size());
			assertEquals(new HashSet<>(store.nodesByLabel("Person")), new HashSet<>(found));
		}
	}

	/** Re-declaring is a no-op rather than a second set of entries. */
	@Test
	public void testRedeclaringDoesNotDoubleTheEntries() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Person", "city");
			store.createNode("Person", Properties.of().set("city", "Berlin"));
			store.createIndex("Person", "city");
			assertEquals(1, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());
			assertEquals(1, store.compositeIndexes().size());
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Several declarations coexist, including two over the same key with different labels. */
	@Test
	public void testSeveralDeclarationsCoexist() throws Exception {
		try (GraphStore store = stores.open()) {
			long person = store.createNode("Person", Properties.of().set("city", "Berlin"));
			long company = store.createNode("Company", Properties.of().set("city", "Berlin"));
			store.createIndex("Person", "city");
			store.createIndex("Company", "city");

			assertEquals(List.of(person), store.nodesByLabelAndProperty("Person", "city", "Berlin"));
			assertEquals(List.of(company), store.nodesByLabelAndProperty("Company", "city", "Berlin"));
			assertEquals(2, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- dropping

	/** Dropping gives the entries back and leaves the store answering as if the index had never been declared. */
	@Test
	public void testDroppingRemovesTheEntriesAndTheDeclaration() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			List<Long> people = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 100; i++) {
					long id = store.createNode("Person", Properties.of().set("city", "Berlin"));
					people.add(id);
				}
				txn.commit();
			}
			store.createIndex("Person", "city");
			assertEquals(100, impl.indexCatalog().compositeIndexSize());

			store.dropIndex("Person", "city");
			assertFalse(store.hasIndex("Person", "city"));
			assertEquals(List.of(), store.compositeIndexes());
			assertEquals(0, impl.indexCatalog().compositeIndexSize(), "the entries must go with the declaration");
			// The answer is unchanged; only the path to it is.
			assertEquals(new HashSet<>(people), new HashSet<>(store.nodesByLabelAndProperty("Person", "city", "Berlin")));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Dropping one of several leaves the others intact — entries included. */
	@Test
	public void testDroppingOneLeavesTheOthers() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			long person = store.createNode("Person", Properties.of().set("city", "Berlin"));
			long company = store.createNode("Company", Properties.of().set("city", "Berlin"));
			store.createIndex("Person", "city");
			store.createIndex("Company", "city");
			store.createIndex("Person", "name");
			assertEquals(2, impl.indexCatalog().compositeIndexSize());

			store.dropIndex("Person", "city");

			assertFalse(store.hasIndex("Person", "city"));
			assertTrue(store.hasIndex("Company", "city"));
			assertTrue(store.hasIndex("Person", "name"), "the compacting list must not lose the entries it shifts");
			assertEquals(List.of(new CompositeIndex("Company", "city", false),
				new CompositeIndex("Person", "name", false)), store.compositeIndexes(),
				"the survivors keep the order they were declared in");
			assertEquals(1, impl.indexCatalog().compositeIndexSize(), "only the dropped pair's entries go");
			assertEquals(List.of(company), store.nodesByLabelAndProperty("Company", "city", "Berlin"));
			assertEquals(List.of(person), store.nodesByLabelAndProperty("Person", "city", "Berlin"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** The tree comes back after the last declaration was dropped, which is the state a fresh store is in. */
	@Test
	public void testTheIndexCanBeDeclaredAgainAfterADrop() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("Person", Properties.of().set("city", "Berlin"));
			store.createIndex("Person", "city");
			store.dropIndex("Person", "city");

			store.createIndex("Person", "city");
			assertEquals(List.of(id), store.nodesByLabelAndProperty("Person", "city", "Berlin"),
				"the second declaration has to backfill exactly as the first did");
			assertEquals(1, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Writes after a drop must not keep maintaining an index nothing declares. */
	@Test
	public void testTheHooksStopAfterADrop() throws Exception {
		try (GraphStore store = stores.open()) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			store.createIndex("Person", "city");
			store.createIndex("Person", "name");
			store.dropIndex("Person", "city");

			long id = store.createNode("Person", Properties.of().set("city", "Berlin").set("name", "wes"));
			assertEquals(1, impl.indexCatalog().compositeIndexSize(), "only the surviving pair may be maintained");
			assertEquals(List.of(id), store.nodesByLabelAndProperty("Person", "name", "wes"));
			store.deleteNode(id);
			assertEquals(0, impl.indexCatalog().compositeIndexSize());
			store.verify(VerifyLevel.FULL);
		}
	}

	/** A label-scoped unique constraint is one declaration with its index, so dropping it drops the constraint. */
	@Test
	public void testDroppingAUniqueDeclarationDropsTheConstraint() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("Person", "email");
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			assertThrows(ConstraintViolationException.class,
				() -> store.createNode("Person", Properties.of().set("email", "wes@example.com")));

			store.dropIndex("Person", "email");
			assertEquals(List.of(), store.compositeIndexes());
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			assertEquals(2, store.nodesByLabelAndProperty("Person", "email", "wes@example.com").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Dropping what was never declared names the pair rather than succeeding quietly. */
	@Test
	public void testDroppingAnUndeclaredPairIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(IllegalArgumentException.class, () -> store.dropIndex("Person", "city"));
			store.createIndex("Person", "city");
			assertThrows(IllegalArgumentException.class, () -> store.dropIndex("Person", "cityy"));
			assertThrows(IllegalArgumentException.class, () -> store.dropIndex("Persson", "city"));
			assertTrue(store.hasIndex("Person", "city"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** A rolled-back drop keeps the index, entries and all. */
	@Test
	public void testARolledBackDropKeepsTheIndex() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("Person", Properties.of().set("city", "Berlin"));
			store.createIndex("Person", "city");
			store.createIndex("Person", "name");

			try (Transaction txn = store.begin()) {
				store.dropIndex("Person", "city");
				// No commit.
			}

			assertTrue(store.hasIndex("Person", "city"));
			assertEquals(List.of(id), store.nodesByLabelAndProperty("Person", "city", "Berlin"),
				"a discarded drop must leave the entries where they were");
			assertEquals(1, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testADropSurvivesAReopen() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Person", Properties.of().set("city", "Berlin"));
			store.createIndex("Person", "city");
			store.dropIndex("Person", "city");
			store.checkpoint();
		}
		try (GraphStore store = stores.open()) {
			assertFalse(store.hasIndex("Person", "city"));
			assertEquals(List.of(), store.compositeIndexes());
			assertEquals(1, store.nodesByLabelAndProperty("Person", "city", "Berlin").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A vector value, which is the one type whose hash had to be taught to depend on content rather than identity.
	 * <p>
	 * The composite key mixes that same {@code valueHash} in, and the verification uses its partner
	 * {@code valueEquals} — a composite index that reached for {@code Objects.deepEquals} instead would find the entry
	 * and then discard the node it names.
	 */
	@Test
	public void testAVectorValuedCompositeIndex() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Face", "embedding");
			long id = store.createNode("Face", Properties.of().set("embedding", new float[] { 1f, 2f, 3f }));
			store.createNode("Photo", Properties.of().set("embedding", new float[] { 1f, 2f, 3f }));

			assertEquals(List.of(id),
				store.nodesByLabelAndProperty("Face", "embedding", new float[] { 1f, 2f, 3f }));
			assertEquals(List.of(),
				store.nodesByLabelAndProperty("Face", "embedding", new float[] { 1f, 2f }));

			store.deleteNode(id);
			assertEquals(0, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The key is a hash of the triple, so collisions are possible and must cost a wasted read rather than a wrong
	 * answer. Enough distinct values to make the tree deep, with every lookup verified.
	 */
	@Test
	public void testEveryCandidateIsVerified() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Person", "i");
			List<Long> ids = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 5000; i++) {
					ids.add(store.createNode("Person", Properties.of().set("i", i)));
				}
				txn.commit();
			}
			for (int i = 0; i < 5000; i += 97) {
				assertEquals(List.of(ids.get(i)), store.nodesByLabelAndProperty("Person", "i", i),
					"a collision must not turn into a wrong answer at i=" + i);
			}
			assertEquals(5000, ((GraphStoreImpl) store).indexCatalog().compositeIndexSize());
			store.verify(VerifyLevel.FULL);
		}
	}
}
