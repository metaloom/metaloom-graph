package io.metaloom.graph.cypher.ast;

import java.util.stream.Collectors;

/**
 * Renders an expression back to the text a user would have written.
 *
 * <p>
 * Needed because an unaliased projection column is named after its source text — {@code RETURN n.city} produces a
 * column called {@code n.city} — and {@code ORDER BY n.city} after that projection has to find it. By then the row no
 * longer holds {@code n}, only the projected column, so the sort expression is resolved by name first and evaluated
 * only if that fails.
 * </p>
 *
 * <p>
 * This must agree with the parser's alias generation for the shapes that reach it. Where it cannot — an expression too
 * complex to render faithfully — it returns something that will not match any column, and the caller falls back to
 * evaluating, which is the correct behaviour anyway.
 * </p>
 */
public final class Rendering {

	private Rendering() {
	}

	public static String text(Ast.Expr expression) {
		return switch (expression) {
			case Ast.Variable variable -> variable.name();
			case Ast.Property property -> text(property.target()) + "." + property.key();
			case Ast.Parameter parameter -> "$" + parameter.name();
			case Ast.Literal literal -> literal.value() instanceof String string ? "'" + string + "'"
				: String.valueOf(literal.value());
			case Ast.Index index -> text(index.target()) + "[" + text(index.index()) + "]";
			case Ast.FunctionCall call -> call.name() + "("
				+ (call.arguments().isEmpty() ? "*"
					: (call.distinct() ? "DISTINCT " : "")
						+ call.arguments().stream().map(Rendering::text).collect(Collectors.joining(", ")))
				+ ")";
			default -> " unrenderable";
		};
	}
}
