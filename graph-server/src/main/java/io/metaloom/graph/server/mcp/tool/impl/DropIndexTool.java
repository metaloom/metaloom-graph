package io.metaloom.graph.server.mcp.tool.impl;

import java.util.List;
import java.util.Set;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor.McpToolParam;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Drops a composite index.
 *
 * <p>
 * A write tool, and the only destructive one here — but it destroys an <em>index</em>, which can be rebuilt from the
 * data. Dropping a database is deliberately not offered at all.
 * </p>
 */
public final class DropIndexTool extends StoreTool {

	public DropIndexTool(Vertx vertx, GraphDatabases databases, String defaultDatabase) {
		super(vertx, databases, defaultDatabase);
	}

	@Override
	public McpToolDescriptor descriptor() {
		return new McpToolDescriptor("graph_drop_index",
			"Drop the index on a (label, property key) pair. The data is untouched; only the index goes.",
			McpToolDescriptor.buildInputSchema(List.of(
				new McpToolParam("label", "string", "The node label.", true),
				new McpToolParam("key", "string", "The property key.", true),
				new McpToolParam("database", "string", DATABASE_DESCRIPTION, false))),
			true, Set.of(Privilege.DROP_INDEX));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		String label = arguments.getString("label");
		String key = arguments.getString("key");
		if (label == null || label.isBlank() || key == null || key.isBlank()) {
			return Future.succeededFuture(GraphMcpToolResults.error("Both 'label' and 'key' are required."));
		}
		String database = databaseName(arguments);
		return vertx.executeBlocking(() -> {
			GraphStore store = store(database);
			if (!store.hasIndex(label, key)) {
				return GraphMcpToolResults.error("No index on :" + label + "." + key);
			}
			store.dropIndex(label, key);
			return GraphMcpToolResults.json(new JsonObject()
				.put("database", database).put("label", label).put("key", key).put("dropped", true));
		}, false);
	}
}
