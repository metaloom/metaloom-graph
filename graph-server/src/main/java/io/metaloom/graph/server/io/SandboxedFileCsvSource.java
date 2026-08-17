package io.metaloom.graph.server.io;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import io.metaloom.graph.cypher.io.CsvAccessException;
import io.metaloom.graph.cypher.io.CsvSource;

/**
 * The only files a {@code LOAD CSV} on this server may read: those under one directory.
 *
 * <p>
 * The threat is not exotic. No port on this server has authentication ({@code spec/OPERATIONS.md} section 9), the HTTP
 * one binds every interface, and {@code LOAD CSV … RETURN row} is not a write — so without this class, anything that can
 * reach port 8080 could read any file the server process can, and get it back as query results. That is a worse hole
 * than {@code --allow-write}, because it needs no flag to be enabled and leaves no trace in the graph.
 * </p>
 *
 * <h2>The path is always relative to the root</h2>
 *
 * <p>
 * {@code file:///people.csv} means {@code <import-dir>/people.csv}, <b>not</b> {@code /people.csv}. The leading slash is
 * kept in the URL because that is the spelling every other engine uses and every example on the internet is written
 * with, but it is read as "the root of the import directory" rather than as the root of the filesystem. A URL therefore
 * cannot name a file outside the root even before the checks below run, which is the property worth having: the
 * containment is structural, not a filter.
 * </p>
 *
 * <h2>Three checks, in this order</h2>
 *
 * <ol>
 * <li><b>The scheme.</b> Only {@code file:} and a bare relative path. {@code http:} is refused <em>by name</em>, because
 * the alternative is a server-side request forgery primitive on an unauthenticated port — a query that makes the server
 * fetch a URL of the caller's choosing, from inside whatever network the server is on.</li>
 * <li><b>Resolution.</b> The URL's path is resolved against the root, and {@code ..} in it cannot escape because of
 * {@link Path#normalize()} followed by the containment check — not because {@code ..} is looked for. Searching for
 * {@code ..} means anticipating {@code %2e%2e}, {@code ....//} and whatever else; asking the filesystem where the path
 * actually ended up means anticipating nothing. This is the same reasoning {@code GraphDatabases.validate} uses one
 * layer up, and it is the reason that class is an allow-list.</li>
 * <li><b>The real path.</b> {@link Path#toRealPath} resolves every symlink, and the result must still be inside the
 * root. Without this, a symlink inside the import directory pointing at {@code /etc} would be followed happily — and an
 * import directory is very often somewhere a less-trusted process can write.</li>
 * </ol>
 *
 * <h2>One refusal, one message</h2>
 *
 * <p>
 * "Outside the import directory" and "no such file" are the <b>same</b> message here. Telling them apart would turn the
 * clause into a way of asking whether a path exists on a machine the caller cannot otherwise see — which is a small
 * information leak that costs nothing to close, and the operator who needs the distinction has the server log.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
public final class SandboxedFileCsvSource implements CsvSource {

	private final Path root;

	/**
	 * @param root
	 *            the import directory. Resolved to a real path once, here, so that every later comparison is against a
	 *            path with no symlinks left in it
	 */
	public SandboxedFileCsvSource(Path root) throws IOException {
		if (root == null) {
			throw new IllegalArgumentException("An import directory is required");
		}
		if (!Files.isDirectory(root)) {
			throw new IOException("The import directory " + root + " is not a directory");
		}
		this.root = root.toRealPath();
	}

	/** The directory this source serves, real path resolved. */
	public Path root() {
		return root;
	}

	@Override
	public Reader open(String url) throws IOException {
		Path file = resolve(url);
		// Opened after the containment check and never before it, so a refused path is never touched at all.
		return Files.newBufferedReader(file, StandardCharsets.UTF_8);
	}

	/**
	 * Turns a URL from a query into a file inside the root, or refuses.
	 *
	 * <p>
	 * Package-visible rather than private so the tests can assert on the decision without opening anything — a test
	 * that had to create every file it wanted to refuse would not be able to test refusing the ones that matter.
	 * </p>
	 */
	Path resolve(String url) {
		if (url == null || url.isBlank()) {
			throw new CsvAccessException("LOAD CSV needs a file to read");
		}
		String relative = pathOf(url);
		Path resolved;
		try {
			// normalize() collapses `..` before anything touches the disk; the real-path check below is what makes it
			// safe rather than merely tidy.
			resolved = root.resolve(relative).normalize();
		} catch (InvalidPathException e) {
			throw new CsvAccessException(refusal(url));
		}
		if (!resolved.startsWith(root)) {
			throw new CsvAccessException(refusal(url));
		}
		Path real;
		try {
			real = resolved.toRealPath();
		} catch (IOException e) {
			// Does not exist, or is not reachable. Deliberately the same message as "outside the root".
			throw new CsvAccessException(refusal(url));
		}
		if (!real.startsWith(root) || !Files.isRegularFile(real)) {
			throw new CsvAccessException(refusal(url));
		}
		return real;
	}

	/** The path part of the URL, as a path relative to the root. */
	private static String pathOf(String url) {
		String lower = url.toLowerCase(java.util.Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			throw new CsvAccessException("LOAD CSV reads local files only, and " + url + " is a URL this server would "
				+ "have to fetch. This server has no authentication, so a query that could make it request an "
				+ "arbitrary address would let any caller reach whatever the server can.");
		}
		if (!lower.startsWith("file:")) {
			// A bare path, which is how a query written against a local directory usually looks.
			return stripLeadingSlashes(url);
		}
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new CsvAccessException(refusal(url));
		}
		if (uri.getHost() != null && !uri.getHost().isEmpty()) {
			// file://somewhere/x is a remote file in the URL's own terms, and there is nothing sensible to do with it.
			throw new CsvAccessException("LOAD CSV reads files under this server's import directory, and " + url
				+ " names a host. Write it as file:///name.csv, which is read relative to that directory.");
		}
		String path = uri.getPath();
		if (path == null) {
			throw new CsvAccessException(refusal(url));
		}
		return stripLeadingSlashes(path);
	}

	/**
	 * Drops the leading slashes, which is what makes {@code file:///x.csv} mean {@code <root>/x.csv}.
	 *
	 * <p>
	 * All of them, not one: {@code file:////etc/passwd} would otherwise resolve against the filesystem root on a
	 * platform whose {@code Path.resolve} treats a leading slash as absolute — the containment check would still catch
	 * it, but a path that has already escaped and is then caught is one check away from being a hole.
	 * </p>
	 */
	private static String stripLeadingSlashes(String path) {
		int start = 0;
		while (start < path.length() && (path.charAt(start) == '/' || path.charAt(start) == '\\')) {
			start++;
		}
		return path.substring(start);
	}

	private static String refusal(String url) {
		return "LOAD CSV cannot read " + url + ": it is not a readable file inside this server's import directory.";
	}
}
