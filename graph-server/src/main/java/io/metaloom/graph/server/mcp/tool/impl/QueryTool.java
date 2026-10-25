package io.metaloom.graph.server.mcp.tool.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.cypher.Cypher;
import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.cypher.QueryAbort;
import io.metaloom.graph.cypher.RowSink;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.server.ResultJson;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor.McpToolParam;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Runs one Cypher query and returns the rows.
 *
 * <h2>The row cap</h2>
 *
 * <p>
 * <b>An LLM context is not a result set.</b> A model that writes {@code MATCH (n) RETURN n} against a ten million node
 * store should get two hundred rows and a note, not a response that costs more than the rest of the conversation. The
 * cap is enforced by returning {@code false} from {@link RowSink#row}, which is the engine's own "stop producing"
 * signal — so the scan actually stops rather than being truncated after the fact.
 * </p>
 *
 * <p>
 * The response says when it was truncated. A model told it saw everything, when it did not, will happily draw a
 * conclusion from a partial answer.
 * </p>
 */
public final class QueryTool extends StoreTool {

	/** Enough to reason over, small enough to be affordable. */
	static final int DEFAULT_LIMIT = 200;

	/** The ceiling on `limit`, so a model cannot ask for the whole store by naming a big number. */
	static final int MAX_LIMIT = 1_000;

	private final boolean allowWrite;

	private final long queryTimeoutMillis;

	public QueryTool(Vertx vertx, GraphDatabases databases, String defaultDatabase, boolean allowWrite,
		long queryTimeoutMillis) {
		super(vertx, databases, defaultDatabase);
		this.allowWrite = allowWrite;
		this.queryTimeoutMillis = queryTimeoutMillis;
	}

	@Override
	public McpToolDescriptor descriptor() {
		return new McpToolDescriptor("graph_query",
			"Run an openCypher query against the graph and return the rows. This engine implements a SUBSET of "
				+ "openCypher — call graph_cypher_guide before writing anything non-trivial, and note that "
				+ "OPTIONAL MATCH is not supported. Results are capped at " + DEFAULT_LIMIT + " rows by default.",
			McpToolDescriptor.buildInputSchema(List.of(
				new McpToolParam("query", "string", "The Cypher query. Use $name placeholders for values rather "
					+ "than interpolating them.", true),
				new McpToolParam("parameters", "object", "Values for the $name placeholders in the query.", false),
				new McpToolParam("database", "string", DATABASE_DESCRIPTION, false),
				new McpToolParam("limit", "integer", "Maximum rows to return (default " + DEFAULT_LIMIT + ", max "
					+ MAX_LIMIT + ").", false))),
			// Whether *this call* writes depends on the query, so the descriptor cannot say. False here means "do not
			// hide this tool on a read-only server" — the per-query check is in execute().
			false);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return execute(arguments, Principal.ANONYMOUS);
	}

	/**
	 * Runs a query for a principal.
	 *
	 * <p>
	 * The per-query privilege check sits next to the per-query write check, and for the same reason the descriptor
	 * cannot carry either: what a query needs depends on the query. It comes <b>before</b> the {@code --allow-write}
	 * refusal, so a caller who has not been granted {@code MERGE} is told that rather than being told to ask the
	 * operator for a flag that would not help them.
	 * </p>
	 */
	@Override
	public Future<JsonObject> execute(JsonObject arguments, Principal principal) {
		String queryText = arguments.getString("query");
		if (queryText == null || queryText.isBlank()) {
			return Future.succeededFuture(GraphMcpToolResults.error("A 'query' is required."));
		}
		// Clamped as a long: getInteger truncates first, so a limit of Long.MAX_VALUE became -1 and was clamped to one row.
		int limit = (int) Math.clamp(arguments.getLong("limit", (long) DEFAULT_LIMIT), 1, MAX_LIMIT);
		Map<String, Object> parameters = arguments.getJsonObject("parameters", new JsonObject()).getMap();
		String database = databaseName(arguments);

		Ast.Query parsed;
		try {
			parsed = Cypher.parse(queryText);
		} catch (RuntimeException syntax) {
			// Returned as a tool error rather than a JSON-RPC error: the model wrote it and the model can fix it, and
			// the engine's message names the position.
			return Future.succeededFuture(GraphMcpToolResults.error("Syntax error: " + syntax.getMessage()));
		}
		for (Privilege privilege : parsed.requiredPrivileges()) {
			if (!principal.may(privilege, database)) {
				return Future.succeededFuture(GraphMcpToolResults.error("'" + principal.name() + "' has not been "
					+ "granted " + privilege + " on '" + database + "'. Do not retry; ask an administrator."));
			}
		}
		if (parsed.isWrite() && !allowWrite) {
			return Future.succeededFuture(GraphMcpToolResults.error(
				"This server does not accept write queries. It was started without --allow-write, so CREATE, SET, "
					+ "REMOVE and DELETE are refused. Do not retry; ask the operator."));
		}
		// Refused on this port whatever --allow-load-csv says, and unlike every other refusal here that is not a
		// configuration question. This is the port an LLM drives (spec/MCP.md), a LOAD CSV names a file rather than
		// anything in the graph, and a model that can read files under the server's import directory and return them
		// as query results is a wider capability than "query this database" — which is the only thing this tool is
		// for. A bulk import is an operator's action, on the HTTP or binary port.
		if (parsed.readsFiles()) {
			return Future.succeededFuture(GraphMcpToolResults.error(
				"LOAD CSV is not available over MCP. This port answers questions about the graph; loading a file into "
					+ "it is an operator action. Do not retry."));
		}

		return vertx.executeBlocking(() -> {
			GraphStore store = store(database);
			long started = System.nanoTime();
			QueryAbort deadline = queryTimeoutMillis <= 0
				? QueryAbort.NEVER
				: QueryAbort.deadline(started + TimeUnit.MILLISECONDS.toNanos(queryTimeoutMillis), queryTimeoutMillis);
			CappedSink sink = new CappedSink(limit);
			Cypher.stream(store, parsed, parameters, sink, deadline);

			JsonArray rows = new JsonArray();
			for (List<Object> values : sink.rows) {
				JsonObject row = new JsonObject();
				for (int i = 0; i < sink.columns.size() && i < values.size(); i++) {
					// As the HTTP route renders a cell. The engine's node, relationship and path objects have no JSON
					// mapping, so putting them in as they are failed every query that returned an element.
					row.put(sink.columns.get(i), ResultJson.value(values.get(i)));
				}
				rows.add(row);
			}
			JsonObject result = new JsonObject()
				.put("columns", new JsonArray(sink.columns))
				.put("rows", rows)
				.put("rowCount", sink.rows.size())
				.put("truncated", sink.truncated)
				.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
			if (sink.truncated) {
				result.put("note", "Stopped at " + limit + " rows. Narrow the query or add LIMIT rather than "
					+ "assuming this is the whole answer.");
			}
			if (sink.stats.containsUpdates()) {
				result.put("changes", sink.stats.summary());
			}
			return GraphMcpToolResults.json(result);
		}, false);
	}

	/** Stops the scan at the cap, using the engine's own back-pressure rather than truncating afterwards. */
	private static final class CappedSink implements RowSink {

		private final int limit;
		private List<String> columns = List.of();
		private final List<List<Object>> rows = new ArrayList<>();
		private CypherStats stats = CypherStats.NONE;
		private boolean truncated;

		CappedSink(int limit) {
			this.limit = limit;
		}

		@Override
		public void columns(List<String> columns) {
			this.columns = columns;
		}

		@Override
		public boolean row(List<Object> values) {
			if (rows.size() >= limit) {
				truncated = true;
				return false;
			}
			rows.add(values);
			return true;
		}

		@Override
		public void stats(CypherStats stats) {
			this.stats = stats;
		}
	}
}
