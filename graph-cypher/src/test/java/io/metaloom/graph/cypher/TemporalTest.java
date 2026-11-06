package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.value.CypherDuration;

/**
 * The temporal types: {@code DATE}, {@code LOCAL TIME}, {@code TIME}, {@code LOCAL DATETIME}, {@code DATETIME} and
 * {@code DURATION}.
 *
 * <p>
 * Each value is the {@code java.time} type a caller would expect — and a {@code CypherDuration} for a duration — so the
 * assertions compare against those directly rather than against strings. The last section stores every type and reads
 * it back, which is the point of having them at all: before, a date could only be stored as a string, and a string
 * orders and compares as text.
 * </p>
 */
@Tag("unit")
public class TemporalTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static Object value(GraphStore store, String expression) {
		return value(store, expression, Map.of());
	}

	private static Object value(GraphStore store, String expression, Map<String, Object> parameters) {
		return Cypher.query(store, "RETURN " + expression + " AS v", parameters).column("v").get(0);
	}

	// ---------------------------------------------------------------- construction from text

	@Test
	public void testDatesInEveryIsoForm() throws Exception {
		try (GraphStore store = stores.open()) {
			LocalDate expected = LocalDate.of(2015, 7, 21);
			assertEquals(expected, value(store, "date('2015-07-21')"));
			assertEquals(expected, value(store, "date('20150721')"));
			assertEquals(LocalDate.of(2015, 7, 1), value(store, "date('2015-07')"));
			assertEquals(LocalDate.of(2015, 1, 1), value(store, "date('2015')"));
			assertEquals(expected, value(store, "date('2015-W30-2')"), "ISO week 30, Tuesday");
			assertEquals(expected, value(store, "date('2015W302')"));
			assertEquals(expected, value(store, "date('2015-202')"), "ordinal day 202");
			assertEquals(expected, value(store, "date('2015202')"));
		}
	}

	@Test
	public void testTimesAndDateTimes() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(LocalTime.of(12, 31, 14, 645_876_123), value(store, "localtime('12:31:14.645876123')"));
			assertEquals(LocalTime.of(12, 31, 14), value(store, "localtime('123114')"));
			assertEquals(LocalTime.of(12, 0), value(store, "localtime('12')"));
			assertEquals(OffsetTime.of(12, 31, 14, 0, ZoneOffset.ofHours(1)), value(store, "time('12:31:14+01:00')"));
			assertEquals(OffsetTime.of(12, 31, 14, 0, ZoneOffset.UTC), value(store, "time('12:31:14')"),
				"no offset is UTC");
			assertEquals(LocalDateTime.of(2015, 7, 21, 21, 40, 32, 142_000_000),
				value(store, "localdatetime('2015-07-21T21:40:32.142')"));
			assertEquals(ZonedDateTime.of(2015, 7, 21, 21, 40, 32, 142_000_000, ZoneOffset.ofHours(1)),
				value(store, "datetime('2015-07-21T21:40:32.142+0100')"));
			ZonedDateTime vienna = (ZonedDateTime) value(store, "datetime('2015-07-21T21:40:32[Europe/Vienna]')");
			assertEquals(ZoneId.of("Europe/Vienna"), vienna.getZone());
			assertEquals(ZoneOffset.ofHours(2), vienna.getOffset(), "summer time in Vienna");
			assertEquals(ZonedDateTime.of(2015, 7, 21, 0, 0, 0, 0, ZoneOffset.UTC), value(store,
				"datetime('2015-07-21')"));
		}
	}

	@Test
	public void testUnreadableTextIsAnErrorNotNull() throws Exception {
		try (GraphStore store = stores.open()) {
			for (String bad : List.of("date('2015-13-01')", "date('yesterday')", "localtime('25:00')",
				"localtime('12:00+01:00')", "datetime('2015-07-21T21:40[Not/AZone]')",
				"datetime('2015-07-21T12:00+05:00[Europe/Vienna]')", "duration('P')", "duration('1D')")) {
				assertThrows(CypherTypeException.class, () -> value(store, bad), bad);
			}
			assertNull(value(store, "date(null)"));
		}
	}

	// ---------------------------------------------------------------- construction from maps and values

	@Test
	public void testDatesFromMaps() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(LocalDate.of(1984, 10, 11), value(store, "date({year: 1984, month: 10, day: 11})"));
			assertEquals(LocalDate.of(1984, 1, 1), value(store, "date({year: 1984})"));
			assertEquals(LocalDate.of(1984, 10, 11), value(store, "date({year: 1984, week: 41, dayOfWeek: 4})"));
			assertEquals(LocalDate.of(1984, 10, 11), value(store, "date({year: 1984, ordinalDay: 285})"));
			assertEquals(LocalDate.of(1984, 10, 11), value(store, "date({year: 1984, quarter: 4, dayOfQuarter: 11})"));
			assertThrows(CypherTypeException.class, () -> value(store, "date({month: 2})"));
			assertThrows(CypherTypeException.class, () -> value(store, "date({year: 1984, month: 2, day: 30})"));
			assertThrows(CypherTypeException.class, () -> value(store, "date({year: 1984, hour: 2})"));
		}
	}

	@Test
	public void testDateTimesFromMapsAndOtherValues() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(ZonedDateTime.of(1984, 10, 11, 12, 31, 14, 123_456_789, ZoneId.of("Europe/Stockholm")),
				value(store, "datetime({year: 1984, month: 10, day: 11, hour: 12, minute: 31, second: 14, "
					+ "millisecond: 123, microsecond: 456, nanosecond: 789, timezone: 'Europe/Stockholm'})"));
			assertEquals(ZonedDateTime.ofInstant(Instant.ofEpochSecond(1_000_000, 5), ZoneOffset.UTC),
				value(store, "datetime({epochSeconds: 1000000, nanosecond: 5})"));
			assertEquals(ZonedDateTime.ofInstant(Instant.ofEpochMilli(1_000_000_123L), ZoneOffset.UTC),
				value(store, "datetime({epochMillis: 1000000123})"));
			assertEquals(LocalDateTime.of(2020, 1, 31, 9, 15), value(store,
				"localdatetime({date: date('2020-01-31'), time: localtime('09:15')})"));
			// A field the map names overrides the one the source supplies.
			assertEquals(LocalDate.of(2020, 1, 1), value(store, "date({date: date('2020-05-17'), month: 1, day: 1})"));
			// A zoned source keeps its instant in a new zone.
			assertEquals(ZonedDateTime.of(2020, 1, 1, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")), value(store,
				"datetime({datetime: datetime('2020-01-01T00:00Z'), timezone: 'Asia/Tokyo'})"));
			assertEquals(LocalDate.of(2020, 1, 31), value(store, "date(datetime('2020-01-31T23:59:59+05:00'))"));
			assertEquals(ZonedDateTime.of(2020, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC), value(store,
				"datetime(date('2020-01-31'))"));
			assertEquals(LocalTime.of(23, 59, 59), value(store, "localtime(datetime('2020-01-31T23:59:59+05:00'))"));
		}
	}

	@Test
	public void testTheClockIsReadOncePerStatement() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(true, value(store, "datetime() = datetime() AND date() = date.statement()"));
			ZonedDateTime now = (ZonedDateTime) value(store, "datetime()");
			assertTrue(Math.abs(now.toInstant().toEpochMilli() - System.currentTimeMillis()) < 60_000);
			assertEquals(ZoneOffset.UTC, now.getZone());
			assertEquals(ZoneId.of("Europe/Vienna"), ((ZonedDateTime) value(store,
				"datetime.realtime('Europe/Vienna')")).getZone());
			assertEquals(ZonedDateTime.ofInstant(Instant.ofEpochMilli(1_600_000_000_123L), ZoneOffset.UTC),
				value(store, "datetime.fromEpochMillis(1600000000123)"));
			assertEquals(ZonedDateTime.ofInstant(Instant.ofEpochSecond(1_600_000_000L, 7), ZoneOffset.UTC),
				value(store, "datetime.fromEpoch(1600000000, 7)"));
		}
	}

	// ---------------------------------------------------------------- components

	@Test
	public void testComponents() throws Exception {
		try (GraphStore store = stores.open()) {
			Map<String, Object> row = Cypher.query(store, "WITH datetime('2015-07-21T21:40:32.142857123"
				+ "[Europe/Vienna]') AS d RETURN d.year AS year, d.quarter AS quarter, d.month AS month, d.week AS week, "
				+ "d.weekYear AS weekYear, d.day AS day, d.ordinalDay AS ordinalDay, d.dayOfWeek AS dayOfWeek, "
				+ "d.dayOfQuarter AS dayOfQuarter, d.hour AS hour, d.minute AS minute, d.second AS second, "
				+ "d.millisecond AS ms, d.microsecond AS us, d.nanosecond AS ns, d.timezone AS tz, "
				+ "d.offset AS offset, d.offsetMinutes AS offsetMinutes, d.epochSeconds AS epoch").row(0).asMap();
			assertEquals(2015L, row.get("year"));
			assertEquals(3L, row.get("quarter"));
			assertEquals(7L, row.get("month"));
			assertEquals(30L, row.get("week"));
			assertEquals(2015L, row.get("weekYear"));
			assertEquals(21L, row.get("day"));
			assertEquals(202L, row.get("ordinalDay"));
			assertEquals(2L, row.get("dayOfWeek"));
			assertEquals(21L, row.get("dayOfQuarter"));
			assertEquals(21L, row.get("hour"));
			assertEquals(40L, row.get("minute"));
			assertEquals(32L, row.get("second"));
			assertEquals(142L, row.get("ms"));
			assertEquals(142_857L, row.get("us"));
			assertEquals(142_857_123L, row.get("ns"));
			assertEquals("Europe/Vienna", row.get("tz"));
			assertEquals("+02:00", row.get("offset"));
			assertEquals(120L, row.get("offsetMinutes"));
			assertEquals(1437507632L, row.get("epoch"));
			CypherTypeException missing = assertThrows(CypherTypeException.class,
				() -> value(store, "date('2015-07-21').hour"));
			assertTrue(missing.getMessage().contains("hour"), missing.getMessage());
		}
	}

	@Test
	public void testDurationComponents() throws Exception {
		try (GraphStore store = stores.open()) {
			Map<String, Object> row = Cypher.query(store, "WITH duration({years: 1, months: 5, days: 16, hours: 12, "
				+ "minutes: 34, seconds: 56, nanoseconds: 789}) AS d RETURN d.years AS years, d.months AS months, "
				+ "d.monthsOfYear AS monthsOfYear, d.days AS days, d.weeks AS weeks, d.daysOfWeek AS daysOfWeek, "
				+ "d.hours AS hours, d.minutesOfHour AS minutesOfHour, d.seconds AS seconds, "
				+ "d.secondsOfMinute AS secondsOfMinute, d.nanosecondsOfSecond AS nanos").row(0).asMap();
			assertEquals(1L, row.get("years"));
			assertEquals(17L, row.get("months"));
			assertEquals(5L, row.get("monthsOfYear"));
			assertEquals(16L, row.get("days"));
			assertEquals(2L, row.get("weeks"));
			assertEquals(2L, row.get("daysOfWeek"));
			assertEquals(12L, row.get("hours"));
			assertEquals(34L, row.get("minutesOfHour"));
			assertEquals(45_296L, row.get("seconds"));
			assertEquals(56L, row.get("secondsOfMinute"));
			assertEquals(789L, row.get("nanos"));
		}
	}

	// ---------------------------------------------------------------- durations

	@Test
	public void testDurationsFromTextAndMaps() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(new CypherDuration(14, 10, 45_296, 0), value(store, "duration('P1Y2M10DT12H34M56S')"));
			assertEquals(new CypherDuration(0, 14, 0, 0), value(store, "duration('P2W')"));
			assertEquals(new CypherDuration(0, 0, -2, 500_000_000), value(store, "duration('PT-1.5S')"));
			assertEquals(new CypherDuration(0, 1, 43_200, 0), value(store, "duration('P1.5D')"),
				"a fraction of a day cascades into seconds");
			assertEquals(new CypherDuration(0, -1, 0, 0), value(store, "duration('-P1D')"));
			assertEquals(new CypherDuration(26, 2, 52_641, 545_000_000),
				value(store, "duration('P0002-02-02T14:37:21.545')"));
			assertEquals(new CypherDuration(0, 1, 3_600, 0), value(store, "duration({days: 1, hours: 1})"));
			assertEquals(new CypherDuration(0, 15, 18_873, 0), value(store, "duration({months: 0.5})"),
				"half of an average month is 15.2184375 days");
			assertEquals("P1Y2M10DT12H34M56S", value(store, "toString(duration('P1Y2M10DT12H34M56S'))"));
			assertEquals("PT-1.5S", value(store, "toString(duration('PT-1.5S'))"));
			assertEquals("PT0S", value(store, "toString(duration({}))"));
		}
	}

	@Test
	public void testDurationBetween() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(new CypherDuration(13, 1, 0, 0),
				value(store, "duration.between(date('1984-10-11'), date('1985-11-12'))"));
			assertEquals(new CypherDuration(-1, -1, 0, 0),
				value(store, "duration.between(date('2015-07-21'), date('2015-06-20'))"));
			assertEquals(new CypherDuration(0, 0, 3_600, 0),
				value(store, "duration.between(localtime('12:00'), localtime('13:00'))"));
			assertEquals(new CypherDuration(0, 1, 3_600, 0), value(store,
				"duration.between(datetime('2020-01-01T10:00Z'), datetime('2020-01-02T12:00+01:00'))"),
				"compared in the first value's zone");
			assertEquals(new CypherDuration(13, 0, 0, 0),
				value(store, "duration.inMonths(date('1984-10-11'), date('1985-11-25'))"));
			assertEquals(new CypherDuration(0, 410, 0, 0),
				value(store, "duration.inDays(date('1984-10-11'), date('1985-11-25'))"));
			assertEquals(new CypherDuration(0, 0, 90_000, 0), value(store,
				"duration.inSeconds(datetime('2020-01-01T00:00Z'), datetime('2020-01-02T01:00Z'))"));
			// Elapsed seconds across the spring-forward change in Vienna are 23 hours, not 24.
			assertEquals(new CypherDuration(0, 0, 23 * 3600, 0), value(store, "duration.inSeconds("
				+ "datetime('2021-03-27T12:00[Europe/Vienna]'), datetime('2021-03-28T12:00[Europe/Vienna]'))"));
			assertNull(value(store, "duration.between(null, date())"));
		}
	}

	// ---------------------------------------------------------------- arithmetic

	@Test
	public void testArithmetic() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(LocalDate.of(2020, 3, 1), value(store, "date('2020-01-31') + duration('P1M1D')"),
				"months first, clamped to Feb 29, then the day");
			assertEquals(LocalDate.of(2020, 2, 2), value(store, "date('2020-01-31') + duration('PT48H')"),
				"whole days in the seconds move a date");
			assertEquals(LocalDate.of(2019, 12, 31), value(store, "date('2020-01-31') - duration('P1M')"));
			assertEquals(LocalTime.of(1, 0), value(store, "localtime('23:00') + duration('PT2H')"),
				"a time of day wraps");
			assertEquals(ZonedDateTime.of(2021, 3, 28, 12, 0, 0, 0, ZoneId.of("Europe/Vienna")), value(store,
				"datetime('2021-03-27T12:00[Europe/Vienna]') + duration('P1D')"), "a day is a calendar day");
			assertEquals(new CypherDuration(1, 2, 3, 0), value(store, "duration('P1M') + duration('P2DT3S')"));
			assertEquals(new CypherDuration(2, 4, 6, 0), value(store, "duration('P1M2DT3S') * 2"));
			assertEquals(new CypherDuration(0, 15, 18_873, 0), value(store, "duration('P1M') / 2"));
			assertEquals(new CypherDuration(-1, 0, 0, 0), value(store, "-duration('P1M')"));
			assertEquals("2020-01-31x", value(store, "toString(date('2020-01-31')) + 'x'"));
			CypherTypeException difference = assertThrows(CypherTypeException.class,
				() -> value(store, "date('2020-01-31') - date('2020-01-01')"));
			assertTrue(difference.getMessage().contains("duration.between"), difference.getMessage());
			assertThrows(CypherTypeException.class, () -> value(store, "date('2020-01-31') + 1"));
		}
	}

	// ---------------------------------------------------------------- comparison, equality, ordering

	@Test
	public void testComparisonAndEquality() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(true, value(store, "date('2020-01-01') < date('2020-01-02')"));
			assertEquals(true, value(store, "datetime('2020-01-01T00:00+01:00') = datetime('2019-12-31T23:00Z')"),
				"the same instant in two zones");
			assertEquals(true, value(store, "datetime('2020-01-01T00:30+01:00') < datetime('2020-01-01T00:00Z')"));
			assertEquals(true, value(store, "time('10:00+01:00') = time('09:00Z')"));
			assertNull(value(store, "date('2020-01-01') < localdatetime('2020-01-02T00:00')"),
				"different temporal types do not compare");
			assertEquals(false, value(store, "date('2020-01-01') = localdatetime('2020-01-01T00:00')"));
			assertNull(value(store, "duration('P1M') < duration('P30D')"), "durations do not order");
			assertEquals(true, value(store, "duration('P1M') = duration({months: 1})"));
			assertEquals(false, value(store, "duration('P1M') = duration('P30D')"));
			assertEquals(true, value(store, "date('2020-01-01') IN [date('2020-01-01')]"));
		}
	}

	@Test
	public void testDistinctAndGroupingUseEquality() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(1, Cypher.query(store, "UNWIND [datetime('2020-01-01T00:00+01:00'), "
				+ "datetime('2019-12-31T23:00Z')] AS d RETURN DISTINCT d").size());
			assertEquals(List.of(2L), Cypher.query(store, "UNWIND [date('2020-01-01'), date('2020-01-01')] AS d "
				+ "RETURN count(*) AS c").column("c"));
		}
	}

	@Test
	public void testOrderingSortsChronologicallyAndByTypeRank() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(List.of(LocalDate.of(2019, 5, 1), LocalDate.of(2020, 1, 1), LocalDate.of(2020, 2, 1)),
				Cypher.query(store, "UNWIND [date('2020-02-01'), date('2019-05-01'), date('2020-01-01')] AS d "
					+ "RETURN d ORDER BY d").column("d"));
			// datetime before local datetime before date before time before local time before duration before string.
			List<Object> mixed = Cypher.query(store, "UNWIND ['s', duration('P1D'), localtime('10:00'), "
				+ "time('10:00Z'), date('2020-01-01'), localdatetime('2020-01-01T00:00'), "
				+ "datetime('2020-01-01T00:00Z')] AS v RETURN v ORDER BY v").column("v");
			assertInstanceOf(ZonedDateTime.class, mixed.get(0));
			assertInstanceOf(LocalDateTime.class, mixed.get(1));
			assertInstanceOf(LocalDate.class, mixed.get(2));
			assertInstanceOf(OffsetTime.class, mixed.get(3));
			assertInstanceOf(LocalTime.class, mixed.get(4));
			assertInstanceOf(CypherDuration.class, mixed.get(5));
			assertEquals("s", mixed.get(6));
			assertEquals(LocalDate.of(2020, 1, 1), Cypher.query(store, "UNWIND [date('2020-01-01'), "
				+ "date('2021-01-01')] AS d RETURN min(d) AS m").column("m").get(0));
		}
	}

	@Test
	public void testSumAndAvgOfDurations() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(new CypherDuration(1, 3, 0, 0), value(store,
				"[d IN [duration('P1M1D'), duration('P2D')] | d][0] + duration('P2D')"));
			assertEquals(List.of(new CypherDuration(1, 3, 0, 0)), Cypher.query(store,
				"UNWIND [duration('P1M1D'), duration('P2D')] AS d RETURN sum(d) AS s").column("s"));
			assertEquals(List.of(new CypherDuration(0, 2, 0, 0)), Cypher.query(store,
				"UNWIND [duration('P1D'), duration('P3D')] AS d RETURN avg(d) AS a").column("a"));
		}
	}

	@Test
	public void testTruncation() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(LocalDate.of(2017, 11, 1), value(store, "date.truncate('month', date('2017-11-11'))"));
			assertEquals(LocalDate.of(2010, 1, 1), value(store, "date.truncate('decade', date('2017-11-11'))"));
			assertEquals(LocalDate.of(2017, 11, 6), value(store, "date.truncate('week', date('2017-11-11'))"),
				"the Monday of that week");
			assertEquals(LocalDate.of(2017, 10, 1), value(store, "date.truncate('quarter', date('2017-11-11'))"));
			assertEquals(ZonedDateTime.of(2017, 11, 11, 12, 0, 0, 0, ZoneId.of("Europe/Vienna")), value(store,
				"datetime.truncate('hour', datetime('2017-11-11T12:31:14.645[Europe/Vienna]'))"));
			assertEquals(LocalTime.of(12, 31, 14, 645_000_000), value(store,
				"localtime.truncate('millisecond', localtime('12:31:14.645876'))"));
			assertEquals(LocalDate.of(2017, 11, 2), value(store,
				"date.truncate('month', date('2017-11-11'), {day: 2})"));
			assertThrows(CypherTypeException.class, () -> value(store, "date.truncate('hour', date('2017-11-11'))"));
		}
	}

	@Test
	public void testToStringIsIso() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals("2015-07-21", value(store, "toString(date('2015-07-21'))"));
			assertEquals("12:00:00", value(store, "toString(localtime('12:00'))"));
			assertEquals("12:00:00.5+01:00", value(store, "toString(time('12:00:00.5+01:00'))"));
			assertEquals("2015-07-21T21:40:32.142", value(store, "toString(localdatetime('2015-07-21T21:40:32.142'))"));
			assertEquals("2015-07-21T21:40:32+02:00[Europe/Vienna]",
				value(store, "toString(datetime('2015-07-21T21:40:32[Europe/Vienna]'))"));
			assertEquals("2015-07-21T21:40:32Z", value(store, "toString(datetime('2015-07-21T21:40:32Z'))"));
		}
	}

	@Test
	public void testParametersInJavaTimeTypes() throws Exception {
		try (GraphStore store = stores.open()) {
			assertEquals(true, value(store, "$d = date('2020-01-01')", Map.of("d", LocalDate.of(2020, 1, 1))));
			// An Instant and an OffsetDateTime are datetimes; a Period is a duration.
			assertEquals(ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
				value(store, "$i", Map.of("i", Instant.parse("2020-01-01T00:00:00Z"))));
			assertEquals(ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(2)),
				value(store, "$o", Map.of("o", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(2)))));
			assertEquals(new CypherDuration(14, 3, 0, 0), value(store, "$p", Map.of("p", Period.of(1, 2, 3))));
			assertEquals(new CypherDuration(0, 0, 90, 0),
				value(store, "$p", Map.of("p", java.time.Duration.ofSeconds(90))));
		}
	}

	// ---------------------------------------------------------------- storage

	@Test
	public void testEveryTypeIsStoredAndReadBackAsItself() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:T {d: date('2015-07-21'), lt: localtime('12:31:14.645876123'), "
				+ "t: time('12:31:14+01:00'), ldt: localdatetime('2015-07-21T21:40:32.142'), "
				+ "dt: datetime('2015-07-21T21:40:32.142[Europe/Vienna]'), "
				+ "odt: datetime('2015-07-21T21:40:32.142-03:30'), dur: duration('P1Y2M10DT-1.5S'), "
				+ "dates: [date('2020-01-01'), date('2020-01-02')]})");
			Map<String, Object> row = Cypher.query(store, "MATCH (n:T) RETURN n.d AS d, n.lt AS lt, n.t AS t, "
				+ "n.ldt AS ldt, n.dt AS dt, n.odt AS odt, n.dur AS dur, n.dates AS dates").row(0).asMap();
			assertEquals(LocalDate.of(2015, 7, 21), row.get("d"));
			assertEquals(LocalTime.of(12, 31, 14, 645_876_123), row.get("lt"));
			assertEquals(OffsetTime.of(12, 31, 14, 0, ZoneOffset.ofHours(1)), row.get("t"));
			assertEquals(LocalDateTime.of(2015, 7, 21, 21, 40, 32, 142_000_000), row.get("ldt"));
			ZonedDateTime dt = (ZonedDateTime) row.get("dt");
			assertEquals(ZoneId.of("Europe/Vienna"), dt.getZone(), "the region survives storage");
			assertEquals(ZonedDateTime.of(2015, 7, 21, 21, 40, 32, 142_000_000, ZoneId.of("Europe/Vienna")), dt);
			assertEquals(ZoneOffset.ofHoursMinutes(-3, -30), ((ZonedDateTime) row.get("odt")).getZone());
			assertEquals(new CypherDuration(14, 10, -2, 500_000_000), row.get("dur"));
			assertEquals(List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2)), row.get("dates"));
		}
	}

	@Test
	public void testStoredDatesFilterAndSortAsDates() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "UNWIND ['2021-03-01', '2019-12-31', '2020-06-15'] AS s CREATE (:E {at: date(s)})");
			assertEquals(List.of(LocalDate.of(2020, 6, 15), LocalDate.of(2021, 3, 1)), Cypher.query(store,
				"MATCH (e:E) WHERE e.at >= date('2020-01-01') RETURN e.at AS at ORDER BY at").column("at"));
			assertEquals(List.of(2021L), Cypher.query(store,
				"MATCH (e:E) WHERE e.at.month = 3 RETURN e.at.year AS y").column("y"));
		}
	}

	@Test
	public void testAnIndexedTemporalPropertyIsProbed() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("E", "at");
			Cypher.query(store, "CREATE (:E {at: datetime('2020-01-01T00:00+01:00'), d: duration('P1M')})");
			// The same instant spelled in another zone, which = calls equal and the index must find.
			assertEquals(1, Cypher.query(store, "MATCH (e:E {at: datetime('2019-12-31T23:00Z')}) RETURN e").size());
			assertEquals(1, Cypher.query(store, "MATCH (e:E) WHERE e.at = $at RETURN e",
				Map.of("at", Instant.parse("2019-12-31T23:00:00Z"))).size());
			store.createIndex("E", "d");
			assertEquals(1, Cypher.query(store, "MATCH (e:E {d: duration({months: 1})}) RETURN e").size());
		}
	}

	@Test
	public void testATemporalWrittenByTheJavaApiReadsAsTheCypherType() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("J", Properties.of().set("at", Instant.parse("2020-01-01T00:00:00Z"))
					.set("period", Period.of(0, 1, 2)).set("day", LocalDate.of(2020, 1, 1)));
				txn.commit();
			}
			Map<String, Object> row = Cypher.query(store, "MATCH (j:J) RETURN j.at AS at, j.period AS period, "
				+ "j.day AS day, j.at.year AS year").row(0).asMap();
			assertEquals(ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), row.get("at"),
				"a stored Instant is a datetime in UTC, no longer a string");
			assertEquals(new CypherDuration(1, 2, 0, 0), row.get("period"));
			assertEquals(LocalDate.of(2020, 1, 1), row.get("day"));
			assertEquals(2020L, row.get("year"));
			// Found through the index too, although the index holds an Instant and the probe is a datetime.
			store.createIndex("J", "at");
			assertEquals(1, Cypher.query(store, "MATCH (j:J {at: datetime('2020-01-01T01:00+01:00')}) RETURN j")
				.size());
		}
	}
}
