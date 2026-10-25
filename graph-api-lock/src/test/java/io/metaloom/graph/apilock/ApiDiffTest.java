package io.metaloom.graph.apilock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What counts as removed, changed, added, and the one kind of addition that breaks. */
@Tag("unit")
public class ApiDiffTest {

	private static final List<String> BASE = List.of(
		"scope package a",
		"a.I type public interface I",
		"a.I method public abstract int size()",
		"a.I method public abstract void put(int)",
		"a.F type public final class F",
		"a.F constructor public F()",
		"a.F method public int size()",
		"a.R type public record R",
		"a.S type public sealed interface S permits a.R");

	private static List<String> with(List<String> lines, String... more) {
		List<String> out = new java.util.ArrayList<>(lines);
		out.addAll(List.of(more));
		return out;
	}

	private static List<String> without(List<String> lines, String line) {
		List<String> out = new java.util.ArrayList<>(lines);
		assertTrue(out.remove(line), line);
		return out;
	}

	@Test
	public void testIdenticalIsEmpty() {
		assertTrue(ApiDiff.between(BASE, BASE).isEmpty());
	}

	@Test
	public void testAnOverloadIsAnAddition() {
		ApiDiff diff = ApiDiff.between(BASE, with(BASE, "a.F method public int size(boolean)"));
		assertEquals(List.of("a.F method public int size(boolean)"), diff.added());
		assertFalse(diff.isBreaking());
	}

	@Test
	public void testAChangedReturnTypeIsOneChange() {
		List<String> live = with(without(BASE, "a.F method public int size()"), "a.F method public long size()");
		ApiDiff diff = ApiDiff.between(BASE, live);
		assertEquals(1, diff.changed().size());
		assertEquals("a.F method size", diff.changed().get(0).key());
		assertTrue(diff.added().isEmpty() && diff.removed().isEmpty());
		assertTrue(diff.isBreaking());
		assertTrue(diff.render().contains("was: a.F method public int size()"), diff.render());
		assertTrue(diff.render().contains("now: a.F method public long size()"), diff.render());
	}

	@Test
	public void testARemovalIsBreaking() {
		ApiDiff diff = ApiDiff.between(BASE, without(BASE, "a.I method public abstract void put(int)"));
		assertEquals(List.of("a.I method public abstract void put(int)"), diff.removed());
		assertTrue(diff.isBreaking());
		assertTrue(diff.render().startsWith("BREAKING - removed (1):"), diff.render());
	}

	@Test
	public void testAnAbstractMethodOnAnImplementableInterfaceIsBreaking() {
		ApiDiff diff = ApiDiff.between(BASE, with(BASE, "a.I method public abstract void clear()"));
		assertEquals(List.of("a.I method public abstract void clear()"), diff.abstractAdded());
		assertTrue(diff.isBreaking());
	}

	@Test
	public void testADefaultMethodIsNot() {
		ApiDiff diff = ApiDiff.between(BASE, with(BASE, "a.I method public default void clear()"));
		assertFalse(diff.isBreaking());
	}

	@Test
	public void testAnAbstractMethodOnASealedOrNewTypeIsNot() {
		assertFalse(ApiDiff.between(BASE, with(BASE, "a.S method public abstract int arity()")).isBreaking());
		assertFalse(ApiDiff.between(BASE, with(BASE, "a.N type public interface N", "a.N method public abstract int n()"))
			.isBreaking());
	}

	@Test
	public void testNarrowingTheScopeIsARemoval() {
		ApiDiff diff = ApiDiff.between(BASE, without(BASE, "scope package a"));
		assertEquals(List.of("scope package a"), diff.removed());
	}
}
