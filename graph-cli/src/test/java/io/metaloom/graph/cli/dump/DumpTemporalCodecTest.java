package io.metaloom.graph.cli.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.time.temporal.TemporalAmount;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tag 15 in a dump: every temporal kind the store is gaining, written and read back, and hostile bytes refused.
 *
 * <p>
 * At the codec rather than through a store, because the store in this tree cannot hold one yet; once it can,
 * {@code BackupRoundTripTest}'s every-property-type node should gain them.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/BACKUP.md">spec/BACKUP.md §3.3</a>
 */
@Tag("unit")
public class DumpTemporalCodecTest {

	private static byte[] write(Object value) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DumpWriter out = new DumpWriter(bytes);
		DumpValueCodec.write(out, value);
		out.flush();
		return bytes.toByteArray();
	}

	private static Object read(byte[] bytes) {
		try (DumpReader in = new DumpReader(new ByteArrayInputStream(bytes), bytes.length)) {
			Object value = DumpValueCodec.read(in);
			assertEquals(0, in.remaining(), "the reader must consume exactly what the writer wrote");
			return value;
		}
	}

	@Test
	public void testEveryKindRoundTripsExactly() {
		List<Object> values = List.of(LocalDate.of(2024, 2, 29), LocalDate.MIN, LocalDate.MAX,
			LocalTime.MIDNIGHT, LocalTime.MAX, OffsetTime.MIN, OffsetTime.MAX,
			OffsetTime.of(10, 0, 0, 1, ZoneOffset.ofHours(1)),
			LocalDateTime.MIN, LocalDateTime.MAX, LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_999),
			ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1)),
			ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC),
			ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 7, ZoneId.of("Europe/Vienna")),
			ZonedDateTime.of(2020, 7, 1, 10, 0, 0, 0, ZoneId.of("America/New_York")),
			List.of(LocalDate.EPOCH, LocalTime.NOON));
		for (Object value : values) {
			Object back = read(write(value));
			// ZonedDateTime.equals compares the zone too, so an offset coming back as a region would fail here.
			assertEquals(value, back, String.valueOf(value));
		}
		assertEquals("2020-01-01T10:00+01:00[Europe/Vienna]",
			read(write(ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneId.of("Europe/Vienna")))).toString());
	}

	@Test
	public void testADurationKeepsMonthsDaysSecondsAndNanosApart() {
		TemporalAmount back = (TemporalAmount) read(write(new DumpDuration(14, 3, 3_723, 500_000_000)));
		assertEquals(new DumpDuration(14, 3, 3_723, 500_000_000), back);
		assertEquals(List.of(ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.SECONDS, ChronoUnit.NANOS), back.getUnits(),
			"exactly the units the store accepts");

		// Whatever TemporalAmount the store hands back is written by its units.
		assertEquals(new DumpDuration(14, 3, 0, 0), read(write(Period.of(1, 2, 3))));
		assertEquals(new DumpDuration(0, 0, -2, 500_000_000), read(write(Duration.ofMillis(-1_500))));
		assertEquals(new DumpDuration(0, 0, 93_600, 0), read(write(Duration.ofHours(26))),
			"seconds never fold into days: a day is not always 86,400 of them");

		assertThrows(DumpFormatException.class, () -> write(new TemporalAmount() {
			@Override
			public long get(java.time.temporal.TemporalUnit unit) {
				return 1;
			}

			@Override
			public List<java.time.temporal.TemporalUnit> getUnits() {
				return List.of(ChronoUnit.HOURS);
			}

			@Override
			public java.time.temporal.Temporal addTo(java.time.temporal.Temporal temporal) {
				return temporal;
			}

			@Override
			public java.time.temporal.Temporal subtractFrom(java.time.temporal.Temporal temporal) {
				return temporal;
			}
		}), "a unit the store cannot hold is a bug to report, not a value to round");
	}

	@Test
	public void testADurationAddsMonthsThenDays() {
		TemporalAmount back = (TemporalAmount) read(write(new DumpDuration(1, 1, 0, 0)));
		assertEquals(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 1, 31).plus(back));
	}

	// ---------------------------------------------------------------- hostile input

	private static byte[] temporal(int kind, Object... fields) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DumpWriter out = new DumpWriter(bytes);
		out.u8((kind << 5) | DumpValueCodec.T_TEMPORAL);
		for (Object field : fields) {
			switch (field) {
				case Long l -> out.u64(l);
				case Integer i -> out.u32(i);
				case String s -> out.string(s);
				default -> throw new IllegalArgumentException(String.valueOf(field));
			}
		}
		out.flush();
		return bytes.toByteArray();
	}

	private static void refused(byte[] input, String what) {
		assertThrows(DumpFormatException.class, () -> {
			try (DumpReader in = new DumpReader(new ByteArrayInputStream(input), input.length)) {
				DumpValueCodec.read(in);
			}
		}, what);
	}

	@Test
	public void testOutOfRangeFieldsAndBadZonesAreRefused() {
		refused(temporal(DumpValueCodec.K_DATE, Long.MAX_VALUE), "an epoch day past LocalDate.MAX");
		refused(temporal(DumpValueCodec.K_LOCAL_TIME, -1L), "a negative nano of day");
		refused(temporal(DumpValueCodec.K_TIME, 0L, 18 * 3600 + 1), "an offset past +18:00");
		refused(temporal(DumpValueCodec.K_LOCAL_DATETIME, 0L, 1_000_000_000), "a whole second of nanos");
		refused(temporal(DumpValueCodec.K_LOCAL_DATETIME, Long.MIN_VALUE, 0), "before LocalDateTime.MIN");
		refused(temporal(DumpValueCodec.K_DATETIME_OFFSET, Long.MAX_VALUE, 0, 0), "past Instant.MAX");
		refused(temporal(DumpValueCodec.K_DATETIME_REGION, 0L, 0, "Mars/Olympus_Mons"), "an unknown region");
		refused(temporal(DumpValueCodec.K_DATETIME_REGION, 0L, 0, "+01:00"), "an offset spelt as a region");
		refused(temporal(DumpValueCodec.K_DATETIME_REGION, 0L, 0, "UTC+1"), "a non-canonical zone id");
		refused(temporal(DumpValueCodec.K_DATETIME_REGION, 0L, 0, ""), "an empty zone id");
		refused(temporal(DumpValueCodec.K_DURATION, 0L, 0L, 0L, -1), "negative duration nanos");
		refused(temporal(7), "kind 7, which does not exist");
	}

	@Test
	public void testEveryTruncationIsRefused() {
		for (Object value : List.of(LocalDate.MAX, OffsetTime.MAX, LocalDateTime.MAX,
			ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(2)),
			ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("Europe/Vienna")), new DumpDuration(1, 2, 3, 4))) {
			byte[] valid = write(value);
			for (int length = 0; length < valid.length; length++) {
				byte[] truncated = new byte[length];
				System.arraycopy(valid, 0, truncated, 0, length);
				refused(truncated, value + " truncated to " + length + " of " + valid.length + " bytes");
			}
		}
	}
}
