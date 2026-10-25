package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.store.adj.PackedEdge;
import io.metaloom.graph.core.store.adj.TypeFilter;

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
		assertEquals(PackedEdge.TYPE_NOT_RECORDED, PackedEdge.typeId(edge));
	}

	/** The IDX-2 fast path: a materialised entry carries the type a traversal filters on. */
	@Test
	public void testAMaterialisedEntryRoundTripsItsCachedType() {
		long relId = ElementId.encode(Kind.REL, 1, 42, 3);
		for (int type : new int[] { 1, 2, 8191, PackedEdge.MAX_TYPE - 1 }) {
			long edge = PackedEdge.ofRelationship(relId, type);
			assertTrue(PackedEdge.isMaterialised(edge), "dir must still say materialised for type " + type);
			assertEquals(type, PackedEdge.typeId(edge));
			assertEquals(relId, ElementId.withGen(PackedEdge.relationshipId(edge), 3),
				"the location must survive the type sharing the word");
			assertFalse(PackedEdge.isTombstone(edge));
		}
	}

	/**
	 * The two type ids that cannot be cached. {@link PackedEdge#MAX_TYPE} is what keeps the tombstone unreachable, and
	 * 0 is not a real type — {@code Catalog} hands it out for "unknown" — which is what makes it free to use as the
	 * "not recorded" marker an older store's entries already hold.
	 */
	@Test
	public void testAnUncacheableTypeFallsBackToReadingTheRecord() {
		long relId = ElementId.encode(Kind.REL, 1, 42, 3);
		assertEquals(PackedEdge.TYPE_NOT_RECORDED, PackedEdge.typeId(PackedEdge.ofRelationship(relId, 0)));
		assertEquals(PackedEdge.TYPE_NOT_RECORDED,
			PackedEdge.typeId(PackedEdge.ofRelationship(relId, PackedEdge.MAX_TYPE)));
		// A type id read out of a corrupt record must degrade to "read the record", not break the caller.
		assertEquals(PackedEdge.TYPE_NOT_RECORDED, PackedEdge.typeId(PackedEdge.ofRelationship(relId, -7)));
		assertEquals(PackedEdge.TYPE_NOT_RECORDED,
			PackedEdge.typeId(PackedEdge.ofRelationship(relId, Integer.MAX_VALUE)));
	}

	/** A cached type is a hint: an entry that has none must never be filtered out on the strength of it. */
	@Test
	public void testAnEntryWithNoCachedTypeSurvivesEveryTypeFilter() {
		long relId = ElementId.encode(Kind.REL, 1, 42, 3);
		TypeFilter filter = TypeFilter.of(7);
		assertFalse(PackedEdge.excludedByType(PackedEdge.ofRelationship(relId), filter));
		assertFalse(PackedEdge.excludedByType(PackedEdge.ofRelationship(relId, 7), filter));
		assertTrue(PackedEdge.excludedByType(PackedEdge.ofRelationship(relId, 8), filter));
		// A lightweight entry always carries its own type, so type 0 there is a genuine mismatch.
		assertTrue(PackedEdge.excludedByType(PackedEdge.pack(PackedEdge.DIR_OUT, 0, 5), filter));
		assertFalse(PackedEdge.excludedByType(PackedEdge.pack(PackedEdge.DIR_OUT, 7, 5), filter));
		// No filter excludes nothing.
		assertFalse(PackedEdge.excludedByType(PackedEdge.pack(PackedEdge.DIR_OUT, 8, 5), null));
	}

	/** The primitive filter must agree with the set it replaced, across word boundaries and past the 14 bit field. */
	@Test
	public void testATypeFilterAdmitsExactlyItsIds() {
		int[] ids = { 1, 63, 64, 65, 1000, PackedEdge.MAX_TYPE, PackedEdge.MAX_TYPE + 5 };
		TypeFilter filter = TypeFilter.of(ids);
		java.util.Set<Integer> expected = new java.util.HashSet<>();
		for (int id : ids) {
			expected.add(id);
		}
		for (int id = -2; id < PackedEdge.MAX_TYPE + 70; id++) {
			assertEquals(expected.contains(id), filter.admits(id), "type " + id);
		}
		assertFalse(TypeFilter.of(java.util.List.of()).admits(1), "a filter that resolved to nothing admits nothing");
	}

	@Test
	public void testTombstoneIsUnreachableThroughTheConstructors() {
		assertTrue(PackedEdge.isTombstone(PackedEdge.TOMBSTONE));
		assertFalse(PackedEdge.isTombstone(PackedEdge.pack(0, 0, 1)));
		// Location 0 is the first node any store creates and must never read as a tombstone.
		assertFalse(PackedEdge.isTombstone(PackedEdge.pack(0, 0, 0)));
		assertFalse(PackedEdge.isTombstone(
			PackedEdge.ofRelationship(ElementId.encode(Kind.REL, 0, 0, 1))));
		// The tombstone bit pattern needs dir 3 with an all-ones type, which pack rejects — including at the one
		// location where the rest of the word is already all ones.
		assertThrows(IllegalArgumentException.class,
			() -> PackedEdge.pack(PackedEdge.DIR_MATERIALISED, PackedEdge.MAX_TYPE, PackedEdge.MAX_LOCATION));
		assertThrows(IllegalArgumentException.class,
			() -> PackedEdge.pack(PackedEdge.DIR_MATERIALISED, PackedEdge.MAX_TYPE, 1));
		// And the constructor that takes a type must never route around that check.
		assertFalse(PackedEdge.isTombstone(
			PackedEdge.ofRelationship(ElementId.fromLocation(Kind.REL, PackedEdge.MAX_LOCATION),
				PackedEdge.MAX_TYPE)));
	}

	@Test
	public void testTypeBeyondFourteenBitsIsRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> PackedEdge.pack(0, PackedEdge.MAX_TYPE + 1, 1));
	}

	/** Masked matching is how a delete finds an entry whose cached type it does not know. */
	@Test
	public void testTheTypeAgnosticMaskComparesEverythingButTheType() {
		long relId = ElementId.encode(Kind.REL, 1, 42, 3);
		long cached = PackedEdge.ofRelationship(relId, 9);
		long plain = PackedEdge.ofRelationship(relId);
		assertEquals(plain & PackedEdge.TYPE_AGNOSTIC_MASK, cached & PackedEdge.TYPE_AGNOSTIC_MASK);
		long other = PackedEdge.ofRelationship(ElementId.encode(Kind.REL, 1, 43, 3), 9);
		assertFalse((other & PackedEdge.TYPE_AGNOSTIC_MASK) == (cached & PackedEdge.TYPE_AGNOSTIC_MASK),
			"a different relationship must not match");
		assertFalse((PackedEdge.pack(PackedEdge.DIR_OUT, 9, PackedEdge.location(cached))
			& PackedEdge.TYPE_AGNOSTIC_MASK) == (cached & PackedEdge.TYPE_AGNOSTIC_MASK),
			"the direction stays part of the match");
	}
}
