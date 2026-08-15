package io.metaloom.graph.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The COMPLETE frame's payload: nine fixed fields, and what happens when fewer arrive.
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
@Tag("unit")
public class QuerySummaryTest {

	private static final QuerySummary SAMPLE = new QuerySummary(12, 3_456, 1, 2, 3, 4, 5, 6, 7);

	@Test
	public void testItRoundTrips() {
		WireValueCodec.Writer writer = new WireValueCodec.Writer(QuerySummary.BYTES);
		SAMPLE.write(writer);

		byte[] bytes = writer.toArray();
		assertEquals(QuerySummary.BYTES, bytes.length, "nine fixed fields, no framing of their own");
		assertEquals(SAMPLE, QuerySummary.read(new WireValueCodec.Reader(bytes)));
	}

	@Test
	public void testAReadReportsNoUpdatesAndAWriteReportsSome() {
		assertFalse(new QuerySummary(100, 5, 0, 0, 0, 0, 0, 0, 0).containsUpdates(),
			"a hundred rows and nothing changed is a read");
		assertTrue(new QuerySummary(0, 5, 0, 0, 0, 0, 1, 0, 0).containsUpdates(),
			"no rows and one property set is a write, and the counters are its whole result");
	}

	/**
	 * A truncated payload throws rather than reading past the buffer.
	 *
	 * <p>
	 * A frame is length-prefixed, so a short one is a peer that is wrong or hostile rather than a partial read. Every
	 * offset is tried because a fixed-layout decoder that checks its bounds once at the start and then runs is the
	 * shape this repository has already found a defect in.
	 * </p>
	 */
	@Test
	public void testEveryTruncationIsRefused() {
		WireValueCodec.Writer writer = new WireValueCodec.Writer(QuerySummary.BYTES);
		SAMPLE.write(writer);
		byte[] full = writer.toArray();

		for (int length = 0; length < full.length; length++) {
			byte[] truncated = Arrays.copyOf(full, length);
			int at = length;
			assertThrows(WireFormatException.class,
				() -> QuerySummary.read(new WireValueCodec.Reader(truncated)),
				"a payload of " + at + " bytes must be refused, not read past");
		}
	}

	@Test
	public void testTrailingBytesAreIgnoredRatherThanRefused() {
		WireValueCodec.Writer writer = new WireValueCodec.Writer(QuerySummary.BYTES);
		SAMPLE.write(writer);
		byte[] padded = Arrays.copyOf(writer.toArray(), QuerySummary.BYTES + 16);

		// Not a compatibility promise — the version is bumped rather than extended — but a decoder that read its nine
		// fields and then failed on bytes it never asked for would be refusing something harmless.
		assertEquals(SAMPLE, QuerySummary.read(new WireValueCodec.Reader(padded)));
	}
}
