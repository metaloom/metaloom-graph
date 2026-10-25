package io.metaloom.graph.apilock;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The accept tool: the one thing that writes an API baseline. Run through {@code tools/api-lock.sh}, which compiles the
 * module and puts its test classpath together; never from a test.
 *
 * <pre>
 *   check  &lt;artifactId&gt; &lt;moduleDir&gt;                          exit 1 if the API differs from the baseline
 *   accept &lt;artifactId&gt; &lt;moduleDir&gt; [--allow-breaking] [--policy alpha|stable]
 *   policy &lt;artifactId&gt; &lt;moduleDir&gt; alpha|stable [--allow-breaking]
 * </pre>
 *
 * <p>
 * {@code accept} records the live API. Under {@code policy: alpha} it records anything. Under {@code policy: stable} it
 * refuses a removal, a change or an abstract addition unless given {@code --allow-breaking}, and then says so loudly.
 * {@code --policy} only applies when there is no baseline yet. {@code policy} changes the level of an up-to-date
 * baseline; lowering stable to alpha takes {@code --allow-breaking} as well, since it is the same decision.
 * </p>
 */
public final class ApiLockTool {

	static final int OK = 0;
	static final int DIFFERS = 1;
	static final int REFUSED = 2;
	static final int USAGE = 64;

	private ApiLockTool() {
	}

	public static void main(String[] args) {
		int status;
		try {
			status = run(args, ApiLockTool.class.getClassLoader(), System.out, System.err);
		} catch (IllegalArgumentException e) {
			status = usage(System.err, e.getMessage());
		}
		System.exit(status);
	}

	static int run(String[] args, ClassLoader loader, PrintStream out, PrintStream err) {
		List<String> positional = new ArrayList<>();
		boolean allowBreaking = false;
		Baseline.Policy initialPolicy = Baseline.Policy.ALPHA;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--allow-breaking" -> allowBreaking = true;
				case "--policy" -> {
					if (i + 1 >= args.length) {
						return usage(err, "--policy needs alpha or stable");
					}
					initialPolicy = Baseline.Policy.parse(args[++i]);
				}
				default -> {
					if (args[i].startsWith("--")) {
						return usage(err, "unknown option " + args[i]);
					}
					positional.add(args[i]);
				}
			}
		}
		if (positional.size() < 3) {
			return usage(err, "missing arguments");
		}
		String command = positional.get(0);
		LockedModule module;
		try {
			module = LockedModule.forArtifactId(positional.get(1));
		} catch (IllegalArgumentException e) {
			return usage(err, e.getMessage());
		}
		Target target = module.target(Path.of(positional.get(2)));
		return switch (command) {
			case "check" -> positional.size() == 3 ? check(target, loader, out) : usage(err, "too many arguments");
			case "accept" -> positional.size() == 3 ? accept(target, loader, allowBreaking, initialPolicy, out, err)
				: usage(err, "too many arguments");
			case "policy" -> positional.size() == 4
				? policy(target, loader, Baseline.Policy.parse(positional.get(3)), allowBreaking, out, err)
				: usage(err, "policy needs alpha or stable");
			default -> usage(err, "unknown command " + command);
		};
	}

	static int check(Target module, ClassLoader loader, PrintStream out) {
		Path file = module.file();
		try {
			ApiLock.verify(module, loader);
			out.println(module.artifactId() + ": the API matches " + file);
			return OK;
		} catch (AssertionError e) {
			out.println(e.getMessage());
			return DIFFERS;
		}
	}

	static int accept(Target module, ClassLoader loader, boolean allowBreaking, Baseline.Policy initialPolicy,
		PrintStream out, PrintStream err) {
		Path file = module.file();
		List<String> live = ApiDump.render(module.scope(), loader);
		if (!Files.isRegularFile(file)) {
			Baseline.write(file, module.artifactId(), initialPolicy, live);
			out.println(module.artifactId() + ": created " + file + " (policy: " + initialPolicy.label() + ", " + live.size()
				+ " lines). Review it and commit it.");
			return OK;
		}

		Baseline baseline = Baseline.read(file);
		boolean untrusted = !baseline.checksumValid() || !baseline.module().equals(module.artifactId());
		if (untrusted && !allowBreaking) {
			err.println(module.artifactId() + ": " + file + " fails its sha256 check or names another module, so it was"
				+ " edited by hand or written partially. Restore it from git, or overwrite it from the code with"
				+ " --allow-breaking.");
			return REFUSED;
		}
		boolean formatChanged = baseline.format() != ApiDump.FORMAT;
		ApiDiff diff = ApiDiff.between(baseline.body(), live);
		if (diff.isEmpty() && !untrusted && !formatChanged) {
			out.println(module.artifactId() + ": the API matches " + file + "; nothing to record.");
			return OK;
		}

		out.println(diff.isEmpty() ? "(no line of the API changed)" : diff.render());
		if (formatChanged) {
			out.println("The dump format changes from " + baseline.format() + " to " + ApiDump.FORMAT
				+ ": some of the lines above may differ only in how they are rendered.");
		}
		boolean breaking = diff.isBreaking() || formatChanged || untrusted;
		if (baseline.policy() == Baseline.Policy.STABLE && breaking) {
			if (!allowBreaking) {
				err.println(module.artifactId() + " is under policy: stable, and this change can break callers (see above)."
					+ " Make it additive instead — spec/API_LOCK.md section 3 — or, if breaking the API is the decision,"
					+ " re-run with --allow-breaking.");
				return REFUSED;
			}
			warn(err, "RECORDING A BREAKING CHANGE TO THE STABLE API OF " + module.artifactId(),
				"Callers compiled against the previous baseline may no longer compile or link. This needs a major version"
					+ " and a release note.");
		}
		Baseline.write(file, module.artifactId(), baseline.policy(), live);
		out.println(module.artifactId() + ": wrote " + file + " (policy: " + baseline.policy().label() + ").");
		out.println("Review it with  git diff -- " + file + "  and commit it together with the code that changed the API.");
		return OK;
	}

	static int policy(Target module, ClassLoader loader, Baseline.Policy policy, boolean allowBreaking, PrintStream out,
		PrintStream err) {
		Path file = module.file();
		try {
			ApiLock.verify(module, loader);
		} catch (AssertionError e) {
			err.println(e.getMessage());
			err.println("The policy is only changed on a baseline that matches the code. Accept or undo the change first.");
			return REFUSED;
		}
		Baseline baseline = Baseline.read(file);
		if (baseline.policy() == policy) {
			out.println(module.artifactId() + " is already under policy: " + policy.label() + ".");
			return OK;
		}
		if (policy == Baseline.Policy.ALPHA) {
			if (!allowBreaking) {
				err.println("Lowering " + module.artifactId() + " from stable to alpha lets every later accept record a"
					+ " breaking change. That is the same decision as breaking it; re-run with --allow-breaking.");
				return REFUSED;
			}
			warn(err, "LOWERING THE API POLICY OF " + module.artifactId() + " FROM STABLE TO ALPHA",
				"Every later accept will record breaking changes without asking.");
		}
		Baseline.write(file, module.artifactId(), policy, baseline.body());
		out.println(module.artifactId() + ": policy is now " + policy.label() + " in " + file + ". Commit it.");
		return OK;
	}

	private static void warn(PrintStream err, String title, String detail) {
		String bar = "!".repeat(Math.max(title.length() + 8, 60));
		err.println(bar);
		err.println("!!! " + title);
		err.println("!!! " + detail);
		err.println(bar);
	}

	private static int usage(PrintStream err, String problem) {
		err.println("api-lock: " + problem);
		err.println("usage: check  <artifactId> <moduleDir>");
		err.println("       accept <artifactId> <moduleDir> [--allow-breaking] [--policy alpha|stable]");
		err.println("       policy <artifactId> <moduleDir> alpha|stable [--allow-breaking]");
		return USAGE;
	}
}
