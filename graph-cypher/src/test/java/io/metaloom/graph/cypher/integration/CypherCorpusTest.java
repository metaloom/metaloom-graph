package io.metaloom.graph.cypher.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.CypherResult;

/**
 * The four graph dumps in {@code test-data/}, imported and queried, against counts derived somewhere else.
 *
 * <p>
 * <b>Why this is worth having on top of the tests that already exist.</b> Every other test in this module was written
 * by the same hand as the code it exercises, over a graph chosen to exercise it. This corpus was not: the dumps and the
 * thirty expected counts were derived from the generation rules and proven against a real Neo4j before this engine saw
 * them. So it measures the one thing an in-house test cannot — whether a query written for openCypher, against data
 * written for openCypher, gets the openCypher answer here.
 * </p>
 *
 * <h2>Three outcomes, and none of them is allowed to be silent</h2>
 *
 * <p>
 * This engine implements a documented <em>subset</em>, so the classification is the point of the test rather than an
 * accommodation:
 * </p>
 *
 * <ul>
 * <li><b>Agreed</b> — ran, and both counts match.
 * <li><b>Refused</b> — the parser rejected it by name, which is the documented contract for anything outside the
 * subset. Reported so the gap is visible rather than absorbed.
 * <li><b>Wrong or broken</b> — ran and disagreed, or failed for any reason other than a named refusal. Always a
 * failure. A wrong count is the defect this whole corpus exists to catch, because nothing about it throws.
 * </ul>
 *
 * <p>
 * <b>All 29 now agree, so the bar is that and not a threshold.</b> It was two counts while parts of the corpus were
 * out of reach; a threshold could only hide a regression now. The refused outcome stays in place because the subset is
 * still a subset — a query added to the catalogue tomorrow may well fall outside it, and it should be reported rather
 * than counted as a failure of this engine to parse valid Cypher.
 * </p>
 *
 * @see <a href="../../../../../../../../../test-data/README.md">test-data/README.md</a>
 * @see <a href="../../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
@Tag("unit")
public class CypherCorpusTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/**
	 * Every dataset, with the node and relationship totals its own README states.
	 *
	 * <p>
	 * Named here rather than discovered from the directory, so that the counts are a second source rather than
	 * whatever the import happened to produce — and {@link #testEveryDatasetInTheCatalogueIsCovered} then asserts this
	 * list has not fallen behind the catalogue.
	 * </p>
	 */
	private static final Map<String, long[]> DATASETS = Map.of(
		"01-movies", new long[] { 29, 52 },
		"02-transit", new long[] { 27, 64 },
		"03-social", new long[] { 188, 570 },
		"04-supply-chain", new long[] { 136, 352 });

	private enum Outcome {
		AGREED, REFUSED, WRONG
	}

	private record Checked(Corpus.Query query, Outcome outcome, String detail) {
	}

	// ---------------------------------------------------------------- importing

	@Test
	public void testEveryDumpImportsCompletely() throws Exception {
		for (Map.Entry<String, long[]> entry : DATASETS.entrySet()) {
			try (GraphStore store = freshStore(entry.getKey())) {
				int statements = importInto(store, entry.getKey());
				assertTrue(statements > 0, entry.getKey() + " produced no statements; the splitter is broken");

				assertEquals(entry.getValue()[0], countNodes(store), entry.getKey() + ": node count after import");
				assertEquals(entry.getValue()[1], countRelationships(store),
					entry.getKey() + ": relationship count after import");

				// The corpus says what the graph is; the invariant checkers say whether the store agrees with itself
				// about it — bidirectional edges, degree counters, no orphan blocks, no dangling references.
				store.verify(VerifyLevel.FULL);
			}
		}
	}

	/**
	 * The catalogue against the list above, so a fifth dataset cannot arrive unnoticed.
	 *
	 * <p>
	 * Without this, adding a dump and its queries to {@code test-data/} would leave {@link #DATASETS} behind and the
	 * import test would quietly cover three quarters of the corpus while reporting success.
	 * </p>
	 */
	@Test
	public void testEveryDatasetInTheCatalogueIsCovered() {
		assertEquals(Corpus.datasets(), DATASETS.keySet(),
			"the catalogue and this test's expected totals name different datasets");
	}

	/**
	 * The supply-chain dump run twice, which is what it was written to survive.
	 *
	 * <p>
	 * It is {@code MERGE}-only and deliberately idempotent: node identity is the business key in the MERGE pattern and
	 * everything else is written with {@code ON CREATE SET}, so a second run must not change a single count or rewrite
	 * a single property. That is a much sharper test of MERGE than any single run — a MERGE that quietly created
	 * duplicates would import perfectly the first time and double the graph the second.
	 * </p>
	 */
	@Test
	public void testTheMergeOnlyDumpIsIdempotent() throws Exception {
		try (GraphStore store = freshStore("04-supply-chain")) {
			importInto(store, "04-supply-chain");
			long nodes = countNodes(store);
			long relationships = countRelationships(store);
			Object reference = Cypher.query(store,
				"MATCH (s:Supplier {supplierId: 3}) RETURN s.name AS name").row(0).get("name");

			importInto(store, "04-supply-chain");

			assertEquals(nodes, countNodes(store), "a second run of a MERGE-only dump created nodes");
			assertEquals(relationships, countRelationships(store),
				"a second run of a MERGE-only dump created relationships");
			assertEquals(reference, Cypher.query(store,
				"MATCH (s:Supplier {supplierId: 3}) RETURN s.name AS name").row(0).get("name"),
				"ON CREATE SET ran on a node that was matched rather than created");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAnImportedGraphSurvivesAReopen() throws Exception {
		long nodes;
		long relationships;
		try (GraphStore store = freshStore("02-transit")) {
			importInto(store, "02-transit");
			nodes = countNodes(store);
			relationships = countRelationships(store);
		}
		try (GraphStore reopened = freshStore("02-transit")) {
			assertEquals(nodes, countNodes(reopened));
			assertEquals(relationships, countRelationships(reopened));
			reopened.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- querying

	@Test
	public void testEveryQueryEitherAgreesOrIsRefusedByName() throws Exception {
		List<Checked> results = new ArrayList<>();
		Map<String, List<Corpus.Query>> byDataset = new LinkedHashMap<>();
		for (Corpus.Query query : Corpus.queries()) {
			byDataset.computeIfAbsent(query.dataset(), ignored -> new ArrayList<>()).add(query);
		}

		for (Map.Entry<String, List<Corpus.Query>> entry : byDataset.entrySet()) {
			try (GraphStore store = freshStore(entry.getKey())) {
				importInto(store, entry.getKey());
				for (Corpus.Query query : entry.getValue()) {
					results.add(check(store, query));
				}
			}
		}

		report(results);

		List<Checked> wrong = results.stream().filter(c -> c.outcome() == Outcome.WRONG).toList();
		assertTrue(wrong.isEmpty(), wrong.size() + " quer" + (wrong.size() == 1 ? "y" : "ies")
			+ " ran and gave the wrong answer:\n" + describe(wrong));

		// The bar, and an engine that refused everything would satisfy the assertion above perfectly. It was a pair of
		// counts while parts of the corpus were out of reach; all 29 agree now, so a threshold could only hide a
		// regression. A refusal here is a feature this build had and lost.
		List<Checked> shortfall = results.stream().filter(c -> c.outcome() != Outcome.AGREED).toList();
		assertTrue(shortfall.isEmpty(), shortfall.size() + " quer" + (shortfall.size() == 1 ? "y" : "ies")
			+ " stopped agreeing:\n" + describe(shortfall));
		assertEquals(29, results.size(), "the catalogue changed size; check the count in spec/CYPHER.md §9.2");
	}

	/**
	 * Runs one catalogue query and classifies it.
	 *
	 * <p>
	 * Every query in the corpus returns exactly two columns, {@code nodes} and {@code rels}, which is what lets one
	 * assertion cover thirty different questions.
	 * </p>
	 */
	private static Checked check(GraphStore store, Corpus.Query query) {
		CypherResult result;
		try {
			result = Cypher.query(store, query.cypher());
		} catch (RuntimeException e) {
			String message = String.valueOf(e.getMessage());
			// A refusal is documented behaviour; anything else is this engine breaking on valid openCypher.
			boolean named = e instanceof io.metaloom.graph.cypher.parse.CypherSyntaxException
				&& message.contains("not supported");
			return new Checked(query, named ? Outcome.REFUSED : Outcome.WRONG,
				e.getClass().getSimpleName() + ": " + reason(message));
		}

		if (result.isEmpty()) {
			// Not the same as zero: the corpus queries all aggregate, so an empty result means the query matched
			// nothing at all where it should have produced one row of counts.
			return new Checked(query, Outcome.WRONG, "no rows; expected one row of counts");
		}
		Object nodes = result.row(0).get("nodes");
		Object relationships = result.row(0).get("rels");
		if (!(nodes instanceof Long) || !(relationships instanceof Long)) {
			return new Checked(query, Outcome.WRONG, "columns were " + result.columns() + " = " + nodes + ", "
				+ relationships);
		}
		if ((Long) nodes == query.nodes() && (Long) relationships == query.relationships()) {
			return new Checked(query, Outcome.AGREED, null);
		}
		return new Checked(query, Outcome.WRONG,
			"expected " + query.nodes() + " nodes / " + query.relationships() + " rels, got "
				+ nodes + " / " + relationships);
	}

	// ---------------------------------------------------------------- plumbing

	/**
	 * Runs a dump, one statement per transaction.
	 *
	 * <p>
	 * Per statement rather than one transaction for the whole file, because that is what a script of statements
	 * separated by semicolons means — and because it is what makes the refusal case above informative: the statements
	 * before the unsupported one are committed, so the store is left in a state the invariant checkers can be run
	 * against rather than one giant rollback.
	 * </p>
	 */
	private static int importInto(GraphStore store, String dataset) {
		int ran = 0;
		for (String statement : Corpus.statements(dataset)) {
			Cypher.query(store, statement);
			ran++;
		}
		return ran;
	}

	/**
	 * A store of its own per dataset.
	 *
	 * <p>
	 * {@link TempStore#open()} hands out one directory per <em>test method</em>, so opening it once per dataset
	 * imported all of them on top of each other — which the corpus caught immediately as 56 nodes where it wanted 27,
	 * and which is exactly the kind of arithmetic a fixture with derived counts is good at exposing.
	 * </p>
	 */
	private GraphStore freshStore(String dataset) throws java.io.IOException {
		return GraphStore.open(stores.path().resolve(dataset), TempStore.options());
	}

	private static long countNodes(GraphStore store) {
		long[] count = { 0 };
		store.forEachNode(id -> count[0]++);
		return count[0];
	}

	/** Counted from the OUT side only, so an edge is counted once however many endpoints it touches. */
	private static long countRelationships(GraphStore store) {
		long[] count = { 0 };
		store.forEachNode(id -> count[0] += store.neighbours(id, io.metaloom.graph.core.api.Direction.OUT).size());
		return count[0];
	}

	/**
	 * The reason out of a rendered syntax error.
	 *
	 * <p>
	 * The <em>last</em> line, not the first: a rendered message is the source line, then a caret line, then the reason
	 * and its position. Taking the first line would put the whole query in the report where the explanation belongs.
	 * </p>
	 */
	private static String reason(String message) {
		String[] lines = message.split("\n");
		for (int i = lines.length - 1; i >= 0; i--) {
			if (!lines[i].isBlank()) {
				return lines[i].trim();
			}
		}
		return message;
	}

	private static String describe(List<Checked> checked) {
		StringBuilder text = new StringBuilder();
		for (Checked c : checked) {
			text.append("  ").append(c.query().dataset()).append(' ').append(c.query().id()).append("  ")
				.append(c.query().description()).append("\n      ").append(c.detail()).append('\n');
		}
		return text.toString();
	}

	/** Printed rather than only asserted, because the gap between the subset and openCypher is the useful output. */
	private static void report(List<Checked> results) {
		long agreed = results.stream().filter(c -> c.outcome() == Outcome.AGREED).count();
		long refused = results.stream().filter(c -> c.outcome() == Outcome.REFUSED).count();
		long wrong = results.stream().filter(c -> c.outcome() == Outcome.WRONG).count();
		StringBuilder text = new StringBuilder(String.format(
			"%ntest-data corpus: %d queries — %d agreed, %d refused as outside the subset, %d wrong%n",
			results.size(), agreed, refused, wrong));
		for (Checked c : results) {
			if (c.outcome() != Outcome.AGREED) {
				text.append(String.format("  %-8s %-6s %-9s %s%n", c.query().dataset(), c.query().id(),
					c.outcome(), c.detail()));
			}
		}
		System.out.println(text);
	}
}
