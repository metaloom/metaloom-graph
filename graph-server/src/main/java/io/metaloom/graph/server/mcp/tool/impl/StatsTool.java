package io.metaloom.graph.server.mcp.tool.impl;

import java.util.List;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.SnapshotStats;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.nativelib.NativeCapabilities;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor.McpToolParam;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/** Size and health of one database, so an agent can tell a small graph from one it should not scan. */
public final class StatsTool extends StoreTool {

	public StatsTool(Vertx vertx, GraphDatabases databases, String defaultDatabase) {
		super(vertx, databases, defaultDatabase);
	}

	@Override
	public McpToolDescriptor descriptor() {
		return new McpToolDescriptor("graph_stats",
			"Size and health of a database: node and relationship counts, storage bytes, open read views and "
				+ "whether the native layer loaded. Use it to decide whether a full scan is affordable.",
			McpToolDescriptor.buildInputSchema(List.of(
				new McpToolParam("database", "string", DATABASE_DESCRIPTION, false))),
			false);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		String database = databaseName(arguments);
		return vertx.executeBlocking(() -> {
			GraphStore store = store(database);
			StoreStats stats = store.stats();
			SnapshotStats snapshots = store.snapshotStats();
			return GraphMcpToolResults.json(new JsonObject()
				.put("database", database)
				.put("nodes", stats.nodeCount())
				.put("relationships", stats.relationshipCount())
				.put("labels", stats.labelCount())
				.put("propertyKeys", stats.keyCount())
				.put("storageBytes", stats.storageBytes())
				.put("segmentFiles", stats.segmentFileCount())
				.put("openViews", snapshots.openViews())
				.put("retainedBytes", snapshots.retainedBytes())
				.put("nativeLayer", NativeCapabilities.status()));
		}, false);
	}
}
