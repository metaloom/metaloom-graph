package io.metaloom.graph.apilock.fixture;

/** Package-private: not API itself, but its public members are, through PublicChild. */
abstract class Base {

	public static final int BASE_CONSTANT = 7;

	public String inherited() {
		return "base";
	}

	public String overridden() {
		return "base";
	}

	protected abstract void hook();
}
