package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

		assertEquals(8192, g.stride(Kind.ADJ));
		assertEquals(1 << 19, g.slotsPerSegment(Kind.ADJ));
		assertEquals(4 * GIB, g.dataBytes(Kind.ADJ));
		assertEquals(16 * MIB, g.pmaBytes(Kind.ADJ));
		assertEquals(64 * 1024, g.fsbBytes(Kind.ADJ));
		assertEquals(16 * MIB + 64 * 1024 + 8192, g.dataStart(Kind.ADJ));
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
}
