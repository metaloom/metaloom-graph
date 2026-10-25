package io.metaloom.graph.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link LongObjectMap} against the {@code LinkedHashMap<Long, V>} it replaced on the write path: same lookups, same
 * iteration order — the commit logs pages in that order — and the same "replacing keeps the position" rule.
 */
@Tag("unit")
public class LongObjectMapTest {

	@Test
	public void testItBehavesLikeALinkedHashMapUnderRandomUse() {
		Random random = new Random(20261025L);
		for (int round = 0; round < 50; round++) {
			LongObjectMap<String> map = new LongObjectMap<>();
			Map<Long, String> reference = new LinkedHashMap<>();
			int operations = 1 + random.nextInt(5000);
			// A narrow key range forces replacements; the page-key shape (kind and segment in the high bits) is what
			// the overlay actually stores, so both are exercised.
			long highBits = random.nextBoolean() ? 0 : ((long) (1 + random.nextInt(7)) << 56) | (3L << 32);
			int range = 1 + random.nextInt(round % 2 == 0 ? 64 : 100_000);
			for (int i = 0; i < operations; i++) {
				long key = highBits | random.nextInt(range);
				if (random.nextInt(3) == 0) {
					assertEquals(reference.get(key), map.get(key), "get " + key);
				} else {
					String value = "v" + i;
					assertEquals(reference.put(key, value), map.put(key, value), "put " + key);
				}
			}
			assertEquals(reference.size(), map.size());
			List<Long> keys = new ArrayList<>(reference.keySet());
			for (int i = 0; i < keys.size(); i++) {
				assertEquals(keys.get(i).longValue(), map.keyAt(i), "key at " + i);
				assertEquals(reference.get(keys.get(i)), map.valueAt(i), "value at " + i);
			}
			map.clear();
			assertTrue(map.isEmpty());
			assertNull(map.get(keys.isEmpty() ? 0 : keys.get(0)));
		}
	}

	@Test
	public void testKeysThatDifferOnlyInTheHighBitsAreDistinct() {
		LongObjectMap<String> map = new LongObjectMap<>();
		for (long kind = 0; kind < 8; kind++) {
			for (long segment = 0; segment < 4; segment++) {
				map.put((kind << 56) | (segment << 32) | 7, kind + "/" + segment);
			}
		}
		assertEquals(32, map.size());
		assertEquals("5/2", map.get((5L << 56) | (2L << 32) | 7));
		assertEquals("0/0", map.get(7));
		assertNull(map.get((1L << 56) | 8));
	}

	@Test
	public void testIndexingPastTheEndFails() {
		LongObjectMap<String> map = new LongObjectMap<>();
		map.put(1, "a");
		assertThrows(IndexOutOfBoundsException.class, () -> map.keyAt(1));
		assertThrows(IndexOutOfBoundsException.class, () -> map.valueAt(1));
	}
}
