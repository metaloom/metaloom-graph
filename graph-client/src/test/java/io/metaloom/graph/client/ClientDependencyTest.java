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
		"io.metaloom.graph.server.GraphServer");

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
}
