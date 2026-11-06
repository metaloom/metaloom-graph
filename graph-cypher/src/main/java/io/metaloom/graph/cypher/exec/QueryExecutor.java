package io.metaloom.graph.cypher.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.cypher.RowSink;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.func.Functions;
import io.metaloom.graph.cypher.io.CsvFormatException;
import io.metaloom.graph.cypher.io.CsvRowReader;
import io.metaloom.graph.cypher.io.CsvSource;

/**
 * Runs a parsed query and produces columns and rows.
 *
 * <p>
 * Clause at a time over a materialised list of rows, rather than a pull-based operator tree. The trade is deliberate:
 * a volcano iterator streams and would let LIMIT stop the scan early, but every clause here can already see all its
 * input, which is what ORDER BY, DISTINCT and aggregation need anyway — and the simpler shape has far fewer places for
 * a scoping bug to hide. The cost is that a query's peak memory is its widest intermediate result, which is recorded
 * in spec/CYPHER.md rather than hidden.
 * </p>
 */
public final class QueryExecutor {

	/**
	 * How many CSV rows pass between two consultations of the abort signal.
	 *
	 * <p>
	 * Matches {@code GraphAccess}'s interval on store touches, and exists for the case that one does not cover: a
	 * {@code LOAD CSV … RETURN row} touches the store never, so without this the query deadline would not bound it at
	 * all and a large file would be read to its end whatever the caller had asked for.
	 * </p>
	 */
	private static final int ABORT_CHECK_ROWS = 1024;

	private final GraphAccess graph;
	private final Evaluator evaluator;
	private final PatternMatcher matcher;
	private final Writer writer;
	private final CsvSource csv;

	public QueryExecutor(GraphAccess graph, Map<String, Object> parameters) {
		this(graph, parameters, CsvSource.DENY);
	}

	/**
	 * @param csv
	 *            where {@code LOAD CSV} may read from. {@link CsvSource#DENY} unless the caller has decided otherwise —
	 *            see that interface for why refusal is the default
	 */
	public QueryExecutor(GraphAccess graph, Map<String, Object> parameters, CsvSource csv) {
		this.graph = graph;
		this.evaluator = new Evaluator(graph, parameters);
		this.matcher = new PatternMatcher(evaluator);
		// MERGE has to match before it can decide whether to create, so the writer gets the same matcher rather than
		// one of its own — a second instance would be a second place for the pattern rules to drift.
		this.writer = new Writer(graph, evaluator, matcher);
		this.csv = csv == null ? CsvSource.DENY : csv;
		this.evaluator.subqueries(this::subquery);
	}

	/**
	 * The result of one query: the column names, in order, the rows, and what the query changed.
	 *
	 * @param stats
	 *            {@link CypherStats#NONE} for a read. A write query is often all counters and no rows, so this is not
	 *            an addendum to the result — for {@code CREATE (n:Person)} it is the entire result
	 */
	public record Result(List<String> columns, List<List<Object>> rows, CypherStats stats) {
	}

	/**
	 * Where {@link #runBatched} puts a transaction boundary.
	 *
	 * <p>
	 * The executor decides <em>where</em> the boundaries fall, because only it knows how many CSV rows have been read;
	 * the caller decides <em>what</em> a boundary is, because only it knows about transactions. This module has no
	 * business calling {@code store.begin()} — everything it does to the graph goes through {@link GraphAccess}, and
	 * keeping it that way is what makes the query layer's use of the storage API checkable.
	 * </p>
	 */
	public interface BatchBoundary {

		/** Called before the first row of a batch is produced. */
		void begin();

		/**
		 * Called after the last row of a batch has been written, and not called at all for an empty final batch.
		 *
		 * @param rowsCommitted
		 *            CSV rows durable once this returns, counted from the start of the load
		 */
		void commit(long rowsCommitted);
	}

	/**
	 * Runs a {@code USING PERIODIC COMMIT} load: the same clauses as {@link #run}, in transactions of {@code batchSize}
	 * CSV rows.
	 *
	 * <p>
	 * The file is read once, from one reader, and held open across the batches — the alternative would be reopening and
	 * skipping, which is quadratic and would also read a file that had changed underneath. The rows of one batch are
	 * materialised, the remaining clauses run over exactly those rows, and the boundary closes the transaction before
	 * the next row is read. Peak memory is therefore one batch rather than one file, which is the entire point.
	 * </p>
	 *
	 * <p>
	 * Every batch shares one {@link Writer}, so the returned counters are the load's rather than the last batch's.
	 * </p>
	 *
	 * @return what the whole load changed
	 */
	public CypherStats runBatched(Ast.SingleQuery part, int batchSize, BatchBoundary boundary) {
		if (!(part.clauses().get(0) instanceof Ast.LoadCsv load)) {
			throw new IllegalArgumentException("A batched run starts with LOAD CSV; the parser enforces this");
		}
		List<Ast.Clause> rest = List.copyOf(part.clauses().subList(1, part.clauses().size()));
		List<Row> batch = new ArrayList<>();
		long[] read = { 0 };
		boolean[] open = { false };

		forEachCsvRow(load, new Row(), row -> {
			if (!open[0]) {
				boundary.begin();
				open[0] = true;
			}
			batch.add(row);
			read[0]++;
			if (batch.size() >= batchSize) {
				runClauses(rest, batch);
				batch.clear();
				boundary.commit(read[0]);
				open[0] = false;
			}
			return true;
		});

		// The last batch is short unless the row count divides exactly, and a file whose row count does divide exactly
		// must not open a transaction here for the zero rows that follow.
		if (open[0]) {
			runClauses(rest, batch);
			boundary.commit(read[0]);
		}
		return writer.stats();
	}

	public Result run(Ast.Query query) {
		return run(query, new Row());
	}

	/**
	 * A whole query — every UNION part — over one seed row. The top level seeds an empty row; a subquery seeds the
	 * outer row, or the part of it the body imports.
	 */
	private Result run(Ast.Query query, Row seed) {
		Result result = runSingle(query.parts().get(0), seed);
		for (int i = 1; i < query.parts().size(); i++) {
			Result next = runSingle(query.parts().get(i), seed);
			if (!result.columns().equals(next.columns())) {
				throw new CypherTypeException("All parts of a UNION must return the same columns: "
					+ result.columns() + " against " + next.columns());
			}
			List<List<Object>> combined = new ArrayList<>(result.rows());
			combined.addAll(next.rows());
			// UNION deduplicates, UNION ALL does not — the flag is per union, in order.
			if (!query.unionAll().get(i - 1)) {
				combined = distinctRows(combined);
			}
			result = new Result(result.columns(), combined, result.stats());
		}
		// One Writer serves every part, so the counters are the query's rather than the last part's.
		return new Result(result.columns(), result.rows(), writer.stats());
	}

	// ---------------------------------------------------------------- the streaming path

	/**
	 * Runs a query straight into a sink, without ever holding its rows.
	 *
	 * <p>
	 * Only when every clause of a single-part query streams — see {@link #streams}. Otherwise this materialises through
	 * {@link #run} and pushes the result, which is what the whole engine did before this path existed, so the fallback
	 * is not a degraded mode but the previous one.
	 * </p>
	 *
	 * <p>
	 * <b>What this buys, and what it does not.</b> It bounds what the <em>executor</em> holds for the queries it
	 * covers, and it lets a {@code LIMIT} stop the scan — which is a change in complexity, not a constant factor: ten
	 * rows out of ten million nodes stops after ten. It does nothing for {@code ORDER BY}, {@code DISTINCT},
	 * aggregation or {@code UNION}, which need their whole input by definition, and nothing for a write, which
	 * materialises so that it can commit before delivering a row.
	 * </p>
	 */
	public void stream(Ast.Query query, RowSink sink) {
		if (query.parts().size() != 1 || !streams(query.parts().get(0))) {
			Result result = run(query);
			sink.columns(result.columns());
			for (List<Object> row : result.rows()) {
				if (!sink.row(row)) {
					return;
				}
			}
			sink.stats(result.stats());
			return;
		}

		List<Ast.Clause> clauses = query.parts().get(0).clauses();
		Ast.Return last = (Ast.Return) clauses.get(clauses.size() - 1);
		List<String> columns = columnsOf(last.projection());
		sink.columns(columns);

		Row seed = new Row();
		push(clauses, 0, seed, new Stage[clauses.size()], row -> {
			List<Object> values = new ArrayList<>(columns.size());
			for (String column : columns) {
				values.add(row.get(column));
			}
			return sink.row(values);
		});
		// A read changes nothing, and only a read reaches here.
		sink.stats(CypherStats.NONE);
	}

	/**
	 * Whether every clause of this part can be run as a push stage.
	 *
	 * <p>
	 * All or nothing rather than the longest streaming prefix. A blocking clause buffers its whole input whatever runs
	 * above it, so pushing only the prefix would bound nothing — the one thing it could still buy is an early stop, and
	 * a {@code LIMIT} before a blocking clause is rare enough not to be worth a second execution shape.
	 * </p>
	 */
	private static boolean streams(Ast.SingleQuery part) {
		List<Ast.Clause> clauses = part.clauses();
		if (clauses.isEmpty() || !(clauses.get(clauses.size() - 1) instanceof Ast.Return)) {
			return false;
		}
		return streams(clauses);
	}

	/** Whether every one of these clauses can run as a push stage, whatever the last one is. */
	private static boolean streams(List<Ast.Clause> clauses) {
		for (Ast.Clause clause : clauses) {
			boolean ok = switch (clause) {
				case Ast.Match ignored -> true;
				case Ast.Unwind ignored -> true;
				// The whole point of the clause: a file is the one input that genuinely does not fit in memory, so it
				// must not be read into a list on its way to being read row by row.
				case Ast.LoadCsv ignored -> true;
				case Ast.With with -> streams(with.projection());
				case Ast.Return returning -> streams(returning.projection());
				// A write materialises so that it can commit before a row is delivered, and its eager semantics come
				// from the executor holding each clause's whole input. Pushing one would take both away silently.
				case Ast.Create ignored -> false;
				case Ast.Merge ignored -> false;
				case Ast.SetClause ignored -> false;
				case Ast.Remove ignored -> false;
				case Ast.Delete ignored -> false;
				// A call produces its whole result set at once — the index returns k rows, not a stream — so there is
				// nothing to push. Rows after it stream as usual.
				case Ast.Call ignored -> false;
				// Runs a whole query per row, whose result is materialised; the rows after it stream as usual.
				case Ast.CallSubquery ignored -> false;
			};
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Runs a procedure call, binding its results into each incoming row.
	 *
	 * <p>
	 * There is exactly one procedure and it is dispatched by name here rather than through a registry, because a
	 * registry for one entry is indirection without a second implementer. When there is a second, this becomes the
	 * place that grows a map.
	 * </p>
	 */
	private List<Row> call(Ast.Call call, List<Row> rows) {
		if (!call.procedure().equalsIgnoreCase("vector.query")) {
			throw new CypherTypeException("Unknown procedure " + call.procedure()
				+ "(). This build has one: vector.query(label, key, vector, k) YIELD node, score");
		}
		if (call.arguments().size() != 4) {
			throw new CypherTypeException("vector.query(label, key, vector, k) takes 4 arguments but got "
				+ call.arguments().size());
		}
		if (call.yields().size() > 2) {
			throw new CypherTypeException("vector.query yields node and score, so at most two names");
		}
		List<Row> out = new ArrayList<>();
		for (Row row : rows) {
			for (String name : call.yields()) {
				refuseRebinding(row, name, "YIELD");
			}
			String label = text(evaluator.evaluate(call.arguments().get(0), row));
			String key = text(evaluator.evaluate(call.arguments().get(1), row));
			float[] query = Functions.vectorArgument(evaluator.evaluate(call.arguments().get(2), row),
				"vector.query");
			long k = Values.number(evaluator.evaluate(call.arguments().get(3), row), "vector.query").longValue();
			if (query == null || k <= 0) {
				continue;
			}
			for (GraphAccess.Scored scored : graph.nearest(label, key, query, (int) k)) {
				Row bound = row.with(call.yields().get(0), graph.node(scored.nodeId()));
				if (call.yields().size() > 1) {
					bound = bound.with(call.yields().get(1), scored.score());
				}
				out.add(bound);
			}
		}
		return out;
	}

	/**
	 * UNWIND, LOAD CSV and YIELD introduce a name, and one already in scope is refused rather than shadowed.
	 *
	 * <p>
	 * Shadowing silently replaced the earlier binding for the rest of the query, so
	 * {@code MATCH (n) UNWIND [1, 2] AS n RETURN n} returned the numbers and lost every node without a word. openCypher
	 * makes it an error, and so does this — checked per row because the executor has no scope table, which costs one
	 * hash lookup against a row that is about to be copied anyway.
	 * </p>
	 */
	static void refuseRebinding(Row row, String name, String clause) {
		if (row.has(name)) {
			throw new CypherTypeException(clause + " cannot bind '" + name + "': that variable is already defined. "
				+ "Choose another name, or drop the earlier one with WITH");
		}
	}

	private static String text(Object value) {
		if (value instanceof String string) {
			return string;
		}
		throw new CypherTypeException("vector.query needs a label and a key as strings, got "
			+ Values.typeName(value));
	}

	/**
	 * A projection streams when its output does not depend on rows it has not seen.
	 *
	 * <p>
	 * {@code ORDER BY} and {@code DISTINCT} need the whole input by definition, and so does an aggregate. {@code *}
	 * looks harmless and is not: the star's column set is the union of the variable names across <em>every</em> row, so
	 * the header cannot be written until the last row has been read — and the header goes out first.
	 * </p>
	 */
	private static boolean streams(Ast.Projection projection) {
		return !projection.star() && !projection.distinct() && projection.order().isEmpty()
			&& !hasAggregate(projection.items());
	}

	private static List<String> columnsOf(Ast.Projection projection) {
		List<String> columns = new ArrayList<>(projection.items().size());
		for (Ast.ProjectionItem item : projection.items()) {
			columns.add(item.alias());
		}
		return columns;
	}

	/** SKIP and LIMIT for one projection stage, resolved once, which is the only state a push stage carries. */
	private static final class Stage {

		private final long skip;
		private final long limit;
		private long skipped;
		private long emitted;

		Stage(long skip, long limit) {
			this.skip = skip;
			this.limit = limit;
		}
	}

	/**
	 * Runs one clause and hands each row it produces to the rest of the chain.
	 *
	 * <p>
	 * Recursive over the clause list rather than a composed chain of lambdas, because a stage needs its index to find
	 * its own SKIP and LIMIT counters, and because the stack this builds is one frame per clause — a depth set by the
	 * query text, not by the data.
	 * </p>
	 *
	 * @return false as soon as anything downstream has had enough
	 */
	private boolean push(List<Ast.Clause> clauses, int index, Row row, Stage[] stages, RowConsumer out) {
		if (index == clauses.size()) {
			return out.accept(row);
		}
		RowConsumer next = downstream -> push(clauses, index + 1, downstream, stages, out);

		return switch (clauses.get(index)) {
			case Ast.Match match when match.optional() -> {
				boolean[] found = { false };
				boolean more = matcher.match(match.pattern(), match.where(), row, matched -> {
					if (!keeps(matched, match.where())) {
						return true;
					}
					found[0] = true;
					return next.accept(matched);
				});
				// Decided after the walk, not per candidate: the padded row stands for "nothing matched", so it can only
				// be produced once every candidate has had its chance.
				yield !more ? false : found[0] || next.accept(padded(match, row));
			}
			case Ast.Match match -> matcher.match(match.pattern(), match.where(), row,
				matched -> !keeps(matched, match.where()) || next.accept(matched));
			case Ast.Unwind unwind -> {
				refuseRebinding(row, unwind.variable(), "UNWIND");
				Object value = evaluator.evaluate(unwind.expression(), row);
				if (value == null) {
					// UNWIND null produces no rows, which is not the same as producing one null row.
					yield true;
				}
				if (!(value instanceof List<?> list)) {
					throw new CypherTypeException("UNWIND expects a list but got " + Values.typeName(value));
				}
				for (Object element : list) {
					// The store is never touched here, so its abort checks never run: tick the same counter they use.
					graph.checkAbort();
					if (!next.accept(row.with(unwind.variable(), element))) {
						yield false;
					}
				}
				yield true;
			}
			case Ast.LoadCsv load -> forEachCsvRow(load, row, next);
			// The WHERE goes *into* the projection stage rather than after it, because the list path filters before
			// it slices: WITH n WHERE n.x > 5 LIMIT 3 means three of the rows that passed, not however many of the
			// first three did. Applying them the other way round is a wrong answer that throws nothing.
			case Ast.With with -> project(row, with.projection(), with.where(), stages, index, next);
			case Ast.Return returning -> project(row, returning.projection(), null, stages, index, next);
			default -> throw new IllegalStateException("Not a streaming clause: " + clauses.get(index));
		};
	}

	/**
	 * Opens the CSV named by the clause and pushes one row per record, binding {@code load.variable()}.
	 *
	 * <p>
	 * The one place this module touches the outside world, and it does so through {@link CsvSource} — which is what
	 * keeps the sandbox decision with the server rather than in the parser. Both execution paths come through here, so
	 * the header handling, the abort checks and the close cannot drift between them.
	 * </p>
	 *
	 * <p>
	 * <b>Closed on every path.</b> The try-with-resources covers a normal end, an exception, and — the case that is
	 * easy to miss — a downstream {@code LIMIT} returning false, which is a perfectly ordinary early exit and would
	 * otherwise leak a file handle per query.
	 * </p>
	 *
	 * @return false if anything downstream had enough, which stops the read where it is
	 */
	private boolean forEachCsvRow(Ast.LoadCsv load, Row row, RowConsumer out) {
		refuseRebinding(row, load.variable(), "LOAD CSV");
		Object url = evaluator.evaluate(load.url(), row);
		if (!(url instanceof String location)) {
			throw new CypherTypeException("LOAD CSV needs a string URL but got " + Values.typeName(url));
		}
		try (CsvRowReader reader = new CsvRowReader(csv.open(location), load.fieldTerminator())) {
			List<String> headers = null;
			if (load.withHeaders()) {
				headers = reader.next();
				if (headers == null) {
					// No header line means no columns, so every `row.x` downstream would be null. An empty result is a
					// plausible answer to a query and a terrible answer to this, so it is refused.
					throw new CsvFormatException("LOAD CSV WITH HEADERS needs a header line, and " + location
						+ " is empty");
				}
				checkHeaders(headers, location);
			}
			List<String> values;
			long produced = 0;
			while ((values = reader.next()) != null) {
				// GraphAccess only consults the abort signal when it touches the store, so a load that reads a file and
				// returns it would otherwise run past the query deadline unbounded. This is the other half of that
				// check, on the same interval and against the same signal.
				if (++produced % ABORT_CHECK_ROWS == 0) {
					graph.checkAbortNow();
				}
				// `values` is a fresh list per record and may contain nulls, so it is handed over as it is — List.copyOf
				// would reject the nulls that an empty unquoted field deliberately produces.
				Object bound = headers == null ? values : toMap(headers, values, reader.line(), location);
				if (!out.accept(row.with(load.variable(), bound))) {
					return false;
				}
			}
			return true;
		} catch (IOException e) {
			throw new UncheckedIOException("LOAD CSV could not read " + location, e);
		}
	}

	/**
	 * Binds a record to its header, refusing a width the header cannot explain.
	 *
	 * <p>
	 * A short row is padded with nulls rather than refused: a trailing empty column is extremely common in exported
	 * data and Cypher's null semantics already say what {@code row.missing} means. A <em>long</em> row is refused,
	 * because there is no name to bind the extra cells to and the overwhelmingly likely cause is an unquoted separator
	 * inside a value — which means the row's other cells are misaligned too, and importing it would write wrong data
	 * under correct-looking keys.
	 * </p>
	 */
	private static Map<String, Object> toMap(List<String> headers, List<String> values, long line, String location) {
		if (values.size() > headers.size()) {
			throw new CsvFormatException("Line " + line + " of " + location + " has " + values.size()
				+ " fields but the header declares " + headers.size()
				+ "; the usual cause is an unquoted separator inside a value");
		}
		Map<String, Object> bound = new LinkedHashMap<>(headers.size());
		for (int i = 0; i < headers.size(); i++) {
			bound.put(headers.get(i), i < values.size() ? values.get(i) : null);
		}
		return bound;
	}

	/** A duplicate or absent column name makes {@code row.x} ambiguous, so it is refused before any row is read. */
	private static void checkHeaders(List<String> headers, String location) {
		Set<String> seen = new LinkedHashSet<>();
		for (int i = 0; i < headers.size(); i++) {
			String header = headers.get(i);
			if (header == null || header.isBlank()) {
				throw new CsvFormatException("Column " + (i + 1) + " of " + location + " has no name in the header, "
					+ "so nothing downstream could refer to it");
			}
			if (!seen.add(header)) {
				throw new CsvFormatException("The header of " + location + " names '" + header + "' more than once, "
					+ "so row." + header + " would be ambiguous");
			}
		}
	}

	/**
	 * Projects one row and applies SKIP and LIMIT to it.
	 *
	 * <p>
	 * The counters are per stage and survive across rows, which is what makes {@code SKIP 10 LIMIT 5} mean the same
	 * thing here as it does over a list. Returning false at the limit is what reaches the scan.
	 * </p>
	 */
	private boolean project(Row row, Ast.Projection projection, Ast.Expr where, Stage[] stages, int index,
		RowConsumer out) {
		Stage stage = stages[index];
		if (stage == null) {
			// SKIP and LIMIT are evaluated against an empty row in the list path, so they cannot depend on the data —
			// which means once per stage rather than once per row.
			stage = stages[index] = new Stage(
				projection.skip() == null ? 0 : positive(projection.skip(), "SKIP"),
				projection.limit() == null ? Long.MAX_VALUE : positive(projection.limit(), "LIMIT"));
		}
		if (stage.limit == 0) {
			return false;
		}

		// The projected items and nothing else: what a WITH leaves out is out of scope after it, which is what the
		// list path does too. Only ORDER BY may see underneath a projection, and ORDER BY never streams.
		Row projected = Row.withCapacity(projection.items().size());
		for (Ast.ProjectionItem item : projection.items()) {
			projected.put(item.alias(), evaluator.evaluate(item.expression(), row));
		}

		// Before the counters: a row the WHERE drops was never one of the rows SKIP and LIMIT are counting.
		if (!keeps(projected, where)) {
			return true;
		}
		if (stage.skipped < stage.skip) {
			stage.skipped++;
			return true;
		}
		if (!out.accept(projected)) {
			return false;
		}
		// Checked after emitting rather than before, so the row that reaches the limit is still delivered and the one
		// after it is never produced.
		return ++stage.emitted < stage.limit;
	}

	private boolean keeps(Row row, Ast.Expr where) {
		// A predicate that evaluates to null drops the row, exactly as false does. See Values.isTrue.
		return where == null || Values.isTrue(evaluator.evaluate(where, row));
	}

	private Result runSingle(Ast.SingleQuery query, Row start) {
		// One row, so the first clause runs exactly once: empty at the top level, the imported outer row in a subquery.
		// A clause that produces rows replaces it; a clause that filters has something to filter.
		List<Row> seed = new ArrayList<>();
		seed.add(start);
		return runClauses(query.clauses(), seed);
	}

	/**
	 * The materialised path, over a given starting set of rows.
	 *
	 * <p>
	 * Split out from {@link #runSingle} so that {@link #runBatched} can run the clauses after {@code LOAD CSV} over one
	 * batch's rows and get exactly the semantics an unbatched run would have given them. Sharing the loop rather than
	 * copying it is what keeps periodic commit from being a second, subtly different executor.
	 * </p>
	 */
	private Result runClauses(List<Ast.Clause> clauses, List<Row> seed) {
		Pipeline pipeline = pipeline(clauses, seed);
		List<Row> rows = pipeline.rows();
		List<String> columns = pipeline.columns();
		if (!pipeline.returned()) {
			// A write query with no RETURN. Its result is what it changed, and the counters carry that; projecting the
			// working rows would report one empty row per element touched, which is a result the query never asked
			// for. Only a write can reach here — the parser refuses a read that does not end in RETURN.
			return new Result(List.of(), List.of(), writer.stats());
		}

		List<List<Object>> output = new ArrayList<>(rows.size());
		for (Row row : rows) {
			List<Object> values = new ArrayList<>(columns.size());
			for (String column : columns) {
				values.add(row.get(column));
			}
			output.add(values);
		}
		return new Result(columns, output, writer.stats());
	}

	/**
	 * What a clause list leaves behind: its working rows, and the columns of its RETURN if it had one.
	 *
	 * <p>
	 * The rows rather than a result, because a subquery expression without a RETURN — {@code EXISTS { MATCH … }} — is
	 * answered by the rows themselves.
	 * </p>
	 */
	private record Pipeline(List<Row> rows, List<String> columns, boolean returned) {
	}

	private Pipeline pipeline(List<Ast.Clause> clauses, List<Row> seed) {
		List<Row> rows = seed;
		List<String> columns = List.of();
		boolean returned = false;

		for (Ast.Clause clause : clauses) {
			switch (clause) {
				case Ast.Match match when match.optional() -> {
					List<Row> next = new ArrayList<>();
					for (Row row : rows) {
						List<Row> found = filter(matcher.match(match.pattern(), match.where(), row), match.where());
						if (found.isEmpty()) {
							next.add(padded(match, row));
						} else {
							next.addAll(found);
						}
					}
					rows = next;
				}
				case Ast.Match match -> {
					List<Row> next = new ArrayList<>();
					for (Row row : rows) {
						next.addAll(matcher.match(match.pattern(), match.where(), row));
					}
					rows = filter(next, match.where());
				}
				case Ast.Unwind unwind -> {
					List<Row> next = new ArrayList<>();
					for (Row row : rows) {
						refuseRebinding(row, unwind.variable(), "UNWIND");
						Object value = evaluator.evaluate(unwind.expression(), row);
						if (value == null) {
							// UNWIND null produces no rows, which is not the same as producing one null row.
							continue;
						}
						if (!(value instanceof List<?> list)) {
							throw new CypherTypeException("UNWIND expects a list but got "
								+ Values.typeName(value));
						}
						for (Object element : list) {
							graph.checkAbort();
							next.add(row.with(unwind.variable(), element));
						}
					}
					rows = next;
				}
				case Ast.LoadCsv load -> {
					// The whole file, in memory. That is what this path is, and it is why USING PERIODIC COMMIT exists:
					// a write query cannot stream — it commits before delivering a row — so a load feeding CREATE
					// arrives here, and unbatched its peak memory is the file. Cypher.write drives the batched form;
					// this is the single-transaction one, which is correct and is bounded by the file size.
					List<Row> next = new ArrayList<>();
					for (Row row : rows) {
						forEachCsvRow(load, row, next::add);
					}
					rows = next;
				}
				case Ast.With with -> {
					Projected projected = project(rows, with.projection());
					// The WHERE sees only what the WITH projects, and so does every clause after it. ORDER BY may
					// still read underneath, as openCypher allows, so the source rows are dropped only after it.
					// Without an ORDER BY the projection kept no source row (projectRows), so each row already holds
					// exactly its columns and narrowing it again would copy it twice for nothing.
					boolean narrowed = with.projection().order().isEmpty();
					rows = narrowed ? filter(projected.rows(), with.where())
						: filter(projected.rows(), with.where(), projected.columns());
					rows = order(rows, with.projection(), projected.columns());
					rows = slice(rows, with.projection());
					if (!narrowed) {
						rows = only(rows, projected.columns());
					}
					columns = projected.columns();
				}
				case Ast.Return returnClause -> {
					Projected projected = project(rows, returnClause.projection());
					rows = order(projected.rows(), returnClause.projection(), projected.columns());
					rows = slice(rows, returnClause.projection());
					columns = projected.columns();
					returned = true;
				}
				// Each of these consumes the materialised list and produces one, which is what gives the write clauses
				// openCypher's eager semantics for nothing: a CREATE cannot feed the MATCH that is driving it.
				case Ast.Create create -> rows = writer.create(create, rows);
				case Ast.Merge merge -> rows = writer.merge(merge, rows);
				case Ast.SetClause set -> rows = writer.set(set, rows);
				case Ast.Remove remove -> rows = writer.remove(remove, rows);
				case Ast.Delete delete -> rows = writer.delete(delete, rows);
				case Ast.Call call -> rows = call(call, rows);
				case Ast.CallSubquery subquery -> rows = callSubquery(subquery, rows);
			}
		}
		return new Pipeline(rows, columns, returned);
	}

	// ---------------------------------------------------------------- OPTIONAL MATCH

	/**
	 * The row an OPTIONAL MATCH produces when nothing matched: the input, with every variable the pattern would have
	 * introduced bound to null.
	 *
	 * <p>
	 * Bound to null and not left unbound, which is the difference that matters downstream: {@code RETURN b} must have a
	 * column to read, and a later {@code MATCH (b)-->(c)} must match nothing rather than scan for a new {@code b}. A
	 * variable already bound before the clause keeps its value; the pattern only constrained it.
	 * </p>
	 */
	private static Row padded(Ast.Match match, Row row) {
		Row out = null;
		for (Ast.PatternPart part : match.pattern()) {
			List<String> names = new ArrayList<>();
			if (part.pathVariable() != null) {
				names.add(part.pathVariable());
			}
			for (int i = 0; i < part.nodes().size(); i++) {
				names.add(part.nodes().get(i).variable());
				if (i < part.relationships().size()) {
					names.add(part.relationships().get(i).variable());
				}
			}
			for (String name : names) {
				if (name != null && !row.has(name) && (out == null || !out.has(name))) {
					if (out == null) {
						out = Row.copyOf(row, names.size());
					}
					out.put(name, null);
				}
			}
		}
		return out == null ? row : out;
	}

	// ---------------------------------------------------------------- subqueries

	/**
	 * {@code CALL { … }}: the body once per row, seeded with what it imports.
	 *
	 * <p>
	 * A returning body multiplies the row by its result, and a row the body has no answer for is dropped — as a MATCH
	 * that finds nothing drops it. A unit body (no RETURN) is run for its writes and lets each row through once.
	 * </p>
	 */
	private List<Row> callSubquery(Ast.CallSubquery subquery, List<Row> rows) {
		List<Row> out = new ArrayList<>();
		for (Row row : rows) {
			graph.checkAbort();
			Row seed;
			if (subquery.importAll()) {
				seed = row;
			} else {
				for (String name : subquery.imports()) {
					if (!row.has(name)) {
						throw new CypherTypeException("CALL { … } imports '" + name + "', which is not defined here. "
							+ "In scope: " + visible(row));
					}
				}
				seed = row.only(subquery.imports());
			}
			Result result = run(subquery.body(), seed);
			if (result.columns().isEmpty()) {
				out.add(row);
				continue;
			}
			for (String column : result.columns()) {
				if (row.has(column) && !seed.has(column)) {
					throw new CypherTypeException("CALL { … } returns '" + column + "', which is already defined "
						+ "outside it. Rename it with AS");
				}
			}
			for (List<Object> values : result.rows()) {
				Row joined = Row.copyOf(row, values.size());
				for (int i = 0; i < values.size(); i++) {
					joined.put(result.columns().get(i), values.get(i));
				}
				out.add(joined);
			}
		}
		return out;
	}

	private static List<String> visible(Row row) {
		List<String> names = new ArrayList<>();
		for (String name : row.names()) {
			if (!PatternMatcher.isAnonymous(name)) {
				names.add(name);
			}
		}
		return names;
	}

	/**
	 * {@code EXISTS}, {@code COUNT} and {@code COLLECT}, seeded with the whole outer row.
	 *
	 * <p>
	 * A single-part body whose clauses all stream is pushed, so {@code EXISTS} stops at its first row and
	 * {@code COUNT} holds none of them: {@code WHERE EXISTS { (n)-[:KNOWS]->() }} costs one expansion step for a node
	 * that has a friend, not all of them. Anything else is materialised through the ordinary pipeline.
	 * </p>
	 */
	private Object subquery(Ast.Subquery subquery, Row row) {
		Ast.Query body = subquery.body();
		if (body.parts().size() == 1 && subquery.kind() != Ast.SubqueryKind.COLLECT
			&& streams(body.parts().get(0).clauses())) {
			List<Ast.Clause> clauses = body.parts().get(0).clauses();
			long[] count = { 0 };
			boolean exists = subquery.kind() == Ast.SubqueryKind.EXISTS;
			push(clauses, 0, row, new Stage[clauses.size()], ignored -> {
				count[0]++;
				return !exists;
			});
			return exists ? (Object) (count[0] > 0) : (Object) count[0];
		}
		boolean returns = body.parts().get(0).clauses()
			.get(body.parts().get(0).clauses().size() - 1) instanceof Ast.Return;
		if (!returns) {
			// The rows are the answer. Only a single part can get here: the parser keeps a union's parts alike.
			long size = 0;
			for (Ast.SingleQuery part : body.parts()) {
				List<Row> seed = new ArrayList<>();
				seed.add(row);
				size += pipeline(part.clauses(), seed).rows().size();
			}
			return subquery.kind() == Ast.SubqueryKind.EXISTS ? (Object) (size > 0) : (Object) size;
		}
		Result result = run(body, row);
		long size = result.rows().size();
		List<Object> values = new ArrayList<>(result.rows().size());
		if (subquery.kind() == Ast.SubqueryKind.COLLECT) {
			for (List<Object> resultRow : result.rows()) {
				values.add(resultRow.get(0));
			}
		}
		return switch (subquery.kind()) {
			case EXISTS -> size > 0;
			case COUNT -> size;
			case COLLECT -> values;
		};
	}

	// ---------------------------------------------------------------- clauses

	private List<Row> filter(List<Row> rows, Ast.Expr where) {
		if (where == null) {
			return rows;
		}
		List<Row> kept = new ArrayList<>();
		for (Row row : rows) {
			// A predicate that evaluates to null drops the row, exactly as false does. See Values.isTrue.
			if (Values.isTrue(evaluator.evaluate(where, row))) {
				kept.add(row);
			}
		}
		return kept;
	}

	/** The WHERE of a WITH, evaluated against the projection alone. */
	private List<Row> filter(List<Row> rows, Ast.Expr where, List<String> scope) {
		if (where == null) {
			return rows;
		}
		List<Row> kept = new ArrayList<>();
		for (Row row : rows) {
			if (Values.isTrue(evaluator.evaluate(where, row.only(scope)))) {
				kept.add(row);
			}
		}
		return kept;
	}

	private static List<Row> only(List<Row> rows, List<String> columns) {
		List<Row> out = new ArrayList<>(rows.size());
		for (Row row : rows) {
			out.add(row.only(columns));
		}
		return out;
	}

	private record Projected(List<String> columns, List<Row> rows) {
	}

	private Projected project(List<Row> rows, Ast.Projection projection) {
		List<String> columns = new ArrayList<>();
		List<Ast.ProjectionItem> items = new ArrayList<>();
		if (projection.star()) {
			// `RETURN *` is every variable the query named, in binding order, and none of the anonymous ones the
			// pattern matcher invented for `()`.
			Set<String> names = new LinkedHashSet<>();
			for (Row row : rows) {
				for (String name : row.names()) {
					if (!PatternMatcher.isAnonymous(name)) {
						names.add(name);
					}
				}
			}
			for (String name : names) {
				columns.add(name);
				items.add(new Ast.ProjectionItem(new Ast.Variable(name), name));
			}
		}
		for (Ast.ProjectionItem item : projection.items()) {
			if (projection.star() && columns.contains(item.alias())) {
				// The parser refuses a repeated alias among the items; this is the half it cannot see, a name that *
				// already brings in. One of the two values would silently replace the other.
				throw new CypherTypeException("'" + item.alias() + "' is already a column of *; give the item a "
					+ "different name with AS");
			}
			columns.add(item.alias());
			items.add(item);
		}

		List<Row> projected = hasAggregate(items)
			? aggregate(rows, items)
			: projectRows(rows, items, !projection.order().isEmpty());
		if (projection.distinct()) {
			projected = distinctBy(projected, columns);
		}
		return new Projected(columns, projected);
	}

	/**
	 * @param keepSource
	 *            whether the source row stays reachable underneath, so that ORDER BY can sort on something not
	 *            projected, which Cypher allows. Only ORDER BY reads it — the WHERE of a WITH sees the projection alone
	 *            and everything after is scoped to it — so without one the copy of every source row is skipped
	 */
	private List<Row> projectRows(List<Row> rows, List<Ast.ProjectionItem> items, boolean keepSource) {
		List<Row> out = new ArrayList<>(rows.size());
		for (Row row : rows) {
			Row projected = keepSource ? Row.copyOf(row, items.size()) : Row.withCapacity(items.size());
			for (Ast.ProjectionItem item : items) {
				projected.put(item.alias(), evaluator.evaluate(item.expression(), row));
			}
			out.add(projected);
		}
		return out;
	}

	// ---------------------------------------------------------------- aggregation

	private static boolean hasAggregate(List<Ast.ProjectionItem> items) {
		for (Ast.ProjectionItem item : items) {
			if (containsAggregate(item.expression())) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsAggregate(Ast.Expr expression) {
		// CASE's subject and ELSE are both optional, so null is an ordinary input here rather than a mistake — and a
		// switch over a null selector throws.
		if (expression == null) {
			return false;
		}
		return switch (expression) {
			case Ast.FunctionCall call -> Functions.isAggregate(call.name())
				|| call.arguments().stream().anyMatch(QueryExecutor::containsAggregate);
			case Ast.Binary binary -> containsAggregate(binary.left()) || containsAggregate(binary.right());
			case Ast.Unary unary -> containsAggregate(unary.operand());
			case Ast.Comparison comparison -> containsAggregate(comparison.left())
				|| containsAggregate(comparison.right());
			case Ast.Property property -> containsAggregate(property.target());
			case Ast.Index index -> containsAggregate(index.target()) || containsAggregate(index.index());
			case Ast.ListLiteral list -> list.elements().stream().anyMatch(QueryExecutor::containsAggregate);
			case Ast.MapLiteral map -> map.entries().values().stream().anyMatch(QueryExecutor::containsAggregate);
			case Ast.IsNull isNull -> containsAggregate(isNull.operand());
			case Ast.LabelPredicate predicate -> containsAggregate(predicate.target());
			// `sum(CASE WHEN x THEN 1 ELSE 0 END)` is caught by the FunctionCall arm above; this is the other way
			// round — an aggregate inside a branch, as in `CASE WHEN $wanted THEN count(*) ELSE 0 END`.
			case Ast.Case branch -> containsAggregate(branch.subject())
				|| branch.whens().stream().anyMatch(QueryExecutor::containsAggregate)
				|| branch.thens().stream().anyMatch(QueryExecutor::containsAggregate)
				|| containsAggregate(branch.otherwise());
			// `[x IN collect(n) | x.name]`: the list is the one place an aggregate can sit in a comprehension, since the
			// rest is evaluated per element.
			case Ast.ListComprehension comprehension -> containsAggregate(comprehension.list());
			case Ast.Quantifier quantifier -> containsAggregate(quantifier.list());
			// An aggregate inside a subquery aggregates the subquery's rows, not this projection's.
			case Ast.Subquery ignored -> false;
			case Ast.Literal ignored -> false;
			case Ast.Variable ignored -> false;
			case Ast.Parameter ignored -> false;
		};
	}

	/**
	 * Groups by every non-aggregating projection item and folds the aggregating ones.
	 *
	 * <p>
	 * That grouping rule is Cypher's, and it surprises people: there is no GROUP BY, the grouping keys are whatever
	 * you projected that is not an aggregate. {@code RETURN n.city, count(*)} groups by city because {@code n.city}
	 * is the only non-aggregate.
	 * </p>
	 */
	private List<Row> aggregate(List<Row> rows, List<Ast.ProjectionItem> items) {
		List<Ast.ProjectionItem> keys = new ArrayList<>();
		List<Ast.ProjectionItem> aggregates = new ArrayList<>();
		for (Ast.ProjectionItem item : items) {
			(containsAggregate(item.expression()) ? aggregates : keys).add(item);
		}

		// Keyed on Cypher equivalence rather than Java equality, so 1 and 1.0 are one group, as DISTINCT says they are.
		// The first row's values are what the group projects.
		Map<Object, List<Object>> keyValues = new LinkedHashMap<>();
		Map<Object, List<Row>> groups = new LinkedHashMap<>();
		if (keys.isEmpty()) {
			// No grouping key — `RETURN count(*)` — is one group of every row, so nothing needs hashing. It also covers
			// the empty input, which still produces its one row: `count(*)` of nothing is 0, not no answer at all.
			groups.put(List.of(), rows);
			keyValues.put(List.of(), List.of());
		}
		for (Row row : keys.isEmpty() ? List.<Row>of() : rows) {
			List<Object> values = new ArrayList<>(keys.size());
			for (Ast.ProjectionItem item : keys) {
				values.add(evaluator.evaluate(item.expression(), row));
			}
			Object key = Values.groupingKey(values);
			keyValues.putIfAbsent(key, values);
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
		}
		// An aggregate with no grouping key over no rows still produces one row: `count(*)` of nothing is 0, not
		// no answer at all.
		if (groups.isEmpty() && keys.isEmpty()) {
			groups.put(List.of(), List.of());
			keyValues.put(List.of(), List.of());
		}

		List<Row> out = new ArrayList<>(groups.size());
		for (Map.Entry<Object, List<Row>> group : groups.entrySet()) {
			List<Object> values = keyValues.get(group.getKey());
			Row projected = Row.withCapacity(keys.size() + aggregates.size());
			for (int i = 0; i < keys.size(); i++) {
				projected.put(keys.get(i).alias(), values.get(i));
			}
			for (Ast.ProjectionItem item : aggregates) {
				projected.put(item.alias(), foldAggregate(item.expression(), group.getValue()));
			}
			out.add(projected);
		}
		return out;
	}

	/**
	 * Evaluates an expression in which aggregating calls are folded over the group and the rest is ordinary.
	 *
	 * <p>
	 * Whatever surrounds the calls is evaluated against the first row of the group, which is sound because anything
	 * there that is not an aggregate is constant across the group: it is one of the grouping keys. Each call is folded
	 * when the evaluation reaches it and not before, so {@code CASE WHEN $wanted THEN count(*) ELSE 0 END} folds only
	 * the branch it takes.
	 * </p>
	 */
	private Object foldAggregate(Ast.Expr expression, List<Row> group) {
		Map<Ast.FunctionCall, Object> folded = new java.util.IdentityHashMap<>();
		Evaluator folding = evaluator.folding(call -> folded.computeIfAbsent(call,
			ignored -> Aggregates.fold(evaluator, call, group)));
		// Only the no-key group can be empty, and then there is nothing for a non-aggregate to read: `count(*)` of
		// nothing is 0, and `size(collect(x))` of nothing is 0 too.
		return folding.evaluate(expression, group.isEmpty() ? new Row() : group.get(0));
	}

	// ---------------------------------------------------------------- ordering and paging

	private List<Row> order(List<Row> rows, Ast.Projection projection, List<String> columns) {
		if (projection.order().isEmpty()) {
			return rows;
		}
		// Keys once per row, not once per comparison: a key can read the store or score a vector, and a sort makes
		// O(n log n) comparisons. The column name each item would resolve to is rendered once per item for the same
		// reason.
		List<Ast.SortItem> items = projection.order();
		// A sort item written as the expression a projection item computes reads that item's column: after an
		// aggregation `RETURN n.age AS age, count(*) ORDER BY n.age` has no n left, but it has the column that holds
		// n.age, which is what was meant.
		Map<String, String> projectedAs = new java.util.HashMap<>();
		for (Ast.ProjectionItem item : projection.items()) {
			projectedAs.putIfAbsent(io.metaloom.graph.cypher.ast.Rendering.text(item.expression()), item.alias());
		}
		List<String> names = new ArrayList<>(items.size());
		List<String> aliases = new ArrayList<>(items.size());
		for (Ast.SortItem item : items) {
			String name = io.metaloom.graph.cypher.ast.Rendering.text(item.expression());
			names.add(name);
			aliases.add(projectedAs.get(name));
		}
		List<Keyed> keyed = new ArrayList<>(rows.size());
		for (Row row : rows) {
			Object[] keys = new Object[items.size()];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = sortKey(items.get(i).expression(), names.get(i), aliases.get(i), row);
			}
			keyed.add(new Keyed(row, keys));
		}
		keyed.sort((left, right) -> {
			for (int i = 0; i < items.size(); i++) {
				int order = Values.orderForSort(left.keys()[i], right.keys()[i]);
				if (order != 0) {
					return items.get(i).ascending() ? order : -Integer.signum(order);
				}
			}
			return 0;
		});
		List<Row> sorted = new ArrayList<>(keyed.size());
		for (Keyed entry : keyed) {
			sorted.add(entry.row());
		}
		return sorted;
	}

	private record Keyed(Row row, Object[] keys) {
	}

	/**
	 * The value a sort item orders by.
	 *
	 * <p>
	 * Resolved as a projected column first. After an aggregation the row holds only the projection's output, so
	 * {@code RETURN n.city, count(*) ORDER BY n.city} has no {@code n} left to read a property from — but it does have
	 * a column called {@code n.city}, which is what the user meant and what Cypher orders by.
	 * </p>
	 */
	private Object sortKey(Ast.Expr expression, String name, String alias, Row row) {
		if (row.has(name)) {
			return row.get(name);
		}
		if (alias != null && row.has(alias)) {
			return row.get(alias);
		}
		return evaluator.evaluate(expression, row);
	}

	private List<Row> slice(List<Row> rows, Ast.Projection projection) {
		int from = 0;
		if (projection.skip() != null) {
			from = (int) Math.min(rows.size(), positive(projection.skip(), "SKIP"));
		}
		int to = rows.size();
		if (projection.limit() != null) {
			// Clamped before the addition, or SKIP 1 LIMIT 9223372036854775807 overflows into a negative bound.
			to = (int) Math.min(rows.size(), from + Math.min(rows.size(), positive(projection.limit(), "LIMIT")));
		}
		return from == 0 && to == rows.size() ? rows : new ArrayList<>(rows.subList(from, to));
	}

	private long positive(Ast.Expr expression, String clause) {
		Object value = evaluator.evaluate(expression, new Row());
		// An integer, not any number: LIMIT 1.5 was read as LIMIT 1, a row count nobody asked for.
		if (!(value instanceof Number given) || !Values.isIntegral(given)) {
			throw new CypherTypeException(clause + " needs a non-negative integer but got " + Values.typeName(value)
				+ (value instanceof Number ? " " + value : ""));
		}
		long number = given.longValue();
		if (number < 0) {
			throw new CypherTypeException(clause + " must not be negative, got " + number);
		}
		return number;
	}

	// ---------------------------------------------------------------- distinct

	/**
	 * DISTINCT, under Cypher equivalence rather than {@code Object.equals}.
	 *
	 * <p>
	 * A plain hash set over the values would be wrong — {@code 1} and {@code 1.0} are the same Cypher value with
	 * different hash codes, and two nodes are equal by id rather than by contents — so it is a hash set over
	 * {@link Values#groupingKey}, which is the same rule aggregation groups by. Two nulls are the same value here,
	 * which is the one place null equals null in Cypher.
	 * </p>
	 */
	private List<Row> distinctBy(List<Row> rows, List<String> columns) {
		List<Row> kept = new ArrayList<>();
		Set<Object> seen = new java.util.HashSet<>();
		for (Row row : rows) {
			List<Object> key = new ArrayList<>(columns.size());
			for (String column : columns) {
				key.add(row.get(column));
			}
			if (seen.add(Values.groupingKey(key))) {
				kept.add(row);
			}
		}
		return kept;
	}

	private static List<List<Object>> distinctRows(List<List<Object>> rows) {
		List<List<Object>> kept = new ArrayList<>();
		Set<Object> seen = new java.util.HashSet<>();
		for (List<Object> row : rows) {
			if (seen.add(Values.groupingKey(row))) {
				kept.add(row);
			}
		}
		return kept;
	}
}
