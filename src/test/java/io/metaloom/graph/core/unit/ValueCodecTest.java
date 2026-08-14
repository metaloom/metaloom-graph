package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.store.prop.ValueCodec;

/**
 * Value encoding without a store behind it. The integer size classes are the subtle part: zig-zag output is unsigned,
 * and treating it as signed silently truncates large values.
 */
@Tag("unit")
public class ValueCodecTest {

	private Object roundTrip(Object value) throws Exception {
		ValueCodec.Writer writer = new ValueCodec.Writer();
		ValueCodec.encode(writer, value, null);
		byte[] encoded = writer.toArray();
		return ValueCodec.decode(new ValueCodec.Reader(encoded, 0, encoded.length), null, true, null);
	}

	private int encodedSize(Object value) throws Exception {
		ValueCodec.Writer writer = new ValueCodec.Writer();
		ValueCodec.encode(writer, value, null);
		return writer.position();
	}

	@Test
	public void testIntegerSizeClasses() throws Exception {
		long[] values = { 0, 1, -1, 127, 128, -128, 255, 256, 65535, 65536, Integer.MAX_VALUE, Integer.MIN_VALUE,
			Long.MAX_VALUE, Long.MIN_VALUE };
		for (long value : values) {
			assertEquals(value, roundTrip(value), "round trip of " + value);
		}
		assertEquals(2, encodedSize(0L), "a small integer costs a tag plus one byte");
		assertEquals(2, encodedSize(-1L), "zig-zag keeps small negatives small");
		assertEquals(9, encodedSize(Long.MAX_VALUE));
	}

	@Test
	public void testRandomLongs() throws Exception {
		Random random = new Random(31337L);
		for (int i = 0; i < 50_000; i++) {
			long value = random.nextLong() >> random.nextInt(64);
			assertEquals(value, roundTrip(value), "failed for " + value);
		}
	}

	@Test
	public void testBooleanCostsOnlyItsTag() throws Exception {
		assertEquals(1, encodedSize(true));
		assertEquals(1, encodedSize(false));
		assertEquals(true, roundTrip(true));
		assertEquals(false, roundTrip(false));
	}

	@Test
	public void testStringsAndBytes() throws Exception {
		assertEquals("", roundTrip(""));
		assertEquals("hello", roundTrip("hello"));
		assertEquals("König ✓", roundTrip("König ✓"), "UTF-8 must survive intact");
		assertEquals(2 + 5, encodedSize("hello"), "tag, length, five bytes");
	}

	@Test
	public void testFloatingPoint() throws Exception {
		assertEquals(1.5f, roundTrip(1.5f));
		assertEquals(Math.PI, roundTrip(Math.PI));
		assertEquals(5, encodedSize(1.5f));
		assertEquals(9, encodedSize(Math.PI));
	}

	@Test
	public void testNullAndLists() throws Exception {
		assertEquals(null, roundTrip(null));
		assertEquals(1, encodedSize(null));
		assertEquals(java.util.List.of(1L, 2L, 3L), roundTrip(java.util.List.of(1, 2, 3)));
		assertEquals(java.util.List.of("a", "b"), roundTrip(java.util.List.of("a", "b")));
	}

	@Test
	public void testTypicalNodeBlockIsTiny() throws Exception {
		// name plus age, the example from spec/PROPERTY_STORAGE.md section 2.1.
		int size = 3 + encodedSize("Wes Anderson") + 3 + encodedSize(42);
		assertTrue(size <= 40, "Expected the example block payload under 40 bytes, got " + size);
	}

	// ---------------------------------------------------------------- compaction support

	/**
	 * Element ids embedded in a payload are the reference class a compaction sweep is most likely to forget: they are
	 * the only ones that do not live in a typed record field. The rewrite must find every one of them, must not disturb
	 * anything else, and must not change the payload length, since the block keeps its cells.
	 */
	@Test
	public void testRemapRewritesEveryEmbeddedId() throws Exception {
		ValueCodec.Writer writer = new ValueCodec.Writer();
		writer.u24(1);
		ValueCodec.encode(writer, "short string", null);
		writer.u24(2);
		ValueCodec.encode(writer, new ValueCodec.ElementRef(0x1111L), null);
		writer.u24(3);
		ValueCodec.encode(writer, 123456789L, null);
		writer.u24(4);
		ValueCodec.encode(writer, java.util.List.of(new ValueCodec.ElementRef(0x2222L), Boolean.TRUE,
			new ValueCodec.ElementRef(0x3333L)), null);
		writer.u24(5);
		ValueCodec.encode(writer, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), null);
		byte[] payload = writer.toArray();
		int length = payload.length;

		assertTrue(ValueCodec.remapIds(payload, 0, payload.length,
			id -> id == 0x1111L ? 0xAAAAL : id == 0x2222L ? 0xBBBBL : id == 0x3333L ? 0xCCCCL : id));
		assertEquals(length, payload.length, "a rewrite must not change the payload length");

		ValueCodec.Reader reader = new ValueCodec.Reader(payload, 0, payload.length);
		reader.u24();
		assertEquals("short string", ValueCodec.decode(reader, null, true, null));
		reader.u24();
		assertEquals(new ValueCodec.ElementRef(0xAAAAL), ValueCodec.decode(reader, null, true, null));
		reader.u24();
		assertEquals(123456789L, ValueCodec.decode(reader, null, true, null));
		reader.u24();
		assertEquals(java.util.List.of(new ValueCodec.ElementRef(0xBBBBL), Boolean.TRUE,
			new ValueCodec.ElementRef(0xCCCCL)), ValueCodec.decode(reader, null, true, null),
			"ids nested inside a list must be rewritten too");
		reader.u24();
		assertEquals(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
			ValueCodec.decode(reader, null, true, null), "a UUID is not an element id and must be left alone");
	}

	@Test
	public void testRemapReportsWhenNothingMoved() throws Exception {
		ValueCodec.Writer writer = new ValueCodec.Writer();
		writer.u24(1);
		ValueCodec.encode(writer, new ValueCodec.ElementRef(0x1234L), null);
		byte[] payload = writer.toArray();
		byte[] expected = payload.clone();

		assertFalse(ValueCodec.remapIds(payload, 0, payload.length, id -> id),
			"an identity rewrite must report no change, so the caller does not dirty the page");
		assertArrayEquals(expected, payload);
	}
}
