package io.metaloom.graph.server.mcp;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.server.mcp.model.JsonRpcRequest;
import io.metaloom.graph.server.mcp.model.JsonRpcResponse;
import io.metaloom.graph.server.mcp.tool.GraphMcpTool;
import io.metaloom.graph.server.mcp.tool.GraphMcpTools;
import io.metaloom.graph.server.mcp.tool.GraphMcpToolResults;
import io.metaloom.graph.server.metrics.GraphMetrics;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The protocol itself: {@code initialize}, {@code tools/list}, {@code tools/call}, {@code ping}.
 *
 * <p>
 * Transport-independent on purpose — the SSE and WebSocket front ends in {@link McpServer} both hand messages here, so
 * a rule enforced in this class cannot be missing from one of them.
 * </p>
 */
public final class McpJsonRpcHandler {

	private static final Logger logger = LoggerFactory.getLogger(McpJsonRpcHandler.class);

	private final Map<String, GraphMcpTool> byName;

	private final List<GraphMcpTool> visible;

	private final boolean allowWrite;

	private final GraphMetrics metrics;

	public McpJsonRpcHandler(List<GraphMcpTool> tools, boolean allowWrite, GraphMetrics metrics) {
		this.allowWrite = allowWrite;
		this.metrics = metrics;
		this.visible = GraphMcpTools.visible(tools, allowWrite);
		// Every tool, not just the visible ones: dispatch has to be able to recognise a write tool by name in order
		// to refuse it properly, rather than answering "no such tool" and inviting the model to guess again.
		this.byName = tools.stream().collect(Collectors.toMap(GraphMcpTool::name, tool -> tool));
	}

	/** @return the response, or null for a notification, which the specification says must not be answered */
	public Future<JsonRpcResponse> handle(JsonRpcRequest request) {
		String method = request.method();
		if (method == null) {
			return Future.succeededFuture(
				JsonRpcResponse.error(request.id(), McpConstants.INVALID_REQUEST, "A 'method' is required"));
		}
		return switch (method) {
			case McpConstants.METHOD_INITIALIZE -> Future.succeededFuture(
				JsonRpcResponse.ok(request.id(), initialize()));
			case McpConstants.METHOD_INITIALIZED -> Future.succeededFuture(null);
			case McpConstants.METHOD_PING -> Future.succeededFuture(
				JsonRpcResponse.ok(request.id(), new JsonObject()));
			case McpConstants.METHOD_TOOLS_LIST -> Future.succeededFuture(
				JsonRpcResponse.ok(request.id(), toolsList()));
			case McpConstants.METHOD_TOOLS_CALL -> callTool(request);
			default -> Future.succeededFuture(JsonRpcResponse.error(request.id(),
				McpConstants.METHOD_NOT_FOUND, "Unknown method '" + method + "'"));
		};
	}

	private JsonObject initialize() {
		return new JsonObject()
			.put("protocolVersion", McpConstants.MCP_PROTOCOL_VERSION)
			.put("capabilities", new JsonObject().put("tools", new JsonObject()))
			.put("serverInfo", new JsonObject()
				.put("name", "metaloom-graph")
				.put("version", version()))
			// Read by a client that shows the user what a server is for, and by the model as context. Naming the
			// read-only posture here means it is known before the first refused write rather than after it.
			.put("instructions", "A graph database speaking a subset of openCypher. Call graph_schema to learn the "
				+ "labels, then graph_cypher_guide before writing anything non-trivial — OPTIONAL MATCH and several "
				+ "other constructs are not implemented. This server is "
				+ (allowWrite ? "writable." : "READ-ONLY: CREATE, SET, REMOVE and DELETE will be refused."));
	}

	private static String version() {
		String implementation = McpJsonRpcHandler.class.getPackage().getImplementationVersion();
		return implementation == null ? "dev" : implementation;
	}

	private JsonObject toolsList() {
		JsonArray tools = new JsonArray();
		visible.forEach(tool -> tools.add(tool.descriptor().toJson()));
		return new JsonObject().put("tools", tools);
	}

	private Future<JsonRpcResponse> callTool(JsonRpcRequest request) {
		String name = request.params().getString("name");
		JsonObject arguments = request.params().getJsonObject("arguments", new JsonObject());
		if (name == null) {
			return Future.succeededFuture(JsonRpcResponse.error(request.id(), McpConstants.INVALID_PARAMS,
				"tools/call needs a 'name'"));
		}
		GraphMcpTool tool = byName.get(name);
		if (tool == null) {
			metrics.recordMcpToolCall(name, "error");
			return Future.succeededFuture(JsonRpcResponse.error(request.id(), McpConstants.METHOD_NOT_FOUND,
				"No tool named '" + name + "'"));
		}
		// The second enforcement point. tools/list already hides write tools on a read-only server; this is what
		// stops a client calling one by a name it remembers from an earlier session against a different server.
		if (tool.descriptor().write() && !allowWrite) {
			metrics.recordMcpToolCall(name, "refused");
			return Future.succeededFuture(JsonRpcResponse.ok(request.id(), GraphMcpToolResults.error(
				"'" + name + "' changes the store, and this server was started without --allow-write. Do not "
					+ "retry; ask the operator.")));
		}
		return tool.execute(arguments)
			.map(result -> {
				metrics.recordMcpToolCall(name, result.getBoolean("isError", false) ? "refused" : "ok");
				return JsonRpcResponse.ok(request.id(), result);
			})
			.otherwise(failure -> {
				// Returned as a tool result rather than a JSON-RPC error: the call was well formed and the model can
				// act on the message, which a transport-level error would not let it see as tool output.
				logger.warn("MCP tool '{}' failed", name, failure);
				metrics.recordMcpToolCall(name, "error");
				return JsonRpcResponse.ok(request.id(),
					GraphMcpToolResults.error(name + " failed: " + failure.getMessage()));
			});
	}
}
