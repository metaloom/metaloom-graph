package io.metaloom.graph.apilock.fixture;

import java.util.List;
import java.util.function.Supplier;

/** Nested types of every visibility, a lambda and an anonymous class, which must not leak synthetic members. */
@Deprecated
public class Outer<K> {

	static {
		Initialised.outer = true;
	}

	public final List<String> names = List.of();

	protected int counter;

	int packageField;

	private int privateField;

	public Outer() {
	}

	public <V extends K> Supplier<V> supplier(V value) {
		Supplier<V> lambda = () -> value;
		Runnable anonymous = new Runnable() {
			@Override
			public void run() {
			}
		};
		anonymous.run();
		return lambda;
	}

	protected void guarded() {
	}

	void packageMethod() {
	}

	private void privateMethod() {
	}

	public static class Inner implements Comparable<Inner> {

		@Override
		public int compareTo(Inner other) {
			return 0;
		}
	}

	public class InnerOfInstance {
	}

	protected interface Guarded {

		void guard();
	}

	private static class Secret {

		public void notApi() {
		}
	}

	@FunctionalInterface
	public interface Callback {

		void call(int[] values, List<? extends Number>[] lists);
	}

	public @interface Marker {

		String value() default "m";

		int[] sizes() default { 1, 2 };
	}
}
