package io.metaloom.graph.core.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link WalManager#changedRuns} against the chunk-by-chunk loop it replaced.
 *
 * <p>
 * The runs decide what is logged and what conflict detection compares, so the faster search has to produce exactly
 * the same list — not merely a list that covers the same bytes.
 * </p>
 */
@Tag("unit")
public class ChangedRunsTest {

	@Test
	public void testTheRunsMatchTheChunkLoopOnRandomEdits() {
		Random random = new Random(20261024L);
		try (Arena arena = Arena.ofConfined()) {
			for (int size : new int[] { 4096, 8192, 4093, 17, 8, 0 }) {
				MemorySegment original = arena.allocate(Math.max(1, size)).asSlice(0, size);
				MemorySegment modified = arena.allocate(Math.max(1, size)).asSlice(0, size);
				for (int trial = 0; trial < 500; trial++) {
					for (int i = 0; i < size; i++) {
						original.set(ValueLayout.JAVA_BYTE, i, (byte) random.nextInt());
					}
					modified.copyFrom(original);
					int edits = size == 0 ? 0 : random.nextInt(12);
					for (int e = 0; e < edits; e++) {
						int at = random.nextInt(size);
						int span = 1 + random.nextInt(Math.min(size - at, 1 + random.nextInt(200)));
						for (int j = at; j < at + span; j++) {
							modified.set(ValueLayout.JAVA_BYTE, j, (byte) ~original.get(ValueLayout.JAVA_BYTE, j));
						}
					}
					assertEquals(render(reference(original, modified)), render(WalManager.changedRuns(original, modified)),
						"size " + size + ", trial " + trial);
				}
			}
		}
	}

	/** The loop as it stood before, kept verbatim as the definition. */
	private static List<int[]> reference(MemorySegment original, MemorySegment modified) {
		List<int[]> runs = new ArrayList<>();
		int size = (int) modified.byteSize();
		int start = -1;
		int lastDifferent = -1;
		for (int i = 0; i < size; i += 8) {
			int chunk = Math.min(8, size - i);
			boolean same;
			if (chunk == 8) {
				same = original.get(ValueLayout.JAVA_LONG_UNALIGNED, i) == modified.get(ValueLayout.JAVA_LONG_UNALIGNED, i);
			} else {
				same = true;
				for (int j = 0; j < chunk; j++) {
					if (original.get(ValueLayout.JAVA_BYTE, i + j) != modified.get(ValueLayout.JAVA_BYTE, i + j)) {
						same = false;
						break;
					}
				}
			}
			if (!same) {
				if (start < 0) {
					start = i;
				} else if (i - lastDifferent > 64) {
					runs.add(new int[] { start, lastDifferent + 8 - start });
					start = i;
				}
				lastDifferent = i;
			}
		}
		if (start >= 0) {
			int end = Math.min(size, lastDifferent + 8);
			runs.add(new int[] { start, end - start });
		}
		return runs;
	}

	private static String render(List<int[]> runs) {
		StringBuilder out = new StringBuilder();
		for (int[] run : runs) {
			out.append(run[0]).append('+').append(run[1]).append(' ');
		}
		return out.toString();
	}
}
