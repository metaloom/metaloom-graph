package io.metaloom.graph.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The identity store as a whole: bootstrap, edits, durability, and the two independences that make recovery possible.
 */
@Tag("unit")
public class AuthStoreTest {

	@TempDir
	Path root;

	private static final char[] PASSWORD = "correct horse battery".toCharArray();

	// ---------------------------------------------------------------- bootstrap

	@Test
	public void testAFreshRootHasNobodyUntilItIsBootstrapped() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			assertTrue(auth.isEmpty());
			assertTrue(auth.users().isEmpty());
			assertEquals(0, auth.generation());

			assertTrue(auth.bootstrap(PASSWORD));

			assertFalse(auth.isEmpty());
			assertEquals(1, auth.users().size());
			assertEquals(AuthStore.ADMIN, auth.users().get(0).name());
			assertEquals(1, auth.generation());
		}
	}

	/** The first administrator holds everything, or the server is unusable the moment it starts. */
	@Test
	public void testTheBootstrappedAdminHoldsEveryPrivilegeOnEveryDatabase() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			Principal admin = auth.authenticate(AuthStore.ADMIN, PASSWORD).orElseThrow();
			for (Privilege privilege : Privilege.values()) {
				assertTrue(admin.may(privilege, "any-database"), privilege + " was not granted to the first admin");
				assertTrue(admin.may(privilege), privilege + " was not granted globally to the first admin");
			}
		}
	}

	@Test
	public void testBootstrapDoesNothingWhenThereIsAlreadyAUser() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			long generation = auth.generation();

			assertFalse(auth.bootstrap("a different password".toCharArray()));

			assertEquals(generation, auth.generation(), "a refused bootstrap must not write");
			assertTrue(auth.authenticate(AuthStore.ADMIN, PASSWORD).isPresent(),
				"the original password still works, so a restart cannot silently reset it");
		}
	}

	// ---------------------------------------------------------------- authentication

	@Test
	public void testAWrongPasswordAndAnUnknownUserAreIndistinguishable() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			assertEquals(Optional.empty(), auth.authenticate(AuthStore.ADMIN, "wrong".toCharArray()));
			assertEquals(Optional.empty(), auth.authenticate("nobody", PASSWORD));
			assertEquals(Optional.empty(), auth.authenticate(null, PASSWORD));
		}
	}

	@Test
	public void testADisabledUserCannotAuthenticateOrResolve() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			auth.createRole("analyst");
			auth.grantToRole("analyst", Grant.on(Privilege.MATCH));
			auth.createUser("alice", "secret".toCharArray(), List.of("analyst"));

			assertTrue(auth.authenticate("alice", "secret".toCharArray()).isPresent());
			assertTrue(auth.principal("alice").isPresent());

			auth.setEnabled("alice", false);

			assertTrue(auth.authenticate("alice", "secret".toCharArray()).isEmpty());
			assertTrue(auth.principal("alice").isEmpty(),
				"a live session must stop working, which is why the flag is re-checked rather than frozen at login");
		}
	}

	/**
	 * A revoke takes effect on the next request, not the next login.
	 *
	 * <p>
	 * {@link AuthStore#principal} resolves grants from the current image every time it is called, which is what makes
	 * that true — and freezing them into a session at login is the more common bug.
	 * </p>
	 */
	@Test
	public void testARevokeTakesEffectWithoutANewLogin() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			auth.createRole("analyst");
			auth.grantToRole("analyst", new Grant(Privilege.MERGE, "sales"));
			auth.createUser("alice", "secret".toCharArray(), List.of("analyst"));

			assertTrue(auth.principal("alice").orElseThrow().may(Privilege.MERGE, "sales"));

			auth.revokeFromRole("analyst", new Grant(Privilege.MERGE, "sales"));

			assertFalse(auth.principal("alice").orElseThrow().may(Privilege.MERGE, "sales"));
		}
	}

	// ---------------------------------------------------------------- edits

	@Test
	public void testRolesAndGrantsCompose() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			auth.createRole("reader");
			auth.createRole("loader");
			auth.grantToRole("reader", Grant.on(Privilege.MATCH));
			auth.grantToRole("loader", new Grant(Privilege.MERGE, "sales"));
			auth.createUser("alice", "secret".toCharArray(), List.of("reader", "loader"));

			Principal alice = auth.authenticate("alice", "secret".toCharArray()).orElseThrow();
			assertTrue(alice.may(Privilege.MATCH, "hr"));
			assertTrue(alice.may(Privilege.MERGE, "sales"));
			assertFalse(alice.may(Privilege.MERGE, "hr"));
			assertFalse(alice.may(Privilege.DROP_DATABASE, "sales"));
		}
	}

	@Test
	public void testGrantingSomethingTwiceIsNotAWrite() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			auth.createRole("reader");
			auth.grantToRole("reader", Grant.on(Privilege.MATCH));
			long generation = auth.generation();

			auth.grantToRole("reader", Grant.on(Privilege.MATCH));

			assertEquals(generation, auth.generation(),
				"a no-op edit must not burn a generation, or every reader reloads for nothing");
		}
	}

	/**
	 * Dropping a role takes every reference to it with it.
	 *
	 * <p>
	 * Leaving one behind would write a file this build's own decoder refuses — an edit that succeeds and leaves the
	 * server unable to start, which is the worst shape a bug here could take.
	 * </p>
	 */
	@Test
	public void testDroppingARoleRemovesItFromEveryUser() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			auth.createRole("temporary");
			auth.grantToRole("temporary", Grant.on(Privilege.MATCH));
			auth.createUser("alice", "secret".toCharArray(), List.of("temporary"));

			auth.dropRole("temporary");

			assertFalse(auth.user("alice").orElseThrow().hasRole("temporary"));
			assertFalse(auth.principal("alice").orElseThrow().may(Privilege.MATCH, "sales"));
		}
		// The proof that the file is still readable: reopening parses it.
		try (AuthStore reopened = AuthStore.open(root)) {
			assertTrue(reopened.user("alice").isPresent());
		}
	}

	@Test
	public void testTheLastUserCannotBeRemoved() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			AuthException refused = assertThrows(AuthException.class, () -> auth.dropUser(AuthStore.ADMIN));
			assertTrue(refused.getMessage().contains("only user"), refused.getMessage());
			assertFalse(auth.isEmpty());
		}
	}

	@Test
	public void testEditsAgainstThingsThatAreNotThereAreRefused() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			assertThrows(AuthException.class, () -> auth.dropUser("nobody"));
			assertThrows(AuthException.class, () -> auth.setPassword("nobody", PASSWORD));
			assertThrows(AuthException.class, () -> auth.dropRole("nothing"));
			assertThrows(AuthException.class, () -> auth.grantToRole("nothing", Grant.on(Privilege.MATCH)));
			assertThrows(AuthException.class, () -> auth.createUser("bob", PASSWORD, List.of("nothing")));
			auth.createRole("reader");
			assertThrows(AuthException.class, () -> auth.createRole("reader"));
		}
	}

	@Test
	public void testAUserNameIsValidatedBeforeItIsWritten() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			assertThrows(IllegalArgumentException.class, () -> auth.createUser("../etc", PASSWORD, List.of()));
			assertThrows(IllegalArgumentException.class, () -> auth.createUser("has space", PASSWORD, List.of()));
			assertThrows(IllegalArgumentException.class, () -> auth.createRole("bad/name"));
		}
	}

	// ---------------------------------------------------------------- recovery

	/** Every way of being locked out, fixed by one command. */
	@Test
	public void testResetAdminRestoresThePasswordTheUserAndTheGrants() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);

			// Locked out three different ways.
			auth.setPassword(AuthStore.ADMIN, "forgotten".toCharArray());
			auth.revokeFromRole(Role.ADMIN, Grant.on(Privilege.MANAGE_ROLES));
			auth.setEnabled(AuthStore.ADMIN, false);

			auth.resetAdmin("recovered".toCharArray());

			Principal admin = auth.authenticate(AuthStore.ADMIN, "recovered".toCharArray()).orElseThrow();
			assertTrue(admin.may(Privilege.MANAGE_ROLES), "the grants come back, not just the password");
			for (Privilege privilege : Privilege.values()) {
				assertTrue(admin.may(privilege, "sales"), privilege + " was not restored");
			}
		}
	}

	@Test
	public void testResetAdminRecreatesTheUserWhenItIsGone() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			auth.createUser("someone-else", "x".toCharArray(), List.of());
			auth.dropUser(AuthStore.ADMIN);
			assertTrue(auth.user(AuthStore.ADMIN).isEmpty());

			auth.resetAdmin("recovered".toCharArray());

			assertTrue(auth.authenticate(AuthStore.ADMIN, "recovered".toCharArray()).isPresent());
			assertTrue(auth.user("someone-else").isPresent(), "recovery must not remove anybody else");
		}
	}

	// ---------------------------------------------------------------- durability and reload

	@Test
	public void testEverythingSurvivesAReopen() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
			auth.createRole("analyst");
			auth.grantToRole("analyst", new Grant(Privilege.MERGE, "sales"));
			auth.createUser("alice", "secret".toCharArray(), List.of("analyst"));
		}
		try (AuthStore reopened = AuthStore.open(root)) {
			Principal alice = reopened.authenticate("alice", "secret".toCharArray()).orElseThrow();
			assertTrue(alice.may(Privilege.MERGE, "sales"));
			assertEquals(4, reopened.generation(), "one generation per write");
		}
	}

	/**
	 * A second process's write is picked up on the login path, with no restart.
	 *
	 * <p>
	 * This is what makes {@code metaloom-graph-cli auth reset-admin} usable against a running server, and it is the
	 * first thing anybody locked out will try.
	 * </p>
	 */
	@Test
	public void testAnExternalWriteIsPickedUpOnTheNextLogin() throws IOException {
		try (AuthStore server = AuthStore.open(root)) {
			server.bootstrap(PASSWORD);

			// A separate handle, standing in for the offline CLI against a root the server has open.
			try (AuthStore cli = AuthStore.open(root)) {
				cli.resetAdmin("recovered".toCharArray());
			}

			assertTrue(server.authenticate(AuthStore.ADMIN, "recovered".toCharArray()).isEmpty(),
				"before a reload the server still holds the old image");

			assertTrue(server.reloadIfChanged());
			assertTrue(server.authenticate(AuthStore.ADMIN, "recovered".toCharArray()).isPresent());
			assertFalse(server.reloadIfChanged(), "a second reload finds nothing changed");
		}
	}

	/** A write that is not there leaves no temporary file, and a stray one is cleaned up rather than read. */
	@Test
	public void testAStrayTemporaryFileIsRemovedOnOpen() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
		}
		Path temp = root.resolve(AuthStore.DIRECTORY).resolve("AUTH.tmp");
		Files.write(temp, new byte[] { 'n', 'o', 't', ' ', 'a', ' ', 'f', 'i', 'l', 'e' });

		try (AuthStore reopened = AuthStore.open(root)) {
			assertFalse(Files.exists(temp), "a partial write is removed rather than examined");
			assertTrue(reopened.authenticate(AuthStore.ADMIN, PASSWORD).isPresent(), "the real file is untouched");
		}
	}

	/**
	 * A corrupt file is refused rather than bootstrapped over.
	 *
	 * <p>
	 * Replacing it would discard every grant in it and could silently widen access, so the server refuses to start and
	 * names the recovery command instead.
	 * </p>
	 */
	@Test
	public void testACorruptFileIsRefusedRatherThanReplaced() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
		}
		Path file = root.resolve(AuthStore.DIRECTORY).resolve(AuthStore.FILE);
		byte[] bytes = Files.readAllBytes(file);
		bytes[bytes.length / 2] ^= (byte) 0xFF;
		Files.write(file, bytes);

		AuthFormatException refused = assertThrows(AuthFormatException.class, () -> AuthStore.open(root));
		assertTrue(refused.getMessage().contains("reset-admin"),
			"the refusal names the way out: " + refused.getMessage());
	}

	// ---------------------------------------------------------------- the root it lives in

	/**
	 * {@code .auth/} is not a database, by two independent mechanisms.
	 *
	 * <p>
	 * Asserted rather than assumed, so that nobody later "fixes" {@code names()} to count directories and quietly
	 * turns the identity store into a database somebody can drop.
	 * </p>
	 */
	@Test
	public void testTheIdentityDirectoryIsNotADatabase() throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.bootstrap(PASSWORD);
		}
		try (GraphDatabases databases = GraphDatabases.open(root)) {
			assertFalse(databases.names().contains(AuthStore.DIRECTORY),
				"the identity directory must never be listed as a database");
			assertTrue(databases.names().contains(GraphDatabases.DEFAULT_NAME),
				"a root holding only .auth/ is still empty, so open() still creates the default database");
		}
		assertThrows(IllegalArgumentException.class, () -> GraphDatabases.validate(AuthStore.DIRECTORY),
			"the name allow-list refuses a leading dot, so no query can ever address it");
	}
}
