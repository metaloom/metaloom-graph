package io.metaloom.graph.apilock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The difference between a baseline dump and the live one, split by what it means for a caller.
 *
 * <p>
 * A line only in the baseline is <em>removed</em>, a line only in the live dump is <em>added</em>, and a removal and an
 * addition that share an {@link ApiDump#groupKey(String) owner, category and member name} are one <em>change</em> — a
 * changed return type, an extra parameter, a method that lost {@code default}. Removed and changed are breaking: some
 * caller compiled against the baseline stops compiling or linking. Added is compatible, with one exception kept apart
 * as <em>abstract added</em>: a new abstract method on an existing type that a caller can implement or extend — not
 * sealed, not final, not an enum or record, and for a class one with an accessible constructor — breaks every
 * implementation outside this project, so it counts as breaking; so does an annotation element without a default. A
 * default method is the compatible way to add one.
 * </p>
 *
 * @param removed
 *            baseline lines with no counterpart
 * @param changed
 *            pairs of old and new lines for one member
 * @param abstractAdded
 *            new abstract methods on existing types that callers may implement
 * @param added
 *            live lines with no counterpart, other than the above
 */
public record ApiDiff(List<String> removed, List<Change> changed, List<String> abstractAdded, List<String> added) {

	/** One member whose lines differ: everything the baseline had for it, and everything the code has now. */
	public record Change(String key, List<String> was, List<String> now) {
	}

	public static ApiDiff between(List<String> baseline, List<String> live) {
		Set<String> before = new LinkedHashSet<>(baseline);
		Set<String> after = new LinkedHashSet<>(live);
		Map<String, List<String>> removedByKey = new LinkedHashMap<>();
		Map<String, List<String>> addedByKey = new LinkedHashMap<>();
		for (String line : before) {
			if (!after.contains(line)) {
				removedByKey.computeIfAbsent(ApiDump.groupKey(line), key -> new ArrayList<>()).add(line);
			}
		}
		for (String line : after) {
			if (!before.contains(line)) {
				addedByKey.computeIfAbsent(ApiDump.groupKey(line), key -> new ArrayList<>()).add(line);
			}
		}
		List<String> removed = new ArrayList<>();
		List<Change> changed = new ArrayList<>();
		List<String> abstractAdded = new ArrayList<>();
		List<String> added = new ArrayList<>();
		removedByKey.forEach((key, lines) -> {
			List<String> replacements = addedByKey.remove(key);
			if (replacements == null) {
				removed.addAll(lines);
			} else {
				changed.add(new Change(key, lines, replacements));
			}
		});
		for (List<String> lines : addedByKey.values()) {
			for (String line : lines) {
				(isAbstractAddition(line, before, after) ? abstractAdded : added).add(line);
			}
		}
		return new ApiDiff(removed, changed, abstractAdded, added);
	}

	private static boolean isAbstractAddition(String line, Set<String> before, Set<String> after) {
		String[] parts = line.split(" ", 3);
		if (parts.length < 3 || !parts[1].equals("method") || !(" " + parts[2]).contains(" abstract ")) {
			return false;
		}
		String owner = parts[0];
		String typeLine = typeLine(owner, after);
		if (typeLine == null || typeLine(owner, before) == null) {
			return false; // a new type: nobody implements it yet
		}
		String declaration = " " + typeLine.substring(owner.length() + " type ".length());
		if (declaration.contains(" @interface ")) {
			return !parts[2].contains(") default "); // an element without a default breaks every use of the annotation
		}
		if (declaration.contains(" sealed ") || declaration.contains(" final ") || declaration.contains(" enum ")
			|| declaration.contains(" record ")) {
			return false;
		}
		if (declaration.contains(" class ")) {
			String constructors = owner + " constructor ";
			return after.stream().anyMatch(other -> other.startsWith(constructors));
		}
		return true;
	}

	private static String typeLine(String owner, Set<String> lines) {
		String prefix = owner + " type ";
		return lines.stream().filter(line -> line.startsWith(prefix)).findFirst().orElse(null);
	}

	public boolean isEmpty() {
		return removed.isEmpty() && changed.isEmpty() && abstractAdded.isEmpty() && added.isEmpty();
	}

	/** Whether a caller compiled against the baseline can break. */
	public boolean isBreaking() {
		return !removed.isEmpty() || !changed.isEmpty() || !abstractAdded.isEmpty();
	}

	/** The three lists, readable in a build log. */
	public String render() {
		StringBuilder out = new StringBuilder();
		if (!removed.isEmpty()) {
			out.append("BREAKING - removed (").append(removed.size()).append("):\n");
			removed.forEach(line -> out.append("  - ").append(line).append('\n'));
		}
		if (!changed.isEmpty()) {
			out.append("BREAKING - changed (").append(changed.size()).append("):\n");
			for (Change change : changed) {
				out.append("  ~ ").append(change.key()).append('\n');
				change.was().forEach(line -> out.append("      was: ").append(line).append('\n'));
				change.now().forEach(line -> out.append("      now: ").append(line).append('\n'));
			}
		}
		if (!abstractAdded.isEmpty()) {
			out.append("BREAKING - abstract method added to a type callers may implement (").append(abstractAdded.size())
				.append("):\n");
			abstractAdded.forEach(line -> out.append("  ! ").append(line).append('\n'));
		}
		if (!added.isEmpty()) {
			out.append("COMPATIBLE - added (").append(added.size()).append("):\n");
			added.forEach(line -> out.append("  + ").append(line).append('\n'));
		}
		return out.toString();
	}
}
