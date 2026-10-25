package io.metaloom.graph.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.metaloom.graph.protocol.FrameReader;
import io.metaloom.graph.protocol.Frames;
import io.metaloom.graph.protocol.Protocol;
import io.metaloom.graph.protocol.QuerySummary;
import io.metaloom.graph.protocol.WireError;
import io.metaloom.graph.protocol.WireValueCodec;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;

/**
 * A binary-port server that says exactly what a test tells it to.
 *
 * <p>
 * The client's hard cases are the ones a real server produces rarely or never on purpose: a connection dropped between
 * two batches, an error after rows, a frame that does not decode, rows for a stream nobody opened, a server that
 * accepts and never answers. A real server would need a fault-injection seam for each; this needs a lambda. It speaks
 * only what graph-protocol defines, so it costs the client module no dependency — which {@code ClientDependencyTest}
 * would otherwise refuse.
 * </p>
 */
final class ScriptedServer implements AutoCloseable {

	/** What the server does with one QUERY frame. Runs on the connection's event loop. */
	@FunctionalInterface
	interface OnQuery {

		void query(Peer peer, int streamId, String text, Map<String, Object> parameters);
	}

	/** One accepted connection, with helpers that write well-formed frames onto it. */
	static final class Peer {

		final NetSocket socket;
		final List<Integer> cancelled = new CopyOnWriteArrayList<>();

		Peer(NetSocket socket) {
			this.socket = socket;
		}

		void columns(int streamId, List<String> names) {
			WireValueCodec.Writer payload = new WireValueCodec.Writer();
			WireValueCodec.encode(payload, names);
			socket.write(Frames.frame(Frames.COLUMNS, streamId, payload));
		}

		void rows(int streamId, List<List<Object>> rows) {
			WireValueCodec.Writer payload = new WireValueCodec.Writer();
			payload.i32(rows.size());
			for (List<Object> row : rows) {
				for (Object value : row) {
					WireValueCodec.encode(payload, value);
				}
			}
			socket.write(Frames.frame(Frames.ROWS, streamId, payload));
		}

		void raw(int type, int streamId, byte[] payload) {
			socket.write(Frames.frame(type, streamId, Buffer.buffer(payload)));
		}

		void complete(int streamId, long rows) {
			WireValueCodec.Writer payload = new WireValueCodec.Writer();
			new QuerySummary(rows, 1, 0, 0, 0, 0, 0, 0, 0).write(payload);
			socket.write(Frames.frame(Frames.COMPLETE, streamId, payload));
		}

		void error(int streamId, WireError code, String message) {
			WireValueCodec.Writer payload = new WireValueCodec.Writer();
			payload.i32(code.code());
			WireValueCodec.encodeString(payload, message);
			payload.i32(0);
			payload.i32(0);
			socket.write(Frames.frame(Frames.ERROR, streamId, payload));
		}

		void welcome() {
			WireValueCodec.Writer welcome = new WireValueCodec.Writer();
			welcome.i32(Protocol.VERSION);
			welcome.i32(Frames.MAX_FRAME_BYTES);
			welcome.i32(64);
			WireValueCodec.encode(welcome, "scripted");
			WireValueCodec.encode(welcome, "");
			WireValueCodec.encode(welcome, List.of());
			socket.write(Frames.frame(Frames.WELCOME, Frames.CONNECTION_STREAM, welcome));
		}
	}

	private final Vertx vertx = Vertx.vertx();
	private final NetServer server;
	private final List<Peer> peers = new CopyOnWriteArrayList<>();
	private volatile OnQuery onQuery;
	private volatile boolean answerHello = true;
	private final int port;

	ScriptedServer(OnQuery onQuery) {
		this.onQuery = onQuery;
		server = vertx.createNetServer().connectHandler(socket -> {
			Peer peer = new Peer(socket);
			peers.add(peer);
			FrameReader.attach(socket, (type, streamId, payload) -> {
				switch (type) {
					case Frames.HELLO -> {
						if (answerHello) {
							peer.welcome();
						}
					}
					case Frames.QUERY -> {
						WireValueCodec.Reader reader = new WireValueCodec.Reader(payload.getBytes());
						String text = (String) WireValueCodec.decode(reader);
						@SuppressWarnings("unchecked")
						Map<String, Object> parameters = (Map<String, Object>) WireValueCodec.decode(reader);
						this.onQuery.query(peer, streamId, text, parameters);
					}
					case Frames.CANCEL -> peer.cancelled.add(streamId);
					default -> {
					}
				}
			}, malformed -> socket.close());
		});
		port = server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join().actualPort();
	}

	/** A server that accepts the connection and then never says WELCOME. */
	ScriptedServer silent() {
		answerHello = false;
		return this;
	}

	int port() {
		return port;
	}

	List<Peer> peers() {
		return peers;
	}

	Vertx vertx() {
		return vertx;
	}

	GraphClient connect() {
		return GraphClient.connect("127.0.0.1", port);
	}

	GraphClient connect(ClientOptions options) {
		return GraphClient.connect("127.0.0.1", port, options);
	}

	@Override
	public void close() {
		server.close().toCompletionStage().toCompletableFuture().join();
		vertx.close().toCompletionStage().toCompletableFuture().join();
	}
}
