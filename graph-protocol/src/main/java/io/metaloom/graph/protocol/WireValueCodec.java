package io.metaloom.graph.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How one Cypher cell is written on the wire.
 *
 * <h2>Why this is not {@code ValueCodec}</h2>
 *
 * <p>
 * The storage engine already has a little-endian tag-byte codec, and mirroring its tag numbering is deliberate. Reusing
 * the class is not, for four reasons in increasing order of decisiveness:
 * </p>
 *
 * <ol>
 * <li>It lives in a package that {@code module-info.java} does not export. That it is reachable at all today is an
 * accident of nothing putting graph-core on the module path.
 * <li>It is a <em>storage</em> format, versioned with the store. Welding the wire to it means every wire change is a
 * store format bump and vice versa — two things that must version independently.
 * <li>It has no tags for the four types that dominate this wire — node, relationship, path, map — and four tags
 * ({@code BYTES}, {@code TIMESTAMP}, {@code UUID}, {@code VECTOR_F32}) that can never reach a query result, because
 * the query layer renders them as strings and lists first.
 * <li><b>It cannot encode what this wire has to carry.</b> {@code ValueCodec.encodeBytes} escapes anything over 255
 * bytes to the blob store — {@code blobs.store(data)}, a <em>write</em> — and throws {@code IllegalStateException}
 * when there is no blob store. Encoding a result onto a socket has neither one nor any business allocating one, so any
 * string over 255 bytes is simply unencodable. That settles it on its own; the other three merely agree.
 * </ol>
 *
 * <h2>The tag byte</h2>
 *
 * <p>
 * Same shape as the storage codec, {@code sizeClass:3 | type:5}, and the same numbers wherever the encoding is
 * <b>byte-identical</b> — so a reader of one can be checked against the other. Tags 5 to 9 are reserved and
 * <b>rejected</b> rather than skipped: a decoder meeting one has met a value this engine cannot produce, which means
 * either corruption or a version mismatch, and both deserve an error rather than a shrug.
 * </p>
 *
 * <table>
 * <caption>Tags</caption>
 * <tr><td>0 NULL</td><td>1 BOOL</td><td>2 INT (zigzag)</td><td>3 FLOAT</td><td>4 STRING</td></tr>
 * <tr><td>10 LIST</td><td>11 MAP</td><td>12 NODE</td><td>13 REL</td><td>14 PATH</td></tr>
 * </table>
 *
 * <p>
 * <b>Element ids are fixed 8 bytes, not {@code INT}.</b> A real id is packed with a kind in the high bits and lands
 * around 2^60, so a zigzag varint costs 9 or 10 bytes for every one of them — worse than fixed. Ordinary integer
 * property values are small and do benefit from zigzag, so those keep the size classes.
 * </p>
 *
 * <p>
 * Written on {@code byte[]} rather than on a Vert.x {@code Buffer} so that it can be fuzzed without a transport, and so
 * that this module stays free of anything the client would not otherwise need.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
public final class WireValueCodec {

	public static final int T_NULL = 0;
	public static final int T_BOOL = 1;
	public static final int T_INT = 2;
	public static final int T_FLOAT = 3;
	public static final int T_STRING = 4;
	public static final int T_LIST = 10;
	public static final int T_MAP = 11;
	public static final int T_NODE = 12;
	public static final int T_REL = 13;
	public static final int T_PATH = 14;

	/**
	 * How deep a value may nest before the decoder refuses.
	 *
	 * <p>
	 * The storage codec caps a list's <em>allocation</em> but recurses into nested lists without a depth limit, so a
	 * nested-list bomb is a {@code StackOverflowError} rather than a rejection. A frame arriving from a socket is
	 * exactly the untrusted input that would carry one.
	 * </p>
	 */
	public static final int MAX_DEPTH = 32;

	/** A relationship with no record: no id, no properties. The flag exists so the id field can be omitted. */
	public static final int REL_MATERIALISED = 1;

	private WireValueCodec() {
	}

	// ---------------------------------------------------------------- writing

	/** A growable little-endian byte sink. Little-endian explicitly, so a stream written on one machine reads on another. */
	public static final class Writer {

		private byte[] buf;
		private int pos;

		public Writer() {
			this(256);
		}

		public Writer(int initialCapacity) {
			this.buf = new byte[Math.max(16, initialCapacity)];
		}

		private void ensure(int extra) {
			if (pos + extra > buf.length) {
				int size = Math.max(buf.length * 2, pos + extra);
				byte[] grown = new byte[size];
				System.arraycopy(buf, 0, grown, 0, pos);
				buf = grown;
			}
		}

		public void u8(int value) {
			ensure(1);
			buf[pos++] = (byte) value;
		}

		public void i32(int value) {
			ensure(4);
			buf[pos++] = (byte) value;
			buf[pos++] = (byte) (value >>> 8);
			buf[pos++] = (byte) (value >>> 16);
			buf[pos++] = (byte) (value >>> 24);
		}

		public void i64(long value) {
			ensure(8);
			for (int i = 0; i < 8; i++) {
				buf[pos++] = (byte) (value >>> (i * 8));
			}
		}

		public void bytes(byte[] data) {
			ensure(data.length);
			System.arraycopy(data, 0, buf, pos, data.length);
			pos += data.length;
		}

		/** Unsigned LEB128, for counts and lengths that are usually small. */
		public void varint(long value) {
			long remaining = value;
			while ((remaining & ~0x7FL) != 0) {
				u8((int) ((remaining & 0x7F) | 0x80));
				remaining >>>= 7;
			}
			u8((int) remaining);
		}

		/**
		 * Overwrites four bytes already written.
		 *
		 * <p>
		 * For a count that is only known once the thing it counts has been written — a row batch, whose size is decided
		 * by the bytes it reached rather than by the rows it holds. The alternative is encoding into a scratch buffer
		 * and copying it after the count, which is a copy of every row on the hot path to avoid four bytes of
		 * bookkeeping.
		 * </p>
		 */
		public void patchI32(int offset, int value) {
			if (offset < 0 || offset > pos - 4) {
				throw new IndexOutOfBoundsException("Cannot patch 4 bytes at " + offset + " of " + pos + " written");
			}
			buf[offset] = (byte) value;
			buf[offset + 1] = (byte) (value >>> 8);
			buf[offset + 2] = (byte) (value >>> 16);
			buf[offset + 3] = (byte) (value >>> 24);
		}

		public int position() {
			return pos;
		}

		public void reset() {
			pos = 0;
		}

		public byte[] toArray() {
			byte[] out = new byte[pos];
			System.arraycopy(buf, 0, out, 0, pos);
			return out;
		}

		/** The backing array, valid to {@link #position()}. Avoids a copy on the hot path. */
		public byte[] array() {
			return buf;
		}
	}

	private static int tag(int type, int sizeClass) {
		return (sizeClass << 5) | type;
	}

	public static void encode(Writer w, Object value) {
		encode(w, value, 0);
	}

	private static void encode(Writer w, Object value, int depth) {
		if (depth > MAX_DEPTH) {
			throw new IllegalArgumentException("Value nests deeper than " + MAX_DEPTH);
		}
		switch (value) {
			case null -> w.u8(tag(T_NULL, 0));
			case Boolean b -> w.u8(tag(T_BOOL, b ? 1 : 0));
			case Long l -> encodeInt(w, l);
			case Integer i -> encodeInt(w, i.longValue());
			case Short s -> encodeInt(w, s.longValue());
			case Byte b -> encodeInt(w, b.longValue());
			case Double d -> {
				w.u8(tag(T_FLOAT, 1));
				w.i64(Double.doubleToRawLongBits(d));
			}
			case Float f -> {
				w.u8(tag(T_FLOAT, 0));
				w.i32(Float.floatToRawIntBits(f));
			}
			case String s -> encodeString(w, s);
			case List<?> list -> {
				w.u8(tag(T_LIST, 0));
				w.varint(list.size());
				for (Object element : list) {
					encode(w, element, depth + 1);
				}
			}
			case Map<?, ?> map -> {
				w.u8(tag(T_MAP, 0));
				w.varint(map.size());
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					encodeString(w, String.valueOf(entry.getKey()));
					encode(w, entry.getValue(), depth + 1);
				}
			}
			case WireNode node -> {
				w.u8(tag(T_NODE, 0));
				w.i64(node.id());
				w.varint(node.labels().size());
				for (String label : node.labels()) {
					encodeString(w, label);
				}
				encodeProperties(w, node.properties(), depth);
			}
			case WireRelationship rel -> encodeRelationship(w, rel, depth);
			case WirePath path -> {
				w.u8(tag(T_PATH, 0));
				w.varint(path.relationships().size());
				for (WireNode node : path.nodes()) {
					encode(w, node, depth + 1);
				}
				for (WireRelationship rel : path.relationships()) {
					encodeRelationship(w, rel, depth + 1);
				}
			}
			// Deliberately not a stringifying default. The JSON route turns an unknown object into its toString,
			// which silently changes a value's type; on a typed binary wire that is a wrong answer rather than an
			// ugly one, so it is a failure a differential test can catch.
			default -> throw new IllegalArgumentException(
				"No wire encoding for " + value.getClass().getName() + "; the query layer should have normalised it");
		}
	}

	private static void encodeRelationship(Writer w, WireRelationship rel, int depth) {
		w.u8(tag(T_REL, 0));
		boolean materialised = rel.id() != null;
		w.u8(materialised ? REL_MATERIALISED : 0);
		if (materialised) {
			w.i64(rel.id());
		}
		encodeString(w, rel.type());
		w.i64(rel.startId());
		w.i64(rel.endId());
		encodeProperties(w, rel.properties(), depth);
	}

	private static void encodeProperties(Writer w, Map<String, Object> properties, int depth) {
		w.varint(properties.size());
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			encodeString(w, entry.getKey());
			encode(w, entry.getValue(), depth + 1);
		}
	}

	private static void encodeInt(Writer w, long value) {
		long zig = (value << 1) ^ (value >> 63);
		if (Long.compareUnsigned(zig, 0xFFL) <= 0) {
			w.u8(tag(T_INT, 0));
			w.u8((int) zig);
		} else if (Long.compareUnsigned(zig, 0xFFFFFFFFL) <= 0) {
			w.u8(tag(T_INT, 2));
			w.i32((int) zig);
		} else {
			w.u8(tag(T_INT, 3));
			w.i64(zig);
		}
	}

	/** Public because the frame payloads are built out of the same self-describing values the rows are. */
	public static void encodeString(Writer w, String value) {
		byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
		if (utf8.length <= 0xFF) {
			w.u8(tag(T_STRING, 0));
			w.u8(utf8.length);
		} else {
			// Inline, always. Where the storage codec would escape to a blob this carries the bytes, which is the
			// whole reason this codec exists separately.
			w.u8(tag(T_STRING, 1));
			w.i32(utf8.length);
		}
		w.bytes(utf8);
	}

	// ---------------------------------------------------------------- reading

	/**
	 * A bounds-checked little-endian source.
	 *
	 * <p>
	 * Every read is checked against the remaining input <b>before</b> anything is allocated from a length read out of
	 * that input. That is the property the hostile-input oracle asserts, and the one seven defects in the storage
	 * parsers were about.
	 * </p>
	 */
	public static final class Reader {

		private final byte[] buf;
		private final int limit;
		private int pos;

		public Reader(byte[] buf) {
			this(buf, 0, buf.length);
		}

		public Reader(byte[] buf, int offset, int length) {
			this.buf = buf;
			this.pos = offset;
			this.limit = offset + length;
		}

		private void need(int count) {
			// `count > limit - pos`, not `pos + count > limit`. The obvious form overflows for a count near
			// Integer.MAX_VALUE — the sum goes negative, the check passes, and the allocation behind it proceeds.
			// The hostile-input oracle found this with a string claiming two gigabytes inside a five byte frame.
			if (count < 0 || count > limit - pos) {
				throw new WireFormatException("Frame claims " + count + " bytes with " + (limit - pos) + " remaining");
			}
		}

		public int u8() {
			need(1);
			return buf[pos++] & 0xFF;
		}

		public int i32() {
			need(4);
			return (buf[pos++] & 0xFF) | (buf[pos++] & 0xFF) << 8 | (buf[pos++] & 0xFF) << 16 | (buf[pos++] & 0xFF) << 24;
		}

		public long i64() {
			need(8);
			long value = 0;
			for (int i = 0; i < 8; i++) {
				value |= (long) (buf[pos++] & 0xFF) << (i * 8);
			}
			return value;
		}

		public long varint() {
			long value = 0;
			int shift = 0;
			while (true) {
				if (shift > 63) {
					throw new WireFormatException("Varint longer than 64 bits");
				}
				int b = u8();
				value |= (long) (b & 0x7F) << shift;
				if ((b & 0x80) == 0) {
					return value;
				}
				shift += 7;
			}
		}

		public byte[] bytes(int count) {
			need(count);
			byte[] out = new byte[count];
			System.arraycopy(buf, pos, out, 0, count);
			pos += count;
			return out;
		}

		public int remaining() {
			return limit - pos;
		}

		public int position() {
			return pos;
		}

		/**
		 * A count that is about to drive an allocation.
		 *
		 * <p>
		 * A list claiming two billion elements inside a twelve-byte frame must not become a two-billion-entry
		 * ArrayList before the first element fails to decode. One byte is the floor for any element, so the remaining
		 * input is the bound.
		 * </p>
		 */
		private int countWithin(long claimed) {
			if (claimed < 0 || claimed > remaining()) {
				throw new WireFormatException("A count of " + claimed + " cannot fit in " + remaining() + " bytes");
			}
			return (int) claimed;
		}
	}

	public static Object decode(Reader r) {
		return decode(r, 0);
	}

	/**
	 * A decoded value where a particular type was required.
	 *
	 * <p>
	 * Corrupt input decodes to whatever its bytes happen to say, so a label position can yield a Long and a path
	 * element can yield a relationship. A plain cast turns that into a {@code ClassCastException} escaping the
	 * decoder, which a caller cannot distinguish from a bug in the decoder itself. The oracle found three of these.
	 * </p>
	 */
	public static <T> T expect(Object value, Class<T> type, String where) {
		if (!type.isInstance(value)) {
			throw new WireFormatException("Expected " + type.getSimpleName() + " for " + where + " but found "
				+ (value == null ? "null" : value.getClass().getSimpleName()));
		}
		return type.cast(value);
	}

	private static Object decode(Reader r, int depth) {
		if (depth > MAX_DEPTH) {
			throw new WireFormatException("Value nests deeper than " + MAX_DEPTH);
		}
		int tag = r.u8();
		int type = tag & 0x1F;
		int sizeClass = tag >>> 5;
		switch (type) {
			case T_NULL:
				return null;
			case T_BOOL:
				return sizeClass != 0;
			case T_INT:
				return decodeInt(r, sizeClass);
			case T_FLOAT:
				return sizeClass == 0 ? (Object) Float.intBitsToFloat(r.i32()) : (Object) Double.longBitsToDouble(r.i64());
			case T_STRING:
				return decodeString(r, sizeClass);
			case T_LIST: {
				int count = r.countWithin(r.varint());
				List<Object> list = new ArrayList<>(Math.min(count, 64));
				for (int i = 0; i < count; i++) {
					list.add(decode(r, depth + 1));
				}
				return list;
			}
			case T_MAP:
				return decodeMap(r, depth);
			case T_NODE:
				return decodeNode(r, depth);
			case T_REL:
				return decodeRelationship(r, depth);
			case T_PATH: {
				int hops = r.countWithin(r.varint());
				List<WireNode> nodes = new ArrayList<>(Math.min(hops + 1, 64));
				for (int i = 0; i <= hops; i++) {
					nodes.add(expect(decode(r, depth + 1), WireNode.class, "a path node"));
				}
				List<WireRelationship> rels = new ArrayList<>(Math.min(hops, 64));
				for (int i = 0; i < hops; i++) {
					rels.add(expect(decode(r, depth + 1), WireRelationship.class, "a path relationship"));
				}
				return new WirePath(nodes, rels);
			}
			default:
				// Includes tags 5 to 9, which are reserved. Meeting one means corruption or a version mismatch.
				throw new WireFormatException("Unknown or reserved value tag " + type);
		}
	}

	private static WireNode decodeNode(Reader r, int depth) {
		long id = r.i64();
		int labelCount = r.countWithin(r.varint());
		List<String> labels = new ArrayList<>(Math.min(labelCount, 16));
		for (int i = 0; i < labelCount; i++) {
			labels.add(decodeKey(r, "a node label"));
		}
		return new WireNode(id, labels, decodeProperties(r, depth));
	}

	private static WireRelationship decodeRelationship(Reader r, int depth) {
		int flags = r.u8();
		Long id = (flags & REL_MATERIALISED) != 0 ? r.i64() : null;
		String type = decodeKey(r, "a relationship type");
		long startId = r.i64();
		long endId = r.i64();
		return new WireRelationship(id, type, startId, endId, decodeProperties(r, depth));
	}

	private static Map<String, Object> decodeProperties(Reader r, int depth) {
		int count = r.countWithin(r.varint());
		if (count == 0) {
			return Map.of();
		}
		Map<String, Object> properties = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			properties.put(decodeKey(r, "a property key"), decode(r, depth + 1));
		}
		return properties;
	}

	private static Map<String, Object> decodeMap(Reader r, int depth) {
		int count = r.countWithin(r.varint());
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			map.put(decodeKey(r, "a map key"), decode(r, depth + 1));
		}
		return map;
	}

	/**
	 * A string where only a string may stand: a label, a relationship type, a key.
	 *
	 * <p>
	 * Not a level of nesting, so no depth, exactly as the encoder writes it with {@code encodeString}. Decoding it as a
	 * value one level down made a node or relationship at exactly {@link #MAX_DEPTH} encodable and undecodable.
	 * </p>
	 */
	private static String decodeKey(Reader r, String where) {
		int tag = r.u8();
		if ((tag & 0x1F) != T_STRING) {
			throw new WireFormatException("Expected String for " + where + " but found value tag " + (tag & 0x1F));
		}
		return decodeString(r, tag >>> 5);
	}

	private static Long decodeInt(Reader r, int sizeClass) {
		long zig = switch (sizeClass) {
			case 0 -> r.u8();
			case 2 -> r.i32() & 0xFFFFFFFFL;
			case 3 -> r.i64();
			default -> throw new WireFormatException("Unknown integer size class " + sizeClass);
		};
		return (zig >>> 1) ^ -(zig & 1);
	}

	private static String decodeString(Reader r, int sizeClass) {
		int length = switch (sizeClass) {
			case 0 -> r.u8();
			case 1 -> r.i32();
			default -> throw new WireFormatException("Unknown string size class " + sizeClass);
		};
		if (length < 0) {
			throw new WireFormatException("Negative string length " + length);
		}
		return new String(r.bytes(length), StandardCharsets.UTF_8);
	}
}
