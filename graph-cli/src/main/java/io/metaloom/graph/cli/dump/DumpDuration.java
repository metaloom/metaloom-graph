package io.metaloom.graph.cli.dump;

import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.List;

/**
 * A duration property as a dump reads it back: months, days, seconds and nanoseconds, kept apart.
 *
 * <p>
 * The store takes any {@link TemporalAmount} whose units are among {@code MONTHS}, {@code DAYS}, {@code SECONDS} and
 * {@code NANOS}, which is exactly what this is. {@code java.time} has no type that holds both halves: a {@code Period}
 * has no seconds and a {@code Duration} has no months, and turning one into the other would change the value — a month
 * is not thirty days. graph-cli does not depend on graph-cypher, whose {@code CypherDuration} is the same idea, so the
 * dump carries its own, and keeps it package-private.
 * </p>
 *
 * <p>
 * {@code nanos} is in {@code [0, 999_999_999]}, a negative fraction carried into the seconds, as {@code CypherDuration}
 * normalises it; the decoder refuses any other value.
 * </p>
 */
record DumpDuration(long months, long days, long seconds, int nanos) implements TemporalAmount {

	private static final List<TemporalUnit> UNITS = List.of(ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.SECONDS,
		ChronoUnit.NANOS);

	DumpDuration {
		if (nanos < 0 || nanos >= 1_000_000_000) {
			throw new IllegalArgumentException("Nanoseconds " + nanos + " outside [0, 999999999]");
		}
	}

	/** The four components of any amount in years, months, days, seconds and nanos. */
	static DumpDuration from(TemporalAmount amount) {
		if (amount instanceof DumpDuration duration) {
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
					throw new DumpFormatException("A duration with a " + unit + " component cannot be written to a dump");
				}
			}
			return new DumpDuration(months, days, Math.addExact(seconds, Math.floorDiv(nanos, 1_000_000_000L)),
				(int) Math.floorMod(nanos, 1_000_000_000L));
		} catch (ArithmeticException | DateTimeException e) {
			throw new DumpFormatException("Duration " + amount + " cannot be written to a dump: " + e.getMessage());
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
		return step(temporal, 1);
	}

	@Override
	public Temporal subtractFrom(Temporal temporal) {
		return step(temporal, -1);
	}

	/** Zero components are skipped, so a duration of whole days can be added to a {@code LocalDate}. */
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
}
