package io.metaloom.graph.server.mcp.tool.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.metaloom.graph.server.mcp.tool.GraphMcpTool;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * What this build's Cypher can and cannot do.
 *
 * <p>
 * <b>The highest-value tool here, and the least obvious one.</b> A model that has seen Neo4j will write
 * {@code OPTIONAL MATCH} within two turns. This engine refuses it at parse time, which is the right behaviour and
 * still leaves the model with an error it cannot reason its way out of — so it tries a synonym, and then another. One
 * call to this replaces that whole loop.
 * </p>
 *
 * <p>
 * The text is a packaged resource rather than a string constant so that it stays readable and diffable, and
 * {@code CypherGuideToolTest} checks it against {@code spec/CYPHER.md} so the two cannot drift into disagreeing about
 * what is supported.
 * </p>
 */
public final class CypherGuideTool implements GraphMcpTool {

	static final String RESOURCE = "/mcp/cypher-guide.md";

	private final String guide = load();

	@Override
	public McpToolDescriptor descriptor() {
		return new McpToolDescriptor("graph_cypher_guide",
			"Describes the openCypher SUBSET this engine implements: which clauses, patterns, functions and "
				+ "operators exist, what is deliberately not built (OPTIONAL MATCH among it), and the null and "
				+ "performance rules. Call this before writing any non-trivial query.",
			McpToolDescriptor.buildInputSchema(List.of()), false);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		// No store access and no blocking: it is a string that was read once at construction.
		return Future.succeededFuture(GraphMcpToolResults.text(guide));
	}

	static String load() {
		try (InputStream stream = CypherGuideTool.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) {
				throw new IllegalStateException(RESOURCE + " is missing from the jar; it is a packaged resource and "
					+ "the tool is useless without it");
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
