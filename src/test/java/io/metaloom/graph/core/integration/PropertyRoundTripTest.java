package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.PropertyTypeException;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Typed property storage: every supported value type, the blob escape, and the absent versus null distinction.
 *
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 8
 */
@Tag("unit")
public class PropertyRoundTripTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testEveryValueType() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		UUID uuid = UUID.randomUUID();
		byte[] bytes = { 1, 2, 3, (byte) 250 };
		float[] vector = new float[128];
		for (int i = 0; i < vector.length; i++) {
			vector[i] = i * 0.25f;
		}

		long id;
		try (GraphStore store = stores.open()) {
			id = store.createNode("Everything", Properties.of()
				.set("bool_true", true)
				.set("bool_false", false)
				.set("int_small", 42)
				.set("int_negative", -1)
				.set("int_large", Long.MAX_VALUE)
				.set("float", 1.5f)
				.set("double", Math.PI)
				.set("string", "Wes Anderson")
				.set("empty_string", "")
				.set("bytes", bytes)
				.set("timestamp", now)
				.set("uuid", uuid)
				.set("vector", vector)
				.set("list", List.of(1, 2, 3))
				.setNull("explicit_null"));
			store.checkpoint();
		}

		try (GraphStore store = stores.open()) {
			Properties props = store.nodeProperties(id);
			assertTrue(props.getBoolean("bool_true"));
			assertFalse(props.getBoolean("bool_false"));
			assertEquals(42, props.getInt("int_small"));
			assertEquals(-1, props.getInt("int_negative"));
			assertEquals(Long.MAX_VALUE, props.getLong("int_large"));
			assertEquals(1.5, props.getDouble("float"), 0.0001);
			assertEquals(Math.PI, props.getDouble("double"), 0.0000001);
			assertEquals("Wes Anderson", props.getString("string"));
			assertEquals("", props.getString("empty_string"));
			assertArrayEquals(bytes, props.getBytes("bytes"));
			assertEquals(now, props.getInstant("timestamp"));
			assertEquals(uuid, props.getUuid("uuid"));
			assertArrayEquals(vector, props.getVector("vector"), 0.0f);
			assertEquals(List.of(1L, 2L, 3L), props.get("list"));

			assertTrue(props.has("explicit_null"));
			assertTrue(props.isNull("explicit_null"));
			assertFalse(props.has("never_set"));
			assertNull(props.get("never_set"));
		}
	}

	@Test
	public void testLargeValuesEscapeToBlobs() throws Exception {
		Random random = new Random(11L);
		byte[] big = new byte[200_000];
		random.nextBytes(big);
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			text.append("long text ").append(i).append(' ');
		}

		long id;
		try (GraphStore store = stores.open()) {
			id = store.createNode("Big", Properties.of()
				.set("blob", big)
				.set("text", text.toString()));
			assertTrue(store.stats().blobExtentCount() > 1, "A 200 KB value must span several extents");
			store.checkpoint();
		}

		try (GraphStore store = stores.open()) {
			Properties props = store.nodeProperties(id);
			assertArrayEquals(big, props.getBytes("blob"));
			assertEquals(text.toString(), props.getString("text"));
			store.verify();
		}
	}

	@Test
	public void testTypeMismatchIsRejectedRatherThanCoerced() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("N", Properties.of().set("count", 7));
			Properties props = store.nodeProperties(id);
			assertThrows(PropertyTypeException.class, () -> props.getString("count"));
			assertThrows(PropertyTypeException.class, () -> props.getBoolean("count"));
		}
	}

	@Test
	public void testSinglePropertyReadDoesNotMaterialiseTheBlock() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("N", Properties.of()
				.set("aaa", 1)
				.set("mmm", "middle")
				.set("zzz", 3));
			assertEquals("middle", store.nodeProperty(id, "mmm"));
			assertEquals(1L, store.nodeProperty(id, "aaa"));
			assertEquals(3L, store.nodeProperty(id, "zzz"));
			assertNull(store.nodeProperty(id, "absent"));
		}
	}

	@Test
	public void testPropertyBlockGrowsAndShrinksWithoutLeaking() throws Exception {
		try (GraphStore store = stores.open()) {
			long id = store.createNode("N");
			long baseline = store.stats().propertyCellCount();

			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int round = 0; round < 20; round++) {
					Properties props = Properties.of();
					for (int i = 0; i <= round; i++) {
						props.set("k" + i, "value " + i);
					}
					store.setNodeProperties(id, props);
				}
				store.setNodeProperties(id, Properties.of());
				txn.commit();
			}
			assertEquals(baseline, store.stats().propertyCellCount(),
				"Every intermediate block must have been freed");
			store.verify();
		}
	}

	@Test
	public void testRelationshipProperties() throws Exception {
		try (GraphStore store = stores.open()) {
			long a = store.createNode("A");
			long b = store.createNode("B");
			long rel = store.createRelationship(a, "LINK", b, Properties.of().set("weight", 0.75));
			assertEquals(0.75, store.relationshipProperties(rel).getDouble("weight"), 0.0001);

			store.setRelationshipProperties(rel, Properties.of().set("weight", 0.25).set("note", "changed"));
			assertEquals(0.25, store.relationshipProperties(rel).getDouble("weight"), 0.0001);
			assertEquals("changed", store.relationshipProperties(rel).getString("note"));
			store.verify();
		}
	}
}
