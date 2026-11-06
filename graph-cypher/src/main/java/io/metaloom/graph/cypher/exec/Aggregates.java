package io.metaloom.graph.cypher.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.metaloom.graph.cypher.ast.Ast;

/**
 * The aggregating functions, folded over a group of rows.
 *
 * <p>
 * All of them <b>skip nulls</b>, and that is not an implementation shortcut but the specified behaviour:
 * {@code count(n.age)} counts the rows where an age exists, {@code avg} averages over those rows, and
 * {@code collect} builds a list without them. The one exception is {@code count(*)}, which counts <em>rows</em> and
 * therefore counts everything.
 * </p>
 *
 * <p>
 * The difference matters in a way that is easy to get wrong: over five people of whom two have no age recorded,
 * {@code count(*)} is 5 and {@code count(p.age)} is 3 — and {@code avg(p.age)} divides by 3, not by 5.
 * </p>
 */
final class Aggregates {

	private Aggregates() {
	}

	static Object fold(Evaluator evaluator, Ast.FunctionCall call, List<Row> group) {
		String name = call.name().toLowerCase(Locale.ROOT);

		// count(*) is the only aggregate with no argument: it counts rows rather than values, so a row whose every
		// property is null still counts.
		if (name.equals("count") && call.arguments().isEmpty()) {
			return (long) group.size();
		}
		if (call.arguments().size() != 1) {
			throw new CypherTypeException(call.name() + "() takes exactly one argument"
				+ (name.equals("count") ? " or *" : ""));
		}

		List<Object> values = new ArrayList<>(group.size());
		for (Row row : group) {
			Object value = evaluator.evaluate(call.arguments().get(0), row);
			if (value != null) {
				values.add(value);
			}
		}
		if (call.distinct()) {
			values = distinct(values);
		}

		return switch (name) {
			case "count" -> (long) values.size();
			case "collect" -> values;
			case "sum" -> sum(values);
			case "avg" -> average(values);
			case "min" -> extreme(values, true);
			case "max" -> extreme(values, false);
			default -> throw new CypherTypeException("Unknown aggregate " + call.name() + "()");
		};
	}

	/** Zero over an empty group, not null: summing nothing is nothing, and Cypher says that is 0. */
	private static Object sum(List<Object> values) {
		if (!values.isEmpty() && values.get(0) instanceof io.metaloom.graph.cypher.value.CypherDuration) {
			// Durations add component by component, as + does. Mixed with numbers it is a type error, as + is.
			Object total = values.get(0);
			for (int i = 1; i < values.size(); i++) {
				if (!(values.get(i) instanceof io.metaloom.graph.cypher.value.CypherDuration)) {
					throw new CypherTypeException("sum() cannot add a " + Values.typeName(values.get(i))
						+ " to a Duration");
				}
				total = Values.add(total, values.get(i));
			}
			return total;
		}
		boolean integral = true;
		double asDouble = 0;
		long asLong = 0;
		// Net wraps past either end. The true sum is asLong + wraps * 2^64, so it fits exactly when this is zero — and
		// only matters at all if the result stays an integer.
		long wraps = 0;
		for (Object value : values) {
			Number number = Values.number(value, "sum");
			if (Values.isIntegral(number)) {
				long next = asLong + number.longValue();
				if (((asLong ^ next) & (number.longValue() ^ next)) < 0) {
					wraps += number.longValue() > 0 ? 1 : -1;
				}
				asLong = next;
			} else {
				integral = false;
			}
			asDouble += number.doubleValue();
		}
		if (integral && wraps != 0) {
			throw new CypherTypeException("Integer overflow in sum(): the result does not fit in 64 bits");
		}
		return integral ? (Object) asLong : (Object) asDouble;
	}

	/** Null over an empty group: the average of nothing is not zero, it is unknown. */
	private static Object average(List<Object> values) {
		if (values.isEmpty()) {
			return null;
		}
		if (values.get(0) instanceof io.metaloom.graph.cypher.value.CypherDuration) {
			Object total = sum(values);
			return Values.divide(total, (long) values.size());
		}
		double total = 0;
		for (Object value : values) {
			total += Values.number(value, "avg").doubleValue();
		}
		return total / values.size();
	}

	private static Object extreme(List<Object> values, boolean minimum) {
		Object best = null;
		for (Object value : values) {
			if (best == null) {
				best = value;
				continue;
			}
			int order = Values.orderForSort(value, best);
			if (minimum ? order < 0 : order > 0) {
				best = value;
			}
		}
		return best;
	}

	/** Distinct under Cypher equivalence, so that 1 and 1.0 collapse and two reads of one node collapse. */
	private static List<Object> distinct(List<Object> values) {
		List<Object> kept = new ArrayList<>();
		java.util.Set<Object> seen = new java.util.HashSet<>();
		for (Object value : values) {
			if (seen.add(Values.groupingKey(value))) {
				kept.add(value);
			}
		}
		return kept;
	}
}
