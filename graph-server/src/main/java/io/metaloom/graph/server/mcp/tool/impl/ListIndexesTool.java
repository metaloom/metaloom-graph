package io.metaloom.graph.server.mcp.tool.impl;

import java.util.List;

import io.metaloom.graph.core.api.CompositeIndex;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor.McpToolParam;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The declared composite indexes.
 *
 * <p>
 * Worth exposing because it is what tells an agent whether a {@code WHERE n.key = $v} will use an index or scan the
 * whole store — the difference between a query that answers and one that hits the timeout.
 * </p>
 */
public final class ListIndexesTool extends StoreTool {

	public ListIndexesTool(Vertx vertx, GraphDatabases databases, String defaultDatabase) {
		super(vertx, databases, defaultDatabase);
	}

	@Override
	public McpToolDescriptor descriptor() {
		return new McpToolDescriptor("graph_list_indexes",
			"List the declared (label, property) indexes and whether each is unique. A WHERE on an indexed pair is "
				+ "a lookup; on an unindexed one it is a full scan.",
			McpToolDescriptor.buildInputSchema(List.of(
				new McpToolParam("database", "string", DATABASE_DESCRIPTION, false))),
			false);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		String database = databaseName(arguments);
		return vertx.executeBlocking(() -> {
			JsonArray indexes = new JsonArray();
			for (CompositeIndex index : store(database).compositeIndexes()) {
				indexes.add(new JsonObject()
					.put("label", index.label())
					.put("key", index.key())
					.put("unique", index.unique()));
			}
			return GraphMcpToolResults.json(new JsonObject().put("database", database).put("indexes", indexes));
		}, false);
	}
}
