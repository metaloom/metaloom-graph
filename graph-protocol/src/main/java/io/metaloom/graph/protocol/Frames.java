package io.metaloom.graph.protocol;

import io.vertx.core.buffer.Buffer;

/**
 * The frame header, and what the eight kinds of frame carry.
 *
 * <pre>
 * u32 length     payload bytes, little-endian
 * u8  type
 * u8  flags      reserved, written as zero
 * u16 streamId   little-endian
 * </pre>
 *
 * <p>
 * Eight bytes, so the payload starts 8-aligned and a header never straddles awkwardly. Little-endian explicitly, like
 * every other binary structure in this project — a stream written on one machine has to be readable on another.
 * </p>
 *
 * <h2>The stream id</h2>
 *
 * <p>
 * One connection carries many queries at once, and every frame after {@link #WELCOME} names which one it belongs to.
 * That is what lets a client issue a second query without waiting for the first to finish, and it is why the id is in
 * the header rather than in each payload: the dispatcher has to route a frame before it has decoded anything.
 * </p>
 *
 * <h2>Payloads</h2>
 *
 * <p>
 * Everything that is not a fixed-width scalar is a {@link WireValueCodec} value, so there is one decoder to get right
 * and one to fuzz rather than a bespoke reader per frame type.
 * </p>
 *
 * <table>
 * <caption>Frame payloads</caption>
 * <tr><th>Frame</th><th>Direction</th><th>Payload</th></tr>
 * <tr><td>{@link #HELLO}</td><td>client</td><td>{@code i32} protocol version</td></tr>
 * <tr><td>{@link #WELCOME}</td><td>server</td><td>{@code i32} version, {@code i32} max frame bytes, {@code i32} max concurrent streams</td></tr>
 * <tr><td>{@link #QUERY}</td><td>client</td><td>value: query text, value: parameter map, {@code i64} deadline millis (0 = the server's own)</td></tr>
 * <tr><td>{@link #CANCEL}</td><td>client</td><td>empty</td></tr>
 * <tr><td>{@link #COLUMNS}</td><td>server</td><td>value: list of column names</td></tr>
 * <tr><td>{@link #ROWS}</td><td>server</td><td>{@code i32} row count, then {@code rows × columns} values</td></tr>
 * <tr><td>{@link #COMPLETE}</td><td>server</td><td>{@code i64} row count, {@code i64} elapsed micros</td></tr>
 * <tr><td>{@link #ERROR}</td><td>server</td><td>{@code i32} {@link WireError} code, value: message, {@code i32} line, {@code i32} column</td></tr>
 * </table>
 *
 * <p>
 * <b>The length is validated against a cap before a single byte is allocated.</b> That is the entire contract the
 * hostile-input oracle asserts, and the reason it is stated here rather than left to whoever writes the decoder: a
 * length prefix read from a socket is the textbook unvalidated length field, and seven defects of exactly that kind
 * were found in this project's storage parsers.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
public final class Frames {

	public static final int HEADER_BYTES = 8;

	public static final int HELLO = 1;
	public static final int WELCOME = 2;
	public static final int QUERY = 3;
	public static final int CANCEL = 4;
	public static final int COLUMNS = 5;
	public static final int ROWS = 6;
	public static final int COMPLETE = 7;
	public static final int ERROR = 8;

	/**
	 * The largest frame either side will write or accept.
	 *
	 * <p>
	 * Larger than the HTTP route's 1 MiB body limit because a row batch is a legitimate reason to send a lot at once,
	 * and because a single cell may be a string of any size — the case the storage codec cannot even encode. Small
	 * enough that a hostile peer cannot make the other side allocate its way out of memory with one header.
	 * </p>
	 */
	public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

	/** Streams are numbered from one; zero is the connection itself, which is what HELLO and WELCOME travel on. */
	public static final int CONNECTION_STREAM = 0;

	private Frames() {
	}

	public static Buffer header(int type, int streamId, int payloadLength) {
		if (payloadLength < 0 || payloadLength > MAX_FRAME_BYTES) {
			throw new WireFormatException("Refusing to write a " + payloadLength + " byte frame");
		}
		if (streamId < 0 || streamId > 0xFFFF) {
			throw new WireFormatException("Stream id " + streamId + " is outside 0..65535");
		}
		Buffer buffer = Buffer.buffer(HEADER_BYTES);
		buffer.appendIntLE(payloadLength);
		buffer.appendByte((byte) type);
		buffer.appendByte((byte) 0);
		buffer.appendShortLE((short) streamId);
		return buffer;
	}

	/** Header and payload as one buffer, which is one write and therefore one chance to be interleaved. */
	public static Buffer frame(int type, int streamId, Buffer payload) {
		return header(type, streamId, payload.length()).appendBuffer(payload);
	}

	public static Buffer frame(int type, int streamId, WireValueCodec.Writer payload) {
		return header(type, streamId, payload.position())
			.appendBytes(payload.array(), 0, payload.position());
	}

	public static void checkLength(int length) {
		if (length < 0 || length > MAX_FRAME_BYTES) {
			throw new WireFormatException("Frame length " + length + " is outside 0.." + MAX_FRAME_BYTES);
		}
	}

	/** For log lines and error messages, where a bare number tells nobody anything. */
	public static String typeName(int type) {
		return switch (type) {
			case HELLO -> "HELLO";
			case WELCOME -> "WELCOME";
			case QUERY -> "QUERY";
			case CANCEL -> "CANCEL";
			case COLUMNS -> "COLUMNS";
			case ROWS -> "ROWS";
			case COMPLETE -> "COMPLETE";
			case ERROR -> "ERROR";
			default -> "frame type " + type;
		};
	}
}
