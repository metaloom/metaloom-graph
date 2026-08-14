package io.metaloom.graph.core.store.blob;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.util.Crc;
import io.metaloom.graph.core.util.Layouts;

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
	private static final int OFF_REFCOUNT = 24;

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

	public long store(byte[] data) throws IOException {
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
		head.set(Layouts.INT, OFF_CRC, Crc.crc32c(data));
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
		// store's live blob extents could possibly hold.
		long ceiling = (long) payloadPerExtent * Math.max(1, segments.liveCount());
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
		int expected = head.get(Layouts.INT, OFF_CRC);
		int actual = Crc.crc32c(result);
		if (expected != actual) {
			throw new PageCorruptException("Blob " + ElementId.toString(blobId) + " failed its checksum");
		}
		return result;
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
