package io.metaloom.graph.apilock;

import java.util.List;
import java.util.Objects;

/**
 * What counts as the public API of one module: an anchor class that locates the module's compiled classes, and the
 * roots whose public types are dumped.
 *
 * <p>
 * A root is one of three things. {@link Root#moduleExports()} reads the unqualified {@code exports} of the
 * {@code module-info.class} next to the anchor, so a package that becomes exported is locked without anybody editing
 * this list. {@link Root#pkg(String)} is every public type directly in a package, found by listing the class files
 * there rather than by naming them, so a newly added public type is caught too. {@link Root#type(String)} is one named
 * type and its public nested types, for a module whose callers need a handful of types out of an otherwise internal
 * package.
 * </p>
 *
 * <p>
 * The resolved roots are written into the dump itself as {@code scope} lines. Narrowing a scope is therefore an API
 * change like any other: the baseline stops matching.
 * </p>
 *
 * @param anchor
 *            binary name of a class in the module; its code source is where the module's classes are listed from
 * @param roots
 *            the scope roots, in the order their {@code scope} lines are written
 */
public record ApiScope(String anchor, List<Root> roots) {

	public ApiScope {
		Objects.requireNonNull(anchor, "anchor");
		roots = List.copyOf(roots);
		if (roots.isEmpty()) {
			throw new IllegalArgumentException("A scope needs at least one root");
		}
	}

	public static ApiScope of(String anchor, Root... roots) {
		return new ApiScope(anchor, List.of(roots));
	}

	/**
	 * One scope root.
	 *
	 * @param kind
	 *            what the root names
	 * @param name
	 *            a package name, a binary type name, or empty for {@link Kind#MODULE_EXPORTS}
	 */
	public record Root(Kind kind, String name) {

		public Root {
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(name, "name");
		}

		/** Every unqualified export of the anchor's {@code module-info.class}. */
		public static Root moduleExports() {
			return new Root(Kind.MODULE_EXPORTS, "");
		}

		/** Every public type directly in the package, nested public types included. */
		public static Root pkg(String packageName) {
			return new Root(Kind.PACKAGE, packageName);
		}

		/** One type by binary name ({@code a.b.Outer$Inner}), and its public nested types. */
		public static Root type(String binaryName) {
			return new Root(Kind.TYPE, binaryName);
		}
	}

	/** The three kinds of root. */
	public enum Kind {
		MODULE_EXPORTS, PACKAGE, TYPE
	}
}
