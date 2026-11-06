package io.metaloom.graph.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tag 15: the six temporal kinds, round-tripped, and hostile bytes refused.
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md §2</a>
 */
@Tag("unit")
public class WireTemporalCodecTest {

	private static byte[] encode(Object value) {
		WireValueCodec.Writer writer = new WireValueCodec.Writer();
		WireValueCodec.encode(writer, value);
		return writer.toArray();
	}

	private static Object decode(byte[] bytes) {
		WireValueCodec.Reader reader = new WireValueCodec.Reader(bytes);
		Object decoded = WireValueCodec.decode(reader);
		assertEquals(0, reader.remaining(), "the decoder must consume exactly what the encoder wrote");
		return decoded;
	}

	private static Object roundTrip(Object value) {
		return decode(encode(value));
	}

	@Test
	public void testEveryKindRoundTripsExactly() {
		List<Object> values = List.of(
			LocalDate.of(2024, 2, 29), LocalDate.MIN, LocalDate.MAX, LocalDate.EPOCH,
			LocalTime.MIDNIGHT, LocalTime.MAX, LocalTime.of(10, 15, 30, 123_456_789),
			OffsetTime.of(10, 0, 0, 1, ZoneOffset.ofHours(1)), OffsetTime.MIN, OffsetTime.MAX,
			LocalDateTime.of(2020, 1, 1, 10, 0), LocalDateTime.MIN, LocalDateTime.MAX,
			LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_999),
			ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1)),
			ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC),
			ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(-9, -30)),
			ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 7, ZoneId.of("Europe/Vienna")),
			ZonedDateTime.of(2020, 7, 1, 10, 0, 0, 0, ZoneId.of("America/New_York")),
			ZonedDateTime.of(2020, 7, 1, 10, 0, 0, 0, ZoneId.of("UTC")));
		for (Object value : values) {
			Object decoded = roundTrip(value);
			assertEquals(value, decoded, "kind of " + value);
			assertEquals(value.getClass(), decoded.getClass(), "the type of " + value);
		}
	}

	@Test
	public void testARegionKeepsItsZoneAndAnOffsetStaysAnOffset() {
		ZonedDateTime vienna = (ZonedDateTime) roundTrip(ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0,
			ZoneId.of("Europe/Vienna")));
		assertEquals("2020-01-01T10:00+01:00[Europe/Vienna]", vienna.toString());
		ZonedDateTime fixed = (ZonedDateTime) roundTrip(ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0,
			ZoneOffset.ofHours(1)));
		assertInstanceOf(ZoneOffset.class, fixed.getZone(), "a fixed offset must not come back as a region");
	}

	@Test
	public void testDurationsKeepTheirFourComponentsApart() {
		TemporalAmount decoded = (TemporalAmount) roundTrip(new WireDuration(14, 3, 3_723, 500_000_000));
		assertEquals(14L, decoded.get(ChronoUnit.MONTHS));
		assertEquals(3L, decoded.get(ChronoUnit.DAYS));
		assertEquals(3_723L, decoded.get(ChronoUnit.SECONDS));
		assertEquals(500_000_000L, decoded.get(ChronoUnit.NANOS));
		assertEquals("P1Y2M3DT1H2M3.5S", decoded.toString());
		assertEquals(new WireDuration(14, 3, 3_723, 500_000_000), decoded, "equality is over the four fields");
		assertEquals(new WireDuration(14, 3, 3_723, 500_000_000).hashCode(), decoded.hashCode());

		assertEquals("PT0S", roundTrip(new WireDuration(0, 0, 0, 0)).toString());
		assertEquals("PT-1.5S", roundTrip(new WireDuration(0, 0, -2, 500_000_000)).toString(),
			"the normalised -2 s + 0.5 s prints as Neo4j prints it");
	}

	@Test
	public void testAnyTemporalAmountInTheRightUnitsIsADuration() {
		// The store may hand a duration back as some other TemporalAmount; the wire reads its units.
		assertEquals(new WireDuration(14, 3, 0, 0), roundTrip(Period.of(1, 2, 3)), "years fold into months");
		assertEquals(new WireDuration(0, 0, -2, 500_000_000), roundTrip(Duration.ofMillis(-1_500)),
			"a negative fraction carries into the seconds");
		assertEquals(new WireDuration(1, 2, 3, 4), roundTrip(amount(Map.of(ChronoUnit.MONTHS, 1L,
			ChronoUnit.DAYS, 2L, ChronoUnit.SECONDS, 3L, ChronoUnit.NANOS, 4L))));

		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
			() -> encode(amount(Map.of(ChronoUnit.HOURS, 1L))));
		assertTrue(thrown.getMessage().contains("Hours"), thrown.getMessage());
	}

	@Test
	public void testTemporalValuesNestInsideEverything() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("born", LocalDate.of(1990, 5, 17));
		properties.put("shift", new WireDuration(0, 0, 28_800, 0));
		WireNode node = new WireNode(1L << 60, List.of("Person"), properties);
		List<Object> list = new ArrayList<>(List.of(LocalTime.NOON, node,
			Map.of("at", ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("Europe/Vienna")))));
		assertEquals(list, roundTrip(list));
	}

	@Test
	public void testDurationsAddLikeCypher() {
		TemporalAmount days = (TemporalAmount) roundTrip(new WireDuration(1, 1, 0, 0));
		assertEquals(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 1, 31).plus(days),
			"months first (Jan 31 + 1 month = Feb 29), then days; zero seconds skipped so a date accepts it");
	}

	// ---------------------------------------------------------------- hostile input

	private static byte[] temporal(int kind, Object... fields) {
		WireValueCodec.Writer writer = new WireValueCodec.Writer();
		writer.u8((kind << 5) | WireValueCodec.T_TEMPORAL);
		for (Object field : fields) {
			switch (field) {
				case Long l -> writer.i64(l);
				case Integer i -> writer.i32(i);
				case String s -> WireValueCodec.encodeString(writer, s);
				default -> throw new IllegalArgumentException(String.valueOf(field));
			}
		}
		return writer.toArray();
	}

	private static void refused(byte[] input, String what) {
		assertThrows(WireFormatException.class, () -> WireValueCodec.decode(new WireValueCodec.Reader(input)), what);
	}

	@Test
	public void testOutOfRangeFieldsAreRefused() {
		refused(temporal(WireValueCodec.K_DATE, Long.MAX_VALUE), "an epoch day past LocalDate.MAX");
		refused(temporal(WireValueCodec.K_LOCAL_TIME, -1L), "a negative nano of day");
		refused(temporal(WireValueCodec.K_LOCAL_TIME, 86_400_000_000_000L), "a nano of day of a whole day");
		refused(temporal(WireValueCodec.K_TIME, 0L, 18 * 3600 + 1), "an offset past +18:00");
		refused(temporal(WireValueCodec.K_LOCAL_DATETIME, 0L, 1_000_000_000), "a nano of second of a whole second");
		refused(temporal(WireValueCodec.K_LOCAL_DATETIME, 0L, -1), "a negative nano of second");
		refused(temporal(WireValueCodec.K_LOCAL_DATETIME, Long.MAX_VALUE, 0), "a datetime past LocalDateTime.MAX");
		refused(temporal(WireValueCodec.K_DATETIME_OFFSET, Long.MIN_VALUE, 0, 0), "an instant before Instant.MIN");
		refused(temporal(WireValueCodec.K_DATETIME_OFFSET, 0L, 0, -18 * 3600 - 1), "an offset past -18:00");
		refused(temporal(WireValueCodec.K_DURATION, 0L, 0L, 0L, 1_000_000_000), "duration nanos not normalised");
		refused(temporal(WireValueCodec.K_DURATION, 0L, 0L, 0L, -1), "negative duration nanos");
		refused(temporal(7), "kind 7, which does not exist");
	}

	@Test
	public void testZoneIdsAreChecked() {
		refused(temporal(WireValueCodec.K_DATETIME_REGION, 0L, 0, "Mars/Olympus_Mons"), "an unknown region");
		refused(temporal(WireValueCodec.K_DATETIME_REGION, 0L, 0, "not a zone!"), "a malformed zone id");
		refused(temporal(WireValueCodec.K_DATETIME_REGION, 0L, 0, ""), "an empty zone id");
		refused(temporal(WireValueCodec.K_DATETIME_REGION, 0L, 0, "+01:00"), "a fixed offset spelt as a region");
		refused(temporal(WireValueCodec.K_DATETIME_REGION, 0L, 0, "Z"), "UTC as an offset spelt as a region");
		refused(temporal(WireValueCodec.K_DATETIME_REGION, 0L, 0, "UTC+1"), "a non-canonical spelling of UTC+01:00");
		// The zone id must be a string value, not a number in its place.
		refused(temporal(WireValueCodec.K_DATETIME_REGION, 0L, 0, 5L), "a zone id that is not a string");
		assertEquals(ZoneId.of("Europe/Vienna"),
			((ZonedDateTime) decode(temporal(WireValueCodec.K_DATETIME_REGION, 0L, 0, "Europe/Vienna"))).getZone());
	}

	@Test
	public void testEveryTruncationOfEveryKindIsRefused() {
		for (Object value : List.of(LocalDate.MAX, LocalTime.MAX, OffsetTime.MAX, LocalDateTime.MAX,
			ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(2)),
			ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("Europe/Vienna")), new WireDuration(1, 2, 3, 4))) {
			byte[] valid = encode(value);
			for (int length = 0; length < valid.length; length++) {
				byte[] truncated = new byte[length];
				System.arraycopy(valid, 0, truncated, 0, length);
				refused(truncated, value + " truncated to " + length + " of " + valid.length + " bytes");
			}
		}
	}

	/** An amount in whatever units it is given, as a store might hand one back. */
	private static TemporalAmount amount(Map<TemporalUnit, Long> components) {
		return new TemporalAmount() {
			@Override
			public long get(TemporalUnit unit) {
				return components.get(unit);
			}

			@Override
			public List<TemporalUnit> getUnits() {
				return List.copyOf(components.keySet());
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
}
