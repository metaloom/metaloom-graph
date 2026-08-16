package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.CompositeIndex;
import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Unique property indexes — the Phase 7 deliverable that the B+tree could not express until it was asked to.
 *
 * @see <a href="../../../../../../../../spec/PLAN.md">spec/PLAN.md</a> Phase 7
 */
@Tag("unit")
public class UniqueConstraintTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testADuplicateIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("email");
			assertEquals(List.of("email"), store.uniqueConstraints());

			long first = store.createNode("Person", Properties.of().set("email", "wes@example.com"));

			ConstraintViolationException failure = assertThrows(ConstraintViolationException.class,
				() -> store.createNode("Person", Properties.of().set("email", "wes@example.com")));
			assertEquals("email", failure.key());
			assertEquals(first, failure.existingId(), "the message must name the element that already holds it");

			// A different value is fine, and the refusal left nothing behind.
			store.createNode("Person", Properties.of().set("email", "owen@example.com"));
			assertEquals(2, store.nodesByLabel("Person").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	/** A value hash collision must not refuse a legitimate write: candidates are verified against the stored value. */
	@Test
	public void testOnlyRealDuplicatesAreRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("i");
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 5000; i++) {
					store.createNode("N", Properties.of().set("i", i));
				}
				txn.commit();
			}
			assertEquals(5000, store.nodesByLabel("N").size(),
				"five thousand distinct values must all be accepted");
			assertThrows(ConstraintViolationException.class,
				() -> store.createNode("N", Properties.of().set("i", 2500)));
		}
	}

	@Test
	public void testUpdatingIntoADuplicateIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("email");
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			long owen = store.createNode("Person", Properties.of().set("email", "owen@example.com"));

			assertThrows(ConstraintViolationException.class,
				() -> store.setNodeProperties(owen, Properties.of().set("email", "wes@example.com")));

			// Its own value is not a conflict with itself.
			store.setNodeProperties(owen, Properties.of().set("email", "owen@example.com").set("age", 55));
			assertEquals(55, store.nodeProperties(owen).getInt("age"));
		}
	}

	/** Freeing the value frees the constraint: a deleted node holds nothing. */
	@Test
	public void testDeletingTheHolderReleasesTheValue() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("email");
			long first = store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			store.deleteNode(first);

			long second = store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			assertTrue(second != first || !store.nodeExists(first));
			assertEquals(1, store.nodesByProperty("email", "wes@example.com").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Declaring a constraint that the data already breaks must fail rather than record something untrue. */
	@Test
	public void testDeclaringAConstraintOverDuplicatesIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));

			assertThrows(ConstraintViolationException.class, () -> store.createUniqueConstraint("email"));
			assertTrue(store.uniqueConstraints().isEmpty(),
				"a refused declaration must not be recorded");
		}
	}

	/**
	 * A constraint on a vector property fires, which it could not while the value hashed to an identity.
	 * <p>
	 * {@code conflicts()} probes with the same key the maintenance hooks write, so an unreproducible key made the
	 * probe return nothing and every duplicate was accepted — the quietest of the three symptoms of IDX-1.
	 *
	 * @see <a href="../../../../../../../../spec/INDEX_TASKS.md">spec/INDEX_TASKS.md</a> IDX-1
	 */
	@Test
	public void testADuplicateVectorIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("embedding");
			long first = store.createNode("Face", Properties.of().set("embedding", new float[] { 1f, 2f, 3f }));

			ConstraintViolationException failure = assertThrows(ConstraintViolationException.class,
				() -> store.createNode("Face", Properties.of().set("embedding", new float[] { 1f, 2f, 3f })));
			assertEquals(first, failure.existingId());

			// A vector that differs in one component is a different value.
			store.createNode("Face", Properties.of().set("embedding", new float[] { 1f, 2f, 4f }));
			assertEquals(2, store.nodesByLabel("Face").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testTheConstraintSurvivesAReopen() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("email");
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			store.checkpoint();
		}
		try (GraphStore store = stores.open()) {
			assertEquals(List.of("email"), store.uniqueConstraints());
			assertThrows(ConstraintViolationException.class,
				() -> store.createNode("Person", Properties.of().set("email", "wes@example.com")));
		}
	}

	// ---------------------------------------------------------------- label-scoped

	/**
	 * The constraint an application usually means: no two <b>:Person</b> share an email, rather than no two nodes do.
	 * <p>
	 * It cannot be expressed by the store-wide form at all, which is the argument for building it: a store-wide
	 * constraint on {@code email} would refuse a {@code :Company} holding the same address, and dropping the
	 * constraint to allow that would stop protecting the people.
	 */
	@Test
	public void testALabelScopedDuplicateIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("Person", "email");
			assertEquals(List.of(new CompositeIndex("Person", "email", true)), store.compositeIndexes());
			assertTrue(store.uniqueConstraints().isEmpty(), "a label-scoped constraint is not a store-wide one");
			assertTrue(store.hasIndex("Person", "email"), "declaring it also declares the index it probes");

			long first = store.createNode("Person", Properties.of().set("email", "wes@example.com"));

			ConstraintViolationException failure = assertThrows(ConstraintViolationException.class,
				() -> store.createNode("Person", Properties.of().set("email", "wes@example.com")));
			assertEquals("Person", failure.label());
			assertEquals("email", failure.key());
			assertEquals(first, failure.existingId());
			assertTrue(failure.getMessage().contains(":Person.email"), failure.getMessage());

			// The same value under a different label is exactly what the scope is for.
			long company = store.createNode("Company", Properties.of().set("email", "wes@example.com"));
			assertEquals(List.of(company), store.nodesByLabelAndProperty("Company", "email", "wes@example.com"));
			assertEquals(2, store.nodesByProperty("email", "wes@example.com").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The label can break the constraint on its own.
	 * <p>
	 * Two nodes may legitimately hold the same email until the second one is told it is a {@code :Person}. The check
	 * runs before the label is written, so the refusal leaves nothing behind — the ordering {@code createNode} has and
	 * {@code setNodeProperties} does not.
	 */
	@Test
	public void testAddingTheLabelCanViolateIt() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("Person", "email");
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			long company = store.createNode("Company", Properties.of().set("email", "wes@example.com"));

			assertThrows(ConstraintViolationException.class, () -> store.addNodeLabel(company, "Person"));
			assertEquals(List.of("Company"), store.nodeLabels(company), "a refused label must not be written");
			assertEquals(1, store.nodesByLabel("Person").size());

			// A label carrying no conflict is still added.
			assertTrue(store.addNodeLabel(company, "Supplier"));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testUpdatingIntoALabelScopedDuplicateIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("Person", "email");
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			long owen = store.createNode("Person", Properties.of().set("email", "owen@example.com"));

			assertThrows(ConstraintViolationException.class,
				() -> store.setNodeProperties(owen, Properties.of().set("email", "wes@example.com")));

			// Its own value is not a conflict with itself.
			store.setNodeProperties(owen, Properties.of().set("email", "owen@example.com").set("age", 55));
			assertEquals(55, store.nodeProperties(owen).getInt("age"));
		}
	}

	/** Declaring over data the label already breaks fails; the same data under two labels does not. */
	@Test
	public void testDeclaringALabelScopedConstraintOverDuplicates() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			store.createNode("Company", Properties.of().set("email", "wes@example.com"));
			// Scoped to :Person, the two are not duplicates.
			store.createUniqueConstraint("Person", "email");
			assertTrue(store.hasIndex("Person", "email"));

			// Scoped to nothing, they are — which is the distinction the whole feature exists to make.
			assertThrows(ConstraintViolationException.class, () -> store.createUniqueConstraint("email"));
			assertTrue(store.uniqueConstraints().isEmpty(), "a refused declaration must not be recorded");

			// A second :Company on the same address, which the :Company scope does break.
			store.createNode("Company", Properties.of().set("email", "wes@example.com"));
			assertThrows(ConstraintViolationException.class,
				() -> store.createUniqueConstraint("Company", "email"));
			assertFalse(store.hasIndex("Company", "email"), "a refused declaration must not be recorded");
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Declaring uniqueness over a pair already indexed upgrades the declaration rather than adding a second one. */
	@Test
	public void testUniquenessUpgradesAnExistingIndex() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Person", "email");
			long first = store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			store.createUniqueConstraint("Person", "email");

			assertEquals(List.of(new CompositeIndex("Person", "email", true)), store.compositeIndexes());
			assertEquals(List.of(first), store.nodesByLabelAndProperty("Person", "email", "wes@example.com"),
				"the entries the index already had must not be duplicated or lost");
			assertThrows(ConstraintViolationException.class,
				() -> store.createNode("Person", Properties.of().set("email", "wes@example.com")));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testTheLabelScopedConstraintSurvivesAReopen() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("Person", "email");
			store.createNode("Person", Properties.of().set("email", "wes@example.com"));
			store.checkpoint();
		}
		try (GraphStore store = stores.open()) {
			assertEquals(List.of(new CompositeIndex("Person", "email", true)), store.compositeIndexes());
			assertThrows(ConstraintViolationException.class,
				() -> store.createNode("Person", Properties.of().set("email", "wes@example.com")));
			store.createNode("Company", Properties.of().set("email", "wes@example.com"));
			store.verify(VerifyLevel.FULL);
		}
	}
}
