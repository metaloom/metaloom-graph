package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.server.demo.DemoData;

/**
 * The command line, parsed as a value so it can be checked without starting anything.
 *
 * <p>
 * Worth its own test because the failure mode is silent: a flag that is quietly ignored produces a server that starts
 * successfully and does the wrong thing, which is the kind of thing that gets diagnosed as "the demo data feature is
 * broken".
 * </p>
 */
@Tag("unit")
public class GraphServerOptionsTest {

	@Test
	public void testTheRootAndPortArePositional() {
		GraphServerOptions options = GraphServerOptions.parse(new String[] { "/data/mygraph", "9000" });
		assertEquals(Path.of("/data/mygraph"), options.root());
		assertEquals(9000, options.port());
		assertFalse(options.demo(), "demo data is never the default: it writes");
	}

	@Test
	public void testThePortIsOptional() {
		assertEquals(GraphServerOptions.DEFAULT_PORT, GraphServerOptions.parse(new String[] { "/data/g" }).port());
	}

	@Test
	public void testDemoDefaultsToTheSmallGraph() {
		GraphServerOptions options = GraphServerOptions.parse(new String[] { "--demo", "/data/g" });
		assertTrue(options.demo());
		assertEquals(DemoData.SMALL, options.demoAirports());
	}

	@Test
	public void testASizeImpliesDemo() {
		// Asking for a size and getting no data because a second flag was missing is a silly way to lose a minute.
		GraphServerOptions options = GraphServerOptions.parse(new String[] { "--demo-size=tiny", "/data/g" });
		assertTrue(options.demo());
		assertEquals(DemoData.TINY, options.demoAirports());
	}

	@Test
	public void testASizeCanBeANumber() {
		assertEquals(120, GraphServerOptions.parse(new String[] { "--demo-size=120", "/data/g" }).demoAirports());
	}

	@Test
	public void testOptionsMayFollowTheStore() {
		GraphServerOptions options = GraphServerOptions.parse(new String[] { "/data/g", "8100", "--demo-size=large" });
		assertEquals(8100, options.port());
		assertEquals(DemoData.LARGE, options.demoAirports());
	}

	@Test
	public void testAMistakeIsNamed() {
		// Every one of these prints the message and the usage rather than a stack trace: the user's command is wrong,
		// not the server.
		assertMessage("A root directory is required", new String[] {});
		assertMessage("Unknown option '--demoo'", new String[] { "--demoo", "/data/g" });
		assertMessage("Port must be a number", new String[] { "/data/g", "http" });
		assertMessage("Port must be between", new String[] { "/data/g", "70000" });
		assertMessage("Unexpected argument", new String[] { "/data/g", "8080", "extra" });
		assertMessage("Demo size must be", new String[] { "--demo-size=enormous", "/data/g" });
	}

	private static void assertMessage(String expected, String[] args) {
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
			() -> GraphServerOptions.parse(args));
		assertTrue(thrown.getMessage().contains(expected),
			"expected a message containing '" + expected + "', was '" + thrown.getMessage() + "'");
	}

	@Test
	public void testTheQueryTimeoutIsParsedAndDefaulted() {
		assertEquals(GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS,
			GraphServerOptions.parse(new String[] { "/data/g" }).queryTimeoutMillis(),
			"a store with no limit named still gets one, or a runaway query holds a view for as long as it likes");
		assertEquals(1500, GraphServerOptions.parse(new String[] { "--query-timeout=1500", "/data/g" })
			.queryTimeoutMillis());
		// Zero is the documented way to ask for no limit, so it must survive parsing rather than be treated as unset.
		assertEquals(0, GraphServerOptions.parse(new String[] { "--query-timeout=0", "/data/g" })
			.queryTimeoutMillis());

		assertMessage("--query-timeout must be a number", new String[] { "--query-timeout=soon", "/data/g" });
		assertMessage("--query-timeout must not be negative", new String[] { "--query-timeout=-1", "/data/g" });
	}

	@Test
	public void testTheBinaryPortIsOnByDefaultAndCanBeTurnedOff() {
		GraphServerOptions defaults = GraphServerOptions.parse(new String[] { "/data/g" });
		assertTrue(defaults.wire(), "the binary port is part of the server rather than an extra to be discovered");
		assertEquals(io.metaloom.graph.protocol.Protocol.DEFAULT_PORT, defaults.wirePort());
		assertEquals(8, defaults.maxConcurrentStreams());

		assertFalse(GraphServerOptions.parse(new String[] { "--no-wire", "/data/g" }).wire());
		assertEquals(9100, GraphServerOptions.parse(new String[] { "--wire-port=9100", "/data/g" }).wirePort());
		assertEquals(0, GraphServerOptions.parse(new String[] { "--wire-port=0", "/data/g" }).wirePort(),
			"zero means any free port here as it does for the HTTP one");
		assertEquals(2, GraphServerOptions.parse(new String[] { "--max-streams=2", "/data/g" })
			.maxConcurrentStreams());
	}

	@Test
	public void testTheTwoPortsMayNotBeTheSameOne() {
		// Otherwise it surfaces as "address already in use" from whichever front end happened to bind second, which
		// says nothing about the actual mistake.
		assertMessage("both 8080", new String[] { "--wire-port=8080", "/data/g", "8080" });
		// ...unless both are asking for any free port, where there is nothing to collide.
		assertEquals(0, GraphServerOptions.parse(new String[] { "--wire-port=0", "/data/g", "0" }).wirePort());
	}

	@Test
	public void testMaxStreamsIsValidated() {
		assertMessage("--max-streams must be a number", new String[] { "--max-streams=many", "/data/g" });
		assertMessage("--max-streams must be at least 1", new String[] { "--max-streams=0", "/data/g" });
	}

	@Test
	public void testTheDefaultDatabaseIsNamedAndValidated() {
		assertEquals("default", GraphServerOptions.parse(new String[] { "/data/g" }).database());
		assertEquals("sales", GraphServerOptions.parse(new String[] { "--database=sales", "/data/g" }).database());
		// The name becomes a path. A usage error printed with the usage text beats a stack trace out of the storage
		// layer, so it is checked here rather than at open time.
		assertThrows(IllegalArgumentException.class,
			() -> GraphServerOptions.parse(new String[] { "--database=../escaped", "/data/g" }));
		assertThrows(IllegalArgumentException.class,
			() -> GraphServerOptions.parse(new String[] { "--database=", "/data/g" }));
	}

	@Test
	public void testDroppingIsOffUnlessAskedFor() {
		// The only route that destroys data, on a port with no authentication, beside a query language that cannot
		// delete a single node. Off is the only defensible default.
		assertFalse(GraphServerOptions.parse(new String[] { "/data/g" }).allowDrop());
		assertTrue(GraphServerOptions.parse(new String[] { "--allow-drop", "/data/g" }).allowDrop());
	}

	@Test
	public void testHelpIsARequestRatherThanAMistake() {
		// So that `--help` can print the usage and exit zero.
		assertThrows(GraphServerOptions.HelpRequested.class,
			() -> GraphServerOptions.parse(new String[] { "--help" }));
		assertThrows(GraphServerOptions.HelpRequested.class,
			() -> GraphServerOptions.parse(new String[] { "-h" }));

		String usage = GraphServerOptions.usage();
		assertTrue(usage.contains("--demo-size"), "the usage has to name every flag it accepts");
		assertTrue(usage.contains(String.valueOf(DemoData.SMALL)), "and what the sizes mean");
		assertTrue(usage.contains("--query-timeout"), "including the one that bounds a runaway query");
		assertTrue(usage.contains("--wire-port"), "and the binary port, which is on by default");
		assertTrue(usage.contains("--max-streams"), "and the factor an operator has to multiply");
		assertTrue(usage.contains("authentication"),
			"a server with two unauthenticated ports should say so where somebody will read it");
		assertTrue(usage.contains("--database"), "and the flag that picks which database a request gets");
		assertTrue(usage.contains("--allow-drop"), "and the one that turns on the only route that deletes data");
	}
}
