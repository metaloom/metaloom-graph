package io.metaloom.graph.apilock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file format, and the checksum that makes a hand-edited baseline fail. */
@Tag("unit")
public class BaselineTest {

	private static final List<String> BODY = List.of("scope package a", "a.B type public final class B",
		"a.B method public int size()");

	@TempDir
	Path dir;

	private Path written(Baseline.Policy policy) {
		Path file = dir.resolve("api/m.api");
		Baseline.write(file, "m", policy, BODY);
		return file;
	}

	@Test
	public void testRoundTrip() {
		Baseline baseline = Baseline.read(written(Baseline.Policy.STABLE));
		assertTrue(baseline.checksumValid());
		assertEquals("m", baseline.module());
		assertEquals(Baseline.Policy.STABLE, baseline.policy());
		assertEquals(ApiDump.FORMAT, baseline.format());
		assertEquals(BODY, baseline.body());
	}

	@Test
	public void testTheFileTellsAReaderWhatItIs() throws Exception {
		String text = Files.readString(written(Baseline.Policy.ALPHA));
		assertTrue(text.contains("tools/api-lock.sh"), text);
		assertTrue(text.contains("spec/API_LOCK.md"), text);
		assertTrue(text.contains("AGENTS.md"), text);
		assertTrue(text.contains("\npolicy: alpha\n"), text);
		assertTrue(text.contains("\nsha256: "), text);
	}

	@Test
	public void testAHandEditedBodyFailsTheChecksum() throws Exception {
		Path file = written(Baseline.Policy.ALPHA);
		Files.writeString(file, Files.readString(file).replace("int size()", "long size()"));
		assertFalse(Baseline.read(file).checksumValid());
	}

	@Test
	public void testAHandEditedPolicyFailsTheChecksum() throws Exception {
		Path file = written(Baseline.Policy.STABLE);
		Files.writeString(file, Files.readString(file).replace("policy: stable", "policy: alpha"));
		Baseline baseline = Baseline.read(file);
		assertEquals(Baseline.Policy.ALPHA, baseline.policy());
		assertFalse(baseline.checksumValid());
	}

	@Test
	public void testADeletedOrAppendedLineFailsTheChecksum() throws Exception {
		Path file = written(Baseline.Policy.ALPHA);
		String text = Files.readString(file);
		Files.writeString(file, text.replace("a.B method public int size()\n", ""));
		assertFalse(Baseline.read(file).checksumValid(), "truncated");
		Files.writeString(file, text + "a.B method public int extra()\n");
		assertFalse(Baseline.read(file).checksumValid(), "appended");
		Files.writeString(file, text.replace("# AI agents", "# Agents"));
		assertFalse(Baseline.read(file).checksumValid(), "comment edited");
	}

	@Test
	public void testCrlfLineEndingsStillVerify() throws Exception {
		Path file = written(Baseline.Policy.ALPHA);
		Files.writeString(file, Files.readString(file).replace("\n", "\r\n"));
		assertTrue(Baseline.read(file).checksumValid());
	}
}
