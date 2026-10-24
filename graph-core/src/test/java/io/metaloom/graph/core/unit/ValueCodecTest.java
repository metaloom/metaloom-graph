package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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

	/**
	 * A timestamp is stored as microseconds in a long, which spans about 292,000 years either side of 1970. Beyond that
	 * the multiplication used to wrap silently, so Instant.MAX read back as a date in year -171978.
	 */
	@Test
	public void testTimestampsOutsideTheMicrosecondRangeAreRefused() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> roundTrip(Instant.MAX));
		assertThrows(IllegalArgumentException.class, () -> roundTrip(Instant.MIN));
		Instant latest = Instant.ofEpochSecond(Math.floorDiv(Long.MAX_VALUE, 1_000_000L),
			Math.floorMod(Long.MAX_VALUE, 1_000_000L) * 1000L);
		assertEquals(latest, roundTrip(latest), "the last representable microsecond round-trips");
		assertThrows(IllegalArgumentException.class, () -> roundTrip(latest.plusNanos(1000)),
			"and one microsecond later is refused, not wrapped");
		Instant earliest = Instant.ofEpochSecond(Math.floorDiv(Long.MIN_VALUE, 1_000_000L),
			Math.floorMod(Long.MIN_VALUE, 1_000_000L) * 1000L);
		assertEquals(earliest, roundTrip(earliest));
		assertThrows(IllegalArgumentException.class, () -> roundTrip(earliest.minusNanos(1000)));
		Instant beforeEpoch = Instant.parse("1969-12-31T23:59:59.999999Z");
		assertEquals(beforeEpoch, roundTrip(beforeEpoch));
	}

	/** A lone surrogate has no UTF-8 form; the JDK writes '?', so two distinct strings would read back equal. */
	@Test
	public void testALoneSurrogateIsRefused() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> roundTrip("k\uD800"));
		assertThrows(IllegalArgumentException.class, () -> roundTrip("\uDC00k"));
		assertEquals("k\uD83D\uDE00", roundTrip("k\uD83D\uDE00"), "a proper pair is fine");
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
