package io.metaloom.graph.core.store.blob;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.nativelib.NativeCodec;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.util.Crc;
import io.metaloom.graph.core.util.Layouts;
import io.metaloom.graph.core.util.Lz4;

/**
 * Page sized extents for anything that does not belong inline: long strings, binary payloads, embedding vectors.
 *
 * <pre>
 * Extent header (32 bytes, present on every extent of a chain):
 *  0  8  total_length      meaningful on the first extent
 *  8  8  next_extent_id
 * 16  4  crc32c            of the whole blob, on the first extent
 * 20  2  gen
 * 22  1  flags
 * 23  1  compression       0 = none
 * 24  8  refcount
 * </pre>
 *
 * @see <a href="../../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 6
 */
public final class BlobStore {

	public static final int HEADER_BYTES = 32;

	private static final int OFF_TOTAL_LENGTH = 0;
	private static final int OFF_NEXT = 8;
	private static final int OFF_CRC = 16;
	private static final int OFF_COMPRESSION = 23;
	private static final int OFF_REFCOUNT = 24;

	/** Stored whole. */
	public static final int COMPRESSION_NONE = 0;
	/** lz4 block format, with the uncompressed length in the first eight bytes of the payload. */
	public static final int COMPRESSION_LZ4 = 1;

	/**
	 * A blob is only stored compressed if it saves at least this much, because decompression is not free and a blob
	 * that barely shrinks costs more to read than it saves to hold.
	 */
	private static final double WORTH_COMPRESSING = 0.9;

	/** Bytes prefixed to a compressed payload to record what it decompresses to. */
	private static final int ORIGINAL_LENGTH_BYTES = 8;

	private final SegmentSet segments;
	private final int payloadPerExtent;

	public BlobStore(SegmentSet segments) {
		if (segments.kind() != Kind.BLOB) {
			throw new IllegalArgumentException("BlobStore requires a BLOB segment set");
		}
		this.segments = segments;
		// A blob extent is one page by definition of the BLOB kind.
		this.payloadPerExtent = segments.stride() - HEADER_BYTES;
	}

	/** Bytes of payload that fit into a single extent. */
	public int payloadPerExtent() {
		return payloadPerExtent;
	}

	/**
	 * Stores a blob, compressing it when that is available and worthwhile.
	 *
	 * <p>
	 * The {@code compression} byte has been in the extent header since the format was written and has always been
	 * zero, so switching it on is not a format change and no version bump is needed: an old store's blobs simply say
	 * "none". The reverse direction is what needed care — a blob written compressed must be readable by a reader with
	 * no native library at all, which is why {@link Lz4} decompresses in pure Java.
	 * </p>
	 *
	 * <p>
	 * Compressed payloads carry their uncompressed length in their first eight bytes. There is no spare header field
	 * for it, and {@code total_length} has to keep meaning "bytes stored in this chain" or the chain walk would not
	 * know when to stop.
	 * </p>
	 */
	public long store(byte[] data) throws IOException {
		byte[] payload = data;
		int compression = COMPRESSION_NONE;
		byte[] compressed = NativeCodec.compress(data);
		if (compressed != null && compressed.length + ORIGINAL_LENGTH_BYTES < data.length * WORTH_COMPRESSING) {
			payload = new byte[compressed.length + ORIGINAL_LENGTH_BYTES];
			MemorySegment.ofArray(payload).set(Layouts.LONG, 0, data.length);
			System.arraycopy(compressed, 0, payload, ORIGINAL_LENGTH_BYTES, compressed.length);
			compression = COMPRESSION_LZ4;
		}
		return store(data, payload, compression);
	}

	private long store(byte[] original, byte[] data, int compression) throws IOException {
		int extents = Math.max(1, (data.length + payloadPerExtent - 1) / payloadPerExtent);
		long firstId = ElementId.NULL;
		long previousId = ElementId.NULL;
		int written = 0;
		for (int i = 0; i < extents; i++) {
			long id = segments.allocate();
			if (firstId == ElementId.NULL) {
				firstId = id;
			} else {
				MemorySegment previous = segments.mutable(previousId);
				previous.set(Layouts.LONG, OFF_NEXT, id);
				segments.touched(previousId);
			}
			MemorySegment extent = segments.mutable(id);
			int chunk = Math.min(payloadPerExtent, data.length - written);
			MemorySegment.copy(MemorySegment.ofArray(data), written, extent, HEADER_BYTES, chunk);
			extent.set(Layouts.LONG, OFF_NEXT, ElementId.NULL);
			written += chunk;
			previousId = id;
		}
		MemorySegment head = segments.mutable(firstId);
		head.set(Layouts.LONG, OFF_TOTAL_LENGTH, data.length);
		// The checksum covers what the caller gave us, not what was stored, so it validates the bytes that come back
		// out of decompression rather than the bytes that went into it.
		head.set(Layouts.INT, OFF_CRC, Crc.crc32c(original));
		head.set(Layouts.BYTE, OFF_COMPRESSION, (byte) compression);
		head.set(Layouts.LONG, OFF_REFCOUNT, 1L);
		segments.touched(firstId);
		return firstId;
	}

	public byte[] read(long blobId) {
		MemorySegment head = segments.resolve(blobId);
		long totalLength = head.get(Layouts.LONG, OFF_TOTAL_LENGTH);
		// The length is read out of a page and is therefore untrusted. Bounding it by Integer.MAX_VALUE is not
		// enough: a flipped byte would then have the reader allocate two gigabytes before discovering the chain is
		// four pages long, which is a way to be told how much heap the process has. The real bound is how much the
		// store's blob extents could possibly hold — counted by high water mark, not by live count: a snapshot view
		// still reads extents a later commit freed, and the high water mark is what never drops under an open view.
		long ceiling = (long) payloadPerExtent * Math.max(1, segments.highWaterTotal());
		if (totalLength < 0 || totalLength > Math.min(Integer.MAX_VALUE, ceiling)) {
			throw new PageCorruptException("Blob " + ElementId.toString(blobId) + " reports length " + totalLength
				+ ", which is beyond the " + ceiling + " bytes its store could hold");
		}
		byte[] result = new byte[(int) totalLength];
		int read = 0;
		long id = blobId;
		MemorySegment extent = head;
		java.util.Set<Long> visited = new java.util.HashSet<>();
		visited.add(blobId);
		while (read < result.length) {
			int chunk = Math.min(payloadPerExtent, result.length - read);
			MemorySegment.copy(extent, HEADER_BYTES, MemorySegment.ofArray(result), read, chunk);
			read += chunk;
			if (read < result.length) {
				id = extent.get(Layouts.LONG, OFF_NEXT);
				if (ElementId.isNull(id)) {
					throw new PageCorruptException("Blob " + ElementId.toString(blobId) + " chain ends early after "
						+ read + " of " + result.length + " bytes");
				}
				if (!visited.add(id)) {
					throw new PageCorruptException("Blob " + ElementId.toString(blobId)
						+ " has a cyclic extent chain at " + ElementId.toString(id));
				}
				extent = segments.resolve(id);
			}
		}
		byte[] payload = decompress(blobId, result, head.get(Layouts.BYTE, OFF_COMPRESSION));

		int expected = head.get(Layouts.INT, OFF_CRC);
		int actual = Crc.crc32c(payload);
		if (expected != actual) {
			throw new PageCorruptException("Blob " + ElementId.toString(blobId) + " failed its checksum");
		}
		return payload;
	}

	/**
	 * Expands a stored payload according to its compression byte.
	 * <p>
	 * Everything read here comes off a page and is untrusted, including the compression byte itself and the length
	 * prefix: an unknown codec, an implausible length or a malformed stream is corruption, not a reason to guess.
	 */
	private byte[] decompress(long blobId, byte[] stored, int compression) {
		switch (compression) {
			case COMPRESSION_NONE:
				return stored;
			case COMPRESSION_LZ4: {
				if (stored.length < ORIGINAL_LENGTH_BYTES) {
					throw new PageCorruptException("Blob " + ElementId.toString(blobId)
						+ " claims lz4 compression but holds only " + stored.length + " bytes");
				}
				long originalLength = MemorySegment.ofArray(stored).get(Layouts.LONG, 0);
				long ceiling = (long) payloadPerExtent * Math.max(1, segments.highWaterTotal()) * 255L;
				if (originalLength < 0 || originalLength > Math.min(Integer.MAX_VALUE, ceiling)) {
					throw new PageCorruptException("Blob " + ElementId.toString(blobId)
						+ " declares an uncompressed length of " + originalLength + ", which is not plausible");
				}
				byte[] expanded = new byte[(int) originalLength];
				try {
					Lz4.decompress(stored, ORIGINAL_LENGTH_BYTES, stored.length - ORIGINAL_LENGTH_BYTES, expanded);
				} catch (IllegalArgumentException e) {
					throw new PageCorruptException("Blob " + ElementId.toString(blobId)
						+ " has a malformed lz4 payload: " + e.getMessage());
				}
				return expanded;
			}
			default:
				throw new PageCorruptException("Blob " + ElementId.toString(blobId)
					+ " declares compression codec " + compression + ", which this build does not know");
		}
	}

	/** The next extent of a chain, or {@link ElementId#NULL}. */
	public long nextExtent(long extentId) {
		return segments.resolve(extentId).get(Layouts.LONG, OFF_NEXT);
	}

	/** Repoints the next extent. Compaction is the only caller. */
	public void nextExtent(long extentId, long nextId) {
		segments.mutable(extentId).set(Layouts.LONG, OFF_NEXT, nextId);
		segments.touched(extentId);
	}

	public SegmentSet segments() {
		return segments;
	}

	public void free(long blobId) {
		long id = blobId;
		// Freeing follows the same untrusted pointers the read path does; a ring here would free slots forever.
		java.util.Set<Long> visited = new java.util.HashSet<>();
		while (!ElementId.isNull(id) && visited.add(id)) {
			MemorySegment extent = segments.resolve(id);
			long next = extent.get(Layouts.LONG, OFF_NEXT);
			segments.free(id);
			id = next;
		}
	}
}
