package io.metaloom.graph.server.mcp.tool.impl;

import java.util.List;
import java.util.Set;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor.McpToolParam;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/** Declares a composite index. A write tool: absent from tools/list unless the server allows writes. */
public final class CreateIndexTool extends StoreTool {

	public CreateIndexTool(Vertx vertx, GraphDatabases databases, String defaultDatabase) {
		super(vertx, databases, defaultDatabase);
	}

	@Override
	public McpToolDescriptor descriptor() {
		return new McpToolDescriptor("graph_create_index",
			"Declare an index on a (label, property key) pair, optionally unique. Building it scans the store, so "
				+ "it is not free on a large graph.",
			McpToolDescriptor.buildInputSchema(List.of(
				new McpToolParam("label", "string", "The node label, for example 'Airport'.", true),
				new McpToolParam("key", "string", "The property key, for example 'code'.", true),
				new McpToolParam("unique", "boolean", "Also enforce uniqueness. Fails if the data already "
					+ "violates it.", false),
				new McpToolParam("database", "string", DATABASE_DESCRIPTION, false))),
			true, Set.of(Privilege.CREATE_INDEX));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		String label = arguments.getString("label");
		String key = arguments.getString("key");
		if (label == null || label.isBlank() || key == null || key.isBlank()) {
			return Future.succeededFuture(GraphMcpToolResults.error("Both 'label' and 'key' are required."));
		}
		boolean unique = arguments.getBoolean("unique", false);
		String database = databaseName(arguments);
		return vertx.executeBlocking(() -> {
			// A label-scoped unique constraint IS a declared composite index plus a flag, so the unique branch does
			// not also need createIndex — calling both would scan the store twice for one declaration.
			if (unique) {
				store(database).createUniqueConstraint(label, key);
			} else {
				store(database).createIndex(label, key);
			}
			return GraphMcpToolResults.json(new JsonObject()
				.put("database", database).put("label", label).put("key", key)
				.put("unique", unique).put("created", true));
		}, false);
	}
}
