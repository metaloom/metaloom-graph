package io.metaloom.graph.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;

/**
 * The commands as somebody actually runs them, through {@link GraphCli#run()} and its exit status.
 *
 * <p>
 * {@code CliOptionsTest} covers the parsing and {@code BackupRoundTripTest} covers the format. What is only visible
 * here is the sequence in between — which databases get opened, in what order, and what the tool refuses — and that is
 * where restoring into a <b>fresh root</b> was broken for the whole of this feature's first version: opening a root
 * that does not exist creates a {@code default} inside it, dropping refuses to remove the last database in a root, so
 * the single most obvious restore there is ("put this dump on that empty machine") could not be done at all. Neither
 * of the other two test classes could see it, because neither goes through the CLI.
 * </p>
 */
@Tag("unit")
public class GraphCliTest {

	@TempDir
	Path tmp;

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	/** @return the process exit status the real main would have used */
	private int run(String... args) {
		CliOptions options = CliOptions.parse(args, name -> null);
		InputStream stdin = new ByteArrayInputStream(new byte[0]);
		return new GraphCli(options, new PrintStream(err, true, StandardCharsets.UTF_8), out, stdin).run();
	}

	private String errText() {
		return err.toString(StandardCharsets.UTF_8);
	}

	/** A root holding one database with {@code count} cities in it. */
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

	private long nodeCountOf(Path root, String database) throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root)) {
			return databases.database(database).stats().nodeCount();
		}
	}

	/**
	 * The one this class exists for: a dump, an empty machine, and no flags.
	 *
	 * <p>
	 * The target root does not exist. Opening it creates a {@code default} that nobody asked for and that holds
	 * nothing, and a restore has to be able to put a database over that without being told twice.
	 * </p>
	 */
	@Test
	public void testARestoreIntoAFreshRootNeedsNoForce() throws Exception {
		Path source = seed("source", 7);
		Path dump = tmp.resolve("out.dump");
		assertEquals(0, run("database", "backup", "--path=" + source, "--out=" + dump, "--quiet"));

		Path fresh = tmp.resolve("fresh");
		assertEquals(0, run("database", "restore", "--path=" + fresh, "--in=" + dump),
			"restoring into a fresh root failed: " + errText());
		assertEquals(7, nodeCountOf(fresh, "default"));
	}

	/** And the same into a root that exists but whose target is the empty database the previous restore left. */
	@Test
	public void testARestoreOverAnEmptyDatabaseNeedsNoForce() throws Exception {
		Path source = seed("source", 3);
		Path dump = tmp.resolve("out.dump");
		assertEquals(0, run("database", "backup", "--path=" + source, "--out=" + dump, "--quiet"));

		Path target = tmp.resolve("target");
		try (GraphDatabases databases = GraphDatabases.open(target)) {
			databases.create("spare");
		}
		assertEquals(0, run("database", "restore", "--path=" + target, "--in=" + dump),
			"restoring over an empty database failed: " + errText());
		assertEquals(3, nodeCountOf(target, "default"));
	}

	/** Data, on the other hand, is not overwritten because a command was typed in a hurry. */
	@Test
	public void testARestoreOverDataIsRefusedWithoutForce() throws Exception {
		Path source = seed("source", 4);
		Path dump = tmp.resolve("out.dump");
		assertEquals(0, run("database", "backup", "--path=" + source, "--out=" + dump, "--quiet"));

		Path target = seed("target", 99);
		assertEquals(1, run("database", "restore", "--path=" + target, "--in=" + dump));
		assertTrue(errText().contains("--force"), errText());
		assertEquals(99, nodeCountOf(target, "default"), "the refusal has to have changed nothing");

		err.reset();
		assertEquals(0, run("database", "restore", "--path=" + target, "--in=" + dump, "--force"), errText());
		assertEquals(4, nodeCountOf(target, "default"), "--force replaces it whole rather than merging");
	}

	/**
	 * {@code inspect} opens no store, so it needs no {@code --path}, and that is the point of it: a dump that will not
	 * restore is diagnosed on a machine that may have no working database on it at all.
	 */
	@Test
	public void testInspectReadsAHeaderWithNoStore() throws Exception {
		Path source = seed("source", 2);
		Path dump = tmp.resolve("out.dump");
		assertEquals(0, run("database", "backup", "--path=" + source, "--out=" + dump, "--quiet"));

		assertEquals(0, run("database", "inspect", "--in=" + dump), errText());
		String printed = out.toString(StandardCharsets.UTF_8);
		assertTrue(printed.contains("format version"), printed);
		assertTrue(printed.contains("default"), printed);
	}

	/** A damaged dump is diagnosed rather than half-restored, and says which of the two things is wrong. */
	@Test
	public void testATruncatedDumpIsRefusedBeforeAnythingIsCreated() throws Exception {
		Path source = seed("source", 5);
		Path dump = tmp.resolve("out.dump");
		assertEquals(0, run("database", "backup", "--path=" + source, "--out=" + dump, "--quiet"));

		Path truncated = tmp.resolve("truncated.dump");
		byte[] whole = Files.readAllBytes(dump);
		Files.write(truncated, java.util.Arrays.copyOf(whole, 32));

		assertEquals(1, run("database", "inspect", "--in=" + truncated));
		assertTrue(errText().contains("dump could not be read"), errText());
	}
}
