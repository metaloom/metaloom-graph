package io.metaloom.graph.core.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.MigrationException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.StoreMigration;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.SidecarVersions;
import io.metaloom.graph.core.fixture.StoreFiles;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The migration runner, end to end, over a synthetic chain of steps: 1 -> 2 renames a label in the dictionary file,
 * 2 -> 3 only leaves a trace. Every step appends to {@code TRACE} in the store, so a test can tell how many times each
 * one ran against the copy that was swapped in.
 *
 * <p>
 * The store underneath is a genuine current store; only its runner-visible version is synthetic
 * ({@link SidecarVersions}). That is what makes the verification at the end the real one — open, then
 * {@code verify(FULL)} — rather than a stand-in.
 * </p>
 */
@Tag("unit")
public class MigrationRunnerTest {

	private static final int TARGET = 3;

	@TempDir
	Path root;

	private Path store;

	private final List<String> events = new ArrayList<>();

	@BeforeEach
	public void seed() throws IOException {
		store = root.resolve("graph");
		try (GraphStore graph = GraphStore.open(store, TempStore.options())) {
			try (Transaction txn = graph.begin()) {
				long previous = 0;
				for (int i = 0; i < 3; i++) {
					long id = graph.createNode("Person", Properties.of().set("name", "p" + i));
					if (previous != 0) {
						graph.createEdge(previous, "KNOWS", id);
					}
					previous = id;
				}
				graph.createNode("Place", Properties.of().set("name", "here"));
				txn.commit();
			}
			graph.createIndex("Person", "name");
		}
		new SidecarVersions().write(store, 1);
	}

	// ---------------------------------------------------------------- the synthetic chain

	/** Rewrites {@code dict/labels.dict}, which is {@code [u32 big-endian length][utf8]} per entry. */
	static final class RenameLabel implements FormatMigration {

		@Override
		public int from() {
			return 1;
		}

		@Override
		public String description() {
			return "renames the label Person to Human in dict/labels.dict";
		}

		@Override
		public void migrate(MigrationContext context) throws IOException {
			Path dict = context.store().resolve("dict").resolve("labels.dict");
			ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(dict));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			while (in.remaining() >= 4) {
				byte[] bytes = new byte[in.getInt()];
				in.get(bytes);
				String label = new String(bytes, StandardCharsets.UTF_8);
				byte[] written = (label.equals("Person") ? "Human" : label).getBytes(StandardCharsets.UTF_8);
				out.write(ByteBuffer.allocate(4).putInt(written.length).array());
				out.write(written);
			}
			Files.write(dict, out.toByteArray());
			trace(context);
			context.progress("renamed");
		}
	}

	static class Trace implements FormatMigration {

		private final int from;

		Trace(int from) {
			this.from = from;
		}

		@Override
		public int from() {
			return from;
		}

		@Override
		public String description() {
			return "leaves a trace";
		}

		@Override
		public void migrate(MigrationContext context) throws IOException {
			trace(context);
		}
	}

	static void trace(MigrationContext context) throws IOException {
		Files.writeString(context.store().resolve("TRACE"), context.from() + "->" + context.to() + "\n",
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/** Thrown from a hook to stop the runner the way a crash would: no cleanup runs for an Error. */
	static final class SimulatedCrash extends Error {

		private static final long serialVersionUID = 1L;

		SimulatedCrash(String point) {
			super("simulated crash at " + point);
		}
	}

	private MigrationRunner runner(List<FormatMigration> steps, MigrationHooks hooks) {
		return new MigrationRunner(new MigrationRegistry(TARGET, steps), new SidecarVersions(),
			MigrationVerifier.OPEN_AND_VERIFY_FULL, hooks);
	}

	private MigrationRunner runner() {
		return runner(List.of(new RenameLabel(), new Trace(2)), MigrationHooks.NONE);
	}

	private MigrationRunner crashingAt(String point) {
		return runner(List.of(new RenameLabel(), new Trace(2)), reached -> {
			if (reached.equals(point)) {
				throw new SimulatedCrash(point);
			}
		});
	}

	private Path backup() {
		return root.resolve("graph.pre-migration-v1");
	}

	private Path work() {
		return root.resolve(".migrating-graph");
	}

	private Path marker() {
		return root.resolve(".migration-graph");
	}

	/** The end state every successful migration of the seeded store must reach, however it got there. */
	private void assertMigrated(Map<String, String> original) throws IOException {
		assertEquals(TARGET, new SidecarVersions().read(store));
		assertEquals("1->2\n2->3\n", Files.readString(store.resolve("TRACE")),
			"each step ran exactly once against the copy that was swapped in");
		try (GraphStore graph = GraphStore.open(store, TempStore.options())) {
			assertEquals(3, graph.nodesByLabel("Human").size());
			assertEquals(0, graph.nodesByLabel("Person").size());
			assertEquals(1, graph.nodesByLabel("Place").size());
			assertEquals(1, graph.nodesByProperty("name", "p1").size(), "the index came across with the data");
			assertTrue(graph.hasIndex("Human", "name"), "an index declared on the old name is on the new one");
			graph.verify(VerifyLevel.FULL);
		}
		assertTrue(Files.isDirectory(backup()), "the original is kept");
		assertEquals(original, StoreFiles.digest(backup()), "and kept byte for byte");
		assertFalse(Files.exists(work()), "no working copy is left behind");
		assertFalse(Files.exists(marker()), "and no marker");
		assertEquals(List.of("graph", "graph.pre-migration-v1"), StoreFiles.list(root));
	}

	private void assertUntouched(Map<String, String> original) throws IOException {
		assertEquals(original, StoreFiles.digest(store), "the original store was not changed");
		assertEquals(List.of("graph"), StoreFiles.list(root), "and nothing was left beside it");
	}

	// ---------------------------------------------------------------- plan

	@Test
	public void testThePlanIsTheChainAndChangesNothing() throws IOException {
		Map<String, String> before = StoreFiles.digest(store);
		StoreMigration.Plan plan = runner().plan(store);
		assertEquals(1, plan.fromVersion());
		assertEquals(TARGET, plan.toVersion());
		assertEquals(List.of(new StoreMigration.Step(1, 2, "renames the label Person to Human in dict/labels.dict"),
			new StoreMigration.Step(2, 3, "leaves a trace")), plan.steps());
		assertTrue(plan.canProceed(), plan.blockers().toString());
		assertFalse(plan.upToDate());
		assertNull(plan.interrupted());
		assertEquals(backup(), plan.backupDirectory());
		assertEquals(work(), plan.workDirectory());
		assertUntouched(before);
	}

	@Test
	public void testAStoreAtTheTargetIsLeftAlone() throws IOException {
		new SidecarVersions().write(store, TARGET);
		Map<String, String> before = StoreFiles.digest(store);
		assertTrue(runner().plan(store).upToDate());
		StoreMigration.Result result = runner().migrate(store, events::add);
		assertFalse(result.changed());
		assertTrue(result.applied().isEmpty());
		assertUntouched(before);
	}

	// ---------------------------------------------------------------- migrate

	@Test
	public void testChainedStepsRunInOrderAndTheOriginalIsKept() throws IOException {
		Map<String, String> before = StoreFiles.digest(store);
		StoreMigration.Result result = runner().migrate(store, events::add);
		assertTrue(result.changed());
		assertFalse(result.resumed());
		assertEquals(1, result.fromVersion());
		assertEquals(TARGET, result.toVersion());
		assertEquals(2, result.applied().size());
		assertEquals(backup(), result.backupDirectory());
		assertMigrated(before);
		assertTrue(events.stream().anyMatch(e -> e.contains("Step 1 -> 2")), events.toString());
		assertTrue(events.stream().anyMatch(e -> e.contains("[1 -> 2] renamed")), "a step's own progress is relayed");
		assertTrue(events.stream().anyMatch(e -> e.contains("verify(FULL)")), events.toString());
	}

	/** The copy is opened by this build with a fresh log; it has to be a store that then behaves like any other. */
	@Test
	public void testTheMigratedStoreTakesWritesAndRecoversFromACrash() throws IOException {
		runner().migrate(store);
		long added;
		GraphStore graph = GraphStore.open(store, TempStore.options());
		try (Transaction txn = graph.begin()) {
			added = graph.createNode("Human", Properties.of().set("name", "after"));
			txn.commit();
		}
		((GraphStoreImpl) graph).abandon();
		try (GraphStore reopened = GraphStore.open(store, TempStore.options())) {
			assertTrue(reopened.nodeExists(added), "a commit after the migration survives a crash");
			assertEquals(4, reopened.nodesByLabel("Human").size());
			reopened.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAFailingStepLeavesTheOriginalUntouched() throws IOException {
		Map<String, String> before = StoreFiles.digest(store);
		FormatMigration breaking = new Trace(2) {

			@Override
			public void migrate(MigrationContext context) throws IOException {
				// Damage the copy first, so the test would notice if the copy were the original.
				Files.delete(context.store().resolve("MANIFEST"));
				throw new IOException("step two could not finish");
			}
		};
		MigrationException failed = assertThrows(MigrationException.class,
			() -> runner(List.of(new RenameLabel(), breaking), MigrationHooks.NONE).migrate(store));
		assertTrue(failed.getMessage().contains("step two could not finish"), failed.getMessage());
		assertTrue(failed.getMessage().contains("untouched"), failed.getMessage());
		assertUntouched(before);
		try (GraphStore graph = GraphStore.open(store, TempStore.options())) {
			assertEquals(3, graph.nodesByLabel("Person").size(), "and it still opens as it was");
			graph.verify(VerifyLevel.FULL);
		}
	}

	/** A step that writes something the current code cannot accept is caught by verification, not by the operator. */
	@Test
	public void testACopyThatFailsVerificationIsNotSwappedIn() throws IOException {
		Map<String, String> before = StoreFiles.digest(store);
		FormatMigration corrupting = new Trace(2) {

			@Override
			public void migrate(MigrationContext context) throws IOException {
				long dataStart = new Geometry(context.source().pageSize(), context.source().segmentCapacity())
					.dataStart(Kind.NODE);
				try (RandomAccessFile file = new RandomAccessFile(
					context.store().resolve("seg/node/node-000000.seg").toFile(), "rw")) {
					file.seek(dataStart + 1);
					file.write(0x5A);
				}
			}
		};
		MigrationException failed = assertThrows(MigrationException.class,
			() -> runner(List.of(new RenameLabel(), corrupting), MigrationHooks.NONE).migrate(store));
		assertTrue(failed.getMessage().contains("Checksum mismatch"), "refused by verify(FULL): " + failed.getMessage());
		assertUntouched(before);
	}

	// ---------------------------------------------------------------- crash and resume

	/**
	 * A crash at each point the runner can stop at, then a second run without one.
	 *
	 * <p>
	 * Before {@code verified} the second run must start over from the untouched original — which the trace proves,
	 * since each step must appear once and not twice. From {@code verified} on it must finish the swap and never start
	 * over, because by then the original may already have been renamed. In between, the store must refuse to open,
	 * and must not be re-created by the attempt: at {@code backed-up} its directory is gone, and an open that created
	 * an empty store there would be the worst outcome on the list.
	 * </p>
	 */
	@ParameterizedTest
	@ValueSource(strings = { MigrationHooks.STARTED, MigrationHooks.COPIED, MigrationHooks.STEPPED + "2",
		MigrationHooks.STEPPED + "3", MigrationHooks.VERIFIED, MigrationHooks.BACKED_UP, MigrationHooks.SWAPPED })
	public void testACrashAtAnyPointIsFinishedByRunningItAgain(String point) throws IOException {
		Map<String, String> before = StoreFiles.digest(store);
		assertThrows(SimulatedCrash.class, () -> crashingAt(point).migrate(store));
		assertTrue(Files.exists(marker()), "the marker records the interrupted migration");

		boolean storeDirectoryExisted = Files.exists(store);
		MigrationException refused = assertThrows(MigrationException.class,
			() -> GraphStore.open(store, TempStore.options()).close());
		assertTrue(refused.getMessage().contains("database migrate graph"), refused.getMessage());
		assertEquals(storeDirectoryExisted, Files.exists(store), "a refused open creates nothing");

		StoreMigration.Plan plan = runner().plan(store);
		assertNotNull(plan.interrupted(), "the plan reports what it found");
		assertTrue(plan.canProceed(), plan.blockers().toString());

		StoreMigration.Result result = runner().migrate(store, events::add);
		assertTrue(result.resumed());
		assertMigrated(before);
	}

	/**
	 * The database a server opens by default, crashed between the two renames: the root then holds no {@code default}
	 * and {@code GraphDatabases.open} would normally create an empty one. It must refuse instead, create nothing, and
	 * leave a state the next run finishes.
	 */
	@Test
	public void testAServerStartDuringAnInterruptedSwapCreatesNothing() throws IOException {
		Path rootOfDefault = root.resolve("databases");
		Path defaultStore = rootOfDefault.resolve(io.metaloom.graph.core.api.GraphDatabases.DEFAULT_NAME);
		try (io.metaloom.graph.core.api.GraphDatabases databases =
			io.metaloom.graph.core.api.GraphDatabases.open(rootOfDefault, TempStore.options())) {
			databases.database("default").createNode("Person", Properties.of().set("name", "kept"));
		}
		new SidecarVersions().write(defaultStore, 1);
		assertThrows(SimulatedCrash.class, () -> crashingAt(MigrationHooks.BACKED_UP).migrate(defaultStore));
		assertFalse(Files.exists(defaultStore));

		MigrationException refused = assertThrows(MigrationException.class,
			() -> io.metaloom.graph.core.api.GraphDatabases.open(rootOfDefault, TempStore.options()).close());
		assertTrue(refused.getMessage().contains("interrupted"), refused.getMessage());
		assertFalse(Files.exists(defaultStore), "no empty default was created in the original's place");

		runner().migrate(defaultStore);
		try (io.metaloom.graph.core.api.GraphDatabases databases =
			io.metaloom.graph.core.api.GraphDatabases.open(rootOfDefault, TempStore.options())) {
			assertEquals(1, databases.database("default").nodesByLabel("Human").size());
		}
	}

	// ---------------------------------------------------------------- refusals

	@Test
	public void testANewerStoreIsRefused() throws IOException {
		new SidecarVersions().write(store, TARGET + 6);
		Map<String, String> before = StoreFiles.digest(store);
		StoreMigration.Plan plan = runner().plan(store);
		assertFalse(plan.canProceed());
		assertTrue(plan.blockers().get(0).contains("newer"), plan.blockers().toString());
		assertThrows(FormatVersionException.class, () -> runner().migrate(store));
		assertUntouched(before);
	}

	/**
	 * Committed work past the checkpoint is in a log only the old build can replay. Refused until the store has been
	 * opened and closed by that build — here the same code, since the versions are synthetic — and then it migrates
	 * with the recovered transaction in it.
	 */
	@Test
	public void testAStoreThatWasNotClosedCleanlyIsRefusedUntilItIs() throws IOException {
		long survivor;
		GraphStore graph = GraphStore.open(store, TempStore.options());
		try (Transaction txn = graph.begin()) {
			survivor = graph.createNode("Person", Properties.of().set("name", "unreplayed"));
			txn.commit();
		}
		((GraphStoreImpl) graph).abandon();
		Map<String, String> before = StoreFiles.digest(store);

		StoreMigration.Plan plan = runner().plan(store);
		assertFalse(plan.canProceed());
		assertTrue(plan.blockers().get(0).contains("1 committed transaction"), plan.blockers().toString());
		MigrationException refused = assertThrows(MigrationException.class, () -> runner().migrate(store));
		assertTrue(refused.getMessage().contains("Nothing was changed"), refused.getMessage());
		assertTrue(refused.getMessage().contains("SIGTERM"), "and it says how to get a clean store");
		assertUntouched(before);

		GraphStore.open(store, TempStore.options()).close();
		assertTrue(runner().plan(store).canProceed());
		runner().migrate(store);
		try (GraphStore migrated = GraphStore.open(store, TempStore.options())) {
			assertEquals("unreplayed", migrated.nodeProperties(survivor).getString("name"));
			assertTrue(migrated.nodeHasLabel(survivor, "Human"));
		}
	}

	/** A clean close leaves uncommitted work in no log, and work rolled back leaves none either. */
	@Test
	public void testRolledBackWorkDoesNotMakeAStoreDirty() throws IOException {
		try (GraphStore graph = GraphStore.open(store, TempStore.options())) {
			try (Transaction txn = graph.begin()) {
				graph.createNode("Person", Properties.of().set("name", "never"));
				txn.rollback();
			}
		}
		assertTrue(runner().plan(store).canProceed());
	}

	@Test
	public void testAStoreOpenElsewhereIsRefused() throws IOException {
		try (GraphStore open = GraphStore.open(store, TempStore.options())) {
			Map<String, String> before = StoreFiles.digest(store);
			StoreMigration.Plan plan = runner().plan(store);
			assertFalse(plan.canProceed());
			assertTrue(plan.blockers().get(0).contains("open in this JVM"), plan.blockers().toString());
			assertThrows(MigrationException.class, () -> runner().migrate(store));
			assertEquals(before, StoreFiles.digest(store));
			assertEquals(List.of("graph"), StoreFiles.list(root));
		}
	}

	@Test
	public void testAnEarlierBackupIsNeverOverwritten() throws IOException {
		Files.createDirectories(backup());
		Files.writeString(backup().resolve("keep-me"), "an earlier backup");
		StoreMigration.Plan plan = runner().plan(store);
		assertFalse(plan.canProceed());
		assertTrue(plan.blockers().get(0).contains("is taken"), plan.blockers().toString());
		assertThrows(MigrationException.class, () -> runner().migrate(store));
		assertEquals("an earlier backup", Files.readString(backup().resolve("keep-me")));
		assertFalse(Files.exists(marker()));
	}

	@Test
	public void testAWorkingCopyWithoutAMarkerIsNotDeletedOnAGuess() throws IOException {
		Files.createDirectories(work());
		Files.writeString(work().resolve("whose"), "unknown");
		StoreMigration.Plan plan = runner().plan(store);
		assertFalse(plan.canProceed());
		assertTrue(plan.blockers().get(0).contains("no migration marker"), plan.blockers().toString());
		assertThrows(MigrationException.class, () -> runner().migrate(store));
		assertEquals("unknown", Files.readString(work().resolve("whose")));
	}

	@Test
	public void testAMissingStepIsNamed() throws IOException {
		StoreMigration.Plan plan = runner(List.of(new Trace(2)), MigrationHooks.NONE).plan(store);
		assertFalse(plan.canProceed());
		assertTrue(plan.blockers().get(0).contains("no migration step from format version 1"),
			plan.blockers().toString());
	}

	/** Directories that a verified migration could not have left are reported, not rearranged on a guess. */
	@Test
	public void testAVerifiedMarkerOverAStateNoRunLeftIsRefused() throws IOException {
		Map<String, String> before = StoreFiles.digest(store);
		new MigrationMarker(1, TARGET, MigrationMarker.Phase.VERIFIED, ".migrating-graph", "graph.pre-migration-v1",
			"").write(store);
		StoreMigration.Plan plan = runner().plan(store);
		assertFalse(plan.canProceed());
		MigrationException refused = assertThrows(MigrationException.class, () -> runner().migrate(store));
		assertTrue(refused.getMessage().contains("did not leave"), refused.getMessage());
		assertEquals(before, StoreFiles.digest(store));
		assertTrue(Files.exists(marker()), "the marker stays until somebody has looked");
	}

	@Test
	public void testThereIsNothingToPlanWhereThereIsNoStore() {
		assertThrows(io.metaloom.graph.core.api.GraphStoreException.class,
			() -> runner().plan(root.resolve("missing")));
	}

	/** A store with non-default geometry is verified with its own, read from the manifest rather than assumed. */
	@Test
	public void testTheCopyIsVerifiedWithTheStoresOwnGeometry() throws IOException {
		Path other = root.resolve("other");
		GraphStoreOptions small = GraphStoreOptions.builder().pageSize(4096).segmentCapacity(64 * 1024)
			.vectorDimensions(4).walSegmentBytes(1024 * 1024).build();
		try (GraphStore graph = GraphStore.open(other, small)) {
			graph.createNode("Person", Properties.of().set("embedding", new float[] { 1, 2, 3, 4 }));
		}
		new SidecarVersions().write(other, 1);
		runner().migrate(other);
		try (GraphStore graph = GraphStore.open(other, small)) {
			assertEquals(1, graph.nodesByLabel("Human").size());
			graph.verify(VerifyLevel.FULL);
		}
	}
}
