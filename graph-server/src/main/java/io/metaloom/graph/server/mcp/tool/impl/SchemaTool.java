package io.metaloom.graph.server.mcp.tool.impl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.server.SchemaScanner;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor.McpToolParam;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Labels, relationship types and property keys, with counts.
 *
 * <p>
 * The first tool an agent should call: without it a model guesses label names, and a guessed label produces an empty
 * result rather than an error, which is the least debuggable outcome available.
 * </p>
 */
public final class SchemaTool extends StoreTool {

	/** One scanner per database. The scan is O(nodes + edges) and its whole value is the cache in front of it. */
	private final Map<String, SchemaScanner> scanners = new ConcurrentHashMap<>();

	public SchemaTool(Vertx vertx, GraphDatabases databases, String defaultDatabase) {
		super(vertx, databases, defaultDatabase);
	}

	@Override
	public McpToolDescriptor descriptor() {
		return new McpToolDescriptor("graph_schema",
			"List the labels, relationship types and property keys in the graph, with counts. Call this before "
				+ "writing a query: a guessed label name returns an empty result rather than an error.",
			McpToolDescriptor.buildInputSchema(List.of(
				new McpToolParam("database", "string", DATABASE_DESCRIPTION, false))),
			false);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		String database = databaseName(arguments);
		return vertx.executeBlocking(() -> {
			SchemaScanner scanner = scanners.computeIfAbsent(database, name -> new SchemaScanner(store(name)));
			return GraphMcpToolResults.json(scanner.schema(false));
		}, false);
	}
}
