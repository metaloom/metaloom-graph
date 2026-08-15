package io.metaloom.graph.protocol;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sun.management.ThreadMXBean;

/**
 * The decoder against input nobody meant it to see.
 *
 * <h2>Why this and not only a differential test</h2>
 *
 * <p>
 * spec/TESTING.md records the finding plainly: a differential test compares two implementations and therefore finds
 * only the mistakes they do not share. This project's two edge codecs were written from one format description by one
 * person, so they shared their bugs, and it took {@code cargo fuzz} — an oracle that knew nothing about either, only
 * that decoding must not panic and must not write past a buffer — to find two of them within seconds.
 * </p>
 *
 * <p>
 * A frame arriving on a socket is the same situation with a worse threat model, because the port has no
 * authentication. So the oracle here knows nothing about the wire format either. It asserts four properties and no
 * expected values:
 * </p>
 *
 * <ol>
 * <li>every input either decodes or raises {@link WireFormatException} — never {@code OutOfMemoryError}, never
 * {@code StackOverflowError}, never an index or cast exception escaping from the parser's internals;
 * <li>it always terminates;
 * <li><b>allocation stays proportional to the input</b>, which is the mechanical form of "never allocate from an
 * unvalidated length field" — the shape of seven defects found in this project's storage parsers;
 * <li>nesting is bounded, so a small input cannot exhaust the stack.
 * </ol>
 *
 * <p>
 * It earned its place: run against the codec for the first time, it found three real defects within seconds — a bounds
 * check written {@code pos + count > limit} that overflows for a count near {@code Integer.MAX_VALUE} and let a 2 GiB
 * allocation through, and two {@code ClassCastException}s escaping the decoder on corrupt input.
 * </p>
 */
@Tag("fuzz")
public class WireDecoderHostileInputTest {

	private static final long SEED = 20260815L;

	/**
	 * Decodes and reports only whether it failed acceptably.
	 *
	 * @return the bytes allocated on this thread while decoding
	 */
	private static long decodeSafely(byte[] input, String what) {
		ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long before = threads.getCurrentThreadAllocatedBytes();
		try {
			WireValueCodec.decode(new WireValueCodec.Reader(input));
		} catch (WireFormatException expected) {
			// The one acceptable failure.
		} catch (StackOverflowError e) {
			fail("StackOverflowError on " + what + "; the depth cap did not hold");
		} catch (OutOfMemoryError e) {
			fail("OutOfMemoryError on " + what + "; a length field was trusted");
		} catch (RuntimeException e) {
			fail("A " + e.getClass().getSimpleName() + " escaped the decoder on " + what
				+ "; every rejection must be a WireFormatException: " + e);
		}
		return threads.getCurrentThreadAllocatedBytes() - before;
	}

	@Test
	public void testRandomBytesNeverEscapeTheDecoder() {
		Random random = new Random(SEED);
		System.out.println("WireDecoderHostileInputTest seed=" + SEED);
		for (int i = 0; i < 20_000; i++) {
			byte[] input = new byte[random.nextInt(64)];
			random.nextBytes(input);
			decodeSafely(input, "random input " + i);
		}
	}

	@Test
	public void testEveryTruncationOfAValidValue() {
		// A frame arrives in whatever pieces TCP feels like, and a decoder that reads one byte past the end on a
		// short read is the classic way that becomes someone else's data.
		byte[] valid = encodeRichValue();
		for (int length = 0; length < valid.length; length++) {
			byte[] truncated = new byte[length];
			System.arraycopy(valid, 0, truncated, 0, length);
			decodeSafely(truncated, "a valid value truncated to " + length + " of " + valid.length + " bytes");
		}
	}

	@Test
	public void testEverySingleByteCorruption() {
		byte[] valid = encodeRichValue();
		for (int offset = 0; offset < valid.length; offset++) {
			for (int flip = 1; flip < 256; flip += 37) {
				byte[] corrupt = valid.clone();
				corrupt[offset] = (byte) (corrupt[offset] ^ flip);
				decodeSafely(corrupt, "byte " + offset + " xor " + flip);
			}
		}
	}

	/**
	 * The one that matters most: a count that would drive a huge allocation, inside a tiny frame.
	 */
	@Test
	public void testALengthFieldIsNeverTrusted() {
		// A list claiming Integer.MAX_VALUE elements in a twelve-byte input.
		WireValueCodec.Writer writer = new WireValueCodec.Writer();
		writer.u8((0 << 5) | WireValueCodec.T_LIST);
		writer.varint(Integer.MAX_VALUE);
		byte[] input = writer.toArray();

		long allocated = decodeSafely(input, "a list claiming 2^31-1 elements in " + input.length + " bytes");
		assertTrue(allocated < 64 * 1024,
			"decoding a " + input.length + " byte input allocated " + allocated
				+ " bytes; the element count was trusted before it was checked against the input");

		// And a string claiming four gigabytes.
		WireValueCodec.Writer huge = new WireValueCodec.Writer();
		huge.u8((1 << 5) | WireValueCodec.T_STRING);
		huge.i32(Integer.MAX_VALUE);
		long stringAllocated = decodeSafely(huge.toArray(), "a string claiming 2GiB");
		assertTrue(stringAllocated < 64 * 1024,
			"a 5 byte input allocated " + stringAllocated + " bytes for a claimed 2 GiB string");
	}

	@Test
	public void testAllocationStaysProportionalToInput() {
		Random random = new Random(SEED + 1);
		for (int i = 0; i < 2000; i++) {
			byte[] input = new byte[1 + random.nextInt(512)];
			random.nextBytes(input);
			long allocated = decodeSafely(input, "proportionality sample " + i);
			// Generous: object headers and boxing make a tight bound meaningless. What it catches is the
			// catastrophic case, which is the one that matters.
			assertTrue(allocated <= 64 * 1024 + input.length * 64L,
				"decoding " + input.length + " bytes allocated " + allocated + " bytes");
		}
	}

	@Test
	public void testDeepNestingIsRefusedRatherThanOverflowingTheStack() {
		// A list of a list of a list, a million deep, in two bytes per level. The storage codec caps a list's
		// allocation but recurses without a depth limit, so the equivalent input is a StackOverflowError there.
		WireValueCodec.Writer writer = new WireValueCodec.Writer();
		for (int i = 0; i < 1_000_000; i++) {
			writer.u8((0 << 5) | WireValueCodec.T_LIST);
			writer.varint(1);
		}
		writer.u8(WireValueCodec.T_NULL);
		decodeSafely(writer.toArray(), "a list nested one million deep");
	}

	@Test
	public void testFrameLengthsOutsideTheCap() {
		for (int length : new int[] { -1, Integer.MIN_VALUE, Integer.MAX_VALUE, Frames.MAX_FRAME_BYTES + 1 }) {
			try {
				Frames.checkLength(length);
				fail("A frame length of " + length + " must be refused before anything is allocated for it");
			} catch (WireFormatException expected) {
				// Correct.
			}
		}
	}

	/** Every branch of the encoder in one value, so corruption and truncation reach all of them. */
	private static byte[] encodeRichValue() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("name", "Heathrow");
		properties.put("code", "LHR");
		properties.put("runways", 4L);
		properties.put("open", true);
		properties.put("latitude", 51.47d);
		properties.put("closed", null);
		properties.put("tags", List.of("intl", "hub"));

		WireNode from = new WireNode(1L << 60, List.of("Airport", "Hub"), properties);
		WireNode to = new WireNode((1L << 60) + 1, List.of("Airport"), Map.of("code", "CDG"));
		List<WireRelationship> rels = new ArrayList<>();
		rels.add(new WireRelationship(42L, "ROUTE_TO", from.id(), to.id(),
			new LinkedHashMap<>(Map.of("distanceKm", 350.3d))));

		WireValueCodec.Writer writer = new WireValueCodec.Writer();
		WireValueCodec.encode(writer, new WirePath(List.of(from, to), rels));
		return writer.toArray();
	}
}
