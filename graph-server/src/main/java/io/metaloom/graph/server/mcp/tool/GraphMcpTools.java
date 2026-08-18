package io.metaloom.graph.server.mcp.tool;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.server.mcp.tool.impl.CreateIndexTool;
import io.metaloom.graph.server.mcp.tool.impl.CypherGuideTool;
import io.metaloom.graph.server.mcp.tool.impl.DropIndexTool;
import io.metaloom.graph.server.mcp.tool.impl.ListDatabasesTool;
import io.metaloom.graph.server.mcp.tool.impl.ListIndexesTool;
import io.metaloom.graph.server.mcp.tool.impl.QueryTool;
import io.metaloom.graph.server.mcp.tool.impl.SchemaTool;
import io.metaloom.graph.server.mcp.tool.impl.StatsTool;
import io.vertx.core.Vertx;

/**
 * The registry: which tools exist, and which of them a given server offers.
 *
 * <h2>Write gating, one rule and two enforcement points</h2>
 *
 * <p>
 * A write tool is <b>omitted from the list</b> on a read-only server — an agent should not be shown a capability it
 * cannot use, because it will spend turns trying — <b>and</b> re-checked before dispatch, because a {@code tools/call}
 * naming a tool remembered from an earlier session must not work. Either check alone is a hole: listing without
 * dispatch checking trusts the client's memory, dispatch checking without listing wastes the model's time.
 * </p>
 *
 * <h2>What is deliberately not exposed</h2>
 *
 * <p>
 * Creating and dropping <em>databases</em>. Dropping sits behind its own {@code --allow-drop} flag precisely because
 * it destroys a whole directory in one request and "enabling writes is not asking for that"; handing that to a model
 * is further still. There is also no {@code explain}: the grammar has no {@code EXPLAIN}, and inventing one for MCP
 * would be a query-language change made in the wrong place.
 * </p>
 */
public final class GraphMcpTools {

	private GraphMcpTools() {
	}

	/**
	 * @param allowWrite
	 *            the server's own {@code --allow-write}. The MCP port does not get a second, softer switch: one
	 *            server, one answer to "may this be changed"
	 */
	public static List<GraphMcpTool> all(Vertx vertx, GraphDatabases databases, String defaultDatabase,
		boolean allowWrite, long queryTimeoutMillis) {
		List<GraphMcpTool> tools = new ArrayList<>();
		tools.add(new QueryTool(vertx, databases, defaultDatabase, allowWrite, queryTimeoutMillis));
		tools.add(new SchemaTool(vertx, databases, defaultDatabase));
		tools.add(new StatsTool(vertx, databases, defaultDatabase));
		tools.add(new ListDatabasesTool(vertx, databases));
		tools.add(new ListIndexesTool(vertx, databases, defaultDatabase));
		tools.add(new CreateIndexTool(vertx, databases, defaultDatabase));
		tools.add(new DropIndexTool(vertx, databases, defaultDatabase));
		tools.add(new CypherGuideTool());
		return List.copyOf(tools);
	}

	/** The subset a client is told about: everything on a writable server, the read-only tools otherwise. */
	public static List<GraphMcpTool> visible(List<GraphMcpTool> tools, boolean allowWrite) {
		return allowWrite ? tools : tools.stream().filter(tool -> !tool.descriptor().write()).toList();
	}

	/**
	 * The subset <b>this principal</b> is told about.
	 *
	 * <p>
	 * Both filters, and in this order, because they answer different questions: the flag says what the server will
	 * ever do and the grant says what this caller may do within that. An agent shown a capability it cannot use will
	 * spend turns trying, which is the reason {@code tools/list} filters at all.
	 * </p>
	 */
	public static List<GraphMcpTool> visible(List<GraphMcpTool> tools, boolean allowWrite, Principal principal,
		String defaultDatabase) {
		return visible(tools, allowWrite).stream()
			.filter(tool -> tool.descriptor().permits(principal, defaultDatabase))
			.toList();
	}
}
