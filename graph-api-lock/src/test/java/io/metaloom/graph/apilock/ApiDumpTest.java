package io.metaloom.graph.apilock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.apilock.fixture.Initialised;

/**
 * The rendering, against fixture classes that cover each shape a public API can take.
 *
 * <p>
 * Asserted line by line rather than against a golden file, so that each case says which rule it pins.
 * </p>
 */
@Tag("unit")
public class ApiDumpTest {

	static final String FIXTURE_PACKAGE = "io.metaloom.graph.apilock.fixture";
	static final String P = FIXTURE_PACKAGE + ".";

	static final ApiScope FIXTURE = ApiScope.of(P + "Widget", ApiScope.Root.pkg(FIXTURE_PACKAGE));

	static List<String> dump() {
		return ApiDump.render(FIXTURE, ApiDumpTest.class.getClassLoader());
	}

	private static void assertLine(List<String> dump, String line) {
		assertTrue(dump.contains(line), () -> "missing: " + line + "\n--- dump ---\n" + String.join("\n", dump));
	}

	@Test
	public void testDeterministicAndSortedByOwner() {
		List<String> first = dump();
		assertEquals(first, dump());
		assertEquals("scope package " + FIXTURE_PACKAGE, first.get(0));
		List<String> owners = first.stream().skip(1).map(line -> line.split(" ")[0]).toList();
		assertEquals(owners.stream().sorted().toList(), owners);
	}

	@Test
	public void testLoadsWithoutRunningStaticInitialisers() {
		dump();
		assertFalse(Initialised.colour, "dumping Colour ran its static initialiser");
		assertFalse(Initialised.outer, "dumping Outer ran its static initialiser");
	}

	@Test
	public void testGenericSignaturesThrowsAndVarargs() {
		List<String> dump = dump();
		assertLine(dump, P + "Widget type public interface Widget<T extends java.lang.Comparable<T>> extends java.lang.AutoCloseable");
		assertLine(dump, P + "Widget method public abstract T value()");
		assertLine(dump, P + "Widget method public default java.util.List<T> values(java.util.Map<java.lang.String, ? super T>)"
			+ " throws java.io.IOException");
		assertLine(dump, P + "Widget method public static <E extends java.lang.Exception & java.lang.Comparable<E>>"
			+ " " + P + "Widget<java.lang.String> of(java.lang.String, java.lang.String...) throws E");
		assertLine(dump, P + "Outer method public <V extends K> java.util.function.Supplier<V> supplier(V)");
		assertLine(dump, P + "Outer$Callback method public abstract void call(int[], java.util.List<? extends java.lang.Number>[])");
	}

	@Test
	public void testConstantValuesAreRenderedBecauseCallersInlineThem() {
		List<String> dump = dump();
		assertLine(dump, P + "Widget field public static final int LIMIT = 4");
		assertLine(dump, P + "Widget field public static final long BIG = 1099511627776L");
		assertLine(dump, P + "Widget field public static final boolean FLAG = true");
		assertLine(dump, P + "Widget field public static final char LETTER = 'q'");
		assertLine(dump, P + "Widget field public static final java.lang.String NAME = \"w\\\"x\\n\"");
		// Not a compile-time constant: no value.
		assertLine(dump, P + "Outer field public final java.util.List<java.lang.String> names");
	}

	@Test
	public void testDeprecationIsItsOwnLineSoDeprecatingReadsAsAnAddition() {
		List<String> dump = dump();
		assertLine(dump, P + "Widget method public abstract void legacy()");
		assertLine(dump, P + "Widget deprecated method legacy() @java.lang.Deprecated(since=\"0.1\", forRemoval=true)");
		assertLine(dump, P + "Outer deprecated type @java.lang.Deprecated(since=\"\", forRemoval=false)");
	}

	@Test
	public void testRecordsEnumsSealedAndAnnotations() {
		List<String> dump = dump();
		assertLine(dump, P + "Shape type public sealed interface Shape permits " + P + "Shape$Circle, " + P + "Shape$Square");
		assertLine(dump, P + "Shape$Square type public record Square implements " + P + "Shape");
		assertLine(dump, P + "Shape$Square components (double side, java.util.List<java.lang.String> tags)");
		assertLine(dump, P + "Shape$Square constructor public Square(double)");
		assertLine(dump, P + "Shape$Square constructor public Square(double, java.util.List<java.lang.String>)");
		// Constants with bodies make the enum abstract and sealed over anonymous classes: not rendered.
		assertLine(dump, P + "Colour type public enum Colour");
		assertLine(dump, P + "Colour constant RED ordinal=0");
		assertLine(dump, P + "Colour constant GREEN ordinal=1");
		assertLine(dump, P + "Colour method public static " + P + "Colour valueOf(java.lang.String)");
		assertLine(dump, P + "Outer$Callback annotation @java.lang.FunctionalInterface");
		assertLine(dump, P + "Outer$Marker type public @interface Marker");
		assertLine(dump, P + "Outer$Marker method public abstract java.lang.String value() default \"m\"");
		assertLine(dump, P + "Outer$Marker method public abstract int[] sizes() default {1, 2}");
	}

	@Test
	public void testVisibility() {
		List<String> dump = dump();
		assertLine(dump, P + "Outer field protected int counter");
		assertLine(dump, P + "Outer method protected void guarded()");
		assertLine(dump, P + "Outer$Guarded type protected interface Guarded");
		assertLine(dump, P + "Outer$Inner type public static class Inner implements java.lang.Comparable<" + P + "Outer$Inner>");
		for (String line : dump) {
			assertFalse(line.startsWith(P + "Hidden "), line);
			assertFalse(line.startsWith(P + "Base "), line);
			assertFalse(line.startsWith(P + "Outer$Secret "), line);
			assertFalse(line.startsWith(P + "Outer$1 "), "anonymous class: " + line);
			assertFalse(line.contains("lambda$"), "synthetic lambda body: " + line);
			assertFalse(line.contains("packageMethod") || line.contains("privateMethod"), line);
			assertFalse(line.contains("packageField") || line.contains("privateField"), line);
			assertFalse(line.contains("PublicChild(java.lang.String)"), "package-private constructor: " + line);
		}
	}

	@Test
	public void testMembersInheritedFromANonPublicSuperclassAreApiOfThePublicClass() {
		List<String> dump = dump();
		assertLine(dump, P + "PublicChild type public class PublicChild extends " + P + "Base");
		assertLine(dump, P + "PublicChild method public java.lang.String inherited()");
		assertLine(dump, P + "PublicChild method public java.lang.String overridden()");
		assertLine(dump, P + "PublicChild field public static final int BASE_CONSTANT = 7");
		assertLine(dump, P + "PublicChild constructor protected PublicChild(int)");
		assertEquals(1, dump.stream().filter(line -> line.contains(" overridden()")).count(), "an override is one method");
	}

	@Test
	public void testTypeRootLocksOneTypeAndItsNestedTypes() {
		List<String> dump = ApiDump.render(ApiScope.of(P + "Widget", ApiScope.Root.type(P + "Shape")),
			ApiDumpTest.class.getClassLoader());
		assertEquals("scope type " + P + "Shape", dump.get(0));
		assertEquals(List.of(P + "Shape", P + "Shape$Circle", P + "Shape$Square"),
			dump.stream().skip(1).map(line -> line.split(" ")[0]).distinct().toList());
	}

	@Test
	public void testAScopeThatNamesNothingIsAnErrorNotAnEmptyDump() {
		ClassLoader loader = ApiDumpTest.class.getClassLoader();
		assertThrows(IllegalStateException.class,
			() -> ApiDump.render(ApiScope.of(P + "Widget", ApiScope.Root.pkg("io.metaloom.graph.apilock.nothing")), loader));
		assertThrows(IllegalStateException.class,
			() -> ApiDump.render(ApiScope.of(P + "Widget", ApiScope.Root.type(P + "Missing")), loader));
		// The test classes have no module-info.class.
		assertThrows(IllegalStateException.class,
			() -> ApiDump.render(ApiScope.of(P + "Widget", ApiScope.Root.moduleExports()), loader));
	}

	@Test
	public void testGroupKeyIsOwnerCategoryAndName() {
		assertEquals("a.B method of", ApiDump.groupKey("a.B method public static <T> a.B<T> of(T, int) throws E"));
		assertEquals("a.B constructor B", ApiDump.groupKey("a.B constructor protected B(int)"));
		assertEquals("a.B field LIMIT", ApiDump.groupKey("a.B field public static final java.lang.String LIMIT = \"x(y\""));
		assertEquals("a.B constant RED", ApiDump.groupKey("a.B constant RED ordinal=0"));
		assertEquals("a.B deprecated method m(int)", ApiDump.groupKey("a.B deprecated method m(int) @java.lang.Deprecated(since=\"\", forRemoval=false)"));
		assertEquals("a.B type ", ApiDump.groupKey("a.B type public final class B"));
	}
}
