package io.metaloom.graph.cypher.ast;

import java.util.List;
import java.util.Map;

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
	}

	/** A sequence of clauses, optionally ending in RETURN. */
	public record SingleQuery(List<Clause> clauses) {

		public boolean isWrite() {
			for (Clause clause : clauses) {
				if (clause instanceof Create || clause instanceof SetClause || clause instanceof Remove
					|| clause instanceof Delete) {
					return true;
				}
			}
			return false;
		}
	}

	public sealed interface Clause permits Match, Unwind, With, Return, Create, SetClause, Remove, Delete {
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
	 * @param nodes
	 *            always one more than {@code relationships}
	 */
	public record PatternPart(String pathVariable, List<NodePattern> nodes, List<RelPattern> relationships) {
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
	 * Parsed but rejected by the planner. Present so that {@code CASE} produces "not supported in this build" rather
	 * than a bare syntax error, which would read as though the query were malformed.
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
