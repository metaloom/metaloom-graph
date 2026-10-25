package io.metaloom.graph.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * Property-based round trips: whatever the encoder accepts, the decoder gives back unchanged.
 *
 * <p>
 * {@link WireValueCodecTest} pins one example per type. This generates thousands of random values — nested lists and
 * maps, elements inside paths inside lists, every numeric extreme and size-class boundary, strings across the whole of
 * Unicode including supplementary characters — and asserts three things of each: it decodes to an equal value, the
 * decoder consumes exactly the bytes the encoder wrote, and values written back to back come apart again at the right
 * places. A seeded {@link Random}, so a failure names a seed that reproduces it.
 * </p>
 *
 * <p>
 * The frame half does the same for the transport: random frames of every type, payload lengths from zero to a
 * megabyte, delivered in random chunkings that split headers, split payloads and coalesce frames.
 * </p>
 */
@Tag("unit")
public class WireCodecPropertyTest {

	private static final long[] INTEGER_EDGES = { 0, 1, -1, 127, 128, -128, -129, 255, 256, 0x7FFF, 0xFFFF, 0x10000,
		Integer.MAX_VALUE, Integer.MIN_VALUE, (long) Integer.MAX_VALUE + 1, (long) Integer.MIN_VALUE - 1,
		0x7FFFFFFFL, 0xFFFFFFFFL, 0x100000000L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1,
		Long.MIN_VALUE + 1, 1L << 60 };

	private static final double[] DOUBLE_EDGES = { 0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY,
		Double.NEGATIVE_INFINITY, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
		Double.MIN_NORMAL, 1.0, -1.5, 1e-300, 1e300 };

	/** What a value decodes to: integers of every width come back as Long, everything else as itself. */
	private static Object normalised(Object value) {
		return switch (value) {
			case null -> null;
			case Integer i -> i.longValue();
			case Short s -> s.longValue();
			case Byte b -> b.longValue();
			case List<?> list -> {
				List<Object> out = new ArrayList<>(list.size());
				for (Object element : list) {
					out.add(normalised(element));
				}
				yield out;
			}
			case Map<?, ?> map -> {
				Map<String, Object> out = new LinkedHashMap<>();
				map.forEach((k, v) -> out.put(String.valueOf(k), normalised(v)));
				yield out;
			}
			case WireNode node -> new WireNode(node.id(), node.labels(), normalisedProperties(node.properties()));
			case WireRelationship rel -> normalisedRel(rel);
			case WirePath path -> {
				List<WireNode> nodes = new ArrayList<>();
				for (WireNode node : path.nodes()) {
					nodes.add((WireNode) normalised(node));
				}
				List<WireRelationship> rels = new ArrayList<>();
				for (WireRelationship rel : path.relationships()) {
					rels.add(normalisedRel(rel));
				}
				yield new WirePath(nodes, rels);
			}
			default -> value;
		};
	}

	private static WireRelationship normalisedRel(WireRelationship rel) {
		return new WireRelationship(rel.id(), rel.type(), rel.startId(), rel.endId(),
			normalisedProperties(rel.properties()));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> normalisedProperties(Map<String, Object> properties) {
		return (Map<String, Object>) normalised(properties);
	}

	// ---------------------------------------------------------------- generators

	private static final class Values {

		private final Random random;

		Values(long seed) {
			this.random = new Random(seed);
		}

		Object any(int depth) {
			// Containers get rarer with depth, so a value terminates and its size stays bounded.
			int choice = random.nextInt(depth >= 6 ? 7 : 12);
			return switch (choice) {
				case 0 -> null;
				case 1 -> random.nextBoolean();
				case 2 -> integer();
				case 3 -> floating();
				case 4, 5 -> string();
				case 6 -> random.nextBoolean() ? (Object) random.nextInt() : (Object) (short) random.nextInt();
				case 7 -> list(depth + 1);
				case 8 -> map(depth + 1);
				case 9 -> node(depth + 1);
				case 10 -> relationship(depth + 1);
				default -> path(depth + 1);
			};
		}

		long integer() {
			if (random.nextInt(3) == 0) {
				return INTEGER_EDGES[random.nextInt(INTEGER_EDGES.length)];
			}
			// Spread over every magnitude rather than uniform over the long range, which is almost always 9 bytes.
			return random.nextLong() >> random.nextInt(64);
		}

		Object floating() {
			if (random.nextInt(4) == 0) {
				// A Float travels as a FLOAT of size class 0 and comes back as a Float.
				return random.nextFloat() * (random.nextBoolean() ? 1e30f : -1e-30f);
			}
			if (random.nextInt(3) == 0) {
				return DOUBLE_EDGES[random.nextInt(DOUBLE_EDGES.length)];
			}
			return Double.longBitsToDouble(random.nextLong());
		}

		String string() {
			int kind = random.nextInt(10);
			int length = switch (kind) {
				case 0 -> 0;
				case 1 -> 254 + random.nextInt(4); // around the one-byte length boundary, counted in chars
				case 2 -> 2_000 + random.nextInt(4_000);
				default -> random.nextInt(40);
			};
			StringBuilder out = new StringBuilder(length);
			while (out.length() < length) {
				out.appendCodePoint(codePoint());
			}
			return out.toString();
		}

		/** Any scalar value: ASCII, two- and three-byte UTF-8, and supplementary characters as surrogate pairs. */
		int codePoint() {
			while (true) {
				int cp = switch (random.nextInt(5)) {
					case 0 -> random.nextInt(0x80);
					case 1 -> 0x80 + random.nextInt(0x800 - 0x80);
					case 2 -> 0x800 + random.nextInt(0x10000 - 0x800);
					case 3 -> 0x10000 + random.nextInt(0x110000 - 0x10000);
					default -> "aé€😀\u0000�﻿".codePointAt(random.nextInt(4));
				};
				if (!Character.isSurrogate((char) cp) || cp > 0xFFFF) {
					return cp;
				}
			}
		}

		List<Object> list(int depth) {
			int size = random.nextInt(8) == 0 ? 300 + random.nextInt(200) : random.nextInt(6);
			List<Object> list = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				// Long homogeneous lists are what a vector looks like on this wire.
				list.add(size > 100 ? (Object) random.nextDouble() : any(depth));
			}
			return list;
		}

		Map<String, Object> map(int depth) {
			int size = random.nextInt(6);
			Map<String, Object> map = new LinkedHashMap<>();
			for (int i = 0; i < size; i++) {
				map.put(string(), any(depth));
			}
			return map;
		}

		Map<String, Object> properties(int depth) {
			return random.nextInt(3) == 0 ? Map.of() : map(depth);
		}

		WireNode node(int depth) {
			List<String> labels = new ArrayList<>();
			for (int i = random.nextInt(4); i > 0; i--) {
				labels.add(string());
			}
			return new WireNode(random.nextLong(), labels, properties(depth));
		}

		WireRelationship relationship(int depth) {
			// A lightweight edge has no id and no properties.
			if (random.nextInt(3) == 0) {
				return new WireRelationship(null, string(), random.nextLong(), random.nextLong(), Map.of());
			}
			return new WireRelationship(random.nextLong(), string(), random.nextLong(), random.nextLong(),
				properties(depth));
		}

		WirePath path(int depth) {
			int hops = random.nextInt(4);
			List<WireNode> nodes = new ArrayList<>();
			List<WireRelationship> rels = new ArrayList<>();
			nodes.add(node(depth + 1));
			for (int i = 0; i < hops; i++) {
				rels.add(relationship(depth + 1));
				nodes.add(node(depth + 1));
			}
			return new WirePath(nodes, rels);
		}
	}

	private static byte[] encode(Object value) {
		WireValueCodec.Writer writer = new WireValueCodec.Writer(16);
		WireValueCodec.encode(writer, value);
		return writer.toArray();
	}

	// ---------------------------------------------------------------- value round trips

	@Test
	public void testRandomValuesRoundTripExactly() {
		for (long seed = 1; seed <= 3_000; seed++) {
			Object value = new Values(seed).any(0);
			byte[] bytes = encode(value);
			WireValueCodec.Reader reader = new WireValueCodec.Reader(bytes);
			Object decoded = WireValueCodec.decode(reader);
			assertEquals(normalised(value), decoded, "seed " + seed);
			assertEquals(0, reader.remaining(), "seed " + seed + ": the decoder must consume exactly what was written");
		}
	}

	@Test
	public void testValuesWrittenBackToBackComeApartAtTheRightPlaces() {
		for (long seed = 1; seed <= 300; seed++) {
			Values values = new Values(seed * 7919);
			List<Object> written = new ArrayList<>();
			WireValueCodec.Writer writer = new WireValueCodec.Writer(16);
			for (int i = 0; i < 20; i++) {
				Object value = values.any(0);
				written.add(normalised(value));
				WireValueCodec.encode(writer, value);
			}
			WireValueCodec.Reader reader = new WireValueCodec.Reader(writer.toArray());
			for (int i = 0; i < written.size(); i++) {
				assertEquals(written.get(i), WireValueCodec.decode(reader), "seed " + seed + ", value " + i);
			}
			assertEquals(0, reader.remaining());
		}
	}

	@Test
	public void testEveryIntegerEdgeAndItsNeighbours() {
		for (long edge : INTEGER_EDGES) {
			for (long delta = -2; delta <= 2; delta++) {
				long value = edge + delta;
				assertEquals(value, WireValueCodec.decode(new WireValueCodec.Reader(encode(value))), "value " + value);
			}
		}
	}

	@Test
	public void testEveryDoubleEdgeKeepsItsBits() {
		for (double edge : DOUBLE_EDGES) {
			Object decoded = WireValueCodec.decode(new WireValueCodec.Reader(encode(edge)));
			// Bits, not ==: -0.0 == 0.0 and NaN != NaN, and both of those would hide a codec that changed the value.
			assertEquals(Double.doubleToRawLongBits(edge), Double.doubleToRawLongBits((Double) decoded), "value " + edge);
		}
		float negativeZero = -0.0f;
		Object decoded = WireValueCodec.decode(new WireValueCodec.Reader(encode(negativeZero)));
		assertEquals(Float.floatToRawIntBits(negativeZero), Float.floatToRawIntBits((Float) decoded));
	}

	@Test
	public void testStringLengthsAroundTheSizeClassBoundary() {
		// The one-byte length holds 255 bytes of UTF-8, which is fewer than 255 characters once any is multi-byte.
		for (String unit : List.of("a", "é", "€", "😀")) {
			for (int bytes = 240; bytes <= 270; bytes++) {
				String value = unit.repeat(Math.max(1, bytes / unit.getBytes(StandardCharsets.UTF_8).length));
				byte[] encoded = encode(value);
				assertEquals(value, WireValueCodec.decode(new WireValueCodec.Reader(encoded)), unit + " x " + bytes);
				int utf8 = value.getBytes(StandardCharsets.UTF_8).length;
				assertEquals(utf8 <= 255 ? 2 + utf8 : 5 + utf8, encoded.length, "the length header for " + utf8 + " bytes");
			}
		}
	}

	@Test
	public void testABigStringAndAVectorSizedListRoundTrip() {
		String big = "ü".repeat(3 * 1024 * 1024);
		assertEquals(big, WireValueCodec.decode(new WireValueCodec.Reader(encode(big))));

		List<Object> vector = new ArrayList<>();
		Random random = new Random(4);
		for (int i = 0; i < 4_096; i++) {
			vector.add(random.nextGaussian());
		}
		assertEquals(vector, WireValueCodec.decode(new WireValueCodec.Reader(encode(vector))));
	}

	/**
	 * A lone surrogate is refused rather than silently turned into {@code '?'}.
	 *
	 * <p>
	 * {@code String.getBytes(UTF_8)} replaces a surrogate without its pair with a question mark, so {@code "\uD800"} and
	 * {@code "?"} encoded to the same bytes: a parameter a client sent arrived at the server as a different string, and
	 * nothing anywhere said so. The storage codec refuses the same input for the same reason.
	 * </p>
	 */
	@Test
	public void testALoneSurrogateIsRefusedRatherThanReplaced() {
		for (String lone : List.of("\uD800", "a\uDC00b", "\uD83D", "x\uD83Dy", "\uDE00\uD83D")) {
			assertThrows(IllegalArgumentException.class, () -> encode(lone), "lone surrogate in " + escaped(lone));
			assertThrows(IllegalArgumentException.class, () -> encode(Map.of(lone, 1L)), "as a key");
			assertThrows(IllegalArgumentException.class, () -> encode(List.of("ok", lone)), "inside a list");
		}
		// A proper pair is fine, in every position.
		assertEquals("😀", WireValueCodec.decode(new WireValueCodec.Reader(encode("😀"))));
	}

	/**
	 * The fast path that skips the surrogate check looks for {@code '?'} eight bytes at a time; a lone surrogate at
	 * every offset in and around a word, and a real {@code '?'} at every offset, are each handled exactly.
	 */
	@Test
	public void testTheSurrogateCheckIsExactAtEveryOffset() {
		for (int length = 1; length <= 40; length++) {
			for (int at = 0; at < length; at++) {
				StringBuilder lone = new StringBuilder("é".repeat(length % 3)).append("a".repeat(length));
				lone.setCharAt(lone.length() - length + at, '\uDC01');
				String withLone = lone.toString();
				assertThrows(IllegalArgumentException.class, () -> encode(withLone), length + "@" + at);

				StringBuilder question = new StringBuilder("a".repeat(length));
				question.setCharAt(at, '?');
				String withQuestion = question.toString();
				assertEquals(withQuestion, WireValueCodec.decode(new WireValueCodec.Reader(encode(withQuestion))));
			}
		}
	}

	/**
	 * Bytes that are not UTF-8 are refused rather than decoded to U+FFFD.
	 *
	 * <p>
	 * The decoder's contract is that a value either decodes or raises {@link WireFormatException}. A replacement
	 * character is neither: it is a different string than the one the peer sent, produced silently.
	 * </p>
	 */
	@Test
	public void testMalformedUtf8IsRefusedAndARealReplacementCharacterIsNot() {
		byte[][] malformed = { { (byte) 0xC3, 0x28 }, { (byte) 0xFF }, { (byte) 0xED, (byte) 0xA0, (byte) 0x80 },
			{ (byte) 0xE2, (byte) 0x82 }, { (byte) 0xF0, (byte) 0x9F, (byte) 0x98 }, { (byte) 0xC0, (byte) 0x80 } };
		for (byte[] bytes : malformed) {
			byte[] frame = new byte[2 + bytes.length];
			frame[0] = WireValueCodec.T_STRING;
			frame[1] = (byte) bytes.length;
			System.arraycopy(bytes, 0, frame, 2, bytes.length);
			assertThrows(WireFormatException.class, () -> WireValueCodec.decode(new WireValueCodec.Reader(frame)),
				"bytes " + java.util.HexFormat.of().formatHex(bytes));
		}
		assertEquals("a�b", WireValueCodec.decode(new WireValueCodec.Reader(encode("a�b"))));
	}

	@Test
	public void testTypesTheWireHasNoTagForAreRefused() {
		for (Object unencodable : List.of(new byte[] { 1 }, new float[] { 1f }, Instant.EPOCH, UUID.randomUUID(),
			'c', new Object(), java.math.BigInteger.ONE)) {
			assertThrows(IllegalArgumentException.class, () -> encode(unencodable), unencodable.getClass().getName());
			assertThrows(IllegalArgumentException.class, () -> encode(List.of(unencodable)), "nested");
		}
	}

	/**
	 * A path is n+1 nodes and n relationships, and only the hop count is written.
	 *
	 * <p>
	 * So a path that is not that shape encodes into bytes that decode as something else entirely — the decoder reads
	 * hops+1 nodes and runs into whatever followed. It is refused when it is made.
	 * </p>
	 */
	@Test
	public void testAPathThatIsNotAPathIsRefusedWhenMade() {
		WireNode node = new WireNode(1, List.of(), Map.of());
		WireRelationship rel = new WireRelationship(2L, "R", 1, 1, Map.of());
		assertThrows(IllegalArgumentException.class, () -> new WirePath(List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new WirePath(List.of(node, node), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new WirePath(List.of(node), List.of(rel)));
		assertEquals(0, new WirePath(List.of(node), List.of()).length());
	}

	private static String escaped(String value) {
		StringBuilder out = new StringBuilder();
		value.chars().forEach(c -> out.append(String.format("\\u%04X", c)));
		return out.toString();
	}

	// ---------------------------------------------------------------- frames

	/** A stream the test drives by hand. */
	private static final class FakeStream implements ReadStream<Buffer> {

		private Handler<Buffer> handler;

		void deliver(Buffer buffer) {
			handler.handle(buffer);
		}

		@Override
		public ReadStream<Buffer> handler(Handler<Buffer> handler) {
			this.handler = handler;
			return this;
		}

		@Override
		public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
			return this;
		}

		@Override
		public ReadStream<Buffer> endHandler(Handler<Void> handler) {
			return this;
		}

		@Override
		public ReadStream<Buffer> pause() {
			return this;
		}

		@Override
		public ReadStream<Buffer> resume() {
			return this;
		}

		@Override
		public ReadStream<Buffer> fetch(long amount) {
			return this;
		}
	}

	private record Frame(int type, int streamId, byte[] payload) {
	}

	@Test
	public void testRandomFramesSurviveRandomChunking() {
		for (long seed = 1; seed <= 200; seed++) {
			Random random = new Random(seed);
			List<Frame> sent = new ArrayList<>();
			Buffer wire = Buffer.buffer();
			int count = 1 + random.nextInt(30);
			for (int i = 0; i < count; i++) {
				int length = switch (random.nextInt(6)) {
					case 0 -> 0;
					case 1 -> random.nextInt(8);
					case 2 -> 64 * 1024 + random.nextInt(1024);
					case 3 -> seed % 20 == 0 ? 1024 * 1024 : random.nextInt(4096);
					default -> random.nextInt(300);
				};
				byte[] payload = new byte[length];
				random.nextBytes(payload);
				// Every frame type the protocol has, and types it does not: the reader routes by header, it does not
				// judge, and refusing an unknown type is the dispatcher's decision.
				Frame frame = new Frame(random.nextInt(256), random.nextInt(0x10000), payload);
				sent.add(frame);
				wire.appendBuffer(Frames.frame(frame.type(), frame.streamId(), Buffer.buffer(payload)));
			}

			List<Frame> received = new ArrayList<>();
			List<WireFormatException> malformed = new ArrayList<>();
			FakeStream stream = new FakeStream();
			FrameReader.attach(stream, (type, id, payload) -> received.add(new Frame(type, id, payload.getBytes())),
				malformed::add);
			int position = 0;
			while (position < wire.length()) {
				// Chunks from one byte to several frames long, so headers and payloads are split at every kind of place.
				int chunk = switch (random.nextInt(4)) {
					case 0 -> 1 + random.nextInt(8);
					case 1 -> 1 + random.nextInt(Frames.HEADER_BYTES * 2);
					case 2 -> 1 + random.nextInt(70_000);
					default -> wire.length();
				};
				int end = Math.min(wire.length(), position + chunk);
				stream.deliver(wire.slice(position, end).copy());
				position = end;
			}

			assertTrue(malformed.isEmpty(), "seed " + seed + ": " + malformed);
			assertEquals(sent.size(), received.size(), "seed " + seed);
			for (int i = 0; i < sent.size(); i++) {
				assertEquals(sent.get(i).type(), received.get(i).type(), "seed " + seed + " frame " + i);
				assertEquals(sent.get(i).streamId(), received.get(i).streamId(), "seed " + seed + " frame " + i);
				assertArrayEquals(sent.get(i).payload(), received.get(i).payload(), "seed " + seed + " frame " + i);
			}
		}
	}

	@Test
	public void testAFrameOfExactlyTheMaximumIsAcceptedAndOneByteMoreIsNot() {
		List<Integer> lengths = new ArrayList<>();
		List<WireFormatException> malformed = new ArrayList<>();
		FakeStream stream = new FakeStream();
		FrameReader.attach(stream, (type, id, payload) -> lengths.add(payload.length()), malformed::add);
		stream.deliver(Frames.frame(Frames.ROWS, 1, Buffer.buffer(new byte[Frames.MAX_FRAME_BYTES])));
		assertEquals(List.of(Frames.MAX_FRAME_BYTES), lengths);
		assertTrue(malformed.isEmpty());

		assertThrows(WireFormatException.class, () -> Frames.header(Frames.ROWS, 1, Frames.MAX_FRAME_BYTES + 1),
			"the writer refuses what the reader would");
		stream.deliver(Buffer.buffer().appendIntLE(Frames.MAX_FRAME_BYTES + 1).appendByte((byte) Frames.ROWS)
			.appendByte((byte) 0).appendShortLE((short) 1));
		assertEquals(1, malformed.size());
	}

	/** The per-header ceiling follows the connection's state: tight until the handshake is done, then the full limit. */
	@Test
	public void testTheCeilingIsConsultedAtEveryHeader() {
		int[] ceiling = { 64 };
		List<Integer> lengths = new ArrayList<>();
		List<WireFormatException> malformed = new ArrayList<>();
		FakeStream stream = new FakeStream();
		FrameReader.attach(stream, (type, id, payload) -> {
			lengths.add(payload.length());
			// The handshake completing inside the handler, as the server's WELCOME path does.
			ceiling[0] = Frames.MAX_FRAME_BYTES;
		}, malformed::add, () -> ceiling[0]);

		Buffer coalesced = Buffer.buffer()
			.appendBuffer(Frames.frame(Frames.HELLO, 0, Buffer.buffer(new byte[64])))
			.appendBuffer(Frames.frame(Frames.QUERY, 1, Buffer.buffer(new byte[100_000])));
		stream.deliver(coalesced);
		assertTrue(malformed.isEmpty(), "the second header is read under the raised ceiling: " + malformed);
		assertEquals(List.of(64, 100_000), lengths);

		ceiling[0] = 64;
		FakeStream tight = new FakeStream();
		List<WireFormatException> refused = new ArrayList<>();
		FrameReader.attach(tight, (type, id, payload) -> fail("nothing over the ceiling may arrive"), refused::add,
			() -> 64);
		tight.deliver(Frames.header(Frames.HELLO, 0, 65));
		assertEquals(1, refused.size(), "refused from the header, before a payload byte is buffered");
	}

	private static void fail(String message) {
		throw new AssertionError(message);
	}
}
