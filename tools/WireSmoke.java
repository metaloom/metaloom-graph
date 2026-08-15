import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Does the binary port work in the native binary?
 *
 * <pre>
 * java tools/WireSmoke.java 127.0.0.1 8081
 * </pre>
 *
 * <p>
 * Run by {@code tools/native-image.sh} as part of the smoke test, and useful on its own against a jar.
 * The question it answers is narrow and worth answering: netty's {@code NetServer} path is a different
 * one from the HTTP server's, and three of the four defects the GraalVM build hit were invisible until
 * the binary ran — a missing FFM registration is a run-time error, an unregistered resource is a 404,
 * and netty's buffer allocator failing on the event loop is an empty reply with nothing in the log.
 * </p>
 *
 * <h2>Why plain sockets and no graph-client</h2>
 *
 * <p>
 * A single-file source-launcher program with no classpath runs anywhere a JDK 25 does, which is what a
 * shell script wants. It also means this file is an executable description of the handshake and the
 * frame header: no library between the reader and the bytes.
 * </p>
 *
 * <p>
 * It counts rows out of the {@code ROWS} frame header rather than decoding cells, so it needs the frame
 * format and not the value codec. The value codec is exercised properly, cell by cell against the
 * embedded engine, by {@code WireVersusEmbeddedTest} — this is about whether the port answers at all.
 * </p>
 *
 * @see <a href="../spec/WIRE.md">spec/WIRE.md</a>
 */
public class WireSmoke {

	static final int HEADER_BYTES = 8;
	static final int HELLO = 1;
	static final int WELCOME = 2;
	static final int QUERY = 3;
	static final int COLUMNS = 5;
	static final int ROWS = 6;
	static final int COMPLETE = 7;
	static final int ERROR = 8;

	static final int PROTOCOL_VERSION = 3;
	/** Empty: whichever database the server calls its default. Hard-coding "default" would query the wrong one. */
	static final String DATABASE = "";
	static final String CYPHER = "MATCH (a:Airport)-[r:ROUTE_TO]->(b) RETURN a.code, b.code LIMIT 5";
	static final int EXPECTED_ROWS = 5;

	public static void main(String[] args) throws Exception {
		String host = args.length > 0 ? args[0] : "127.0.0.1";
		int port = args.length > 1 ? Integer.parseInt(args[1]) : 8081;

		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), 5_000);
			socket.setSoTimeout(15_000);
			OutputStream out = socket.getOutputStream();
			DataInputStream in = new DataInputStream(socket.getInputStream());

			// HELLO, and wait for WELCOME before sending anything else — a client that queried straight
			// away would meet a version mismatch as frames it could not read.
			out.write(frame(HELLO, 0, helloPayload(PROTOCOL_VERSION, DATABASE)));
			out.flush();
			Frame welcome = read(in);
			if (welcome.type != WELCOME) {
				fail("expected WELCOME, got " + describe(welcome));
			}
			ByteBuffer limits = ByteBuffer.wrap(welcome.payload).order(ByteOrder.LITTLE_ENDIAN);
			int version = limits.getInt();
			int maxFrame = limits.getInt();
			int maxStreams = limits.getInt();
			String database = readString(limits);
			if (version != PROTOCOL_VERSION) {
				fail("server speaks protocol " + version + ", this smoke test speaks " + PROTOCOL_VERSION);
			}

			out.write(frame(QUERY, 1, queryPayload(CYPHER)));
			out.flush();

			boolean sawColumns = false;
			long rows = 0;
			while (true) {
				Frame f = read(in);
				if (f.type == COLUMNS) {
					sawColumns = true;
				} else if (f.type == ROWS) {
					// The row count is the first four bytes of the payload; the cells behind it need the
					// value codec, which is not this program's job.
					rows += ByteBuffer.wrap(f.payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
				} else if (f.type == COMPLETE) {
					break;
				} else if (f.type == ERROR) {
					fail("the server refused the query: " + describe(f));
				} else {
					fail("unexpected " + describe(f));
				}
			}

			if (!sawColumns) {
				fail("no COLUMNS frame; a client blocking on the header would have hung");
			}
			if (rows != EXPECTED_ROWS) {
				fail("expected " + EXPECTED_ROWS + " rows from the demo graph, got " + rows);
			}
			System.out.println("[wire] " + rows + " rows from database '" + database + "'; protocol " + version
				+ ", max frame " + maxFrame + ", max streams " + maxStreams);
		}
	}

	// ---------------------------------------------------------------- frames

	record Frame(int type, int streamId, byte[] payload) {
	}

	/** Version, then the database name as a codec string. */
	static byte[] helloPayload(int version, String database) {
		byte[] utf8 = database.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer = ByteBuffer.allocate(4 + 2 + utf8.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(version);
		buffer.put((byte) 0x04);            // STRING, size class 0
		buffer.put((byte) utf8.length);
		buffer.put(utf8);
		return buffer.array();
	}

	/** A short codec string, which is all the handshake ever carries. */
	static String readString(ByteBuffer buffer) {
		int tag = buffer.get() & 0xFF;
		if (tag != 0x04) {
			fail("expected a short string in WELCOME, got tag " + tag);
		}
		byte[] utf8 = new byte[buffer.get() & 0xFF];
		buffer.get(utf8);
		return new String(utf8, StandardCharsets.UTF_8);
	}

	static byte[] frame(int type, int streamId, byte[] payload) {
		ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(payload.length);
		buffer.put((byte) type);
		buffer.put((byte) 0);
		buffer.putShort((short) streamId);
		buffer.put(payload);
		return buffer.array();
	}

	static Frame read(DataInputStream in) throws IOException {
		byte[] header = new byte[HEADER_BYTES];
		in.readFully(header);
		ByteBuffer view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		int length = view.getInt();
		int type = view.get() & 0xFF;
		view.get();
		int streamId = view.getShort() & 0xFFFF;
		if (length < 0 || length > 16 * 1024 * 1024) {
			fail("the server sent a frame claiming " + length + " bytes");
		}
		byte[] payload = new byte[length];
		in.readFully(payload);
		return new Frame(type, streamId, payload);
	}

	/** Query text, an empty parameter map, and a zero deadline meaning "the server's own". */
	static byte[] queryPayload(String cypher) {
		byte[] utf8 = cypher.getBytes(StandardCharsets.UTF_8);
		if (utf8.length > 0xFF) {
			throw new IllegalArgumentException("this smoke test only encodes the short-string size class");
		}
		ByteBuffer buffer = ByteBuffer.allocate(2 + utf8.length + 2 + 8).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put((byte) 0x04);            // STRING, size class 0
		buffer.put((byte) utf8.length);
		buffer.put(utf8);
		buffer.put((byte) 0x0B);            // MAP
		buffer.put((byte) 0x00);            // ...with no entries
		buffer.putLong(0);                  // deadline millis
		return buffer.array();
	}

	static String describe(Frame f) {
		String name = switch (f.type) {
			case HELLO -> "HELLO";
			case WELCOME -> "WELCOME";
			case QUERY -> "QUERY";
			case COLUMNS -> "COLUMNS";
			case ROWS -> "ROWS";
			case COMPLETE -> "COMPLETE";
			case ERROR -> "ERROR";
			default -> "frame type " + f.type;
		};
		String text = new String(f.payload, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7E]", ".");
		return name + " on stream " + f.streamId + " (" + f.payload.length + " bytes: " + text + ")";
	}

	static void fail(String message) {
		System.err.println("[wire] " + message);
		System.exit(1);
	}
}
