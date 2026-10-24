package io.metaloom.graph.server.mcp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.server.auth.AuthPolicy;
import io.metaloom.graph.server.mcp.model.JsonRpcRequest;
import io.metaloom.graph.server.mcp.model.JsonRpcResponse;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * The Model Context Protocol port: HTTP+SSE and WebSocket.
 *
 * <h2>Off by default, and loopback when on</h2>
 *
 * <p>
 * Every other port here defaults to on. This one does not, and that is the one place this server deliberately diverges
 * from MetaLoom's MCP service — which sits behind a token. There is no authentication anywhere in this process, and MCP
 * is a remote code path an <em>LLM</em> drives: the blast radius of leaving it open is not the same as for a port a
 * person has to point a client at on purpose. {@code --mcp} turns it on and it binds loopback; {@code --mcp-host=}
 * widens it as a separate, deliberate act.
 * </p>
 *
 * <h2>Two transports, one handler</h2>
 *
 * <p>
 * SSE is what the current generation of clients speak: the client opens {@code GET /mcp/sse}, is told over that stream
 * which URL to post to, and every response is written back down the stream. WebSocket is one frame per message and is
 * simpler in every way. Both hand messages to the same {@link McpJsonRpcHandler}, so neither can be missing a rule the
 * other enforces.
 * </p>
 */
public final class McpServer {

	private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

	private final Vertx vertx;

	private final McpJsonRpcHandler handler;

	private final String host;

	/** Open SSE streams by session id. A POST to /mcp/message finds its stream here. */
	private final Map<String, HttpServerResponse> sessions = new ConcurrentHashMap<>();

	private HttpServer server;

	public McpServer(Vertx vertx, McpJsonRpcHandler handler, String host) {
		this.vertx = vertx;
		this.handler = handler;
		this.host = host;
	}

	/**
	 * Who a client has to be, read per request.
	 *
	 * <p>
	 * A supplier for the reason the other two front ends hold one: the policy is set through a setter and capturing it
	 * would make a later call silently do nothing.
	 * </p>
	 */
	private java.util.function.Supplier<AuthPolicy> auth = AuthPolicy::disabled;

	public void authPolicy(java.util.function.Supplier<AuthPolicy> policy) {
		this.auth = policy == null ? AuthPolicy::disabled : policy;
	}

	/** The bearer token on a request, or null. There is no cookie here: an MCP client is not a browser. */
	private static String token(io.vertx.core.MultiMap headers) {
		String header = headers.get("Authorization");
		return header != null && header.startsWith("Bearer ") ? header.substring("Bearer ".length()).trim() : null;
	}

	/** The principal for a request, or empty when it presented nothing this server accepts. */
	private java.util.Optional<Principal> principal(io.vertx.core.MultiMap headers) {
		AuthPolicy policy = auth.get();
		return policy.isEnabled() ? policy.resolve(token(headers)) : java.util.Optional.of(Principal.ANONYMOUS);
	}

	public Future<Integer> listen(int port) {
		Router router = Router.router(vertx);
		router.route().handler(BodyHandler.create().setBodyLimit(1 << 20));
		router.get(McpConstants.SSE_PATH).handler(this::openStream);
		router.post(McpConstants.MESSAGE_PATH).handler(this::acceptMessage);

		// HTTP/1.1 only. Vert.x enables h2c by default and Server-Sent Events do not survive it: measured against
		// this very server, `curl -sN` over HTTP/1.1 returns the endpoint event immediately and the same request
		// with `--http2` returns *zero bytes* and hangs. That matters more than it looks, because java.net.http —
		// which is what a JVM-based MCP client uses — attempts an h2c upgrade by default, so the failure is a client
		// that connects, is told nothing, and waits forever. SSE is an HTTP/1.1 idiom; the transport the MCP
		// specification defines assumes it.
		server = vertx.createHttpServer(new HttpServerOptions()
			.setHttp2ClearTextEnabled(false)
			.setRegisterWebSocketWriteHandlers(false));
		server.webSocketHandler(this::openWebSocket);
		return server.requestHandler(router).listen(port, host).map(HttpServer::actualPort);
	}

	public int port() {
		return server.actualPort();
	}

	/** How many clients are attached, over either transport. Exposed for the {@code graph_mcp_sessions} gauge. */
	public int sessionCount() {
		return sessions.size();
	}

	// ------------------------------------------------------------------ SSE

	private void openStream(RoutingContext context) {
		if (principal(context.request().headers()).isEmpty()) {
			context.response().setStatusCode(401)
				.putHeader("WWW-Authenticate", "Bearer realm=\"metaloom-graph\"")
				.end("This MCP port requires a bearer token. See spec/AUTH.md.");
			return;
		}
		String sessionId = UUID.randomUUID().toString();
		HttpServerResponse response = context.response();
		response.setChunked(true)
			.putHeader("content-type", "text/event-stream")
			.putHeader("cache-control", "no-cache")
			.putHeader("connection", "keep-alive");
		sessions.put(sessionId, response);
		// Removed on close as well as on end: a client that drops its TCP connection never sends either an end or a
		// close frame, and a map that only shrank on a clean goodbye would grow for the lifetime of the process.
		response.closeHandler(ignored -> sessions.remove(sessionId));
		response.endHandler(ignored -> sessions.remove(sessionId));

		// The handshake the specification defines: the first event names the URL to post to, carrying the session.
		response.write("event: endpoint\ndata: " + McpConstants.MESSAGE_PATH + "?sessionId=" + sessionId + "\n\n");
		logger.debug("MCP SSE session {} opened", sessionId);
	}

	private void acceptMessage(RoutingContext context) {
		java.util.Optional<Principal> caller = principal(context.request().headers());
		if (caller.isEmpty()) {
			context.response().setStatusCode(401)
				.putHeader("WWW-Authenticate", "Bearer realm=\"metaloom-graph\"")
				.end("This MCP port requires a bearer token. See spec/AUTH.md.");
			return;
		}
		String sessionId = context.request().getParam("sessionId");
		HttpServerResponse stream = sessionId == null ? null : sessions.get(sessionId);
		if (stream == null) {
			context.response().setStatusCode(404).end("No such MCP session; open " + McpConstants.SSE_PATH + " first");
			return;
		}
		JsonObject message;
		try {
			message = context.body().asJsonObject();
		} catch (DecodeException malformed) {
			context.response().setStatusCode(400).end(JsonRpcResponse
				.error(null, McpConstants.PARSE_ERROR, malformed.getMessage()).toJson().encode());
			return;
		}
		if (message == null) {
			context.response().setStatusCode(400).end(JsonRpcResponse
				.error(null, McpConstants.INVALID_REQUEST, "An empty body is not a JSON-RPC message")
				.toJson().encode());
			return;
		}

		handler.handle(JsonRpcRequest.from(message), caller.get()).onComplete(outcome -> {
			JsonRpcResponse response = outcome.succeeded() ? outcome.result()
				: JsonRpcResponse.error(message.getValue("id"), McpConstants.INTERNAL_ERROR,
					String.valueOf(outcome.cause()));
			if (response == null) {
				// A notification. 202 rather than 200 with a body: there is nothing to say, and returning an empty
				// JSON-RPC envelope would be a protocol violation rather than merely noise.
				context.response().setStatusCode(202).end();
				return;
			}
			String encoded = response.toJson().encode();
			// Answered on BOTH: down the stream because that is where an SSE client reads, and in the POST response
			// because a client that used the POST as a request/response pair would otherwise hang. Sending it twice
			// is allowed; sending it on the wrong one is a hang.
			stream.write("event: message\ndata: " + encoded + "\n\n");
			context.response().putHeader("content-type", "application/json").end(encoded);
		});
	}

	// ------------------------------------------------------------ WebSocket

	private void openWebSocket(ServerWebSocket socket) {
		if (!McpConstants.WEBSOCKET_PATH.equals(socket.path())) {
			// Vert.x 5 removed accept()/reject() from ServerWebSocket — reaching this handler means the handshake
			// already completed — so a wrong path is refused by closing with 1008 (policy violation) rather than by
			// declining the upgrade. The client still learns it asked for the wrong thing.
			socket.close((short) 1008, "Use " + McpConstants.WEBSOCKET_PATH);
			return;
		}
		java.util.Optional<Principal> caller = principal(socket.headers());
		if (caller.isEmpty()) {
			// Vert.x 5 removed accept()/reject() from ServerWebSocket, so reaching this handler means the handshake is
			// already done. 1008 is "policy violation", which is exactly what this is, and is the same mechanism the
			// wrong-path branch above uses.
			socket.close((short) 1008, "This MCP port requires a bearer token");
			return;
		}
		// The headers of the upgrade, kept: the session they name is re-resolved at every message, as an SSE POST is,
		// so a revoke or a disable reaches a socket that stays open for hours instead of waiting for it to close.
		io.vertx.core.MultiMap upgrade = io.vertx.core.MultiMap.caseInsensitiveMultiMap().addAll(socket.headers());
		socket.textMessageHandler(text -> {
			java.util.Optional<Principal> current = principal(upgrade);
			if (current.isEmpty()) {
				socket.close((short) 1008, "This session has ended; reconnect with a new token");
				return;
			}
			JsonObject message;
			try {
				message = new JsonObject(text);
			} catch (DecodeException malformed) {
				socket.writeTextMessage(JsonRpcResponse
					.error(null, McpConstants.PARSE_ERROR, malformed.getMessage()).toJson().encode());
				return;
			}
			handler.handle(JsonRpcRequest.from(message), current.get()).onComplete(outcome -> {
				JsonRpcResponse response = outcome.succeeded() ? outcome.result()
					: JsonRpcResponse.error(message.getValue("id"), McpConstants.INTERNAL_ERROR,
						String.valueOf(outcome.cause()));
				if (response != null) {
					socket.writeTextMessage(response.toJson().encode());
				}
			});
		});
	}

	// ------------------------------------------------------------ lifecycle

	public Future<Void> close() {
		sessions.values().forEach(response -> {
			try {
				response.end();
			} catch (RuntimeException ignored) {
				// A stream whose socket has already gone. Nothing to do and nothing worth logging.
			}
		});
		sessions.clear();
		return server == null ? Future.succeededFuture() : server.close();
	}

	public void closeQuietly() {
		try {
			close().toCompletionStage().toCompletableFuture().join();
		} catch (RuntimeException failure) {
			logger.debug("MCP port did not close cleanly", failure);
		}
	}
}
