package io.metaloom.graph.server.mcp.model;

import io.vertx.core.json.JsonObject;

/**
 * One JSON-RPC 2.0 request.
 *
 * @param id
 *            null for a notification, which must not be answered. Kept as {@link Object} because the specification
 *            allows a string or a number and an answer has to echo back what arrived, with its type
 * @param method
 *            null when the message has none, or has one that is not a string
 * @param params
 *            null when the message carried {@code params} that are not an object; empty when it carried none
 */
public record JsonRpcRequest(Object id, String method, JsonObject params) {

	/**
	 * Reads a request without trusting its types.
	 *
	 * <p>
	 * {@code getString} and {@code getJsonObject} throw {@code ClassCastException} on a value of another type — and
	 * {@code getString} quietly turns a number into its text. A message is a model's or a stranger's output, so the
	 * types are checked here and a wrong one becomes a null the handler answers with a JSON-RPC error. Thrown instead,
	 * it escaped the handler: a 500 with nothing on the SSE stream, and over a WebSocket no answer at all.
	 * </p>
	 */
	public static JsonRpcRequest from(JsonObject json) {
		Object method = json.getValue("method");
		Object params = json.getValue("params");
		return new JsonRpcRequest(json.getValue("id"), method instanceof String name ? name : null,
			params == null ? new JsonObject() : params instanceof JsonObject object ? object : null);
	}

	/** A notification expects no response at all — answering one is a protocol error, not merely noise. */
	public boolean isNotification() {
		return id == null;
	}
}
