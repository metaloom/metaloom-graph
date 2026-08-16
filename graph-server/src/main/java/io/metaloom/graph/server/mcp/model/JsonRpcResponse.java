package io.metaloom.graph.server.mcp.model;

import io.metaloom.graph.server.mcp.McpConstants;
import io.vertx.core.json.JsonObject;

/** One JSON-RPC 2.0 response, success or error. */
public record JsonRpcResponse(Object id, JsonObject result, JsonObject error) {

	public static JsonRpcResponse ok(Object id, JsonObject result) {
		return new JsonRpcResponse(id, result, null);
	}

	public static JsonRpcResponse error(Object id, int code, String message) {
		return new JsonRpcResponse(id, null, new JsonObject().put("code", code).put("message", message));
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject().put("jsonrpc", McpConstants.JSONRPC_VERSION);
		// Explicitly null rather than absent when there was no id: the specification requires the member to be
		// present on an error response even where the request could not be parsed well enough to have one.
		json.put("id", id);
		if (error != null) {
			json.put("error", error);
		} else {
			json.put("result", result == null ? new JsonObject() : result);
		}
		return json;
	}
}
