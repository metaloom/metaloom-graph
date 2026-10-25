package io.metaloom.graph.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;

/**
 * The CLI on the inputs an operator gets wrong at three in the morning: a mistyped root, a database that is not there,
 * a backup written over the last good one, a password piped in with a trailing line, and the exit codes a script
 * branches on.
 */
@Tag("unit")
public class GraphCliEdgeCaseTest {

	@TempDir
	Path tmp;

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private int run(String... args) {
		CliOptions options = CliOptions.parse(args, name -> null);
		InputStream stdin = new ByteArrayInputStream(new byte[0]);
		return new GraphCli(options, new PrintStream(err, true, StandardCharsets.UTF_8), out, stdin).run();
	}

	private String errText() {
		return err.toString(StandardCharsets.UTF_8);
	}

	private Path seed(String directory, int count) throws Exception {
		Path root = tmp.resolve(directory);
		try (GraphDatabases databases = GraphDatabases.open(root)) {
			GraphStore store = databases.database(GraphDatabases.DEFAULT_NAME);
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < count; i++) {
					store.createNode(List.of("City"), Properties.of().set("name", "city-" + i));
				}
				txn.commit();
			}
		}
		return root;
	}

	// ---------------------------------------------------------------- a backup never costs the previous one

	/**
	 * A backup that fails leaves whatever was at {@code --out} as it was.
	 *
	 * <p>
	 * The file was opened — and so truncated — before the database was. A backup run nightly onto the same name,
	 * failing for any reason at all (the database name mistyped, the store locked by a server, a session expired),
	 * replaced the last good dump with an empty file and exited 1: the one failure that also destroys the backup it
	 * was meant to refresh.
	 * </p>
	 */
	@Test
	public void testAFailedBackupLeavesThePreviousDumpAlone() throws Exception {
		Path root = seed("root", 5);
		Path dump = tmp.resolve("nightly.dump");
		assertEquals(0, run("database", "backup", "--path=" + root, "--out=" + dump, "--quiet"), errText());
		byte[] good = Files.readAllBytes(dump);
		assertTrue(good.length > 0);

		assertEquals(1, run("database", "backup", "nosuchdb", "--path=" + root, "--out=" + dump, "--quiet"));
		assertArrayEquals(good, Files.readAllBytes(dump), "the previous dump must survive a failed backup");

		// Locked by another process: the commonest real failure.
		try (GraphDatabases held = GraphDatabases.open(root)) {
			held.database(GraphDatabases.DEFAULT_NAME);
			assertEquals(1, run("database", "backup", "--path=" + root, "--out=" + dump, "--quiet"));
		}
		assertArrayEquals(good, Files.readAllBytes(dump), "and survives a locked store too");
		try (var listing = Files.list(tmp)) {
			assertEquals(List.of(), listing.filter(p -> p.getFileName().toString().startsWith("nightly.dump."))
				.toList(), "and no partial file is left beside it");
		}
	}

	@Test
	public void testASuccessfulBackupReplacesThePreviousDump() throws Exception {
		Path root = seed("root", 5);
		Path dump = tmp.resolve("nightly.dump");
		Files.writeString(dump, "an older dump");
		assertEquals(0, run("database", "backup", "--path=" + root, "--out=" + dump, "--quiet"), errText());
		assertEquals(0, run("database", "inspect", "--in=" + dump), errText());
		assertTrue(out.toString(StandardCharsets.UTF_8).contains("database         default"));
	}

	/**
	 * A root that does not exist is an error, not an empty database.
	 *
	 * <p>
	 * Opening a missing root creates it with a {@code default} database in it. For a backup that made a mistyped
	 * {@code --path} into a successful, valid, <em>empty</em> dump — and a directory nobody asked for — which is the
	 * worst possible outcome for a command whose output nobody looks at until the day they need it.
	 * </p>
	 */
	@Test
	public void testAMistypedRootIsRefusedRatherThanBackedUpAsEmpty() throws Exception {
		Path missing = tmp.resolve("no-such-root");
		Path dump = tmp.resolve("x.dump");
		assertEquals(1, run("database", "backup", "--path=" + missing, "--out=" + dump, "--quiet"));
		assertTrue(errText().contains(missing.toString()), errText());
		assertFalse(Files.exists(missing), "a backup must not create the root it was asked to read");
		assertFalse(Files.exists(dump));

		assertEquals(1, run("database", "list", "--path=" + missing));
		assertFalse(Files.exists(missing), "nor may a listing");
	}

	@Test
	public void testRestoringIntoAFreshRootStillCreatesIt() throws Exception {
		Path root = seed("root", 3);
		Path dump = tmp.resolve("x.dump");
		assertEquals(0, run("database", "backup", "--path=" + root, "--out=" + dump, "--quiet"), errText());
		Path fresh = tmp.resolve("brand-new");
		assertEquals(0, run("database", "restore", "--path=" + fresh, "--in=" + dump, "--quiet"), errText());
		assertTrue(Files.isDirectory(fresh), "a restore is how a root comes back, so it may make one");
	}

	// ---------------------------------------------------------------- exit codes and parsing

	@Test
	public void testUsageErrorsAreRefusedBeforeAnythingRuns() {
		Map<List<String>, String> refused = Map.of(
			List.of(), "No command",
			List.of("database", "backup", "--path"), "Unknown option",
			List.of("database", "backup", "--path=", "--out=x"), "needs a value",
			List.of("database", "backup", "a", "b", "--path=/x", "--out=x"), "Unexpected argument",
			List.of("database", "backup", "--path=/x", "--server=http://h", "--out=x"), "alternatives",
			List.of("database", "backup", "--out=x"), "--path",
			List.of("auth", "list", "--server=http://h"), "--path");
		refused.forEach((args, message) -> {
			IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> CliOptions.parse(args.toArray(String[]::new), name -> null), String.join(" ", args));
			assertTrue(thrown.getMessage().contains(message), args + " -> " + thrown.getMessage());
		});
	}

	@Test
	public void testAnUnknownCommandExitsTwoAndAFailureExitsOne() throws Exception {
		Path root = seed("root", 1);
		assertEquals(2, run("database", "frobnicate", "--path=" + root));
		assertTrue(errText().contains("Unknown command"), errText());
		assertEquals(1, run("database", "restore", "--path=" + root, "--in=" + tmp.resolve("missing.dump")));
		assertEquals(1, run("database", "backup", "--path=" + root), "backup without --out");
	}

	/**
	 * {@code --password-stdin} reads one line, as its help says.
	 *
	 * <p>
	 * It read the whole of stdin and stripped only trailing newlines, so a second line became part of the password —
	 * {@code "secret\nsecret"} from a script that echoed it twice for a confirmation prompt — and the login that
	 * followed failed with nothing to say why.
	 * </p>
	 */
	@Test
	public void testPasswordStdinReadsOneLine() {
		for (String input : List.of("s3cret\n", "s3cret\r\n", "s3cret", "s3cret\nignored\n", "s3cret\r\nsecond")) {
			CliOptions options = CliOptions.parse(new String[] { "auth", "reset-admin", "--path=/x", "--password-stdin" },
				name -> null, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
			assertEquals("s3cret", options.password(), "from " + input.replace("\n", "\\n").replace("\r", "\\r"));
		}
		assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(
			new String[] { "auth", "reset-admin", "--path=/x", "--password-stdin" }, name -> null,
			new ByteArrayInputStream("\nsecond".getBytes(StandardCharsets.UTF_8))), "an empty first line is no password");
	}

	@Test
	public void testInspectOfGarbageIsAFormatError() throws Exception {
		Path garbage = tmp.resolve("garbage.dump");
		Files.write(garbage, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
		assertEquals(1, run("database", "inspect", "--in=" + garbage));
		assertTrue(errText().contains("could not be read"), errText());
		Path empty = tmp.resolve("empty.dump");
		Files.write(empty, new byte[0]);
		assertEquals(1, run("database", "inspect", "--in=" + empty));
	}
}
