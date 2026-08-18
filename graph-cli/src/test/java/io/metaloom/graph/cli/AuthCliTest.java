package io.metaloom.graph.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.core.api.Privilege;

/**
 * The {@code auth} commands as somebody locked out actually runs them.
 *
 * <p>
 * The assertions that matter are not about output. They are that the identity file afterwards says what the command
 * claimed — checked by reopening it through {@link AuthStore} rather than by reading the text back — and that the
 * generated password lands on <b>stdout</b> alone, because {@code auth reset-admin > password.txt} is exactly what
 * somebody will do at three in the morning.
 * </p>
 */
@Tag("unit")
public class AuthCliTest {

	@TempDir
	Path tmp;

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private int run(String... args) {
		return run(new byte[0], args);
	}

	private int run(byte[] stdinBytes, String... args) {
		CliOptions options = CliOptions.parse(args, name -> null, new ByteArrayInputStream(stdinBytes));
		InputStream stdin = new ByteArrayInputStream(stdinBytes);
		return new GraphCli(options, new PrintStream(err, true, StandardCharsets.UTF_8), out, stdin).run();
	}

	private String outText() {
		return out.toString(StandardCharsets.UTF_8);
	}

	private String errText() {
		return err.toString(StandardCharsets.UTF_8);
	}

	private String root() {
		return tmp.resolve("graphs").toString();
	}

	// ---------------------------------------------------------------- reset-admin

	/**
	 * The whole point of the command: a root with no identity at all becomes one somebody can log into.
	 *
	 * <p>
	 * And the password is on stdout with nothing else, so it can be captured. Everything explanatory is on stderr,
	 * which is the same split a piped dump relies on.
	 * </p>
	 */
	@Test
	public void testResetAdminCreatesAnAdministratorAndPrintsThePasswordToStdoutAlone() throws Exception {
		assertEquals(0, run("auth", "reset-admin", "--path=" + root()));

		String password = outText().trim();
		assertEquals(24, password.length(), "stdout holds the password and nothing else, so it can be captured");
		assertFalse(password.contains(" "), password);
		assertTrue(errText().contains("Reset the 'admin' user"), errText());
		assertTrue(errText().contains("shown once"), errText());

		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			Principal admin = auth.authenticate(AuthStore.ADMIN, password.toCharArray()).orElseThrow();
			for (Privilege privilege : Privilege.values()) {
				assertTrue(admin.may(privilege, "anything"), privilege + " was not granted");
			}
		}
	}

	/** A password that was given is not echoed: printing it back would put it somewhere new. */
	@Test
	public void testAGivenPasswordIsNotPrinted() throws Exception {
		assertEquals(0, run("auth", "reset-admin", "--path=" + root(), "--password=chosen-by-hand"));

		assertEquals("", outText(), "a password the caller already has must not be printed anywhere");
		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			assertTrue(auth.authenticate(AuthStore.ADMIN, "chosen-by-hand".toCharArray()).isPresent());
		}
	}

	@Test
	public void testThePasswordCanComeFromStdin() throws Exception {
		assertEquals(0, run("from-a-pipe\n".getBytes(StandardCharsets.UTF_8),
			"auth", "reset-admin", "--path=" + root(), "--password-stdin"));

		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			assertTrue(auth.authenticate(AuthStore.ADMIN, "from-a-pipe".toCharArray()).isPresent(),
				"the trailing newline echo adds must not become part of the password");
		}
	}

	@Test
	public void testThePasswordCanComeFromTheEnvironment() throws Exception {
		CliOptions options = CliOptions.parse(new String[] { "auth", "reset-admin", "--path=" + root() },
			name -> "GRAPH_ADMIN_PASSWORD".equals(name) ? "from-the-environment" : null);
		assertEquals(0, new GraphCli(options, new PrintStream(err, true, StandardCharsets.UTF_8), out,
			new ByteArrayInputStream(new byte[0])).run());

		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			assertTrue(auth.authenticate(AuthStore.ADMIN, "from-the-environment".toCharArray()).isPresent());
		}
	}

	/**
	 * Every way of being locked out, fixed at once.
	 *
	 * <p>
	 * Somebody reaching for this has no way to know which of the three happened to them, so a command that fixed only
	 * the one its name mentions would leave them running it and wondering why nothing changed.
	 * </p>
	 */
	@Test
	public void testResetAdminUndoesEveryWayOfBeingLockedOut() throws Exception {
		run("auth", "reset-admin", "--path=" + root(), "--password=first");
		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			auth.setEnabled(AuthStore.ADMIN, false);
			auth.revokeFromRole("admin", Grant.on(Privilege.MANAGE_ROLES));
			auth.setPassword(AuthStore.ADMIN, "forgotten".toCharArray());
		}

		assertEquals(0, run("auth", "reset-admin", "--path=" + root(), "--password=second"));

		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			Principal admin = auth.authenticate(AuthStore.ADMIN, "second".toCharArray()).orElseThrow();
			assertTrue(admin.may(Privilege.MANAGE_ROLES), "the grants come back too, not just the password");
		}
	}

	/**
	 * The command works against a root a server has open.
	 *
	 * <p>
	 * That independence is the whole reason the identity file has its own short-lived lock rather than living under
	 * the store's. Held open here by a second {@link AuthStore}, which is what a running server would be.
	 * </p>
	 */
	@Test
	public void testItWorksWhileSomethingElseHasTheRootOpen() throws Exception {
		run("auth", "reset-admin", "--path=" + root(), "--password=first");

		try (AuthStore server = AuthStore.open(tmp.resolve("graphs"))) {
			assertEquals(0, run("auth", "reset-admin", "--path=" + root(), "--password=second"),
				"the identity file takes no store lock, so this must not be blocked");

			assertTrue(server.reloadIfChanged());
			assertTrue(server.authenticate(AuthStore.ADMIN, "second".toCharArray()).isPresent(),
				"and the running server sees it on the next login");
		}
	}

	// ---------------------------------------------------------------- users, roles and grants

	@Test
	public void testUsersRolesAndGrantsCanBeManaged() throws Exception {
		run("auth", "reset-admin", "--path=" + root(), "--password=admin-pw");

		assertEquals(0, run("auth", "role", "--path=" + root(), "add", "analyst"));
		assertEquals(0, run("auth", "grant", "--path=" + root(), "analyst", "MATCH", "*"));
		assertEquals(0, run("auth", "grant", "--path=" + root(), "analyst", "MERGE", "sales"));
		assertEquals(0, run("auth", "user", "--path=" + root(), "add", "alice", "--password=alice-pw", "analyst"));

		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			Principal alice = auth.authenticate("alice", "alice-pw".toCharArray()).orElseThrow();
			assertTrue(alice.may(Privilege.MATCH, "hr"));
			assertTrue(alice.may(Privilege.MERGE, "sales"));
			assertFalse(alice.may(Privilege.MERGE, "hr"));
		}

		assertEquals(0, run("auth", "revoke", "--path=" + root(), "analyst", "MERGE", "sales"));
		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			assertFalse(auth.principal("alice").orElseThrow().may(Privilege.MERGE, "sales"));
		}

		assertEquals(0, run("auth", "user", "--path=" + root(), "drop", "alice"));
		try (AuthStore auth = AuthStore.open(tmp.resolve("graphs"))) {
			assertTrue(auth.user("alice").isEmpty());
		}
	}

	@Test
	public void testAuthListShowsUsersRolesAndGrants() throws Exception {
		run("auth", "reset-admin", "--path=" + root(), "--password=admin-pw");
		run("auth", "role", "--path=" + root(), "add", "analyst");
		run("auth", "grant", "--path=" + root(), "analyst", "MERGE", "sales");

		out.reset();
		assertEquals(0, run("auth", "list", "--path=" + root()));

		String listing = outText();
		assertTrue(listing.contains("admin"), listing);
		assertTrue(listing.contains("analyst"), listing);
		assertTrue(listing.contains("MERGE ON sales"), "grants print in the form auth grant accepts: " + listing);
	}

	/** What {@code auth list} prints is what {@code auth grant} takes, so a listing can be replayed. */
	@Test
	public void testTheListingIsInTheFormTheGrantCommandAccepts() throws Exception {
		run("auth", "reset-admin", "--path=" + root(), "--password=admin-pw");
		run("auth", "role", "--path=" + root(), "add", "analyst");
		run("auth", "grant", "--path=" + root(), "analyst", "MERGE", "sales");
		out.reset();
		run("auth", "list", "--path=" + root());

		for (String line : outText().split("\n")) {
			String trimmed = line.trim();
			if (trimmed.contains(" ON ")) {
				Grant parsed = Grant.parse(trimmed);
				assertEquals(trimmed, parsed.toString(), "a printed grant must parse back to itself");
			}
		}
	}

	// ---------------------------------------------------------------- refusals

	/**
	 * {@code --server} is refused by name, with the reason.
	 *
	 * <p>
	 * Doing this over HTTP would need an administrator already logged in, which is precisely what has gone missing
	 * whenever the command is needed. A generic "unknown option" would send somebody looking for a typo.
	 * </p>
	 */
	@Test
	public void testTheAuthCommandsRefuseServerByName() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> CliOptions.parse(new String[] { "auth", "reset-admin", "--server=http://localhost:8080" },
				name -> null));
		assertTrue(refused.getMessage().contains("--path"), refused.getMessage());
		assertTrue(refused.getMessage().contains("logged in"), refused.getMessage());
	}

	@Test
	public void testAMisspeltSubcommandIsRefused() {
		run("auth", "reset-admin", "--path=" + root(), "--password=pw");
		assertEquals(1, run("auth", "user", "--path=" + root(), "delete", "alice"));
		assertTrue(errText().contains("'add' or 'drop'"), errText());
	}

	@Test
	public void testAGrantNeedsAllThreeWords() {
		run("auth", "reset-admin", "--path=" + root(), "--password=pw");
		assertEquals(1, run("auth", "grant", "--path=" + root(), "analyst", "MERGE"));
		assertTrue(errText().contains("PRIVILEGE"), errText());
	}

	@Test
	public void testAnUnknownPrivilegeIsRefusedWithTheListOfRealOnes() {
		run("auth", "reset-admin", "--path=" + root(), "--password=pw");
		run("auth", "role", "--path=" + root(), "add", "analyst");
		err.reset();
		assertEquals(1, run("auth", "grant", "--path=" + root(), "analyst", "SUPERUSER", "*"));
		assertTrue(errText().contains("SUPERUSER"), errText());
		assertTrue(errText().contains("MATCH"), "the refusal lists what is allowed: " + errText());
	}

	/** A privilege that is not per-database is refused rather than silently widened. */
	@Test
	public void testAGlobalOnlyPrivilegeCannotBeGrantedOnADatabase() {
		run("auth", "reset-admin", "--path=" + root(), "--password=pw");
		run("auth", "role", "--path=" + root(), "add", "helpdesk");
		err.reset();
		assertEquals(1, run("auth", "grant", "--path=" + root(), "helpdesk", "MANAGE_USERS", "sales"));
		assertTrue(errText().contains("MANAGE_USERS"), errText());
	}

	@Test
	public void testEveryAuthCommandIsDocumented() {
		String usage = CliOptions.usage();
		for (String command : GraphCli.commands()) {
			assertTrue(usage.contains(command), command + " is dispatched but not documented");
		}
		assertTrue(usage.contains("GRAPH_ADMIN_PASSWORD"), "the environment variable has to be in the help text");
	}

	/** The dump commands still take exactly one trailing word, which the new argument list must not have relaxed. */
	@Test
	public void testTheDumpCommandsStillRefuseAnExtraArgument() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> CliOptions.parse(new String[] { "database", "list", "mydb", "extra", "--path=/tmp/x" },
				name -> null));
		assertTrue(refused.getMessage().contains("Unexpected argument 'extra'"), refused.getMessage());
	}

	/** And the trailing word is still the database for them. */
	@Test
	public void testTheDumpCommandsStillTakeTheDatabaseAsAPositionalArgument() {
		CliOptions options = CliOptions.parse(new String[] { "database", "backup", "sales", "--path=/tmp/x",
			"--out=-" }, name -> null);
		assertEquals("sales", options.database());
		assertEquals(List.of("sales"), options.arguments());
	}

	/** An auth command's trailing words are its own, and must never be read as a database name. */
	@Test
	public void testAnAuthCommandsArgumentsAreNotMistakenForADatabase() {
		CliOptions options = CliOptions.parse(new String[] { "auth", "grant", "analyst", "MERGE", "sales",
			"--path=/tmp/x" }, name -> null);
		assertEquals(List.of("analyst", "MERGE", "sales"), options.arguments());
		assertEquals("default", options.database(), "'analyst' is a role, not a database");
	}
}
