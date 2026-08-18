package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.AuthFormatException;
import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.server.auth.Bootstrap;

/**
 * The first start: who exists afterwards, what gets printed, and what is deliberately ignored.
 */
@Tag("unit")
public class BootstrapTest {

	@TempDir
	Path root;

	private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

	private PrintStream out() {
		return new PrintStream(captured, true, StandardCharsets.UTF_8);
	}

	private String printed() {
		return captured.toString(StandardCharsets.UTF_8);
	}

	/** The generated password, pulled back out of what was printed — which is the only place it exists. */
	private String passwordFromOutput() {
		Matcher matcher = Pattern.compile("password:\\s+(\\S+)").matcher(printed());
		assertTrue(matcher.find(), "no password was printed:\n" + printed());
		return matcher.group(1);
	}

	// ---------------------------------------------------------------- a fresh root

	@Test
	public void testAFreshRootGetsAnAdministratorAndAPrintedPassword() throws Exception {
		try (AuthStore auth = AuthStore.open(root)) {
			assertTrue(Bootstrap.ensureAdministrator(auth, null, out()));

			String password = passwordFromOutput();
			assertEquals(24, password.length(), password);
			assertTrue(auth.authenticate(AuthStore.ADMIN, password.toCharArray()).isPresent(),
				"the printed password has to be the one that works");
			for (Privilege privilege : Privilege.values()) {
				assertTrue(auth.authenticate(AuthStore.ADMIN, password.toCharArray()).orElseThrow()
					.may(privilege, "anything"), privilege + " was not granted to the first administrator");
			}
		}
	}

	/**
	 * The generated password avoids the four characters that get misread.
	 *
	 * <p>
	 * Its most likely next journey is being read off one screen and typed into another, and {@code I}, {@code O},
	 * {@code 0} and {@code 1} are where that goes wrong.
	 * </p>
	 */
	@Test
	public void testTheGeneratedPasswordAvoidsAmbiguousCharacters() {
		for (int i = 0; i < 200; i++) {
			String password = new String(Bootstrap.generate());
			assertFalse(password.contains("I"), password);
			assertFalse(password.contains("O"), password);
			assertFalse(password.contains("0"), password);
			assertFalse(password.contains("1"), password);
		}
	}

	@Test
	public void testTwoGeneratedPasswordsDiffer() {
		assertFalse(new String(Bootstrap.generate()).equals(new String(Bootstrap.generate())));
	}

	/** The announcement says it will not be shown again, and how to change it. */
	@Test
	public void testTheAnnouncementSaysWhatToDoWithIt() throws Exception {
		try (AuthStore auth = AuthStore.open(root)) {
			Bootstrap.ensureAdministrator(auth, null, out());
		}
		String text = printed();
		assertTrue(text.contains("shown once"), text);
		assertTrue(text.contains("auth reset-admin"), "it names the way to change it: " + text);
		assertTrue(text.contains("container log"), "it warns that kubectl logs shows this too: " + text);
		assertTrue(text.contains("GRAPH_INITIAL_PASSWORD"), text);
	}

	// ---------------------------------------------------------------- a configured password

	@Test
	public void testAConfiguredPasswordIsUsedAndNotPrinted() throws Exception {
		try (AuthStore auth = AuthStore.open(root)) {
			assertTrue(Bootstrap.ensureAdministrator(auth, "chosen-by-the-operator", out()));

			assertTrue(auth.authenticate(AuthStore.ADMIN, "chosen-by-the-operator".toCharArray()).isPresent());
			assertFalse(printed().contains("chosen-by-the-operator"),
				"a password the operator already knows must not be echoed anywhere new: " + printed());
			assertEquals("", printed().trim(), "nothing at all is printed when a password was configured");
		}
	}

	// ---------------------------------------------------------------- a second start

	/**
	 * On a root that already has users, nothing happens and the configured password is ignored.
	 *
	 * <p>
	 * The failure this prevents is quiet and expensive: a Helm chart that leaves {@code GRAPH_INITIAL_PASSWORD} set
	 * would otherwise reset the administrator's password on every pod restart, undoing whatever it had been changed
	 * to, with nothing in the response to say why the new password stopped working.
	 * </p>
	 */
	@Test
	public void testASecondStartChangesNothingAndIgnoresTheConfiguredPassword() throws Exception {
		try (AuthStore auth = AuthStore.open(root)) {
			Bootstrap.ensureAdministrator(auth, "the-first-one", out());
			auth.setPassword(AuthStore.ADMIN, "changed-by-an-admin".toCharArray());
		}

		captured.reset();
		try (AuthStore auth = AuthStore.open(root)) {
			assertFalse(Bootstrap.ensureAdministrator(auth, "the-first-one", out()),
				"a restart must not create anybody");

			assertTrue(auth.authenticate(AuthStore.ADMIN, "changed-by-an-admin".toCharArray()).isPresent(),
				"the administrator's own password survives a restart");
			assertTrue(auth.authenticate(AuthStore.ADMIN, "the-first-one".toCharArray()).isEmpty(),
				"the configured password must not be reapplied");
			assertEquals("", printed().trim(), "and nothing is printed on a restart");
		}
	}

	/**
	 * An identity file holding no users is treated as no file at all.
	 *
	 * <p>
	 * The alternative is a server that starts, refuses every login and can only be fixed offline — a state nobody
	 * chose and one with no way out from where the operator is standing.
	 * </p>
	 */
	@Test
	public void testAFileWithNoUsersIsBootstrappedAgain() throws Exception {
		try (AuthStore auth = AuthStore.open(root)) {
			Bootstrap.ensureAdministrator(auth, "first", out());
		}
		// An identity file that parses and holds nobody. Written by hand, because no supported edit produces one:
		// dropUser refuses to remove the last user.
		Path file = root.resolve(AuthStore.DIRECTORY).resolve(AuthStore.FILE);
		Files.delete(file);
		try (AuthStore emptied = AuthStore.open(root)) {
			emptied.createRole("leftover");
		}

		captured.reset();
		try (AuthStore auth = AuthStore.open(root)) {
			assertTrue(Bootstrap.ensureAdministrator(auth, null, out()),
				"a file with no users is as unusable as no file, and is treated the same way");
			assertTrue(auth.authenticate(AuthStore.ADMIN, passwordFromOutput().toCharArray()).isPresent());
		}
	}

	// ---------------------------------------------------------------- a damaged file

	/**
	 * A file that cannot be read stops the server rather than being replaced.
	 *
	 * <p>
	 * Bootstrapping over it would discard every grant in it, which nobody could then reconstruct, and could silently
	 * widen access. So the refusal happens at {@code AuthStore.open}, before anything binds a port, and names the
	 * command that does replace it deliberately.
	 * </p>
	 */
	@Test
	public void testACorruptFileStopsTheServerAndNamesTheWayOut() throws Exception {
		try (AuthStore auth = AuthStore.open(root)) {
			Bootstrap.ensureAdministrator(auth, "first", out());
		}
		Path file = root.resolve(AuthStore.DIRECTORY).resolve(AuthStore.FILE);
		byte[] bytes = Files.readAllBytes(file);
		bytes[bytes.length / 2] ^= (byte) 0xFF;
		Files.write(file, bytes);

		AuthFormatException refused = assertThrows(AuthFormatException.class, () -> AuthStore.open(root));
		assertTrue(refused.getMessage().contains("reset-admin"), refused.getMessage());
	}
}
