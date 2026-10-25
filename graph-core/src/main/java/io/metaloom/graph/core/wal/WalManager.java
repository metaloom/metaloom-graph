package io.metaloom.graph.core.wal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.page.PageOverlay;
import io.metaloom.graph.core.segment.SegmentFile;
import io.metaloom.graph.core.segment.SegmentSet;

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
		this(storePath, segmentBytes, startLsn, segmentSets, syncMode, syncIntervalMillis,
			io.metaloom.graph.core.api.GraphStoreOptions.IoEngineKind.MMAP);
	}

	public WalManager(Path storePath, long segmentBytes, long startLsn, Map<Kind, SegmentSet> segmentSets,
		WalSyncMode syncMode, long syncIntervalMillis,
		io.metaloom.graph.core.api.GraphStoreOptions.IoEngineKind engine) throws IOException {
		this.storePath = storePath;
		this.segmentBytes = segmentBytes;
		this.segmentSets = segmentSets;
		// Recovery has already run, so everything up to here is in the data files. Leaving this at zero would make a
		// reopened store claim its last commit was at LSN 0 — harmless to visibility, because every later commit is
		// still greater, but wrong in StoreStats and in any SnapshotTooOldException raised before the first commit of
		// the session.
		this.lastCommitLsn = startLsn;
		this.writer = new WalWriter(storePath, segmentBytes, startLsn, syncMode, syncIntervalMillis, engine);
	}

	/** Whether the log is bypassing the page cache. */
	public boolean isDirect() {
		return writer.isDirect();
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

	/**
	 * Stops the log accepting anything more, for a commit that failed after its records were logged.
	 *
	 * @see WalWriter#poison(Throwable)
	 */
	public void poison(Throwable cause) {
		writer.poison(cause);
	}

	/** Whether the log has failed and is refusing writes until the store is reopened. */
	public boolean isPoisoned() {
		return writer.isPoisoned();
	}

	// ---------------------------------------------------------------- commit

	/**
	 * One byte run of one page that a durable commit is about to copy into the mapping.
	 *
	 * <p>
	 * Named rather than closed over as a {@code Runnable} so that the commit path can look at what is about to change
	 * before it changes — which is what {@link io.metaloom.graph.core.txn.PageVersions} needs to keep the previous
	 * content for readers that began earlier.
	 * </p>
	 */
	public record PageWrite(SegmentFile file, Kind kind, int segmentNumber, int pageIndex, MemorySegment source,
		int offset, int length) {

		/** The page as it currently stands in the mapping, before this write lands. */
		public MemorySegment mappedPage() {
			return file.mappedPage(pageIndex);
		}

		void apply() {
			file.applyPage(pageIndex, source, offset, offset, length);
		}
	}

	/** A logged, durable transaction that has not yet been copied into the mapped files. */
	public record CommitBatch(long commitLsn, List<PageWrite> writes) {
	}

	/**
	 * One overlay page and the runs within it this transaction changed, worked out once by the commit.
	 *
	 * <p>
	 * The runs come from {@link PageOverlay.Page#base()} rather than from the mapping, which is what makes two writers
	 * on one page compose instead of overwriting each other, and they are computed <em>before</em> the log is written
	 * because conflict detection needs the same list. Passing it in rather than recomputing it here saves diffing every
	 * touched page twice.
	 * </p>
	 */
	public record PageDiff(long key, PageOverlay.Page page, List<int[]> runs) {
	}

	/**
	 * Writes the transaction's redo records and makes them durable. Nothing has touched a data file when this returns,
	 * so the caller can take the reader-excluding lock for the short apply step only.
	 */
	public CommitBatch logCommit(long txnId, List<PageDiff> diffs) throws IOException {
		long prevLsn = 0;
		List<PageWrite> writes = new ArrayList<>();

		for (PageDiff diff : diffs) {
			long key = diff.key();
			MemorySegment copy = diff.page().copy();
			Kind kind = PageOverlay.kindOf(key);
			int segmentNumber = PageOverlay.segmentOf(key);
			int pageIndex = PageOverlay.pageOf(key);
			SegmentFile file = segmentSets.get(kind).segment(segmentNumber);

			for (int[] run : diff.runs()) {
				int offset = run[0];
				int length = run[1];
				// Straight from the overlay copy into one record array, rather than through an image and a payload.
				prevLsn = writer.appendPageImage(prevLsn, txnId, kind.code(), segmentNumber, pageIndex, offset, copy,
					offset, length);
				writes.add(new PageWrite(file, kind, segmentNumber, pageIndex, copy, offset, length));
			}
		}
		if (writes.isEmpty()) {
			// The transaction left every page it touched exactly as it found it. Logging a commit record for that
			// would burn an LSN and an fsync to say nothing.
			return new CommitBatch(lastCommitLsn, List.of());
		}
		long commitLsn = writer.append(prevLsn, txnId, WalRecord.TYPE_TXN_COMMIT, new byte[0]);
		// The barrier: nothing below this line can be lost, and nothing above it has touched a data file.
		writer.sync();
		lastCommitLsn = commitLsn;
		return new CommitBatch(commitLsn, writes);
	}

	/** Copies a durable transaction into the mapped files. Idempotent; recovery does the same thing. */
	public void apply(CommitBatch batch) {
		for (PageWrite write : batch.writes()) {
			write.apply();
		}
	}

	/**
	 * The changed byte runs between the image a transaction started from and its copy. Runs separated by a small gap
	 * are merged, because a record header costs more than a few unchanged bytes.
	 */
	public static List<int[]> changedRuns(MemorySegment original, MemorySegment modified) {
		return changedRuns(original, modified, 0, (int) modified.byteSize());
	}

	/**
	 * The changed byte runs within {@code [from, to)}, for a caller that knows nothing outside that window can differ —
	 * the overlay records which bytes it handed out for writing. The window is widened to whole 8-byte chunks, so the
	 * result is exactly what the whole-page diff returns whenever that knowledge is right.
	 */
	public static List<int[]> changedRuns(MemorySegment original, MemorySegment modified, int from, int to) {
		List<int[]> runs = new ArrayList<>();
		int size = (int) Math.min(modified.byteSize(), ((long) to + 7) & ~7L);
		int start = -1;
		int lastDifferent = -1;
		// The unit is still the 8-byte chunk, so the runs are exactly what a chunk-by-chunk loop produces. mismatch()
		// only finds the next differing chunk faster: it is vectorised, and a commit usually changes a few dozen bytes
		// of a page, so almost all of the page is skipped in one call rather than walked a long at a time.
		int i = Math.max(0, from) & ~7;
		while (i < size) {
			long found = MemorySegment.mismatch(original, i, size, modified, i, size);
			if (found < 0) {
				break;
			}
			int chunk = (int) (i + found) & ~7;
			if (start < 0) {
				start = chunk;
			} else if (chunk - lastDifferent > RUN_MERGE_GAP) {
				runs.add(new int[] { start, lastDifferent + 8 - start });
				start = chunk;
			}
			lastDifferent = chunk;
			i = chunk + 8;
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
		requireContiguousLog(storePath, segmentBytes, fromLsn);
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
					// Alignment filler from a direct-IO commit. It carries nothing, but it must not advance the resume
					// point either: a padding record can sit between a page image and the commit that makes it durable.
					case WalRecord.TYPE_PADDING -> {
					}
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

	/**
	 * Refuses a log with a segment missing between the checkpoint and the newest segment on disk.
	 *
	 * <p>
	 * Segment files are created on the first append into them and deleted only below a durable checkpoint, so the
	 * ones from the checkpoint onwards are always contiguous. A hole means the log lost a file — or the manifest is
	 * older than the trim that removed it — and the reader would stop at the hole and report a clean end of log.
	 * Opening on that is recovering to a wrong point, and resuming the writer there leaves the segments past the hole
	 * holding stale records that self-identify as valid. Fatal instead, as spec/WAL.md section 6.1 requires.
	 * </p>
	 */
	private static void requireContiguousLog(Path storePath, long segmentBytes, long fromLsn) throws IOException {
		Path dir = storePath.resolve("wal");
		if (!Files.isDirectory(dir)) {
			return;
		}
		long startBase = fromLsn / segmentBytes * segmentBytes;
		List<Long> bases = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String name = p.getFileName().toString();
				if (!name.startsWith("wal-") || !name.endsWith(".log")) {
					continue;
				}
				try {
					long base = Long.parseUnsignedLong(name.substring(4, name.indexOf('.')), 16);
					if (base >= startBase) {
						bases.add(base);
					}
				} catch (NumberFormatException e) {
					// Not one of ours; leave it alone.
				}
			}
		}
		Collections.sort(bases);
		long expected = startBase;
		for (long base : bases) {
			if (base != expected) {
				throw new PageCorruptException("WAL segment " + dir.resolve(String.format("wal-%016x.log", expected))
					+ " is missing: recovery starts at checkpoint LSN " + fromLsn + " and the log continues in "
					+ String.format("wal-%016x.log", base) + ". Refusing to open rather than recover to a wrong point;"
					+ " restore the missing segment, or the store from a backup.");
			}
			expected += segmentBytes;
		}
	}

	private static void apply(Map<Kind, SegmentSet> segmentSets, WalRecord.PageImage image, long lsn) {
		Kind kind = Kind.byCode(image.kindCode());
		SegmentSet set = segmentSets.get(kind);
		if (set == null || image.segmentNumber() >= set.segmentCount()) {
			// A typed failure, not an IllegalStateException. This is reached by opening a store whose data files are
			// older than its log — a restored-from-backup segment directory, a partial copy, a filesystem that lost a
			// file a crash should have kept — and a caller has to be able to catch it as a store problem rather than
			// as a programming error. Found by the crash matrix, which asserts that a damaged store never fails in a
			// vocabulary the engine does not own.
			throw new PageCorruptException("Recovery references " + kind + " segment " + image.segmentNumber()
				+ " at LSN " + lsn + ", which does not exist. The store is incomplete: its data files are older than"
				+ " its log.");
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
