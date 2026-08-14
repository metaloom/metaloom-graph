package io.metaloom.graph.core.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.store.GraphStoreImpl;
import io.metaloom.graph.core.wal.WalReader;
import io.metaloom.graph.core.wal.WalRecord;

/**
 * The crash matrix: every fault a device can inflict on the log, at every record in it.
 *
 * <p>
 * This is level 2 of [TESTING.md §6](../../../../../../../../spec/TESTING.md), and it was the largest single gap in
 * the project — the only level not covered, and the one that would catch a device lying about what it wrote. Level 1
 * (abandon the store and reopen) proves recovery replays; it cannot prove anything about what happens when the bytes
 * on disk are not the bytes the engine wrote.
 * </p>
 *
 * <h2>The property being asserted</h2>
 *
 * <p>
 * The workload is deliberately serial: transaction <i>t</i> creates three nodes carrying <i>t</i>, committed together.
 * A write-ahead log's guarantee under any truncation or corruption of its tail is then exactly expressible:
 * </p>
 *
 * <ol>
 * <li><b>The surviving transactions are a prefix.</b> If transaction 7 survived, so did 0 through 6. A hole means
 * recovery applied a record it should not have, or skipped one it should have applied.</li>
 * <li><b>No transaction is partial.</b> All three of a transaction's nodes are present, or none are. This is
 * atomicity, and it is the property a torn commit record attacks.</li>
 * <li><b>The store opens, or fails in the engine's own vocabulary.</b> A corrupt log may legitimately be refused; it
 * may not produce a {@code NullPointerException}, an {@code ArrayIndexOutOfBoundsException}, or a hang.</li>
 * <li><b>Whatever it recovered, it verifies.</b> Every structural invariant holds afterwards.</li>
 * <li><b>Recovery is idempotent.</b> Reopening the recovered store a second time changes nothing.</li>
 * </ol>
 *
 * <p>
 * The matrix is generated rather than written: every record in the log is located by scanning it, and every fault kind
 * is applied to every record. Nothing here encodes an assumption about which records are interesting, because a test
 * that only damages the records its author thought about is a test of the author.
 * </p>
 *
 * <p>
 * Tagged {@code crash}: {@code mvn test -Pcrash}.
 * </p>
 */
@Tag("crash")
public class CrashMatrixTest {

	@TempDir
	Path root;

	/**
	 * Transactions in the workload. Each writes three nodes, so a torn commit has something to tear.
	 * <p>
	 * The case count is six faults times every record the workload produces, which grows with this number: 12
	 * transactions is about 1,200 cases and a minute, 24 is 2,394 cases and four minutes. The default is sized for
	 * {@code -Pcrash} to stay usable; a nightly run should raise it, since a wider matrix is strictly more evidence.
	 */
	private static final int TRANSACTIONS = Integer.getInteger("graph.crash.transactions", 12);
	private static final int NODES_PER_TXN = 3;

	/** How the bytes on disk can differ from the bytes the engine wrote. */
	private enum Fault {
		/** The log ends early: everything from this record on was never written. */
		TRUNCATE,
		/** A write that never reached the device, leaving whatever the preallocated file already held. */
		LOST_WRITE,
		/** Half a record landed. The classic torn write, and what a sector boundary produces. */
		TORN_WRITE,
		/** The write landed, but the bytes are wrong — a bad cable, bad RAM, a lying cache. */
		CORRUPT_PAYLOAD,
		/** Only the header is damaged, which is what a CRC alone would miss if the LSN were not self-identifying. */
		CORRUPT_HEADER,
		/** Two records swapped, which is what a device that reorders without honouring a barrier produces. */
		REORDERED
	}

	@Test
	public void testEveryFaultAtEveryRecord() throws Exception {
		Path pristine = root.resolve("pristine");
		Path baseline = root.resolve("baseline");
		buildWorkload(pristine, baseline);

		List<long[]> records = locateRecords(pristine);
		assertTrue(records.size() > TRANSACTIONS,
			"the workload must produce more records than transactions, found " + records.size());

		int cases = 0;
		int opened = 0;
		int refused = 0;
		int highestRecovered = 0;
		int lowestRecovered = Integer.MAX_VALUE;
		int partial = 0;
		for (Fault fault : Fault.values()) {
			for (int index = 0; index < records.size(); index++) {
				Path candidate = root.resolve("case-" + fault + "-" + index);
				// The data files as they were before the workload, plus the log that describes it. Recovery has to
				// rebuild everything from the log, which is the only way damaging the log can change an outcome.
				copyRecursively(baseline, candidate);
				copyWal(pristine, candidate);
				if (!damage(candidate, records, index, fault)) {
					deleteRecursively(candidate);
					continue;
				}

				Outcome outcome = reopenAndCheck(candidate, fault, index);
				cases++;
				if (outcome.opened()) {
					opened++;
					highestRecovered = Math.max(highestRecovered, outcome.recovered());
					lowestRecovered = Math.min(lowestRecovered, outcome.recovered());
					if (outcome.recovered() < TRANSACTIONS) {
						partial++;
					}
				} else {
					refused++;
				}
				deleteRecursively(candidate);
			}
		}

		System.out.printf("%ncrash matrix: %,d cases over %,d records and %d fault kinds%n",
			cases, records.size(), Fault.values().length);
		System.out.printf("  %,d stores opened and verified, %,d refused with a typed failure%n", opened, refused);
		System.out.printf("  recovery depth across cases: %d to %d of %d transactions%n",
			lowestRecovered == Integer.MAX_VALUE ? 0 : lowestRecovered, highestRecovered, TRANSACTIONS);
		System.out.printf("  %,d cases lost at least one transaction to the damage%n", partial);

		// A matrix in which nothing ever opened would pass every assertion above while testing nothing: it would mean
		// the damage is always fatal and recovery is never exercised. Likewise one in which nothing is ever refused
		// would mean the damage never reached anything load-bearing.
		assertTrue(opened > 0, "no damaged store ever recovered; the matrix is not exercising recovery");
		assertTrue(cases > 50, "the matrix is too small to mean anything: " + cases + " cases");

		// The assertion that keeps the rest honest. Every property above is satisfied by a store that ignored the
		// damage entirely — a full recovery in every case would pass the prefix check, the atomicity check and the
		// idempotence check while proving nothing at all. Some damage has to cost something, or the matrix is
		// measuring a log that was never load-bearing.
		assertTrue(partial > 0,
			"every case recovered all " + TRANSACTIONS + " transactions, so the damage never mattered. Either the "
				+ "data files are ahead of the log or the faults are landing somewhere harmless.");
		assertTrue(lowestRecovered < TRANSACTIONS / 2,
			"the worst case still recovered " + lowestRecovered + " of " + TRANSACTIONS
				+ " transactions; damage near the start of the log should cost far more than that");
	}

	private record Outcome(boolean opened, int recovered) {
	}

	/**
	 * Reopens a damaged store and holds it to the five properties above.
	 */
	private Outcome reopenAndCheck(Path dir, Fault fault, int index) {
		String what = fault + " at record " + index;
		boolean[] present;
		try (GraphStore store = GraphStore.open(dir, options())) {
			store.verify(VerifyLevel.FULL);
			present = survivors(store, what);
		} catch (GraphStoreException e) {
			// Refusing a corrupt log is a legitimate outcome. What matters is that it is expressed in the engine's own
			// vocabulary rather than as whatever exception happened to escape first.
			return new Outcome(false, 0);
		} catch (IOException e) {
			return new Outcome(false, 0);
		} catch (RuntimeException e) {
			throw new AssertionError(what + ": opening a damaged store threw " + e.getClass().getName()
				+ " rather than one of the engine's own exceptions: " + e.getMessage(), e);
		}

		int recovered = prefixLength(present, what);

		// Idempotence: recovery has already run once and rewritten what it needed to. Running it again must converge.
		try (GraphStore store = GraphStore.open(dir, options())) {
			store.verify(VerifyLevel.FULL);
			boolean[] again = survivors(store, what);
			for (int t = 0; t < TRANSACTIONS; t++) {
				assertEquals(present[t], again[t],
					what + ": transaction " + t + " changed on the second open; recovery is not idempotent");
			}
		} catch (GraphStoreException | IOException e) {
			throw new AssertionError(what + ": the store opened once and then refused to open again", e);
		}
		return new Outcome(true, recovered);
	}

	/**
	 * Which transactions survived, asserting that none of them survived partially.
	 * <p>
	 * Atomicity is checked here rather than at the end because a partial transaction is meaningless to the prefix
	 * question: it is already a failure, whatever its position.
	 */
	private boolean[] survivors(GraphStore store, String what) {
		boolean[] present = new boolean[TRANSACTIONS];
		for (int t = 0; t < TRANSACTIONS; t++) {
			int found = store.nodesByProperty("txn", t).size();
			if (found != 0 && found != NODES_PER_TXN) {
				fail(what + ": transaction " + t + " recovered " + found + " of " + NODES_PER_TXN
					+ " nodes. A transaction is all or nothing.");
			}
			present[t] = found == NODES_PER_TXN;
		}
		return present;
	}

	/** Asserts the survivors form a prefix and returns its length. */
	private int prefixLength(boolean[] present, String what) {
		int prefix = 0;
		while (prefix < present.length && present[prefix]) {
			prefix++;
		}
		for (int t = prefix; t < present.length; t++) {
			if (present[t]) {
				fail(what + ": transaction " + t + " survived but " + prefix
					+ " did not. Recovery produced a hole, which no log may do.");
			}
		}
		return prefix;
	}

	// ---------------------------------------------------------------- the workload

	/**
	 * Builds the workload and leaves the data files <b>behind</b> the log.
	 *
	 * <p>
	 * This is the step that makes the whole matrix mean something, and getting it wrong would have made every case
	 * pass while testing nothing. A commit here applies its pages to the mapping as soon as the log is fsynced, so by
	 * the end of an ordinary run the data files already hold everything and recovery has nothing left to do —
	 * damaging the log would then change no outcome at all.
	 * </p>
	 *
	 * <p>
	 * So the segment files are snapshotted before the workload and restored after it, which is the state a crash
	 * actually leaves: the dirty pages never reached the device, and the log did. The technique is
	 * {@code RecoveryTest#testCommittedWorkSurvivesLosingTheDataFiles}, generalised.
	 * </p>
	 */
	private void buildWorkload(Path dir, Path baseline) throws IOException {
		try (GraphStore store = GraphStore.open(dir, options())) {
			// One committed node before the snapshot, so that every segment kind the workload will touch already
			// exists in the baseline. Without it the baseline has no NODE segment at all, and restoring it produces a
			// state no crash can produce: a segment file that is missing rather than stale. Segment files are created
			// and fsynced eagerly, so a crash loses their contents and never the files themselves.
			store.createNode("Baseline", Properties.of().set("txn", -1).set("n", 0));
			store.checkpoint();
		}
		copyRecursively(dir, baseline);

		GraphStoreImpl store = (GraphStoreImpl) GraphStore.open(dir, options());
		for (int t = 0; t < TRANSACTIONS; t++) {
			try (Transaction txn = store.begin()) {
				for (int n = 0; n < NODES_PER_TXN; n++) {
					store.createNode("Acked", Properties.of().set("txn", t).set("n", n));
				}
				txn.commit();
			}
		}
		// A crash: no checkpoint, no flush. Every one of these commits is durable in the log and only some of them,
		// if any, reached the data files.
		store.abandon();
	}

	/**
	 * A deliberately small store.
	 * <p>
	 * Segment files are sparse, but {@code Files.copy} is not: it writes every apparent byte. At the 4 GiB default
	 * this test copied gigabytes per case and never finished. The geometry below has no bearing on what is being
	 * tested — the log format and recovery are the same at any size — and it makes a restore a few megabytes.
	 */
	private static GraphStoreOptions options() {
		return GraphStoreOptions.builder()
			.walSyncMode(WalSyncMode.FSYNC_ON_COMMIT)
			.segmentCapacity(4L * 1024 * 1024)
			.walSegmentBytes(1L * 1024 * 1024)
			.build();
	}

	// ---------------------------------------------------------------- locating and damaging records

	/** Every record in the log, as {@code {offset, length}} pairs. */
	private List<long[]> locateRecords(Path dir) throws IOException {
		List<long[]> records = new ArrayList<>();
		try (WalReader reader = new WalReader(dir, options().walSegmentBytes(), 0)) {
			byte[] record;
			long previous = 0;
			while ((record = reader.next()) != null) {
				records.add(new long[] { previous, WalRecord.length(record, 0) });
				previous = reader.lsn();
			}
		}
		return records;
	}

	private Path segmentPath(Path dir, long lsn) {
		long base = lsn / options().walSegmentBytes() * options().walSegmentBytes();
		return dir.resolve("wal").resolve(String.format("wal-%016x.log", base));
	}

	/** Applies one fault to one record. Returns false when the fault does not apply at this position. */
	private boolean damage(Path dir, List<long[]> records, int index, Fault fault) throws IOException {
		long[] record = records.get(index);
		long offset = record[0] % options().walSegmentBytes();
		int length = (int) record[1];
		Path path = segmentPath(dir, record[0]);
		if (!Files.exists(path)) {
			return false;
		}

		byte[] log = Files.readAllBytes(path);
		if (offset + length > log.length) {
			return false;
		}
		Random random = new Random(20260814L + index);

		switch (fault) {
			case TRUNCATE -> {
				// Everything from here on was never written. The preallocated tail is zeros, which is what a fresh
				// segment holds anyway.
				java.util.Arrays.fill(log, (int) offset, log.length, (byte) 0);
			}
			case LOST_WRITE -> java.util.Arrays.fill(log, (int) offset, (int) offset + length, (byte) 0);
			case TORN_WRITE -> {
				if (length < 16) {
					return false;
				}
				java.util.Arrays.fill(log, (int) offset + length / 2, (int) offset + length, (byte) 0);
			}
			case CORRUPT_PAYLOAD -> {
				if (length <= WalRecord.HEADER_BYTES) {
					return false;
				}
				int at = (int) offset + WalRecord.HEADER_BYTES + random.nextInt(length - WalRecord.HEADER_BYTES);
				log[at] = (byte) (log[at] ^ 0xFF);
			}
			case CORRUPT_HEADER -> {
				int at = (int) offset + random.nextInt(WalRecord.HEADER_BYTES);
				log[at] = (byte) (log[at] ^ 0xFF);
			}
			case REORDERED -> {
				if (index + 1 >= records.size()) {
					return false;
				}
				long[] next = records.get(index + 1);
				long nextOffset = next[0] % options().walSegmentBytes();
				int nextLength = (int) next[1];
				if (nextOffset + nextLength > log.length || !segmentPath(dir, next[0]).equals(path)) {
					return false;
				}
				// Swap the two record images in place. Their self-identifying LSNs now disagree with where they sit,
				// which is precisely what the format claims to detect.
				byte[] first = java.util.Arrays.copyOfRange(log, (int) offset, (int) offset + length);
				byte[] second = java.util.Arrays.copyOfRange(log, (int) nextOffset, (int) nextOffset + nextLength);
				System.arraycopy(second, 0, log, (int) offset, Math.min(second.length, length));
				System.arraycopy(first, 0, log, (int) nextOffset, Math.min(first.length, nextLength));
			}
		}

		Files.write(path, log);
		return true;
	}

	// ---------------------------------------------------------------- plumbing

	/** Replaces the candidate's log with the workload's, leaving its data files at the baseline. */
	private static void copyWal(Path from, Path to) throws IOException {
		Path source = from.resolve("wal");
		Path target = to.resolve("wal");
		deleteRecursively(target);
		Files.createDirectories(target);
		try (Stream<Path> walk = Files.walk(source)) {
			for (Path entry : walk.filter(Files::isRegularFile).toList()) {
				Files.copy(entry, target.resolve(source.relativize(entry).toString()),
					StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static void copyRecursively(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path source : walk.toList()) {
				Path target = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
