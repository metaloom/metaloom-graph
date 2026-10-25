package io.metaloom.graph.core.store.prop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
