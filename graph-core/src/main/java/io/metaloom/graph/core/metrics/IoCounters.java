package io.metaloom.graph.core.metrics;

import java.util.concurrent.atomic.LongAdder;

import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.id.Kind;

/**
 * The store's I/O counters.
 * <p>
 * These exist to make two claims falsifiable rather than merely plausible. The first is the design's central promise,
 * that reading an element by id costs no index probe and no extra seek: a counter is the only way to assert "this
 * operation touched exactly one node record and nothing else" as a build failure rather than as a comment. The second
 * is write amplification, which is a ratio of two byte counts and cannot be argued about without both of them.
 * <p>
 * <b>The per-kind counters are {@link LongAdder}s and the rest are plain longs.</b> That split is not cosmetic. A
 * record read runs tens of millions of times a second from every reader thread at once, and a plain
 * {@code counter++} on a shared field is a write to a shared cache line: measured on a 24 thread machine it cost more
 * than the lock it was sitting next to, turning read scaling from 1.4x at two threads into 0.5x. A {@code LongAdder}
 * spreads the write across padded cells and the contention disappears. Everything else here is incremented once per
 * commit or per checkpoint, under the writer lock, and a plain long is both cheaper and exact.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
public final class IoCounters {

	private static final int KINDS = 8;

	private final LongAdder[] recordReads = adders();
	private final LongAdder[] recordWrites = adders();

	/**
	 * Adjacency blocks a read walked past without decoding, and blocks it decoded.
	 * <p>
	 * These are the unit block skipping is measurable in, and {@code recordReads(ADJ)} is not. A chain is a linked
	 * list whose {@code next} pointer lives in each block's own header, so a walk has to acquire every block in the
	 * chain whatever it intends to do with it — the record read is owed before the skip decision can even be made.
	 * What a skip saves is the varint decode of up to 8,000 entries and the {@code long[]} it lands in, which is CPU
	 * and allocation rather than I/O. Counting it as an I/O saving would be a claim the format cannot support.
	 * <p>
	 * {@link LongAdder}s for the same reason the per-kind counters are: these are incremented on the read path from
	 * every reader thread, not once per commit under the writer lock.
	 */
	private final LongAdder adjacencyBlockDecodes = new LongAdder();
	private final LongAdder adjacencyBlockSkips = new LongAdder();

	private static LongAdder[] adders() {
		LongAdder[] result = new LongAdder[KINDS];
		for (int i = 0; i < KINDS; i++) {
			result[i] = new LongAdder();
		}
		return result;
	}

	private long pageApplies;
	private long bytesApplied;
	private long walBytesAppended;
	private long walRecords;
	private long walSyncs;
	private long dataForces;
	private long pagesPunched;
	private long checkpoints;
	private long checkpointNanos;
	private long recoveryNanos;
	private long recoveredPageImages;
	private long compactions;
	private long recordsRelocated;

	public void recordRead(int kindCode) {
		recordReads[kindCode].increment();
	}

	public void recordWrite(int kindCode) {
		recordWrites[kindCode].increment();
	}

	public void adjacencyBlockDecoded() {
		adjacencyBlockDecodes.increment();
	}

	public void adjacencyBlockSkipped() {
		adjacencyBlockSkips.increment();
	}

	public void pageApplied(int bytes) {
		pageApplies++;
		bytesApplied += bytes;
	}

	public void walAppended(int bytes) {
		walRecords++;
		walBytesAppended += bytes;
	}

	public void walSynced() {
		walSyncs++;
	}

	public void dataForced() {
		dataForces++;
	}

	public void pagePunched() {
		pagesPunched++;
	}

	public void checkpointed(long nanos) {
		checkpoints++;
		checkpointNanos += nanos;
	}

	public void recovered(long nanos, long pageImages) {
		recoveryNanos += nanos;
		recoveredPageImages += pageImages;
	}

	public void compacted(long relocated) {
		compactions++;
		recordsRelocated += relocated;
	}

	public long recordReads(Kind kind) {
		return recordReads[kind.code()].sum();
	}

	public long recordWrites(Kind kind) {
		return recordWrites[kind.code()].sum();
	}

	/** An immutable snapshot, which is what a caller comparing two points in time needs. */
	public IoStats snapshot() {
		return new IoStats(sums(recordReads), sums(recordWrites), pageApplies, bytesApplied, walBytesAppended,
			walRecords, walSyncs, dataForces, pagesPunched, checkpoints, checkpointNanos, recoveryNanos,
			recoveredPageImages, compactions, recordsRelocated,
			adjacencyBlockDecodes.sum(), adjacencyBlockSkips.sum());
	}

	private static long[] sums(LongAdder[] adders) {
		long[] result = new long[adders.length];
		for (int i = 0; i < adders.length; i++) {
			result[i] = adders[i].sum();
		}
		return result;
	}

	public void reset() {
		for (int i = 0; i < KINDS; i++) {
			recordReads[i].reset();
			recordWrites[i].reset();
		}
		adjacencyBlockDecodes.reset();
		adjacencyBlockSkips.reset();
		pageApplies = 0;
		bytesApplied = 0;
		walBytesAppended = 0;
		walRecords = 0;
		walSyncs = 0;
		dataForces = 0;
		pagesPunched = 0;
		checkpoints = 0;
		checkpointNanos = 0;
		recoveryNanos = 0;
		recoveredPageImages = 0;
		compactions = 0;
		recordsRelocated = 0;
	}
}
