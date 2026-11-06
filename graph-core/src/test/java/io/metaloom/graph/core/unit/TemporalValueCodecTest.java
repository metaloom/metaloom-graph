package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.store.prop.StoredDuration;
import io.metaloom.graph.core.store.prop.ValueCodec;

/**
 * The temporal tag ({@code T_TEMPORAL}, format version 5): every kind round trips, the skip path and the id remapper
 * step over exactly the bytes the decoder reads, and an untrusted payload out of range is corruption rather than a
 * {@code DateTimeException}.
 *
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 4
 */
@Tag("unit")
public class TemporalValueCodecTest {

	private static byte[] encode(Object value) throws Exception {
		ValueCodec.Writer writer = new ValueCodec.Writer();
		ValueCodec.encode(writer, value, null);
		return writer.toArray();
	}

	private static Object decode(byte[] encoded) {
		return ValueCodec.decode(new ValueCodec.Reader(encoded, 0, encoded.length), null, true, null);
	}

	/**
	 * Decodes it, then checks the skip path and the remapper consume exactly the same bytes: a value followed by a
	 * marker integer, read back with the first value skipped.
	 */
	private static Object roundTrip(Object value) throws Exception {
		byte[] encoded = encode(value);
		ValueCodec.Reader reader = new ValueCodec.Reader(encoded, 0, encoded.length);
		Object decoded = ValueCodec.decode(reader, null, true, null);
		assertEquals(encoded.length, reader.position(), "the decoder reads the whole value");

		ValueCodec.Writer pair = new ValueCodec.Writer();
		ValueCodec.encode(pair, value, null);
		ValueCodec.encode(pair, 4242L, null);
		byte[] both = pair.toArray();
		ValueCodec.Reader skipping = new ValueCodec.Reader(both, 0, both.length);
		assertNull(ValueCodec.decode(skipping, null, false, null));
		assertEquals(4242L, ValueCodec.decode(skipping, null, true, null), "the skip path skips the right count");

		// The remapper walks a block of [u24 key][value] entries.
		ValueCodec.Writer block = new ValueCodec.Writer();
		block.u24(1);
		ValueCodec.encode(block, value, null);
		block.u24(2);
		ValueCodec.encode(block, 4242L, null);
		byte[] blockBytes = block.toArray();
		assertEquals(false, ValueCodec.remapIds(blockBytes, 0, blockBytes.length, id -> id));
		return decoded;
	}

	@Test
	public void testDates() throws Exception {
		for (LocalDate date : List.of(LocalDate.of(2024, 2, 29), LocalDate.EPOCH, LocalDate.of(1969, 12, 31),
			LocalDate.MIN, LocalDate.MAX)) {
			assertEquals(date, roundTrip(date));
		}
		assertEquals(9, encode(LocalDate.EPOCH).length, "tag and an i64 epoch day");
	}

	@Test
	public void testLocalTimesAndTimes() throws Exception {
		for (LocalTime time : List.of(LocalTime.MIDNIGHT, LocalTime.MAX, LocalTime.of(12, 34, 56, 789))) {
			assertEquals(time, roundTrip(time));
		}
		for (OffsetTime time : List.of(OffsetTime.MIN, OffsetTime.MAX,
			OffsetTime.of(8, 30, 0, 1, ZoneOffset.ofHoursMinutesSeconds(-5, -30, -15)))) {
			assertEquals(time, roundTrip(time), "the offset is kept, not normalised away");
		}
	}

	@Test
	public void testLocalDateTimes() throws Exception {
		for (LocalDateTime time : List.of(LocalDateTime.MIN, LocalDateTime.MAX,
			LocalDateTime.of(1969, 12, 31, 23, 59, 59, 500_000_000), LocalDateTime.of(1970, 1, 1, 0, 0),
			LocalDateTime.of(2024, 2, 29, 12, 0, 0, 1))) {
			assertEquals(time, roundTrip(time));
		}
	}

	@Test
	public void testZonedDateTimesKeepTheirZone() throws Exception {
		ZonedDateTime fixed = ZonedDateTime.of(2024, 10, 27, 2, 30, 0, 7, ZoneOffset.ofHours(2));
		assertEquals(fixed, roundTrip(fixed));

		// The autumn overlap in Vienna: 02:30 happens twice. Both readings must survive, region id included.
		ZoneId vienna = ZoneId.of("Europe/Vienna");
		ZonedDateTime earlier = ZonedDateTime.of(LocalDateTime.of(2024, 10, 27, 2, 30), vienna)
			.withEarlierOffsetAtOverlap();
		ZonedDateTime later = earlier.withLaterOffsetAtOverlap();
		for (ZonedDateTime time : List.of(earlier, later)) {
			ZonedDateTime back = (ZonedDateTime) roundTrip(time);
			assertEquals(time, back);
			assertEquals("Europe/Vienna", back.getZone().getId());
		}
		ZonedDateTime beforeEpoch = ZonedDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_999, ZoneId.of("UTC"));
		assertEquals(beforeEpoch, roundTrip(beforeEpoch));
		ZonedDateTime extreme = ZonedDateTime.of(LocalDateTime.MAX.minusDays(1), ZoneId.of("America/New_York"));
		assertEquals(extreme, roundTrip(extreme));
	}

	@Test
	public void testAnOffsetDateTimeReadsBackAsAZonedOne() throws Exception {
		OffsetDateTime offset = OffsetDateTime.of(2001, 9, 9, 1, 46, 40, 123_456_789, ZoneOffset.ofHoursMinutes(5, 30));
		Object back = roundTrip(offset);
		assertInstanceOf(ZonedDateTime.class, back);
		assertEquals(offset.toZonedDateTime(), back);
		assertEquals(ZoneOffset.ofHoursMinutes(5, 30), ((ZonedDateTime) back).getZone());
	}

	@Test
	public void testDurationsFromEveryKindOfAmount() throws Exception {
		assertEquals(new StoredDuration(14, 3, 0, 0), roundTrip(Period.of(1, 2, 3)));
		assertEquals(new StoredDuration(0, 0, -90_000, 250_000_000), roundTrip(Duration.ofHours(-25).plusMillis(250)));
		assertEquals(new StoredDuration(0, 0, -1, 500_000_000), roundTrip(Duration.ofMillis(-500)));
		assertEquals(StoredDuration.ZERO, roundTrip(Duration.ZERO));
		assertEquals(StoredDuration.ZERO, roundTrip(Period.ZERO));
		// A custom amount in the shape of graph-cypher's CypherDuration, with an unnormalised negative nano part.
		assertEquals(new StoredDuration(-5, 2, -5, 999_999_999), roundTrip(amount(-5, 2, -3, -1_000_000_001)));
		StoredDuration max = new StoredDuration(Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, 999_999_999);
		assertEquals(max, roundTrip(max));
		assertEquals(29, encode(Duration.ZERO).length, "tag, three i64 and an i32");
	}

	@Test
	public void testAnAmountTheStoreCannotHoldIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> encode(new UnitAmount(ChronoUnit.HOURS, 1)),
			"hours have no single home in the four components");
		assertThrows(IllegalArgumentException.class, () -> encode(amount(0, 0, Long.MAX_VALUE, 1_000_000_000L)),
			"the carry overflows the seconds");
		assertThrows(IllegalArgumentException.class,
			() -> encode(new UnitAmount(ChronoUnit.YEARS, Long.MAX_VALUE / 6)), "years times twelve overflows");
	}

	@Test
	public void testStoredDurationRendersAsIso8601() {
		assertEquals("PT0S", StoredDuration.ZERO.toString());
		assertEquals("P1Y2M3DT4H5M6.5S", StoredDuration.of(14, 3, 4 * 3600 + 5 * 60 + 6, 500_000_000).toString());
		assertEquals("P-1M", new StoredDuration(-1, 0, 0, 0).toString());
		assertEquals("PT-0.5S", StoredDuration.of(0, 0, 0, -500_000_000).toString());
		assertEquals("P1D", new StoredDuration(0, 1, 0, 0).toString());
	}

	@Test
	public void testStoredDurationIsATemporalAmount() {
		StoredDuration d = StoredDuration.of(13, 1, 3600, 5);
		assertEquals(List.of(ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.SECONDS, ChronoUnit.NANOS), d.getUnits());
		assertEquals(13, d.get(ChronoUnit.MONTHS));
		assertEquals(5, d.get(ChronoUnit.NANOS));
		LocalDateTime start = LocalDateTime.of(2024, 1, 31, 0, 0);
		assertEquals(LocalDateTime.of(2025, 3, 1, 1, 0, 0, 5), start.plus(d));
		assertEquals(start, start.plus(d).minus(d));
		assertEquals(d, StoredDuration.of(d), "a stored duration is its own normal form");
	}

	@Test
	public void testTemporalsInsideAList() throws Exception {
		List<Object> values = List.of(LocalDate.EPOCH, Period.ofDays(-7), LocalTime.NOON, List.of(Duration.ofSeconds(1)));
		assertEquals(List.of(LocalDate.EPOCH, new StoredDuration(0, -7, 0, 0), LocalTime.NOON,
			List.of(new StoredDuration(0, 0, 1, 0))), roundTrip(values));
	}

	// ---------------------------------------------------------------- untrusted bytes

	private static byte[] raw(int kind, long... fields) {
		ValueCodec.Writer w = new ValueCodec.Writer();
		w.u8((kind << 5) | ValueCodec.T_TEMPORAL);
		for (long field : fields) {
			w.i64(field);
		}
		return w.toArray();
	}

	@Test
	public void testCorruptPayloadsAreRefusedAsCorruption() throws Exception {
		List<byte[]> corrupt = new ArrayList<>();
		corrupt.add(raw(ValueCodec.K_DATE, Long.MAX_VALUE));
		corrupt.add(raw(ValueCodec.K_LOCAL_TIME, -1));
		corrupt.add(raw(ValueCodec.K_LOCAL_TIME, 86_400_000_000_000L));
		corrupt.add(raw(7, 0));

		ValueCodec.Writer w = new ValueCodec.Writer();
		w.u8((ValueCodec.K_TIME << 5) | ValueCodec.T_TEMPORAL);
		w.i64(0);
		w.i32(18 * 3600 + 1);
		corrupt.add(w.toArray());

		w = new ValueCodec.Writer();
		w.u8((ValueCodec.K_LOCAL_DATE_TIME << 5) | ValueCodec.T_TEMPORAL);
		w.i64(0);
		w.i32(1_000_000_000);
		corrupt.add(w.toArray());

		w = new ValueCodec.Writer();
		w.u8((ValueCodec.K_LOCAL_DATE_TIME << 5) | ValueCodec.T_TEMPORAL);
		w.i64(Long.MAX_VALUE);
		w.i32(0);
		corrupt.add(w.toArray());

		w = new ValueCodec.Writer();
		w.u8((ValueCodec.K_DATE_TIME_OFFSET << 5) | ValueCodec.T_TEMPORAL);
		w.i64(Long.MIN_VALUE);
		w.i32(0);
		w.i32(0);
		corrupt.add(w.toArray());

		w = new ValueCodec.Writer();
		w.u8((ValueCodec.K_DATE_TIME_ZONE << 5) | ValueCodec.T_TEMPORAL);
		w.i64(0);
		w.i32(0);
		byte[] zone = "Not/AZone".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		w.u8(zone.length);
		w.bytes(zone);
		corrupt.add(w.toArray());

		w = new ValueCodec.Writer();
		w.u8((ValueCodec.K_DURATION << 5) | ValueCodec.T_TEMPORAL);
		w.i64(0);
		w.i64(0);
		w.i64(0);
		w.i32(-1);
		corrupt.add(w.toArray());

		for (byte[] bytes : corrupt) {
			assertThrows(PageCorruptException.class, () -> decode(bytes), () -> "kind " + ((bytes[0] >>> 5) & 7));
		}
		// Truncated: the reader's own refusal.
		byte[] date = encode(LocalDate.EPOCH);
		assertThrows(IllegalArgumentException.class, () -> decode(java.util.Arrays.copyOf(date, 5)));
	}

	// ---------------------------------------------------------------- helpers

	/** An amount shaped like graph-cypher's CypherDuration: months, days, seconds, nanos, unnormalised. */
	private static TemporalAmount amount(long months, long days, long seconds, long nanos) {
		return new TemporalAmount() {
			@Override
			public long get(TemporalUnit unit) {
				if (unit == ChronoUnit.MONTHS) {
					return months;
				}
				if (unit == ChronoUnit.DAYS) {
					return days;
				}
				if (unit == ChronoUnit.SECONDS) {
					return seconds;
				}
				return nanos;
			}

			@Override
			public List<TemporalUnit> getUnits() {
				return List.of(ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.SECONDS, ChronoUnit.NANOS);
			}

			@Override
			public Temporal addTo(Temporal temporal) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Temporal subtractFrom(Temporal temporal) {
				throw new UnsupportedOperationException();
			}
		};
	}

	private record UnitAmount(TemporalUnit unit, long value) implements TemporalAmount {

		@Override
		public long get(TemporalUnit requested) {
			return requested == unit ? value : 0;
		}

		@Override
		public List<TemporalUnit> getUnits() {
			return List.of(unit);
		}

		@Override
		public Temporal addTo(Temporal temporal) {
			return temporal.plus(value, unit);
		}

		@Override
		public Temporal subtractFrom(Temporal temporal) {
			return temporal.minus(value, unit);
		}
	}
}
