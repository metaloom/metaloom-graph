package io.metaloom.graph.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The property index key, at the level the tree sees it.
 * <p>
 * One property carries every assertion here: <b>the key depends on the content of the value and on nothing else</b>. A
 * value that hashes differently on two equal instances is not a slow index, it is an index that answers nothing and
 * leaks the entries it wrote — which is exactly what a {@code float[]} property did while it fell through to
 * {@code Object::hashCode}.
 *
 * @see <a href="../../../../../../../../spec/INDEX_TASKS.md">spec/INDEX_TASKS.md</a> IDX-1
 */
@Tag("unit")
public class IndexKeyTest {

	@Test
	public void testEqualVectorsHashTheSame() {
		float[] a = { 1f, 2f, 3f };
		float[] b = { 1f, 2f, 3f };
		assertNotSame(a, b, "the two arrays must be distinct instances, or this test proves nothing");

		assertEquals(IndexCatalog.valueHash(a), IndexCatalog.valueHash(b));
		assertEquals(IndexCatalog.propertyKey(7, a), IndexCatalog.propertyKey(7, b));
	}

	@Test
	public void testDifferentVectorsHashDifferently() {
		// Not a contract — a collision would be safe, because the caller verifies every candidate — but a hash that
		// ignored the content would pass the test above and fail this one.
		assertNotEquals(IndexCatalog.valueHash(new float[] { 1f, 2f, 3f }),
			IndexCatalog.valueHash(new float[] { 3f, 2f, 1f }));
		assertNotEquals(IndexCatalog.valueHash(new float[] { 1f, 2f, 3f }),
			IndexCatalog.valueHash(new float[] { 1f, 2f, 3f, 0f }));
	}

	/** A list's hash delegates to its elements, so a list holding an array inherited the identity hash. */
	@Test
	public void testAListContainingAVectorHashesByContent() {
		List<Object> a = List.of("x", new float[] { 1f, 2f }, 3L);
		List<Object> b = List.of("x", new float[] { 1f, 2f }, 3L);
		assertEquals(IndexCatalog.valueHash(a), IndexCatalog.valueHash(b));
		assertNotEquals(IndexCatalog.valueHash(a), IndexCatalog.valueHash(List.of("x", new float[] { 1f, 9f }, 3L)));
	}

	@Test
	public void testAListOfBytesArraysHashesByContent() {
		assertEquals(IndexCatalog.valueHash(List.of(new byte[] { 1, 2 })),
			IndexCatalog.valueHash(List.of(new byte[] { 1, 2 })));
		assertEquals(IndexCatalog.valueHash(List.of()), IndexCatalog.valueHash(List.of()));
	}

	/** The fallback for an array shape no branch names — the next vector type must not reintroduce the defect. */
	@Test
	public void testAnUnnamedArrayShapeStillHashesByContent() {
		assertEquals(IndexCatalog.valueHash(new double[] { 1.5, 2.5 }),
			IndexCatalog.valueHash(new double[] { 1.5, 2.5 }));
		assertEquals(IndexCatalog.valueHash(new int[] { 1, 2 }), IndexCatalog.valueHash(new int[] { 1, 2 }));
		assertEquals(IndexCatalog.valueHash(new String[] { "a", "b" }),
			IndexCatalog.valueHash(new String[] { "a", "b" }));
	}

	/** The normalisation the numeric types already had, kept honest while the switch grows. */
	@Test
	public void testTheNumericWidthsAgree() {
		assertEquals(IndexCatalog.valueHash((byte) 1), IndexCatalog.valueHash(1L));
		assertEquals(IndexCatalog.valueHash((short) 1), IndexCatalog.valueHash(1L));
		assertEquals(IndexCatalog.valueHash(1), IndexCatalog.valueHash(1L));
		assertEquals(IndexCatalog.valueHash(1.5f), IndexCatalog.valueHash(1.5d));
	}

	@Test
	public void testTheOtherValueTypesHashByContent() {
		assertEquals(IndexCatalog.valueHash("berlin"), IndexCatalog.valueHash(new StringBuilder("berlin").toString()));
		assertEquals(IndexCatalog.valueHash(new byte[] { 1, 2 }), IndexCatalog.valueHash(new byte[] { 1, 2 }));
		assertEquals(IndexCatalog.valueHash(Instant.ofEpochSecond(1, 2000)),
			IndexCatalog.valueHash(Instant.ofEpochSecond(1, 2000)));
		UUID uuid = UUID.fromString("2f8a5a3e-3e2a-4d0f-9b31-3f9d4b8c1a77");
		assertEquals(IndexCatalog.valueHash(uuid), IndexCatalog.valueHash(new UUID(uuid.getMostSignificantBits(),
			uuid.getLeastSignificantBits())));
		assertEquals(0, IndexCatalog.valueHash(null));
	}

	/** The key id occupies the high half, so two keys never share a prefix however the values hash. */
	@Test
	public void testTheKeyIdIsInTheHighBits() {
		float[] vector = { 1f, 2f, 3f };
		assertNotEquals(IndexCatalog.propertyKey(7, vector), IndexCatalog.propertyKey(8, vector));
		assertEquals(7L, IndexCatalog.propertyKey(7, vector) >>> 32);
	}
}
