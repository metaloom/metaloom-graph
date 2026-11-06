package io.metaloom.graph.core.store.prop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.store.blob.BlobStore;
import io.metaloom.graph.core.store.vec.VectorStore;
import io.metaloom.graph.core.util.VarInt;

/**
 * Typed property value encoding.
 * <p>
 * The tag byte is {@code sizeClass:3 | type:5}. Values are stored in the smallest representation that is lossless, so
 * a boolean costs one byte in total and a small integer costs two. Anything larger than 255 bytes escapes to the blob
 * store.
 * <p>
 * Storing values as text, as the proof of concept did, is both larger and lossy: every integer, timestamp and boolean
 * went through decimal formatting and came back as a String.
 *
 * @see <a href="../../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 4
 */
public final class ValueCodec {

	public static final int T_NULL = 0;
	public static final int T_BOOL = 1;
	public static final int T_INT = 2;
	public static final int T_FLOAT = 3;
	public static final int T_STRING = 4;
	public static final int T_BYTES = 5;
	public static final int T_TIMESTAMP = 6;
	public static final int T_UUID = 7;
	public static final int T_ELEMENT_REF = 8;
	public static final int T_VECTOR_F32 = 9;
	public static final int T_LIST = 10;
	/**
	 * A vector held in a {@code VEC} record rather than a blob: {@code i32 dim}, {@code i64 vecId}.
	 * <p>
	 * Same payload shape as {@link #T_VECTOR_F32}, deliberately — the two differ only in which store owns the bytes,
	 * so every sweep that walks an id inside a payload treats them identically. Which one a vector gets is decided at
	 * write time by {@code VectorStore.accepts}: the fixed-size record can only hold the one dimension the store was
	 * created for, and everything else still escapes to a blob.
	 */
	public static final int T_VECTOR_REF = 11;
	/**
	 * A Cypher temporal value; the size class is the kind ({@code K_*}). Part of store format version 4: it was added without a version bump while no store
	 * existed outside tests, so a build without the tag fails on the value instead of refusing the store.
	 * <ul>
	 * <li>{@link #K_DATE}: {@code i64 epochDay}</li>
	 * <li>{@link #K_LOCAL_TIME}: {@code i64 nanoOfDay}</li>
	 * <li>{@link #K_TIME}: {@code i64 nanoOfDay, i32 offsetSeconds}</li>
	 * <li>{@link #K_LOCAL_DATE_TIME}: {@code i64 epochSecond} (as if UTC), {@code i32 nano}</li>
	 * <li>{@link #K_DATE_TIME_OFFSET}: {@code i64 epochSecond, i32 nano, i32 offsetSeconds}</li>
	 * <li>{@link #K_DATE_TIME_ZONE}: {@code i64 epochSecond, i32 nano, u8 length, utf8 zoneId}</li>
	 * <li>{@link #K_DURATION}: {@code i64 months, i64 days, i64 seconds, i32 nanos}</li>
	 * </ul>
	 * An {@link OffsetDateTime} is written as {@link #K_DATE_TIME_OFFSET} and reads back as a {@link ZonedDateTime}
	 * whose zone is that offset; any {@link TemporalAmount} reads back as a {@link StoredDuration}.
	 */
	public static final int T_TEMPORAL = 12;

	public static final int K_DATE = 0;
	public static final int K_LOCAL_TIME = 1;
	public static final int K_TIME = 2;
	public static final int K_LOCAL_DATE_TIME = 3;
	public static final int K_DATE_TIME_OFFSET = 4;
	public static final int K_DATE_TIME_ZONE = 5;
	public static final int K_DURATION = 6;

	private static final long NANOS_PER_DAY = 86_400_000_000_000L;
	private static final int MAX_OFFSET_SECONDS = 18 * 3600;

	/** Values whose inline form would exceed this escape to the blob store. */
	public static final int MAX_INLINE_BYTES = 255;

	private ValueCodec() {
	}

	// ---------------------------------------------------------------- writer

	/** A growable little-endian byte sink. */
	public static final class Writer {

		private byte[] buf;
		private int pos;

		public Writer() {
			this(64);
		}

		public Writer(int initialCapacity) {
			this.buf = new byte[Math.max(16, initialCapacity)];
		}

		private void ensure(int extra) {
			if (pos + extra > buf.length) {
				buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + extra));
			}
		}

		public void u8(int value) {
			ensure(1);
			buf[pos++] = (byte) value;
		}

		public void u16(int value) {
			ensure(2);
			buf[pos++] = (byte) value;
			buf[pos++] = (byte) (value >>> 8);
		}

		public void u24(int value) {
			ensure(3);
			buf[pos++] = (byte) value;
			buf[pos++] = (byte) (value >>> 8);
			buf[pos++] = (byte) (value >>> 16);
		}

		public void i32(int value) {
			ensure(4);
			for (int i = 0; i < 4; i++) {
				buf[pos++] = (byte) (value >>> (8 * i));
			}
		}

		public void i64(long value) {
			ensure(8);
			for (int i = 0; i < 8; i++) {
				buf[pos++] = (byte) (value >>> (8 * i));
			}
		}

		public void varint(long value) {
			ensure(VarInt.sizeOf(value));
			pos = VarInt.write(buf, pos, value);
		}

		public void bytes(byte[] data) {
			ensure(data.length);
			System.arraycopy(data, 0, buf, pos, data.length);
			pos += data.length;
		}

		public int position() {
			return pos;
		}

		public byte[] toArray() {
			return Arrays.copyOf(buf, pos);
		}
	}

	/** A cursor over an encoded byte array. */
	public static final class Reader {

		private final byte[] buf;
		private final int limit;
		private int pos;

		public Reader(byte[] buf, int offset, int limit) {
			this.buf = buf;
			this.pos = offset;
			this.limit = limit;
		}

		private void require(int n) {
			if (pos + n > limit) {
				throw new IllegalArgumentException("Truncated property value at offset " + pos);
			}
		}

		public int u8() {
			require(1);
			return buf[pos++] & 0xFF;
		}

		public int u16() {
			require(2);
			return (buf[pos++] & 0xFF) | ((buf[pos++] & 0xFF) << 8);
		}

		public int u24() {
			require(3);
			return (buf[pos++] & 0xFF) | ((buf[pos++] & 0xFF) << 8) | ((buf[pos++] & 0xFF) << 16);
		}

		public int i32() {
			require(4);
			int v = 0;
			for (int i = 0; i < 4; i++) {
				v |= (buf[pos++] & 0xFF) << (8 * i);
			}
			return v;
		}

		public long i64() {
			require(8);
			long v = 0;
			for (int i = 0; i < 8; i++) {
				v |= (long) (buf[pos++] & 0xFF) << (8 * i);
			}
			return v;
		}

		public long varint() {
			long[] out = new long[1];
			pos = VarInt.read(buf, pos, limit, out);
			return out[0];
		}

		public byte[] bytes(int length) {
			require(length);
			byte[] result = Arrays.copyOfRange(buf, pos, pos + length);
			pos += length;
			return result;
		}

		/**
		 * {@code length} bytes decoded as UTF-8, straight out of the buffer. The same decoding as
		 * {@code new String(bytes(length), UTF_8)} — malformed input is replaced, not rejected — without the
		 * intermediate copy.
		 */
		public String utf8(int length) {
			require(length);
			String result = new String(buf, pos, length, StandardCharsets.UTF_8);
			pos += length;
			return result;
		}

		/** Advances past {@code n} bytes without materialising them. */
		public void skip(int n) {
			require(n);
			pos += n;
		}

		public int position() {
			return pos;
		}

		public int limit() {
			return limit;
		}

		public boolean hasRemaining() {
			return pos < limit;
		}
	}

	// ---------------------------------------------------------------- encode

	public static void encode(Writer w, Object value, BlobStore blobs) throws IOException {
		encode(w, value, blobs, null, ElementId.NULL);
	}

	/**
	 * @param vectors
	 *            the store's vector records, or null when it has none. A {@code float[]} of exactly that store's
	 *            dimension is written there; every other vector escapes to a blob as before.
	 */
	public static void encode(Writer w, Object value, BlobStore blobs, VectorStore vectors, long ownerId)
		throws IOException {
		switch (value) {
			case null -> w.u8(tag(T_NULL, 0));
			case Boolean b -> w.u8(tag(T_BOOL, b ? 1 : 0));
			case Byte b -> encodeInt(w, b.longValue());
			case Short s -> encodeInt(w, s.longValue());
			case Integer i -> encodeInt(w, i.longValue());
			case Long l -> encodeInt(w, l.longValue());
			case Float f -> {
				w.u8(tag(T_FLOAT, 0));
				w.i32(Float.floatToRawIntBits(f));
			}
			case Double d -> {
				w.u8(tag(T_FLOAT, 1));
				w.i64(Double.doubleToRawLongBits(d));
			}
			case String s -> encodeBytes(w, T_STRING, utf8(s), blobs);
			case byte[] b -> encodeBytes(w, T_BYTES, b, blobs);
			case Instant i -> {
				w.u8(tag(T_TIMESTAMP, 0));
				w.i64(micros(i));
			}
			case UUID u -> {
				w.u8(tag(T_UUID, 0));
				w.i64(u.getMostSignificantBits());
				w.i64(u.getLeastSignificantBits());
			}
			case ElementRef r -> {
				w.u8(tag(T_ELEMENT_REF, 0));
				w.i64(r.id());
			}
			// Two homes for a vector, and the store's fixed record can only hold the one dimension it was created
			// for. Everything else — a different length, or a store with no vector storage at all — still escapes
			// to a blob, so both paths stay live and both stay tested.
			case float[] vector when vectors != null && vectors.accepts(vector) -> {
				w.u8(tag(T_VECTOR_REF, 0));
				w.i32(vector.length);
				w.i64(vectors.store(vector, ownerId));
			}
			case float[] vector -> {
				if (blobs == null) {
					throw new IllegalStateException("Vectors require a blob store");
				}
				byte[] raw = new byte[vector.length * 4];
				for (int i = 0; i < vector.length; i++) {
					int bits = Float.floatToRawIntBits(vector[i]);
					raw[i * 4] = (byte) bits;
					raw[i * 4 + 1] = (byte) (bits >>> 8);
					raw[i * 4 + 2] = (byte) (bits >>> 16);
					raw[i * 4 + 3] = (byte) (bits >>> 24);
				}
				w.u8(tag(T_VECTOR_F32, 0));
				w.i32(vector.length);
				w.i64(blobs.store(raw));
			}
			case LocalDate d -> {
				w.u8(tag(T_TEMPORAL, K_DATE));
				w.i64(d.toEpochDay());
			}
			case LocalTime t -> {
				w.u8(tag(T_TEMPORAL, K_LOCAL_TIME));
				w.i64(t.toNanoOfDay());
			}
			case OffsetTime t -> {
				w.u8(tag(T_TEMPORAL, K_TIME));
				w.i64(t.toLocalTime().toNanoOfDay());
				w.i32(t.getOffset().getTotalSeconds());
			}
			case LocalDateTime t -> {
				w.u8(tag(T_TEMPORAL, K_LOCAL_DATE_TIME));
				w.i64(t.toEpochSecond(ZoneOffset.UTC));
				w.i32(t.getNano());
			}
			case OffsetDateTime t -> {
				w.u8(tag(T_TEMPORAL, K_DATE_TIME_OFFSET));
				w.i64(t.toEpochSecond());
				w.i32(t.getNano());
				w.i32(t.getOffset().getTotalSeconds());
			}
			case ZonedDateTime t when t.getZone() instanceof ZoneOffset offset -> {
				w.u8(tag(T_TEMPORAL, K_DATE_TIME_OFFSET));
				w.i64(t.toEpochSecond());
				w.i32(t.getNano());
				w.i32(offset.getTotalSeconds());
			}
			case ZonedDateTime t -> {
				byte[] zone = t.getZone().getId().getBytes(StandardCharsets.UTF_8);
				if (zone.length > 255) {
					throw new IllegalArgumentException("Zone id " + t.getZone().getId() + " is longer than 255 bytes");
				}
				w.u8(tag(T_TEMPORAL, K_DATE_TIME_ZONE));
				w.i64(t.toEpochSecond());
				w.i32(t.getNano());
				w.u8(zone.length);
				w.bytes(zone);
			}
			case TemporalAmount amount -> {
				StoredDuration d = StoredDuration.of(amount);
				w.u8(tag(T_TEMPORAL, K_DURATION));
				w.i64(d.months());
				w.i64(d.days());
				w.i64(d.seconds());
				w.i32(d.nanos());
			}
			case List<?> list -> {
				w.u8(tag(T_LIST, 0));
				w.varint(list.size());
				for (Object element : list) {
					encode(w, element, blobs, vectors, ownerId);
				}
			}
			default -> throw new IllegalArgumentException(
				"Unsupported property value type: " + value.getClass().getName());
		}
	}

	/** Microseconds since the epoch, refusing an instant a long cannot hold rather than wrapping it into another. */
	private static long micros(Instant instant) {
		long seconds = instant.getEpochSecond();
		long micros = instant.getNano() / 1000L;
		try {
			if (seconds < 0 && micros > 0) {
				// As Instant.toEpochMilli does: borrow a second first, or the earliest representable microsecond
				// overflows in the multiplication although the sum fits.
				return Math.addExact(Math.multiplyExact(seconds + 1, 1_000_000L), micros - 1_000_000L);
			}
			return Math.addExact(Math.multiplyExact(seconds, 1_000_000L), micros);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Timestamp " + instant + " is outside the microsecond range a property "
				+ "can store (about 292,000 years either side of 1970)", e);
		}
	}

	/**
	 * Strict UTF-8. {@code String.getBytes} silently writes '?' for a lone surrogate, so two distinct strings would read
	 * back as one; checking first keeps the common path the JDK's fast encoder.
	 */
	public static byte[] utf8(String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!Character.isSurrogate(c)) {
				continue;
			}
			if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
				i++;
				continue;
			}
			throw new IllegalArgumentException("String holds a lone surrogate at index " + i
				+ ", which has no UTF-8 encoding");
		}
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static void encodeInt(Writer w, long value) {
		long zig = VarInt.zigZagEncode(value);
		// The zig-zag result is an unsigned quantity: Long.MAX_VALUE encodes to 0xFFFFFFFFFFFFFFFE, which is negative
		// as a signed long and would otherwise be mistaken for a one byte value.
		if (Long.compareUnsigned(zig, 0xFFL) <= 0) {
			w.u8(tag(T_INT, 0));
			w.u8((int) zig);
		} else if (Long.compareUnsigned(zig, 0xFFFFL) <= 0) {
			w.u8(tag(T_INT, 1));
			w.u16((int) zig);
		} else if (Long.compareUnsigned(zig, 0xFFFFFFFFL) <= 0) {
			w.u8(tag(T_INT, 2));
			w.i32((int) zig);
		} else {
			w.u8(tag(T_INT, 3));
			w.i64(zig);
		}
	}

	private static void encodeBytes(Writer w, int type, byte[] data, BlobStore blobs) throws IOException {
		if (data.length <= MAX_INLINE_BYTES) {
			w.u8(tag(type, 0));
			w.u8(data.length);
			w.bytes(data);
		} else {
			if (blobs == null) {
				throw new IllegalStateException("Values above " + MAX_INLINE_BYTES + " bytes require a blob store");
			}
			w.u8(tag(type, 1));
			w.i32(data.length);
			w.i64(blobs.store(data));
		}
	}

	private static int tag(int type, int sizeClass) {
		return (sizeClass << 5) | type;
	}

	// ---------------------------------------------------------------- decode

	/**
	 * Decodes one value.
	 *
	 * @param materialise
	 *            when false the value is parsed and skipped but not built, which the free path uses
	 * @param ownedIds
	 *            when non-null, every id this value owns — a blob extent chain head, or a vector record — is added to
	 *            it. The free path uses it to release them, and it has to see both kinds or a vector record leaks.
	 */
	public static Object decode(Reader r, BlobStore blobs, boolean materialise, List<Long> ownedIds) {
		return decode(r, blobs, null, materialise, ownedIds);
	}

	public static Object decode(Reader r, BlobStore blobs, VectorStore vectors, boolean materialise,
		List<Long> ownedIds) {
		int tagByte = r.u8();
		int type = tagByte & 0x1F;
		int sizeClass = (tagByte >>> 5) & 0x7;
		switch (type) {
			case T_NULL:
				return null;
			case T_BOOL:
				return sizeClass != 0;
			case T_INT: {
				long zig = switch (sizeClass) {
					case 0 -> r.u8();
					case 1 -> r.u16();
					case 2 -> r.i32() & 0xFFFFFFFFL;
					case 3 -> r.i64();
					default -> throw new IllegalArgumentException("Bad INT size class " + sizeClass);
				};
				return VarInt.zigZagDecode(zig);
			}
			case T_FLOAT:
				return sizeClass == 0
					? (Object) Float.intBitsToFloat(r.i32())
					: (Object) Double.longBitsToDouble(r.i64());
			case T_STRING: {
				if (sizeClass == 0 && materialise) {
					// Inline: decode in place rather than copying the bytes out first and then into the String.
					return r.utf8(r.u8());
				}
				byte[] data = readBytes(r, sizeClass, blobs, materialise, ownedIds);
				return materialise && data != null ? new String(data, StandardCharsets.UTF_8) : null;
			}
			case T_BYTES:
				return readBytes(r, sizeClass, blobs, materialise, ownedIds);
			case T_TIMESTAMP: {
				long micros = r.i64();
				return materialise
					? Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
						Math.floorMod(micros, 1_000_000L) * 1000L)
					: null;
			}
			case T_UUID: {
				long hi = r.i64();
				long lo = r.i64();
				return materialise ? new UUID(hi, lo) : null;
			}
			case T_ELEMENT_REF:
				return materialise ? new ElementRef(r.i64()) : skip(r, 8);
			case T_VECTOR_F32: {
				int dim = r.i32();
				long blobId = r.i64();
				if (ownedIds != null) {
					ownedIds.add(blobId);
				}
				if (!materialise) {
					return null;
				}
				byte[] raw = blobs.read(blobId);
				// The dimension comes off a data page and is therefore untrusted. Unchecked, a flipped byte makes this
				// `new float[dim]` a heap-exhaustion oracle for a value that is four bytes long, or walks off the end
				// of `raw` with an AIOOBE that says nothing about where the corruption is. The blob's own length is the
				// authority: it was written as exactly four bytes per component and its CRC has already been verified.
				if (dim < 0 || (long) dim * 4 != raw.length) {
					throw new PageCorruptException("A vector declares " + dim + " dimensions, which is "
						+ ((long) dim * 4) + " bytes, but its blob " + ElementId.toString(blobId) + " holds "
						+ raw.length);
				}
				float[] vector = new float[dim];
				for (int i = 0; i < dim; i++) {
					int bits = (raw[i * 4] & 0xFF) | ((raw[i * 4 + 1] & 0xFF) << 8)
						| ((raw[i * 4 + 2] & 0xFF) << 16) | ((raw[i * 4 + 3] & 0xFF) << 24);
					vector[i] = Float.intBitsToFloat(bits);
				}
				return vector;
			}
			case T_VECTOR_REF: {
				int dim = r.i32();
				long vectorId = r.i64();
				if (ownedIds != null) {
					ownedIds.add(vectorId);
				}
				if (!materialise) {
					return null;
				}
				if (vectors == null) {
					// The manifest refuses to open a vectorful store without vector storage, so reaching this means
					// the two disagree — which is corruption, not a configuration the caller can fix.
					throw new PageCorruptException("A property references vector record "
						+ ElementId.toString(vectorId) + " but this store has no vector storage");
				}
				if (dim != vectors.dimensions()) {
					throw new PageCorruptException("A property declares a " + dim
						+ " dimension vector in a store of " + vectors.dimensions());
				}
				return vectors.read(vectorId);
			}
			case T_TEMPORAL:
				if (!materialise) {
					skipTemporal(r, sizeClass);
					return null;
				}
				return decodeTemporal(r, sizeClass);
			case T_LIST: {
				int count = (int) r.varint();
				List<Object> list = materialise ? new ArrayList<>(Math.min(count, 1024)) : null;
				for (int i = 0; i < count; i++) {
					Object element = decode(r, blobs, vectors, materialise, ownedIds);
					if (list != null) {
						list.add(element);
					}
				}
				return list;
			}
			default:
				throw new IllegalArgumentException("Unknown property value type " + type);
		}
	}

	private static byte[] readBytes(Reader r, int sizeClass, BlobStore blobs, boolean materialise, List<Long> ownedIds) {
		if (sizeClass == 0) {
			int length = r.u8();
			if (!materialise) {
				// A single-key read skips every value ahead of its key; copying each one out was pure garbage.
				r.skip(length);
				return null;
			}
			return r.bytes(length);
		}
		r.i32(); // total length, informational
		long blobId = r.i64();
		if (ownedIds != null) {
			ownedIds.add(blobId);
		}
		return materialise ? blobs.read(blobId) : null;
	}

	private static Object skip(Reader r, int bytes) {
		r.skip(bytes);
		return null;
	}

	/** Advances past one {@link #T_TEMPORAL} payload of the given kind. */
	private static void skipTemporal(Reader r, int kind) {
		switch (kind) {
			case K_DATE, K_LOCAL_TIME -> r.skip(8);
			case K_TIME, K_LOCAL_DATE_TIME -> r.skip(12);
			case K_DATE_TIME_OFFSET -> r.skip(16);
			case K_DATE_TIME_ZONE -> {
				r.skip(12);
				r.skip(r.u8());
			}
			case K_DURATION -> r.skip(28);
			default -> throw new PageCorruptException("Unknown temporal kind " + kind + " in a property value");
		}
	}

	/**
	 * One {@link #T_TEMPORAL} payload. The bytes come off a data page and are untrusted: every range the
	 * {@code java.time} factories would reject with a {@link DateTimeException} is reported as corruption instead, so
	 * a flipped byte says where it is rather than surfacing as a date-parsing error.
	 */
	private static Object decodeTemporal(Reader r, int kind) {
		try {
			return switch (kind) {
				case K_DATE -> LocalDate.ofEpochDay(r.i64());
				case K_LOCAL_TIME -> LocalTime.ofNanoOfDay(nanoOfDay(r.i64()));
				case K_TIME -> {
					long nanoOfDay = nanoOfDay(r.i64());
					yield OffsetTime.of(LocalTime.ofNanoOfDay(nanoOfDay), offset(r.i32()));
				}
				case K_LOCAL_DATE_TIME -> {
					long epochSecond = r.i64();
					yield LocalDateTime.ofEpochSecond(epochSecond, nano(r.i32()), ZoneOffset.UTC);
				}
				case K_DATE_TIME_OFFSET -> {
					long epochSecond = r.i64();
					int nano = nano(r.i32());
					ZoneOffset offset = offset(r.i32());
					yield ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nano), offset);
				}
				case K_DATE_TIME_ZONE -> {
					long epochSecond = r.i64();
					int nano = nano(r.i32());
					String zoneId = new String(r.bytes(r.u8()), StandardCharsets.UTF_8);
					yield ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nano), ZoneId.of(zoneId));
				}
				case K_DURATION -> {
					long months = r.i64();
					long days = r.i64();
					long seconds = r.i64();
					yield new StoredDuration(months, days, seconds, nano(r.i32()));
				}
				default -> throw new PageCorruptException("Unknown temporal kind " + kind + " in a property value");
			};
		} catch (DateTimeException | ArithmeticException e) {
			throw new PageCorruptException("A temporal property value of kind " + kind + " is out of range: "
				+ e.getMessage());
		}
	}

	private static long nanoOfDay(long value) {
		if (value < 0 || value >= NANOS_PER_DAY) {
			throw new PageCorruptException("A stored time of day is " + value + " ns, outside [0, 86400e9)");
		}
		return value;
	}

	private static int nano(int value) {
		if (value < 0 || value >= 1_000_000_000) {
			throw new PageCorruptException("A stored nanosecond field is " + value + ", outside [0, 1e9)");
		}
		return value;
	}

	private static ZoneOffset offset(int seconds) {
		if (seconds < -MAX_OFFSET_SECONDS || seconds > MAX_OFFSET_SECONDS) {
			throw new PageCorruptException("A stored zone offset is " + seconds + " s, outside +-18:00");
		}
		return ZoneOffset.ofTotalSeconds(seconds);
	}

	// ---------------------------------------------------------------- reference rewriting

	/**
	 * Rewrites every element id embedded in an encoded property block, in place, and reports whether anything changed.
	 * <p>
	 * Two value types carry ids: a blob escape (a long string, a byte array or a vector) and an explicit
	 * {@link ElementRef}. Both are fixed 8 byte fields, so the payload length never changes and the block does not have
	 * to be reallocated. This is the reference class a compaction sweep is most likely to forget, because it is the
	 * only one that lives inside an opaque payload rather than in a typed record field.
	 *
	 * @param map
	 *            must return its argument unchanged for an id it does not relocate
	 */
	public static boolean remapIds(byte[] payload, int offset, int limit, java.util.function.LongUnaryOperator map) {
		Reader r = new Reader(payload, offset, limit);
		boolean changed = false;
		while (r.hasRemaining()) {
			r.u24();
			changed |= remapValue(payload, r, map);
		}
		return changed;
	}

	private static boolean remapValue(byte[] payload, Reader r, java.util.function.LongUnaryOperator map) {
		int tagByte = r.u8();
		int type = tagByte & 0x1F;
		int sizeClass = (tagByte >>> 5) & 0x7;
		switch (type) {
			case T_NULL:
			case T_BOOL:
				return false;
			case T_INT:
				r.skip(switch (sizeClass) {
					case 0 -> 1;
					case 1 -> 2;
					case 2 -> 4;
					case 3 -> 8;
					default -> throw new IllegalArgumentException("Bad INT size class " + sizeClass);
				});
				return false;
			case T_FLOAT:
				r.skip(sizeClass == 0 ? 4 : 8);
				return false;
			case T_STRING:
			case T_BYTES:
				if (sizeClass == 0) {
					r.skip(r.u8());
					return false;
				}
				r.skip(4);
				return patchId(payload, r, map);
			case T_TIMESTAMP:
				r.skip(8);
				return false;
			case T_UUID:
				r.skip(16);
				return false;
			case T_TEMPORAL:
				skipTemporal(r, sizeClass);
				return false;
			case T_ELEMENT_REF:
				return patchId(payload, r, map);
			case T_VECTOR_F32:
			case T_VECTOR_REF:
				// Identical payloads, and both hold an id compaction can move — a blob extent for one, a vector
				// record for the other. This is the reference class the class javadoc calls the one a sweep is most
				// likely to forget, because it lives inside an opaque payload rather than in a typed record field.
				r.skip(4);
				return patchId(payload, r, map);
			case T_LIST: {
				int count = (int) r.varint();
				boolean changed = false;
				for (int i = 0; i < count; i++) {
					changed |= remapValue(payload, r, map);
				}
				return changed;
			}
			default:
				throw new IllegalArgumentException("Unknown property value type " + type);
		}
	}

	private static boolean patchId(byte[] payload, Reader r, java.util.function.LongUnaryOperator map) {
		int at = r.position();
		long id = r.i64();
		long mapped = map.applyAsLong(id);
		if (mapped == id) {
			return false;
		}
		for (int i = 0; i < 8; i++) {
			payload[at + i] = (byte) (mapped >>> (8 * i));
		}
		return true;
	}

	/** A reference from one element to another, stored as a property value. */
	public record ElementRef(long id) {
	}
}
