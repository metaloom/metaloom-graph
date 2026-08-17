package io.metaloom.graph.server.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.cypher.io.CsvAccessException;

/**
 * The import sandbox: what {@code LOAD CSV} may read, and — mostly — what it may not.
 *
 * <p>
 * This is the security test for the whole clause, in the same way {@code GraphDatabasesTest}'s traversal cases are the
 * security test for database names. Every refusal below asserts that <b>nothing was read</b>, not merely that the query
 * failed: a check that refuses after opening the file has already lost.
 * </p>
 */
@Tag("unit")
public class SandboxedFileCsvSourceTest {

	@TempDir
	Path tmp;

	private Path root;
	private SandboxedFileCsvSource source;

	private SandboxedFileCsvSource source() throws IOException {
		if (source == null) {
			root = Files.createDirectories(tmp.resolve("import"));
			Files.writeString(root.resolve("people.csv"), "name\nAlice\n");
			Files.createDirectories(root.resolve("sub"));
			Files.writeString(root.resolve("sub/nested.csv"), "name\nBob\n");
			// The thing an escape is trying to reach, deliberately next to the root rather than at /etc, so the test
			// proves containment rather than the filesystem's own permissions.
			Files.writeString(tmp.resolve("secret.txt"), "the crown jewels");
			source = new SandboxedFileCsvSource(root);
		}
		return source;
	}

	private String read(String url) throws IOException {
		try (Reader reader = source().open(url)) {
			StringBuilder out = new StringBuilder();
			int c;
			while ((c = reader.read()) >= 0) {
				out.append((char) c);
			}
			return out.toString();
		}
	}

	private void refused(String url) throws IOException {
		SandboxedFileCsvSource sandbox = source();
		CsvAccessException e = assertThrows(CsvAccessException.class, () -> sandbox.resolve(url),
			url + " must be refused");
		assertTrue(e.getMessage().contains("LOAD CSV"), e.getMessage());
	}

	// ---------------------------------------------------------------- what works

	/**
	 * {@code file:///x.csv} is {@code <root>/x.csv}, not {@code /x.csv}.
	 *
	 * <p>
	 * The leading slash is kept because that is how every example anywhere is written, and read as the root of the
	 * import directory. That makes containment structural: a URL cannot even name a file outside the root.
	 * </p>
	 */
	@Test
	public void testAFileUrlIsRelativeToTheImportDirectory() throws IOException {
		assertEquals("name\nAlice\n", read("file:///people.csv"));
		assertEquals("name\nBob\n", read("file:///sub/nested.csv"));
	}

	@Test
	public void testABarePathWorksToo() throws IOException {
		assertEquals("name\nAlice\n", read("people.csv"));
		assertEquals("name\nBob\n", read("sub/nested.csv"));
	}

	// ---------------------------------------------------------------- what does not

	@Test
	public void testTraversalOutOfTheRootIsRefused() throws IOException {
		refused("file:///../secret.txt");
		refused("../secret.txt");
		refused("file:///sub/../../secret.txt");
		refused("sub/../../secret.txt");
		refused("file:///../../../../../../etc/passwd");
	}

	/**
	 * An absolute path cannot escape either, because the leading slashes are stripped before resolution.
	 *
	 * <p>
	 * {@code file:////etc/passwd} is the one worth having a case for: a single strip would leave {@code /etc/passwd},
	 * which {@code Path.resolve} treats as absolute and which would then only be caught by the containment check. Being
	 * caught by the last check rather than the first is one refactor away from not being caught.
	 * </p>
	 */
	@Test
	public void testAnAbsolutePathIsReadRelativeToTheRoot() throws IOException {
		refused("file:///etc/passwd");
		refused("file:////etc/passwd");
		refused("/etc/passwd");
		refused("//etc/passwd");
	}

	/**
	 * A symlink out of the root is refused, which is why the real path is resolved rather than the nominal one.
	 *
	 * <p>
	 * An import directory is very often somewhere a less-trusted process drops files, so a link planted inside it is a
	 * realistic way in and not a theoretical one.
	 * </p>
	 */
	@Test
	public void testASymlinkOutOfTheRootIsRefused() throws IOException {
		SandboxedFileCsvSource sandbox = source();
		try {
			Files.createSymbolicLink(root.resolve("escape.csv"), tmp.resolve("secret.txt"));
		} catch (UnsupportedOperationException | IOException e) {
			// Some filesystems and some CI containers do not permit symlinks. Skipping is honest; asserting nothing
			// while looking green would not be.
			System.out.println("Symlinks unavailable here, skipping: " + e);
			return;
		}
		assertThrows(CsvAccessException.class, () -> sandbox.resolve("file:///escape.csv"));
		// And the link itself is still there, i.e. it was refused rather than followed and then rejected on content.
		assertTrue(Files.isSymbolicLink(root.resolve("escape.csv")));
	}

	/** An SSRF primitive is worse than the file read, so the scheme is refused by name. */
	@Test
	public void testHttpIsRefusedByName() throws IOException {
		SandboxedFileCsvSource sandbox = source();
		for (String url : new String[] { "http://169.254.169.254/latest/meta-data/", "https://example.com/x.csv",
			"HTTP://example.com/x.csv" }) {
			CsvAccessException e = assertThrows(CsvAccessException.class, () -> sandbox.resolve(url));
			assertTrue(e.getMessage().contains("local files only"), e.getMessage());
		}
	}

	@Test
	public void testAHostInAFileUrlIsRefused() throws IOException {
		SandboxedFileCsvSource sandbox = source();
		assertTrue(assertThrows(CsvAccessException.class, () -> sandbox.resolve("file://evil.example.com/people.csv"))
			.getMessage().contains("names a host"));
	}

	@Test
	public void testADirectoryIsNotAFile() throws IOException {
		refused("file:///sub");
	}

	/**
	 * "Outside the root" and "does not exist" are the same message.
	 *
	 * <p>
	 * Telling them apart would make the clause a way of asking whether a path exists on a machine the caller cannot
	 * otherwise see. The operator who needs the distinction has the server's own log.
	 * </p>
	 */
	@Test
	public void testARefusalDoesNotSayWhetherTheFileExists() throws IOException {
		SandboxedFileCsvSource sandbox = source();
		String missing = assertThrows(CsvAccessException.class, () -> sandbox.resolve("file:///nope.csv"))
			.getMessage().replace("nope.csv", "X");
		String outside = assertThrows(CsvAccessException.class, () -> sandbox.resolve("file:///../secret.txt"))
			.getMessage().replace("../secret.txt", "X");
		assertEquals(missing, outside, "a caller cannot tell a missing file from a forbidden one");
	}

	@Test
	public void testAnEmptyUrlIsRefused() throws IOException {
		SandboxedFileCsvSource sandbox = source();
		assertThrows(CsvAccessException.class, () -> sandbox.resolve(""));
		assertThrows(CsvAccessException.class, () -> sandbox.resolve(null));
	}

	@Test
	public void testTheRootMustBeADirectory() throws IOException {
		Path file = Files.writeString(tmp.resolve("not-a-dir"), "x");
		assertThrows(IOException.class, () -> new SandboxedFileCsvSource(file));
		assertThrows(IOException.class, () -> new SandboxedFileCsvSource(tmp.resolve("absent")));
		assertThrows(IllegalArgumentException.class, () -> new SandboxedFileCsvSource(null));
	}

	/** The root is resolved once, so a symlinked import directory still compares against real paths afterwards. */
	@Test
	public void testTheRootIsRealPathResolved() throws IOException {
		assertEquals(source().root(), source().root().toRealPath());
	}
}
