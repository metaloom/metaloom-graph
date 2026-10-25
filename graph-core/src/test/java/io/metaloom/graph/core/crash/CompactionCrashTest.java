package io.metaloom.graph.core.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.maintenance.Compactor;
import io.metaloom.graph.core.maintenance.MaintenanceRunner;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * Crashing in the middle of a compaction.
 * <p>
 * This is the property that makes id-breaking relocation safe to run online at all. A compaction is a sequence of
 * batches and <b>each batch is one transaction</b>: relocating the records, rewriting every reference to them and
 * freeing the sources all commit together or not at all. So the only states a crash can leave are "the first k batches
 * happened" for some k, and the store has to be complete and consistent at every one of them.
 * <p>
 * The harness is the one from {@link RecoveryTest}: the data files are rolled back to a pre-compaction snapshot while
 * the log is kept, which is exactly what a crash leaves when dirty pages never reached the device.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 9
 */
@Tag("unit")
public class CompactionCrashTest {

	private static final int NODES = 1500;
	private static final int SURVIVORS = NODES / 5;

	@Test
	public void testCrashBetweenBatchesLosesNothing() throws Exception {
		for (int stopAfter = 1; stopAfter <= 6; stopAfter++) {
			runCrashAt(stopAfter, false);
		}
	}

	@Test
	public void testCrashInsideABatchRollsThatBatchBack() throws Exception {
		for (int stopAfter = 1; stopAfter <= 6; stopAfter++) {
			runCrashAt(stopAfter, true);
		}
	}

	/**
	 * @param insideBatch
	 *            when true the failure happens after the batch's work but before its commit, so the batch rolls back;
	 *            when false it happens cleanly between two batches
	 */
	private void runCrashAt(int stopAfter, boolean insideBatch) throws Exception {
		Path store = Files.createTempDirectory("graphstore-compaction-crash-");
		Path snapshot = Files.createTempDirectory("graphstore-compaction-snapshot-");
		String where = (insideBatch ? "inside" : "between") + " batch " + stopAfter;
		try {
			// A churned store, made durable so the snapshot is a real starting point.
			try (GraphStore fresh = GraphStore.open(store, TempStore.options())) {
				List<Long> ids = new ArrayList<>();
				try (Transaction txn = fresh.begin()) {
					for (int i = 0; i < NODES; i++) {
						ids.add(fresh.createNode(i % 5 == 0 ? "Keep" : "Drop", Properties.of().set("i", i)));
					}
					for (int i = 5; i < NODES; i += 5) {
						fresh.createEdge(ids.get(i - 5), "NEXT", ids.get(i));
					}
					txn.commit();
				}
				try (Transaction txn = fresh.begin()) {
					for (int i = 0; i < ids.size(); i++) {
						if (i % 5 != 0) {
							fresh.deleteNode(ids.get(i));
						}
					}
					txn.commit();
				}
				fresh.checkpoint();
			}
			copySegments(store, snapshot);

			// Compact, and fail partway. Segment retirement is off: unlinking a file is not a transaction and the
			// compactor checkpoints before it, which would put the batches beyond the reach of this harness.
			GraphStoreImpl impl = (GraphStoreImpl) GraphStore.open(store, TempStore.options());
			CompactionOptions options = CompactionOptions.builder()
				.livenessThreshold(1.0)
				.maxRelocationsPerTransaction(64)
				.retireEmptySegments(false)
				.build();
			try {
				new Compactor(impl.segmentSets(), impl.nodeStore(), impl.relStore(), impl.adjacencyStore(),
					impl.propertyStore(), impl.blobStore(), impl.indexCatalog(),
					new FailingRunner(impl, stopAfter, insideBatch)).compact(options);
			} catch (SimulatedCrash expected) {
				// This is the point of the test.
			}
			impl.abandon();

			// The dirty pages never reached the device. The log did.
			copySegments(snapshot, store);

			try (GraphStore reopened = GraphStore.open(store, TempStore.options())) {
				assertEquals(SURVIVORS, reopened.stats().nodeCount(),
					"a crash " + where + " must not lose or duplicate a node");
				reopened.verify(VerifyLevel.FULL);

				List<Long> survivors = reopened.nodesByLabel("Keep");
				assertEquals(SURVIVORS, survivors.size(),
					"the index must agree with the records after a crash " + where);
				TreeSet<Integer> values = new TreeSet<>();
				for (long id : survivors) {
					values.add(reopened.nodeProperties(id).getInt("i"));
				}
				assertEquals(SURVIVORS, values.size(), "every surviving node must be distinct after a crash " + where);
				assertEquals(0, values.first().intValue());
				assertEquals((SURVIVORS - 1) * 5, values.last().intValue());

				// And the compaction can simply be run again, to completion.
				reopened.compact(CompactionOptions.always());
				reopened.verify(VerifyLevel.FULL);
				assertEquals(SURVIVORS, reopened.nodesByLabel("Keep").size());
				assertTrue(((GraphStoreImpl) reopened).segments(Kind.NODE).liveness() > 0.9,
					"the retried compaction must still reach 90% liveness after a crash " + where);
			}
		} finally {
			deleteRecursively(snapshot);
			deleteRecursively(store);
		}
	}

	/** Fails the run at a chosen batch, either before it starts or after its work but before its commit. */
	private static final class FailingRunner implements MaintenanceRunner {

		private final MaintenanceRunner delegate;
		private final int failAt;
		private final boolean insideBatch;
		private int writes;

		FailingRunner(MaintenanceRunner delegate, int failAt, boolean insideBatch) {
			this.delegate = delegate;
			this.failAt = failAt;
			this.insideBatch = insideBatch;
		}

		@Override
		public void runWrite(Unit unit) throws IOException {
			fallible(unit, delegate::runWrite);
		}

		/**
		 * Compaction's write units are exclusive ones, so this is the method the batches actually arrive through.
		 * Counted alongside {@link #runWrite} rather than separately: the test is choosing a batch to fail at, and the
		 * batch is a unit of work whichever way the compactor asks for it.
		 */
		@Override
		public void runExclusive(Unit unit) throws IOException {
			fallible(unit, delegate::runExclusive);
		}

		@Override
		public void runUnlogged(Unit unit) throws IOException {
			fallible(unit, delegate::runUnlogged);
		}

		private void fallible(Unit unit, Fallible run) throws IOException {
			if (++writes < failAt) {
				run.accept(unit);
				return;
			}
			if (!insideBatch) {
				throw new SimulatedCrash();
			}
			// Throwing from inside the unit leaves the transaction to be rolled back by its own close().
			run.accept(() -> {
				unit.run();
				throw new SimulatedCrash();
			});
		}

		@FunctionalInterface
		private interface Fallible {
			void accept(Unit unit) throws IOException;
		}

		@Override
		public void runRead(Unit unit) throws IOException {
			delegate.runRead(unit);
		}

		@Override
		public void runCheckpoint() throws IOException {
			throw new IllegalStateException("This test must not let the compactor checkpoint");
		}
	}

	private static final class SimulatedCrash extends RuntimeException {

		private static final long serialVersionUID = 1L;
	}

	// ---------------------------------------------------------------- helpers

	/** Copies the data segments only, leaving the log alone. */
	private static void copySegments(Path from, Path to) throws IOException {
		Path source = from.resolve("seg");
		Path target = to.resolve("seg");
		if (!Files.isDirectory(source)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(source)) {
			for (Path entry : walk.toList()) {
				Path destination = target.resolve(source.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
		Path manifest = from.resolve("MANIFEST");
		if (Files.exists(manifest)) {
			Files.copy(manifest, to.resolve("MANIFEST"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
