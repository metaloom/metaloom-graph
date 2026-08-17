package io.metaloom.graph.cli.dump;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The value encoding a dump uses: every type the storage layer can hold, and nothing that needs a store to decode.
 *
 * <h2>Why neither existing codec could be used</h2>
 *
 * <p>
 * <b>{@code ValueCodec}</b>, the storage one, has exactly the right type set — and escapes any value over 255 bytes to
 * the blob store, which is a <em>write</em>, and throws when there is no blob store to write to. A dump has neither one
 * nor any business allocating one, so a single long string would be unencodable. It is also a storage format, versioned
 * with the store: welding a dump to it would make every format bump a dump-compatibility question and vice versa, which
 * is exactly backwards for the one artefact whose job is to outlive a store.
 * </p>
 *
 * <p>
 * <b>{@code WireValueCodec}</b> rejects tags 5 to 9 — {@code BYTES}, {@code TIMESTAMP}, {@code UUID},
 * {@code ELEMENT_REF}, {@code VECTOR_F32} — because the query layer renders those as strings before they reach a
 * result, so the wire never carries them. A dump has to carry them: they are real property values that the Java API
 * writes and that a restore must reproduce exactly, and a backup that silently turned a {@code float[]} embedding into
 * a list of doubles would restore a store that no vector index could be rebuilt over.
 * </p>
 *
 * <h2>Same numbers where the bytes are the same</h2>
 *
 * <p>
 * The tag is {@code sizeClass:3 | type:5} and the type numbers are the storage codec's, wherever the encoding is
 * byte-identical — the convention {@code WireValueCodec} set, and for its reason: a reader of one can be checked
 * against the other, and the two can be read side by side without a translation table. What is deliberately <b>not</b>
 * shared is the escape hatch and the indirections. There is no {@code VECTOR_REF} and no {@code ELEMENT_REF} here: both
 * name a location inside a specific store, which is the one thing a dump must not depend on.
 * </p>
 *
 * <table>
 * <caption>Tags</caption>
 * <tr><td>0 NULL</td><td>1 BOOL</td><td>2 INT (zigzag)</td><td>3 FLOAT</td><td>4 STRING</td></tr>
 * <tr><td>5 BYTES</td><td>6 TIMESTAMP</td><td>7 UUID</td><td>9 VECTOR_F32</td><td>10 LIST</td></tr>
 * </table>
 *
 * @see <a href="../../../../../../../../spec/BACKUP.md">spec/BACKUP.md</a>
 */
public final class DumpValueCodec {

	public static final int T_NULL = 0;
	public static final int T_BOOL = 1;
	public static final int T_INT = 2;
	public static final int T_FLOAT = 3;
	public static final int T_STRING = 4;
	public static final int T_BYTES = 5;
	public static final int T_TIMESTAMP = 6;
	public static final int T_UUID = 7;
	public static final int T_VECTOR_F32 = 9;
	public static final int T_LIST = 10;

	/**
	 * How deep a value may nest before the decoder refuses.
	 *
	 * <p>
	 * A list is the only nesting there is and the engine does not produce nested ones, so this is a bound on hostile
	 * input rather than on real data: without it a crafted dump is a stack overflow, which is the textbook way a
	 * recursive decoder is turned into a crash.
	 * </p>
	 */
	public static final int MAX_DEPTH = 16;

	private DumpValueCodec() {
	}

	// ---------------------------------------------------------------- encode

	public static void write(DumpWriter out, Object value) {
		switch (value) {
			case null -> out.u8(tag(T_NULL, 0));
			case Boolean b -> out.u8(tag(T_BOOL, b ? 1 : 0));
			case Byte b -> writeLong(out, b);
			case Short s -> writeLong(out, s);
			case Integer i -> writeLong(out, i);
			case Long l -> writeLong(out, l);
			case Float f -> {
				out.u8(tag(T_FLOAT, 0));
				out.u32(Float.floatToRawIntBits(f));
			}
			case Double d -> {
				out.u8(tag(T_FLOAT, 1));
				out.u64(Double.doubleToRawLongBits(d));
			}
			case String s -> writeBytes(out, T_STRING, s.getBytes(StandardCharsets.UTF_8));
			case byte[] b -> writeBytes(out, T_BYTES, b);
			case Instant instant -> {
				out.u8(tag(T_TIMESTAMP, 0));
				out.u64(instant.getEpochSecond());
				out.u32(instant.getNano());
			}
			case UUID uuid -> {
				out.u8(tag(T_UUID, 0));
				out.u64(uuid.getMostSignificantBits());
				out.u64(uuid.getLeastSignificantBits());
			}
			case float[] vector -> {
				out.u8(tag(T_VECTOR_F32, 0));
				out.varInt(vector.length);
				for (float f : vector) {
					out.u32(Float.floatToRawIntBits(f));
				}
			}
			case List<?> list -> {
				out.u8(tag(T_LIST, 0));
				out.varInt(list.size());
				for (Object element : list) {
					write(out, element);
				}
			}
			default -> throw new DumpFormatException("A property of type " + value.getClass().getName()
				+ " cannot be written to a dump. The storage layer cannot hold one either, so this is a bug rather "
				+ "than a limitation.");
		}
	}

	/**
	 * Integers keep the storage codec's size classes, because most of them are small and a zigzag varint is not.
	 *
	 * <p>
	 * The size class says how many bytes follow, so a property like {@code age: 34} costs two bytes rather than nine.
	 * </p>
	 */
	private static void writeLong(DumpWriter out, long value) {
		long zigzag = (value << 1) ^ (value >> 63);
		if (Long.compareUnsigned(zigzag, 0xFFL) <= 0) {
			out.u8(tag(T_INT, 0));
			out.u8((int) zigzag);
		} else if (Long.compareUnsigned(zigzag, 0xFFFFL) <= 0) {
			out.u8(tag(T_INT, 1));
			out.u16((int) zigzag);
		} else if (Long.compareUnsigned(zigzag, 0xFFFFFFFFL) <= 0) {
			out.u8(tag(T_INT, 2));
			out.u32((int) zigzag);
		} else {
			out.u8(tag(T_INT, 3));
			out.u64(zigzag);
		}
	}

	private static void writeBytes(DumpWriter out, int type, byte[] bytes) {
		out.u8(tag(type, 0));
		out.varInt(bytes.length);
		out.bytes(bytes);
	}

	private static int tag(int type, int sizeClass) {
		return (sizeClass << 5) | type;
	}

	// ---------------------------------------------------------------- decode

	public static Object read(DumpReader in) {
		return read(in, 0);
	}

	private static Object read(DumpReader in, int depth) {
		if (depth > MAX_DEPTH) {
			throw new DumpFormatException("A value nests more than " + MAX_DEPTH + " deep");
		}
		int tag = in.u8();
		int type = tag & 0x1F;
		int sizeClass = (tag >>> 5) & 0x7;
		return switch (type) {
			case T_NULL -> null;
			case T_BOOL -> sizeClass != 0;
			case T_INT -> {
				long zigzag = switch (sizeClass) {
					case 0 -> in.u8();
					case 1 -> in.u16();
					case 2 -> in.u32() & 0xFFFFFFFFL;
					case 3 -> in.u64();
					default -> throw new DumpFormatException("Integer size class " + sizeClass + " is not a size");
				};
				yield (zigzag >>> 1) ^ -(zigzag & 1);
			}
			case T_FLOAT -> sizeClass == 0
				? (Object) Float.intBitsToFloat(in.u32())
				: (Object) Double.longBitsToDouble(in.u64());
			case T_STRING -> new String(in.bytes(in.varInt()), StandardCharsets.UTF_8);
			case T_BYTES -> in.bytes(in.varInt());
			case T_TIMESTAMP -> Instant.ofEpochSecond(in.u64(), in.u32());
			case T_UUID -> new UUID(in.u64(), in.u64());
			case T_VECTOR_F32 -> {
				int length = in.varInt();
				// Checked against what is left in the stream before allocating, so a crafted length cannot ask for a
				// gigabyte array. Every length field in this format goes through the same check.
				in.checkAvailable((long) length * Integer.BYTES);
				float[] vector = new float[length];
				for (int i = 0; i < length; i++) {
					vector[i] = Float.intBitsToFloat(in.u32());
				}
				yield vector;
			}
			case T_LIST -> {
				int size = in.varInt();
				// One byte is the smallest a value can be, so a list claiming more elements than there are bytes left
				// is a corrupt length rather than a large list.
				in.checkAvailable(size);
				List<Object> list = new ArrayList<>(Math.min(size, 1024));
				for (int i = 0; i < size; i++) {
					list.add(read(in, depth + 1));
				}
				yield list;
			}
			default -> throw new DumpFormatException("Value tag " + type + " is not one this build writes; the dump "
				+ "is corrupt or was written by a newer version");
		};
	}
}
