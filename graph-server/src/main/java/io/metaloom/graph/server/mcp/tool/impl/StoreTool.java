package io.metaloom.graph.server.mcp.tool.impl;

import java.io.IOException;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.server.mcp.tool.GraphMcpTool;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/** Shared plumbing for the tools that touch a store: resolving the database, and getting off the event loop. */
abstract class StoreTool implements GraphMcpTool {

	final Vertx vertx;

	final GraphDatabases databases;

	final String defaultDatabase;

	StoreTool(Vertx vertx, GraphDatabases databases, String defaultDatabase) {
		this.vertx = vertx;
		this.databases = databases;
		this.defaultDatabase = defaultDatabase;
	}

	/**
	 * The database named in the arguments, or the server's default.
	 *
	 * <p>
	 * Resolved inside the blocking call rather than captured, for the same reason the metrics gauges do it: a database
	 * can be dropped and recreated between the model deciding to call a tool and the tool running.
	 * </p>
	 */
	String databaseName(JsonObject arguments) {
		String requested = arguments.getString("database");
		return requested == null || requested.isBlank() ? defaultDatabase : requested;
	}

	@Override
	public String database(JsonObject arguments) {
		return databaseName(arguments);
	}

	GraphStore store(String name) {
		try {
			return databases.database(name);
		} catch (GraphStoreException e) {
			throw new IllegalArgumentException("No database '" + name + "'");
		} catch (IOException e) {
			throw new GraphStoreException("Could not open database '" + name + "': " + e.getMessage());
		}
	}

	/** The `database` parameter every store tool accepts, described once. */
	static final String DATABASE_DESCRIPTION = "Which database to use. Defaults to the server's default database; "
		+ "call graph_list_databases to see the others.";
}
