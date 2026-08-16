package io.metaloom.graph.server.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Vertx;

/**
 * Keeps {@code spec/METRICS.md} and the actual scrape from drifting apart, <b>in both directions</b>.
 *
 * <p>
 * A metrics catalogue nobody checks is worse than none, because it is trusted: an operator writes an alert rule
 * against a meter name from the documentation, the rule never fires, and nobody finds out until the incident it was
 * supposed to catch. So §3 of that file must appear in a real scrape, and §5 — "declared but not recorded" — must not.
 * </p>
 *
 * <p>
 * The scrape comes from the production {@link StoreMetricsBinder} bound to a real store, not from meters this test
 * registers. Registering them here would prove that Micrometer works and nothing about whether the binder binds what
 * the document claims.
 * </p>
 */
@Tag("unit")
public class MetricsCatalogScrapeTest {

	/** graph-server is one directory below the root, unlike the three-deep modules in the Loom repository. */
	private static final Path SPEC = Path.of("..", "spec", "METRICS.md");

	private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

	/** Micrometer's Prometheus renderer appends these; the names in the document are the names in the code. */
	private static final List<String> SUFFIXES = List.of("", "_total", "_seconds", "_bytes", "_seconds_max",
		"_max", "_count", "_sum", "_seconds_count", "_seconds_sum", "_seconds_bucket", "_bucket", "_info");

	private static String scrape;

	private static Set<String> scrapedNames;

	private static Path root;

	@BeforeAll
	public static void scrapeARealRegistry() throws IOException {
		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		GraphMetrics metrics = new MicrometerGraphMetrics(registry);
		Vertx vertx = Vertx.vertx();
		root = Files.createTempDirectory("metrics-catalogue");
		try (GraphDatabases databases = GraphDatabases.open(root)) {
			databases.create("catalogue");
			// Not closed here: GraphDatabases owns the handle it hands out and closes it itself. Closing it as well
			// would unmap the segments the gauges are about to read.
			GraphStore store = databases.database("catalogue");
			// A store with something in it, so the size gauges report a number rather than zero and the WAL counters
			// have had a reason to move.
			store.createNode("Airport", Properties.of().set("code", "BON"));
			MetricsCatalogExercise.exerciseEverything(metrics, vertx, databases, "catalogue");
			scrape = registry.scrape();
		} finally {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}
		scrapedNames = typeNamesIn(scrape);
	}

	@AfterAll
	public static void removeTheStore() throws IOException {
		if (root != null && Files.exists(root)) {
			try (var walk = Files.walk(root)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException ignored) {
						// A leftover temp directory is not worth failing a passing test over.
					}
				});
			}
		}
	}

	@Test
	public void testEveryMeterDocumentedAsLiveIsInTheScrape() {
		List<String> documented = documentedNames("## 3.", "## 4.");
		// The guard against the guard: if the table parse breaks, `documented` goes empty and every assertion below
		// passes vacuously. This is what turns that into a failure.
		assertTrue(documented.size() > 15,
			"only " + documented.size() + " meters parsed out of METRICS.md section 3 — the table format changed");

		List<String> missing = documented.stream().filter(name -> !isInScrape(name)).toList();
		assertTrue(missing.isEmpty(), "METRICS.md section 3 documents these as live, and a real scrape of a real "
			+ "store does not contain them: " + missing + "\nEither the meter was removed and the document was not "
			+ "updated, or it was never bound. Scraped graph_ names were: " + graphNames());
	}

	@Test
	public void testNothingDocumentedAsDeadIsInTheScrape() {
		List<String> dead = documentedNames("## 5.", "## 6.");
		List<String> alive = dead.stream().filter(MetricsCatalogScrapeTest::isInScrape).toList();
		assertTrue(alive.isEmpty(), "METRICS.md section 5 says these are not recorded, but they are in the scrape: "
			+ alive + "\nA meter that works must be documented as live, or an operator will never use it.");
	}

	@Test
	public void testTheLabelsAreTheOnesTheDocumentPromises() {
		// Spelled out rather than parsed, because a label set is what an alert rule actually selects on. METRICS.md
		// section 6 writes rules against exactly these.
		assertTrue(scrape.contains("frontend=\"http\""), "queries are tagged by front end");
		assertTrue(scrape.contains("outcome=\"ok\""), "and by outcome");
		assertTrue(scrape.contains("database=\"catalogue\""), "store meters are tagged by database");
		assertTrue(scrape.contains("kind=\"node\""),
			"per-kind read counts keep their kind tag; summing them would hide what ReadBudgetTest gates");
		assertTrue(scrape.contains("reason=\"readonly\""), "a refusal carries why");
	}

	@Test
	public void testTheStalenessOfTheCachedTierIsItselfReported() {
		// The cached tier is only defensible because its age is visible. If this gauge ever stops being emitted, the
		// node count silently becomes a number nobody can date.
		assertTrue(isInScrape("graph_store_stats_age_seconds"),
			"the cached tier must publish its own age, or its staleness is invisible");
	}

	/** Every {@code # TYPE <name> <type>} name in the scrape. */
	private static Set<String> typeNamesIn(String text) {
		Set<String> names = new LinkedHashSet<>();
		for (String line : text.split("\n")) {
			if (line.startsWith("# TYPE ")) {
				String[] parts = line.split(" ");
				if (parts.length >= 3) {
					names.add(parts[2]);
				}
			}
		}
		return names;
	}

	/**
	 * Whether a documented name is present, allowing for the suffix the renderer adds.
	 *
	 * <p>
	 * Compared against whole names rather than by {@code contains}, so that {@code graph_queries} is not considered
	 * present merely because {@code graph_queries_slow_total} is.
	 * </p>
	 */
	private static boolean isInScrape(String name) {
		return SUFFIXES.stream().anyMatch(suffix -> scrapedNames.contains(name + suffix));
	}

	private static List<String> graphNames() {
		return scrapedNames.stream().filter(name -> name.startsWith("graph_")).sorted().toList();
	}

	/**
	 * The {@code graph_} names in the first cell of every table row between two headings.
	 *
	 * <p>
	 * First cell only: later cells hold call sites and label names, which are also in code spans and are not meters.
	 * </p>
	 */
	private static List<String> documentedNames(String fromHeading, String toHeading) {
		String text;
		try {
			text = Files.readString(SPEC);
		} catch (IOException e) {
			throw new AssertionError("Could not read " + SPEC.toAbsolutePath() + "; this test runs from the module "
				+ "directory and the spec lives one level up", e);
		}
		int from = text.indexOf(fromHeading);
		int to = text.indexOf(toHeading, from + 1);
		if (from < 0 || to < 0) {
			throw new AssertionError("METRICS.md no longer has both '" + fromHeading + "' and '" + toHeading
				+ "'; this test locates its tables by those headings");
		}
		List<String> names = new ArrayList<>();
		for (String line : text.substring(from, to).split("\n")) {
			if (!line.startsWith("|")) {
				continue;
			}
			String firstCell = line.split("\\|")[1];
			Matcher span = CODE_SPAN.matcher(firstCell);
			if (span.find() && span.group(1).startsWith("graph_")) {
				names.add(span.group(1));
			}
		}
		return names;
	}

	@Test
	public void testTheDocumentIsWhereTheTestThinksItIs() {
		assertFalse(documentedNames("## 3.", "## 4.").isEmpty(),
			"section 3 parsed to nothing, so every other assertion here would pass without checking anything");
	}
}
