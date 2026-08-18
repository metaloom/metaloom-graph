package io.metaloom.graph.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a grant means, and what it refuses to mean.
 *
 * <p>
 * The matching rule is three lines long, which is the point: an authorization decision that needed a paragraph to
 * describe would be one nobody could audit by reading it.
 * </p>
 */
@Tag("unit")
public class GrantTest {

	// ---------------------------------------------------------------- matching

	@Test
	public void testANamedGrantMatchesOnlyThatDatabase() {
		Grant grant = new Grant(Privilege.MERGE, "sales");
		assertTrue(grant.permits(Privilege.MERGE, "sales"));
		assertFalse(grant.permits(Privilege.MERGE, "hr"), "a grant on one database is not a grant on another");
		assertFalse(grant.permits(Privilege.DELETE, "sales"), "a grant of one privilege is not a grant of another");
	}

	@Test
	public void testAWildcardMatchesEveryDatabase() {
		Grant grant = Grant.on(Privilege.MATCH);
		assertTrue(grant.isWildcard());
		assertTrue(grant.permits(Privilege.MATCH, "sales"));
		assertTrue(grant.permits(Privilege.MATCH, "anything-created-later"));
		assertFalse(grant.permits(Privilege.CREATE, "sales"));
	}

	/**
	 * A grant on one database does not answer "may you do this everywhere".
	 *
	 * <p>
	 * The question is asked with {@link Grant#ALL_DATABASES} as the target by routes that are not about a single
	 * database. Answering yes here would let a principal granted {@code MATCH ON sales} through a check meant for
	 * every store at once.
	 * </p>
	 */
	@Test
	public void testANamedGrantDoesNotAnswerTheEverywhereQuestion() {
		assertFalse(new Grant(Privilege.MATCH, "sales").permits(Privilege.MATCH, Grant.ALL_DATABASES));
		assertTrue(Grant.on(Privilege.MATCH).permits(Privilege.MATCH, Grant.ALL_DATABASES));
	}

	// ---------------------------------------------------------------- refusals

	/**
	 * An administrative privilege cannot be scoped to a database.
	 *
	 * <p>
	 * Refused rather than quietly widened to {@code *} or narrowed to nothing. A user is not a per-database object, so
	 * {@code MANAGE_USERS ON sales} is a sentence with no meaning — and a grant that silently meant something other
	 * than what it said would be the worst outcome available here.
	 * </p>
	 */
	@Test
	public void testAGlobalOnlyPrivilegeCannotBeScopedToADatabase() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> new Grant(Privilege.MANAGE_USERS, "sales"));
		assertTrue(refused.getMessage().contains("MANAGE_USERS"), refused.getMessage());
		assertTrue(refused.getMessage().contains("ON *"), refused.getMessage());

		assertThrows(IllegalArgumentException.class, () -> new Grant(Privilege.MANAGE_ROLES, "sales"));
		// The same privilege on every database is exactly what it is for.
		assertTrue(Grant.on(Privilege.MANAGE_USERS).permits(Privilege.MANAGE_USERS, Grant.ALL_DATABASES));
	}

	/** A database name in a grant faces the same allow-list it faces everywhere else. */
	@Test
	public void testADatabaseNameInAGrantIsValidated() {
		assertThrows(IllegalArgumentException.class, () -> new Grant(Privilege.MATCH, "../etc"));
		assertThrows(IllegalArgumentException.class, () -> new Grant(Privilege.MATCH, "has space"));
		assertThrows(IllegalArgumentException.class, () -> new Grant(Privilege.MATCH, "-leading-dash"));
		assertThrows(IllegalArgumentException.class, () -> new Grant(Privilege.MATCH, ""));
	}

	// ---------------------------------------------------------------- text form

	@Test
	public void testTheTextFormRoundTrips() {
		for (String text : new String[] { "MATCH ON *", "MERGE ON sales", "MANAGE_ROLES ON *", "DROP_INDEX ON a_b-c" }) {
			assertEquals(text, Grant.parse(text).toString(), "what auth list prints must be what auth grant accepts");
		}
	}

	@Test
	public void testTheTextFormIsCaseInsensitiveInBothKeywords() {
		Grant expected = new Grant(Privilege.MERGE, "sales");
		assertEquals(expected, Grant.parse("merge on sales"));
		assertEquals(expected, Grant.parse("Merge On sales"));
		assertEquals(expected, Grant.parse("  MERGE ON sales  "));
	}

	@Test
	public void testAMalformedGrantSaysWhatAGrantLooksLike() {
		for (String text : new String[] { "MERGE", "MERGE sales", "", "   ", "ON sales", "MERGE ON " }) {
			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Grant.parse(text),
				"'" + text + "' is not a grant");
			assertTrue(refused.getMessage().contains("ON"), refused.getMessage());
		}
	}

	/** A database whose name contains "on" must not be mistaken for the separator. */
	@Test
	public void testADatabaseNameContainingOnStillParses() {
		assertEquals(new Grant(Privilege.MATCH, "london"), Grant.parse("MATCH ON london"));
	}

	// ---------------------------------------------------------------- privilege names

	@Test
	public void testAnUnknownPrivilegeNamesWhatIsAllowed() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> Privilege.parse("SUPERUSER"));
		assertTrue(refused.getMessage().contains("SUPERUSER"), refused.getMessage());
		assertTrue(refused.getMessage().contains("MATCH"), "the message lists what is allowed: " + refused.getMessage());
	}

	@Test
	public void testPrivilegeNamesAreCaseInsensitive() {
		assertEquals(Privilege.MERGE, Privilege.parse("merge"));
		assertEquals(Privilege.LOAD_CSV, Privilege.parse("Load_Csv"));
	}

	/**
	 * The write flags have to match what the clause mapping believes.
	 *
	 * <p>
	 * {@code LOAD_CSV} and {@code BACKUP} are the two that are easy to get wrong, and both are reads: reading a file
	 * creates nothing, and a backup is the read that hands over everything.
	 * </p>
	 */
	@Test
	public void testWhichPrivilegesCountAsWrites() {
		assertFalse(Privilege.MATCH.isWrite());
		assertFalse(Privilege.LOAD_CSV.isWrite());
		assertFalse(Privilege.BACKUP.isWrite());
		assertFalse(Privilege.INTROSPECT.isWrite());
		assertTrue(Privilege.CREATE.isWrite());
		assertTrue(Privilege.MERGE.isWrite());
		assertTrue(Privilege.CREATE_INDEX.isWrite());
		assertTrue(Privilege.DROP_DATABASE.isWrite());
	}

	@Test
	public void testOnlyTheAdministrativePrivilegesAreGlobalOnly() {
		for (Privilege privilege : Privilege.values()) {
			boolean administrative = privilege == Privilege.MANAGE_USERS || privilege == Privilege.MANAGE_ROLES;
			assertEquals(administrative, privilege.isGlobalOnly(),
				privilege + " disagrees about whether it can be scoped to a database");
		}
	}
}
