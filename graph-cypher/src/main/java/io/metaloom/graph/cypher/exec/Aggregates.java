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
		boolean integral = true;
		double asDouble = 0;
		long asLong = 0;
		for (Object value : values) {
			Number number = Values.number(value, "sum");
			if (Values.isIntegral(number)) {
				asLong += number.longValue();
			} else {
				integral = false;
			}
			asDouble += number.doubleValue();
		}
		return integral ? (Object) asLong : (Object) asDouble;
	}

	/** Null over an empty group: the average of nothing is not zero, it is unknown. */
	private static Object average(List<Object> values) {
		if (values.isEmpty()) {
			return null;
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

	/** Distinct under Cypher equality, so that 1 and 1.0 collapse and two reads of one node collapse. */
	private static List<Object> distinct(List<Object> values) {
		List<Object> kept = new ArrayList<>();
		for (Object value : values) {
			boolean seen = false;
			for (Object existing : kept) {
				if (Values.equalKnown(existing, value)) {
					seen = true;
					break;
				}
			}
			if (!seen) {
				kept.add(value);
			}
		}
		return kept;
	}
}
