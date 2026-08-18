package io.metaloom.graph.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.AuthFormatException;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.api.Role;
import io.metaloom.graph.core.api.User;

/**
 * The identity format, field by field.
 *
 * <p>
 * {@code AuthHostileInputTest} covers what a damaged file does; this covers what an intact one means.
 * </p>
 */
@Tag("unit")
public class AuthCodecTest {

	private static PasswordHash cheap(String password) {
		return PasswordHash.of(password.toCharArray(), 1);
	}

	private static AuthFile sample() {
		Role analyst = new Role("analyst", List.of(
			Grant.on(Privilege.MATCH),
			new Grant(Privilege.MERGE, "sales")));
		Role admin = new Role(Role.ADMIN, List.of(Grant.on(Privilege.MANAGE_USERS), Grant.on(Privilege.MANAGE_ROLES)));
		StoredUser alice = new StoredUser(new User("alice", true, 111L, 222L,
			List.of("analyst"), List.of(new Grant(Privilege.BACKUP, "sales"))), cheap("s1"));
		StoredUser bob = new StoredUser(new User("bob", false, 333L, 444L, List.of(), List.of()), cheap("s2"));
		return new AuthFile(42, List.of(analyst, admin), List.of(alice, bob));
	}

	@Test
	public void testEveryFieldSurvivesTheRoundTrip() {
		AuthFile decoded = AuthCodec.decode(AuthCodec.encode(sample()));

		assertEquals(42, decoded.generation());
		assertEquals(2, decoded.roles().size());
		assertEquals(2, decoded.users().size());

		Role analyst = decoded.role("analyst");
		assertEquals(List.of(Grant.on(Privilege.MATCH), new Grant(Privilege.MERGE, "sales")), analyst.grants());

		User alice = decoded.user("alice").user();
		assertTrue(alice.enabled());
		assertEquals(111L, alice.createdAtEpochMillis());
		assertEquals(222L, alice.passwordChangedAtEpochMillis());
		assertEquals(List.of("analyst"), alice.roles());
		assertEquals(List.of(new Grant(Privilege.BACKUP, "sales")), alice.grants());

		User bob = decoded.user("bob").user();
		assertFalse(bob.enabled(), "the disabled flag is one bit and must survive");
		assertTrue(decoded.user("bob").hash().verify("s2".toCharArray()), "the credential still verifies");
	}

	/** Encoding is deterministic, so a file rewritten without an edit is byte-identical and no reload is triggered. */
	@Test
	public void testEncodingIsDeterministic() {
		AuthFile file = sample();
		assertTrue(java.util.Arrays.equals(AuthCodec.encode(file), AuthCodec.encode(file)));
	}

	@Test
	public void testAnEmptyFileRoundTrips() {
		AuthFile decoded = AuthCodec.decode(AuthCodec.encode(AuthFile.empty()));
		assertEquals(0, decoded.generation());
		assertTrue(decoded.roles().isEmpty());
		assertTrue(decoded.users().isEmpty());
	}

	// ---------------------------------------------------------------- refusals

	@Test
	public void testADuplicateUserIsRefusedRatherThanResolved() {
		StoredUser first = new StoredUser(new User("alice", true, 1, 1, List.of(), List.of()), cheap("a"));
		StoredUser second = new StoredUser(new User("alice", true, 2, 2, List.of(), List.of()), cheap("b"));
		byte[] bytes = AuthCodec.encode(new AuthFile(1, List.of(), List.of(first, second)));

		AuthFormatException refused = assertThrows(AuthFormatException.class, () -> AuthCodec.decode(bytes));
		assertTrue(refused.getMessage().contains("twice"), refused.getMessage());
		assertTrue(refused.getMessage().contains("alice"), refused.getMessage());
	}

	@Test
	public void testADuplicateRoleIsRefused() {
		byte[] bytes = AuthCodec.encode(new AuthFile(1,
			List.of(new Role("analyst", List.of()), new Role("analyst", List.of(Grant.on(Privilege.DROP_DATABASE)))),
			List.of()));
		assertTrue(assertThrows(AuthFormatException.class, () -> AuthCodec.decode(bytes)).getMessage()
			.contains("twice"));
	}

	/**
	 * A user naming a role the file does not define is refused, not ignored.
	 *
	 * <p>
	 * Ignoring it would turn a corrupt file into a user with fewer grants than the file says they have — a wrong
	 * answer that looks exactly like a right one, and one that would surface as a permission bug nobody could explain.
	 * </p>
	 */
	@Test
	public void testAUserNamingANonexistentRoleIsRefused() {
		StoredUser orphan = new StoredUser(new User("alice", true, 1, 1, List.of("ghost"), List.of()), cheap("a"));
		byte[] bytes = AuthCodec.encode(new AuthFile(1, List.of(), List.of(orphan)));

		AuthFormatException refused = assertThrows(AuthFormatException.class, () -> AuthCodec.decode(bytes));
		assertTrue(refused.getMessage().contains("ghost"), refused.getMessage());
		assertTrue(refused.getMessage().contains("does not define"), refused.getMessage());
	}

	/** Roles are declared before users in the file, so a forward reference cannot be resolved and is not attempted. */
	@Test
	public void testRolesAreReadBeforeTheUsersThatNameThem() {
		AuthFile file = new AuthFile(1,
			List.of(new Role("late", List.of(Grant.on(Privilege.MATCH)))),
			List.of(new StoredUser(new User("alice", true, 1, 1, List.of("late"), List.of()), cheap("a"))));
		assertEquals(List.of(Grant.on(Privilege.MATCH)), AuthCodec.decode(AuthCodec.encode(file))
			.effectiveGrants("alice"));
	}

	// ---------------------------------------------------------------- effective grants

	@Test
	public void testEffectiveGrantsCombineDirectAndRoleGrantsWithoutDuplicates() {
		Role a = new Role("a", List.of(Grant.on(Privilege.MATCH), new Grant(Privilege.MERGE, "sales")));
		Role b = new Role("b", List.of(Grant.on(Privilege.MATCH), Grant.on(Privilege.BACKUP)));
		StoredUser alice = new StoredUser(new User("alice", true, 1, 1, List.of("a", "b"),
			List.of(new Grant(Privilege.DELETE, "hr"))), cheap("s"));

		AuthFile decoded = AuthCodec.decode(AuthCodec.encode(new AuthFile(1, List.of(a, b), List.of(alice))));
		List<Grant> effective = decoded.effectiveGrants("alice");

		assertTrue(effective.contains(new Grant(Privilege.DELETE, "hr")), "a direct grant counts");
		assertTrue(effective.contains(Grant.on(Privilege.MATCH)));
		assertTrue(effective.contains(new Grant(Privilege.MERGE, "sales")));
		assertTrue(effective.contains(Grant.on(Privilege.BACKUP)));
		assertEquals(4, effective.size(), "MATCH is granted by both roles and must appear once");
	}

	@Test
	public void testEffectiveGrantsOfAnUnknownUserAreEmpty() {
		assertTrue(AuthCodec.decode(AuthCodec.encode(sample())).effectiveGrants("nobody").isEmpty());
	}

	/** The public view of a user carries no credential, which is the property the type split exists to guarantee. */
	@Test
	public void testThePublicUserListCarriesNoHash() {
		for (User user : AuthCodec.decode(AuthCodec.encode(sample())).publicUsers()) {
			assertFalse(user.toString().contains("pbkdf2"), "a User's toString leaked a hash: " + user);
		}
	}

	/** A StoredUser must not print its credential either, because that is the object a log line would catch. */
	@Test
	public void testAStoredUserDoesNotPrintItsCredential() {
		StoredUser stored = new StoredUser(new User("alice", true, 1, 1, List.of(), List.of()), cheap("secret"));
		assertEquals("StoredUser[alice]", stored.toString());
	}
}
