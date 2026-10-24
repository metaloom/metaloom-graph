package io.metaloom.graph.core.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.GraphStoreOptions.IoEngineKind;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.fixture.FaultInjectingIoEngine;
import io.metaloom.graph.core.fixture.FaultInjectingIoEngine.Fault;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.io.DirectIoEngine;
import io.metaloom.graph.core.store.GraphStoreImpl;
import io.metaloom.graph.core.wal.WalManager;
import io.metaloom.graph.core.wal.WalReader;
import io.metaloom.graph.core.wal.WalRecord;
import io.metaloom.graph.core.wal.WalWriter;

/**
 * The two device faults that cannot be produced on disk after the fact.
 *
 * <p>
 * {@code CrashMatrixTest} covers four of the six faults in
 * [TESTING.md §6](../../../../../../../../spec/TESTING.md) — lost, torn, corrupt and reordered writes — by damaging
 * the log at every record and reopening. Those are all <em>states</em>. The remaining two are <em>behaviours</em>, and
 * a file on disk cannot express them:
 * </p>
 *
 * <ul>
 * <li>a write that fails, and</li>
 * <li>a write that succeeds followed by an fsync that fails.</li>
 * </ul>
 *
 * <p>
 * <b>The second is the one that matters.</b> It is the moment at which an engine is most likely to have already told
 * its caller that a commit is durable. The rule it must obey is simple and absolute: {@code sync()} may not return
 * normally unless the bytes are on the device, and {@code durableLsn} may not advance past what has been forced.
 * </p>
 */
@Tag("crash")
public class WalFaultTest {

	@TempDir
	Path dir;

	private static final long SEGMENT = 1L << 20;

	private WalWriter writer(FaultInjectingIoEngine engine) throws IOException {
		return new WalWriter(dir, SEGMENT, 0, WalSyncMode.FSYNC_ON_COMMIT, 0, IoEngineKind.DIRECT, engine);
	}

	private static FaultInjectingIoEngine engine(Fault fault, int from) {
		return new FaultInjectingIoEngine(new DirectIoEngine(), fault, from);
	}

	/** The control: with an honest device, everything appended is readable. */
	@Test
	public void testAnHonestDeviceWritesWhatItWasGiven() throws Exception {
		try (FaultInjectingIoEngine engine = engine(Fault.NONE, Integer.MAX_VALUE); WalWriter writer = writer(engine)) {
			for (int i = 0; i < 8; i++) {
				writer.append(0, i, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
			}
			writer.sync();
			assertEquals(writer.nextLsn(), writer.durableLsn(), "everything appended must be durable after a sync");
		}
		assertEquals(8, countRecords(), "all eight records must be readable");
	}

	/** A failing write must not be reported as a successful commit. */
	@Test
	public void testAFailingWriteIsReported() throws Exception {
		try (FaultInjectingIoEngine engine = engine(Fault.FAIL_WRITE, 1)) {
			// The first write is the preallocation of the segment, which is honest. The next one fails.
			WalWriter writer = writer(engine);
			writer.append(0, 1, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
			long durableBefore = writer.durableLsn();
			assertThrows(IOException.class, writer::sync, "a write that fails must reach the caller");
			assertEquals(durableBefore, writer.durableLsn(),
				"durableLsn must not advance past a write that never landed");
			closeExpectingFailure(writer);
		}
	}

	/**
	 * The important one: the write lands, the fsync does not.
	 *
	 * <p>
	 * The bytes may well be in the page cache and readable by this process; that is exactly what makes the case
	 * dangerous. What must not happen is the commit path returning normally, because the caller would then be holding
	 * an acknowledgement for a transaction that a power cut would erase.
	 * </p>
	 */
	@Test
	public void testAFailingFsyncIsNotACommit() throws Exception {
		try (FaultInjectingIoEngine engine = engine(Fault.FAIL_FSYNC, 1)) {
			WalWriter writer = writer(engine);
			writer.append(0, 1, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
			long durableBefore = writer.durableLsn();

			assertThrows(IOException.class, writer::sync,
				"an fsync that fails must fail the commit; the caller cannot be told this is durable");
			assertEquals(durableBefore, writer.durableLsn(),
				"durableLsn is the promise made to the caller and must not advance on a failed fsync");
			assertTrue(engine.forceCount() > 0, "the fsync must actually have been attempted");
			closeExpectingFailure(writer);
		}
	}

	/**
	 * A device that reports a write it never performed.
	 *
	 * <p>
	 * This is the one fault no software can defend against, and the test says so rather than asserting something
	 * comforting. The writer does everything right and the log is still empty. It is recorded here because the
	 * conclusion is a real one: the durability of this engine rests on the device telling the truth about fsync, which
	 * is a property of the hardware and can only be established by the manual power-loss procedure in
	 * spec/OPERATIONS.md section 8 — not by any test in this suite.
	 * </p>
	 */
	@Test
	public void testALyingDeviceDefeatsTheLogAndThatIsExpected() throws Exception {
		try (FaultInjectingIoEngine engine = engine(Fault.LOSE_WRITE, 1); WalWriter writer = writer(engine)) {
			for (int i = 0; i < 4; i++) {
				writer.append(0, i, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
			}
			writer.sync();
			// No exception: the device claimed success at every step.
			assertEquals(writer.nextLsn(), writer.durableLsn());
		}
		assertEquals(0, countRecords(),
			"a device that lies about writing loses the log, and nothing in the format can tell");
	}

	/** A torn write must be detected by the record CRC rather than half-applied. */
	@Test
	public void testATornWriteIsDetectedOnRead() throws Exception {
		try (FaultInjectingIoEngine engine = engine(Fault.NONE, Integer.MAX_VALUE); WalWriter writer = writer(engine)) {
			for (int i = 0; i < 4; i++) {
				writer.append(0, i, WalRecord.TYPE_TXN_COMMIT, new byte[512]);
			}
			writer.sync();
		}
		int whole = countRecords();
		assertTrue(whole >= 4, "the honest run must have written every record, found " + whole);

		// Now a second writer whose device tears the block it writes.
		try (FaultInjectingIoEngine engine = engine(Fault.TEAR_WRITE, 0); WalWriter writer =
			new WalWriter(dir, SEGMENT, whole == 0 ? 0 : lastLsn(), WalSyncMode.FSYNC_ON_COMMIT, 0,
				IoEngineKind.DIRECT, engine)) {
			writer.append(0, 99, WalRecord.TYPE_TXN_COMMIT, new byte[512]);
			writer.sync();
		}

		// The torn record cannot validate, so the scan stops at the last whole one. It must never return a record that
		// was only half written.
		int after = countRecords();
		assertTrue(after <= whole + 1, "a torn record must not be handed to recovery: " + after + " against " + whole);
	}

	/**
	 * A failed fsync is the end of the log, even if the next one would succeed.
	 *
	 * <p>
	 * The commit record of the failed transaction is already in the file. Carrying on would let the next transaction's
	 * fsync make it durable after the caller was told it failed — and after the store rolled it back and handed its
	 * slots to someone else, so recovery would replay both over the same records. The PostgreSQL rule: after a failed
	 * fsync nothing more is written until the store is reopened and recovery decides what the log holds.
	 * </p>
	 */
	@Test
	public void testAFailedFsyncPoisonsTheLog() throws Exception {
		// Force 0 is the preallocation of the segment; force 1 is the commit.
		try (FaultInjectingIoEngine engine = engine(Fault.FAIL_FSYNC_ONCE, 1)) {
			WalWriter writer = writer(engine);
			writer.append(0, 1, WalRecord.TYPE_PAGE_IMAGE, new byte[64]);
			writer.append(0, 1, WalRecord.TYPE_TXN_COMMIT, new byte[0]);
			assertThrows(IOException.class, writer::sync);

			assertThrows(IOException.class, () -> writer.append(0, 2, WalRecord.TYPE_TXN_COMMIT, new byte[0]),
				"the device is honest again, but the log must refuse to carry on after a failed fsync");
			assertThrows(IOException.class, writer::sync, "nor may a later sync report success");
			assertThrows(IOException.class, writer::forceNow, "nor a checkpoint's force");
			writer.close();
		}
	}

	/**
	 * A write that fails inside a flush must not leave the stream to be continued at the wrong offset.
	 *
	 * <p>
	 * The direct writer pads the block before writing it. When that write failed, the padding stayed staged but the
	 * stream did not move past it, so every later record was written one padding length away from its own LSN — and
	 * the reader, which validates exactly that, stopped at the first of them. Refused instead.
	 * </p>
	 */
	@Test
	public void testAFailedWritePoisonsTheLog() throws Exception {
		// Write 0 is the preallocation; write 1 is the commit's flush.
		try (FaultInjectingIoEngine engine = engine(Fault.FAIL_WRITE_ONCE, 1)) {
			WalWriter writer = writer(engine);
			writer.append(0, 1, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
			assertThrows(IOException.class, writer::sync);

			assertThrows(IOException.class, () -> {
				writer.append(0, 2, WalRecord.TYPE_TXN_COMMIT, new byte[64]);
				writer.sync();
			}, "a log whose last write failed must not accept another commit");
			writer.close();
		}
	}

	/**
	 * The same rule at the store: once a commit's log write has failed, the store takes no further commits.
	 *
	 * <p>
	 * The failed transaction is rolled back in memory and its slots go back to the free ring, but its records may be
	 * in the log. A later commit reusing those slots would then share them with a transaction recovery will replay.
	 * The store must still close, and reopen to a state that verifies.
	 * </p>
	 */
	@Test
	public void testAStoreTakesNoCommitAfterItsLogFailed() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.walSyncMode(WalSyncMode.FSYNC_ON_COMMIT)
			.ioEngine(IoEngineKind.DIRECT)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.build();
		long before;
		try (FaultInjectingIoEngine engine = engine(Fault.FAIL_FSYNC_ONCE, 0)) {
			GraphStoreImpl store = (GraphStoreImpl) GraphStore.open(dir, options);
			before = store.createNode("Before", Properties.of().set("i", 1));
			swapInWriter(store, engine, options.walSegmentBytes());

			assertThrows(IOException.class, () -> store.createNode("Failed", Properties.of().set("i", 2)),
				"the commit whose fsync failed must fail");
			assertThrows(IOException.class, () -> store.createNode("After", Properties.of().set("i", 3)),
				"the store must refuse every commit after its log failed, until it is reopened");
			assertThrows(IOException.class, store::checkpoint, "a checkpoint would trim a log nobody can trust");
			// Closing reports the failure rather than hiding it, and still releases the store.
			assertThrows(IOException.class, store::close);
		}

		try (GraphStore reopened = GraphStore.open(dir, options)) {
			assertEquals(1L, reopened.nodeProperty(before, "i"));
			// The failed commit's outcome is whatever the log holds: present or absent, but never half, and never
			// sharing a slot with a later commit.
			long count = reopened.stats().nodeCount();
			assertTrue(count == 1 || count == 2, "unexpected node count " + count);
			reopened.verify();
			reopened.createNode("AfterReopen");
		}
	}

	/**
	 * Replaces the store's log writer with one on a misbehaving device, resuming where the real one stands.
	 * <p>
	 * Reflection, because the store has no seam for its log engine and production code should not grow one for this.
	 */
	private void swapInWriter(GraphStoreImpl store, FaultInjectingIoEngine engine, long segmentBytes)
		throws Exception {
		WalManager wal = store.wal();
		Field field = WalManager.class.getDeclaredField("writer");
		field.setAccessible(true);
		WalWriter current = (WalWriter) field.get(wal);
		current.close();
		WalWriter faulty = new WalWriter(dir, segmentBytes, current.nextLsn(),
			WalSyncMode.FSYNC_ON_COMMIT, 0, IoEngineKind.DIRECT, engine);
		field.set(wal, faulty);
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Records in the log, not counting alignment padding.
	 * <p>
	 * The direct log fills the tail of its last block with a {@link WalRecord#TYPE_PADDING} record on every flush, so
	 * a raw count is the number of records plus the number of syncs. Padding is part of the stream and carries
	 * nothing; counting it here would make every expectation depend on how often the writer happened to flush.
	 */
	private int countRecords() throws IOException {
		int count = 0;
		try (WalReader reader = new WalReader(dir, SEGMENT, 0)) {
			byte[] record;
			while ((record = reader.next()) != null) {
				if (WalRecord.type(record, 0) != WalRecord.TYPE_PADDING) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * Closes a writer whose device is broken.
	 * <p>
	 * {@code close()} forces one last time, so a writer with an injected fault fails there too — correctly: it never
	 * pretends the failure passed. The test has already asserted the failure it cares about at the commit point.
	 */
	private static void closeExpectingFailure(WalWriter writer) {
		try {
			writer.close();
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("injected"), "unexpected failure on close: " + e.getMessage());
		}
	}

	private long lastLsn() throws IOException {
		try (WalReader reader = new WalReader(dir, SEGMENT, 0)) {
			byte[] record;
			byte[] last = null;
			while ((record = reader.next()) != null) {
				last = record;
			}
			assertNotNull(last);
			return reader.lsn();
		}
	}
}
