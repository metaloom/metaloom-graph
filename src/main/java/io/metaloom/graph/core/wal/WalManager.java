package io.metaloom.graph.core.wal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.page.PageOverlay;
import io.metaloom.graph.core.segment.SegmentFile;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.util.Layouts;

/**
 * Commit, recovery and checkpointing.
 * <p>
 * The engine is <b>no-steal, force-at-commit</b>: a writer mutates private page copies, and at commit the redo records
 * are made durable <em>before</em> the copies are applied to the mapped files. Uncommitted data is therefore
 * structurally incapable of reaching the data files, which is why recovery is redo-only and there is no undo pass at
 * all. The cost is that a transaction is bounded by memory.
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a>
 */
public final class WalManager implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(WalManager.class);

	/** Changed byte runs closer together than this are merged into one record. */
	private static final int RUN_MERGE_GAP = 64;

	private final WalWriter writer;
	private final Path storePath;
	private final long segmentBytes;
	private final Map<Kind, SegmentSet> segmentSets;

	private long lastCommitLsn;

	public WalManager(Path storePath, long segmentBytes, long startLsn, Map<Kind, SegmentSet> segmentSets,
		WalSyncMode syncMode, long syncIntervalMillis) throws IOException {
		this.storePath = storePath;
		this.segmentBytes = segmentBytes;
		this.segmentSets = segmentSets;
		this.writer = new WalWriter(storePath, segmentBytes, startLsn, syncMode, syncIntervalMillis);
	}

	/** Points the writer at the store's counters, so log volume and fsyncs are measurable. */
	public void counters(io.metaloom.graph.core.metrics.IoCounters counters) {
		writer.counters(counters);
	}

	public long durableLsn() {
		return writer.durableLsn();
	}

	public long nextLsn() {
		return writer.nextLsn();
	}

	public long lastCommitLsn() {
		return lastCommitLsn;
	}

	// ---------------------------------------------------------------- commit

	/** A logged, durable transaction that has not yet been copied into the mapped files. */
	public record CommitBatch(long commitLsn, List<Runnable> applications) {
	}

	/**
	 * Writes the transaction's redo records and makes them durable. Nothing has touched a data file when this returns,
	 * so the caller can take the reader-excluding lock for the short apply step only.
	 */
	public CommitBatch logCommit(long txnId, PageOverlay overlay) throws IOException {
		if (overlay.isEmpty()) {
			return new CommitBatch(lastCommitLsn, List.of());
		}
		long prevLsn = 0;
		List<Runnable> applications = new ArrayList<>();

		for (Map.Entry<Long, MemorySegment> entry : overlay.pages().entrySet()) {
			long key = entry.getKey();
			MemorySegment copy = entry.getValue();
			Kind kind = PageOverlay.kindOf(key);
			int segmentNumber = PageOverlay.segmentOf(key);
			int pageIndex = PageOverlay.pageOf(key);
			SegmentFile file = segmentSets.get(kind).segment(segmentNumber);
			MemorySegment mapped = file.mappedPage(pageIndex);

			for (int[] run : changedRuns(mapped, copy)) {
				int offset = run[0];
				int length = run[1];
				byte[] image = new byte[length];
				MemorySegment.copy(copy, offset, MemorySegment.ofArray(image), 0, length);
				byte[] payload = WalRecord.encodePageImage(kind.code(), segmentNumber, pageIndex, offset,
					image, 0, length);
				prevLsn = writer.append(prevLsn, txnId, WalRecord.TYPE_PAGE_IMAGE, payload);
				applications.add(() -> file.applyPage(pageIndex, copy, offset, offset, length));
			}
		}
		long commitLsn = writer.append(prevLsn, txnId, WalRecord.TYPE_TXN_COMMIT, new byte[0]);
		// The barrier: nothing below this line can be lost, and nothing above it has touched a data file.
		writer.sync();
		lastCommitLsn = commitLsn;
		return new CommitBatch(commitLsn, applications);
	}

	/** Copies a durable transaction into the mapped files. Idempotent; recovery does the same thing. */
	public void apply(CommitBatch batch) {
		for (Runnable application : batch.applications()) {
			application.run();
		}
	}

	/**
	 * The changed byte runs between the mapped page and the transaction's copy. Runs separated by a small gap are
	 * merged, because a record header costs more than a few unchanged bytes.
	 */
	static List<int[]> changedRuns(MemorySegment original, MemorySegment modified) {
		List<int[]> runs = new ArrayList<>();
		int size = (int) modified.byteSize();
		int start = -1;
		int lastDifferent = -1;
		for (int i = 0; i < size; i += 8) {
			int chunk = Math.min(8, size - i);
			boolean same;
			if (chunk == 8) {
				same = original.get(Layouts.LONG, i) == modified.get(Layouts.LONG, i);
			} else {
				same = true;
				for (int j = 0; j < chunk; j++) {
					if (original.get(Layouts.BYTE, i + j) != modified.get(Layouts.BYTE, i + j)) {
						same = false;
						break;
					}
				}
			}
			if (!same) {
				if (start < 0) {
					start = i;
				} else if (i - lastDifferent > RUN_MERGE_GAP) {
					runs.add(new int[] { start, lastDifferent + 8 - start });
					start = i;
				}
				lastDifferent = i;
			}
		}
		if (start >= 0) {
			int end = Math.min(size, lastDifferent + 8);
			runs.add(new int[] { start, end - start });
		}
		return runs;
	}

	// ---------------------------------------------------------------- recovery

	/**
	 * Replays every committed transaction from {@code fromLsn}. Returns the LSN just past the last committed record,
	 * which is where appending resumes.
	 */
	public static long recover(Path storePath, long segmentBytes, long fromLsn, Map<Kind, SegmentSet> segmentSets)
		throws IOException {
		long resumeLsn = fromLsn;
		int committedTransactions = 0;
		int appliedImages = 0;

		try (WalReader reader = new WalReader(storePath, segmentBytes, fromLsn)) {
			Map<Long, List<byte[]>> pending = new HashMap<>();
			byte[] record;
			while ((record = reader.next()) != null) {
				long txnId = WalRecord.txnId(record, 0);
				switch (WalRecord.type(record, 0)) {
					case WalRecord.TYPE_PAGE_IMAGE ->
						pending.computeIfAbsent(txnId, k -> new ArrayList<>()).add(record);
					case WalRecord.TYPE_TXN_COMMIT -> {
						List<byte[]> images = pending.remove(txnId);
						if (images != null) {
							for (byte[] image : images) {
								apply(segmentSets, WalRecord.decodePageImage(image, 0),
									WalRecord.lsn(image, 0));
								appliedImages++;
							}
						}
						committedTransactions++;
						// Everything up to and including this commit is now reflected in the data files.
						resumeLsn = reader.lsn();
					}
					case WalRecord.TYPE_CHECKPOINT_BEGIN, WalRecord.TYPE_CHECKPOINT_END,
						WalRecord.TYPE_SEGMENT_CREATE -> resumeLsn = reader.lsn();
					default -> throw new IOException("Unknown WAL record type " + WalRecord.type(record, 0)
						+ " at LSN " + WalRecord.lsn(record, 0));
				}
			}
			if (!pending.isEmpty()) {
				logger.info("Discarded {} uncommitted transaction(s) found at the tail of the log", pending.size());
			}
		}
		if (committedTransactions > 0) {
			logger.info("Recovered {} committed transaction(s), {} page image(s), resuming at LSN {}",
				committedTransactions, appliedImages, resumeLsn);
		}
		return resumeLsn;
	}

	private static void apply(Map<Kind, SegmentSet> segmentSets, WalRecord.PageImage image, long lsn) {
		Kind kind = Kind.byCode(image.kindCode());
		SegmentSet set = segmentSets.get(kind);
		if (set == null || image.segmentNumber() >= set.segmentCount()) {
			throw new IllegalStateException("Recovery references " + kind + " segment " + image.segmentNumber()
				+ ", which does not exist. The store is incomplete.");
		}
		SegmentFile file = set.segment(image.segmentNumber());
		MemorySegment source = MemorySegment.ofArray(image.buffer())
			.asSlice(image.dataOffset(), image.length());
		// Redo is unconditional and idempotent: the log only ever holds committed data.
		file.applyPage(image.pageIndex(), source, 0, image.offsetInPage(), image.length());
	}

	// ---------------------------------------------------------------- checkpoint

	/**
	 * Flushes every data file, then records the checkpoint and trims the log. After a clean checkpoint a reopen
	 * replays nothing.
	 */
	public long checkpoint() throws IOException {
		long begin = writer.append(0, 0, WalRecord.TYPE_CHECKPOINT_BEGIN, new byte[0]);
		for (SegmentSet set : segmentSets.values()) {
			set.force();
		}
		long end = writer.append(begin, 0, WalRecord.TYPE_CHECKPOINT_END, new byte[0]);
		writer.forceNow();
		return end + WalRecord.HEADER_BYTES;
	}

	public void trim(long minLsn) throws IOException {
		writer.trim(minLsn);
	}

	public Path storePath() {
		return storePath;
	}

	public long segmentBytes() {
		return segmentBytes;
	}

	@Override
	public void close() throws IOException {
		writer.close();
	}
}
