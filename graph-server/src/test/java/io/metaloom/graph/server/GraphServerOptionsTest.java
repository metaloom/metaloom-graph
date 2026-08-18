package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

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
		GraphServerOptions options = parse("/data/mygraph", "9000");
		assertEquals(Path.of("/data/mygraph"), options.root());
		assertEquals(9000, options.port());
		assertFalse(options.demo(), "demo data is never the default: it writes");
	}

	@Test
	public void testThePortIsOptional() {
		assertEquals(GraphServerOptions.DEFAULT_PORT, parse("/data/g").port());
	}

	@Test
	public void testDemoDefaultsToTheSmallGraph() {
		GraphServerOptions options = parse("--demo", "/data/g");
		assertTrue(options.demo());
		assertEquals(DemoData.SMALL, options.demoAirports());
	}

	@Test
	public void testASizeImpliesDemo() {
		// Asking for a size and getting no data because a second flag was missing is a silly way to lose a minute.
		GraphServerOptions options = parse("--demo-size=tiny", "/data/g");
		assertTrue(options.demo());
		assertEquals(DemoData.TINY, options.demoAirports());
	}

	@Test
	public void testASizeCanBeANumber() {
		assertEquals(120, parse("--demo-size=120", "/data/g").demoAirports());
	}

	@Test
	public void testOptionsMayFollowTheStore() {
		GraphServerOptions options = parse("/data/g", "8100", "--demo-size=large");
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
			() -> parse(args));
		assertTrue(thrown.getMessage().contains(expected),
			"expected a message containing '" + expected + "', was '" + thrown.getMessage() + "'");
	}

	/**
	 * Parses against an <b>empty</b> environment.
	 *
	 * <p>
	 * Every test here goes through this rather than through {@link GraphServerOptions#parse(String[])}, because that
	 * one reads the real process environment: a developer with {@code GRAPH_ALLOW_WRITE} exported would otherwise see
	 * failures in this class and no reason for them.
	 * </p>
	 */
	private static GraphServerOptions parse(String... args) {
		return GraphServerOptions.parse(args, name -> null);
	}

	/** Parses against a fixed environment, given as alternating name and value. */
	private static GraphServerOptions parseWithEnv(Map<String, String> environment, String... args) {
		return GraphServerOptions.parse(args, environment::get);
	}

	@Test
	public void testTheQueryTimeoutIsParsedAndDefaulted() {
		assertEquals(GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS,
			parse("/data/g").queryTimeoutMillis(),
			"a store with no limit named still gets one, or a runaway query holds a view for as long as it likes");
		assertEquals(1500, parse("--query-timeout=1500", "/data/g")
			.queryTimeoutMillis());
		// Zero is the documented way to ask for no limit, so it must survive parsing rather than be treated as unset.
		assertEquals(0, parse("--query-timeout=0", "/data/g")
			.queryTimeoutMillis());

		assertMessage("--query-timeout must be a number", new String[] { "--query-timeout=soon", "/data/g" });
		assertMessage("--query-timeout must not be negative", new String[] { "--query-timeout=-1", "/data/g" });
	}

	@Test
	public void testTheBinaryPortIsOnByDefaultAndCanBeTurnedOff() {
		GraphServerOptions defaults = parse("/data/g");
		assertTrue(defaults.wire(), "the binary port is part of the server rather than an extra to be discovered");
		assertEquals(io.metaloom.graph.protocol.Protocol.DEFAULT_PORT, defaults.wirePort());
		assertEquals(8, defaults.maxConcurrentStreams());

		assertFalse(parse("--no-wire", "/data/g").wire());
		assertEquals(9100, parse("--wire-port=9100", "/data/g").wirePort());
		assertEquals(0, parse("--wire-port=0", "/data/g").wirePort(),
			"zero means any free port here as it does for the HTTP one");
		assertEquals(2, parse("--max-streams=2", "/data/g")
			.maxConcurrentStreams());
	}

	@Test
	public void testTheTwoPortsMayNotBeTheSameOne() {
		// Otherwise it surfaces as "address already in use" from whichever front end happened to bind second, which
		// says nothing about the actual mistake.
		assertMessage("both 8080", new String[] { "--wire-port=8080", "/data/g", "8080" });
		// ...unless both are asking for any free port, where there is nothing to collide.
		assertEquals(0, parse("--wire-port=0", "/data/g", "0").wirePort());

		// All four listeners, not just the two that existed first. The monitoring port is on by default, so serving
		// HTTP on 8082 is the collision an operator is most likely to walk into.
		assertMessage("both 8082", new String[] { "/data/g", "8082" });
		assertMessage("both 9000", new String[] { "--metrics-port=9000", "--mcp-port=9000", "/data/g" });
		assertMessage("both 9000", new String[] { "--wire-port=9000", "--mcp-port=9000", "/data/g" });
		// A disabled front end cannot collide with anything, so its port is not compared.
		assertEquals(8082, parse("--no-metrics", "--wire-port=8082", "/data/g").wirePort());
		assertEquals(8083, parse("--wire-port=8083", "/data/g").wirePort(),
			"the MCP port is off by default, so its number is free");
	}

	@Test
	public void testMaxStreamsIsValidated() {
		assertMessage("--max-streams must be a number", new String[] { "--max-streams=many", "/data/g" });
		assertMessage("--max-streams must be at least 1", new String[] { "--max-streams=0", "/data/g" });
	}

	@Test
	public void testTheDefaultDatabaseIsNamedAndValidated() {
		assertEquals("default", parse("/data/g").database());
		assertEquals("sales", parse("--database=sales", "/data/g").database());
		// The name becomes a path. A usage error printed with the usage text beats a stack trace out of the storage
		// layer, so it is checked here rather than at open time.
		assertThrows(IllegalArgumentException.class,
			() -> parse("--database=../escaped", "/data/g"));
		assertThrows(IllegalArgumentException.class,
			() -> parse("--database=", "/data/g"));
	}

	@Test
	public void testWritingIsOffUnlessAskedFor() {
		// Both ports, one flag. Off is the only defensible default when neither has authentication.
		assertFalse(parse("/data/g").allowWrite());
		assertTrue(parse("--allow-write", "/data/g").allowWrite());
		// And the two flags are independent: enabling writes is not asking to be able to delete whole databases.
		assertFalse(parse("--allow-write", "/data/g").allowDrop());
		assertFalse(parse("--allow-drop", "/data/g").allowWrite());
	}

	@Test
	public void testDroppingIsOffUnlessAskedFor() {
		// The only route that destroys data, on a port with no authentication, beside a query language that cannot
		// delete a single node. Off is the only defensible default.
		assertFalse(parse("/data/g").allowDrop());
		assertTrue(parse("--allow-drop", "/data/g").allowDrop());
	}

	@Test
	public void testTheEnvironmentSuppliesEverySetting() {
		// A container image cannot be reconfigured without rebuilding its CMD, and a chart has environment variables
		// and not an argument vector. Every variable is checked here because one that is declared and not read starts
		// a server that ignores it silently.
		GraphServerOptions options = parseWithEnv(Map.ofEntries(
			Map.entry("GRAPH_ROOT", "/data/fromenv"),
			Map.entry("GRAPH_HTTP_PORT", "9090"),
			Map.entry("GRAPH_DATABASE", "sales"),
			Map.entry("GRAPH_ALLOW_WRITE", "true"),
			Map.entry("GRAPH_ALLOW_DROP", "true"),
			Map.entry("GRAPH_DEMO", "true"),
			Map.entry("GRAPH_DEMO_SIZE", "tiny"),
			Map.entry("GRAPH_QUERY_TIMEOUT_MILLIS", "1234"),
			Map.entry("GRAPH_SLOW_QUERY_MILLIS", "77"),
			Map.entry("GRAPH_WIRE_PORT", "9091"),
			Map.entry("GRAPH_WIRE_HOST", "0.0.0.0"),
			Map.entry("GRAPH_MAX_STREAMS", "3"),
			Map.entry("GRAPH_METRICS_PORT", "9092"),
			Map.entry("GRAPH_MCP_ENABLED", "true"),
			Map.entry("GRAPH_MCP_PORT", "9093"),
			Map.entry("GRAPH_MCP_HOST", "0.0.0.0")));

		assertEquals(Path.of("/data/fromenv"), options.root(), "GRAPH_ROOT is what lets CMD carry no arguments");
		assertEquals(9090, options.port());
		assertEquals("sales", options.database());
		assertTrue(options.allowWrite());
		assertTrue(options.allowDrop());
		assertTrue(options.demo());
		assertEquals(DemoData.TINY, options.demoAirports());
		assertEquals(1234, options.queryTimeoutMillis());
		assertEquals(77, options.slowQueryMillis());
		assertEquals(9091, options.wirePort());
		assertEquals("0.0.0.0", options.wireHost());
		assertEquals(3, options.maxConcurrentStreams());
		assertEquals(9092, options.metricsPort());
		assertTrue(options.mcp());
		assertEquals(9093, options.mcpPort());
		assertEquals("0.0.0.0", options.mcpHost());
	}

	@Test
	public void testEveryDeclaredVariableIsActuallyRead() {
		// The guard against the list in VARIABLES drifting away from the parser: a variable printed by --help and read
		// by nothing is worse than one that was never documented, because it has been promised.
		for (GraphServerOptions.EnvVar variable : GraphServerOptions.VARIABLES) {
			assertTrue(GraphServerOptions.usage().contains(variable.name()),
				variable.name() + " is declared but --help does not print it");
		}
		// And the reverse direction, spelled out: the count is asserted so that adding a variable to the parser
		// without adding it to VARIABLES fails here rather than being noticed by an operator.
		assertEquals(24, GraphServerOptions.VARIABLES.size(),
			"a variable was added or removed; update this count and check --help still prints all of them");
	}

	/**
	 * {@code LOAD CSV} is off by default, and the two settings that enable it only make sense together.
	 *
	 * <p>
	 * Both halves are refused at parse time rather than at the first query. A server that took
	 * {@code --allow-load-csv} and then refused every load would look like a bug in the queries, and one that took
	 * {@code --import-dir} without the flag reads as enabled and is not — which is the more dangerous of the two,
	 * because it is the one an operator would believe.
	 * </p>
	 */
	@Test
	public void testLoadCsvIsOffByDefaultAndNeedsAnImportDirectory() {
		GraphServerOptions defaults = parse("/data");
		assertFalse(defaults.allowLoadCsv(), "off unless asked for");
		assertNull(defaults.importDir());

		GraphServerOptions enabled = parse("/data", "--allow-load-csv", "--import-dir=/srv/import");
		assertTrue(enabled.allowLoadCsv());
		assertEquals(Path.of("/srv/import"), enabled.importDir());

		assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("/data", "--allow-load-csv"))
			.getMessage().contains("--import-dir"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("/data", "--import-dir=/srv/import"))
			.getMessage().contains("--allow-load-csv"));

		// And through the environment, which is how the chart sets it.
		GraphServerOptions fromEnv = parseWithEnv(Map.of("GRAPH_ROOT", "/data",
			"GRAPH_ALLOW_LOAD_CSV", "true", "GRAPH_IMPORT_DIR", "/srv/import"));
		assertTrue(fromEnv.allowLoadCsv());
		assertEquals(Path.of("/srv/import"), fromEnv.importDir());
	}

	/** A backup hands over everything in one request, so it is its own decision rather than part of another. */
	@Test
	public void testBackupIsOffByDefaultAndIndependentOfTheOtherFlags() {
		assertFalse(parse("/data").allowBackup());
		assertFalse(parse("/data", "--allow-write").allowBackup(), "enabling writes is not enabling this");
		assertFalse(parse("/data", "--allow-drop").allowBackup());
		assertTrue(parse("/data", "--allow-backup").allowBackup());
		assertFalse(parse("/data", "--allow-backup").allowWrite(), "and it does not enable anything else either");
		assertTrue(parseWithEnv(Map.of("GRAPH_ROOT", "/data", "GRAPH_ALLOW_BACKUP", "true")).allowBackup());
	}

	@Test
	public void testAFlagBeatsAVariable() {
		// Otherwise a shell that exported GRAPH_ALLOW_WRITE for one server silently changes what a second one does.
		GraphServerOptions options = parseWithEnv(
			Map.of("GRAPH_ROOT", "/data/fromenv", "GRAPH_HTTP_PORT", "9090", "GRAPH_DATABASE", "fromenv"),
			"/data/fromflag", "9100", "--database=fromflag");
		assertEquals(Path.of("/data/fromflag"), options.root());
		assertEquals(9100, options.port());
		assertEquals("fromflag", options.database());
	}

	@Test
	public void testAnEmptyVariableIsUnsetRatherThanEmpty() {
		// An unset optional value is exactly how a Helm template renders one. Treating "" as a real setting would turn
		// an omitted chart value into an empty database name, which is refused, at start-up, in production.
		GraphServerOptions options = parseWithEnv(Map.of("GRAPH_DATABASE", "  ", "GRAPH_WIRE_HOST", ""), "/data/g");
		assertEquals("default", options.database());
		assertEquals("127.0.0.1", options.wireHost());
	}

	@Test
	public void testABooleanTypoIsRefusedRatherThanReadAsFalse() {
		// Boolean.parseBoolean maps every typo to false, so GRAPH_ALLOW_WRITE=ture would give a read-only server and
		// no reason for it.
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
			() -> parseWithEnv(Map.of("GRAPH_ALLOW_WRITE", "ture"), "/data/g"));
		assertTrue(thrown.getMessage().contains("GRAPH_ALLOW_WRITE must be true or false"),
			"the message has to name the variable, was '" + thrown.getMessage() + "'");
		assertTrue(parseWithEnv(Map.of("GRAPH_ALLOW_WRITE", "yes"), "/data/g").allowWrite(),
			"the forms an operator actually writes are accepted");
		assertFalse(parseWithEnv(Map.of("GRAPH_ALLOW_WRITE", "0"), "/data/g").allowWrite());
	}

	@Test
	public void testTheSlowQueryThresholdIsParsedAndDefaulted() {
		assertEquals(GraphServerOptions.DEFAULT_SLOW_QUERY_MILLIS, parse("/data/g").slowQueryMillis());
		assertEquals(50, parse("--slow-query-millis=50", "/data/g").slowQueryMillis());
		// Zero is the documented way to log nothing, so it has to survive parsing rather than be treated as unset.
		assertEquals(0, parse("--slow-query-millis=0", "/data/g").slowQueryMillis());
		assertMessage("--slow-query-millis must not be negative", new String[] { "--slow-query-millis=-1", "/data/g" });
	}

	@Test
	public void testTheMonitoringPortIsOnAndTheMcpPortIsNot() {
		GraphServerOptions defaults = parse("/data/g");
		assertTrue(defaults.metrics(), "a database that cannot be scraped is a database nobody can operate");
		assertEquals(GraphServerOptions.DEFAULT_METRICS_PORT, defaults.metricsPort());
		assertFalse(defaults.mcp(), "MCP is a remote code path an LLM drives, against a server with no authentication");
		assertEquals(GraphServerOptions.DEFAULT_MCP_HOST, defaults.mcpHost(), "and it binds loopback when it is on");

		assertFalse(parse("--no-metrics", "/data/g").metrics());
		assertEquals(9200, parse("--metrics-port=9200", "/data/g").metricsPort());
		assertTrue(parse("--mcp", "/data/g").mcp());
		// Naming a port or a host is asking for the port, so that --mcp-port=9300 alone is not silently ignored.
		assertTrue(parse("--mcp-port=9300", "/data/g").mcp());
		assertTrue(parse("--mcp-host=0.0.0.0", "/data/g").mcp());
	}

	@Test
	public void testHelpIsARequestRatherThanAMistake() {
		// So that `--help` can print the usage and exit zero.
		assertThrows(GraphServerOptions.HelpRequested.class,
			() -> parse("--help"));
		assertThrows(GraphServerOptions.HelpRequested.class,
			() -> parse("-h"));

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
		assertTrue(usage.contains("--allow-write"), "and the one that lets a query change a store at all");
	}

	// ---------------------------------------------------------------- authentication

	/**
	 * On by default, and it is the only setting here that is.
	 *
	 * <p>
	 * Every capability flag defaults to off because enabling one is a decision. This defaults to on for the mirror
	 * reason: it is the setting whose <em>unsafe</em> value is the one somebody gets by not thinking about it, so not
	 * thinking about it has to be the safe outcome.
	 * </p>
	 */
	@Test
	public void testAuthenticationIsOnByDefault() {
		assertTrue(GraphServerOptions.parse(new String[] { "/tmp/x" }, name -> null).auth());
	}

	@Test
	public void testAuthenticationCanBeTurnedOffByFlagOrVariable() {
		assertFalse(GraphServerOptions.parse(new String[] { "--no-auth", "/tmp/x" }, name -> null).auth());
		assertFalse(GraphServerOptions.parse(new String[] { "/tmp/x" },
			name -> "GRAPH_AUTH_ENABLED".equals(name) ? "false" : null).auth());
	}

	/** A flag beats a variable, so a shell that exported one cannot decide for a second server. */
	@Test
	public void testTheFlagBeatsTheVariable() {
		assertFalse(GraphServerOptions.parse(new String[] { "--no-auth", "/tmp/x" },
			name -> "GRAPH_AUTH_ENABLED".equals(name) ? "true" : null).auth());
	}

	/** A typo must not silently disable authentication, which {@code Boolean.parseBoolean} would do. */
	@Test
	public void testAMisspeltBooleanIsRefusedRatherThanReadAsFalse() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> GraphServerOptions.parse(new String[] { "/tmp/x" },
				name -> "GRAPH_AUTH_ENABLED".equals(name) ? "ture" : null));
		assertTrue(refused.getMessage().contains("GRAPH_AUTH_ENABLED"), refused.getMessage());
	}

	/**
	 * A password with authentication off is refused at parse time.
	 *
	 * <p>
	 * The same shape as {@code --allow-load-csv} without {@code --import-dir}, and the more dangerous of the two
	 * misunderstandings it guards against: a chart that sets a password on a server that never asks for one reads as
	 * protected and is not.
	 * </p>
	 */
	@Test
	public void testAPasswordWithAuthenticationOffIsRefused() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> GraphServerOptions.parse(new String[] { "--no-auth", "--initial-password=x", "/tmp/x" },
				name -> null));
		assertTrue(refused.getMessage().contains("authentication is off"), refused.getMessage());
	}

	@Test
	public void testTheInitialPasswordComesFromTheEnvironment() {
		assertEquals("from-a-secret", GraphServerOptions.parse(new String[] { "/tmp/x" },
			name -> "GRAPH_INITIAL_PASSWORD".equals(name) ? "from-a-secret" : null).initialPassword());
	}

	/**
	 * The record must not print the password.
	 *
	 * <p>
	 * A record's generated {@code toString} prints every component, which would put a credential into any log line,
	 * exception or debugger view that touched this object. Overriding it is the only way to make that impossible
	 * rather than merely unlikely.
	 * </p>
	 */
	@Test
	public void testTheOptionsDoNotPrintTheInitialPassword() {
		GraphServerOptions options = GraphServerOptions.parse(new String[] { "--initial-password=hunter2", "/tmp/x" },
			name -> null);
		assertEquals("hunter2", options.initialPassword(), "it is still readable by the code that needs it");
		assertFalse(options.toString().contains("hunter2"), options.toString());
		assertTrue(options.toString().contains("initialPassword=<set>"), options.toString());
		assertTrue(GraphServerOptions.parse(new String[] { "/tmp/x" }, name -> null).toString()
			.contains("initialPassword=<unset>"));
	}

	/** And --help describes it as a credential, so an operator knows it needs a Secret. */
	@Test
	public void testHelpMarksTheCredentialVariable() {
		String usage = GraphServerOptions.usage();
		assertTrue(usage.contains("GRAPH_INITIAL_PASSWORD"), usage);
		assertTrue(usage.contains("a credential"), "the sensitive marker has to reach --help");
		assertTrue(usage.contains("--no-auth"), usage);
	}
}
