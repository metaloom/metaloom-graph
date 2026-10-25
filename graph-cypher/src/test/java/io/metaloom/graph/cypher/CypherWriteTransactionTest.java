package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.exec.CypherTypeException;

/**
 * Where a write query's transaction comes from, and what happens to it when the query fails.
 *
 * <p>
 * The interesting cases are the two boundaries. A caller who already holds a transaction must have their transaction
 * used rather than a second one opened — {@code GraphStore.begin} does not refuse to nest and a nested transaction
 * quietly stops being rollbackable. A caller who holds a read <em>view</em> must be refused, because a writer reads the
 * current state and would silently not get the isolation the view was opened for.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a> section 4
 */
@Tag("unit")
public class CypherWriteTransactionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testAWriteQueryWithNoCallerTransactionCommitsOnItsOwn() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (n:Person {name: 'Ada'})");
			assertFalse(store.hasWriteTransaction(), "the query's own transaction must not outlive it");
		}
		// Reopened, so this is durability rather than "the same process remembers".
		try (GraphStore reopened = stores.open()) {
			assertEquals(1, reopened.nodesByLabel("Person").size());
			reopened.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testSeveralWriteQueriesInOneCallerTransactionCommitTogether() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				Cypher.query(store, "CREATE (n:Person {name: 'Ada'})");
				// The second query must see the first: a joined transaction reads its own writes.
				CypherResult seen = Cypher.query(store, "MATCH (n:Person) SET n.seen = true RETURN n.name AS name");
				assertEquals(List.of("Ada"), seen.column("name"));
				txn.commit();
			}
			assertEquals(1, store.nodesByLabel("Person").size());
			assertEquals(Boolean.TRUE, store.nodeProperty(store.nodesByLabel("Person").get(0), "seen"));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testRollingBackTheCallersTransactionUndoesEveryQueryInIt() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				Cypher.query(store, "CREATE (n:Person {name: 'Ada'})");
				Cypher.query(store, "CREATE (n:Person {name: 'Grace'})");
				txn.rollback();
			}
			assertEquals(List.of(), store.nodesByLabel("Person"),
				"the queries must not have committed anything of their own");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A query that fails part way leaves nothing behind in the caller's transaction — statement atomicity, which an
	 * explicit transaction in openCypher keeps by refusing to commit after a failed statement.
	 *
	 * <p>
	 * The plain DELETE below deletes the isolated node, then refuses the connected one. On its own transaction the
	 * query rolls back and nothing is lost ({@link #testAFailedWriteQueryLeavesTheSegmentFilesByteIdentical}). Joined
	 * to the caller's, the first deletion used to stay in it, and a caller that caught the type error and committed
	 * committed half a statement. The query now runs through {@code GraphStore.inOpenTransaction}, so the failure
	 * makes the caller's transaction rollback-only and the commit refuses.
	 * </p>
	 */
	@Test
	public void testAFailedQueryLeavesNothingInTheCallersTransaction() throws Exception {
		try (GraphStore store = stores.open()) {
			Cypher.query(store, "CREATE (:Iso), (:Hub)-[:R]->(:Leaf)");
			try (Transaction txn = store.begin()) {
				// :Iso sorts first and is deleted; :Hub still has its relationship and is refused.
				assertThrows(CypherTypeException.class, () -> Cypher.query(store,
					"MATCH (n) WHERE n:Iso OR n:Hub WITH n ORDER BY labels(n)[0] DESC DELETE n"));
				GraphStoreException refused = assertThrows(GraphStoreException.class, txn::commit);
				assertTrue(refused.getMessage().contains("rollback-only"), refused.getMessage());
			}
			assertEquals(1, store.nodesByLabel("Iso").size(), "the failed DELETE must not have taken :Iso with it");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAWriteQueryIsRefusedInsideAReadView() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Person");
			try (ReadTransaction view = store.beginRead()) {
				CypherWriteException thrown = assertThrows(CypherWriteException.class,
					() -> Cypher.query(store, "CREATE (n:Person)"));
				assertTrue(thrown.getMessage().contains("read view"), thrown.getMessage());
				// Reads inside the view keep working, which is the point of refusing rather than half-doing it.
				assertEquals(1, Cypher.query(store, "MATCH (n:Person) RETURN n").size());
			}
			assertEquals(1, store.nodesByLabel("Person").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testAWriteQueryLeavesNoViewAndNoTransactionBehind() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode(List.of("Person"), Properties.of().set("name", "Ada"));

			Cypher.query(store, "MATCH (n:Person) SET n.age = 30");
			assertEquals(0, store.snapshotStats().openViews(), "a write query must not open a read view at all");
			assertFalse(store.hasWriteTransaction());

			assertThrows(CypherTypeException.class, () -> Cypher.query(store, "MATCH (n:Person) SET n.bad = {a: 1}"));
			assertEquals(0, store.snapshotStats().openViews());
			assertFalse(store.hasWriteTransaction(), "and a failed one must not leave the writer lock held");

			// The proof that the lock really is free: another thread can take it without waiting.
			assertTrue(writerLockIsFree(store), "a leaked write transaction would block every writer in the process");
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * A query that fails partway must leave the data files exactly as they were.
	 *
	 * <p>
	 * Not "the store behaves as though nothing happened" but "nothing happened" — the same standard
	 * {@code RollbackTest} holds the engine to. The query below creates two nodes and then fails on a property a node
	 * cannot hold, so there is real uncommitted work for the rollback to discard.
	 * </p>
	 */
	@Test
	public void testAFailedWriteQueryLeavesTheSegmentFilesByteIdentical() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode(List.of("Person"), Properties.of().set("name", "Ada"));
			store.createNode(List.of("Person"), Properties.of().set("name", "Grace"));
			store.checkpoint();
			String before = digestSegments(stores.path());

			assertThrows(CypherTypeException.class,
				() -> Cypher.query(store, "MATCH (n:Person) CREATE (c:Copy) SET c.owner = n"));

			assertEquals(before, digestSegments(stores.path()), "a failed write must not reach a data file");
			assertEquals(List.of(), store.nodesByLabel("Copy"), "including the nodes it had already created");
			assertEquals(2, store.nodesByLabel("Person").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testTheDigestActuallyNoticesAChange() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("Anchor");
			store.checkpoint();
			String before = digestSegments(stores.path());

			Cypher.query(store, "CREATE (n:Committed)");
			store.checkpoint();

			assertFalse(before.equals(digestSegments(stores.path())),
				"a committed write must change the files, or the assertion above proves nothing");
		}
	}

	/**
	 * A reader's view is unaffected by a Cypher write committing underneath it.
	 *
	 * <p>
	 * On another thread, because a view is thread-confined and a write query on the same thread is refused. This is the
	 * isolation claim of spec/CYPHER.md section 4 tested through the write path rather than through the Java API.
	 * </p>
	 */
	@Test
	public void testACommittedWriteIsInvisibleToAViewThatOpenedBeforeIt() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode(List.of("Person"), Properties.of().set("name", "Ada"));

			CountDownLatch viewOpen = new CountDownLatch(1);
			CountDownLatch written = new CountDownLatch(1);
			AtomicReference<Object> seenAfterWrite = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();

			Thread reader = new Thread(() -> {
				try (ReadTransaction view = store.beginRead()) {
					assertEquals("Ada", store.nodeProperty(node, "name"));
					viewOpen.countDown();
					assertTrue(written.await(10, TimeUnit.SECONDS));
					seenAfterWrite.set(store.nodeProperty(node, "name"));
				} catch (Throwable e) {
					failure.set(e);
					viewOpen.countDown();
				}
			});
			reader.start();

			assertTrue(viewOpen.await(10, TimeUnit.SECONDS));
			Cypher.query(store, "MATCH (n:Person) SET n.name = 'Grace'");
			written.countDown();
			reader.join(10_000);

			assertEquals(null, failure.get(), String.valueOf(failure.get()));
			assertEquals("Ada", seenAfterWrite.get(), "the view was fixed before the write and must stay there");
			assertEquals("Grace", store.nodeProperty(node, "name"), "while the store itself has moved on");
			store.verify(VerifyLevel.FULL);
		}
	}

	// ---------------------------------------------------------------- plumbing

	private static boolean writerLockIsFree(GraphStore store) throws InterruptedException {
		AtomicReference<Boolean> acquired = new AtomicReference<>(false);
		Thread other = new Thread(() -> {
			try (Transaction txn = store.begin()) {
				acquired.set(true);
				txn.rollback();
			}
		});
		other.start();
		other.join(5_000);
		return acquired.get();
	}

	/** A digest over every segment file. The log and manifest move when a checkpoint runs; the data files must not. */
	private static String digestSegments(Path storeDir) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(storeDir.resolve("seg"))) {
			walk.filter(Files::isRegularFile).sorted().forEach(files::add);
		}
		for (Path file : files) {
			digest.update(file.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
