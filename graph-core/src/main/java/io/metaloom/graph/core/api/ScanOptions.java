package io.metaloom.graph.core.api;

/**
 * How {@link GraphStore#forEachNodeWhile(java.util.function.LongPredicate, ScanOptions)} behaves against a live store.
 *
 * <p>
 * A full scan takes the read latch and holds it, because a scan cannot be re-run — its consumer has side effects — and
 * the optimistic path exists to be repeated. That is the right trade for a query, which is over in milliseconds, and
 * the wrong one for a walk of every node in the store: <b>the latch it holds is the one a committing writer has to take
 * exclusively</b>, so a long scan blocks every writer in the process, embedded callers included.
 * </p>
 *
 * <p>
 * Throttling releases the latch every {@code batchSize} ids and optionally sleeps, so a writer waiting behind the scan
 * waits for a batch rather than for the store. The shape is deliberately {@link VerifyOptions}', because it is the same
 * problem — {@code verify} met it first — and two spellings of one idea would be worse than one.
 * </p>
 *
 * <h2>Unlike {@code verify}, this costs no consistency</h2>
 *
 * <p>
 * A throttled {@code verify} is no longer a single point in time, and {@link VerifyOptions} says so. A throttled
 * <em>scan</em> is, <b>provided the caller holds a read view</b> — and that is the difference worth understanding
 * before relying on it. The bitmap the scan walks and the generation it reads both go through
 * {@code SegmentFile.readSlice}, which resolves through the snapshot's page versions like any other read, so a node
 * allocated after the view opened reads as free and is skipped whether or not the latch was dropped in between. The
 * snapshot fixes what the scan sees; the latch only ever fixed <em>when</em>.
 * </p>
 *
 * <p>
 * Without a view, a throttled scan is what it sounds like: a series of instants. Use {@link #uninterrupted()} then, or
 * open a view.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/BACKUP.md">spec/BACKUP.md</a>
 */
public final class ScanOptions {

	/** The same 4096 {@code VerifyOptions} uses, and for the same reason: small enough to matter, large enough to amortise. */
	public static final int DEFAULT_BATCH_SIZE = 4096;

	public static final long DEFAULT_PAUSE_MICROS = 200;

	private final int batchSize;
	private final long pauseMicrosPerBatch;

	private ScanOptions(int batchSize, long pauseMicrosPerBatch) {
		this.batchSize = batchSize;
		this.pauseMicrosPerBatch = pauseMicrosPerBatch;
	}

	/** One uninterrupted pass, holding the latch throughout. The default, and what an ordinary query wants. */
	public static ScanOptions uninterrupted() {
		return new ScanOptions(0, 0);
	}

	/** Yields every {@value #DEFAULT_BATCH_SIZE} ids and pauses briefly, so a foreground writer is never blocked for long. */
	public static ScanOptions throttled() {
		return new ScanOptions(DEFAULT_BATCH_SIZE, DEFAULT_PAUSE_MICROS);
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Ids per batch, or 0 for one uninterrupted pass. */
	public int batchSize() {
		return batchSize;
	}

	public long pauseMicrosPerBatch() {
		return pauseMicrosPerBatch;
	}

	public boolean isThrottled() {
		return batchSize > 0;
	}

	public static final class Builder {

		private int batchSize = DEFAULT_BATCH_SIZE;
		private long pauseMicrosPerBatch = DEFAULT_PAUSE_MICROS;

		public Builder batchSize(int batchSize) {
			if (batchSize < 0) {
				throw new IllegalArgumentException("A batch size cannot be negative; 0 means one uninterrupted pass");
			}
			this.batchSize = batchSize;
			return this;
		}

		public Builder pauseMicrosPerBatch(long pauseMicrosPerBatch) {
			if (pauseMicrosPerBatch < 0) {
				throw new IllegalArgumentException("A pause cannot be negative");
			}
			this.pauseMicrosPerBatch = pauseMicrosPerBatch;
			return this;
		}

		public ScanOptions build() {
			return new ScanOptions(batchSize, pauseMicrosPerBatch);
		}
	}
}
