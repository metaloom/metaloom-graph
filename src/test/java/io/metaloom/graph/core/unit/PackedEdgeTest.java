package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.store.adj.PackedEdge;

@Tag("unit")
public class PackedEdgeTest {

	@Test
	public void testRoundTrip() {
		for (int dir = 0; dir <= 2; dir++) {
			for (int type : new int[] { 0, 1, 8191, PackedEdge.MAX_TYPE }) {
				for (long location : new long[] { 0, 1, 1L << 30, PackedEdge.MAX_LOCATION }) {
					long edge = PackedEdge.pack(dir, type, location);
					assertEquals(dir, PackedEdge.dir(edge));
					assertEquals(type, PackedEdge.typeId(edge));
					assertEquals(location, PackedEdge.location(edge));
				}
			}
		}
	}

	@Test
	public void testNeighbourIdRecovery() {
		long nodeId = ElementId.encode(Kind.NODE, 3, 77, 5);
		long edge = PackedEdge.ofNeighbour(PackedEdge.DIR_OUT, 9, nodeId);
		long recovered = PackedEdge.neighbourId(edge);
		assertEquals(ElementId.segment(nodeId), ElementId.segment(recovered));
		assertEquals(ElementId.slot(nodeId), ElementId.slot(recovered));
		assertEquals(Kind.NODE.code(), ElementId.kindCode(recovered));
		assertEquals(nodeId, ElementId.withGen(recovered, 5));
	}

	@Test
	public void testMaterialisedEntry() {
		long relId = ElementId.encode(Kind.REL, 1, 42, 3);
		long edge = PackedEdge.ofRelationship(relId);
		assertTrue(PackedEdge.isMaterialised(edge));
		assertEquals(relId, ElementId.withGen(PackedEdge.relationshipId(edge), 3));
	}

	@Test
	public void testTombstoneIsUnreachableThroughTheConstructors() {
		assertTrue(PackedEdge.isTombstone(PackedEdge.TOMBSTONE));
		assertFalse(PackedEdge.isTombstone(PackedEdge.pack(0, 0, 1)));
		// Location 0 is the first node any store creates and must never read as a tombstone.
		assertFalse(PackedEdge.isTombstone(PackedEdge.pack(0, 0, 0)));
		assertFalse(PackedEdge.isTombstone(
			PackedEdge.ofRelationship(ElementId.encode(Kind.REL, 0, 0, 1))));
		// The tombstone bit pattern needs dir 3 with a non-zero type, which pack rejects.
		assertThrows(IllegalArgumentException.class,
			() -> PackedEdge.pack(PackedEdge.DIR_MATERIALISED, PackedEdge.MAX_TYPE, PackedEdge.MAX_LOCATION));
	}

	@Test
	public void testTypeBeyondFourteenBitsIsRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> PackedEdge.pack(0, PackedEdge.MAX_TYPE + 1, 1));
	}
}
