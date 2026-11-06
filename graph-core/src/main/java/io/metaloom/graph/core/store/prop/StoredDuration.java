package io.metaloom.graph.core.store.prop;

import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.List;

/**
 * A duration as the store keeps it: months, days, seconds and nanoseconds, each its own component, the way Cypher
 * defines one. What every {@link TemporalAmount} property reads back as.
 *
 * <p>
 * The components are not converted into each other — a month is not a number of days, and a day is not 86,400 seconds
 * across a daylight saving change — except that {@code nanos} is always in {@code [0, 1e9)}, with the carry folded
 * into {@code seconds} (floor division, so {@code -0.5s} is {@code seconds = -1, nanos = 500_000_000}). Two durations
 * are the same value exactly when the four components are equal.
 * </p>
 *
 * <p>
 * Internal on purpose: the package is not exported, so this type is not part of the locked API. A caller sees it as a
 * {@link TemporalAmount}.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 4
 */
public record StoredDuration(long months, long days, long seconds, int nanos) implements TemporalAmount {

	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private static final List<TemporalUnit> UNITS = List.of(ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.SECONDS,
		ChronoUnit.NANOS);

	public static final StoredDuration ZERO = new StoredDuration(0, 0, 0, 0);

	/**
	 * @throws IllegalArgumentException
	 *             when {@code nanos} is outside {@code [0, 1e9)}; use {@link #of(long, long, long, long)} to normalise
	 */
	public StoredDuration {
		if (nanos < 0 || nanos >= NANOS_PER_SECOND) {
			throw new IllegalArgumentException("nanos must be in [0, 1e9), was " + nanos);
		}
	}

	/** Normalises {@code nanos} into {@code [0, 1e9)}, carrying into {@code seconds} with exact arithmetic. */
	public static StoredDuration of(long months, long days, long seconds, long nanos) {
		try {
			long totalSeconds = Math.addExact(seconds, Math.floorDiv(nanos, NANOS_PER_SECOND));
			return new StoredDuration(months, days, totalSeconds, (int) Math.floorMod(nanos, NANOS_PER_SECOND));
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Duration overflows: " + seconds + "s " + nanos + "ns", e);
		}
	}

	/**
	 * Any temporal amount whose units are a subset of years, months, days, seconds and nanoseconds — a
	 * {@link java.time.Period}, a {@link java.time.Duration}, or another implementation such as Cypher's own duration.
	 *
	 * @throws IllegalArgumentException
	 *             for another unit (hours, weeks, ...), which has no single place in the four components, or when the
	 *             components overflow a long
	 */
	public static StoredDuration of(TemporalAmount amount) {
		if (amount instanceof StoredDuration stored) {
			return stored;
		}
		long years = 0;
		long months = 0;
		long days = 0;
		long seconds = 0;
		long nanos = 0;
		for (TemporalUnit unit : amount.getUnits()) {
			long value = amount.get(unit);
			if (unit == ChronoUnit.YEARS) {
				years = value;
			} else if (unit == ChronoUnit.MONTHS) {
				months = value;
			} else if (unit == ChronoUnit.DAYS) {
				days = value;
			} else if (unit == ChronoUnit.SECONDS) {
				seconds = value;
			} else if (unit == ChronoUnit.NANOS) {
				nanos = value;
			} else {
				throw new IllegalArgumentException("Unsupported duration unit " + unit + " in "
					+ amount.getClass().getName() + "; a stored duration has years, months, days, seconds and nanos");
			}
		}
		try {
			return of(Math.addExact(Math.multiplyExact(years, 12L), months), days, seconds, nanos);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Duration overflows: " + years + " years " + months + " months", e);
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

	@Override
	public Temporal addTo(Temporal temporal) {
		Temporal result = temporal;
		if (months != 0) {
			result = result.plus(months, ChronoUnit.MONTHS);
		}
		if (days != 0) {
			result = result.plus(days, ChronoUnit.DAYS);
		}
		if (seconds != 0) {
			result = result.plus(seconds, ChronoUnit.SECONDS);
		}
		if (nanos != 0) {
			result = result.plus(nanos, ChronoUnit.NANOS);
		}
		return result;
	}

	@Override
	public Temporal subtractFrom(Temporal temporal) {
		Temporal result = temporal;
		if (months != 0) {
			result = result.minus(months, ChronoUnit.MONTHS);
		}
		if (days != 0) {
			result = result.minus(days, ChronoUnit.DAYS);
		}
		if (seconds != 0) {
			result = result.minus(seconds, ChronoUnit.SECONDS);
		}
		if (nanos != 0) {
			result = result.minus(nanos, ChronoUnit.NANOS);
		}
		return result;
	}

	/**
	 * ISO-8601, {@code P1Y2M3DT4H5M6.5S}; zero is {@code PT0S}. Each component keeps its own sign, and a negative
	 * fraction of a second is written as {@link java.time.Duration} writes it: {@code -0.5s} is {@code PT-0.5S}.
	 */
	@Override
	public String toString() {
		if (months == 0 && days == 0 && seconds == 0 && nanos == 0) {
			return "PT0S";
		}
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
			// java.time.Duration's own rendering of the time part: hours, minutes and a signed fractional second.
			String time = java.time.Duration.ofSeconds(seconds, nanos).toString();
			out.append(time, 1, time.length());
		}
		return out.toString();
	}
}
