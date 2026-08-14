package io.metaloom.graph.core.wal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import io.metaloom.graph.core.util.Crc;

/**
 * The write-ahead log record header and its payload codecs.
 *
 * <pre>
 *  0  8  lsn          byte offset of this record in the logical stream; self identifying
 *  8  8  prev_lsn     previous record of the same transaction, or 0
 * 16  8  txn_id
 * 24  4  length       total record length including this header
 * 28  2  type
 * 30  2  flags
 * 32  4  crc32c       over the header with the crc field zeroed, plus the payload
 * 36  4  reserved
 * </pre>
 *
 * A record is valid only if its CRC matches <em>and</em> its {@code lsn} equals its own file offset. That second check
 * is what makes a partially overwritten preallocated file unambiguous: stale bytes from a previous life of the file
 * carry the wrong LSN and are rejected without needing an end-of-log marker.
 *
 * @see <a href="../../../../../../../../spec/WAL.md">spec/WAL.md</a> section 2
 */
public final class WalRecord {

	public static final int HEADER_BYTES = 40;

	public static final int OFF_LSN = 0;
	public static final int OFF_PREV_LSN = 8;
	public static final int OFF_TXN_ID = 16;
	public static final int OFF_LENGTH = 24;
	public static final int OFF_TYPE = 28;
	public static final int OFF_FLAGS = 30;
	public static final int OFF_CRC = 32;

	// Record types. Numeric values are frozen: never renumber.
	public static final int TYPE_PAGE_IMAGE = 1;
	public static final int TYPE_TXN_COMMIT = 2;
	public static final int TYPE_CHECKPOINT_BEGIN = 3;
	public static final int TYPE_CHECKPOINT_END = 4;
	public static final int TYPE_SEGMENT_CREATE = 5;
	/**
	 * Filler to the next device block boundary. Carries no meaning and redo skips it.
	 * <p>
	 * Exists because {@code O_DIRECT} can only write whole aligned blocks. A commit that ends part way through a block
	 * has to round up, and the bytes it rounds over must still parse as the log is a gapless stream addressed by byte
	 * offset — so they are a record rather than a gap. See {@link WalWriter}.
	 */
	public static final int TYPE_PADDING = 6;

	private WalRecord() {
	}

	/**
	 * A page image: the changed byte range of one page of one segment. Redo applies it unconditionally, which is safe
	 * because the log only ever contains committed data.
	 *
	 * <pre>
	 * 0  1  kind
	 * 1  4  segment_number
	 * 5  4  page_index      whole-file page index
	 * 9  4  offset_in_page
	 * 13 4  length
	 * 17 N  bytes
	 * </pre>
	 */
	public static final int PAGE_IMAGE_HEADER_BYTES = 17;

	public static byte[] encode(long lsn, long prevLsn, long txnId, int type, byte[] payload) {
		int length = HEADER_BYTES + payload.length;
		ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putLong(OFF_LSN, lsn);
		buffer.putLong(OFF_PREV_LSN, prevLsn);
		buffer.putLong(OFF_TXN_ID, txnId);
		buffer.putInt(OFF_LENGTH, length);
		buffer.putShort(OFF_TYPE, (short) type);
		buffer.putShort(OFF_FLAGS, (short) 0);
		buffer.putInt(OFF_CRC, 0);
		System.arraycopy(payload, 0, buffer.array(), HEADER_BYTES, payload.length);
		buffer.putInt(OFF_CRC, Crc.crc32c(buffer.array(), 0, length));
		return buffer.array();
	}

	/** Validates a record read at {@code expectedLsn}. Returns the total length, or -1 when this is end of log. */
	public static int validate(byte[] buffer, int offset, int available, long expectedLsn) {
		if (available < HEADER_BYTES) {
			return -1;
		}
		ByteBuffer view = ByteBuffer.wrap(buffer, offset, available).order(ByteOrder.LITTLE_ENDIAN);
		long lsn = view.getLong(offset + OFF_LSN);
		int length = view.getInt(offset + OFF_LENGTH);
		if (lsn != expectedLsn || length < HEADER_BYTES || length > available) {
			return -1;
		}
		int stored = view.getInt(offset + OFF_CRC);
		view.putInt(offset + OFF_CRC, 0);
		int actual = Crc.crc32c(buffer, offset, length);
		view.putInt(offset + OFF_CRC, stored);
		return stored == actual ? length : -1;
	}

	public static long lsn(byte[] b, int off) {
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong(off + OFF_LSN);
	}

	public static long txnId(byte[] b, int off) {
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong(off + OFF_TXN_ID);
	}

	public static int type(byte[] b, int off) {
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(off + OFF_TYPE) & 0xFFFF;
	}

	public static int length(byte[] b, int off) {
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(off + OFF_LENGTH);
	}

	public static byte[] encodePageImage(int kindCode, int segmentNumber, int pageIndex, int offsetInPage,
		byte[] source, int sourceOffset, int length) {
		byte[] payload = new byte[PAGE_IMAGE_HEADER_BYTES + length];
		ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put(0, (byte) kindCode);
		buffer.putInt(1, segmentNumber);
		buffer.putInt(5, pageIndex);
		buffer.putInt(9, offsetInPage);
		buffer.putInt(13, length);
		System.arraycopy(source, sourceOffset, payload, PAGE_IMAGE_HEADER_BYTES, length);
		return payload;
	}

	/** A decoded page image. The byte array is a view into the record buffer, not a copy. */
	public record PageImage(int kindCode, int segmentNumber, int pageIndex, int offsetInPage, int length,
		byte[] buffer, int dataOffset) {
	}

	public static PageImage decodePageImage(byte[] record, int offset) {
		ByteBuffer buffer = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
		int base = offset + HEADER_BYTES;
		return new PageImage(
			buffer.get(base) & 0xFF,
			buffer.getInt(base + 1),
			buffer.getInt(base + 5),
			buffer.getInt(base + 9),
			buffer.getInt(base + 13),
			record,
			base + PAGE_IMAGE_HEADER_BYTES);
	}
}
