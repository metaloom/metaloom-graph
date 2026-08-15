package io.metaloom.graph.core.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.store.adj.EdgeCodec;
import io.metaloom.graph.core.store.adj.PackedEdge;
import io.metaloom.graph.core.util.VarInt;

/**
 * Where the time in a traversal actually goes, decomposed.
 *
 * <p>
 * <b>Why this exists.</b> "Should the hot loops be vectorised" is not answerable from the shape of the code. Reading
 * a node's edges costs a page resolve, a decode, a tombstone filter and a materialise into {@code Edge} objects, and
 * the only useful question is which of those dominates — because vectorising a tenth of the cost buys a tenth of
 * whatever the speedup is. This benchmark splits the decode path into its parts and prints nanoseconds per entry for
 * each, so the SIMD decision is made against a measurement rather than an intuition.
 * </p>
 *
 * <p>
 * Run with {@code mvn test -Pbench -Dtest=CodecProfileBenchmark}.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/GRAPH_BENCHMARK.md">spec/GRAPH_BENCHMARK.md</a>
 */
@Tag("bench")
public class CodecProfileBenchmark {

	/** A full page-sized block's worth of edges, which is the size the codec runs at on a busy node. */
	private static final int ENTRIES = 8_000;
	private static final int ROUNDS = 2_000;

	@Test
	public void profileTheDecodePath() {
		long[] edges = realisticEdges(ENTRIES, 20260814L);
		byte[] encoded = EdgeCodec.encode(edges, edges.length);
		long[] dst = new long[edges.length * 2];

		System.out.printf("%n=== edge codec, %,d entries, %,d encoded bytes = %.2f bytes per entry ===%n",
			edges.length, encoded.length, (double) encoded.length / edges.length);

		// Decode: the traversal hot loop.
		double decode = time(ROUNDS, () -> EdgeCodec.decode(encoded, 0, encoded.length, dst));
		report("decode", decode, edges.length);

		// Encode: the write hot loop, run whenever a block recompresses.
		long[] source = Arrays.copyOf(edges, edges.length);
		double encode = time(ROUNDS / 4, () -> EdgeCodec.encode(source, source.length).length);
		report("encode (sort + varint)", encode, edges.length);

		// The sort inside encode, on its own.
		long[] scratch = new long[edges.length];
		double sort = time(ROUNDS / 4, () -> {
			System.arraycopy(edges, 0, scratch, 0, edges.length);
			Arrays.sort(scratch);
			return scratch[0];
		});
		report("  of which sort", sort, edges.length);
		report("  of which varint + grouping", encode - sort, edges.length);

		// The copy out of the mapped page that precedes every decode.
		byte[] target = new byte[encoded.length];
		double copy = time(ROUNDS, () -> {
			System.arraycopy(encoded, 0, target, 0, encoded.length);
			return target.length;
		});
		report("page copy of the same bytes", copy, edges.length);

		// A tombstone filter over the decoded entries: one branch per entry, the shape a vector unit likes best.
		double filter = time(ROUNDS, () -> {
			int kept = 0;
			for (int i = 0; i < edges.length; i++) {
				if (!PackedEdge.isTombstone(dst[i])) {
					kept++;
				}
			}
			return kept;
		});
		report("tombstone filter", filter, edges.length);

		System.out.println();
		System.out.printf("decode is %.0f%% of decode+copy+filter, which is all a read of one block costs%n",
			100.0 * decode / (decode + copy + filter));
		assertTrue(decode > 0);
	}

	/**
	 * The shape {@code compact()} actually hands the encoder, against the random shape measured above.
	 *
	 * <p>
	 * A block recompresses by decoding its sorted region — which comes back sorted — and appending the raw tail, which
	 * is small and unordered. So the array reaching {@code Arrays.sort} is a long sorted run followed by a short
	 * random one, not a random array. That matters twice over: the cost is different, and the JDK's sort has had run
	 * detection since JDK 14, so the optimisation that looks obvious here may already be in the runtime.
	 * </p>
	 */
	@Test
	public void profileTheShapeCompactActuallySorts() {
		System.out.printf("%n=== sort cost by input shape, %,d entries ===%n", ENTRIES);
		long[] random = realisticEdges(ENTRIES, 20260814L);

		long[] sorted = Arrays.copyOf(random, random.length);
		Arrays.sort(sorted);

		long[] scratch = new long[ENTRIES];
		double randomSort = time(ROUNDS / 4, () -> {
			System.arraycopy(random, 0, scratch, 0, ENTRIES);
			Arrays.sort(scratch);
			return scratch[0];
		});
		System.out.printf("  fully random          %9.1f ns = %5.2f ns per entry%n", randomSort, randomSort / ENTRIES);

		double sortedSort = time(ROUNDS / 4, () -> {
			System.arraycopy(sorted, 0, scratch, 0, ENTRIES);
			Arrays.sort(scratch);
			return scratch[0];
		});
		System.out.printf("  already sorted        %9.1f ns = %5.2f ns per entry%n", sortedSort, sortedSort / ENTRIES);

		// The real one: a sorted region plus the raw tail that fits beside it.
		for (int tail : new int[] { 16, 64, 256 }) {
			long[] nearly = Arrays.copyOf(sorted, sorted.length);
			Random rng = new Random(11L);
			for (int i = 0; i < tail; i++) {
				nearly[ENTRIES - tail + i] = random[rng.nextInt(ENTRIES)];
			}
			double nearlySort = time(ROUNDS / 4, () -> {
				System.arraycopy(nearly, 0, scratch, 0, ENTRIES);
				Arrays.sort(scratch);
				return scratch[0];
			});
			System.out.printf("  sorted + %3d raw tail %9.1f ns = %5.2f ns per entry  (%.1fx the random case)%n",
				tail, nearlySort, nearlySort / ENTRIES, nearlySort / randomSort);
		}
		System.out.println("  (if the sorted rows are already cheap, the JDK detects the run and there is nothing");
		System.out.println("   here for a merge or a vector unit to win)");
	}

	/** How the decode cost scales with block size, which says whether it is per-entry work or per-call overhead. */
	@Test
	public void profileDecodeAcrossBlockSizes() {
		System.out.printf("%n=== decode cost against block size ===%n");
		for (int count : new int[] { 8, 64, 400, 2_000, 8_000 }) {
			long[] edges = realisticEdges(count, 4242L);
			byte[] encoded = EdgeCodec.encode(edges, edges.length);
			long[] dst = new long[count * 2];
			double nanos = time(Math.max(200, 2_000_000 / count), () -> EdgeCodec.decode(encoded, 0, encoded.length, dst));
			System.out.printf("  %,6d entries  %8.1f ns total  %6.2f ns per entry  %.2f bytes per entry%n",
				count, nanos, nanos / count, (double) encoded.length / count);
			assertEquals(count, EdgeCodec.decode(encoded, 0, encoded.length, dst));
		}
	}

	/**
	 * The SWAR decode against the byte-at-a-time one it replaced.
	 *
	 * <p>
	 * Both decode the same bytes into the same edges — {@code SwarVarIntTest} holds them to that — so this is a
	 * measurement of the technique alone: eight bytes and one branch per varint, against one byte and one branch per
	 * byte.
	 * </p>
	 */
	@Test
	public void profileSwarAgainstScalar() {
		System.out.printf("%n=== SWAR decode against byte-at-a-time, by block size ===%n");
		for (int count : new int[] { 64, 400, 2_000, 8_000 }) {
			long[] edges = realisticEdges(count, 20260814L);
			byte[] encoded = EdgeCodec.encode(edges, count);
			long[] dst = new long[count * 2];
			int rounds = Math.max(500, 4_000_000 / count);

			double swar = time(rounds, () -> EdgeCodec.decode(encoded, 0, encoded.length, dst));
			double scalar = time(rounds, () -> scalarDecode(encoded, 0, encoded.length, dst));

			System.out.printf("  %,6d entries: scalar %8.1f ns (%5.2f ns/entry), swar %8.1f ns (%5.2f ns/entry) = %.2fx%n",
				count, scalar, scalar / count, swar, swar / count, scalar / swar);
			assertEquals(count, EdgeCodec.decode(encoded, 0, encoded.length, dst));
			assertEquals(count, scalarDecode(encoded, 0, encoded.length, dst));
		}
	}

	/**
	 * Decoding out of a mapped page against decoding out of a copied byte array.
	 *
	 * <p>
	 * The segment path skips an allocation of up to a page per block read, which is the reason to want it. Whether it
	 * is actually cheaper is a different question: a {@code MemorySegment} byte access carries its own bounds check
	 * and the JIT has an easier time with an array. Measured, not assumed — the store uses whichever wins.
	 * </p>
	 */
	@Test
	public void profileSegmentAgainstArrayDecode() {
		System.out.printf("%n=== decode source: mapped segment against copied array ===%n");
		for (int count : new int[] { 64, 400, 2_000, 8_000 }) {
			long[] edges = realisticEdges(count, 20260814L);
			byte[] encoded = EdgeCodec.encode(edges, count);
			long[] dst = new long[count * 2];
			int rounds = Math.max(500, 4_000_000 / count);

			try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
				java.lang.foreign.MemorySegment segment = arena.allocate(encoded.length);
				java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(encoded), 0, segment, 0,
					encoded.length);

				double array = time(rounds, () -> EdgeCodec.decode(encoded, 0, encoded.length, dst));
				double segmentOnly = time(rounds, () -> EdgeCodec.decode(segment, 0, encoded.length, dst));
				// What the block actually did before: copy the region out of the page, then decode the copy.
				byte[] scratch = new byte[encoded.length];
				double copyThenArray = time(rounds, () -> {
					java.lang.foreign.MemorySegment.copy(segment, 0, java.lang.foreign.MemorySegment.ofArray(scratch), 0,
						encoded.length);
					return EdgeCodec.decode(scratch, 0, encoded.length, dst);
				});

				System.out.printf("  %,6d entries: array %8.1f ns, segment %8.1f ns, copy+array %8.1f ns"
					+ "  -> segment is %.2fx the copy-and-decode%n",
					count, array, segmentOnly, copyThenArray, segmentOnly / copyThenArray);
			}
		}
	}

	/** The decode loop written against the byte array reader, kept here to time the alternative. */
	private static int scalarDecode(byte[] src, int offset, int limit, long[] dst) {
		long[] out = new long[1];
		int pos = VarInt.read(src, offset, limit, out);
		int groups = (int) out[0];
		int written = 0;
		for (int g = 0; g < groups; g++) {
			pos = VarInt.read(src, pos, limit, out);
			int key = (int) out[0];
			pos = VarInt.read(src, pos, limit, out);
			int count = (int) out[0];
			pos = VarInt.read(src, pos, limit, out);
			long location = out[0];
			dst[written++] = ((long) key << 48) | location;
			for (int j = 1; j < count; j++) {
				pos = VarInt.read(src, pos, limit, out);
				location += out[0];
				dst[written++] = ((long) key << 48) | location;
			}
		}
		return written;
	}

	/**
	 * Edges shaped like the ones a real load produces: a handful of relationship types, locations clustered where a
	 * loader would leave them, and therefore deltas that varint-encode to one or two bytes.
	 */
	private static long[] realisticEdges(int count, long seed) {
		Random random = new Random(seed);
		long[] edges = new long[count];
		long location = random.nextInt(1 << 20);
		for (int i = 0; i < count; i++) {
			location += 1 + random.nextInt(256);
			int type = random.nextInt(4);
			int dir = random.nextInt(2);
			edges[i] = PackedEdge.pack(dir, type, location);
		}
		return edges;
	}

	private interface Work {
		long run();
	}

	/** Nanoseconds per round, after a warmup of the same work. */
	private static double time(int rounds, Work work) {
		long sink = 0;
		for (int i = 0; i < Math.min(rounds, 200); i++) {
			sink += work.run();
		}
		long start = System.nanoTime();
		for (int i = 0; i < rounds; i++) {
			sink += work.run();
		}
		double nanos = (double) (System.nanoTime() - start) / rounds;
		assertTrue(sink != Long.MIN_VALUE);
		return nanos;
	}

	private static void report(String what, double nanos, int entries) {
		System.out.printf("  %-30s %9.1f ns  = %6.2f ns per entry%n", what, nanos, nanos / entries);
	}
}
