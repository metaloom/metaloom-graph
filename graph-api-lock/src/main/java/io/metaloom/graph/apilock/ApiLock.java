package io.metaloom.graph.apilock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The check {@code ApiLockTest} runs in each locked module: the live API against the committed baseline.
 *
 * <p>
 * This class reads the baseline and never writes it, under any flag or system property. A lock that could regenerate
 * its own baseline would pass whenever it was asked to, which is the same as not having one. Recording a change is
 * {@link ApiLockTool}, run by a person through {@code tools/api-lock.sh accept}; see spec/API_LOCK.md section 4.
 * </p>
 */
public final class ApiLock {

	private ApiLock() {
	}

	/**
	 * Fails with an {@link AssertionError} describing the difference if the module's public API does not match its
	 * baseline, or if the baseline is missing, hand-edited or written by another dump format.
	 *
	 * @param artifactId
	 *            the locked module, e.g. {@code graph-core}
	 */
	public static void verify(String artifactId) {
		LockedModule module = LockedModule.forArtifactId(artifactId);
		// Surefire sets basedir to the module directory; a run from the module directory without it works too.
		Path moduleDir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
		verify(module.target(moduleDir), loader());
	}

	static void verify(Target module, ClassLoader loader) {
		Path file = module.file();
		if (!Files.isRegularFile(file)) {
			throw new AssertionError("There is no API baseline for " + module.artifactId() + " at " + file + ".\n"
				+ "A person creates it, once, with:  tools/api-lock.sh accept " + module.artifactId() + "\n"
				+ "AI agents: do not create it yourself. Stop and report this to the user. See AGENTS.md.");
		}
		Baseline baseline = Baseline.read(file);
		if (!baseline.checksumValid()) {
			throw new AssertionError("The API baseline " + file + " fails its sha256 check: it was edited by hand, merged"
				+ " by a tool, or written only partially, and is not trusted.\n"
				+ "Baselines are only ever written by tools/api-lock.sh. Restore the committed file from git, or have a"
				+ " person regenerate it with  tools/api-lock.sh accept " + module.artifactId() + " --allow-breaking\n"
				+ "AI agents: do not edit the file or its checksum. Stop and report this to the user. See AGENTS.md.");
		}
		if (!baseline.module().equals(module.artifactId())) {
			throw new AssertionError("The API baseline " + file + " is the baseline of " + baseline.module() + ", not of "
				+ module.artifactId() + ".");
		}
		if (baseline.format() != ApiDump.FORMAT) {
			throw new AssertionError("The API baseline " + file + " was written by dump format " + baseline.format()
				+ ", and this build renders format " + ApiDump.FORMAT + ". The rendering changed, not necessarily the API:"
				+ " a person regenerates every baseline with tools/api-lock.sh accept <module> and reviews the diff.");
		}
		List<String> live = ApiDump.render(module.scope(), loader);
		ApiDiff diff = ApiDiff.between(baseline.body(), live);
		if (!diff.isEmpty()) {
			throw new AssertionError(report(module, file, baseline.policy(), diff));
		}
	}

	/** The failure message: what changed, and what to do about it. Shared with the accept tool. */
	static String report(Target module, Path file, Baseline.Policy policy, ApiDiff diff) {
		StringBuilder out = new StringBuilder();
		out.append("The public Java API of ").append(module.artifactId()).append(" no longer matches its locked baseline\n");
		out.append("  ").append(file).append("\n\n");
		out.append(diff.render()).append('\n');
		out.append("The API is locked: any change to it fails the build until a person accepts it.\n");
		out.append("  * If this change was not meant to touch the API, undo it.\n");
		out.append("  * Prefer additive evolution, which callers never notice: keep the old signature and add an overload;\n");
		out.append("    add a default method rather than an abstract one; deprecate with @Deprecated(since, forRemoval)\n");
		out.append("    and remove in a later release; add new enum constants at the end.\n");
		out.append("  * To accept the change on purpose, a person runs:  tools/api-lock.sh accept ").append(module.artifactId())
			.append('\n');
		out.append("    and commits the regenerated baseline together with the code. spec/API_LOCK.md section 4.\n");
		out.append("  * Policy of this baseline: ").append(policy.label()).append(" - ");
		out.append(policy == Baseline.Policy.ALPHA ? "breaking changes can still be accepted.\n"
			: "the accept tool refuses breaking changes without --allow-breaking.\n");
		out.append("AI agents: do not run the accept tool, edit the baseline, or weaken or skip this test. Stop and report\n");
		out.append("this diff to the user, or make the change additive. See AGENTS.md.\n");
		return out.toString();
	}

	private static ClassLoader loader() {
		ClassLoader context = Thread.currentThread().getContextClassLoader();
		return context != null ? context : ApiLock.class.getClassLoader();
	}
}
