package io.metaloom.graph.server.mcp.tool.impl;

import java.util.List;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.metaloom.graph.server.mcp.tool.GraphMcpTool;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** The databases under the root. Read-only: creating and dropping them is not something a model gets to do. */
public final class ListDatabasesTool implements GraphMcpTool {

	private final Vertx vertx;

	private final GraphDatabases databases;

	public ListDatabasesTool(Vertx vertx, GraphDatabases databases) {
		this.vertx = vertx;
		this.databases = databases;
	}

	@Override
	public McpToolDescriptor descriptor() {
		return new McpToolDescriptor("graph_list_databases",
			"List the databases this server holds. Every other tool takes an optional 'database' argument naming "
				+ "one of these.",
			McpToolDescriptor.buildInputSchema(List.of()), false);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		// Blocking: names() lists a directory and probes each entry for a store layout.
		return vertx.executeBlocking(() -> GraphMcpToolResults.json(
			new JsonObject().put("databases", new JsonArray(databases.names()))), false);
	}
}
