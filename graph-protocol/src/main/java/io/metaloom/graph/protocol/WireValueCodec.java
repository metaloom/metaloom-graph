package io.metaloom.graph.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAmount;
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
 * <tr><td>15 TEMPORAL</td><td colspan="4">the size class says which of the seven kinds</td></tr>
 * </table>
 *
 * <h2>Temporal values: one tag, the size class is the kind</h2>
 *
 * <table>
 * <caption>Tag 15</caption>
 * <tr><td>0 DATE</td><td>{@code LocalDate}: i64 epoch day</td></tr>
 * <tr><td>1 LOCAL TIME</td><td>{@code LocalTime}: i64 nano of day</td></tr>
 * <tr><td>2 TIME</td><td>{@code OffsetTime}: i64 nano of day, i32 offset seconds</td></tr>
 * <tr><td>3 LOCAL DATETIME</td><td>{@code LocalDateTime}: i64 epoch second read as UTC, i32 nano</td></tr>
 * <tr><td>4 DATETIME, offset</td><td>{@code ZonedDateTime} whose zone is a {@code ZoneOffset}: i64 instant epoch
 * second, i32 nano, i32 offset seconds</td></tr>
 * <tr><td>5 DATETIME, region</td><td>{@code ZonedDateTime} with a region zone: i64 instant epoch second, i32 nano,
 * the zone id as a string value</td></tr>
 * <tr><td>6 DURATION</td><td>any {@code TemporalAmount} in months, days, seconds and nanos: i64, i64, i64, i32</td></tr>
 * </table>
 *
 * <p>
 * Fifteen because it is the first number free in this codec, the storage codec and the dump codec alike. A region
 * datetime carries its instant and its zone and no offset: the offset follows from the zone's rules, and if the two
 * ends' time-zone data disagree, keeping the instant is keeping the value. Decoding checks every field's range and
 * refuses a zone id that is unknown, is a fixed offset (kind 4 is the one spelling of that), or is not in its canonical
 * form, so one value has one encoding. A decoded duration is a package-private {@link TemporalAmount} with an
 * ISO-8601 {@code toString}.
 * </p>
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
	/** Not public: the API is locked, and a constant is API. */
	static final int T_TEMPORAL = 15;

	static final int K_DATE = 0;
	static final int K_LOCAL_TIME = 1;
	static final int K_TIME = 2;
	static final int K_LOCAL_DATETIME = 3;
	static final int K_DATETIME_OFFSET = 4;
	static final int K_DATETIME_REGION = 5;
	static final int K_DURATION = 6;

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
			case LocalDate date -> {
				w.u8(tag(T_TEMPORAL, K_DATE));
				w.i64(date.toEpochDay());
			}
			case LocalTime time -> {
				w.u8(tag(T_TEMPORAL, K_LOCAL_TIME));
				w.i64(time.toNanoOfDay());
			}
			case OffsetTime time -> {
				w.u8(tag(T_TEMPORAL, K_TIME));
				w.i64(time.toLocalTime().toNanoOfDay());
				w.i32(time.getOffset().getTotalSeconds());
			}
			case LocalDateTime dateTime -> {
				w.u8(tag(T_TEMPORAL, K_LOCAL_DATETIME));
				w.i64(dateTime.toEpochSecond(ZoneOffset.UTC));
				w.i32(dateTime.getNano());
			}
			case ZonedDateTime dateTime -> {
				boolean offset = dateTime.getZone() instanceof ZoneOffset;
				w.u8(tag(T_TEMPORAL, offset ? K_DATETIME_OFFSET : K_DATETIME_REGION));
				w.i64(dateTime.toEpochSecond());
				w.i32(dateTime.getNano());
				if (offset) {
					w.i32(dateTime.getOffset().getTotalSeconds());
				} else {
					encodeString(w, dateTime.getZone().getId());
				}
			}
			case TemporalAmount amount -> {
				WireDuration duration = WireDuration.from(amount);
				w.u8(tag(T_TEMPORAL, K_DURATION));
				w.i64(duration.months());
				w.i64(duration.days());
				w.i64(duration.seconds());
				w.i32(duration.nanos());
			}
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

	/**
	 * Public because the frame payloads are built out of the same self-describing values the rows are.
	 *
	 * @throws IllegalArgumentException
	 *             for a string holding a surrogate without its pair. {@code String.getBytes(UTF_8)} writes {@code '?'}
	 *             for one, so {@code "\uD800"} and {@code "?"} used to leave as the same bytes and a parameter arrived
	 *             as a different string with nothing to say so. Refused, like a type with no tag, and for the reason
	 *             the storage codec refuses it. {@link #wellFormed} is for text that must get through regardless
	 */
	public static void encodeString(Writer w, String value) {
		byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
		// The JDK writes a lone surrogate as '?', so an encoding with no '?' in it had none. Scanning the bytes for one
		// is a tenth of the cost of walking the chars, and a 2 KiB string cell is common enough on this wire that the
		// walk on every string halved large-row throughput. Only an encoding that does hold a '?' is walked.
		if (holdsQuestionMark(utf8)) {
			int lone = loneSurrogate(value);
			if (lone >= 0) {
				throw new UnpairedSurrogateException(lone);
			}
		}
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

	/**
	 * A string that has no UTF-8 encoding because it holds a surrogate without its pair.
	 *
	 * <p>
	 * Its own type, beside the plain {@link IllegalArgumentException} for a type the wire has no tag for, because the
	 * two have different authors: an untagged type is the server's own conversion missing a case, and a lone surrogate
	 * is a value a query made — {@code RETURN '\\uD800'} does — and is the query's problem to fix.
	 * </p>
	 */
	public static final class UnpairedSurrogateException extends IllegalArgumentException {

		private static final long serialVersionUID = 1L;

		UnpairedSurrogateException(int index) {
			super("String holds a lone surrogate at index " + index + ", which has no UTF-8 encoding");
		}
	}

	private static final java.lang.invoke.VarHandle LONGS = java.lang.invoke.MethodHandles
		.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

	private static final long QUESTION_MARKS = 0x3F3F3F3F3F3F3F3FL;
	private static final long LOW_BITS = 0x0101010101010101L;
	private static final long HIGH_BITS = 0x8080808080808080L;

	/**
	 * Whether any byte is {@code '?'}, eight bytes at a time.
	 *
	 * <p>
	 * The usual has-zero-byte test on the bytes XORed with {@code '?'}: a byte that was {@code '?'} becomes zero, and
	 * {@code (v - 0x01..) & ~v & 0x80..} is non-zero exactly when some byte of {@code v} is. A byte-at-a-time loop with
	 * an early exit is not vectorised, and cost a measurable slice of large-row throughput on its own.
	 * </p>
	 */
	private static boolean holdsQuestionMark(byte[] utf8) {
		int i = 0;
		for (int end = utf8.length - 7; i < end; i += 8) {
			long v = (long) LONGS.get(utf8, i) ^ QUESTION_MARKS;
			if (((v - LOW_BITS) & ~v & HIGH_BITS) != 0) {
				return true;
			}
		}
		for (; i < utf8.length; i++) {
			if (utf8[i] == '?') {
				return true;
			}
		}
		return false;
	}

	/** @return the index of the first surrogate without its pair, or -1 */
	private static int loneSurrogate(String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!Character.isSurrogate(c)) {
				continue;
			}
			if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
				i++;
				continue;
			}
			return i;
		}
		return -1;
	}

	/**
	 * The string with every lone surrogate replaced by U+FFFD, so that it can be encoded.
	 *
	 * <p>
	 * For text the server writes about a failure — an error message may quote the query that caused it — where refusing
	 * to encode would leave a stream with no terminal frame and its client waiting for ever. Never for a value.
	 * </p>
	 */
	public static String wellFormed(String value) {
		if (loneSurrogate(value) < 0) {
			return value;
		}
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
				out.append(c).append(value.charAt(++i));
			} else {
				out.append(Character.isSurrogate(c) ? '�' : c);
			}
		}
		return out.toString();
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

		/**
		 * {@code count} bytes of strict UTF-8, decoded straight out of the frame.
		 *
		 * <p>
		 * Straight out, rather than through {@link #bytes}: that copies the bytes only for the {@code String}
		 * constructor to copy them again, which is two allocations per string cell on the client's hot path.
		 * </p>
		 *
		 * <p>
		 * Strict, because a malformed sequence is either corruption or a peer that is not this protocol, and decoding it
		 * to U+FFFD hands the caller a different string than the one that was sent. The JDK's lenient decoder stays the
		 * fast path; only a result that contains a replacement character — which is all a malformed input can produce,
		 * and which real text almost never contains — is checked again by a decoder that reports.
		 * </p>
		 */
		public String utf8(int count) {
			need(count);
			String decoded = new String(buf, pos, count, StandardCharsets.UTF_8);
			if (decoded.indexOf('�') >= 0) {
				try {
					StandardCharsets.UTF_8.newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(buf, pos, count));
				} catch (CharacterCodingException e) {
					throw new WireFormatException("A string of " + count + " bytes is not UTF-8");
				}
			}
			pos += count;
			return decoded;
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
			case T_TEMPORAL:
				return decodeTemporal(r, sizeClass);
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

	/**
	 * One temporal value, every field checked.
	 *
	 * <p>
	 * {@code java.time} already refuses most out-of-range fields with a {@link DateTimeException}; that is turned into
	 * the codec's own exception so a caller sees one failure type for hostile input. The nanosecond fields are
	 * checked here rather than left to {@code Instant.ofEpochSecond}, which would quietly carry an out-of-range one into
	 * the seconds and give one value two encodings.
	 * </p>
	 */
	private static Object decodeTemporal(Reader r, int kind) {
		try {
			return switch (kind) {
				case K_DATE -> LocalDate.ofEpochDay(r.i64());
				case K_LOCAL_TIME -> LocalTime.ofNanoOfDay(r.i64());
				case K_TIME -> {
					LocalTime time = LocalTime.ofNanoOfDay(r.i64());
					yield OffsetTime.of(time, ZoneOffset.ofTotalSeconds(r.i32()));
				}
				case K_LOCAL_DATETIME -> {
					long seconds = r.i64();
					yield LocalDateTime.ofEpochSecond(seconds, nanoOfSecond(r.i32()), ZoneOffset.UTC);
				}
				case K_DATETIME_OFFSET -> {
					Instant instant = instant(r);
					yield ZonedDateTime.ofInstant(instant, ZoneOffset.ofTotalSeconds(r.i32()));
				}
				case K_DATETIME_REGION -> {
					Instant instant = instant(r);
					yield ZonedDateTime.ofInstant(instant, region(decodeKey(r, "a time zone")));
				}
				case K_DURATION -> {
					long months = r.i64();
					long days = r.i64();
					long seconds = r.i64();
					yield new WireDuration(months, days, seconds, nanoOfSecond(r.i32()));
				}
				default -> throw new WireFormatException("Unknown temporal kind " + kind);
			};
		} catch (DateTimeException e) {
			throw new WireFormatException("Temporal value out of range: " + e.getMessage());
		}
	}

	private static Instant instant(Reader r) {
		long seconds = r.i64();
		return Instant.ofEpochSecond(seconds, nanoOfSecond(r.i32()));
	}

	private static int nanoOfSecond(int nanos) {
		if (nanos < 0 || nanos >= 1_000_000_000) {
			throw new WireFormatException("Nanosecond field " + nanos + " outside [0, 999999999]");
		}
		return nanos;
	}

	private static ZoneId region(String id) {
		ZoneId zone = ZoneId.of(id);
		if (zone instanceof ZoneOffset) {
			throw new WireFormatException("Zone id '" + id + "' is a fixed offset, which has its own kind");
		}
		if (!zone.getId().equals(id)) {
			throw new WireFormatException("Zone id '" + id + "' is not in its canonical form '" + zone.getId() + "'");
		}
		return zone;
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
		return r.utf8(length);
	}
}
