package io.metaloom.graph.core.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.WalSyncMode;

/**
 * The forward scan, against record sizes either side of its read window.
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 6.1
 */
@Tag("unit")
public class WalReaderTest {

	private static final long SEGMENT = 1L << 20;

	@TempDir
	Path dir;

	/**
	 * A record longer than the read buffer is a record, not the end of the log.
	 *
	 * <p>
	 * A whole-page image on a 64 KiB page store is 65,536 bytes of data plus two headers, which is more than the
	 * 64 KiB the reader used to read at a time. Such a record failed the length check and the scan stopped there,
	 * reporting a clean end of log with the commit and everything after it unreplayed.
	 * </p>
	 */
	@Test
	public void testARecordLargerThanTheReadWindowIsRead() throws Exception {
		int[] sizes = { 10, 70_000, 64, 200_000, 3 };
		try (WalWriter writer = new WalWriter(dir, SEGMENT, 0, WalSyncMode.FSYNC_ON_COMMIT, 0)) {
			for (int i = 0; i < sizes.length; i++) {
				writer.append(0, i, WalRecord.TYPE_PAGE_IMAGE, new byte[sizes[i]]);
			}
			writer.sync();
		}
		assertEquals(sizes.length, scan().size(), "every record must be read, whatever its size");
	}

	/** Many small records, crossing several segments: every one comes back, in order, at its own LSN. */
	@Test
	public void testSmallRecordsAcrossSegmentsAreReadInOrder() throws Exception {
		long segment = 64 * 1024;
		int count = 5_000;
		try (WalWriter writer = new WalWriter(dir, segment, 0, WalSyncMode.ASYNC, 0)) {
			for (int i = 0; i < count; i++) {
				writer.append(0, i, WalRecord.TYPE_TXN_COMMIT, new byte[i % 300]);
			}
			writer.forceNow();
		}
		List<byte[]> records = new ArrayList<>();
		try (WalReader reader = new WalReader(dir, segment, 0)) {
			byte[] record;
			while ((record = reader.next()) != null) {
				records.add(record);
			}
		}
		assertEquals(count, records.size());
		for (int i = 0; i < count; i++) {
			assertEquals(i, WalRecord.txnId(records.get(i), 0));
			assertEquals(WalRecord.HEADER_BYTES + i % 300, WalRecord.length(records.get(i), 0));
		}
	}

	private List<byte[]> scan() throws Exception {
		List<byte[]> records = new ArrayList<>();
		try (WalReader reader = new WalReader(dir, SEGMENT, 0)) {
			byte[] record;
			while ((record = reader.next()) != null) {
				records.add(record);
			}
		}
		return records;
	}
}
