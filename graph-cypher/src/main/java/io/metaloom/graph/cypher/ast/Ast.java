package io.metaloom.graph.cypher.ast;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.core.api.Privilege;

/**
 * The syntax tree of the accepted subset, as one sealed family.
 *
 * <p>
 * One file rather than thirty, because these are data declarations rather than behaviour and reading the grammar in one
 * place is worth more than the file-per-type convention. Every node is a record: the tree is immutable, and the planner
 * and evaluator pattern-match over it.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
public final class Ast {

	private Ast() {
	}

	// ---------------------------------------------------------------- query structure

	/**
	 * A whole query: one or more parts combined with UNION.
	 *
	 * @param parts
	 *            never empty
	 * @param unionAll
	 *            one entry per union, so {@code parts.size() == unionAll.size() + 1}; true means {@code UNION ALL},
	 *            which keeps duplicates
	 */
	public record Query(List<SingleQuery> parts, List<Boolean> unionAll) {

		/**
		 * Whether running this query can change the store.
		 *
		 * <p>
		 * Answered from the tree so a caller can decide <em>before</em> executing: the servers use it to refuse a write
		 * on the event loop when writes are not enabled, and to choose between a read view and a write transaction. A
		 * query is a write if any of its union parts is.
		 * </p>
		 */
		public boolean isWrite() {
			for (SingleQuery part : parts) {
				if (part.isWrite()) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Whether running this query would read a file.
		 *
		 * <p>
		 * The companion to {@link #isWrite()}, and it exists because that method does <b>not</b> cover this.
		 * {@code LOAD CSV … RETURN row} creates nothing, sets nothing and deletes nothing, so it is not a write by any
		 * reading of the word — and a server that gated file access on {@code --allow-write} alone would therefore
		 * serve it on a read-only port. Since no port here has authentication, that is an arbitrary local file read for
		 * anything that can reach it.
		 * </p>
		 *
		 * <p>
		 * Answered from the tree for the same reason {@code isWrite} is: the refusal costs no worker thread, no
		 * transaction and no open file.
		 * </p>
		 */
		public boolean readsFiles() {
			for (SingleQuery part : parts) {
				if (part.readsFiles()) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Every privilege a principal needs before this may run.
		 *
		 * <p>
		 * The third policy question answered from the tree, alongside {@link #isWrite()} and {@link #readsFiles()}, and
		 * for their reason: a refusal decided here costs no worker thread, no transaction and no read view. The union
		 * over the union parts, because running the query runs all of them.
		 * </p>
		 *
		 * <p>
		 * <b>This is not the whole gate.</b> The server-wide flags are checked first and separately, and can only
		 * subtract — see {@code spec/AUTH.md} section 5. It is also not consulted by embedded callers at all: the
		 * enforcement point is each front end, exactly as it is for {@code --allow-write}, which
		 * {@code spec/CYPHER.md} section 5.1 states as "a property of the servers, not of the language".
		 * </p>
		 */
		public Set<Privilege> requiredPrivileges() {
			Set<Privilege> required = EnumSet.noneOf(Privilege.class);
			for (SingleQuery part : parts) {
				required.addAll(part.requiredPrivileges());
			}
			return required;
		}
	}

	/**
	 * A sequence of clauses, optionally ending in RETURN.
	 *
	 * @param periodicCommit
	 *            rows per transaction under {@code USING PERIODIC COMMIT}, or 0 when the whole part is one transaction.
	 *            It sits here rather than in the clause list because it is not a step in the pipeline: it precedes
	 *            {@code LOAD CSV}, governs every clause after it, and there can be at most one per part
	 */
	public record SingleQuery(List<Clause> clauses, int periodicCommit) {

		public SingleQuery(List<Clause> clauses) {
			this(clauses, 0);
		}

		public boolean isWrite() {
			for (Clause clause : clauses) {
				if (clause instanceof Create || clause instanceof Merge || clause instanceof SetClause
					|| clause instanceof Remove || clause instanceof Delete) {
					return true;
				}
			}
			return false;
		}

		/** See {@link Query#readsFiles()}. */
		public boolean readsFiles() {
			for (Clause clause : clauses) {
				if (clause instanceof LoadCsv) {
					return true;
				}
			}
			return false;
		}

		/**
		 * See {@link Query#requiredPrivileges()}.
		 *
		 * <h2>Why this is a switch where its two neighbours are {@code instanceof} chains</h2>
		 *
		 * <p>
		 * {@link Clause} is sealed, so an exhaustive {@code switch} with no {@code default} makes adding a twelfth
		 * clause a <b>compile error here</b> until its privilege is declared. That is worth more than the symmetry:
		 * {@link #isWrite()} answering "no" for a clause nobody classified is a wrong answer about whether to open a
		 * write transaction, but this answering "nothing required" for one would be a clause that authorises itself.
		 * The compiler is a better guard for that than any test could be, which is why no test asserts exhaustiveness.
		 * </p>
		 *
		 * <p>
		 * {@code UNWIND}, {@code WITH} and {@code RETURN} map to {@link Privilege#MATCH} rather than to nothing. They
		 * compute over rows something else bound, so they authorise no access of their own — but a query made only of
		 * them still occupies a worker thread, and {@code MATCH} is the floor for "may issue a read query at all".
		 * </p>
		 *
		 * <p>
		 * {@code MERGE} yields two, because it matches before it creates: a principal who may {@code MERGE} but not
		 * read would otherwise learn whether a pattern exists by watching whether one appeared.
		 * </p>
		 */
		public Set<Privilege> requiredPrivileges() {
			Set<Privilege> required = EnumSet.noneOf(Privilege.class);
			for (Clause clause : clauses) {
				switch (clause) {
					case Match _ -> required.add(Privilege.MATCH);
					case Unwind _ -> required.add(Privilege.MATCH);
					case With _ -> required.add(Privilege.MATCH);
					case Return _ -> required.add(Privilege.MATCH);
					// The one procedure is vector.query, which reads. A writing procedure would declare itself here.
					case Call _ -> required.add(Privilege.MATCH);
					case Create _ -> required.add(Privilege.CREATE);
					case Merge _ -> {
						required.add(Privilege.MATCH);
						required.add(Privilege.MERGE);
					}
					case SetClause _ -> required.add(Privilege.SET);
					case Remove _ -> required.add(Privilege.REMOVE);
					case Delete _ -> required.add(Privilege.DELETE);
					case LoadCsv _ -> required.add(Privilege.LOAD_CSV);
				}
			}
			return required;
		}
	}

	public sealed interface Clause permits Match, Unwind, With, Return, Create, Merge, SetClause, Remove, Delete,
		Call, LoadCsv {
	}

	/**
	 * A procedure call, which is how a query reaches an access path the pattern matcher has no way to express.
	 *
	 * <p>
	 * There is one procedure, {@code vector.query}, and the shape is deliberate rather than general: the planner is a
	 * fixed order with no cost model, so it could never <em>recognise</em> a nearest-neighbour query written as
	 * {@code ORDER BY … LIMIT}. Making the access path explicit is the honest alternative to pretending it can be
	 * inferred.
	 * </p>
	 *
	 * <p>
	 * Binding the node into a variable is what buys the rest: a following {@code MATCH} on that variable takes the
	 * pattern matcher's already-bound branch and <b>verifies</b> rather than scanning, so a graph predicate is applied
	 * to the search's results without a single planner change.
	 * </p>
	 *
	 * @param yields
	 *            the variables the call binds, in the order the procedure produces them
	 */
	public record Call(String procedure, List<Expr> arguments, List<String> yields) implements Clause {
	}

	/**
	 * @param optional
	 *            parsed so the error can name it; rejected by the planner, because OPTIONAL MATCH needs null-padding
	 *            semantics this subset does not implement
	 */
	public record Match(List<PatternPart> pattern, Expr where, boolean optional) implements Clause {
	}

	public record Unwind(Expr expression, String variable) implements Clause {
	}

	/**
	 * {@code LOAD CSV [WITH HEADERS] FROM <expr> AS <var> [FIELDTERMINATOR '<char>']}.
	 *
	 * <p>
	 * Structurally an {@link Unwind} whose list comes from outside the process: it binds one variable per row and
	 * multiplies the rows flowing through it, which is why it streams and why it needed no new machinery in the
	 * executor beyond the reader itself.
	 * </p>
	 *
	 * <p>
	 * The URL is an {@link Expr} rather than a string so that {@code $file} works. It is evaluated per execution and
	 * handed to the {@code CsvSource} unresolved — this tree deliberately does not know what a path is.
	 * </p>
	 *
	 * @param withHeaders
	 *            true to consume the first row as column names and bind each row as a map, false to bind a list
	 * @param fieldTerminator
	 *            the field separator, {@code ,} unless {@code FIELDTERMINATOR} said otherwise
	 */
	public record LoadCsv(Expr url, String variable, boolean withHeaders, char fieldTerminator) implements Clause {
	}

	public record With(Projection projection, Expr where) implements Clause {
	}

	public record Return(Projection projection) implements Clause {
	}

	// ---------------------------------------------------------------- write clauses

	/**
	 * {@code CREATE (a:Person {name:$n})-[r:KNOWS]->(b)} — runs once per input row.
	 *
	 * <p>
	 * The pattern grammar is shared with MATCH rather than duplicated, so a change to how a node pattern parses reaches
	 * both. What MATCH permits and CREATE does not — a variable-length range, an undirected relationship, a
	 * relationship with no type — is refused by the parser with a message naming the reason, because none of the three
	 * describes a graph that could be built.
	 * </p>
	 */
	public record Create(List<PatternPart> pattern) implements Clause {
	}

	/**
	 * {@code MERGE (a:Person {name:$n}) ON CREATE SET a.at = timestamp()} — match the whole pattern, or create it.
	 *
	 * <p>
	 * One pattern part rather than a list, which is openCypher's rule and not a simplification: MERGE's contract is
	 * that the pattern matches <em>entirely</em> or is built <em>entirely</em>, and a comma-separated list has no
	 * single answer to that. Write two MERGE clauses instead, which is the same thing said unambiguously.
	 * </p>
	 *
	 * @param onCreate
	 *            {@code ON CREATE SET …}, applied only to a pattern this clause built
	 * @param onMatch
	 *            {@code ON MATCH SET …}, applied only to rows the pattern found
	 */
	public record Merge(PatternPart pattern, List<SetItem> onCreate, List<SetItem> onMatch) implements Clause {
	}

	/**
	 * {@code SET} — named {@code SetClause} because {@code Set} beside {@code java.util.Set} in an executor that uses
	 * both would be a genuine hazard rather than a stylistic one.
	 */
	public record SetClause(List<SetItem> items) implements Clause {
	}

	public sealed interface SetItem permits SetProperty, SetPropertiesReplace, SetPropertiesMerge, SetLabels {
	}

	/** {@code SET n.age = 30}. A null value removes the property, which is Cypher's rule rather than an accident. */
	public record SetProperty(Expr target, String key, Expr value) implements SetItem {
	}

	/** {@code SET n = {a:1}} — every existing property is replaced by the map. */
	public record SetPropertiesReplace(String variable, Expr map) implements SetItem {
	}

	/** {@code SET n += {a:1}} — the map is merged over what is there. */
	public record SetPropertiesMerge(String variable, Expr map) implements SetItem {
	}

	/** {@code SET n:Person:Employee} */
	public record SetLabels(String variable, List<String> labels) implements SetItem {
	}

	public record Remove(List<RemoveItem> items) implements Clause {
	}

	public sealed interface RemoveItem permits RemoveProperty, RemoveLabels {
	}

	/** {@code REMOVE n.age} */
	public record RemoveProperty(Expr target, String key) implements RemoveItem {
	}

	/** {@code REMOVE n:Employee} */
	public record RemoveLabels(String variable, List<String> labels) implements RemoveItem {
	}

	/**
	 * @param detach
	 *            {@code DETACH DELETE}, which removes a node's relationships first. Without it, deleting a node that
	 *            still has any is refused — the engine's own delete always cascades, so the refusal is the query
	 *            language's rule and has to be enforced above it
	 */
	public record Delete(List<Expr> targets, boolean detach) implements Clause {
	}

	/**
	 * The shared body of RETURN and WITH.
	 *
	 * @param star
	 *            {@code RETURN *} — every variable in scope, in binding order
	 */
	public record Projection(boolean distinct, boolean star, List<ProjectionItem> items, List<SortItem> order,
		Expr skip, Expr limit) {
	}

	/**
	 * @param alias
	 *            the output column name; defaults to the source text of the expression when no AS is given
	 */
	public record ProjectionItem(Expr expression, String alias) {
	}

	public record SortItem(Expr expression, boolean ascending) {
	}

	// ---------------------------------------------------------------- patterns

	/**
	 * One comma-separated element of a MATCH pattern.
	 *
	 * @param pathVariable
	 *            set by {@code p = (a)-[]->(b)}, or null
	 * @param selector
	 *            which of the matching paths survive; {@link PathSelector#ALL} unless the part was wrapped in
	 *            {@code shortestPath(…)} or {@code allShortestPaths(…)}
	 * @param nodes
	 *            always one more than {@code relationships}
	 */
	public record PatternPart(String pathVariable, PathSelector selector, List<NodePattern> nodes,
		List<RelPattern> relationships) {
	}

	/**
	 * Which of the paths a pattern describes are kept.
	 *
	 * <p>
	 * A property of the <em>pattern part</em> rather than a function of the path it binds, because it changes how the
	 * part is matched and not what is done with the result: {@link #ALL} enumerates every route depth-first, the other
	 * two search breadth-first and stop at the first distance that reaches each endpoint. Modelling it as a function
	 * over paths would mean enumerating everything and then discarding almost all of it, which is the difference
	 * between a bounded search and an exponential one.
	 * </p>
	 */
	public enum PathSelector {

		/** Every path the pattern matches, which is the default and what a bare pattern means. */
		ALL,

		/** {@code shortestPath(…)} — one path per pair of endpoints, of minimal length. */
		SHORTEST,

		/** {@code allShortestPaths(…)} — every path of minimal length, per pair of endpoints. */
		ALL_SHORTEST
	}

	/**
	 * @param variable
	 *            null for an anonymous node, {@code ()}
	 * @param properties
	 *            the inline map, which is sugar for equality predicates; null when absent
	 */
	public record NodePattern(String variable, List<String> labels, Map<String, Expr> properties) {
	}

	/**
	 * @param direction
	 *            which way the arrow points, relative to the node on its left
	 * @param range
	 *            null for a single hop, otherwise the {@code *} bounds
	 */
	public record RelPattern(String variable, List<String> types, Map<String, Expr> properties, Direction direction,
		Range range) {
	}

	public enum Direction {
		LEFT, RIGHT, BOTH
	}

	/**
	 * The bounds of a variable-length pattern.
	 *
	 * @param min
	 *            defaults to 1; {@code *} alone is 1 to unbounded
	 * @param max
	 *            {@link Integer#MAX_VALUE} when open-ended
	 */
	public record Range(int min, int max) {
	}

	// ---------------------------------------------------------------- expressions

	public sealed interface Expr permits Literal, ListLiteral, MapLiteral, Variable, Parameter, Property, Index,
		Binary, Unary, Comparison, LabelPredicate, IsNull, FunctionCall, Case {
	}

	/** A number, string, boolean or null. */
	public record Literal(Object value) implements Expr {
	}

	public record ListLiteral(List<Expr> elements) implements Expr {
	}

	public record MapLiteral(Map<String, Expr> entries) implements Expr {
	}

	public record Variable(String name) implements Expr {
	}

	public record Parameter(String name) implements Expr {
	}

	/** {@code n.age} — also how a map key is read, since a node behaves as a map of its properties. */
	public record Property(Expr target, String key) implements Expr {
	}

	/** {@code list[0]} */
	public record Index(Expr target, Expr index) implements Expr {
	}

	public record Binary(Op op, Expr left, Expr right) implements Expr {
	}

	public record Unary(Op op, Expr operand) implements Expr {
	}

	/** Kept apart from {@link Binary} because comparison is where three-valued logic bites. */
	public record Comparison(Op op, Expr left, Expr right) implements Expr {
	}

	/** {@code n:Person} as a predicate rather than as part of a pattern. */
	public record LabelPredicate(Expr target, List<String> labels) implements Expr {
	}

	public record IsNull(Expr operand, boolean negated) implements Expr {
	}

	public record FunctionCall(String name, List<Expr> arguments, boolean distinct) implements Expr {
	}

	/**
	 * {@code CASE}, in both of its forms.
	 *
	 * @param subject
	 *            the simple form, {@code CASE x WHEN 1 THEN …}, where each {@code when} is compared to {@code x} for
	 *            equality; null for the generic form, {@code CASE WHEN x > 1 THEN …}, where each is a predicate
	 * @param whens
	 *            the same length as {@code thens}, and at least one
	 * @param otherwise
	 *            {@code ELSE}, or null — in which case an unmatched CASE is null rather than an error
	 */
	public record Case(Expr subject, List<Expr> whens, List<Expr> thens, Expr otherwise) implements Expr {
	}

	public enum Op {
		OR, XOR, AND, NOT,
		EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL,
		IN, STARTS_WITH, ENDS_WITH, CONTAINS,
		ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, NEGATE
	}
}
