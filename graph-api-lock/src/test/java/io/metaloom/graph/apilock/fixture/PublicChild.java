package io.metaloom.graph.apilock.fixture;

/** A public class over a package-private superclass. */
public class PublicChild extends Base {

	public PublicChild() {
	}

	protected PublicChild(int size) {
	}

	PublicChild(String hidden) {
	}

	@Override
	public String overridden() {
		return "child";
	}

	@Override
	protected void hook() {
	}
}
