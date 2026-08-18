package io.metaloom.graph.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The client's classpath, asserted rather than intended.
 *
 * <p>
 * A dependency arrives by accident far more often than it arrives by decision: one import, one convenient constant,
 * and the client jar is carrying the storage engine. The consequences are not abstract — graph-core is an FFM module
 * with a native library, so an application embedding this client would need a native-access grant and a shared object
 * it will never call, to read rows off a socket.
 * </p>
 *
 * <p>
 * Checked by loading rather than by reading a pom, because the pom is not what ends up on a classpath. A transitive
 * arrival would pass a pom check and fail this one.
 * </p>
 */
@Tag("unit")
public class ClientDependencyTest {

	private static final List<String> FORBIDDEN = List.of(
		"io.metaloom.graph.core.api.GraphStore",
		"io.metaloom.graph.core.nativelib.NativeCapabilities",
		"io.metaloom.graph.cypher.Cypher",
		"io.metaloom.graph.cypher.value.CypherNode",
		"io.metaloom.graph.server.GraphServer",
		// The identity model, which lives in graph-core because GraphDatabases owns the root that .auth/ sits in.
		// Its absence here is what forces grants to cross the wire as STRINGS rather than as a Privilege enum — see
		// spec/AUTH.md section 1. That is not a workaround: the client makes no authorization decision, so it needs
		// no vocabulary for one, and a client built before a privilege existed still prints it.
		"io.metaloom.graph.core.api.Privilege",
		"io.metaloom.graph.core.api.AuthStore",
		// And the password function, which would drag PBKDF2 and a file parser onto every embedder's classpath.
		"io.metaloom.graph.core.auth.PasswordHash");

	@Test
	public void testNeitherEngineIsOnTheClientsClasspath() {
		for (String forbidden : FORBIDDEN) {
			assertThrows(ClassNotFoundException.class,
				() -> Class.forName(forbidden),
				forbidden + " is on the client's classpath. The client exists to read rows off a socket; it has no"
					+ " business carrying a storage engine or a query engine, and an application embedding it should"
					+ " not have to grant native access for the privilege.");
		}
	}

	@Test
	public void testTheProtocolItDoesDependOnIsThere() {
		// The negative assertions above would also pass if the whole test were misconfigured, so prove the check can
		// see a class that genuinely is present.
		assertTrue(io.metaloom.graph.protocol.WireNode.class.getName().startsWith("io.metaloom.graph.protocol"));
	}

	/**
	 * Credentials are in graph-protocol, which is allowed and is where they belong.
	 *
	 * <p>
	 * They are the {@code AUTH} frame's payload, so both ends of the wire need to name them. Asserted alongside the
	 * refusals above so that "the client may hold a credential type but not a privilege type" reads as one decision
	 * rather than two accidents.
	 * </p>
	 */
	@Test
	public void testCredentialsAreOnTheClasspathBecauseTheyAreProtocol() {
		assertTrue(io.metaloom.graph.protocol.Credentials.class.getName().startsWith("io.metaloom.graph.protocol"));
	}

	/** And a credential never prints itself, at either layer. */
	@Test
	public void testNeitherCredentialsNorOptionsPrintThePassword() {
		io.metaloom.graph.protocol.Credentials credentials =
			new io.metaloom.graph.protocol.Credentials("alice", "correct-horse-battery-staple");
		assertTrue(!credentials.toString().contains("correct-horse-battery-staple"), credentials.toString());

		// The one that would actually bite: ClientOptions is a record, and a generated toString would print the
		// nested credential into any log line, exception or debugger view that touched the options object.
		ClientOptions options = ClientOptions.defaults().withCredentials("alice", "correct-horse-battery-staple");
		assertTrue(!options.toString().contains("correct-horse-battery-staple"), options.toString());
		assertTrue(options.toString().contains("credentials="), options.toString());
	}
}
