package io.metaloom.graph.server.mcp.model;

import io.vertx.core.json.JsonObject;

/**
 * One JSON-RPC 2.0 request.
 *
 * @param id
 *            null for a notification, which must not be answered. Kept as {@link Object} because the specification
 *            allows a string or a number and an answer has to echo back what arrived, with its type
 */
public record JsonRpcRequest(Object id, String method, JsonObject params) {

	public static JsonRpcRequest from(JsonObject json) {
		return new JsonRpcRequest(json.getValue("id"), json.getString("method"),
			json.getJsonObject("params", new JsonObject()));
	}

	/** A notification expects no response at all — answering one is a protocol error, not merely noise. */
	public boolean isNotification() {
		return id == null;
	}
}
