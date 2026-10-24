package io.metaloom.graph.core.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.WalSyncMode;

/**
 * {@code durableLsn} is a promise, and the modes that do not fsync on every commit must not make it.
 *
 * <p>
 * The watermark feeds {@code StoreMetrics.walLagBytes}, which is the one number that tells an operator how much an
 * unclean stop would lose. Setting it to the end of the log on every {@code sync()}, forced or not, made that gauge
 * read zero under {@code GROUP_COMMIT} and {@code ASYNC} — exactly the modes in which it is not.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> sections 4 and 9
 */
@Tag("unit")
public class WalWriterTest {

	private static final long SEGMENT = 1L << 20;

	@TempDir
	Path dir;

	@Test
	public void testGroupCommitDoesNotCallAnUnforcedRecordDurable() throws Exception {
		// An interval long enough that neither sync() nor the background flush forces during the test.
		try (WalWriter writer = new WalWriter(dir, SEGMENT, 0, WalSyncMode.GROUP_COMMIT, 3_600_000)) {
			writer.append(0, 1, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
			writer.sync();
			assertTrue(writer.durableLsn() < writer.nextLsn(),
				"a sync that skipped the fsync must leave durableLsn behind: " + writer.durableLsn() + " of "
					+ writer.nextLsn());

			writer.forceNow();
			assertEquals(writer.nextLsn(), writer.durableLsn(), "a force makes everything durable");
		}
	}

	@Test
	public void testAsyncNeverCallsARecordDurableWithoutAForce() throws Exception {
		try (WalWriter writer = new WalWriter(dir, SEGMENT, 0, WalSyncMode.ASYNC, 0)) {
			for (int i = 0; i < 4; i++) {
				writer.append(0, i, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
				writer.sync();
			}
			assertEquals(0, writer.durableLsn(), "nothing was forced, so nothing is durable");
		}
	}

	@Test
	public void testFsyncOnCommitCallsEverySyncedRecordDurable() throws Exception {
		try (WalWriter writer = new WalWriter(dir, SEGMENT, 0, WalSyncMode.FSYNC_ON_COMMIT, 0)) {
			writer.append(0, 1, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
			writer.sync();
			assertEquals(writer.nextLsn(), writer.durableLsn());
		}
	}

	/** The page image built straight from the page is byte for byte the record the two-step encoding produced. */
	@Test
	public void testAPageImageBuiltFromThePageIsTheSameRecord() {
		java.lang.foreign.MemorySegment page = java.lang.foreign.MemorySegment.ofArray(new byte[4096]);
		for (int i = 0; i < 4096; i++) {
			page.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) (i * 13 + 5));
		}
		byte[] image = new byte[200];
		java.lang.foreign.MemorySegment.copy(page, 1000, java.lang.foreign.MemorySegment.ofArray(image), 0, 200);
		byte[] twoStep = WalRecord.encode(123_456, 99, 7, WalRecord.TYPE_PAGE_IMAGE,
			WalRecord.encodePageImage(3, 2, 17, 1000, image, 0, 200));
		byte[] direct = WalRecord.encodePageImageRecord(123_456, 99, 7, 3, 2, 17, 1000, page, 1000, 200);
		org.junit.jupiter.api.Assertions.assertArrayEquals(twoStep, direct);
		assertEquals(direct.length, WalRecord.validate(direct, 0, direct.length, 123_456));
	}

	/**
	 * The group-commit window is bounded by time, not by the arrival of the next commit.
	 *
	 * <p>
	 * The interval used to be checked only inside {@code sync()}, so the last commits before a quiet period stayed
	 * unforced until something else happened to write — for as long as the store sat idle.
	 * </p>
	 */
	@Test
	public void testGroupCommitForcesAnIdleLogWithinItsInterval() throws Exception {
		try (WalWriter writer = new WalWriter(dir, SEGMENT, 0, WalSyncMode.GROUP_COMMIT, 20)) {
			writer.append(0, 1, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
			writer.sync();
			long deadline = System.nanoTime() + 5_000_000_000L;
			while (writer.durableLsn() < writer.nextLsn() && System.nanoTime() < deadline) {
				Thread.sleep(5);
			}
			assertEquals(writer.nextLsn(), writer.durableLsn(),
				"an idle log under GROUP_COMMIT must be forced within its interval, not at the next commit");
		}
	}
}
