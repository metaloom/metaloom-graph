package io.metaloom.graph.server.mcp.tool;

import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.server.mcp.model.McpToolDescriptor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * One thing an MCP client can do to this graph.
 *
 * <p>
 * A plain {@link java.util.List} of these is the registry — there is no Dagger in this project, and MetaLoom's
 * multibinding exists there because tools are contributed from several Maven modules. Here they are all in one.
 * </p>
 */
public interface GraphMcpTool {

	McpToolDescriptor descriptor();

	/**
	 * Runs the tool.
	 *
	 * <p>
	 * Anything that touches the store must go through {@code vertx.executeBlocking}: a Cypher query is bounded by the
	 * data rather than by the request, and this is an event loop.
	 * </p>
	 */
	Future<JsonObject> execute(JsonObject arguments);

	/**
	 * Runs the tool on behalf of a principal.
	 *
	 * <p>
	 * A default that ignores the principal, because for most tools the descriptor's {@code requiredPrivileges} is the
	 * whole answer and the handler has already checked it. {@code QueryTool} overrides this: what a query needs
	 * depends on the query, exactly as whether it <em>writes</em> does, and that cannot be declared in advance.
	 * </p>
	 */
	default Future<JsonObject> execute(JsonObject arguments, Principal principal) {
		return execute(arguments);
	}

	/**
	 * The database this call is about, which is what the handler checks {@code requiredPrivileges} against.
	 *
	 * <p>
	 * Null for a tool that touches no store. A store tool answers from its {@code database} argument: checking the
	 * server's default instead would let a grant scoped to one database act on every other.
	 * </p>
	 */
	default String database(JsonObject arguments) {
		return null;
	}

	default String name() {
		return descriptor().name();
	}
}
