package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.Geometry;

/**
 * Asserts the segment layout table in spec/FILE_FORMAT.md section 3. A silent change to any of these numbers would
 * relocate every record in the store.
 */
@Tag("unit")
public class GeometryTest {

	private static final long MIB = 1024 * 1024;
	private static final long GIB = 1024 * MIB;

	@Test
	public void testDefaultLayoutMatchesTheSpecification() {
		Geometry g = new Geometry(8192, 4 * GIB);

		assertEquals(64, g.stride(Kind.NODE));
		assertEquals(1 << 24, g.slotsPerSegment(Kind.NODE));
		assertEquals(1 * GIB, g.dataBytes(Kind.NODE));
		assertEquals(2 * MIB, g.fsbBytes(Kind.NODE));
		assertEquals(4 * MIB, g.pmaBytes(Kind.NODE));
		assertEquals(6 * MIB + 8192, g.dataStart(Kind.NODE));

		assertEquals(32, g.stride(Kind.PROP));
		assertEquals(1 << 24, g.slotsPerSegment(Kind.PROP));
		assertEquals(512 * MIB, g.dataBytes(Kind.PROP));
		assertEquals(4 * MIB + 8192, g.dataStart(Kind.PROP));

		// An adjacency block is a run of 512 byte cells, not a page. See Kind.ADJ_CELL_BYTES and
		// spec/RELATIONSHIP_STORAGE.md section 5.
		assertEquals(512, g.stride(Kind.ADJ));
		assertEquals(16, g.recordsPerPage(Kind.ADJ));
		assertEquals(1 << 23, g.slotsPerSegment(Kind.ADJ));
		assertEquals(4 * GIB, g.dataBytes(Kind.ADJ));
	}

	@Test
	public void testMetadataOverheadStaysBelowOnePercent() {
		Geometry g = new Geometry(8192, 4 * GIB);
		for (Kind kind : new Kind[] { Kind.NODE, Kind.REL, Kind.PROP, Kind.ADJ, Kind.BLOB, Kind.UNDO }) {
			double overhead = (double) g.dataStart(kind) / g.dataBytes(kind);
			assertTrue(overhead < 0.01, kind + " metadata overhead is " + overhead);
		}
	}

	@Test
	public void testPageSizeVariants() {
		for (int pageSize : new int[] { 4096, 8192, 16384 }) {
			Geometry g = new Geometry(pageSize, 64 * MIB);
			for (Kind kind : new Kind[] { Kind.NODE, Kind.REL, Kind.PROP, Kind.ADJ, Kind.BLOB, Kind.UNDO }) {
				assertEquals(0, pageSize % g.stride(kind));
				assertEquals(0, g.dataStart(kind) % pageSize);
				assertEquals(g.dataBytes(kind) / pageSize, g.pagesPerSegment(kind));
				assertEquals((long) g.slotsPerSegment(kind) * g.stride(kind), g.dataBytes(kind));
			}
		}
	}

	@Test
	public void testInvalidConfigurationIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new Geometry(5000, 4 * GIB));
		assertThrows(IllegalArgumentException.class, () -> new Geometry(2048, 4 * GIB));
		assertThrows(IllegalArgumentException.class, () -> new Geometry(8192, 1234));
	}

	// ---------------------------------------------------------------- the declared vector kind

	/**
	 * The tables are sized by what the id encoding can express rather than by how many kinds happen to exist.
	 * <p>
	 * They were sized at 8 by hand while the encoding gives the kind 4 bits, and the population loop skips a code past
	 * the end — so a ninth kind was neither a compile error nor a construction error. It was a set of zeroed tables
	 * and an {@code ArrayIndexOutOfBoundsException} out of the address function on the first read.
	 */
	@Test
	public void testEveryKindTheIdEncodingCanExpressHasATableEntry() {
		assertEquals(16, Kind.MAX_KINDS, "four bits of kind");
		assertEquals(Kind.VEC, Kind.byCode(8), "a code past the old table size resolves");
		Geometry g = new Geometry(8192, 4 * GIB, 4096);
		for (Kind kind : Kind.values()) {
			if (kind == Kind.NULL) {
				continue;
			}
			// The point is that none of these throws.
			assertTrue(g.stride(kind) > 0, kind + " has no stride");
			assertTrue(g.dataStart(kind) > 0, kind + " has no data start");
		}
	}

	/** A store that never asked for vector storage does not have the kind at all — not a zero-sized one. */
	@Test
	public void testVectorStorageIsAbsentUntilItIsDeclared() {
		Geometry without = new Geometry(8192, 4 * GIB);
		assertEquals(0, without.stride(Kind.VEC));
		assertEquals(0, without.vectorStride());
		assertFalse(without.isEnabled(Kind.VEC));
		assertTrue(without.isEnabled(Kind.NODE), "and every other kind is unaffected");

		Geometry with = new Geometry(8192, 4 * GIB, 4096);
		assertEquals(4096, with.stride(Kind.VEC));
		assertTrue(with.isEnabled(Kind.VEC));
		assertEquals(2, with.recordsPerPage(Kind.VEC), "two 768-dimension vectors to an 8 KiB page");
		assertEquals(0, with.dataStart(Kind.VEC) % 8192, "and the data region still starts on a page");

		// Declaring vector storage must not move anything else.
		for (Kind kind : new Kind[] { Kind.NODE, Kind.REL, Kind.PROP, Kind.ADJ, Kind.BLOB, Kind.INDEX, Kind.UNDO }) {
			assertEquals(without.dataStart(kind), with.dataStart(kind), kind + " moved");
			assertEquals(without.stride(kind), with.stride(kind), kind + " changed stride");
		}
	}

	/**
	 * A declared stride is the one number in the layout an application chooses, so it is the one that can be wrong.
	 * It goes through exactly the same invariant as every built-in stride: it must divide the page.
	 */
	@Test
	public void testADeclaredStrideMustStillDivideThePage() {
		// 3072 is 768 four-byte floats — the most common embedding width, and the one that does not fit.
		IllegalArgumentException bad =
			assertThrows(IllegalArgumentException.class, () -> new Geometry(8192, 4 * GIB, 3072));
		assertTrue(bad.getMessage().contains("3072"), bad.getMessage());
		assertTrue(bad.getMessage().contains("VEC"), bad.getMessage());

		assertThrows(IllegalArgumentException.class, () -> new Geometry(8192, 4 * GIB, -1));
		// Legal strides are the powers of two that divide the page.
		for (int stride : new int[] { 512, 1024, 2048, 4096, 8192 }) {
			assertEquals(stride, new Geometry(8192, 4 * GIB, stride).stride(Kind.VEC));
		}
	}
}
