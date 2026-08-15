package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.store.adj.EdgeCodec;
import io.metaloom.graph.core.store.adj.PackedEdge;
import io.metaloom.graph.core.util.VarInt;

/**
 * @see <a href="../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> section 9
 */
@Tag("unit")
public class EdgeCodecTest {

	@Test
	public void testEmpty() {
		byte[] encoded = EdgeCodec.encode(new long[0], 0);
		assertEquals(0, EdgeCodec.decode(encoded, 0, encoded.length, new long[0]));
	}

	@Test
	public void testRoundTripPreservesTheEdgeSet() {
		Random random = new Random(4242L);
		for (int trial = 0; trial < 200; trial++) {
			int count = 1 + random.nextInt(500);
			long[] edges = new long[count];
			for (int i = 0; i < count; i++) {
				edges[i] = PackedEdge.pack(random.nextInt(3), random.nextInt(8),
					1 + random.nextInt(1 << 20));
			}
			byte[] encoded = EdgeCodec.encode(edges, count);
			long[] decoded = new long[count];
			int written = EdgeCodec.decode(encoded, 0, encoded.length, decoded);
			assertEquals(count, written, "seed 4242 trial " + trial);
			assertEquals(count, EdgeCodec.countOf(encoded, 0, encoded.length));

			long[] expected = Arrays.copyOf(edges, count);
			Arrays.sort(expected);
			Arrays.sort(decoded);
			assertArrayEquals(expected, decoded, "seed 4242 trial " + trial);
		}
	}

	@Test
	public void testExtremeValues() {
		long[] edges = {
			PackedEdge.pack(0, 0, 1),
			PackedEdge.pack(3, 0, PackedEdge.MAX_LOCATION),
			PackedEdge.pack(1, PackedEdge.MAX_TYPE, 1),
			PackedEdge.pack(2, 0, PackedEdge.MAX_LOCATION)
		};
		byte[] encoded = EdgeCodec.encode(edges, edges.length);
		long[] decoded = new long[edges.length];
		assertEquals(edges.length, EdgeCodec.decode(encoded, 0, encoded.length, decoded));
		long[] expected = edges.clone();
		Arrays.sort(expected);
		Arrays.sort(decoded);
		assertArrayEquals(expected, decoded);
	}

	@Test
	public void testCompressionRatioOnLocalityFriendlyData() {
		// Neighbours created near each other in time land in nearby slots, which is the case the delta encoding is
		// built for. See spec/RELATIONSHIP_STORAGE.md section 5.2.
		int count = 4000;
		long[] edges = new long[count];
		for (int i = 0; i < count; i++) {
			edges[i] = PackedEdge.pack(PackedEdge.DIR_OUT, 3, 1000 + i);
		}
		byte[] encoded = EdgeCodec.encode(edges, count);
		double bytesPerEdge = (double) encoded.length / count;
		assertTrue(bytesPerEdge < 1.5,
			"Expected under 1.5 bytes per edge for sequential neighbours, got " + bytesPerEdge);
		assertTrue(encoded.length < 8192 - 64, "4000 sequential edges must fit one 8 KiB block");
	}

	@Test
	public void testCompressionOnScatteredData() {
		// The adversarial case: neighbours spread across the whole 48 bit location space.
		Random random = new Random(7L);
		int count = 500;
		long[] edges = new long[count];
		for (int i = 0; i < count; i++) {
			edges[i] = PackedEdge.pack(PackedEdge.DIR_OUT, i % 16,
				1 + Math.floorMod(random.nextLong(), PackedEdge.MAX_LOCATION));
		}
		byte[] encoded = EdgeCodec.encode(edges, count);
		double bytesPerEdge = (double) encoded.length / count;
		// Even here the encoding must not exceed the raw 8 bytes per entry by much.
		assertTrue(bytesPerEdge < 9.0, "Worst case is " + bytesPerEdge + " bytes per edge");
	}

	@Test
	public void testCorruptInputIsRejectedRatherThanReadingOutOfBounds() {
		long[] edges = { PackedEdge.pack(0, 1, 100), PackedEdge.pack(0, 1, 200) };
		byte[] encoded = EdgeCodec.encode(edges, edges.length);

		byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
		assertThrows(IllegalArgumentException.class,
			() -> EdgeCodec.decode(truncated, 0, truncated.length, new long[edges.length]));

		byte[] inflated = encoded.clone();
		inflated[0] = (byte) 0x7F; // claim 127 groups
		assertThrows(IllegalArgumentException.class,
			() -> EdgeCodec.decode(inflated, 0, inflated.length, new long[edges.length]));
	}

	/**
	 * A delta that would move the running location backwards or past the end of the range must be refused.
	 *
	 * <p>
	 * The regression test for a defect found by {@code cargo fuzz run edges_decode} against the Rust decoder, which
	 * has the same loop. The accumulation was a plain {@code +}: a delta read off a corrupt page with its high bit set
	 * decodes to a negative long, moves the location <em>backwards</em> onto a perfectly valid one, and the range
	 * check accepts it. The decoder then reports a neighbour that was never there, with no error anywhere.
	 * </p>
	 *
	 * <p>
	 * The differential test against the Rust implementation could not have found this, because Rust wrapped the same
	 * way and the two agreed on the same wrong answer. Comparing two implementations only finds mistakes they do not
	 * share.
	 * </p>
	 */
	@Test
	public void testABackwardsDeltaIsRefusedRatherThanAccepted() {
		// One group, two entries: a valid first location, then a delta with the high bit set.
		byte[] block = new byte[64];
		int pos = VarInt.write(block, 0, 1);          // one group
		pos = VarInt.write(block, pos, 0);            // group key
		pos = VarInt.write(block, pos, 2);            // two entries
		pos = VarInt.write(block, pos, 1L << 40);     // first location, in range
		pos = VarInt.write(block, pos, -1L);          // delta: every bit set
		final int limit = pos;

		long[] dst = new long[8];
		assertThrows(IllegalArgumentException.class, () -> EdgeCodec.decode(block, 0, limit, dst),
			"a delta that is not a valid unsigned varint must be refused, not added");

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(block.length);
			MemorySegment.copy(MemorySegment.ofArray(block), 0, segment, 0, block.length);
			assertThrows(IllegalArgumentException.class, () -> EdgeCodec.decode(segment, 0, limit, dst),
				"and the segment path must refuse it identically");
		}
	}

	/** A delta large enough to overflow the running location, rather than merely exceed its range. */
	@Test
	public void testAnOverflowingDeltaIsRefused() {
		byte[] block = new byte[64];
		int pos = VarInt.write(block, 0, 1);
		pos = VarInt.write(block, pos, 0);
		pos = VarInt.write(block, pos, 2);
		pos = VarInt.write(block, pos, 1L << 40);
		pos = VarInt.write(block, pos, Long.MAX_VALUE);
		final int limit = pos;

		long[] dst = new long[8];
		assertThrows(IllegalArgumentException.class, () -> EdgeCodec.decode(block, 0, limit, dst));
	}

	/**
	 * A group declaring zero entries must be refused, not silently turned into one.
	 *
	 * <p>
	 * The second defect {@code cargo fuzz run edges_decode} found, and the second one both implementations shared. The
	 * first entry of a group is written before the loop that iterates {@code 1..count}, so a count of zero still
	 * emitted an entry — a phantom edge on a store that had none, and an untyped
	 * {@code ArrayIndexOutOfBoundsException} when the destination happened to be full.
	 * </p>
	 */
	@Test
	public void testAGroupOfZeroEntriesIsRefused() {
		byte[] block = new byte[32];
		int pos = VarInt.write(block, 0, 1);       // one group
		pos = VarInt.write(block, pos, 0);         // group key
		pos = VarInt.write(block, pos, 0);         // zero entries, which the encoder never writes
		pos = VarInt.write(block, pos, 1L << 20);  // a location it should never reach
		final int limit = pos;

		long[] dst = new long[4];
		assertThrows(IllegalArgumentException.class, () -> EdgeCodec.decode(block, 0, limit, dst),
			"a group of zero entries is malformed and must be reported as such");

		// And with a destination exactly full, which is where it used to run off the end.
		long[] full = new long[0];
		assertThrows(IllegalArgumentException.class, () -> EdgeCodec.decode(block, 0, limit, full),
			"the failure must be typed even when the destination has no room at all");
	}
}
