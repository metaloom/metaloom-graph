package io.metaloom.graph.cypher.io;

import java.io.IOException;
import java.io.Reader;

/**
 * Turns the URL in a {@code LOAD CSV ... FROM} into characters, or refuses to.
 *
 * <p>
 * This interface is the whole of the query engine's relationship with the outside world, and it exists so that
 * {@code graph-cypher} can implement {@code LOAD CSV} without implementing <em>file access</em>. The module has no
 * third-party dependencies and no {@code java.nio.file} usage in {@code src/main}, and
 * {@code spec/ARCHITECTURE.md} treats that as a claim rather than an accident: the query language is a consumer of the
 * storage API and of nothing else. Opening a path is not the part that is hard to get right — deciding <em>which</em>
 * paths may be opened is, and that decision belongs to whoever is exposed to the network, not to the parser.
 * </p>
 *
 * <p>
 * So the split is: this module parses CSV ({@link CsvRowReader}) and the caller supplies the bytes and the policy.
 * {@code graph-server} passes a source sandboxed to its {@code --import-dir}; a test passes one backed by a string;
 * anything that has not thought about it gets {@link #DENY}.
 * </p>
 *
 * <h2>The default is refusal, deliberately</h2>
 *
 * <p>
 * Every {@code Cypher.query} / {@code Cypher.stream} overload that does not name a source uses {@link #DENY}. An
 * embedded caller therefore cannot be turned into a file-reading primitive by a query string it did not write, which
 * matters because a query string is very often the part of an application that comes from outside it. Opting in is one
 * argument; opting out is not something anyone should have to remember to do.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@FunctionalInterface
public interface CsvSource {

	/**
	 * Opens the resource named by the query, or throws if this policy will not serve it.
	 *
	 * <p>
	 * The returned reader is closed by the executor on every path, including an early {@code LIMIT} and an abort.
	 * </p>
	 *
	 * @param url
	 *            exactly what the query said, unresolved and untrusted
	 * @throws CsvAccessException
	 *             if the policy refuses the URL — a different outcome from the resource being missing or unreadable,
	 *             and the servers map the two to different status codes
	 * @throws IOException
	 *             if it is allowed and cannot be read
	 */
	Reader open(String url) throws IOException;

	/**
	 * Reads nothing, and says so by name.
	 *
	 * <p>
	 * The message names the flag rather than the interface, because the reader of it is an operator holding a query
	 * that was refused, not the person who wired the server up.
	 * </p>
	 */
	CsvSource DENY = url -> {
		throw new CsvAccessException("This server does not read files. LOAD CSV is off unless it was started with "
			+ "--allow-load-csv and an --import-dir to read from.");
	};
}
