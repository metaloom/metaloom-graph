package io.metaloom.graph.apilock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A committed API baseline: {@code <module>/api/<module>.api}.
 *
 * <p>
 * A comment block, a header of {@code key: value} lines ({@code format}, {@code module}, {@code policy},
 * {@code sha256}), a blank line, and the dump. The {@code sha256} is over every other line of the file, each followed by
 * a newline, so a hand edit anywhere — the dump, the policy, the comments — or a partially written file fails
 * {@link #checksumValid()}. Only {@link ApiLockTool} writes one; {@link ApiLock}, which the tests call, only reads.
 * </p>
 *
 * @param format
 *            the {@link ApiDump#FORMAT} the dump was written with
 * @param module
 *            the module's artifactId
 * @param policy
 *            what the accept tool may record
 * @param body
 *            the dump
 * @param checksumValid
 *            whether the recorded checksum matches the file
 */
public record Baseline(int format, String module, Policy policy, List<String> body, boolean checksumValid) {

	/** What the accept tool will record for a module. */
	public enum Policy {
		/** Any change, breaking or not. The alpha releases. */
		ALPHA,
		/** Additions only; a removal or change needs {@code --allow-breaking}. */
		STABLE;

		public String label() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Policy parse(String label) {
			for (Policy policy : values()) {
				if (policy.label().equals(label)) {
					return policy;
				}
			}
			throw new IllegalArgumentException("Unknown policy '" + label + "': expected alpha or stable");
		}
	}

	private static final String SHA_KEY = "sha256";

	public Baseline {
		body = List.copyOf(body);
	}

	/** Reads and checks a baseline file. Never repairs it. */
	public static Baseline read(Path file) {
		List<String> lines;
		try {
			// readAllLines strips \r\n as well as \n, so a checkout that converted line endings still verifies.
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read the API baseline " + file, e);
		}
		Map<String, String> header = new LinkedHashMap<>();
		List<String> body = new ArrayList<>();
		List<String> covered = new ArrayList<>();
		String recordedSha = null;
		boolean inBody = false;
		for (String line : lines) {
			if (inBody) {
				covered.add(line);
				if (!line.isEmpty()) {
					body.add(line);
				}
				continue;
			}
			if (line.startsWith(SHA_KEY + ": ")) {
				recordedSha = line.substring(SHA_KEY.length() + 2).trim();
				continue;
			}
			covered.add(line);
			if (line.isEmpty()) {
				inBody = true;
			} else if (!line.startsWith("#")) {
				int colon = line.indexOf(": ");
				if (colon < 0) {
					throw new IllegalStateException("Malformed header line in " + file + ": " + line);
				}
				header.put(line.substring(0, colon), line.substring(colon + 2).trim());
			}
		}
		for (String key : List.of("format", "module", "policy")) {
			if (!header.containsKey(key)) {
				throw new IllegalStateException("The API baseline " + file + " has no '" + key + ":' header line");
			}
		}
		boolean valid = recordedSha != null && recordedSha.equals(sha256(covered));
		return new Baseline(Integer.parseInt(header.get("format")), header.get("module"), Policy.parse(header.get("policy")),
			body, valid);
	}

	/** The file's text, checksum included. */
	static String render(String module, Policy policy, int format, List<String> body) {
		List<String> lines = new ArrayList<>();
		lines.add("# MetaLoom // Graph: the locked public Java API of " + module + ".");
		lines.add("#");
		lines.add("# Written by tools/api-lock.sh, never by hand and never by a test. The sha256 line covers every other");
		lines.add("# line of this file, so an edit anywhere fails ApiLockTest; so does any difference between this dump and");
		lines.add("# the compiled code. To change the API on purpose, read spec/API_LOCK.md section 4.");
		lines.add("# AI agents: never run the accept tool and never edit this file. See AGENTS.md.");
		lines.add("format: " + format);
		lines.add("module: " + module);
		lines.add("policy: " + policy.label());
		lines.add("");
		lines.addAll(body);
		String sha = sha256(lines);
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			if (i == 9) {
				text.append(SHA_KEY).append(": ").append(sha).append('\n');
			}
			text.append(lines.get(i)).append('\n');
		}
		return text.toString();
	}

	/** Writes a baseline through a temporary file and a move, so a crash leaves the old file or the new, not half. */
	static void write(Path file, String module, Policy policy, List<String> body) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, render(module, policy, ApiDump.FORMAT, body), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write the API baseline " + file, e);
		}
	}

	private static String sha256(List<String> lines) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String line : lines) {
				digest.update(line.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) '\n');
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
