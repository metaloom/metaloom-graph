package io.metaloom.graph.apilock.fixture;

/** Records whether a fixture's static initialiser ran; the dump must load without initialising. */
public final class Initialised {

	public static volatile boolean colour;

	public static volatile boolean outer;

	private Initialised() {
	}
}
