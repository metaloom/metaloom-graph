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
import io.metaloom.graph.core.api.Transaction;
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
 * <h2>Three outcomes, and only one of them is allowed to be silent</h2>
 *
 * <p>
 * This engine implements a documented <em>subset</em>, so some of the corpus is expected not to run. That makes the
 * classification the point of the test rather than an accommodation:
 * </p>
 *
 * <ul>
 * <li><b>Agreed</b> — ran, and both counts match. What the coverage floors below are counted from.
 * <li><b>Refused</b> — the parser rejected it by name, which is the documented contract for anything outside the
 * subset. Acceptable, and reported so the gap is visible rather than absorbed.
 * <li><b>Wrong or broken</b> — ran and disagreed, or failed for any reason other than a named refusal. Always a
 * failure. A wrong count is the defect this whole corpus exists to catch, because nothing about it throws.
 * </ul>
 *
 * <p>
 * The floors are what stop the test degenerating. Without them an engine whose parser refused everything would be
 * perfectly green, which is the failure mode a test like this invites.
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
	 * Datasets that must import completely, with the totals the corpus states.
	 *
	 * <p>
	 * Named rather than discovered, because "every dataset that happens to import" is a floor that falls on its own
	 * when something breaks. The other two are asserted separately, by the reason they cannot be imported.
	 * </p>
	 */
	private static final Map<String, long[]> IMPORTABLE = Map.of(
		"01-movies", new long[] { 29, 52 },
		"02-transit", new long[] { 27, 64 });

	/** Datasets the dump itself puts outside the subset, with the clause that does it. */
	private static final Map<String, String> NOT_IMPORTABLE = Map.of(
		"03-social", "CASE",
		"04-supply-chain", "MERGE");

	private enum Outcome {
		AGREED, REFUSED, WRONG
	}

	private record Checked(Corpus.Query query, Outcome outcome, String detail) {
	}

	// ---------------------------------------------------------------- importing

	@Test
	public void testTheDumpsThisEngineCanRunImportCompletely() throws Exception {
		for (Map.Entry<String, long[]> entry : IMPORTABLE.entrySet()) {
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

	@Test
	public void testTheDumpsItCannotRunAreRefusedByName() throws Exception {
		for (Map.Entry<String, String> entry : NOT_IMPORTABLE.entrySet()) {
			try (GraphStore store = freshStore(entry.getKey())) {
				RuntimeException thrown = null;
				try {
					importInto(store, entry.getKey());
				} catch (RuntimeException e) {
					thrown = e;
				}
				assertTrue(thrown != null, entry.getKey() + " imported unexpectedly — update this test and the spec");
				assertTrue(thrown.getMessage().contains(entry.getValue()),
					entry.getKey() + " must be refused with a message naming " + entry.getValue()
						+ ", got: " + thrown.getMessage());
				// A refusal, not a half-import: the failing statement's transaction rolled back, and the ones before
				// it committed, which is exactly what a script of separate statements means.
				store.verify(VerifyLevel.FULL);
			}
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
			if (!IMPORTABLE.containsKey(entry.getKey())) {
				// Its dump cannot be imported, so its queries have nothing to run against. Counted as refused
				// against the dataset rather than pretended to have been tried.
				for (Corpus.Query query : entry.getValue()) {
					results.add(new Checked(query, Outcome.REFUSED, "the dataset itself is outside the subset"));
				}
				continue;
			}
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

		// The floors. An engine that refused everything would satisfy the assertion above perfectly.
		long agreed = results.stream().filter(c -> c.outcome() == Outcome.AGREED).count();
		assertTrue(agreed >= 12, "only " + agreed + " of " + results.size() + " corpus queries ran and agreed; "
			+ "this floor exists so that a regression which merely stops queries parsing cannot pass");
		for (String dataset : IMPORTABLE.keySet()) {
			long ok = results.stream()
				.filter(c -> c.query().dataset().equals(dataset) && c.outcome() == Outcome.AGREED)
				.count();
			assertTrue(ok >= 5, dataset + " contributed only " + ok + " agreeing queries");
		}
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
