package io.metaloom.graph.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The value codec, round-tripped.
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
@Tag("unit")
public class WireValueCodecTest {

	private static Object roundTrip(Object value) {
		WireValueCodec.Writer writer = new WireValueCodec.Writer();
		WireValueCodec.encode(writer, value);
		byte[] bytes = writer.toArray();
		WireValueCodec.Reader reader = new WireValueCodec.Reader(bytes);
		Object decoded = WireValueCodec.decode(reader);
		assertEquals(0, reader.remaining(), "the decoder must consume exactly what the encoder wrote");
		return decoded;
	}

	@Test
	public void testScalars() {
		assertNull(roundTrip(null));
		assertEquals(Boolean.TRUE, roundTrip(true));
		assertEquals(Boolean.FALSE, roundTrip(false));
		assertEquals("", roundTrip(""));
		assertEquals("hello", roundTrip("hello"));
		assertEquals(3.5d, roundTrip(3.5d));
		assertEquals("äöü — ✈", roundTrip("äöü — ✈"), "UTF-8, counted in bytes rather than chars");
	}

	@Test
	public void testIntegersAcrossEverySizeClass() {
		// Zigzag, so small negatives are as cheap as small positives — the case a plain varint gets wrong.
		for (long value : new long[] { 0, 1, -1, 63, -64, 127, -128, 1 << 20, -(1 << 20),
			Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE }) {
			assertEquals(value, roundTrip(value), "integer " + value);
		}
	}

	@Test
	public void testAStringLongerThanTheStorageCodecCouldCarry() {
		// The decisive case. ValueCodec escapes anything over 255 bytes to the blob store — a write — and throws
		// without one, and a value being encoded onto a socket has no blob store to escape to. That is why the wire
		// has its own codec rather than reusing the storage one.
		String big = "x".repeat(64 * 1024);
		assertEquals(big, roundTrip(big));
	}

	@Test
	public void testCollections() {
		assertEquals(List.of(), roundTrip(List.of()));
		assertEquals(List.of(1L, "two", 3.0d), roundTrip(new ArrayList<>(List.of(1L, "two", 3.0d))));

		List<Object> withNull = new ArrayList<>();
		withNull.add(null);
		withNull.add(1L);
		assertEquals(withNull, roundTrip(withNull), "a null inside a list is a value, not an absence");

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("a", 1L);
		map.put("b", List.of("nested"));
		assertEquals(map, roundTrip(map));
	}

	@Test
	public void testNode() {
		WireNode node = new WireNode(1L << 60, List.of("Airport", "Hub"),
			new LinkedHashMap<>(Map.of("code", "LHR")));
		WireNode decoded = (WireNode) roundTrip(node);
		assertEquals(node.id(), decoded.id());
		assertEquals(node.labels(), decoded.labels());
		assertEquals(node.properties(), decoded.properties());
	}

	@Test
	public void testALightweightEdgeKeepsItsNullId() {
		// A relationship created by createEdge has no record, so no id and no properties. It has to survive the wire
		// as null rather than as zero: zero is a legitimate value elsewhere, and a client cannot tell them apart.
		WireRelationship lightweight = new WireRelationship(null, "BOUGHT", 1L << 60, (1L << 60) + 1, Map.of());
		WireRelationship decoded = (WireRelationship) roundTrip(lightweight);
		assertNull(decoded.id(), "a lightweight edge must not acquire an id in transit");
		assertEquals(false, decoded.isMaterialised());
		assertEquals("BOUGHT", decoded.type());
		assertEquals(lightweight.startId(), decoded.startId());
		assertEquals(lightweight.endId(), decoded.endId());

		WireRelationship materialised = new WireRelationship(7L, "KNOWS", 1L, 2L,
			new LinkedHashMap<>(Map.of("since", 2019L)));
		WireRelationship back = (WireRelationship) roundTrip(materialised);
		assertEquals(7L, back.id());
		assertEquals(Map.of("since", 2019L), back.properties());
	}

	@Test
	public void testPath() {
		WireNode a = new WireNode(1, List.of("A"), Map.of());
		WireNode b = new WireNode(2, List.of("B"), Map.of());
		WirePath path = new WirePath(List.of(a, b), List.of(new WireRelationship(9L, "R", 1, 2, Map.of())));
		WirePath decoded = (WirePath) roundTrip(path);
		assertEquals(1, decoded.length());
		assertEquals(2, decoded.nodes().size());
		assertEquals(9L, decoded.relationships().get(0).id());
		assertEquals(1L, decoded.start().id());
		assertEquals(2L, decoded.end().id());
	}

	/**
	 * Ids are fixed 8 bytes rather than zigzag, and this is the measurement that says why.
	 */
	@Test
	public void testAnIdCostsFewerBytesFixedThanItWouldAsAVarint() {
		long realisticId = (1L << 60) + 12345;
		WireValueCodec.Writer asInteger = new WireValueCodec.Writer();
		WireValueCodec.encode(asInteger, realisticId);

		WireValueCodec.Writer asNode = new WireValueCodec.Writer();
		WireValueCodec.encode(asNode, new WireNode(realisticId, List.of(), Map.of()));

		// The integer form spends a tag plus 8 bytes of zigzag payload for a value whose top bits are all set.
		assertEquals(9, asInteger.position(), "a 2^60 id as a zigzag integer costs a tag and eight bytes");
		// The node form spends a tag, a fixed 8-byte id, and two empty counts.
		assertEquals(11, asNode.position(), "tag + fixed 8-byte id + empty label count + empty property count");
	}

	@Test
	public void testAnUnknownTypeIsRefusedRatherThanStringified() {
		// The JSON route turns an unknown object into its toString, which silently changes a value's type. On a
		// typed binary wire that is a wrong answer rather than an ugly one, so it fails loudly instead.
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
			() -> WireValueCodec.encode(new WireValueCodec.Writer(), new Object()));
		assertTrue(thrown.getMessage().contains("normalised"), thrown.getMessage());
	}

	@Test
	public void testReservedTagsAreRejected() {
		// Tags 5 to 9 are the storage codec's BYTES, TIMESTAMP, UUID, ELEMENT_REF and VECTOR_F32. None can reach a
		// query result, so meeting one means corruption or a version mismatch — both worth an error.
		for (int tag = 5; tag <= 9; tag++) {
			byte[] bytes = { (byte) tag };
			int reserved = tag;
			assertThrows(WireFormatException.class,
				() -> WireValueCodec.decode(new WireValueCodec.Reader(bytes)),
				"tag " + reserved + " must be refused rather than skipped");
		}
	}
}
