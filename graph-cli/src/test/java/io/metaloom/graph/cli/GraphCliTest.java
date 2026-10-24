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

	/**
	 * {@code --server} sends a session token when one is configured.
	 *
	 * <p>
	 * Authentication is on by default, so a CLI that sent nothing could never back up or list a default server: it
	 * would be refused with a 401 every time. Against a stand-in server that refuses anything without the token, so
	 * the test needs no graph server on this module's classpath.
	 * </p>
	 */
	@Test
	public void testTheServerCommandsSendTheConfiguredToken() throws Exception {
		com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(
			new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		stub.createContext("/api/databases", exchange -> {
			boolean presented = "Bearer secret-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = (presented ? "{\"databases\":[{\"name\":\"alpha\"},{\"name\":\"beta\"}]}"
				: "{\"error\":\"unauthenticated\"}").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(presented ? 200 : 401, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		stub.start();
		try {
			String url = "http://127.0.0.1:" + stub.getAddress().getPort();
			assertEquals(1, run("database", "list", "--server=" + url), "a 401 must be an error");
			assertTrue(errText().contains("GRAPH_TOKEN"), "and say how to send a token: " + errText());

			CliOptions options = CliOptions.parse(new String[] { "database", "list", "--server=" + url },
				name -> name.equals("GRAPH_TOKEN") ? "secret-token" : null);
			assertEquals(0, new GraphCli(options, new PrintStream(err, true, StandardCharsets.UTF_8), out,
				new ByteArrayInputStream(new byte[0])).run(), errText());
			String printed = out.toString(StandardCharsets.UTF_8);
			assertTrue(printed.contains("alpha") && printed.contains("beta"), printed);
		} finally {
			stub.stop(0);
		}
	}

	/**
	 * A damaged dump restored with {@code --force} leaves the database it was meant to replace exactly as it was.
	 *
	 * <p>
	 * The checksum is in the trailer, so a dump is only known to be whole once all of it has been read. Replacing the
	 * target first and finding out at the end would trade the user's data for half of somebody else's.
	 * </p>
	 */
	@Test
	public void testADamagedDumpRestoredWithForceKeepsTheOriginal() throws Exception {
		Path source = seed("source", 50);
		Path dump = tmp.resolve("out.dump");
		assertEquals(0, run("database", "backup", "--path=" + source, "--out=" + dump, "--quiet"));
		byte[] whole = Files.readAllBytes(dump);

		Path target = seed("target", 99);
		Path truncated = tmp.resolve("truncated.dump");
		Files.write(truncated, java.util.Arrays.copyOf(whole, whole.length - 8));
		assertEquals(1, run("database", "restore", "--path=" + target, "--in=" + truncated, "--force"));
		assertEquals(99, nodeCountOf(target, "default"), "a failed restore replaced the original: " + errText());

		Path corrupt = tmp.resolve("corrupt.dump");
		byte[] flipped = whole.clone();
		flipped[flipped.length / 2] ^= 0x5A;
		Files.write(corrupt, flipped);
		assertEquals(1, run("database", "restore", "--path=" + target, "--in=" + corrupt, "--force"));
		assertEquals(99, nodeCountOf(target, "default"), "a failed restore replaced the original: " + errText());

		// Nothing half-made is left lying about where a database would be listed.
		try (GraphDatabases databases = GraphDatabases.open(target)) {
			assertEquals(List.of(GraphDatabases.DEFAULT_NAME), databases.names());
		}

		// And a whole dump still replaces it.
		assertEquals(0, run("database", "restore", "--path=" + target, "--in=" + dump, "--force"), errText());
		assertEquals(50, nodeCountOf(target, "default"));
	}
}
