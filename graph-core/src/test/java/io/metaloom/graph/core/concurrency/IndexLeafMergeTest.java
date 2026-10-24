package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Concurrent property updates on different nodes, then every node looked up by the value it holds.
 *
 * <p>
 * The answer is checked through the API rather than through {@code verify(FULL)} alone, because an index that has
 * lost an entry is not a report — it is {@code nodesByProperty} silently not returning a node.
 * </p>
 */
@Tag("unit")
public class IndexLeafMergeTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final int NODES = 48;
	private static final int WRITERS = 6;
	private static final int UPDATES = 400;

	@Test
	public void testConcurrentUpdatesOfDifferentNodesKeepEveryEntry() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			try (GraphStore store = stores.open()) {
				List<Long> ids = new ArrayList<>();
				for (int i = 0; i < NODES; i++) {
					ids.add(store.createNode("Account", Properties.of().set("balance", (long) i)));
				}
				AtomicReference<Throwable> failed = new AtomicReference<>();
				List<Thread> writers = new ArrayList<>();
				for (int t = 0; t < WRITERS; t++) {
					int writer = t;
					writers.add(Thread.ofPlatform().start(() -> {
						try {
							for (int i = 0; i < UPDATES; i++) {
								// Each writer owns its own nodes, so no two writers ever update the same record.
								int slot = writer + WRITERS * ThreadLocalRandom.current().nextInt(NODES / WRITERS);
								store.setNodeProperties(ids.get(slot), Properties.of().set("balance",
									(long) ThreadLocalRandom.current().nextInt(64)));
							}
						} catch (Throwable e) {
							failed.compareAndSet(null, e);
						}
					}));
				}
				for (Thread writer : writers) {
					writer.join();
				}
				assertEquals(null, failed.get());

				List<String> missing = new ArrayList<>();
				for (long id : ids) {
					Object balance = store.nodeProperty(id, "balance");
					if (!store.nodesByProperty("balance", balance).contains(id)) {
						missing.add(id + "=" + balance);
					}
				}
				assertTrue(missing.isEmpty(), "nodesByProperty does not find " + missing.size() + " of " + NODES
					+ " nodes by the value they hold: " + missing);
				store.verify(VerifyLevel.FULL);
			}
		});
	}
}
