package io.metaloom.graph.protocol;

import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.List;

/**
 * A Cypher {@code DURATION} as the wire decodes it: months, days, seconds and nanoseconds, kept apart.
 *
 * <p>
 * <b>Package-private on purpose.</b> graph-protocol and graph-client have locked public APIs, and the client must not
 * depend on graph-cypher, whose {@code CypherDuration} is the same idea. A caller sees a {@link TemporalAmount} —
 * {@code get(ChronoUnit.MONTHS)}, {@code addTo}, an ISO-8601 {@code toString} — which is everything a duration has to
 * offer without naming a type. The server turns one back into a {@code CypherDuration} when it arrives as a parameter.
 * </p>
 *
 * <p>
 * Normalised like {@code CypherDuration}: {@code nanos} is in {@code [0, 999_999_999]} and a negative fraction carries
 * into the seconds. The decoder refuses any other {@code nanos}, so equal durations are equal records.
 * </p>
 */
record WireDuration(long months, long days, long seconds, int nanos) implements TemporalAmount {

	private static final List<TemporalUnit> UNITS = List.of(ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.SECONDS,
		ChronoUnit.NANOS);

	WireDuration {
		if (nanos < 0 || nanos >= 1_000_000_000) {
			throw new IllegalArgumentException("Nanoseconds " + nanos + " outside [0, 999999999]");
		}
	}

	/**
	 * The four components of any amount whose units are among years, months, days, seconds and nanos — a
	 * {@code CypherDuration}, a {@code Period}, a {@code Duration}, or whatever the store hands back.
	 *
	 * @throws IllegalArgumentException
	 *             for an amount with any other unit, or components that overflow
	 */
	static WireDuration from(TemporalAmount amount) {
		if (amount instanceof WireDuration duration) {
			return duration;
		}
		try {
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
					throw new IllegalArgumentException("No wire encoding for a duration with a " + unit + " component");
				}
			}
			return new WireDuration(months, days, Math.addExact(seconds, Math.floorDiv(nanos, 1_000_000_000L)),
				(int) Math.floorMod(nanos, 1_000_000_000L));
		} catch (ArithmeticException | DateTimeException e) {
			throw new IllegalArgumentException("No wire encoding for duration " + amount + ": " + e.getMessage(), e);
		}
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

	/** Zero components are skipped, so a duration of whole days can be added to a {@code LocalDate}. */
	@Override
	public Temporal addTo(Temporal temporal) {
		return step(temporal, 1);
	}

	@Override
	public Temporal subtractFrom(Temporal temporal) {
		return step(temporal, -1);
	}

	private Temporal step(Temporal temporal, int sign) {
		Temporal out = temporal;
		long[] amounts = { months, days, seconds, nanos };
		for (int i = 0; i < amounts.length; i++) {
			if (amounts[i] != 0) {
				out = sign > 0 ? out.plus(amounts[i], UNITS.get(i)) : out.minus(amounts[i], UNITS.get(i));
			}
		}
		return out;
	}

	/** ISO 8601, character for character what {@code CypherDuration} prints: {@code P1Y2M3DT4H5M6.5S}, {@code PT0S}. */
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
