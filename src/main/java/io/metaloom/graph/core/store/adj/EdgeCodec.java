package io.metaloom.graph.core.store.adj;

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
		// Sorting the raw packed value orders by dir, then type, then location, because of the field order.
		Arrays.sort(work);

		int groups = 1;
		for (int i = 1; i < count; i++) {
			if (PackedEdge.groupKey(work[i]) != PackedEdge.groupKey(work[i - 1])) {
				groups++;
			}
		}

		byte[] out = new byte[16 + count * 9];
		int pos = VarInt.write(out, 0, groups);
		int i = 0;
		while (i < count) {
			int key = PackedEdge.groupKey(work[i]);
			int end = i + 1;
			while (end < count && PackedEdge.groupKey(work[end]) == key) {
				end++;
			}
			pos = VarInt.write(out, pos, key);
			pos = VarInt.write(out, pos, end - i);
			long previous = PackedEdge.location(work[i]);
			pos = VarInt.write(out, pos, previous);
			for (int j = i + 1; j < end; j++) {
				long location = PackedEdge.location(work[j]);
				pos = VarInt.write(out, pos, location - previous);
				previous = location;
			}
			i = end;
		}
		return Arrays.copyOf(out, pos);
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
			if (count < 0 || written + count > dst.length) {
				throw new IllegalArgumentException(
					"Encoded adjacency declares " + count + " entries, which does not fit the destination");
			}
			pos = VarInt.read(src, pos, limit, out);
			long location = out[0];
			dst[written++] = rebuild(key, location);
			for (int j = 1; j < count; j++) {
				pos = VarInt.read(src, pos, limit, out);
				location += out[0];
				dst[written++] = rebuild(key, location);
			}
		}
		return written;
	}

	private static long rebuild(int groupKey, long location) {
		if (location < 0 || location > PackedEdge.MAX_LOCATION) {
			throw new IllegalArgumentException("Decoded adjacency location out of range: " + location);
		}
		return ((long) groupKey << 48) | location;
	}
}
