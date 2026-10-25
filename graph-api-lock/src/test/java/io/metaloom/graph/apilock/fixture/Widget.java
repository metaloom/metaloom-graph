package io.metaloom.graph.apilock.fixture;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Generic interface: abstract, default, static and deprecated methods, and constants. */
public interface Widget<T extends Comparable<T>> extends AutoCloseable {

	int LIMIT = 4;

	String NAME = "w\"x\n";

	long BIG = 1L << 40;

	boolean FLAG = true;

	char LETTER = 'q';

	T value();

	default List<T> values(Map<String, ? super T> sink) throws IOException {
		return List.of(value());
	}

	static <E extends Exception & Comparable<E>> Widget<String> of(String first, String... rest) throws E {
		return null;
	}

	@Deprecated(since = "0.1", forRemoval = true)
	void legacy();
}
