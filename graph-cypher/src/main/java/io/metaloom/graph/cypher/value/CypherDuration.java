package io.metaloom.graph.cypher.value;

import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.List;

/**
 * A Cypher {@code DURATION}: months, days, seconds and nanoseconds, kept apart.
 *
 * <p>
 * The four components are independent because their lengths are: a month is 28 to 31 days and a day is 23 to 25 hours
 * across a daylight saving change, so {@code P1M} and {@code P30D} are different durations and adding either to a date
 * gives a different answer. {@code java.time} splits the same idea into {@code Period} (years, months, days) and
 * {@code Duration} (seconds, nanos), and neither holds the other half, which is why this type exists.
 * </p>
 *
 * <p>
 * Normalised as Neo4j does: the nanosecond part is always in {@code [0, 999_999_999]} and carries into the seconds, so
 * {@code -1.5} seconds is {@code seconds = -2, nanos = 500_000_000}. Months and days are never folded into each other or
 * into seconds. It is a {@link TemporalAmount}, so the storage layer stores it without knowing this type.
 * </p>
 */
public record CypherDuration(long months, long days, long seconds, int nanos) implements TemporalAmount {

	private static final List<TemporalUnit> UNITS = List.of(ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.SECONDS,
		ChronoUnit.NANOS);

	public CypherDuration {
		if (nanos < 0 || nanos >= 1_000_000_000) {
			seconds = Math.addExact(seconds, Math.floorDiv(nanos, 1_000_000_000));
			nanos = Math.floorMod(nanos, 1_000_000_000);
		}
	}

	/** A duration from components that may each be out of range, normalising the nanoseconds into the seconds. */
	public static CypherDuration of(long months, long days, long seconds, long nanos) {
		return new CypherDuration(months, days, Math.addExact(seconds, Math.floorDiv(nanos, 1_000_000_000L)),
			(int) Math.floorMod(nanos, 1_000_000_000L));
	}

	/** Any amount whose units are among years, months, days, seconds and nanos — {@code Period}, {@code Duration}. */
	public static CypherDuration from(TemporalAmount amount) {
		if (amount instanceof CypherDuration duration) {
			return duration;
		}
		long months = 0;
		long days = 0;
		long seconds = 0;
		long nanos = 0;
		for (TemporalUnit unit : amount.getUnits()) {
			long value = amount.get(unit);
			if (unit == ChronoUnit.YEARS) {
				months = Math.addExact(months, Math.multiplyExact(value, 12L));
			} else if (unit == ChronoUnit.MONTHS) {
				months = Math.addExact(months, value);
			} else if (unit == ChronoUnit.DAYS) {
				days = Math.addExact(days, value);
			} else if (unit == ChronoUnit.SECONDS) {
				seconds = Math.addExact(seconds, value);
			} else if (unit == ChronoUnit.NANOS) {
				nanos = Math.addExact(nanos, value);
			} else {
				throw new UnsupportedTemporalTypeException("A duration has no " + unit + " component");
			}
		}
		return of(months, days, seconds, nanos);
	}

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
		if (unit == ChronoUnit.NANOS) {
			return nanos;
		}
		throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
	}

	@Override
	public List<TemporalUnit> getUnits() {
		return UNITS;
	}

	@Override
	public Temporal addTo(Temporal temporal) {
		Temporal out = temporal;
		if (months != 0) {
			out = out.plus(months, ChronoUnit.MONTHS);
		}
		if (days != 0) {
			out = out.plus(days, ChronoUnit.DAYS);
		}
		if (seconds != 0) {
			out = out.plus(seconds, ChronoUnit.SECONDS);
		}
		if (nanos != 0) {
			out = out.plus(nanos, ChronoUnit.NANOS);
		}
		return out;
	}

	@Override
	public Temporal subtractFrom(Temporal temporal) {
		Temporal out = temporal;
		if (months != 0) {
			out = out.minus(months, ChronoUnit.MONTHS);
		}
		if (days != 0) {
			out = out.minus(days, ChronoUnit.DAYS);
		}
		if (seconds != 0) {
			out = out.minus(seconds, ChronoUnit.SECONDS);
		}
		if (nanos != 0) {
			out = out.minus(nanos, ChronoUnit.NANOS);
		}
		return out;
	}

	/**
	 * ISO 8601, as Neo4j prints it: {@code P1Y2M3DT4H5M6.5S}, {@code PT0S} for zero, and a negative second part as
	 * {@code PT-1.5S} rather than as the normalised {@code -2 + 0.5}.
	 */
	@Override
	public String toString() {
		StringBuilder out = new StringBuilder("P");
		long years = months / 12;
		long restMonths = months % 12;
		if (years != 0) {
			out.append(years).append('Y');
		}
		if (restMonths != 0) {
			out.append(restMonths).append('M');
		}
		if (days != 0) {
			out.append(days).append('D');
		}
		if (seconds != 0 || nanos != 0) {
			out.append('T');
			// Back to a signed (seconds, fraction) pair: -2 s + 0.5 s prints as -1.5 s.
			long wholeSeconds = seconds;
			long fraction = nanos;
			boolean negative = seconds < 0;
			if (negative && fraction != 0) {
				wholeSeconds += 1;
				fraction = 1_000_000_000L - fraction;
			}
			long hours = wholeSeconds / 3600;
			long minutes = (wholeSeconds % 3600) / 60;
			long secs = wholeSeconds % 60;
			if (hours != 0) {
				out.append(hours).append('H');
			}
			if (minutes != 0) {
				out.append(minutes).append('M');
			}
			if (secs != 0 || fraction != 0) {
				if (negative && secs == 0) {
					out.append('-');
				}
				out.append(secs);
				if (fraction != 0) {
					String digits = String.format("%09d", fraction);
					int end = digits.length();
					while (end > 0 && digits.charAt(end - 1) == '0') {
						end--;
					}
					out.append('.').append(digits, 0, end);
				}
				out.append('S');
			}
		}
		if (out.length() == 1) {
			out.append("T0S");
		}
		return out.toString();
	}
}
