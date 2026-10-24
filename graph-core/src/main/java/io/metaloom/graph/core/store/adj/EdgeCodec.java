package io.metaloom.graph.core.store.adj;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import io.metaloom.graph.core.util.VarInt;

/**
 * Delta and varint compression for a sorted run of packed edges.
 * <p>
 * Entries are grouped by their {@code (dir, type)} key so that those 16 bits are written once per group rather than
 * once per entry. Within a group the 48 bit locations are sorted and delta encoded, and the deltas are usually small
 * because neighbours created near each other in time land in nearby slots. The result is commonly one to two bytes
 * per edge against the eight bytes of the raw form and the thirty-two of the proof of concept's linked list.
 * <p>
 * This is the pure-Java reference implementation. The SIMD version in the Rust layer (Phase 6) is differentially
 * tested against it and must produce byte-identical output.
 *
 * <pre>
 * varint groupCount
 * per group:
 *   varint groupKey        (dir &lt;&lt; 14 | type)
 *   varint count
 *   varint firstLocation
 *   varint delta ...       (count - 1 of them)
 * </pre>
 *
 * @see <a href="../../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> section 5.2
 */
public final class EdgeCodec {

	private EdgeCodec() {
	}

	/**
	 * Encodes the given edges. The input array is sorted in place: entries are ordered by group key and then by
	 * location, which is exactly the order a traversal wants to read them in.
	 */
	public static byte[] encode(long[] edges, int count) {
		if (count == 0) {
			return new byte[] { 0 };
		}
		long[] work = Arrays.copyOf(edges, count);
		sort(work, count);
		return encodeSorted(work, count);
	}

	/**
	 * Encodes an already ordered run.
	 *
	 * <p>
	 * The output buffer is sized at the worst case of nine bytes per entry and trimmed at the end, which allocates
	 * about five times what the result needs. That was measured against the alternative — a first pass summing
	 * {@link VarInt#sizeOf} so the buffer could be exact, and a write straight into the mapped page — and the
	 * allocating version is <b>the faster one, by a factor of two</b> on the block sizes this runs at. Two passes of
	 * arithmetic cost more than one short-lived array from the nursery, and a byte-at-a-time write into a
	 * {@code MemorySegment} is bounds-checked where a write into a {@code byte[]} is not.
	 * </p>
	 */
	private static byte[] encodeSorted(long[] sorted, int count) {
		int groups = 1;
		for (int i = 1; i < count; i++) {
			if (PackedEdge.groupKey(sorted[i]) != PackedEdge.groupKey(sorted[i - 1])) {
				groups++;
			}
		}

		byte[] out = new byte[16 + count * 9];
		int pos = VarInt.write(out, 0, groups);
		int i = 0;
		while (i < count) {
			int key = PackedEdge.groupKey(sorted[i]);
			int end = i + 1;
			while (end < count && PackedEdge.groupKey(sorted[end]) == key) {
				end++;
			}
			pos = VarInt.write(out, pos, key);
			pos = VarInt.write(out, pos, end - i);
			long previous = PackedEdge.location(sorted[i]);
			pos = VarInt.write(out, pos, previous);
			for (int j = i + 1; j < end; j++) {
				long location = PackedEdge.location(sorted[j]);
				pos = VarInt.write(out, pos, location - previous);
				previous = location;
			}
			i = end;
		}
		return Arrays.copyOf(out, pos);
	}

	/** Encodes a run that is already ordered and already free of tombstones. Used by {@link AdjacencyBlock#rewrite}. */
	static byte[] encodeOrdered(long[] sorted, int count) {
		return count == 0 ? new byte[] { 0 } : encodeSorted(sorted, count);
	}

	/**
	 * Orders a run of packed edges, exploiting the shape the caller actually has.
	 *
	 * <p>
	 * A block recompresses by decoding its sorted region — which comes back sorted — and appending the raw tail, which
	 * is short and unordered. So the input here is nearly always one long sorted run followed by a handful of stragglers.
	 * Sorting the whole thing costs 3.5 ns per entry; sorting the stragglers and merging costs about 0.5. The JDK's
	 * sort does detect runs, but it still moves every element.
	 * </p>
	 *
	 * <p>
	 * The merge buffer is the size of the <em>tail</em>, not of the block, which is what keeps this from trading one
	 * allocation for another.
	 * </p>
	 */
	static void sort(long[] work, int count) {
		if (count <= 1) {
			// Nothing to order, and the scan below would start past the end of a zero-length run — which is what a
			// block holds after its last live entry is tombstoned.
			return;
		}
		int sorted = 1;
		while (sorted < count && work[sorted] >= work[sorted - 1]) {
			sorted++;
		}
		if (sorted == count) {
			return;
		}
		Arrays.sort(work, sorted, count);
		if (work[sorted - 1] <= work[sorted]) {
			return;
		}
		long[] tail = Arrays.copyOfRange(work, sorted, count);
		int left = sorted - 1;
		int right = tail.length - 1;
		int target = count - 1;
		// Backwards, so the prefix is only touched where the merge actually displaces it.
		while (right >= 0) {
			work[target--] = left >= 0 && work[left] > tail[right] ? work[left--] : tail[right--];
		}
	}

	/** Number of edges an encoded block holds, without materialising them. */
	public static int countOf(byte[] src, int offset, int limit) {
		long[] out = new long[1];
		int pos = VarInt.read(src, offset, limit, out);
		int groups = (int) out[0];
		int total = 0;
		for (int g = 0; g < groups; g++) {
			pos = VarInt.read(src, pos, limit, out); // group key
			pos = VarInt.read(src, pos, limit, out);
			int count = (int) out[0];
			if (count < 0) {
				throw new IllegalArgumentException("Negative group count in encoded adjacency");
			}
			total += count;
			pos = VarInt.read(src, pos, limit, out); // first location
			for (int j = 1; j < count; j++) {
				pos = VarInt.read(src, pos, limit, out);
			}
		}
		return total;
	}

	/**
	 * Decodes into {@code dst} and returns the number of entries written. Never reads past {@code limit}, so a corrupt
	 * block raises an exception rather than an out-of-bounds access.
	 */
	public static int decode(byte[] src, int offset, int limit, long[] dst) {
		long[] out = new long[1];
		int pos = VarInt.read(src, offset, limit, out);
		int groups = (int) out[0];
		int written = 0;
		for (int g = 0; g < groups; g++) {
			pos = VarInt.read(src, pos, limit, out);
			int key = (int) out[0];
			pos = VarInt.read(src, pos, limit, out);
			int count = (int) out[0];
			if (count <= 0 || written + count > dst.length) {
				// Zero is rejected along with the negatives. The encoder never writes an empty group, and the first
				// entry below is emitted before the loop that counts — so a zero count still produced one entry, and
				// on a full destination that was an ArrayIndexOutOfBoundsException rather than a typed failure. Found
				// by fuzzing the Rust decoder, which had it too.
				throw new IllegalArgumentException(
					"Encoded adjacency declares " + count + " entries, which does not fit the destination");
			}
			pos = VarInt.read(src, pos, limit, out);
			long location = out[0];
			dst[written++] = rebuild(key, location);
			for (int j = 1; j < count; j++) {
				pos = VarInt.read(src, pos, limit, out);
				location = advance(location, out[0]);
				dst[written++] = rebuild(key, location);
			}
		}
		return written;
	}

	/**
	 * Per-thread copy target for {@link #decode(MemorySegment, long, long, long[])}. Grown on demand and never shrunk;
	 * a block is at most one page, so this stays at a page per thread that has ever read one.
	 */
	private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[0]);

	/**
	 * Decodes out of a mapped page.
	 *
	 * <p>
	 * This is the path a traversal takes. The region is bulk-copied into a per-thread scratch array and decoded from
	 * there. Reading the varints a byte at a time out of the segment pays a bounds and liveness check per byte, and
	 * {@code CodecProfileBenchmark#profileSegmentAgainstArrayDecode} measured that at <b>5.6x</b> the copy-and-decode
	 * on blocks of 400 to 8,000 entries. The scratch array is reused, so a block read still allocates nothing.
	 * </p>
	 */
	public static int decode(MemorySegment src, long offset, long limit, long[] dst) {
		long length = limit - offset;
		if (length < 0 || length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Encoded adjacency region of " + length + " bytes");
		}
		int size = (int) length;
		byte[] scratch = SCRATCH.get();
		if (scratch.length < size) {
			scratch = new byte[size];
			SCRATCH.set(scratch);
		}
		MemorySegment.copy(src, ValueLayout.JAVA_BYTE, offset, scratch, 0, size);
		return decode(scratch, 0, size, dst);
	}

	private static long rebuild(int groupKey, long location) {
		if (location < 0 || location > PackedEdge.MAX_LOCATION) {
			throw new IllegalArgumentException("Decoded adjacency location out of range: " + location);
		}
		return ((long) groupKey << 48) | location;
	}

	/**
	 * Adds a delta to a running location, refusing anything that could not have been encoded.
	 *
	 * <p>
	 * Found by fuzzing the Rust decoder, which has the same loop and had the same defect. This was
	 * {@code location += delta}. A delta is read straight off a page, so a corrupt block can carry any 64 bit value:
	 * one with the high bit set decodes to a <em>negative</em> long and moves the location backwards, landing on a
	 * perfectly valid location that the range check then accepts. The decoder emits a plausible wrong neighbour id
	 * and nothing reports a problem.
	 * </p>
	 *
	 * <p>
	 * The differential test could not have caught it: Rust wrapped the same way on the same input, so the two
	 * implementations agreed — on the wrong answer. Comparing two implementations only finds the mistakes they do not
	 * share.
	 * </p>
	 */
	private static long advance(long location, long delta) {
		if (delta < 0) {
			throw new IllegalArgumentException("Adjacency delta " + delta + " is not a valid unsigned varint");
		}
		long next = location + delta;
		if (next < location) {
			throw new IllegalArgumentException("Adjacency locations overflowed at delta " + delta);
		}
		return next;
	}
}
