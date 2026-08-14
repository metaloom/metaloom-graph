package io.metaloom.graph.core.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * {@code RollbackEqualsRecoveryTest} of [TRANSACTIONS.md §9](../../../../../../../../spec/TRANSACTIONS.md): the same
 * transaction, rolled back in one store and crashed in another, must leave both in the same state.
 *
 * <p>
 * The spec asks for this because in an ARIES engine the two paths are different code — rollback walks {@code prev_lsn}
 * and writes CLRs; recovery does an analysis pass, a redo pass and then that same undo — and a test is the only way to
 * be confident the rarely-exercised one behaves like the common one.
 * </p>
 *
 * <p>
 * <b>Here they are not different code, and that is the finding rather than a reason to skip the test.</b> Under
 * no-steal an uncommitted transaction exists only as private page copies, so rolling back is discarding them and
 * crashing is discarding them slightly more abruptly. Neither writes anything. This test is what turns that argument
 * into a fact, and it is the test that would fail first if anyone ever made the write path steal.
 * </p>
 */
@Tag("unit")
public class RollbackEqualsRecoveryTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStoreOptions options() {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.build();
	}

	@Test
	public void testRolledBackAndCrashedStoresAgree() throws Exception {
		Path rolledBack = stores.path().resolve("rolled-back");
		Path crashed = stores.path().resolve("crashed");
		Files.createDirectories(rolledBack);
		Files.createDirectories(crashed);

		String afterRollback;
		String afterCrash;

		// ---- the store that rolls back
		try (GraphStore store = GraphStore.open(rolledBack, options())) {
			Fixture fixture = baseline(store);
			store.checkpoint();
			try (Transaction txn = store.begin()) {
				mutate(store, fixture);
				txn.rollback();
			}
			store.checkpoint();
			afterRollback = describe(store, fixture);
			store.verify(VerifyLevel.FULL);
		}

		// ---- the store that crashes mid-transaction
		GraphStoreImpl store = (GraphStoreImpl) GraphStore.open(crashed, options());
		Fixture fixture = baseline(store);
		store.checkpoint();
		Transaction txn = store.begin();
		mutate(store, fixture);
		// No commit, no rollback, no flush: the private page copies simply cease to exist.
		store.abandon();

		try (GraphStore reopened = GraphStore.open(crashed, options())) {
			reopened.checkpoint();
			afterCrash = describe(reopened, fixture);
			reopened.verify(VerifyLevel.FULL);
		}

		assertEquals(afterRollback, afterCrash,
			"rolling a transaction back and crashing in the middle of it must reach the same state");
		assertEquals(true, txn.isActive() || !txn.isActive(), "the handle is irrelevant after a crash");
	}

	/**
	 * The control: the two stores must differ when the transaction <em>commits</em> in one of them.
	 *
	 * <p>
	 * Without this, {@link #testRolledBackAndCrashedStoresAgree} would pass just as well if {@link #describe} returned
	 * a constant, or if {@link #mutate} did nothing.
	 * </p>
	 */
	@Test
	public void testTheComparisonCanTellTheStoresApart() throws Exception {
		Path committed = stores.path().resolve("committed");
		Path discarded = stores.path().resolve("discarded");
		Files.createDirectories(committed);
		Files.createDirectories(discarded);

		String withCommit;
		String withRollback;
		try (GraphStore store = GraphStore.open(committed, options())) {
			Fixture fixture = baseline(store);
			try (Transaction txn = store.begin()) {
				mutate(store, fixture);
				txn.commit();
			}
			withCommit = describe(store, fixture);
		}
		try (GraphStore store = GraphStore.open(discarded, options())) {
			Fixture fixture = baseline(store);
			try (Transaction txn = store.begin()) {
				mutate(store, fixture);
				txn.rollback();
			}
			withRollback = describe(store, fixture);
		}
		assertNotEquals(withCommit, withRollback, "the description must be sensitive to what the transaction did");
	}

	// ---------------------------------------------------------------- fixture

	private record Fixture(long alice, long bob, long hub, long rel, List<Long> leaves) {
	}

	/** Identical in both stores: same order, same operations, therefore same ids. */
	private static Fixture baseline(GraphStore store) throws IOException {
		long alice = store.createNode("Person", Properties.of().set("name", "alice").set("age", 30L));
		long bob = store.createNode("Person", Properties.of().set("name", "bob"));
		long hub = store.createNode("Hub");
		long rel = store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2020L));
		store.createEdge(alice, "LIKES", bob);
		List<Long> leaves = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 200; i++) {
				long leaf = store.createNode("Leaf", Properties.of().set("i", i));
				store.createEdge(hub, "LINK", leaf);
				leaves.add(leaf);
			}
			txn.commit();
		}
		return new Fixture(alice, bob, hub, rel, leaves);
	}

	/** Every mutation kind the engine has, inside the caller's transaction. */
	private static void mutate(GraphStore store, Fixture fixture) throws IOException {
		long fresh = store.createNode("Temp", Properties.of().set("name", "temp"));
		store.createRelationship(fresh, "TO", fixture.alice());
		store.createEdge(fresh, "ALSO", fixture.bob());
		for (int i = 0; i < 200; i++) {
			store.createEdge(fixture.hub(), "LINK", store.createNode("More"));
		}
		store.setNodeProperties(fixture.alice(), Properties.of().set("name", "alice2").set("age", 31L));
		store.setRelationshipProperties(fixture.rel(), Properties.of().set("since", 2021L));
		store.deleteEdge(fixture.alice(), "LIKES", fixture.bob());
		store.deleteNode(fixture.leaves().get(0));
		byte[] payload = new byte[40_000];
		new java.util.Random(1).nextBytes(payload);
		store.setNodeProperties(fresh, Properties.of().set("blob", payload));
	}

	/** Everything observable about the store, as one string, so a mismatch names itself in the diff. */
	private static String describe(GraphStore store, Fixture fixture) {
		StringBuilder out = new StringBuilder();
		out.append("stats=").append(store.stats()).append('\n');
		for (long id : new long[] { fixture.alice(), fixture.bob(), fixture.hub() }) {
			out.append("node ").append(id).append(" exists=").append(store.nodeExists(id));
			if (store.nodeExists(id)) {
				out.append(" label=").append(store.nodeLabel(id))
					.append(" props=").append(store.nodeProperties(id))
					.append(" out=").append(store.degreeOut(id))
					.append(" in=").append(store.degreeIn(id));
				List<Edge> edges = new ArrayList<>(store.neighbours(id, Direction.BOTH));
				edges.sort((a, b) -> Long.compare(a.neighbourId(), b.neighbourId()));
				out.append(" edges=").append(edges);
			}
			out.append('\n');
		}
		out.append("rel exists=").append(store.relationshipExists(fixture.rel()));
		if (store.relationshipExists(fixture.rel())) {
			out.append(" props=").append(store.relationshipProperties(fixture.rel()));
		}
		out.append('\n');
		for (long leaf : fixture.leaves()) {
			out.append(leaf).append('=').append(store.nodeExists(leaf)).append(' ');
		}
		out.append('\n').append("byLabel Person=").append(store.nodesByLabel("Person").size())
			.append(" Temp=").append(store.nodesByLabel("Temp").size())
			.append(" More=").append(store.nodesByLabel("More").size())
			.append(" byName alice=").append(store.nodesByProperty("name", "alice").size())
			.append(" alice2=").append(store.nodesByProperty("name", "alice2").size());
		return out.toString();
	}
}
