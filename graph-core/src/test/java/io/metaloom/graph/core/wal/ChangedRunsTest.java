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

	/**
	 * The commit diffs only the window an overlay page handed out for writing. Whenever every write went through
	 * {@link io.metaloom.graph.core.page.PageOverlay.Page#writable}, that has to be exactly the whole-page diff —
	 * including a window ending on a page whose size is not a multiple of the chunk.
	 */
	@Test
	public void testTheWrittenWindowFindsExactlyTheWholePageRuns() {
		Random random = new Random(20261025L);
		try (Arena arena = Arena.ofConfined()) {
			for (int size : new int[] { 8192, 4096, 4093, 64, 8 }) {
				MemorySegment source = arena.allocate(size);
				for (int trial = 0; trial < 300; trial++) {
					for (int i = 0; i < size; i++) {
						source.set(ValueLayout.JAVA_BYTE, i, (byte) random.nextInt());
					}
					io.metaloom.graph.core.page.PageOverlay overlay = new io.metaloom.graph.core.page.PageOverlay(size);
					io.metaloom.graph.core.page.PageOverlay.Page page = overlay.forWrite(trial, source);
					int writes = random.nextInt(6);
					for (int w = 0; w < writes; w++) {
						int at = random.nextInt(size);
						int span = 1 + random.nextInt(Math.min(size - at, 1 + random.nextInt(100)));
						MemorySegment slice = page.writable(at, span);
						for (int j = 0; j < span; j++) {
							// Sometimes write the byte it already held: a slice handed out need not change anything.
							byte was = slice.get(ValueLayout.JAVA_BYTE, j);
							slice.set(ValueLayout.JAVA_BYTE, j, random.nextInt(4) == 0 ? was : (byte) ~was);
						}
					}
					assertEquals(render(reference(page.base(), page.copy())),
						render(WalManager.changedRuns(page.base(), page.copy(), page.writtenFrom(), page.writtenTo())),
						"size " + size + ", trial " + trial);
					overlay.clear();
				}
			}
		}
	}

	/**
	 * The other side of the contract, so the assertion guarding it in the commit is known to be able to fire: a byte
	 * changed in the copy without going through {@code writable} is outside the window and the windowed diff does not
	 * see it. The commit cross-checks against the whole-page diff when assertions are on, as they are in every test.
	 */
	@Test
	public void testAWriteThatBypassedTheWindowIsNotSeen() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment source = arena.allocate(8192);
			io.metaloom.graph.core.page.PageOverlay overlay = new io.metaloom.graph.core.page.PageOverlay(8192);
			io.metaloom.graph.core.page.PageOverlay.Page page = overlay.forWrite(1, source);
			page.writable(100, 8).set(ValueLayout.JAVA_LONG_UNALIGNED, 0, -1L);
			page.copy().set(ValueLayout.JAVA_BYTE, 5000, (byte) 1);
			assertEquals("96+16 ", render(WalManager.changedRuns(page.base(), page.copy(), page.writtenFrom(),
				page.writtenTo())));
			assertEquals("96+16 5000+8 ", render(WalManager.changedRuns(page.base(), page.copy())));
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
