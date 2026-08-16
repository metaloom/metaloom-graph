package io.metaloom.graph.server.mcp.tool;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The shape MCP wants a tool result in.
 *
 * <p>
 * One text block, because that is the content type every client understands. Structured data is rendered as pretty
 * JSON inside it rather than as a second content type: an LLM reads JSON perfectly well, and a client that only knows
 * `text` still shows the user something useful.
 * </p>
 */
public final class GraphMcpToolResults {

	private GraphMcpToolResults() {
	}

	public static JsonObject text(String text) {
		return new JsonObject().put("content", new JsonArray()
			.add(new JsonObject().put("type", "text").put("text", text)));
	}

	public static JsonObject json(JsonObject payload) {
		return text(payload.encodePrettily());
	}

	/**
	 * A refusal the model can act on.
	 *
	 * <p>
	 * {@code isError} rather than a JSON-RPC error, because the specification distinguishes "the tool ran and says no"
	 * from "the call was malformed", and only the first is something the model should read and adapt to.
	 * </p>
	 */
	public static JsonObject error(String message) {
		return text(message).put("isError", true);
	}
}
