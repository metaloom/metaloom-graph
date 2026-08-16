package io.metaloom.graph.server.mcp;

/**
 * The protocol's fixed strings.
 *
 * <p>
 * A copy of the equivalent file in MetaLoom's {@code loom-service-mcp} rather than a shared dependency: the two live in
 * different repositories, and a jar dependency between them purely to share nine string constants would couple their
 * release cycles for no benefit. The values must match the specification, not each other.
 * </p>
 */
public final class McpConstants {

	/** The revision of the Model Context Protocol this server implements. */
	public static final String MCP_PROTOCOL_VERSION = "2025-03-26";

	public static final String JSONRPC_VERSION = "2.0";

	// Methods.
	public static final String METHOD_INITIALIZE = "initialize";
	public static final String METHOD_INITIALIZED = "notifications/initialized";
	public static final String METHOD_PING = "ping";
	public static final String METHOD_TOOLS_LIST = "tools/list";
	public static final String METHOD_TOOLS_CALL = "tools/call";

	// Paths.
	public static final String SSE_PATH = "/mcp/sse";
	public static final String MESSAGE_PATH = "/mcp/message";
	public static final String WEBSOCKET_PATH = "/mcp/ws";

	// JSON-RPC error codes, from the specification.
	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	private McpConstants() {
	}
}
