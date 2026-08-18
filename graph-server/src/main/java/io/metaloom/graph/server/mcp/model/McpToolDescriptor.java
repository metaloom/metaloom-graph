package io.metaloom.graph.server.mcp.model;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.core.api.Privilege;
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
 * <h2>{@code requiredPrivileges} exists now, and did not</h2>
 *
 * <p>
 * This class used to say there was deliberately no such field, because the server had no authentication and therefore
 * no identities, and "inventing a permission field that is always empty would suggest a check that does not happen".
 * That reasoning was right and it is recorded here rather than deleted: the field arrived when the check did, not
 * before. {@code spec/AUTH.md} is the design.
 * </p>
 *
 * <p>
 * Like {@code write}, it is <b>not serialised</b>. It decides whether the tool appears in {@code tools/list} for this
 * principal, and it is re-checked before every {@code tools/call}. Sending it to the model would invite the model to
 * argue about it.
 * </p>
 *
 * @param requiredPrivileges
 *            what a caller must hold. Empty for a tool that needs nothing, which is not the same as a tool nobody may
 *            call — being connected at all already required a credential
 */
public record McpToolDescriptor(String name, String description, JsonObject inputSchema, boolean write,
	java.util.Set<Privilege> requiredPrivileges) {

	/** The four-argument form, for a tool that needs no privilege beyond being connected. */
	public McpToolDescriptor(String name, String description, JsonObject inputSchema, boolean write) {
		this(name, description, inputSchema, write, java.util.Set.of());
	}

	/** Whether {@code principal} may call this tool at all, on {@code database}. */
	public boolean permits(Principal principal, String database) {
		for (Privilege privilege : requiredPrivileges) {
			if (!principal.may(privilege, database)) {
				return false;
			}
		}
		return true;
	}

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
