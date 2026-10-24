package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Two writers giving one unique value to two nodes at once.
 *
 * <p>
 * Usually they meet in one B+tree leaf: both insert the same key prefix, both shift the same entries and write the
 * same count, and validation refuses the second. They do not meet when the prefix spans leaves. The tree does not
 * rebalance on delete, so a separator {@code (k, nX)} left behind by entries that have since gone still routes
 * {@code (k, n1 < nX)} to one leaf and {@code (k, n2 >= nX)} to another. Each writer's unique probe reads both leaves
 * and finds nothing, each inserts into a different one, the bytes are disjoint — and at snapshot isolation, which does
 * not validate reads, both used to commit with the same value.
 * </p>
 *
 * <p>
 * The setup builds exactly that: a thousand nodes share one value, so its prefix spans several leaves and leaves its
 * separators behind when every one of them is moved to a value of its own. The race is then between the first of
 * those nodes and the last, which sit on either side of every separator.
 * </p>
 */
@Tag("unit")
public class UniqueConstraintRaceTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int SPREAD = 1000;

	@Test
	public void testTwoWritersCannotShareAUniqueValueAcrossLeaves() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
			try (GraphStore store = stores.open()) {
				List<Long> ids = new ArrayList<>();
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < SPREAD; i++) {
						ids.add(store.createNode("Person", Properties.of().set("email", "shared@example.com")));
					}
					txn.commit();
				}
				try (Transaction txn = store.begin()) {
					for (int i = 0; i < SPREAD; i++) {
						store.setNodeProperties(ids.get(i), Properties.of().set("email", "p" + i + "@example.com"));
					}
					txn.commit();
				}
				store.createUniqueConstraint("email");

				long first = ids.get(0);
				long last = ids.get(SPREAD - 1);
				CountDownLatch otherWrote = new CountDownLatch(1);
				CountDownLatch mineCommitted = new CountDownLatch(1);
				AtomicReference<Throwable> otherFailed = new AtomicReference<>();
				Thread other = Thread.ofPlatform().name("other-writer").start(() -> {
					try (Transaction txn = store.begin()) {
						store.setNodeProperties(last, Properties.of().set("email", "shared@example.com"));
						otherWrote.countDown();
						mineCommitted.await(20, TimeUnit.SECONDS);
						txn.commit();
					} catch (Throwable e) {
						otherWrote.countDown();
						otherFailed.set(e);
					}
				});

				Throwable mineFailed = null;
				try (Transaction txn = store.begin()) {
					store.setNodeProperties(first, Properties.of().set("email", "shared@example.com"));
					assertTrue(otherWrote.await(20, TimeUnit.SECONDS));
					txn.commit();
				} catch (GraphStoreException e) {
					mineFailed = e;
				} finally {
					mineCommitted.countDown();
				}
				other.join();

				assertTrue(mineFailed != null || otherFailed.get() != null,
					"both writers committed the same unique value");
				assertEquals(1, store.nodesByProperty("email", "shared@example.com").size());
				store.verify(VerifyLevel.FULL);
			}
		});
	}
}
