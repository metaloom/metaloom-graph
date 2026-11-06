package io.metaloom.graph.server.mcp;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.Principal;
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

	private final String defaultDatabase;

	private final List<GraphMcpTool> tools;

	public McpJsonRpcHandler(List<GraphMcpTool> tools, boolean allowWrite, GraphMetrics metrics) {
		this(tools, allowWrite, metrics, io.metaloom.graph.core.api.GraphDatabases.DEFAULT_NAME);
	}

	public McpJsonRpcHandler(List<GraphMcpTool> tools, boolean allowWrite, GraphMetrics metrics,
		String defaultDatabase) {
		this.allowWrite = allowWrite;
		this.metrics = metrics;
		this.defaultDatabase = defaultDatabase;
		this.tools = List.copyOf(tools);
		this.visible = GraphMcpTools.visible(tools, allowWrite);
		// Every tool, not just the visible ones: dispatch has to be able to recognise a write tool by name in order
		// to refuse it properly, rather than answering "no such tool" and inviting the model to guess again.
		this.byName = tools.stream().collect(Collectors.toMap(GraphMcpTool::name, tool -> tool));
	}

	/** @return the response, or null for a notification, which the specification says must not be answered */
	public Future<JsonRpcResponse> handle(JsonRpcRequest request) {
		return handle(request, Principal.ANONYMOUS);
	}

	/** @param principal who is asking, which decides both what is listed and what may be dispatched */
	public Future<JsonRpcResponse> handle(JsonRpcRequest request, Principal principal) {
		this.principal.set(principal);
		try {
			return handleAs(request);
		} catch (RuntimeException e) {
			// The backstop. Every request with an id gets an answer: an exception escaping to a WebSocket handler is
			// logged by Vert.x and answered by nothing, and the client waits on that id for ever.
			logger.warn("MCP request '{}' failed", request.method(), e);
			return Future.succeededFuture(request.isNotification() ? null
				: JsonRpcResponse.error(request.id(), McpConstants.INTERNAL_ERROR, String.valueOf(e.getMessage())));
		} finally {
			this.principal.remove();
		}
	}

	/**
	 * The principal of the call in progress.
	 *
	 * <p>
	 * A thread local rather than a parameter threaded through six private methods. The handler is a singleton shared
	 * by three transports and every one of them dispatches on the event loop that read the request, so the value is
	 * set and removed within one turn and never spans a suspension point.
	 * </p>
	 */
	private final ThreadLocal<Principal> principal = ThreadLocal.withInitial(() -> Principal.ANONYMOUS);

	private Future<JsonRpcResponse> handleAs(JsonRpcRequest request) {
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
				+ "labels, then graph_cypher_guide before writing anything non-trivial — FOREACH, map projections and "
				+ "several other constructs are not implemented. This server is "
				+ (allowWrite ? "writable." : "READ-ONLY: CREATE, SET, REMOVE and DELETE will be refused."));
	}

	private static String version() {
		String implementation = McpJsonRpcHandler.class.getPackage().getImplementationVersion();
		return implementation == null ? "dev" : implementation;
	}

	private JsonObject toolsList() {
		JsonArray tools = new JsonArray();
		// Filtered by the caller as well as by the flag. An agent shown a capability it cannot use will spend turns
		// trying to use it, which is the whole reason this list is filtered at all — spec/MCP.md section 5.
		GraphMcpTools.visible(this.tools, allowWrite, principal.get(), defaultDatabase)
			.forEach(tool -> tools.add(tool.descriptor().toJson()));
		return new JsonObject().put("tools", tools);
	}

	private Future<JsonRpcResponse> callTool(JsonRpcRequest request) {
		if (request.params() == null) {
			return Future.succeededFuture(JsonRpcResponse.error(request.id(), McpConstants.INVALID_PARAMS,
				"tools/call needs 'params' to be an object"));
		}
		// Types checked rather than trusted: getString turns a number into its text and getJsonObject throws on a
		// string, and an exception escaping here is no answer at all on a WebSocket. See JsonRpcRequest.from.
		Object rawName = request.params().getValue("name");
		Object rawArguments = request.params().getValue("arguments");
		if (!(rawName instanceof String name)) {
			return Future.succeededFuture(JsonRpcResponse.error(request.id(), McpConstants.INVALID_PARAMS,
				"tools/call needs a 'name', as a string"));
		}
		if (rawArguments != null && !(rawArguments instanceof JsonObject)) {
			return Future.succeededFuture(JsonRpcResponse.error(request.id(), McpConstants.INVALID_PARAMS,
				"tools/call needs 'arguments' to be an object"));
		}
		JsonObject arguments = rawArguments == null ? new JsonObject() : (JsonObject) rawArguments;
		try {
			return dispatch(request, name, arguments);
		} catch (ClassCastException wrongType) {
			// An argument of the wrong type, met by a tool reading it with getInteger or getJsonObject. A tool result
			// rather than a JSON-RPC error, like every other mistake a model can correct: it is the model's to fix.
			metrics.recordMcpToolCall(byName.containsKey(name) ? name : "unknown", "refused");
			return Future.succeededFuture(JsonRpcResponse.ok(request.id(), GraphMcpToolResults.error(
				"An argument to '" + name + "' has the wrong type. Check the tool's input schema and retry: "
					+ wrongType.getMessage())));
		}
	}

	private Future<JsonRpcResponse> dispatch(JsonRpcRequest request, String name, JsonObject arguments) {
		GraphMcpTool tool = byName.get(name);
		if (tool == null) {
			// Not the name itself: it is client-chosen, and as a label it would be one new series per string sent.
			metrics.recordMcpToolCall("unknown", "error");
			return Future.succeededFuture(JsonRpcResponse.error(request.id(), McpConstants.METHOD_NOT_FOUND,
				"No tool named '" + name + "'"));
		}
		// The second enforcement point. tools/list already hides write tools on a read-only server; this is what
		// stops a client calling one by a name it remembers from an earlier session against a different server.
		// The other half of the second enforcement point. tools/list already hid what this principal cannot use; this
		// is what stops a call naming a tool remembered from a session as somebody else.
		// Against the database the call names, not the default: every store tool takes a 'database' argument.
		String database = tool.database(arguments);
		String target = database == null ? defaultDatabase : database;
		if (!tool.descriptor().permits(principal.get(), target)) {
			metrics.recordMcpToolCall(name, "refused");
			return Future.succeededFuture(JsonRpcResponse.ok(request.id(), GraphMcpToolResults.error(
				"'" + principal.get().name() + "' has not been granted what '" + name + "' needs ("
					+ tool.descriptor().requiredPrivileges() + ") on '" + target
					+ "'. Do not retry; ask an administrator.")));
		}
		if (tool.descriptor().write() && !allowWrite) {
			metrics.recordMcpToolCall(name, "refused");
			return Future.succeededFuture(JsonRpcResponse.ok(request.id(), GraphMcpToolResults.error(
				"'" + name + "' changes the store, and this server was started without --allow-write. Do not "
					+ "retry; ask the operator.")));
		}
		return tool.execute(arguments, principal.get())
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
