package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Several stores in one JVM, written to at the same time.
 *
 * <p>
 * Each store has its own writer lock, so unlike everything else in this package these writers really do run at the
 * same time as one another. That makes this the test for anything accidentally shared between stores — a static
 * counter, a dictionary, an id encoder with state. {@code ScalingTest} found exactly that class of defect once before,
 * a synchronized dictionary and a shared counter that together cost 63x aggregate read throughput; the same mistake
 * made in a place that affects correctness rather than speed would show up here and nowhere else.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/DATABASES.md">spec/DATABASES.md</a>
 */
@Tag("unit")
public class MultiStoreIsolationTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final List<String> NAMES = List.of("alpha", "bravo", "charlie", "delta");

	@Test
	public void testConcurrentWritersToDifferentStoresDoNotLeakIntoEachOther() throws Exception {
		try (GraphDatabases databases = GraphDatabases.open(stores.path(), TempStore.options())) {
			Map<String, GraphStore> open = new LinkedHashMap<>();
			for (String name : NAMES) {
				open.put(name, databases.create(name));
			}

			// One hammer thread per store, so every store is being written at the same moment.
			Hammer hammer = Hammer.named("multi-store").threads(NAMES.size()).opsPerThread(150);
			for (String name : NAMES) {
				GraphStore store = open.get(name);
				hammer.op(name, 1, rnd -> {
					long id = store.createNode(label(name), Properties.of()
						.set("owner", name)
						.set("n", (long) rnd.nextInt(1000)));
					if (rnd.nextBoolean()) {
						store.setNodeProperties(id, Properties.of().set("owner", name).set("n", (long) rnd.nextInt(1000)));
					}
				});
			}
			hammer.run().assertRan(NAMES.toArray(new String[0]));

			for (String name : NAMES) {
				GraphStore store = open.get(name);
				List<Long> ids = new ArrayList<>();
				store.forEachNode(ids::add);
				assertTrue(ids.size() > 0, "store '" + name + "' has no nodes, so it was never written");

				for (long id : ids) {
					assertEquals(name, store.nodeProperty(id, "owner"),
						"store '" + name + "' contains a node written by another store");
				}
				// And nothing from any other store is reachable by label.
				for (String other : NAMES) {
					int found = store.nodesByLabel(label(other)).size();
					if (other.equals(name)) {
						assertEquals(ids.size(), found, "store '" + name + "' cannot find its own nodes by label");
					} else {
						assertEquals(0, found, "store '" + name + "' can see nodes belonging to '" + other + "'");
					}
				}
				store.verify(VerifyLevel.FULL);
			}
		}
	}

	/**
	 * The {@code LOCK} file is the outermost layer of exclusion, and the one protecting a store from a second process
	 * — or, here, a second handle in the same one. Corrupting a store by opening it twice has to be impossible rather
	 * than merely discouraged.
	 */
	@Test
	public void testASecondHandleOnTheSameStoreIsRefused() throws Exception {
		try (GraphStore first = stores.open()) {
			first.createNode("N", Properties.of());

			Exception refused = assertThrows(Exception.class, () -> {
				try (GraphStore second = stores.open()) {
					second.createNode("Intruder", Properties.of());
				}
			}, "a second handle on an open store must be refused, or both would write through their own mappings");
			System.out.println("[multi-store] second open refused with " + refused.getClass().getSimpleName()
				+ ": " + refused.getMessage());

			// The refusal must not have damaged the store that was already open.
			first.createNode("Still", Properties.of());
			first.verify(VerifyLevel.FULL);
			assertEquals(0, first.nodesByLabel("Intruder").size(), "the refused handle still managed to write");
		}
	}

	private static String label(String name) {
		return "N_" + name;
	}
}
