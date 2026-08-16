package io.metaloom.graph.server.mcp.model;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What a tool advertises to a client, plus the one bit the client never sees.
 *
 * <p>
 * {@code write} is <b>not serialised</b>. It is a server-side dispatch detail: it decides whether the tool appears in
 * {@code tools/list} at all on a read-only server, and it is re-checked before every {@code tools/call}. Sending it to
 * the model would invite the model to argue about it.
 * </p>
 *
 * <p>
 * There is no {@code requiredPermissions} and no {@code requiresIdentity} here, unlike MetaLoom's equivalent. This
 * server has no authentication and therefore no identities; inventing a permission field that is always empty would
 * suggest a check that does not happen.
 * </p>
 */
public record McpToolDescriptor(String name, String description, JsonObject inputSchema, boolean write) {

	/** One parameter of a tool, as JSON Schema is going to want it. */
	public record McpToolParam(String name, String type, String description, boolean required) {
	}

	public static JsonObject buildInputSchema(List<McpToolParam> params) {
		JsonObject properties = new JsonObject();
		List<String> required = new ArrayList<>();
		for (McpToolParam param : params) {
			properties.put(param.name(), new JsonObject()
				.put("type", param.type())
				.put("description", param.description()));
			if (param.required()) {
				required.add(param.name());
			}
		}
		return new JsonObject()
			.put("type", "object")
			.put("properties", properties)
			.put("required", new JsonArray(required));
	}

	public JsonObject toJson() {
		return new JsonObject()
			.put("name", name)
			.put("description", description)
			.put("inputSchema", inputSchema);
	}
}
