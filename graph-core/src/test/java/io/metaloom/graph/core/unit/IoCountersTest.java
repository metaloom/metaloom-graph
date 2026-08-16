package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.IoStats;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.metrics.IoCounters;

/**
 * The per-kind counters, and specifically that there is one for every kind the id encoding can express.
 *
 * <p>
 * {@code recordRead} indexes its array with a raw kind code on the hottest path in the engine and does not bound-check
 * it — bound-checking a read counter would cost more than the counter does. That makes the array's length a silent
 * contract with {@code Kind}, and it was written as a literal 8 while the encoding allows 16. This is the test that
 * turns the next ninth kind into a failure here rather than an {@code ArrayIndexOutOfBoundsException} somewhere far
 * away.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
@Tag("unit")
public class IoCountersTest {

	@Test
	public void testEveryKindCanBeCounted() {
		IoCounters counters = new IoCounters();
		for (Kind kind : Kind.values()) {
			counters.recordRead(kind.code());
			counters.recordWrite(kind.code());
		}
		for (Kind kind : Kind.values()) {
			assertEquals(1, counters.recordReads(kind), kind + " was not counted");
			assertEquals(1, counters.recordWrites(kind), kind + " was not counted");
		}
		assertEquals(Kind.values().length, counters.snapshot().totalRecordReads());
	}

	/** A snapshot taken before a newer one must subtract cleanly, including for the kinds nothing has touched. */
	@Test
	public void testSubtractingSnapshotsCoversEveryKind() {
		IoCounters counters = new IoCounters();
		counters.recordRead(Kind.NODE.code());
		IoStats before = counters.snapshot();

		counters.recordRead(Kind.NODE.code());
		counters.recordRead(Kind.VEC.code());
		IoStats delta = counters.snapshot().since(before);

		assertEquals(1, delta.recordReads(Kind.NODE));
		assertEquals(1, delta.recordReads(Kind.VEC));
		assertEquals(0, delta.recordReads(Kind.INDEX));
		assertEquals(2, delta.totalRecordReads());
	}

	@Test
	public void testResetClearsEveryKind() {
		IoCounters counters = new IoCounters();
		for (Kind kind : Kind.values()) {
			counters.recordRead(kind.code());
		}
		counters.reset();
		assertEquals(0, counters.snapshot().totalRecordReads());
	}
}
