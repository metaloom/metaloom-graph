package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Vacuum runs outside every lock while a concurrent commit records into the same chain.
 *
 * <p>
 * {@link PageVersions#record} prepends to an existing chain in place, so the chain object a vacuum read is still the
 * one in the map after the prepend. A whole-chain removal conditioned on that object alone matched anyway and took
 * the entry that had just been recorded with it — a before-image a live view needed, and the write-set evidence a
 * conflicting writer needed, both gone. Each round here makes the chain dead at the horizon and races a newer record
 * against its removal; the newer entry must survive every time.
 * </p>
 */
@Tag("unit")
public class PageVersionsRaceTest {

	private static final long PAGE = 42L;

	@Test
	@Timeout(60)
	public void testAnEntryRecordedDuringVacuumSurvivesIt() throws Exception {
		PageVersions versions = new PageVersions();
		MemorySegment page = MemorySegment.ofArray(new byte[64]);
		CyclicBarrier start = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 1; round <= 20_000; round++) {
				long dead = 2L * round;
				long live = dead + 1;
				versions.record(PAGE, dead, page, 0, 8);
				Future<?> vacuum = pool.submit(() -> {
					start.await();
					return versions.vacuum(dead);
				});
				Future<?> record = pool.submit(() -> {
					start.await();
					versions.record(PAGE, live, page, 8, 8);
					return null;
				});
				vacuum.get();
				record.get();

				PageVersions.Version head = versions.head(PAGE);
				assertNotNull(head, "round " + round + ": the entry recorded at " + live + " was vacuumed at " + dead);
				assertEquals(live, head.supersededAt(), "round " + round);
				// Settle the round so the next one starts from a dead chain again.
				versions.vacuum(live);
			}
			assertEquals(0, versions.retainedBytes(), "every entry was reclaimed exactly once");
			assertEquals(0, versions.entryCount());
		} finally {
			pool.shutdownNow();
		}
	}
}
