package io.metaloom.graph.apilock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The accept tool and the check, against the fixture classes and a baseline in a temporary directory.
 *
 * <p>
 * A breaking change is simulated by editing the baseline through the tool's own writer — the compiled fixture classes
 * cannot change inside one test run, so the baseline is made to describe an older API instead.
 * </p>
 */
@Tag("unit")
public class ApiLockToolTest {

	private static final ClassLoader LOADER = ApiLockToolTest.class.getClassLoader();

	@TempDir
	Path dir;

	private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
	private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
	private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

	private Target target() {
		return new Target("fixture", ApiDumpTest.FIXTURE, dir.resolve("api/fixture.api"));
	}

	private String err() {
		return errBytes.toString(StandardCharsets.UTF_8);
	}

	/** Rewrites the baseline as the API looked before a change: an extra method, or a different return type. */
	private void baselineDescribes(Baseline.Policy policy, String removeLine, String addLine) {
		List<String> body = new ArrayList<>(ApiDumpTest.dump());
		if (removeLine != null) {
			assertTrue(body.remove(removeLine), removeLine);
		}
		if (addLine != null) {
			body.add(addLine);
		}
		Baseline.write(target().file(), "fixture", policy, body);
	}

	private static final String VALUE = ApiDumpTest.P + "Widget method public abstract T value()";

	@Test
	public void testAcceptCreatesAMatchingBaselineAsAlpha() {
		assertEquals(ApiLockTool.OK, ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err));
		Baseline baseline = Baseline.read(target().file());
		assertTrue(baseline.checksumValid());
		assertEquals(Baseline.Policy.ALPHA, baseline.policy());
		ApiLock.verify(target(), LOADER);
		assertEquals(ApiLockTool.OK, ApiLockTool.check(target(), LOADER, out));
	}

	@Test
	public void testTheCheckFailsWithAReadableDiffAndNeverWritesAnything() throws Exception {
		// The baseline says the API had a method it no longer has, and said value() returned something else.
		baselineDescribes(Baseline.Policy.ALPHA, VALUE, ApiDumpTest.P + "Widget method public abstract int legacyCount()");
		List<String> body = new ArrayList<>(Baseline.read(target().file()).body());
		body.add(ApiDumpTest.P + "Widget method public abstract java.lang.Object value()");
		Baseline.write(target().file(), "fixture", Baseline.Policy.ALPHA, body);

		byte[] before = Files.readAllBytes(target().file());
		AssertionError failure = assertThrows(AssertionError.class, () -> ApiLock.verify(target(), LOADER));
		String message = failure.getMessage();
		assertTrue(message.contains("BREAKING - removed (1):"), message);
		assertTrue(message.contains("- " + ApiDumpTest.P + "Widget method public abstract int legacyCount()"), message);
		assertTrue(message.contains("BREAKING - changed (1):"), message);
		assertTrue(message.contains("now: " + VALUE), message);
		assertTrue(message.contains("tools/api-lock.sh accept fixture"), message);
		assertTrue(message.contains("AI agents"), message);

		assertArrayEquals(before, Files.readAllBytes(target().file()), "the check modified the baseline");
		try (Stream<Path> files = Files.walk(dir)) {
			assertEquals(List.of(target().file()), files.filter(Files::isRegularFile).toList(), "the check wrote a file");
		}
	}

	@Test
	public void testTheCheckRejectsAHandEditedBaselineEvenWhenItMatches() throws Exception {
		ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err);
		Path file = target().file();
		Files.writeString(file, Files.readString(file).replace("policy: alpha", "policy: stable"));
		AssertionError failure = assertThrows(AssertionError.class, () -> ApiLock.verify(target(), LOADER));
		assertTrue(failure.getMessage().contains("fails its sha256 check"), failure.getMessage());
	}

	@Test
	public void testTheCheckFailsWithoutABaseline() {
		AssertionError failure = assertThrows(AssertionError.class, () -> ApiLock.verify(target(), LOADER));
		assertTrue(failure.getMessage().contains("There is no API baseline"), failure.getMessage());
		assertFalse(Files.exists(target().file()));
	}

	@Test
	public void testAlphaAcceptsABreakingChange() {
		baselineDescribes(Baseline.Policy.ALPHA, null, ApiDumpTest.P + "Widget method public abstract int legacyCount()");
		assertEquals(ApiLockTool.OK, ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err));
		ApiLock.verify(target(), LOADER);
		assertEquals(Baseline.Policy.ALPHA, Baseline.read(target().file()).policy());
	}

	@Test
	public void testStableRefusesABreakingChangeWithoutTheFlag() throws Exception {
		baselineDescribes(Baseline.Policy.STABLE, null, ApiDumpTest.P + "Widget method public abstract int legacyCount()");
		byte[] before = Files.readAllBytes(target().file());
		assertEquals(ApiLockTool.REFUSED, ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err));
		assertTrue(err().contains("policy: stable"), err());
		assertArrayEquals(before, Files.readAllBytes(target().file()));

		assertEquals(ApiLockTool.OK, ApiLockTool.accept(target(), LOADER, true, Baseline.Policy.ALPHA, out, err));
		assertTrue(err().contains("!!! RECORDING A BREAKING CHANGE TO THE STABLE API OF fixture"), err());
		ApiLock.verify(target(), LOADER);
		assertEquals(Baseline.Policy.STABLE, Baseline.read(target().file()).policy(), "accept never changes the policy");
	}

	@Test
	public void testStableRefusesAnAbstractMethodAddedToAnImplementableInterface() {
		// The baseline lacks value(): the live API added it, abstract, on an interface callers may implement.
		baselineDescribes(Baseline.Policy.STABLE, VALUE, null);
		assertEquals(ApiLockTool.REFUSED, ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err));
	}

	@Test
	public void testStableAcceptsADefaultMethodAdditionWithoutTheFlag() {
		String defaultMethod = ApiDumpTest.P + "Widget method public default java.util.List<T> values(java.util.Map<java.lang.String,"
			+ " ? super T>) throws java.io.IOException";
		baselineDescribes(Baseline.Policy.STABLE, defaultMethod, null);
		assertEquals(ApiLockTool.OK, ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err));
		assertFalse(err().contains("!!!"), err());
		ApiLock.verify(target(), LOADER);
	}

	@Test
	public void testAcceptRefusesToOverwriteAHandEditedBaselineWithoutTheFlag() throws Exception {
		ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err);
		Path file = target().file();
		Files.writeString(file, Files.readString(file) + "x.Y method public void injected()\n");
		assertEquals(ApiLockTool.REFUSED, ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err));
		assertEquals(ApiLockTool.OK, ApiLockTool.accept(target(), LOADER, true, Baseline.Policy.ALPHA, out, err));
		ApiLock.verify(target(), LOADER);
	}

	@Test
	public void testPolicyChangesOnlyOnAMatchingBaselineAndLoweringNeedsTheFlag() {
		ApiLockTool.accept(target(), LOADER, false, Baseline.Policy.ALPHA, out, err);
		assertEquals(ApiLockTool.OK, ApiLockTool.policy(target(), LOADER, Baseline.Policy.STABLE, false, out, err));
		assertEquals(Baseline.Policy.STABLE, Baseline.read(target().file()).policy());
		ApiLock.verify(target(), LOADER);

		assertEquals(ApiLockTool.REFUSED, ApiLockTool.policy(target(), LOADER, Baseline.Policy.ALPHA, false, out, err));
		assertEquals(ApiLockTool.OK, ApiLockTool.policy(target(), LOADER, Baseline.Policy.ALPHA, true, out, err));
		assertTrue(err().contains("!!! LOWERING THE API POLICY"), err());

		baselineDescribes(Baseline.Policy.ALPHA, null, ApiDumpTest.P + "Widget method public abstract int legacyCount()");
		assertEquals(ApiLockTool.REFUSED, ApiLockTool.policy(target(), LOADER, Baseline.Policy.STABLE, false, out, err),
			"a policy change must not record a pending API change along with it");
	}

	@Test
	public void testCommandLine() {
		assertEquals(ApiLockTool.USAGE, ApiLockTool.run(new String[] { "accept" }, LOADER, out, err));
		assertEquals(ApiLockTool.USAGE, ApiLockTool.run(new String[] { "accept", "graph-server", dir.toString() }, LOADER, out, err));
		assertTrue(err().contains("Not a locked module: graph-server"), err());
		assertEquals(ApiLockTool.USAGE, ApiLockTool.run(new String[] { "accept", "graph-core", dir.toString(), "--force" },
			LOADER, out, err));
	}
}
