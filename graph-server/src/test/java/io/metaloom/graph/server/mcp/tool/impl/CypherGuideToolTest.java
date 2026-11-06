package io.metaloom.graph.server.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;


/**
 * Keeps the guide the model reads and the grammar the parser implements from disagreeing.
 *
 * <p>
 * The failure this prevents is quiet and expensive: the guide claims something works, the model writes it, the parser
 * refuses it, and the model has been told by the server itself that it should have worked. That is worse than having
 * no guide at all.
 * </p>
 */
@Tag("unit")
public class CypherGuideToolTest {

	private static final Path SPEC = Path.of("..", "spec", "CYPHER.md");

	private final String guide = CypherGuideTool.load();

	@Test
	public void testEverythingTheSpecCallsUnsupportedIsListedAsUnsupported() throws IOException {
		String spec = Files.readString(SPEC);
		// Taken from CYPHER.md section 5.5's table. Named individually rather than parsed, because the table's prose
		// column is not machine-readable and a half-working parse would be worse than an explicit list.
		// LOAD CSV left this list when the clause was built. It is still refused over MCP — see QueryTool — but that
		// is a property of this port rather than of the subset, so it does not belong in the "not implemented" section
		// the guide and CYPHER.md section 5.5 share.
		// OPTIONAL MATCH left it when it was built; the guide now shows it as a clause.
		List<String> unsupported = List.of("FOREACH", "IN TRANSACTIONS", "OPTIONAL CALL");
		for (String construct : unsupported) {
			assertTrue(spec.contains(construct),
				construct + " is no longer named in CYPHER.md; if it became supported, update the guide too");
			assertTrue(guide.contains(construct),
				"the guide has to tell the model that " + construct + " is not implemented, or it will write it");
		}
	}

	@Test
	public void testTheGuideDoesNotPromiseWhatTheParserRefuses() {
		int notImplemented = guide.indexOf("## Not implemented");
		assertTrue(notImplemented > 0, "the guide needs its 'Not implemented' section");
		String supported = guide.substring(0, notImplemented);
		// FOREACH is the one a model reaches for next, so it must not appear anywhere it could be read as available.
		// As a clause, that is: the introduction quotes the refusal message, which names it.
		assertFalse(supported.contains("FOREACH ("),
			"FOREACH appears above the 'Not implemented' heading, where it reads as supported");
		assertTrue(supported.contains("OPTIONAL MATCH"), "OPTIONAL MATCH is built and should be shown as a clause");
	}

	@Test
	public void testTheGuideNamesTheOneProcedureThatExists() {
		// CALL is built and has exactly one procedure. A guide that omitted it would hide the only way to run a
		// vector search from the model.
		assertTrue(guide.contains("vector.query"), "the guide should name the one procedure this build has");
	}

	@Test
	public void testTheGuidePointsAtTheOtherTools() {
		// Its whole value is being the first call in a chain. If it does not send the model on to graph_schema and
		// graph_list_indexes, the model guesses label names and scans unindexed properties.
		assertTrue(guide.contains("graph_schema"));
		assertTrue(guide.contains("graph_list_indexes"));
	}
}
