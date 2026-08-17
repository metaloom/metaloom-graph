package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Several stores under one root.
 *
 * <p>
 * Two things here are worth more than the rest. Names arrive from an unauthenticated port and become paths, so the
 * traversal cases are the security test for the whole feature; and databases have to be genuinely separate, which is
 * only proved by writing to one and reading the other.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/DATABASES.md">spec/DATABASES.md</a>
 */
@Tag("unit")
public class GraphDatabasesTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private Path root() {
		return stores.path().resolve("root");
	}

	@Test
	public void testAFreshRootGetsADefaultDatabase() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			assertEquals(List.of(GraphDatabases.DEFAULT_NAME), databases.names(),
				"a root with no databases has no useful behaviour, so the first open makes one");
			assertTrue(databases.exists(GraphDatabases.DEFAULT_NAME));
			assertTrue(Files.isDirectory(root().resolve(GraphDatabases.DEFAULT_NAME)));
		}
	}

	@Test
	public void testEachDatabaseHasItsOwnDirectoryAndItsOwnData() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			GraphStore sales = databases.create("sales");
			GraphStore hr = databases.create("hr");

			try (Transaction txn = sales.begin()) {
				sales.createNode("Order", Properties.of().set("total", 42L));
				txn.commit();
			}
			try (Transaction txn = hr.begin()) {
				hr.createNode("Person", Properties.of().set("name", "Alice"));
				hr.createNode("Person", Properties.of().set("name", "Bob"));
				txn.commit();
			}

			// The whole point of the feature: a write to one is invisible to the other.
			assertEquals(1, sales.stats().nodeCount());
			assertEquals(2, hr.stats().nodeCount());
			assertEquals(0, databases.database(GraphDatabases.DEFAULT_NAME).stats().nodeCount());

			assertTrue(Files.isRegularFile(root().resolve("sales").resolve("MANIFEST")));
			assertTrue(Files.isRegularFile(root().resolve("hr").resolve("MANIFEST")));
			assertEquals(List.of(GraphDatabases.DEFAULT_NAME, "hr", "sales"), databases.names(), "sorted");
		}
	}

	@Test
	public void testAStoreIsOpenedOnceAndKept() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			GraphStore created = databases.create("sales");
			assertSame(created, databases.database("sales"),
				"opening a store maps every segment and takes its LOCK; twice would fail on the lock alone");
			assertTrue(databases.isOpen("sales"));
		}
	}

	@Test
	public void testNothingIsOpenedUntilItIsAskedFor() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			databases.create("sales");
			databases.create("hr");
		}
		// The steady state, which is the one that matters: an existing root, reopened. Creating a database opens it,
		// but that happens once in its life.
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			assertEquals(3, databases.names().size());
			assertEquals(List.of(), databases.openDatabases(),
				"a root of fifty databases with one in use should cost one open store, not fifty");

			databases.database("sales");
			assertTrue(databases.isOpen("sales"));
			assertFalse(databases.isOpen("hr"));
			assertFalse(databases.isOpen(GraphDatabases.DEFAULT_NAME));
		}
	}

	@Test
	public void testDataSurvivesAReopen() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			GraphStore sales = databases.create("sales");
			try (Transaction txn = sales.begin()) {
				sales.createNode("Order", Properties.of().set("total", 42L));
				txn.commit();
			}
		}
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			assertEquals(List.of(GraphDatabases.DEFAULT_NAME, "sales"), databases.names());
			assertEquals(1, databases.database("sales").stats().nodeCount());
		}
	}

	// ---------------------------------------------------------------- names

	@Test
	public void testAPathCannotBeSmuggledThroughAName() {
		// These are what a name looks like when it arrives from a port with no authentication. Every one of them has
		// to be refused before it is resolved against the root, not after.
		for (String hostile : List.of("..", ".", "../etc", "../../etc/passwd", "/etc/passwd", "sales/../../etc",
			"sales/sub", "sales\\sub", "C:\\data", "~", "\u0000", "a\u0000b", ".hidden", "with space",
			"emoji\uD83D\uDE00", "sales;rm -rf /", "%2e%2e%2f", "café")) {
			assertThrows(IllegalArgumentException.class, () -> GraphDatabases.validate(hostile),
				"'" + hostile + "' must be refused before it becomes a path");
		}
		assertThrows(IllegalArgumentException.class, () -> GraphDatabases.validate(""));
		assertThrows(IllegalArgumentException.class, () -> GraphDatabases.validate(null));
		assertThrows(IllegalArgumentException.class,
			() -> GraphDatabases.validate("x".repeat(GraphDatabases.MAX_NAME_LENGTH + 1)));
		// A leading dash is not a path hazard; it is a name that will one day be read as a flag.
		assertThrows(IllegalArgumentException.class, () -> GraphDatabases.validate("-force"));
	}

	@Test
	public void testTheNamesPeopleActuallyUseAreAccepted() {
		for (String fine : List.of("default", "sales", "SALES", "sales-2024", "sales_2024", "a", "db1",
			"x".repeat(GraphDatabases.MAX_NAME_LENGTH))) {
			GraphDatabases.validate(fine);
		}
	}

	@Test
	public void testAHostileNameNeverReachesTheFilesystem() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			assertThrows(IllegalArgumentException.class, () -> databases.create("../escaped"));
			assertThrows(IllegalArgumentException.class, () -> databases.database("../escaped"));
			assertThrows(IllegalArgumentException.class, () -> databases.drop("../escaped"));
			assertThrows(IllegalArgumentException.class, () -> databases.exists("../escaped"));
			assertFalse(Files.exists(root().getParent().resolve("escaped")),
				"a rejected name must not have created anything anywhere");
		}
	}

	@Test
	public void testADirectoryThatIsNotAStoreIsNotListed() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			// A half-made database, an editor's leftovers, somebody's notes.
			Files.createDirectories(root().resolve("halfmade").resolve("seg"));
			Files.createDirectories(root().resolve("with space"));
			Files.writeString(root().resolve("README.txt"), "not a database");

			assertEquals(List.of(GraphDatabases.DEFAULT_NAME), databases.names());
			assertFalse(databases.exists("halfmade"));
			assertThrows(GraphStoreException.class, () -> databases.database("halfmade"));
		}
	}

	// ---------------------------------------------------------------- lifecycle

	@Test
	public void testCreatingOneThatExistsIsRefusedRatherThanSilent() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			databases.create("sales");
			GraphStoreException thrown = assertThrows(GraphStoreException.class, () -> databases.create("sales"));
			assertTrue(thrown.getMessage().contains("already exists"), thrown.getMessage());
		}
	}

	@Test
	public void testAskingForOneThatDoesNotExistDoesNotCreateIt() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			// The name arrives from an unauthenticated port. A typo that quietly makes a database is a typo that
			// fills a disk.
			assertThrows(GraphStoreException.class, () -> databases.database("typo"));
			assertFalse(Files.exists(root().resolve("typo")));
			assertEquals(List.of(GraphDatabases.DEFAULT_NAME), databases.names());
		}
	}

	@Test
	public void testDroppingClosesTheStoreAndRemovesTheDirectory() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			GraphStore sales = databases.create("sales");
			try (Transaction txn = sales.begin()) {
				sales.createNode("Order", Properties.of().set("total", 1L));
				txn.commit();
			}
			assertTrue(databases.drop("sales"));
			assertFalse(Files.exists(root().resolve("sales")), "the directory and everything under it");
			assertFalse(databases.exists("sales"));
			assertEquals(List.of(GraphDatabases.DEFAULT_NAME), databases.names());

			// Twice is not an error: a caller retrying a drop is not making a mistake.
			assertFalse(databases.drop("sales"));

			// And the name can be used again afterwards.
			assertNotSame(sales, databases.create("sales"));
		}
	}

	@Test
	public void testTheLastDatabaseCannotBeDropped() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			GraphStoreException thrown = assertThrows(GraphStoreException.class,
				() -> databases.drop(GraphDatabases.DEFAULT_NAME));
			assertTrue(thrown.getMessage().contains("only database"), thrown.getMessage());
			assertTrue(databases.exists(GraphDatabases.DEFAULT_NAME));
		}
	}

	/**
	 * The gap {@code replace} exists to close, stated as the two halves it has to satisfy at once.
	 *
	 * <p>
	 * {@code drop} refuses to remove the last database in a root, and it is right to: a server left holding none has
	 * no useful behaviour. But a restore has to be able to put a database back <i>over</i> that last one, and a root
	 * opened for a restore very often holds exactly one — the {@code default} that opening an empty directory creates.
	 * Before this existed, the most obvious restore there is could not be performed.
	 * </p>
	 */
	@Test
	public void testTheLastDatabaseCanBeReplacedEvenThoughItCannotBeDropped() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			GraphStore original = databases.database(GraphDatabases.DEFAULT_NAME);
			try (Transaction txn = original.begin()) {
				original.createNode("Order", Properties.of().set("total", 1L));
				txn.commit();
			}
			assertThrows(GraphStoreException.class, () -> databases.drop(GraphDatabases.DEFAULT_NAME));

			GraphStore replaced = databases.replace(GraphDatabases.DEFAULT_NAME, null);
			assertNotSame(original, replaced, "the old store is closed and a new one opened in its place");
			assertEquals(0, replaced.stats().nodeCount(), "replaced, not merged into");
			// The root is never observed empty: the replacement is made before the lock is released, which is what
			// makes this different from dropping without the check.
			assertEquals(List.of(GraphDatabases.DEFAULT_NAME), databases.names());
			assertTrue(databases.isOpen(GraphDatabases.DEFAULT_NAME));

			// And it is a working store, not just a directory.
			try (Transaction txn = replaced.begin()) {
				replaced.createNode("Invoice");
				txn.commit();
			}
			assertEquals(1, replaced.stats().nodeCount());
		}
	}

	/** Replacing something that was never there is a create, so a restore does not have to know which case it is in. */
	@Test
	public void testReplacingOneThatDoesNotExistCreatesIt() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			GraphStore fresh = databases.replace("sales", null);
			assertEquals(0, fresh.stats().nodeCount());
			assertTrue(databases.exists("sales"));
			assertSame(fresh, databases.database("sales"));
		}
	}

	// ---------------------------------------------------------------- the legacy layout

	@Test
	public void testAStoreDirectoryIsRefusedWithTheCommandsToFixIt() throws Exception {
		Path legacy = stores.path().resolve("legacy");
		try (GraphStore store = GraphStore.open(legacy)) {
			store.createNode("N", Properties.of().set("i", 1));
		}
		assertTrue(GraphDatabases.isStoreDirectory(legacy));

		GraphStoreException thrown = assertThrows(GraphStoreException.class, () -> GraphDatabases.open(legacy));
		String message = thrown.getMessage();
		assertTrue(message.contains("not a root of databases"), message);
		assertTrue(message.contains("mkdir") && message.contains("mv"),
			"refusing without saying what to do instead is only half a message: " + message);
		// Nothing was moved. That is the whole reason for refusing rather than migrating.
		assertTrue(GraphDatabases.isStoreDirectory(legacy));
	}

	@Test
	public void testAnEmptyDirectoryIsARootRatherThanAStore() throws Exception {
		Path fresh = stores.path().resolve("fresh");
		Files.createDirectories(fresh);
		assertFalse(GraphDatabases.isStoreDirectory(fresh));
		try (GraphDatabases databases = GraphDatabases.open(fresh)) {
			assertEquals(List.of(GraphDatabases.DEFAULT_NAME), databases.names());
		}
	}

	@Test
	public void testClosingClosesEveryOpenDatabase() throws Exception {
		GraphStore sales;
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			sales = databases.create("sales");
			assertEquals(2, databases.openDatabases().size(),
				"create() opens, and the default was opened when the root was made");
		}
		// A closed store refuses work rather than misbehaving, and the LOCK is released — which the reopen proves.
		assertThrows(RuntimeException.class, sales::stats);
		try (GraphDatabases reopened = GraphDatabases.open(root())) {
			assertEquals(0, reopened.database("sales").stats().nodeCount());
		}
	}

	@Test
	public void testUsingAClosedRootFails() throws Exception {
		GraphDatabases databases = GraphDatabases.open(root());
		databases.close();
		assertThrows(IllegalStateException.class, () -> databases.database(GraphDatabases.DEFAULT_NAME));
		assertThrows(IllegalStateException.class, databases::names);
		// Closing twice is not an error.
		databases.close();
	}

	@Test
	public void testPathOfNamesTheDirectoryWithoutCreatingIt() throws IOException {
		try (GraphDatabases databases = GraphDatabases.open(root())) {
			assertEquals(root().resolve("sales"), databases.pathOf("sales"));
			assertFalse(Files.exists(root().resolve("sales")));
			assertThrows(IllegalArgumentException.class, () -> databases.pathOf("../escaped"));
		}
	}
}
