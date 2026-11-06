package io.metaloom.graph.cli.dump;

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
 * <tr><td>15 TEMPORAL</td><td colspan="4">the size class says which of the seven kinds</td></tr>
 * </table>
 *
 * <h2>Temporal values: one tag, the size class is the kind</h2>
 *
 * <p>
 * The same layout as the wire's tag 15, and fifteen for the same reason: it is the first number free in the storage,
 * wire and dump codecs alike. 0 date (u64 epoch day), 1 local time (u64 nano of day), 2 time (u64 nano of day, u32
 * offset seconds), 3 local datetime (u64 epoch second read as UTC, u32 nano), 4 datetime at a fixed offset (u64
 * instant epoch second, u32 nano, u32 offset seconds), 5 datetime in a region (u64 instant epoch second, u32 nano,
 * string zone id), 6 duration (u64 months, u64 days, u64 seconds, u32 nanos). A duration is written from any
 * {@link TemporalAmount} in years, months, days, seconds and nanos and read back as a package-private
 * {@code TemporalAmount} in months, days, seconds and nanos — not a {@code Period}, which has no seconds.
 * </p>
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
	public static final int T_TEMPORAL = 15;

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
			case LocalDate date -> {
				out.u8(tag(T_TEMPORAL, K_DATE));
				out.u64(date.toEpochDay());
			}
			case LocalTime time -> {
				out.u8(tag(T_TEMPORAL, K_LOCAL_TIME));
				out.u64(time.toNanoOfDay());
			}
			case OffsetTime time -> {
				out.u8(tag(T_TEMPORAL, K_TIME));
				out.u64(time.toLocalTime().toNanoOfDay());
				out.u32(time.getOffset().getTotalSeconds());
			}
			case LocalDateTime dateTime -> {
				out.u8(tag(T_TEMPORAL, K_LOCAL_DATETIME));
				out.u64(dateTime.toEpochSecond(ZoneOffset.UTC));
				out.u32(dateTime.getNano());
			}
			case ZonedDateTime dateTime -> {
				boolean offset = dateTime.getZone() instanceof ZoneOffset;
				out.u8(tag(T_TEMPORAL, offset ? K_DATETIME_OFFSET : K_DATETIME_REGION));
				out.u64(dateTime.toEpochSecond());
				out.u32(dateTime.getNano());
				if (offset) {
					out.u32(dateTime.getOffset().getTotalSeconds());
				} else {
					out.string(dateTime.getZone().getId());
				}
			}
			case TemporalAmount amount -> {
				DumpDuration duration = DumpDuration.from(amount);
				out.u8(tag(T_TEMPORAL, K_DURATION));
				out.u64(duration.months());
				out.u64(duration.days());
				out.u64(duration.seconds());
				out.u32(duration.nanos());
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
			case T_TEMPORAL -> readTemporal(in, sizeClass);
			default -> throw new DumpFormatException("Value tag " + type + " is not one this build writes; the dump "
				+ "is corrupt or was written by a newer version");
		};
	}

	/**
	 * One temporal value, every field range-checked.
	 *
	 * <p>
	 * {@code java.time}'s own {@link DateTimeException} becomes a {@link DumpFormatException}, the only exception a
	 * corrupt dump may raise. Nanosecond fields are checked here rather than left to {@code Instant.ofEpochSecond}, which
	 * would carry an out-of-range one into the seconds; a zone id must be a known region in its canonical spelling,
	 * since a fixed offset has a kind of its own.
	 * </p>
	 */
	private static Object readTemporal(DumpReader in, int kind) {
		try {
			return switch (kind) {
				case K_DATE -> LocalDate.ofEpochDay(in.u64());
				case K_LOCAL_TIME -> LocalTime.ofNanoOfDay(in.u64());
				case K_TIME -> {
					LocalTime time = LocalTime.ofNanoOfDay(in.u64());
					yield OffsetTime.of(time, ZoneOffset.ofTotalSeconds(in.u32()));
				}
				case K_LOCAL_DATETIME -> {
					long seconds = in.u64();
					yield LocalDateTime.ofEpochSecond(seconds, nanoOfSecond(in.u32()), ZoneOffset.UTC);
				}
				case K_DATETIME_OFFSET -> {
					Instant instant = instant(in);
					yield ZonedDateTime.ofInstant(instant, ZoneOffset.ofTotalSeconds(in.u32()));
				}
				case K_DATETIME_REGION -> {
					Instant instant = instant(in);
					yield ZonedDateTime.ofInstant(instant, region(in.string()));
				}
				case K_DURATION -> {
					long months = in.u64();
					long days = in.u64();
					long seconds = in.u64();
					yield new DumpDuration(months, days, seconds, nanoOfSecond(in.u32()));
				}
				default -> throw new DumpFormatException("Temporal kind " + kind + " is not one this build writes");
			};
		} catch (DateTimeException e) {
			throw new DumpFormatException("A temporal value out of range: " + e.getMessage());
		}
	}

	private static Instant instant(DumpReader in) {
		long seconds = in.u64();
		return Instant.ofEpochSecond(seconds, nanoOfSecond(in.u32()));
	}

	private static int nanoOfSecond(int nanos) {
		if (nanos < 0 || nanos >= 1_000_000_000) {
			throw new DumpFormatException("A nanosecond field of " + nanos + " is outside [0, 999999999]");
		}
		return nanos;
	}

	private static ZoneId region(String id) {
		ZoneId zone = ZoneId.of(id);
		if (zone instanceof ZoneOffset || !zone.getId().equals(id)) {
			throw new DumpFormatException("Zone id '" + id + "' is not a region in its canonical form");
		}
		return zone;
	}
}
