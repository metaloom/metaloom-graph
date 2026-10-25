package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.NeighbourCursor;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.graph.core.fixture.StressRun;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * A thread that already holds the apply latch shared must never ask for it again.
 *
 * <p>
 * {@link java.util.concurrent.locks.StampedLock} read holds are not reentrant in the way that matters: a second
 * {@code readLock()} from a thread that holds one is an ordinary acquisition, and when its fast path loses a race to
 * another reader it queues — behind a commit already waiting for the latch exclusively, which is waiting for the hold
 * this very thread still has. The engine nests in three places that are easy to miss: a mutation that calls one of
 * the store's own public reads (setting a property on a vector-indexed node asks {@code nodeLabels} for its
 * membership), a read made from inside a scan callback, and a cursor opened while another is open.
 * </p>
 *
 * <p>
 * Found by {@code EverythingAtOnceStressTest}: within seconds, a transfer inside {@code setNodeProperties} parked in
 * {@code StampedLock.acquireRead} under {@code maintainVectorMembership -> nodeLabels}, a commit parked in
 * {@code writeLock}, and every other writer behind the commit lock. Here the same three nestings run beside committing
 * writers and a crowd of scanners whose read acquisitions make the fast path lose.
 * </p>
 */
@Tag("unit")
public class NestedApplyLatchTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int DIMS = 4;

	@Test
	public void testNestedReadsBesideCommitsNeverWedge() throws Exception {
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.vectorDimensions(DIMS)
			.walSyncMode(WalSyncMode.ASYNC)
			.build();
		StressRun.withStore(stores.open(options), store -> {
			store.createVectorIndex(VectorIndexDefinition.of("Doc", "emb", DIMS, VectorMetric.COSINE));
			List<Long> docs = new ArrayList<>();
			long hub;
			try (Transaction txn = store.begin()) {
				hub = store.createNode("Hub");
				for (int i = 0; i < 64; i++) {
					long doc = store.createNode("Doc", Properties.of().set("emb", new float[] { 1, i, 0, 1 }));
					docs.add(doc);
					store.createEdge(hub, "HAS", doc);
				}
				txn.commit();
			}
			AtomicLong mutations = new AtomicLong();
			AtomicLong nested = new AtomicLong();
			StressRun.named("nested-latch")
				.millis(2_500)
				.watchdogSeconds(30)
				// A mutation whose own code calls a public read: maintainVectorMembership -> nodeLabels.
				.platform("vector-writer", 3, rnd -> {
					store.setNodeProperties(docs.get(rnd.nextInt(docs.size())),
						Properties.of().set("emb", new float[] { rnd.nextFloat(), 1, 0, 1 }));
					mutations.incrementAndGet();
					return true;
				})
				.virtual("vector-writer", 2, rnd -> {
					store.setNodeProperties(docs.get(rnd.nextInt(docs.size())),
						Properties.of().set("emb", new float[] { 1, rnd.nextFloat(), 0, 1 }));
					mutations.incrementAndGet();
					return true;
				})
				// A read from inside a scan callback, and a cursor opened inside another cursor.
				.platform("nesting-reader", 4, rnd -> {
					if (rnd.nextBoolean()) {
						store.forEachNodeWhile(id -> {
							store.nodeLabels(id);
							return true;
						});
					} else {
						try (NeighbourCursor outer = store.neighbourCursor(hub, Direction.OUT, null)) {
							if (outer.next()) {
								try (NeighbourCursor inner = store.neighbourCursor(outer.edge().neighbourId(),
									Direction.IN, null)) {
									while (inner.next()) {
										inner.edge();
									}
								}
							}
						}
					}
					nested.incrementAndGet();
					return true;
				})
				.virtual("scanner", 4, rnd -> {
					store.forEachNode(id -> {
					});
					return true;
				})
				.run();
			assertTrue(mutations.get() > 50, "only " + mutations.get() + " mutations committed");
			assertTrue(nested.get() > 50, "only " + nested.get() + " nested reads ran");
			System.out.println("[nested-latch] mutations=" + mutations.get() + " nested=" + nested.get());
			store.verify(VerifyLevel.FULL);
		});
	}
}
