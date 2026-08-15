package io.metaloom.graph.core.api;

import java.time.Duration;

/**
 * How {@link GraphStore#verify(VerifyLevel, VerifyOptions)} behaves when it is run against a live store.
 * <p>
 * A full verification walks every live element and holds the read latch while it does so, which on a large store is
 * long enough to matter to whatever else is using it. Throttling turns one long pause into many short ones: the scan
 * releases the latch every {@code batchSize} elements and optionally sleeps, so a writer waiting behind it waits for a
 * batch rather than for the whole store.
 * <p>
 * The cost of that is a verification that is no longer a single point-in-time snapshot. It sees the store as it was at
 * a series of instants, so a concurrent writer can produce a violation report for a state that never existed as a
 * whole — an edge added between two batches can look like a missing mirror entry. {@link #consistentSnapshot()} is the
 * opt-out: it holds the latch for the whole scan and is what a test, or an operator investigating an actual
 * corruption, should use.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 5
 */
public final class VerifyOptions {

	private final int batchSize;
	private final long pauseMicrosPerBatch;
	private final int maxViolations;
	private final Duration timeBudget;

	private VerifyOptions(int batchSize, long pauseMicrosPerBatch, int maxViolations, Duration timeBudget) {
		this.batchSize = batchSize;
		this.pauseMicrosPerBatch = pauseMicrosPerBatch;
		this.maxViolations = maxViolations;
		this.timeBudget = timeBudget;
	}

	/** One uninterrupted pass. Every violation is reported and the store is seen as of a single instant. */
	public static VerifyOptions consistentSnapshot() {
		return new VerifyOptions(0, 0, Integer.MAX_VALUE, null);
	}

	/** Yields every 4096 elements and pauses briefly, so a foreground writer is never blocked for long. */
	public static VerifyOptions throttled() {
		return new VerifyOptions(4096, 200, 1000, null);
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Elements per batch, or 0 for one uninterrupted pass. */
	public int batchSize() {
		return batchSize;
	}

	public long pauseMicrosPerBatch() {
		return pauseMicrosPerBatch;
	}

	/** Stops after this many violations. A store with a million broken records does not need a million lines. */
	public int maxViolations() {
		return maxViolations;
	}

	/** Stops the scan when this much wall clock has passed, reporting what it found. Null means no limit. */
	public Duration timeBudget() {
		return timeBudget;
	}

	public boolean isThrottled() {
		return batchSize > 0;
	}

	public static final class Builder {

		private int batchSize = 4096;
		private long pauseMicrosPerBatch = 200;
		private int maxViolations = 1000;
		private Duration timeBudget;

		public Builder batchSize(int batchSize) {
			this.batchSize = batchSize;
			return this;
		}

		public Builder pauseMicrosPerBatch(long pauseMicrosPerBatch) {
			this.pauseMicrosPerBatch = pauseMicrosPerBatch;
			return this;
		}

		public Builder maxViolations(int maxViolations) {
			this.maxViolations = maxViolations;
			return this;
		}

		public Builder timeBudget(Duration timeBudget) {
			this.timeBudget = timeBudget;
			return this;
		}

		public VerifyOptions build() {
			return new VerifyOptions(batchSize, pauseMicrosPerBatch, maxViolations, timeBudget);
		}
	}
}
