package io.metaloom.graph.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.StoreFormat;
import io.metaloom.graph.core.api.StoreMigration;
import io.metaloom.graph.core.fixture.SidecarVersions;
import io.metaloom.graph.core.fixture.StoreFiles;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.migration.FormatMigration;
import io.metaloom.graph.core.migration.MigrationContext;
import io.metaloom.graph.core.migration.MigrationHooks;
import io.metaloom.graph.core.migration.MigrationRegistry;
import io.metaloom.graph.core.migration.MigrationRunner;
import io.metaloom.graph.core.migration.MigrationVerifier;
import io.metaloom.graph.core.store.Manifest;

/**
 * {@code database migrate} and {@code database info} as an operator runs them: the plan on stdout, progress on
 * stderr, the exit status, and what each refusal leaves behind (nothing).
 *
 * <p>
 * The shipped migration has no steps yet — 4 is the first version with a process — so the end-to-end run goes through
 * a runner built with a synthetic two-step chain and the test's sidecar versions, the same arrangement
 * {@code MigrationRunnerTest} uses in graph-core. The refusals use the shipped migration and a real manifest.
 * </p>
 */
@Tag("unit")
public class MigrateCliTest {

	@TempDir
	Path tmp;

	private Path root;

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	@BeforeEach
	public void seed() throws IOException {
		root = tmp.resolve("root");
		// Small segments: a store with default geometry spans about 15 GiB of holes, which a copy without the native
		// layer has to read through.
		try (GraphDatabases databases = GraphDatabases.open(root, TempStore.options())) {
			GraphStore store = databases.create("sales");
			for (int i = 0; i < 5; i++) {
				store.createNode("Order", Properties.of().set("n", (long) i));
			}
		}
	}

	private int run(StoreMigration migration, String... args) {
		CliOptions options = CliOptions.parse(args, name -> null);
		return new GraphCli(options, new PrintStream(err, true, StandardCharsets.UTF_8), out,
			new ByteArrayInputStream(new byte[0]), migration).run();
	}

	private int run(String... args) {
		return run(StoreMigration.standard(), args);
	}

	private String outText() {
		return out.toString(StandardCharsets.UTF_8);
	}

	private String errText() {
		return err.toString(StandardCharsets.UTF_8);
	}

	private Path sales() {
		return root.resolve("sales");
	}

	/** Two synthetic steps, 1 -> 2 -> 3, over a store whose runner-visible version lives in a sidecar file. */
	private static StoreMigration synthetic() {
		FormatMigration one = step(1, "first synthetic step");
		FormatMigration two = step(2, "second synthetic step");
		return new MigrationRunner(new MigrationRegistry(3, List.of(one, two)), new SidecarVersions(),
			MigrationVerifier.OPEN_AND_VERIFY_FULL, MigrationHooks.NONE);
	}

	private static FormatMigration step(int from, String description) {
		return new FormatMigration() {

			@Override
			public int from() {
				return from;
			}

			@Override
			public String description() {
				return description;
			}

			@Override
			public void migrate(MigrationContext context) throws IOException {
				context.progress("did " + description);
			}
		};
	}

	// ---------------------------------------------------------------- info

	@Test
	public void testInfoPrintsTheFormatVersionWithoutOpeningTheStore() throws IOException {
		try (GraphStore open = GraphStore.open(sales(), StoreFormat.inspect(sales()).options())) {
			// Held open, as a server would: info takes no lock, so it still answers.
			assertEquals(0, run("database", "info", "sales", "--path=" + root), errText());
		}
		assertTrue(outText().contains("format version   " + StoreFormat.CURRENT_VERSION + " (current"), outText());
		assertTrue(outText().contains("this build reads " + StoreFormat.CURRENT_VERSION), outText());
		assertTrue(outText().contains("page size        8192"), outText());
		assertFalse(outText().contains("migration "), "a current store needs none: " + outText());
	}

	@Test
	public void testInfoAnswersForAStoreThisBuildRefusesToOpen() throws IOException {
		Manifest.stampVersion(sales(), 2);
		assertEquals(0, run("database", "info", "sales", "--path=" + root), errText());
		assertTrue(outText().contains("format version   2 (older than this build's"), outText());
		assertTrue(outText().contains("migration        needed"), outText());
	}

	// ---------------------------------------------------------------- migrate, shipped migration

	@Test
	public void testACurrentStoreHasNothingToDo() throws IOException {
		Map<String, String> before = StoreFiles.digest(sales());
		assertEquals(0, run("database", "migrate", "sales", "--path=" + root, "--dry-run"), errText());
		assertTrue(outText().contains("which is current: nothing to do"), outText());
		assertEquals(0, run("database", "migrate", "sales", "--path=" + root), errText());
		assertTrue(errText().contains("Nothing to do"), errText());
		assertEquals(before, StoreFiles.digest(sales()));
		assertEquals(List.of("default", "sales"), names());
	}

	@Test
	public void testAVersionWithNoPathIsBlockedAndUntouched() throws IOException {
		Manifest.stampVersion(sales(), 2);
		Map<String, String> before = StoreFiles.digest(sales());
		assertEquals(1, run("database", "migrate", "sales", "--path=" + root, "--dry-run"));
		assertTrue(outText().contains("format version   2 -> " + StoreFormat.CURRENT_VERSION), outText());
		assertTrue(outText().contains("blocked          There is no migration step from format version 2"), outText());
		assertTrue(errText().contains("Nothing was changed"), errText());
		assertEquals(1, run("database", "migrate", "sales", "--path=" + root));
		assertEquals(before, StoreFiles.digest(sales()));
		assertEquals(List.of("default", "sales"), names());
	}

	@Test
	public void testANewerStoreIsRefused() throws IOException {
		Manifest.stampVersion(sales(), StoreFormat.CURRENT_VERSION + 3);
		assertEquals(1, run("database", "migrate", "sales", "--path=" + root, "--dry-run"));
		assertTrue(outText().contains("newer than this build"), outText());
		assertEquals(1, run("database", "migrate", "sales", "--path=" + root));
		assertTrue(errText().contains("cannot run now"), errText());
	}

	@Test
	public void testAMissingDatabaseIsNotCreated() {
		assertEquals(1, run("database", "migrate", "nope", "--path=" + root));
		assertTrue(errText().contains("No database 'nope'"), errText());
		assertFalse(Files.exists(root.resolve("nope")));
		assertEquals(1, run("database", "info", "nope", "--path=" + tmp.resolve("no-root")));
		assertFalse(Files.exists(tmp.resolve("no-root")));
	}

	@Test
	public void testMigrateAndInfoAreRefusedOverTheServer() {
		IllegalArgumentException migrate = assertThrows(IllegalArgumentException.class,
			() -> CliOptions.parse(new String[] { "database", "migrate", "--server=http://localhost:8080" }, n -> null));
		assertTrue(migrate.getMessage().contains("server stopped"), migrate.getMessage());
		assertThrows(IllegalArgumentException.class,
			() -> CliOptions.parse(new String[] { "database", "info", "--server=http://localhost:8080" }, n -> null));
		assertThrows(IllegalArgumentException.class,
			() -> CliOptions.parse(new String[] { "database", "list", "--path=/x", "--dry-run" }, n -> null),
			"--dry-run means nothing to any other command, so it is refused rather than ignored");
	}

	// ---------------------------------------------------------------- migrate, synthetic chain

	@Test
	public void testADryRunPrintsThePlanAndChangesNothing() throws IOException {
		new SidecarVersions().write(sales(), 1);
		Map<String, String> before = StoreFiles.digest(sales());
		assertEquals(0, run(synthetic(), "database", "migrate", "sales", "--path=" + root, "--dry-run"), errText());
		String plan = outText();
		assertTrue(plan.contains("format version   1 -> 3"), plan);
		assertTrue(plan.contains("step             1 -> 2  first synthetic step"), plan);
		assertTrue(plan.contains("step             2 -> 3  second synthetic step"), plan);
		assertTrue(plan.contains("original kept as " + root.toAbsolutePath().resolve("sales.pre-migration-v1")),
			plan);
		assertFalse(plan.contains("blocked"), plan);
		assertTrue(errText().contains("Dry run: nothing was changed."), errText());
		assertEquals(before, StoreFiles.digest(sales()));
		assertEquals(List.of("default", "sales"), names());
	}

	@Test
	public void testAMigrationRunsThroughTheCli() throws IOException {
		new SidecarVersions().write(sales(), 1);
		assertEquals(0, run(synthetic(), "database", "migrate", "sales", "--path=" + root), errText());
		assertTrue(errText().contains("[1 -> 2] did first synthetic step"), errText());
		assertTrue(errText().contains("Migrated 'sales' from format version 1 to 3"), errText());
		assertEquals(3, new SidecarVersions().read(sales()));
		assertEquals(List.of("default", "sales", "sales.pre-migration-v1"), names());
		try (GraphDatabases databases = GraphDatabases.open(root, TempStore.options())) {
			assertEquals(List.of("default", "sales"), databases.names(), "the backup is not listed as a database");
			assertEquals(5, databases.database("sales").stats().nodeCount());
		}
	}

	@Test
	public void testAStoreAServerHoldsIsRefused() throws IOException {
		new SidecarVersions().write(sales(), 1);
		try (GraphStore open = GraphStore.open(sales(), StoreFormat.inspect(sales()).options())) {
			assertEquals(1, run(synthetic(), "database", "migrate", "sales", "--path=" + root));
			assertTrue(outText().contains("blocked"), outText());
		}
		assertEquals(List.of("default", "sales"), names());
	}

	private List<String> names() throws IOException {
		return StoreFiles.list(root);
	}
}
