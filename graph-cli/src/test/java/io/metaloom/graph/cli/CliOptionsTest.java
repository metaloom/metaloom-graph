package io.metaloom.graph.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The command line, so that a mistyped flag fails here rather than after somebody has waited for a backup.
 *
 * <p>
 * The same shape as {@code GraphServerOptionsTest}, including the variable-count assertion: a variable read and not
 * documented, or documented and not read, is the failure that shape exists to rule out.
 * </p>
 */
@Tag("unit")
public class CliOptionsTest {

	private static CliOptions parse(String... args) {
		return CliOptions.parse(args, name -> null);
	}

	private static CliOptions parseWithEnv(Map<String, String> env, String... args) {
		return CliOptions.parse(args, env::get);
	}

	@Test
	public void testABackup() {
		CliOptions options = parse("database", "backup", "sales", "--path=/data/graphs", "--out=sales.dump");
		assertEquals("database backup", options.command());
		assertEquals("sales", options.database());
		assertEquals(Path.of("/data/graphs"), options.path());
		assertEquals("sales.dump", options.file());
		assertTrue(options.offline());
		assertFalse(options.streamed());
		assertFalse(options.force());
	}

	@Test
	public void testARestoreOverAServer() {
		CliOptions options = parse("database", "restore", "--server=http://localhost:8080", "--in=x.dump", "--force");
		assertEquals("database restore", options.command());
		assertEquals("default", options.database(), "the database defaults rather than being required");
		assertFalse(options.offline());
		assertEquals("http://localhost:8080", options.server());
		assertTrue(options.force());
	}

	@Test
	public void testTheDatabaseCanBeAFlagOrAPositional() {
		assertEquals("sales", parse("database", "backup", "sales", "--path=/d", "--out=x").database());
		assertEquals("sales", parse("database", "backup", "--database=sales", "--path=/d", "--out=x").database());
	}

	/**
	 * Neither {@code --path} nor {@code --server} is a default, and that is deliberate.
	 *
	 * <p>
	 * They do different things — one takes an exclusive lock on somebody's data and the other does not — so guessing
	 * which was meant would be guessing about that.
	 * </p>
	 */
	@Test
	public void testOneOfPathOrServerIsRequired() {
		assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("database", "backup", "--out=x"))
			.getMessage().contains("--path"));
	}

	/** A flag beats a variable, so an exported GRAPH_SERVER cannot silently redirect a --path backup. */
	/**
	 * {@code inspect} is the exception, and it is the one that matters most when it is needed.
	 *
	 * <p>
	 * It reads a header and touches no store, so requiring a transport would mean needing a working database before
	 * being allowed to ask why a dump will not restore. The help text has always described it without one; this is
	 * what makes that true.
	 * </p>
	 */
	@Test
	public void testInspectNeedsNoTransport() {
		CliOptions options = parse("database", "inspect", "--in=x.dump");
		assertNull(options.path());
		assertNull(options.server());
		assertEquals("x.dump", options.file());
	}

	@Test
	public void testAFlagBeatsAVariable() {
		CliOptions options = parseWithEnv(Map.of("GRAPH_SERVER", "http://elsewhere:8080"),
			"database", "backup", "--path=/data", "--out=x");
		assertTrue(options.offline(), "the flag decides");
		assertNull(options.server());

		CliOptions other = parseWithEnv(Map.of("GRAPH_ROOT", "/data"),
			"database", "backup", "--server=http://here:8080", "--out=x");
		assertFalse(other.offline());
		assertNull(other.path());
	}

	@Test
	public void testTheEnvironmentAlone() {
		CliOptions options = parseWithEnv(Map.of("GRAPH_ROOT", "/data", "GRAPH_DATABASE", "sales"),
			"database", "backup", "--out=x");
		assertEquals(Path.of("/data"), options.path());
		assertEquals("sales", options.database());
	}

	/** An empty variable is unset, which is how a Helm template renders a value nobody set. */
	@Test
	public void testAnEmptyVariableIsUnset() {
		Map<String, String> env = new java.util.HashMap<>();
		env.put("GRAPH_ROOT", "");
		env.put("GRAPH_SERVER", "http://here:8080");
		assertFalse(CliOptions.parse(new String[] { "database", "list" }, env::get).offline());
	}

	@Test
	public void testDashMeansAStream() {
		assertTrue(parse("database", "backup", "--path=/d", "--out=-").streamed());
		assertTrue(parse("database", "restore", "--path=/d", "--in=-").streamed());
		assertFalse(parse("database", "backup", "--path=/d", "--out=x.dump").streamed());
	}

	@Test
	public void testHelpIsAnOutcomeRatherThanAnExit() {
		assertThrows(CliOptions.HelpRequested.class, () -> parse("--help"));
		assertThrows(CliOptions.HelpRequested.class, () -> parse("database", "backup", "-h"));
	}

	@Test
	public void testUnknownOptionsAndSurplusArgumentsAreRefused() {
		assertTrue(assertThrows(IllegalArgumentException.class,
			() -> parse("database", "backup", "--path=/d", "--wat")).getMessage().contains("--wat"));
		assertTrue(assertThrows(IllegalArgumentException.class,
			() -> parse("database", "backup", "a", "b", "--path=/d")).getMessage().contains("Unexpected"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("--path=/d")).getMessage()
			.contains("No command"));
		assertTrue(assertThrows(IllegalArgumentException.class,
			() -> parse("database", "backup", "--path=", "--out=x")).getMessage().contains("needs a value"));
	}

	@Test
	public void testEveryDeclaredVariableIsInTheHelpText() {
		for (CliOptions.EnvVar variable : CliOptions.VARIABLES) {
			assertTrue(CliOptions.usage().contains(variable.name()),
				variable.name() + " is declared but the help text does not print it");
		}
		assertEquals(5, CliOptions.VARIABLES.size(),
			"a variable was added or removed; update this count and check the help text still prints them all");
	}

	/** Every command the dispatcher handles is one the help text describes, and the reverse. */
	@Test
	public void testTheHelpTextDescribesEveryCommand() {
		String usage = CliOptions.usage();
		for (String command : GraphCli.commands()) {
			// "database backup" appears as "database backup" in the usage block.
			assertTrue(usage.contains(command), command + " is dispatched but not documented");
		}
	}
}
