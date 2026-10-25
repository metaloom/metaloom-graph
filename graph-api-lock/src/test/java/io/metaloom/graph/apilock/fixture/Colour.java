package io.metaloom.graph.apilock.fixture;

/** An enum whose constants have bodies, so the class is not final, and a static initialiser that must not run. */
public enum Colour {
	RED {
		@Override
		public String code() {
			return "r";
		}
	},
	GREEN {
		@Override
		public String code() {
			return "g";
		}
	};

	static {
		if (Boolean.getBoolean("never.set.by.anyone")) {
			throw new IllegalStateException();
		}
		Initialised.colour = true;
	}

	public abstract String code();
}
