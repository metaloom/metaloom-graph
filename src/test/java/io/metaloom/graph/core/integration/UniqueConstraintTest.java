package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
}
