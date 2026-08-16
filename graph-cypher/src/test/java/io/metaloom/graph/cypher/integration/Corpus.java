package io.metaloom.graph.cypher.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code test-data/}: the four graph dumps and the catalogue of queries over them.
 *
 * <p>
 * The corpus was written against a real Neo4j and its expected counts were derived from the generation rules rather
 * than read back out of a database — which is what makes it an oracle here rather than a second opinion. Nothing in it
 * is specific to this engine, so what it measures is how much of openCypher this engine actually runs.
 * </p>
 */
final class Corpus {

	private Corpus() {
	}

	/** One row of {@code queries.csv}. */
	record Query(String dataset, String id, String description, String cypher, long nodes, long relationships) {
	}

	/**
	 * The corpus directory, found by walking up from wherever the test was started.
	 *
	 * <p>
	 * Surefire runs with the module directory as the working directory and an IDE often does not, so neither
	 * {@code ../test-data} nor an absolute path is reliable. Walking up for a known directory name is.
	 * </p>
	 */
	static Path root() {
		Path at = Path.of("").toAbsolutePath();
		while (at != null) {
			Path candidate = at.resolve("test-data");
			if (Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("queries.csv"))) {
				return candidate;
			}
			at = at.getParent();
		}
		throw new IllegalStateException("No test-data/queries.csv above " + Path.of("").toAbsolutePath());
	}

	static List<Query> queries() {
		Path file = root().resolve("queries.csv");
		List<Query> queries = new ArrayList<>();
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 1; i < lines.size(); i++) {
				if (lines.get(i).isBlank()) {
					continue;
				}
				List<String> cells = splitCsv(lines.get(i));
				if (cells.size() < 6) {
					throw new IllegalStateException("Row " + (i + 1) + " of " + file + " has " + cells.size()
						+ " cells, expected 6: " + lines.get(i));
				}
				queries.add(new Query(cells.get(0), cells.get(1), cells.get(2), cells.get(3),
					Long.parseLong(cells.get(4).trim()), Long.parseLong(cells.get(5).trim())));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return queries;
	}

	/** The dataset names the catalogue refers to, in the order they first appear. */
	static Set<String> datasets() {
		Set<String> names = new LinkedHashSet<>();
		for (Query query : queries()) {
			names.add(query.dataset());
		}
		return names;
	}

	static List<String> statements(String dataset) {
		Path file = root().resolve("datasets").resolve(dataset).resolve("data.cypher");
		try {
			return split(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---------------------------------------------------------------- parsing

	/**
	 * Splits a script into statements on semicolons that are not inside a comment or a quoted run.
	 *
	 * <p>
	 * A naive {@code split(";")} is wrong on this corpus and wrong in a way that would look like an engine defect: the
	 * dumps carry semicolons inside string literals and inside {@code //} comment lines, and a statement cut in half
	 * fails to parse with a message about the fragment rather than about the split.
	 * </p>
	 */
	static List<String> split(String script) {
		List<String> statements = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int i = 0;
		while (i < script.length()) {
			char c = script.charAt(i);

			if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '/') {
				while (i < script.length() && script.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
				int end = script.indexOf("*/", i + 2);
				i = end < 0 ? script.length() : end + 2;
				continue;
			}
			if (c == '\'' || c == '"' || c == '`') {
				int close = closingQuote(script, i);
				current.append(script, i, close);
				i = close;
				continue;
			}
			if (c == ';') {
				add(statements, current);
				i++;
				continue;
			}
			current.append(c);
			i++;
		}
		add(statements, current);
		return statements;
	}

	/** @return the index just past the closing quote, or the end of the script when it never closes */
	private static int closingQuote(String script, int start) {
		char quote = script.charAt(start);
		int i = start + 1;
		while (i < script.length()) {
			char c = script.charAt(i);
			if (c == '\\' && quote != '`') {
				i += 2;
				continue;
			}
			if (c == quote) {
				return i + 1;
			}
			i++;
		}
		return script.length();
	}

	private static void add(List<String> statements, StringBuilder current) {
		String statement = current.toString().trim();
		if (!statement.isEmpty()) {
			statements.add(statement);
		}
		current.setLength(0);
	}

	/** RFC 4180 enough for this file: quoted fields, commas inside them, and {@code ""} for a literal quote. */
	static List<String> splitCsv(String line) {
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted) {
				if (c == '"') {
					if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
						cell.append('"');
						i++;
					} else {
						quoted = false;
					}
				} else {
					cell.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				cells.add(cell.toString());
				cell.setLength(0);
			} else {
				cell.append(c);
			}
		}
		cells.add(cell.toString());
		return cells;
	}
}
