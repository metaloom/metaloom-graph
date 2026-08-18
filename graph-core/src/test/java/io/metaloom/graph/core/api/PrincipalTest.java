package io.metaloom.graph.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one call every enforcement site makes, and the null object that keeps it to one shape.
 */
@Tag("unit")
public class PrincipalTest {

	@Test
	public void testAPrincipalHoldsOnlyWhatItWasGranted() {
		Principal alice = Principal.of("alice", List.of(
			Grant.on(Privilege.MATCH),
			new Grant(Privilege.MERGE, "sales")));

		assertTrue(alice.may(Privilege.MATCH, "sales"));
		assertTrue(alice.may(Privilege.MATCH, "hr"));
		assertTrue(alice.may(Privilege.MERGE, "sales"));
		assertFalse(alice.may(Privilege.MERGE, "hr"), "the merge grant names one database");
		assertFalse(alice.may(Privilege.DELETE, "sales"));
		assertTrue(alice.authenticated());
		assertEquals("alice", alice.name());
	}

	/**
	 * A null database is the everywhere question, not a wildcard grant.
	 *
	 * <p>
	 * Front ends reach the check from routes that are not about one database, and a null must not accidentally become
	 * "any database this principal happens to hold".
	 * </p>
	 */
	@Test
	public void testANullDatabaseAsksAboutEveryDatabase() {
		Principal scoped = Principal.of("bob", List.of(new Grant(Privilege.MATCH, "sales")));
		assertFalse(scoped.may(Privilege.MATCH, null));
		assertFalse(scoped.may(Privilege.MATCH));

		Principal global = Principal.of("carol", List.of(Grant.on(Privilege.MATCH)));
		assertTrue(global.may(Privilege.MATCH, null));
		assertTrue(global.may(Privilege.MATCH));
	}

	@Test
	public void testAPrincipalWithNoGrantsMayNothing() {
		Principal nobody = Principal.of("dave", List.of());
		for (Privilege privilege : Privilege.values()) {
			assertFalse(nobody.may(privilege, "sales"), privilege + " was permitted to a principal with no grants");
		}
		assertTrue(nobody.authenticated(), "having no grants is not the same as not having logged in");
	}

	/** The grants are copied, so a later change to the caller's list cannot widen a live principal. */
	@Test
	public void testGrantsAreCopiedRatherThanAliased() {
		List<Grant> mutable = new ArrayList<>();
		mutable.add(Grant.on(Privilege.MATCH));
		Principal principal = Principal.of("eve", mutable);

		mutable.add(Grant.on(Privilege.DROP_DATABASE));

		assertFalse(principal.may(Privilege.DROP_DATABASE, "sales"),
			"a principal must not gain a privilege because the list it was built from changed");
		assertEquals(1, principal.grants().size());
	}

	@Test
	public void testAPrincipalNeedsAName() {
		assertThrows(IllegalArgumentException.class, () -> Principal.of(null, List.of()));
		assertThrows(IllegalArgumentException.class, () -> Principal.of("", List.of()));
	}

	// ---------------------------------------------------------------- the null object

	/**
	 * {@code ANONYMOUS} permits everything and is not authenticated.
	 *
	 * <p>
	 * Both halves matter. Permitting everything is what makes {@code --no-auth} mean "as it was before"; not being
	 * authenticated is what lets a front end tell 401 from 403, and lets a UI know not to draw a user menu.
	 * </p>
	 */
	@Test
	public void testAnonymousPermitsEverythingAndIsNotAuthenticated() {
		for (Privilege privilege : Privilege.values()) {
			assertTrue(Principal.ANONYMOUS.may(privilege, "sales"), privilege + " was refused to ANONYMOUS");
			assertTrue(Principal.ANONYMOUS.may(privilege), privilege + " was refused to ANONYMOUS globally");
		}
		assertFalse(Principal.ANONYMOUS.authenticated());
		assertEquals("anonymous", Principal.ANONYMOUS.name());
	}

	/**
	 * It reports no grants, rather than all of them.
	 *
	 * <p>
	 * A UI rendering "you have been granted DROP_DATABASE" to somebody nobody granted anything to would be a
	 * particular kind of lie, and {@code authenticated()} already tells a caller which world it is in.
	 * </p>
	 */
	@Test
	public void testAnonymousReportsNoGrants() {
		assertTrue(Principal.ANONYMOUS.grants().isEmpty());
	}
}
