package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.Geometry;

/**
 * @see <a href="../../../../../../../../spec/ID_ENCODING.md">spec/ID_ENCODING.md</a> section 8
 */
@Tag("unit")
public class ElementIdTest {

	@Test
	public void testFieldRoundTrip() {
		int[] segments = { 0, 1, 12345, ElementId.MAX_SEGMENT - 1, ElementId.MAX_SEGMENT };
		int[] slots = { 0, 1, 98765, ElementId.MAX_SLOT - 1, ElementId.MAX_SLOT };
		int[] gens = { 0, 1, 2048, ElementId.MAX_GEN - 1, ElementId.MAX_GEN };
		for (Kind kind : Kind.values()) {
			for (int segment : segments) {
				for (int slot : slots) {
					for (int gen : gens) {
						long id = ElementId.encode(kind, segment, slot, gen);
						assertEquals(kind.code(), ElementId.kindCode(id), "kind");
						assertEquals(segment, ElementId.segment(id), "segment");
						assertEquals(slot, ElementId.slot(id), "slot");
						assertEquals(gen, ElementId.gen(id), "gen");
					}
				}
			}
		}
	}

	@Test
	public void testTotalityOverRandomLongs() {
		Random random = new Random(20260813L);
		for (int i = 0; i < 200_000; i++) {
			long raw = random.nextLong();
			long reencoded = ElementId.encode(ElementId.kindCode(raw), ElementId.segment(raw),
				ElementId.slot(raw), ElementId.gen(raw));
			assertEquals(raw, reencoded, "decode then encode must be the identity");
		}
	}

	@Test
	public void testNullIsZeroAndUnreachable() {
		assertEquals(0L, ElementId.NULL);
		assertTrue(ElementId.isNull(0L));
		// A live id always has kind >= 1 and gen >= 1, so it can never collide with NULL.
		assertNotEquals(ElementId.NULL, ElementId.encode(Kind.NODE, 0, 0, 1));
		assertFalse(ElementId.isNull(ElementId.encode(Kind.NODE, 0, 0, 1)));
	}

	@Test
	public void testAddressFunctionDependsOnlyOnKindAndSlot() {
		Geometry geometry = new Geometry(Geometry.DEFAULT_PAGE_SIZE, Geometry.DEFAULT_SEGMENT_CAPACITY);
		for (Kind kind : new Kind[] { Kind.NODE, Kind.REL, Kind.PROP, Kind.ADJ, Kind.BLOB, Kind.UNDO }) {
			for (int slot : new int[] { 0, 1, 127, 128, 4095 }) {
				long expected = geometry.dataStart(kind) + (long) slot * geometry.stride(kind);
				for (int segment : new int[] { 0, 7, 4242 }) {
					for (int gen : new int[] { 1, 4095 }) {
						long id = ElementId.encode(kind, segment, slot, gen);
						long offset = geometry.dataStart(ElementId.kind(id))
							+ (long) ElementId.slot(id) * geometry.stride(ElementId.kind(id));
						assertEquals(expected, offset,
							"offset must not vary with segment or generation");
					}
				}
			}
		}
	}

	@Test
	public void testPageContainment() {
		for (int pageSize : new int[] { 4096, 8192, 16384 }) {
			Geometry geometry = new Geometry(pageSize, 64L * 1024 * 1024);
			for (Kind kind : new Kind[] { Kind.NODE, Kind.REL, Kind.PROP, Kind.ADJ, Kind.BLOB, Kind.UNDO }) {
				int stride = geometry.stride(kind);
				assertEquals(0, pageSize % stride, kind + " stride must divide the page size");
				assertEquals(0, geometry.dataStart(kind) % pageSize, kind + " data start must be page aligned");
				for (int slot = 0; slot < 1000; slot++) {
					long offset = geometry.byteOffset(kind, slot) - geometry.dataStart(kind);
					assertTrue(offset % pageSize + stride <= pageSize,
						kind + " record " + slot + " straddles a page");
				}
			}
		}
	}

	@Test
	public void testLocationRoundTrip() {
		long id = ElementId.encode(Kind.NODE, 123, 456, 7);
		long location = ElementId.location(id);
		long rebuilt = ElementId.fromLocation(Kind.NODE, location);
		assertEquals(ElementId.segment(id), ElementId.segment(rebuilt));
		assertEquals(ElementId.slot(id), ElementId.slot(rebuilt));
		assertEquals(0, ElementId.gen(rebuilt), "a rebuilt location carries no generation");
		assertEquals(id, ElementId.withGen(rebuilt, 7));
	}

	@Test
	public void testOutOfRangeIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> ElementId.encode(Kind.NODE, -1, 0, 1));
		assertThrows(IllegalArgumentException.class,
			() -> ElementId.encode(Kind.NODE, ElementId.MAX_SEGMENT + 1, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> ElementId.encode(Kind.NODE, 0, ElementId.MAX_SLOT + 1, 1));
		assertThrows(IllegalArgumentException.class, () -> ElementId.encode(Kind.NODE, 0, 0, ElementId.MAX_GEN + 1));
		assertThrows(IllegalArgumentException.class,
			() -> ElementId.expect(ElementId.encode(Kind.REL, 0, 0, 1), Kind.NODE));
	}
}
