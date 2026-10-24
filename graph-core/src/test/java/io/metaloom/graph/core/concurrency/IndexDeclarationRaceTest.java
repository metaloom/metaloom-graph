package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * An index or constraint declared while another writer is mid-transaction.
 *
 * <p>
 * The declaration and the write meet nowhere on disk: the declaring transaction's backfill scan reads its own snapshot,
 * which does not hold the writer's uncommitted node, and the writer's maintenance hook reads the catalog as of
 * <em>its</em> snapshot, which does not hold the declaration. Both write bytes the other never touches, so conflict
 * detection has nothing to find and both commit — leaving an index that permanently misses one node, or a unique
 * constraint declared over data that already breaks it. Each test opens the writer first, starts the declaration on
 * another thread, gives it time to run to completion if nothing stops it, and only then commits the writer.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/INDEX.md">spec/INDEX.md</a> section 3.3
 */
@Tag("unit")
public class IndexDeclarationRaceTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/** How long the declaring thread is given to finish on its own before the writer commits underneath it. */
	private static final long DECLARATION_HEAD_START_MS = 500;

	@FunctionalInterface
	private interface Declaration {
		void run() throws Exception;
	}

	/** Runs the declaration on its own thread while the caller's transaction is open, then commits that transaction. */
	private static Throwable declareWhileOpen(Transaction open, Declaration declaration) throws Exception {
		AtomicReference<Throwable> failed = new AtomicReference<>();
		Thread declaring = Thread.ofPlatform().name("declaring").start(() -> {
			try {
				declaration.run();
			} catch (Throwable e) {
				failed.set(e);
			}
		});
		declaring.join(DECLARATION_HEAD_START_MS);
		open.commit();
		declaring.join();
		return failed.get();
	}

	private void warm(GraphStore store) throws Exception {
		// The catalog page and the trees exist before the race, so it is the declaration that is on trial and not the
		// one-off allocation of the first index page.
		store.createNode("Warm", Properties.of().set("email", "warm@example.com"));
	}

	@Test
	public void testACompositeIndexDeclaredBesideAWriterCoversItsNode() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			try (GraphStore store = stores.open()) {
				warm(store);
				long node;
				Throwable failed;
				try (Transaction txn = store.begin()) {
					node = store.createNode("Person", Properties.of().set("email", "ada@example.com"));
					failed = declareWhileOpen(txn, () -> store.createIndex("Person", "email"));
				}
				assertEquals(null, failed);
				assertTrue(store.hasIndex("Person", "email"));
				assertEquals(List.of(node), store.nodesByLabelAndProperty("Person", "email", "ada@example.com"),
					"the declared index answers with the composite probe alone, so a node it missed is never found");
				store.verify(VerifyLevel.FULL);
			}
		});
	}

	@Test
	public void testAUniqueConstraintIsNotDeclaredOverADuplicateWrittenBesideIt() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			try (GraphStore store = stores.open()) {
				warm(store);
				store.createNode("Person", Properties.of().set("email", "ada@example.com"));
				Throwable failed;
				try (Transaction txn = store.begin()) {
					store.createNode("Person", Properties.of().set("email", "ada@example.com"));
					failed = declareWhileOpen(txn, () -> store.createUniqueConstraint("email"));
				}
				assertNotNull(failed, "the constraint was declared over two nodes sharing a value");
				assertInstanceOf(ConstraintViolationException.class, failed);
				assertEquals(List.of(), store.uniqueConstraints());
				store.verify(VerifyLevel.FULL);
			}
		});
	}

	@Test
	public void testALabelScopedUniqueConstraintIsNotDeclaredOverADuplicateWrittenBesideIt() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			try (GraphStore store = stores.open()) {
				warm(store);
				store.createNode("Person", Properties.of().set("email", "ada@example.com"));
				Throwable failed;
				try (Transaction txn = store.begin()) {
					store.createNode("Person", Properties.of().set("email", "ada@example.com"));
					failed = declareWhileOpen(txn, () -> store.createUniqueConstraint("Person", "email"));
				}
				assertInstanceOf(ConstraintViolationException.class, failed,
					"the constraint was declared over two :Person sharing a value");
				assertEquals(false, store.hasIndex("Person", "email"));
				store.verify(VerifyLevel.FULL);
			}
		});
	}

	@Test
	public void testAVectorIndexDeclaredBesideAWriterCoversItsNode() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			GraphStoreOptions options = GraphStoreOptions.builder()
				.pageSize(Geometry.DEFAULT_PAGE_SIZE)
				.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
				.vectorDimensions(3)
				.build();
			try (GraphStore store = stores.open(options)) {
				warm(store);
				long early = store.createNode(List.of("Doc"),
					Properties.of().set("embedding", new float[] { 0f, 1f, 0f }));
				long node;
				Throwable failed;
				try (Transaction txn = store.begin()) {
					node = store.createNode(List.of("Doc"),
						Properties.of().set("embedding", new float[] { 1f, 0f, 0f }));
					failed = declareWhileOpen(txn, () -> store.createVectorIndex(
						VectorIndexDefinition.of("Doc", "embedding", 3, VectorMetric.COSINE)));
				}
				assertEquals(null, failed);
				List<Neighbour> found = store.nearest("Doc", "embedding", new float[] { 1f, 0f, 0f }, 5, null);
				assertEquals(2, found.size(), "both :Doc nodes are members: " + found);
				assertEquals(node, found.get(0).nodeId());
				assertEquals(early, found.get(1).nodeId());
				store.verify(VerifyLevel.FULL);
			}
		});
	}
}
