package io.metaloom.graph.cypher.func;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.TemporalAmount;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.metaloom.graph.cypher.exec.CypherTypeException;
import io.metaloom.graph.cypher.exec.Values;
import io.metaloom.graph.cypher.value.CypherDuration;

/**
 * Cypher's temporal types: construction, parsing, components, arithmetic and the duration functions.
 *
 * <h2>The six types, and the Java value that carries each</h2>
 *
 * <table>
 * <caption>Types</caption>
 * <tr><td>{@code DATE}</td><td>{@link LocalDate}</td></tr>
 * <tr><td>{@code LOCAL TIME}</td><td>{@link LocalTime}</td></tr>
 * <tr><td>{@code ZONED TIME}</td><td>{@link OffsetTime}</td></tr>
 * <tr><td>{@code LOCAL DATETIME}</td><td>{@link LocalDateTime}</td></tr>
 * <tr><td>{@code ZONED DATETIME}</td><td>{@link ZonedDateTime}, whose zone is an offset or a region</td></tr>
 * <tr><td>{@code DURATION}</td><td>{@link CypherDuration}</td></tr>
 * </table>
 *
 * <p>
 * Plain {@code java.time} values rather than wrappers, so a caller receives something it already knows how to use and
 * the storage layer stores them without knowing this module. A time zone a value does not name is UTC, which is Neo4j's
 * default for {@code db.temporal.timezone}.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a> section 1.2
 */
public final class Temporals {

	private Temporals() {
	}

	/** Days in an average Gregorian month, which is how a fraction of a month becomes days, as in Neo4j. */
	private static final BigDecimal AVG_DAYS_PER_MONTH = new BigDecimal("30.436875");

	private static final BigDecimal NANOS_PER_DAY = BigDecimal.valueOf(86_400_000_000_000L);

	private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

	/** Names this class answers, lower case, as {@link Functions} dispatches them. */
	static final Set<String> FUNCTIONS = Set.of(
		"date", "localtime", "time", "localdatetime", "datetime", "duration",
		"date.truncate", "localtime.truncate", "time.truncate", "localdatetime.truncate", "datetime.truncate",
		"date.statement", "date.transaction", "date.realtime",
		"localtime.statement", "localtime.transaction", "localtime.realtime",
		"time.statement", "time.transaction", "time.realtime",
		"localdatetime.statement", "localdatetime.transaction", "localdatetime.realtime",
		"datetime.statement", "datetime.transaction", "datetime.realtime",
		"datetime.fromepoch", "datetime.fromepochmillis",
		"duration.between", "duration.inmonths", "duration.indays", "duration.inseconds");

	/** The kinds a constructor builds. */
	private enum Kind {
		DATE("date"), LOCAL_TIME("localtime"), TIME("time"), LOCAL_DATETIME("localdatetime"), DATETIME("datetime");

		private final String function;

		Kind(String function) {
			this.function = function;
		}
	}

	/**
	 * One of {@link #FUNCTIONS}.
	 *
	 * @param now
	 *            the statement's clock reading, which the argument-less forms and {@code .statement()} and
	 *            {@code .transaction()} use; {@code .realtime()} reads the clock again
	 */
	static Object call(String name, String spelled, List<Object> args, Instant now) {
		int dot = name.indexOf('.');
		String base = dot < 0 ? name : name.substring(0, dot);
		String member = dot < 0 ? null : name.substring(dot + 1);
		if (base.equals("duration")) {
			return durationFunction(member, spelled, args);
		}
		if (base.equals("datetime") && "fromepoch".equals(member)) {
			arity(spelled, args, 2);
			if (args.get(0) == null || args.get(1) == null) {
				return null;
			}
			long seconds = integer(args.get(0), spelled);
			long nanos = integer(args.get(1), spelled);
			return ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), ZoneOffset.UTC);
		}
		if (base.equals("datetime") && "fromepochmillis".equals(member)) {
			arity(spelled, args, 1);
			if (args.get(0) == null) {
				return null;
			}
			return ZonedDateTime.ofInstant(Instant.ofEpochMilli(integer(args.get(0), spelled)), ZoneOffset.UTC);
		}
		Kind kind = switch (base) {
			case "date" -> Kind.DATE;
			case "localtime" -> Kind.LOCAL_TIME;
			case "time" -> Kind.TIME;
			case "localdatetime" -> Kind.LOCAL_DATETIME;
			case "datetime" -> Kind.DATETIME;
			default -> throw new CypherTypeException("Unknown function " + spelled + "()");
		};
		if (member == null) {
			if (args.size() > 1) {
				throw new CypherTypeException(spelled + "() takes at most one argument but got " + args.size());
			}
			return construct(kind, args.isEmpty() ? null : args.get(0), args.isEmpty(), now, spelled);
		}
		return switch (member) {
			case "statement", "transaction", "realtime" -> {
				if (args.size() > 1) {
					throw new CypherTypeException(spelled + "() takes at most a time zone but got " + args.size()
						+ " arguments");
				}
				Instant clock = member.equals("realtime") ? Instant.now() : now;
				ZoneId zone = args.isEmpty() || args.get(0) == null ? ZoneOffset.UTC : zone(args.get(0), spelled);
				yield fromClock(kind, clock, zone);
			}
			case "truncate" -> truncate(kind, args, spelled, now);
			default -> throw new CypherTypeException("Unknown function " + spelled + "()");
		};
	}

	// ---------------------------------------------------------------- construction

	private static Object construct(Kind kind, Object argument, boolean none, Instant now, String spelled) {
		if (none) {
			return fromClock(kind, now, ZoneOffset.UTC);
		}
		return switch (argument) {
			case null -> null;
			case String text -> parse(kind, text, spelled);
			case Map<?, ?> map -> fromMap(kind, map, now, spelled);
			default -> {
				if (isTemporal(argument) && !(argument instanceof CypherDuration)) {
					yield convert(kind, argument, null, null, spelled);
				}
				throw new CypherTypeException(spelled + "() takes a string, a map or a temporal value, not "
					+ Values.typeName(argument));
			}
		};
	}

	private static Object fromClock(Kind kind, Instant clock, ZoneId zone) {
		ZonedDateTime at = ZonedDateTime.ofInstant(clock, zone);
		return switch (kind) {
			case DATE -> at.toLocalDate();
			case LOCAL_TIME -> at.toLocalTime();
			case TIME -> at.toOffsetDateTime().toOffsetTime();
			case LOCAL_DATETIME -> at.toLocalDateTime();
			case DATETIME -> at;
		};
	}

	/**
	 * A temporal value as another kind: {@code date(datetime)}, {@code datetime(date)} and so on. A part the source
	 * lacks comes from {@code time} or is midnight; a zone it lacks comes from {@code zone} or is UTC. A target with a
	 * date cannot be made from a source without one.
	 */
	private static Object convert(Kind kind, Object source, LocalTime time, ZoneId zone, String spelled) {
		LocalDate date = dateOf(source);
		LocalTime localTime = time != null ? time : localTimeOf(source);
		ZoneId sourceZone = zoneOf(source);
		// A zoned source keeps its instant when it is given a different zone, as Neo4j does: datetime({datetime: d,
		// timezone: 'Asia/Tokyo'}) is the same moment seen from Tokyo.
		if (zone != null && sourceZone != null && source instanceof ZonedDateTime zoned) {
			ZonedDateTime moved = zoned.withZoneSameInstant(zone);
			date = moved.toLocalDate();
			localTime = time != null ? time : moved.toLocalTime();
			sourceZone = zone;
		} else if (zone != null && sourceZone != null && source instanceof OffsetTime offsetTime) {
			OffsetTime moved = offsetTime.withOffsetSameInstant(offsetFor(zone, LocalDate.now()));
			localTime = time != null ? time : moved.toLocalTime();
			sourceZone = zone;
		}
		ZoneId targetZone = zone != null ? zone : sourceZone != null ? sourceZone : ZoneOffset.UTC;
		switch (kind) {
			case DATE -> {
				return requireDate(date, source, spelled);
			}
			case LOCAL_TIME -> {
				return requireTime(localTime, source, spelled);
			}
			case TIME -> {
				LocalTime t = requireTime(localTime, source, spelled);
				return OffsetTime.of(t, offsetFor(targetZone, date != null ? date : LocalDate.now()));
			}
			case LOCAL_DATETIME -> {
				return LocalDateTime.of(requireDate(date, source, spelled), localTime != null ? localTime : LocalTime.MIDNIGHT);
			}
			case DATETIME -> {
				return ZonedDateTime.of(requireDate(date, source, spelled),
					localTime != null ? localTime : LocalTime.MIDNIGHT, targetZone);
			}
			default -> throw new IllegalStateException();
		}
	}

	private static LocalDate requireDate(LocalDate date, Object source, String spelled) {
		if (date == null) {
			throw new CypherTypeException(spelled + "() cannot take its date from a " + Values.typeName(source)
				+ ", which has none");
		}
		return date;
	}

	private static LocalTime requireTime(LocalTime time, Object source, String spelled) {
		if (time == null) {
			throw new CypherTypeException(spelled + "() cannot take its time from a " + Values.typeName(source)
				+ ", which has none");
		}
		return time;
	}

	private static ZoneOffset offsetFor(ZoneId zone, LocalDate date) {
		if (zone instanceof ZoneOffset offset) {
			return offset;
		}
		// A region has no single offset; a time of day with one is given the offset the region has on that date.
		return zone.getRules().getOffset(date.atStartOfDay(zone).toInstant());
	}

	private static final Set<String> DATE_KEYS = Set.of("year", "month", "day", "week", "dayofweek", "ordinalday",
		"quarter", "dayofquarter");

	private static final Set<String> TIME_KEYS = Set.of("hour", "minute", "second", "millisecond", "microsecond",
		"nanosecond");

	/**
	 * {@code date({year: 2020, month: 5})}, {@code datetime({date: d, hour: 12, timezone: 'Europe/Vienna'})}, and the
	 * epoch forms of {@code datetime}. A field the map names overrides the one the {@code date}, {@code time} or
	 * {@code datetime} entry supplies; a field neither names is the smallest value it can take.
	 */
	private static Object fromMap(Kind kind, Map<?, ?> raw, Instant now, String spelled) {
		Map<String, Object> map = new java.util.LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
			if (!DATE_KEYS.contains(key) && !TIME_KEYS.contains(key) && !Set.of("timezone", "date", "time", "datetime",
				"epochseconds", "epochmillis").contains(key)) {
				throw new CypherTypeException(spelled + "() does not know the field '" + entry.getKey() + "'");
			}
			if (entry.getValue() == null) {
				// A null field is read as an absent one: it cannot be a year, and refusing it would fail every row whose
				// property happens to be missing.
				continue;
			}
			map.put(key, entry.getValue());
		}
		boolean hasDateField = map.keySet().stream().anyMatch(DATE_KEYS::contains);
		boolean hasTimeField = map.keySet().stream().anyMatch(TIME_KEYS::contains);
		ZoneId zone = map.containsKey("timezone") ? zone(map.get("timezone"), spelled) : null;

		if (map.containsKey("epochseconds") || map.containsKey("epochmillis")) {
			if (kind != Kind.DATETIME) {
				throw new CypherTypeException(spelled + "() cannot be built from an epoch; datetime() can");
			}
			Instant instant = map.containsKey("epochseconds")
				? Instant.ofEpochSecond(integer(map.get("epochseconds"), spelled))
				: Instant.ofEpochMilli(integer(map.get("epochmillis"), spelled));
			instant = instant.plusNanos(subSecondNanos(map, spelled));
			return ZonedDateTime.ofInstant(instant, zone != null ? zone : ZoneOffset.UTC);
		}

		Object base = map.containsKey("datetime") ? map.get("datetime") : null;
		Object dateSource = map.containsKey("date") ? map.get("date") : base;
		Object timeSource = map.containsKey("time") ? map.get("time") : base;
		for (Object source : new Object[] { dateSource, timeSource }) {
			if (source != null && (!isTemporal(source) || source instanceof CypherDuration)) {
				throw new CypherTypeException(spelled + "() takes a temporal value for date, time and datetime, "
					+ "not " + Values.typeName(source));
			}
		}

		LocalDate date = null;
		if (kind != Kind.LOCAL_TIME && kind != Kind.TIME) {
			LocalDate from = dateSource == null ? null : dateOf(moveTo(dateSource, zone));
			if (dateSource != null && from == null) {
				throw new CypherTypeException(spelled + "() cannot take its date from a " + Values.typeName(dateSource));
			}
			date = buildDate(map, from, spelled);
		} else if (hasDateField) {
			throw new CypherTypeException(spelled + "() has no date, so it cannot take "
				+ map.keySet().stream().filter(DATE_KEYS::contains).findFirst().orElseThrow());
		}

		LocalTime time = null;
		ZoneId sourceZone = null;
		if (kind != Kind.DATE) {
			Object moved = timeSource == null ? null : moveTo(timeSource, zone);
			LocalTime from = moved == null ? null : localTimeOf(moved);
			if (map.containsKey("time") && from == null) {
				throw new CypherTypeException(spelled + "() cannot take its time from a "
					+ Values.typeName(timeSource));
			}
			time = buildTime(map, from, spelled);
			sourceZone = moved == null ? null : zoneOf(moved);
			if (sourceZone == null && dateSource != null && timeSource == null) {
				sourceZone = zoneOf(dateSource);
			}
		} else if (hasTimeField) {
			throw new CypherTypeException("date() has no time, so it cannot take "
				+ map.keySet().stream().filter(TIME_KEYS::contains).findFirst().orElseThrow());
		}

		ZoneId targetZone = zone != null ? zone : sourceZone != null ? sourceZone : ZoneOffset.UTC;
		if (zone != null && (kind == Kind.LOCAL_TIME || kind == Kind.LOCAL_DATETIME || kind == Kind.DATE)) {
			throw new CypherTypeException(spelled + "() has no time zone, so it cannot take one");
		}
		return switch (kind) {
			case DATE -> date;
			case LOCAL_TIME -> time;
			case TIME -> OffsetTime.of(time, offsetFor(targetZone, LocalDate.ofInstant(now, ZoneOffset.UTC)));
			case LOCAL_DATETIME -> LocalDateTime.of(date, time);
			case DATETIME -> ZonedDateTime.of(date, time, targetZone);
		};
	}

	/** A zoned source seen from {@code zone}, or the source itself when either has no zone. */
	private static Object moveTo(Object source, ZoneId zone) {
		if (zone == null) {
			return source;
		}
		if (source instanceof ZonedDateTime zoned) {
			return zoned.withZoneSameInstant(zone);
		}
		if (source instanceof OffsetTime time) {
			return time.withOffsetSameInstant(offsetFor(zone, LocalDate.now()));
		}
		return source;
	}

	private static LocalDate buildDate(Map<String, Object> map, LocalDate from, String spelled) {
		try {
			if (map.containsKey("week") || map.containsKey("dayofweek") && !map.containsKey("month")) {
				LocalDate anchor = from != null ? from : LocalDate.of(1, 1, 1);
				long weekYear = map.containsKey("year") ? integer(map.get("year"), spelled)
					: anchor.get(IsoFields.WEEK_BASED_YEAR);
				long week = map.containsKey("week") ? integer(map.get("week"), spelled)
					: from != null ? anchor.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) : 1;
				long dayOfWeek = map.containsKey("dayofweek") ? integer(map.get("dayofweek"), spelled)
					: from != null ? anchor.getDayOfWeek().getValue() : 1;
				LocalDate start = LocalDate.of(Math.toIntExact(weekYear), 1, 4)
					.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1).with(DayOfWeek.MONDAY);
				LocalDate built = start.plusWeeks(week - 1).plusDays(dayOfWeek - 1);
				if (week < 1 || week > start.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).getMaximum() || dayOfWeek < 1
					|| dayOfWeek > 7) {
					throw new DateTimeException("Week " + week + " day " + dayOfWeek + " is not a day of " + weekYear);
				}
				return built;
			}
			if (map.containsKey("ordinalday")) {
				long year = map.containsKey("year") ? integer(map.get("year"), spelled) : from != null ? from.getYear()
					: 1;
				return LocalDate.ofYearDay(Math.toIntExact(year), Math.toIntExact(integer(map.get("ordinalday"),
					spelled)));
			}
			if (map.containsKey("quarter") || map.containsKey("dayofquarter")) {
				long year = map.containsKey("year") ? integer(map.get("year"), spelled) : from != null ? from.getYear()
					: 1;
				long quarter = map.containsKey("quarter") ? integer(map.get("quarter"), spelled)
					: from != null ? from.get(IsoFields.QUARTER_OF_YEAR) : 1;
				long day = map.containsKey("dayofquarter") ? integer(map.get("dayofquarter"), spelled)
					: from != null ? from.get(IsoFields.DAY_OF_QUARTER) : 1;
				LocalDate start = LocalDate.of(Math.toIntExact(year), 1, 1).with(IsoFields.QUARTER_OF_YEAR, quarter)
					.with(IsoFields.DAY_OF_QUARTER, 1);
				if (day < 1 || day > start.range(IsoFields.DAY_OF_QUARTER).getMaximum()) {
					throw new DateTimeException("Day " + day + " is not a day of quarter " + quarter);
				}
				return start.plusDays(day - 1);
			}
			if (from == null && !map.containsKey("year")) {
				throw new CypherTypeException(spelled + "() needs at least a year, as in " + spelled
					+ "({year: 2020})");
			}
			if (from == null && map.containsKey("day") && !map.containsKey("month")) {
				throw new CypherTypeException(spelled + "() cannot take a day without its month");
			}
			int year = map.containsKey("year") ? Math.toIntExact(integer(map.get("year"), spelled)) : from.getYear();
			int month = map.containsKey("month") ? Math.toIntExact(integer(map.get("month"), spelled))
				: from != null ? from.getMonthValue() : 1;
			int day = map.containsKey("day") ? Math.toIntExact(integer(map.get("day"), spelled))
				: from != null ? from.getDayOfMonth() : 1;
			return LocalDate.of(year, month, day);
		} catch (DateTimeException | ArithmeticException e) {
			throw new CypherTypeException(spelled + "(): " + e.getMessage());
		}
	}

	private static LocalTime buildTime(Map<String, Object> map, LocalTime from, String spelled) {
		try {
			if (from == null && !map.containsKey("hour") && map.keySet().stream().anyMatch(TIME_KEYS::contains)) {
				throw new CypherTypeException(spelled + "() needs the hour when it is given a smaller time field");
			}
			int hour = map.containsKey("hour") ? Math.toIntExact(integer(map.get("hour"), spelled))
				: from != null ? from.getHour() : 0;
			int minute = map.containsKey("minute") ? Math.toIntExact(integer(map.get("minute"), spelled))
				: from != null ? from.getMinute() : 0;
			int second = map.containsKey("second") ? Math.toIntExact(integer(map.get("second"), spelled))
				: from != null ? from.getSecond() : 0;
			boolean subSecond = map.containsKey("millisecond") || map.containsKey("microsecond")
				|| map.containsKey("nanosecond");
			long nano = subSecond ? subSecondNanos(map, spelled) : from != null ? from.getNano() : 0;
			return LocalTime.of(hour, minute, second, Math.toIntExact(nano));
		} catch (DateTimeException | ArithmeticException e) {
			throw new CypherTypeException(spelled + "(): " + e.getMessage());
		}
	}

	/** {@code millisecond * 1e6 + microsecond * 1e3 + nanosecond}, each in its own range, as Neo4j checks them. */
	private static long subSecondNanos(Map<String, Object> map, String spelled) {
		long milli = map.containsKey("millisecond") ? integer(map.get("millisecond"), spelled) : 0;
		long micro = map.containsKey("microsecond") ? integer(map.get("microsecond"), spelled) : 0;
		long nano = map.containsKey("nanosecond") ? integer(map.get("nanosecond"), spelled) : 0;
		long microLimit = map.containsKey("millisecond") ? 1_000 : 1_000_000;
		long nanoLimit = map.containsKey("microsecond") ? 1_000 : map.containsKey("millisecond") ? 1_000_000
			: 1_000_000_000;
		if (milli < 0 || milli >= 1_000 || micro < 0 || micro >= microLimit || nano < 0 || nano >= nanoLimit) {
			throw new CypherTypeException(spelled + "(): millisecond, microsecond and nanosecond are each out of "
				+ "range together or apart: " + milli + ", " + micro + ", " + nano);
		}
		return milli * 1_000_000 + micro * 1_000 + nano;
	}

	// ---------------------------------------------------------------- parsing

	private static final String DATE = "(?<year>\\d{4}|[+-]\\d{4,9})(?:-?(?:(?<month>\\d{2})(?:-?(?<day>\\d{2}))?"
		+ "|W(?<week>\\d{2})(?:-?(?<dow>\\d))?|(?<ordinal>\\d{3})))?";

	private static final String TIME = "(?<hour>\\d{2})(?::?(?<minute>\\d{2})(?::?(?<second>\\d{2})"
		+ "(?:[.,](?<fraction>\\d{1,9}))?)?)?";

	private static final String ZONE = "(?<offset>Z|[+-]\\d{2}(?::?\\d{2})?)?(?:\\[(?<zone>[^\\]]+)])?";

	private static final Pattern DATE_ONLY = Pattern.compile(DATE);

	private static final Pattern TIME_ONLY = Pattern.compile(TIME + ZONE);

	private static final Pattern DATE_TIME = Pattern.compile(DATE + "(?:T" + TIME + ")?" + ZONE);

	/**
	 * ISO 8601 in the forms Neo4j accepts: extended and basic, calendar, week ({@code 2015-W30-2}) and ordinal
	 * ({@code 2015-202}) dates, a fraction of a second up to nine digits, and an offset ({@code Z}, {@code +01},
	 * {@code +0100}, {@code +01:00}) and a region in brackets. A string that is none of them is refused rather than
	 * read as null, so a typo in a literal is a message rather than a missing row.
	 */
	static Object parse(Kind kind, String text, String spelled) {
		String trimmed = text.strip();
		try {
			switch (kind) {
				case DATE -> {
					Matcher m = DATE_ONLY.matcher(trimmed);
					if (!m.matches()) {
						throw unparsable(spelled, text);
					}
					return date(m);
				}
				case LOCAL_TIME, TIME -> {
					Matcher m = TIME_ONLY.matcher(trimmed);
					if (!m.matches()) {
						throw unparsable(spelled, text);
					}
					LocalTime time = time(m);
					if (kind == Kind.LOCAL_TIME) {
						if (m.group("offset") != null || m.group("zone") != null) {
							throw new CypherTypeException(spelled + "() has no time zone, but '" + text + "' names one");
						}
						return time;
					}
					ZoneId zone = parsedZone(m);
					return OffsetTime.of(time, offsetFor(zone == null ? ZoneOffset.UTC : zone, LocalDate.now()));
				}
				default -> {
					Matcher m = DATE_TIME.matcher(trimmed);
					if (!m.matches()) {
						throw unparsable(spelled, text);
					}
					LocalDate date = date(m);
					LocalTime time = m.group("hour") == null ? LocalTime.MIDNIGHT : time(m);
					if (kind == Kind.LOCAL_DATETIME) {
						if (m.group("offset") != null || m.group("zone") != null) {
							throw new CypherTypeException(spelled + "() has no time zone, but '" + text + "' names one");
						}
						return LocalDateTime.of(date, time);
					}
					ZoneId zone = parsedZone(m);
					String offset = m.group("offset");
					if (m.group("zone") != null && offset != null) {
						// Both given: the region decides, and the offset has to be one it has at that moment.
						ZonedDateTime zoned = ZonedDateTime.ofLocal(LocalDateTime.of(date, time), zone,
							offset(offset));
						if (!zoned.getOffset().equals(offset(offset))) {
							throw new CypherTypeException(spelled + "(): '" + text + "' gives an offset its region "
								+ "does not have at that time");
						}
						return zoned;
					}
					return ZonedDateTime.of(date, time, zone == null ? ZoneOffset.UTC : zone);
				}
			}
		} catch (DateTimeException | ArithmeticException e) {
			throw new CypherTypeException(spelled + "() cannot read '" + text + "': " + e.getMessage());
		}
	}

	private static CypherTypeException unparsable(String spelled, String text) {
		return new CypherTypeException(spelled + "() cannot read '" + text + "' as ISO 8601");
	}

	private static LocalDate date(Matcher m) {
		int year = Integer.parseInt(m.group("year"));
		if (m.group("week") != null) {
			int week = Integer.parseInt(m.group("week"));
			int day = m.group("dow") == null ? 1 : Integer.parseInt(m.group("dow"));
			LocalDate start = LocalDate.of(year, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1)
				.with(DayOfWeek.MONDAY);
			if (week < 1 || week > start.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).getMaximum() || day < 1 || day > 7) {
				throw new DateTimeException("week " + week + " day " + day + " is not a day of " + year);
			}
			return start.plusWeeks(week - 1L).plusDays(day - 1L);
		}
		if (m.group("ordinal") != null) {
			return LocalDate.ofYearDay(year, Integer.parseInt(m.group("ordinal")));
		}
		int month = m.group("month") == null ? 1 : Integer.parseInt(m.group("month"));
		int day = m.group("day") == null ? 1 : Integer.parseInt(m.group("day"));
		return LocalDate.of(year, month, day);
	}

	private static LocalTime time(Matcher m) {
		int hour = Integer.parseInt(m.group("hour"));
		int minute = m.group("minute") == null ? 0 : Integer.parseInt(m.group("minute"));
		int second = m.group("second") == null ? 0 : Integer.parseInt(m.group("second"));
		int nano = 0;
		if (m.group("fraction") != null) {
			String digits = (m.group("fraction") + "000000000").substring(0, 9);
			nano = Integer.parseInt(digits);
		}
		return LocalTime.of(hour, minute, second, nano);
	}

	private static ZoneId parsedZone(Matcher m) {
		if (m.group("zone") != null) {
			return ZoneId.of(m.group("zone"));
		}
		return m.group("offset") == null ? null : offset(m.group("offset"));
	}

	private static ZoneOffset offset(String text) {
		if (text.equals("Z")) {
			return ZoneOffset.UTC;
		}
		String digits = text.substring(1).replace(":", "");
		int hours = Integer.parseInt(digits.substring(0, 2));
		int minutes = digits.length() > 2 ? Integer.parseInt(digits.substring(2)) : 0;
		return text.charAt(0) == '-' ? ZoneOffset.ofHoursMinutes(-hours, -minutes)
			: ZoneOffset.ofHoursMinutes(hours, minutes);
	}

	/** A {@code timezone} value: an offset string, a region name, or {@code 'Z'}. */
	static ZoneId zone(Object value, String spelled) {
		if (!(value instanceof String text)) {
			throw new CypherTypeException(spelled + "() takes a time zone as a string, not " + Values.typeName(value));
		}
		try {
			if (text.matches("Z|[+-]\\d{2}(:?\\d{2})?")) {
				return offset(text);
			}
			return ZoneId.of(text);
		} catch (DateTimeException e) {
			throw new CypherTypeException(spelled + "(): '" + text + "' is not a time zone");
		}
	}

	// ---------------------------------------------------------------- truncation

	/**
	 * {@code date.truncate('month', d)}, and the same for every kind: the value with every field below the unit at its
	 * smallest, then any fields the optional map names applied over that.
	 */
	private static Object truncate(Kind kind, List<Object> args, String spelled, Instant now) {
		if (args.size() < 2 || args.size() > 3) {
			throw new CypherTypeException(spelled + "() takes a unit, a temporal value and optionally a map");
		}
		if (args.get(0) == null || args.get(1) == null) {
			return null;
		}
		if (!(args.get(0) instanceof String unit)) {
			throw new CypherTypeException(spelled + "() takes its unit as a string, not "
				+ Values.typeName(args.get(0)));
		}
		Object source = args.get(1);
		if (!isTemporal(source) || source instanceof CypherDuration) {
			throw new CypherTypeException(spelled + "() truncates a temporal value, not " + Values.typeName(source));
		}
		LocalDate date = dateOf(source);
		LocalTime time = localTimeOf(source);
		String u = unit.toLowerCase(Locale.ROOT);
		boolean dateUnit = Set.of("millennium", "century", "decade", "year", "weekyear", "quarter", "month", "week",
			"day").contains(u);
		if (dateUnit && date == null) {
			throw new CypherTypeException(spelled + "() cannot truncate a " + Values.typeName(source) + " to a "
				+ unit);
		}
		if (date != null) {
			date = switch (u) {
				case "millennium" -> LocalDate.of(Math.floorDiv(date.getYear(), 1000) * 1000, 1, 1);
				case "century" -> LocalDate.of(Math.floorDiv(date.getYear(), 100) * 100, 1, 1);
				case "decade" -> LocalDate.of(Math.floorDiv(date.getYear(), 10) * 10, 1, 1);
				case "year" -> date.withDayOfYear(1);
				case "weekyear" -> date.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1).with(DayOfWeek.MONDAY);
				case "quarter" -> date.with(IsoFields.DAY_OF_QUARTER, 1);
				case "month" -> date.withDayOfMonth(1);
				case "week" -> date.with(TemporalAdjusters.previousOrSame(WeekFields.ISO.getFirstDayOfWeek()));
				default -> date;
			};
		}
		if (time != null || date != null) {
			LocalTime base = time == null ? LocalTime.MIDNIGHT : time;
			time = switch (u) {
				case "millennium", "century", "decade", "year", "weekyear", "quarter", "month", "week", "day" ->
					LocalTime.MIDNIGHT;
				case "hour" -> base.truncatedTo(ChronoUnit.HOURS);
				case "minute" -> base.truncatedTo(ChronoUnit.MINUTES);
				case "second" -> base.truncatedTo(ChronoUnit.SECONDS);
				case "millisecond" -> base.truncatedTo(ChronoUnit.MILLIS);
				case "microsecond" -> base.truncatedTo(ChronoUnit.MICROS);
				default -> throw new CypherTypeException(spelled + "() does not know the unit '" + unit + "'");
			};
		}
		if (kind == Kind.DATE && !dateUnit) {
			throw new CypherTypeException(spelled + "() cannot truncate to a " + unit + ", which is smaller than a day");
		}
		ZoneId zone = zoneOf(source);
		Object truncated = switch (kind) {
			case DATE -> date;
			case LOCAL_TIME -> time;
			case TIME -> OffsetTime.of(time, offsetFor(zone == null ? ZoneOffset.UTC : zone,
				date != null ? date : LocalDate.ofInstant(now, ZoneOffset.UTC)));
			case LOCAL_DATETIME -> LocalDateTime.of(requireDate(date, source, spelled), time);
			case DATETIME -> ZonedDateTime.of(requireDate(date, source, spelled), time,
				zone == null ? ZoneOffset.UTC : zone);
		};
		if (args.size() == 3 && args.get(2) != null) {
			if (!(args.get(2) instanceof Map<?, ?> overrides)) {
				throw new CypherTypeException(spelled + "() takes its overrides as a map");
			}
			Map<Object, Object> merged = new java.util.LinkedHashMap<>(overrides);
			merged.put(kind == Kind.DATE ? "date" : kind == Kind.LOCAL_TIME || kind == Kind.TIME ? "time" : "datetime",
				truncated);
			if (kind == Kind.LOCAL_DATETIME) {
				merged.remove("datetime");
				merged.put("date", truncated);
				merged.put("time", ((LocalDateTime) truncated).toLocalTime());
			}
			return fromMap(kind, merged, now, spelled);
		}
		return truncated;
	}

	// ---------------------------------------------------------------- parts of a value

	public static boolean isTemporal(Object value) {
		return value instanceof LocalDate || value instanceof LocalTime || value instanceof OffsetTime
			|| value instanceof LocalDateTime || value instanceof ZonedDateTime || value instanceof CypherDuration;
	}

	private static LocalDate dateOf(Object value) {
		return switch (value) {
			case LocalDate date -> date;
			case LocalDateTime dateTime -> dateTime.toLocalDate();
			case ZonedDateTime dateTime -> dateTime.toLocalDate();
			default -> null;
		};
	}

	private static LocalTime localTimeOf(Object value) {
		return switch (value) {
			case LocalTime time -> time;
			case OffsetTime time -> time.toLocalTime();
			case LocalDateTime dateTime -> dateTime.toLocalTime();
			case ZonedDateTime dateTime -> dateTime.toLocalTime();
			default -> null;
		};
	}

	private static ZoneId zoneOf(Object value) {
		return switch (value) {
			case OffsetTime time -> time.getOffset();
			case ZonedDateTime dateTime -> dateTime.getZone();
			default -> null;
		};
	}

	/**
	 * {@code d.year}, {@code t.hour}, {@code dt.timezone}, {@code dur.days} and the rest. A component the type does not
	 * have is an error naming it, rather than null: {@code date.hour} has an answer nowhere.
	 */
	public static Object component(Object value, String key) {
		if (value instanceof CypherDuration duration) {
			return durationComponent(duration, key);
		}
		LocalDate date = dateOf(value);
		LocalTime time = localTimeOf(value);
		Object answer = switch (key) {
			case "year" -> date == null ? null : (long) date.getYear();
			case "quarter" -> date == null ? null : (long) date.get(IsoFields.QUARTER_OF_YEAR);
			case "month" -> date == null ? null : (long) date.getMonthValue();
			case "week" -> date == null ? null : (long) date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
			case "weekYear" -> date == null ? null : (long) date.get(IsoFields.WEEK_BASED_YEAR);
			case "dayOfQuarter" -> date == null ? null : (long) date.get(IsoFields.DAY_OF_QUARTER);
			case "quarterDay" -> date == null ? null : (long) date.get(IsoFields.DAY_OF_QUARTER);
			case "day" -> date == null ? null : (long) date.getDayOfMonth();
			case "ordinalDay" -> date == null ? null : (long) date.getDayOfYear();
			case "dayOfWeek", "weekDay" -> date == null ? null : (long) date.getDayOfWeek().getValue();
			case "hour" -> time == null ? null : (long) time.getHour();
			case "minute" -> time == null ? null : (long) time.getMinute();
			case "second" -> time == null ? null : (long) time.getSecond();
			case "millisecond" -> time == null ? null : (long) (time.getNano() / 1_000_000);
			case "microsecond" -> time == null ? null : (long) (time.getNano() / 1_000);
			case "nanosecond" -> time == null ? null : (long) time.getNano();
			case "timezone" -> switch (value) {
				case ZonedDateTime zoned -> zoned.getZone() instanceof ZoneOffset offset ? offsetText(offset)
					: zoned.getZone().getId();
				case OffsetTime offsetTime -> offsetText(offsetTime.getOffset());
				default -> null;
			};
			case "offset" -> offsetOf(value) == null ? null : offsetText(offsetOf(value));
			case "offsetMinutes" -> offsetOf(value) == null ? null : (long) (offsetOf(value).getTotalSeconds() / 60);
			case "offsetSeconds" -> offsetOf(value) == null ? null : (long) offsetOf(value).getTotalSeconds();
			case "epochSeconds" -> value instanceof ZonedDateTime zoned ? zoned.toEpochSecond() : null;
			case "epochMillis" -> value instanceof ZonedDateTime zoned ? zoned.toInstant().toEpochMilli() : null;
			default -> null;
		};
		if (answer == null) {
			throw new CypherTypeException("A " + Values.typeName(value) + " has no component '" + key + "'");
		}
		return answer;
	}

	private static ZoneOffset offsetOf(Object value) {
		return switch (value) {
			case ZonedDateTime zoned -> zoned.getOffset();
			case OffsetTime time -> time.getOffset();
			default -> null;
		};
	}

	private static String offsetText(ZoneOffset offset) {
		return offset.getTotalSeconds() == 0 ? "Z" : offset.getId();
	}

	private static Object durationComponent(CypherDuration d, String key) {
		long months = d.months();
		long days = d.days();
		long seconds = d.seconds();
		long nanos = d.nanos();
		return switch (key) {
			case "years" -> months / 12;
			case "quarters" -> months / 3;
			case "months" -> months;
			case "weeks" -> days / 7;
			case "days" -> days;
			case "hours" -> seconds / 3600;
			case "minutes" -> seconds / 60;
			case "seconds" -> seconds;
			case "milliseconds" -> Values.exact("milliseconds", () -> Math.addExact(Math.multiplyExact(seconds, 1_000L),
				nanos / 1_000_000));
			case "microseconds" -> Values.exact("microseconds", () -> Math.addExact(Math.multiplyExact(seconds,
				1_000_000L), nanos / 1_000));
			case "nanoseconds" -> Values.exact("nanoseconds", () -> Math.addExact(Math.multiplyExact(seconds,
				1_000_000_000L), nanos));
			case "quartersOfYear" -> (months % 12) / 3;
			case "monthsOfQuarter" -> months % 3;
			case "monthsOfYear" -> months % 12;
			case "daysOfWeek" -> days % 7;
			case "minutesOfHour" -> (seconds / 60) % 60;
			case "secondsOfMinute" -> seconds % 60;
			case "millisecondsOfSecond" -> nanos / 1_000_000;
			case "microsecondsOfSecond" -> nanos / 1_000;
			case "nanosecondsOfSecond" -> nanos;
			default -> throw new CypherTypeException("A Duration has no component '" + key + "'");
		};
	}

	// ---------------------------------------------------------------- arithmetic

	/** {@code temporal + duration}, {@code duration + duration}; null when neither shape applies. */
	public static Object add(Object left, Object right) {
		if (left instanceof CypherDuration a && right instanceof CypherDuration b) {
			return plus(a, b);
		}
		if (left instanceof CypherDuration d && isTemporal(right)) {
			return shift(right, d, 1);
		}
		if (right instanceof CypherDuration d && isTemporal(left)) {
			return shift(left, d, 1);
		}
		return null;
	}

	/** {@code temporal - duration}, {@code duration - duration}; null when neither shape applies. */
	public static Object subtract(Object left, Object right) {
		if (left instanceof CypherDuration a && right instanceof CypherDuration b) {
			return plus(a, negate(b));
		}
		if (right instanceof CypherDuration d && isTemporal(left)) {
			return shift(left, d, -1);
		}
		if (isTemporal(left) && isTemporal(right)) {
			throw new CypherTypeException("Two temporal values cannot be subtracted; use duration.between(a, b)");
		}
		return null;
	}

	public static CypherDuration negate(CypherDuration d) {
		try {
			return CypherDuration.of(Math.negateExact(d.months()), Math.negateExact(d.days()),
				Math.negateExact(d.seconds()), -(long) d.nanos());
		} catch (ArithmeticException e) {
			throw new CypherTypeException("Duration overflow in -: the result does not fit in 64 bits");
		}
	}

	private static CypherDuration plus(CypherDuration a, CypherDuration b) {
		try {
			return CypherDuration.of(Math.addExact(a.months(), b.months()), Math.addExact(a.days(), b.days()),
				Math.addExact(a.seconds(), b.seconds()), (long) a.nanos() + b.nanos());
		} catch (ArithmeticException e) {
			throw new CypherTypeException("Duration overflow in +: the result does not fit in 64 bits");
		}
	}

	/**
	 * A temporal value moved by a duration, each part of the duration applied to what the value has: a date takes the
	 * months, the days and the whole days in the seconds; a time of day takes only the seconds and wraps at midnight.
	 */
	private static Object shift(Object value, CypherDuration d, int sign) {
		try {
			long months = sign * d.months();
			long days = sign * d.days();
			long seconds = sign * d.seconds();
			long nanos = sign * (long) d.nanos();
			return switch (value) {
				case LocalDate date -> date.plusMonths(months).plusDays(days)
					.plusDays((seconds + Math.floorDiv(nanos, 1_000_000_000L)) / 86_400);
				case LocalTime time -> time.plusSeconds(seconds).plusNanos(nanos);
				case OffsetTime time -> time.plusSeconds(seconds).plusNanos(nanos);
				case LocalDateTime dateTime -> dateTime.plusMonths(months).plusDays(days).plusSeconds(seconds)
					.plusNanos(nanos);
				case ZonedDateTime dateTime -> dateTime.plusMonths(months).plusDays(days).plusSeconds(seconds)
					.plusNanos(nanos);
				default -> throw new CypherTypeException("A duration cannot be added to " + Values.typeName(value));
			};
		} catch (DateTimeException | ArithmeticException e) {
			throw new CypherTypeException("The result of moving " + Values.stringOf(value) + " by " + d
				+ " is outside the range a temporal value can hold");
		}
	}

	/** {@code duration * number}; a fraction cascades: months into days at 30.436875, days into seconds. */
	public static CypherDuration multiply(CypherDuration d, Number factor) {
		return scaled(d, Values.isIntegral(factor) ? new BigDecimal(factor.longValue())
			: new BigDecimal(factor.doubleValue()), "*");
	}

	public static CypherDuration divide(CypherDuration d, Number divisor) {
		BigDecimal by = Values.isIntegral(divisor) ? new BigDecimal(divisor.longValue())
			: new BigDecimal(divisor.doubleValue());
		if (by.signum() == 0) {
			throw new CypherTypeException("/ by zero");
		}
		return scaled(d, BigDecimal.ONE.divide(by, java.math.MathContext.DECIMAL128), "/");
	}

	private static CypherDuration scaled(CypherDuration d, BigDecimal factor, String operator) {
		if (factor.stripTrailingZeros().scale() <= 0) {
			try {
				long f = factor.longValueExact();
				return CypherDuration.of(Math.multiplyExact(d.months(), f), Math.multiplyExact(d.days(), f),
					Math.multiplyExact(d.seconds(), f), Math.multiplyExact((long) d.nanos(), f));
			} catch (ArithmeticException e) {
				throw new CypherTypeException("Duration overflow in " + operator + ": the result does not fit");
			}
		}
		return cascade(new BigDecimal(d.months()).multiply(factor), new BigDecimal(d.days()).multiply(factor),
			new BigDecimal(d.seconds()).multiply(NANOS_PER_SECOND).add(new BigDecimal(d.nanos())).multiply(factor),
			operator);
	}

	/**
	 * Months, days and nanoseconds that may each carry a fraction, folded down the way Neo4j folds them: the fraction
	 * of a month becomes days, the fraction of a day becomes seconds, and what is left of a nanosecond is rounded.
	 */
	private static CypherDuration cascade(BigDecimal months, BigDecimal days, BigDecimal nanos, String operator) {
		try {
			BigDecimal wholeMonths = months.setScale(0, RoundingMode.DOWN);
			days = days.add(months.subtract(wholeMonths).multiply(AVG_DAYS_PER_MONTH));
			BigDecimal wholeDays = days.setScale(0, RoundingMode.DOWN);
			nanos = nanos.add(days.subtract(wholeDays).multiply(NANOS_PER_DAY));
			BigDecimal wholeNanos = nanos.setScale(0, RoundingMode.HALF_EVEN);
			BigDecimal[] split = wholeNanos.divideAndRemainder(NANOS_PER_SECOND);
			return CypherDuration.of(wholeMonths.longValueExact(), wholeDays.longValueExact(),
				split[0].longValueExact(), split[1].longValueExact());
		} catch (ArithmeticException e) {
			throw new CypherTypeException("Duration overflow in " + operator + ": the result does not fit");
		}
	}

	// ---------------------------------------------------------------- comparison

	/**
	 * {@code <} and friends between two temporal values: same type only, zoned values by their instant, and a duration
	 * never, because {@code P1M} against {@code P30D} has no answer. Null when they cannot be compared.
	 */
	public static Integer compare(Object left, Object right) {
		return switch (left) {
			case LocalDate a when right instanceof LocalDate b -> a.compareTo(b);
			case LocalTime a when right instanceof LocalTime b -> a.compareTo(b);
			case OffsetTime a when right instanceof OffsetTime b -> utcNanos(a) == utcNanos(b) ? 0
				: Long.compare(utcNanos(a), utcNanos(b));
			case LocalDateTime a when right instanceof LocalDateTime b -> a.compareTo(b);
			case ZonedDateTime a when right instanceof ZonedDateTime b -> a.toInstant().compareTo(b.toInstant());
			default -> null;
		};
	}

	/**
	 * Cypher equality between two temporal values, both non-null: the same type, and the same instant for a zoned one.
	 */
	public static boolean equal(Object left, Object right) {
		if (left instanceof CypherDuration a && right instanceof CypherDuration b) {
			return a.equals(b);
		}
		Integer order = compare(left, right);
		return order != null && order == 0;
	}

	/** A time of day with an offset, as nanoseconds into the UTC day — the instant it names, ignoring the date. */
	private static long utcNanos(OffsetTime time) {
		return time.toLocalTime().toNanoOfDay() - time.getOffset().getTotalSeconds() * 1_000_000_000L;
	}

	/** A key that equals another exactly when {@link #equal} says the two values are equal. */
	public static Object groupingKey(Object value) {
		return switch (value) {
			case ZonedDateTime zoned -> new TemporalKey("datetime", zoned.toInstant());
			case OffsetTime time -> new TemporalKey("time", utcNanos(time));
			default -> value;
		};
	}

	private record TemporalKey(String kind, Object identity) {
	}

	/** The rank of a temporal type in openCypher's orderability, relative to the others. */
	public static int orderRank(Object value) {
		return switch (value) {
			case ZonedDateTime ignored -> 0;
			case LocalDateTime ignored -> 1;
			case LocalDate ignored -> 2;
			case OffsetTime ignored -> 3;
			case LocalTime ignored -> 4;
			case CypherDuration ignored -> 5;
			default -> -1;
		};
	}

	/** The sort order within one temporal type: chronological, ties broken by zone; durations by component. */
	public static int orderWithin(Object left, Object right) {
		if (left instanceof CypherDuration a && right instanceof CypherDuration b) {
			int order = Long.compare(a.months(), b.months());
			order = order != 0 ? order : Long.compare(a.days(), b.days());
			order = order != 0 ? order : Long.compare(a.seconds(), b.seconds());
			return order != 0 ? order : Integer.compare(a.nanos(), b.nanos());
		}
		Integer order = compare(left, right);
		if (order != null && order != 0) {
			return order;
		}
		if (left instanceof ZonedDateTime a && right instanceof ZonedDateTime b) {
			return a.getZone().getId().compareTo(b.getZone().getId());
		}
		if (left instanceof OffsetTime a && right instanceof OffsetTime b) {
			return a.getOffset().compareTo(b.getOffset());
		}
		return order == null ? 0 : order;
	}

	// ---------------------------------------------------------------- text

	private static final DateTimeFormatter ZONED = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	/** ISO 8601 as Neo4j prints it: seconds always shown, a fraction only when there is one, a region in brackets. */
	public static String text(Object value) {
		return switch (value) {
			case LocalDate date -> date.toString();
			case LocalTime time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time);
			case OffsetTime time -> DateTimeFormatter.ISO_OFFSET_TIME.format(time);
			case LocalDateTime dateTime -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
			case ZonedDateTime dateTime -> ZONED.format(dateTime)
				+ (dateTime.getZone() instanceof ZoneOffset ? "" : "[" + dateTime.getZone().getId() + "]");
			default -> value.toString();
		};
	}

	public static String typeName(Object value) {
		return switch (value) {
			case LocalDate ignored -> "Date";
			case LocalTime ignored -> "LocalTime";
			case OffsetTime ignored -> "Time";
			case LocalDateTime ignored -> "LocalDateTime";
			case ZonedDateTime ignored -> "DateTime";
			case CypherDuration ignored -> "Duration";
			default -> null;
		};
	}

	/**
	 * A temporal value from outside — a parameter, or a value the store hands back — in the shape this module uses:
	 * an {@code Instant} or an {@code OffsetDateTime} becomes a {@code ZonedDateTime}, and any duration-like
	 * {@code TemporalAmount} (a {@code Period}, a {@code java.time.Duration}, the store's own) a {@link CypherDuration}.
	 * Anything else is returned as it is.
	 */
	public static Object normalise(Object value) {
		return switch (value) {
			case Instant instant -> ZonedDateTime.ofInstant(instant, ZoneOffset.UTC);
			case OffsetDateTime offset -> offset.toZonedDateTime();
			case CypherDuration duration -> duration;
			case TemporalAmount amount -> {
				try {
					yield CypherDuration.from(amount);
				} catch (DateTimeException | ArithmeticException e) {
					throw new CypherTypeException("A " + value.getClass().getSimpleName() + " cannot be read as a "
						+ "duration: " + e.getMessage());
				}
			}
			default -> value;
		};
	}

	// ---------------------------------------------------------------- durations

	private static final Pattern ISO_DURATION = Pattern.compile("([-+]?)P(?:([-+]?[0-9.,]+)Y)?(?:([-+]?[0-9.,]+)M)?"
		+ "(?:([-+]?[0-9.,]+)W)?(?:([-+]?[0-9.,]+)D)?(?:T(?:([-+]?[0-9.,]+)H)?(?:([-+]?[0-9.,]+)M)?"
		+ "(?:([-+]?[0-9.,]+)S)?)?", Pattern.CASE_INSENSITIVE);

	private static final Pattern DATE_LIKE_DURATION = Pattern.compile(
		"([-+]?)P(\\d{4})-?(\\d{2})-?(\\d{2})T(\\d{2}):?(\\d{2}):?(\\d{2}(?:[.,]\\d{1,9})?)");

	private static Object durationFunction(String member, String spelled, List<Object> args) {
		if (member == null) {
			arity(spelled, args, 1);
			return switch (args.get(0)) {
				case null -> null;
				case String text -> parseDuration(text, spelled);
				case Map<?, ?> map -> durationFromMap(map, spelled);
				case CypherDuration duration -> duration;
				default -> throw new CypherTypeException("duration() takes a string or a map, not "
					+ Values.typeName(args.get(0)));
			};
		}
		arity(spelled, args, 2);
		Object from = args.get(0);
		Object to = args.get(1);
		if (from == null || to == null) {
			return null;
		}
		for (Object value : List.of(from, to)) {
			if (!isTemporal(value) || value instanceof CypherDuration) {
				throw new CypherTypeException(spelled + "() takes two temporal values, not " + Values.typeName(value));
			}
		}
		return switch (member) {
			case "between" -> between(from, to, true, true, true);
			case "inmonths" -> between(from, to, true, false, false);
			case "indays" -> between(from, to, false, true, false);
			case "inseconds" -> between(from, to, false, false, true);
			default -> throw new CypherTypeException("Unknown function " + spelled + "()");
		};
	}

	/**
	 * The duration from {@code from} to {@code to}, in whichever of months, days and seconds are asked for, each as
	 * large as it can be before the next smaller unit takes the rest — as Neo4j's {@code duration.between} and its
	 * {@code inMonths}, {@code inDays} and {@code inSeconds} forms do.
	 *
	 * <p>
	 * Zoned values are compared in {@code from}'s zone, so a day is a calendar day there. A value without a date is
	 * measured on its time of day alone; a value without a time is midnight.
	 * </p>
	 */
	private static CypherDuration between(Object from, Object to, boolean inMonths, boolean inDays, boolean inSeconds) {
		ZoneId zone = zoneOf(from);
		Object aligned = to;
		if (zone != null && to instanceof ZonedDateTime zoned) {
			aligned = zoned.withZoneSameInstant(zone);
		} else if (zone != null && to instanceof OffsetTime time) {
			aligned = time.withOffsetSameInstant(offsetFor(zone, LocalDate.now()));
		}
		LocalDate dateA = dateOf(from);
		LocalDate dateB = dateOf(aligned);
		LocalTime timeA = localTimeOf(from);
		LocalTime timeB = localTimeOf(aligned);
		if (dateA == null || dateB == null) {
			// Only the time of day can be measured.
			if (!inSeconds) {
				return new CypherDuration(0, 0, 0, 0);
			}
			long nanos = (timeB == null ? 0 : timeB.toNanoOfDay()) - (timeA == null ? 0 : timeA.toNanoOfDay());
			return CypherDuration.of(0, 0, 0, nanos);
		}
		LocalDateTime start = LocalDateTime.of(dateA, timeA == null ? LocalTime.MIDNIGHT : timeA);
		LocalDateTime end = LocalDateTime.of(dateB, timeB == null ? LocalTime.MIDNIGHT : timeB);
		if (from instanceof ZonedDateTime a && aligned instanceof ZonedDateTime b && !inMonths && !inDays) {
			// Seconds alone are elapsed time, which across a daylight saving change is not the wall-clock difference.
			java.time.Duration elapsed = java.time.Duration.between(a.toInstant(), b.toInstant());
			return CypherDuration.of(0, 0, elapsed.getSeconds(), elapsed.getNano());
		}
		long months = 0;
		long days = 0;
		LocalDateTime cursor = start;
		if (inMonths) {
			months = cursor.until(end, ChronoUnit.MONTHS);
			cursor = cursor.plusMonths(months);
		}
		if (inDays) {
			days = cursor.until(end, ChronoUnit.DAYS);
			cursor = cursor.plusDays(days);
		}
		if (!inSeconds) {
			return CypherDuration.of(months, days, 0, 0);
		}
		long nanos = cursor.until(end, ChronoUnit.NANOS);
		return CypherDuration.of(months, days, 0, nanos);
	}

	/**
	 * {@code PnYnMnWnDTnHnMnS}, any component signed and any one with a fraction, and the alternative form
	 * {@code P2012-02-02T14:37:21.545}. A fraction cascades into the smaller units as it does in a map.
	 */
	static CypherDuration parseDuration(String text, String spelled) {
		String trimmed = text.strip();
		Matcher alternative = DATE_LIKE_DURATION.matcher(trimmed);
		if (alternative.matches()) {
			Map<String, Object> map = new java.util.LinkedHashMap<>();
			map.put("years", Long.parseLong(alternative.group(2)));
			map.put("months", Long.parseLong(alternative.group(3)));
			map.put("days", Long.parseLong(alternative.group(4)));
			map.put("hours", Long.parseLong(alternative.group(5)));
			map.put("minutes", Long.parseLong(alternative.group(6)));
			map.put("seconds", new BigDecimal(alternative.group(7).replace(',', '.')));
			CypherDuration parsed = durationFromMap(map, spelled);
			return alternative.group(1).equals("-") ? negate(parsed) : parsed;
		}
		Matcher m = ISO_DURATION.matcher(trimmed);
		if (!m.matches() || trimmed.matches("[-+]?P(T)?") || trimmed.endsWith("T")) {
			throw new CypherTypeException(spelled + "() cannot read '" + text + "' as an ISO 8601 duration");
		}
		String[] names = { null, null, "years", "months", "weeks", "days", "hours", "minutes", "seconds" };
		Map<String, Object> map = new java.util.LinkedHashMap<>();
		for (int group = 2; group <= 8; group++) {
			String part = m.group(group);
			if (part != null) {
				try {
					map.put(names[group], new BigDecimal(part.replace(',', '.')));
				} catch (NumberFormatException e) {
					throw new CypherTypeException(spelled + "() cannot read '" + part + "' in '" + text + "'");
				}
			}
		}
		CypherDuration parsed = durationFromMap(map, spelled);
		return m.group(1).equals("-") ? negate(parsed) : parsed;
	}

	private static final Map<String, BigDecimal[]> DURATION_UNITS = Map.ofEntries(
		// { months, days, nanoseconds } per unit
		Map.entry("years", new BigDecimal[] { BigDecimal.valueOf(12), BigDecimal.ZERO, BigDecimal.ZERO }),
		Map.entry("quarters", new BigDecimal[] { BigDecimal.valueOf(3), BigDecimal.ZERO, BigDecimal.ZERO }),
		Map.entry("months", new BigDecimal[] { BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO }),
		Map.entry("weeks", new BigDecimal[] { BigDecimal.ZERO, BigDecimal.valueOf(7), BigDecimal.ZERO }),
		Map.entry("days", new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO }),
		Map.entry("hours", new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(3_600_000_000_000L) }),
		Map.entry("minutes", new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(60_000_000_000L) }),
		Map.entry("seconds", new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, NANOS_PER_SECOND }),
		Map.entry("milliseconds", new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000L) }),
		Map.entry("microseconds", new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(1_000L) }),
		Map.entry("nanoseconds", new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE }));

	/** {@code duration({days: 1, hours: 12})}; every component may be a float, and the fractions cascade. */
	static CypherDuration durationFromMap(Map<?, ?> map, String spelled) {
		BigDecimal months = BigDecimal.ZERO;
		BigDecimal days = BigDecimal.ZERO;
		BigDecimal nanos = BigDecimal.ZERO;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
			BigDecimal[] unit = DURATION_UNITS.get(key);
			if (unit == null) {
				throw new CypherTypeException(spelled + "() does not know the field '" + entry.getKey() + "'");
			}
			Object raw = entry.getValue();
			if (raw == null) {
				continue;
			}
			BigDecimal amount = switch (raw) {
				case BigDecimal decimal -> decimal;
				case Number number when Values.isIntegral(number) -> BigDecimal.valueOf(number.longValue());
				case Number number when Double.isFinite(number.doubleValue()) -> new BigDecimal(number.doubleValue());
				default -> throw new CypherTypeException(spelled + "() takes a finite number for '" + entry.getKey()
					+ "', not " + Values.typeName(raw));
			};
			months = months.add(amount.multiply(unit[0]));
			days = days.add(amount.multiply(unit[1]));
			nanos = nanos.add(amount.multiply(unit[2]));
		}
		return cascade(months, days, nanos, spelled);
	}

	// ---------------------------------------------------------------- helpers

	private static long integer(Object value, String spelled) {
		if (value instanceof Number number && Values.isIntegral(number)) {
			return number.longValue();
		}
		throw new CypherTypeException(spelled + "() takes an integer here, not " + Values.typeName(value)
			+ (value instanceof Number ? " " + value : ""));
	}

	private static void arity(String spelled, List<Object> args, int arity) {
		if (args.size() != arity) {
			throw new CypherTypeException(spelled + "() takes " + arity + " argument" + (arity == 1 ? "" : "s")
				+ " but got " + args.size());
		}
	}
}
