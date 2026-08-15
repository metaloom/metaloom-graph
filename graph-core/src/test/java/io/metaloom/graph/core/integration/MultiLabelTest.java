package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Several labels per node: the storage half of Cypher support.
 *
 * <p>
 * The label set reuses the record's dead {@code version_ptr} and {@code xmin} bytes, so the interesting cases are the
 * boundaries — one, three (the inline limit), and four or more (the spill into a property block) — and what happens to
 * the label index and the overflow block when a node is deleted or compacted.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 5
 */
@Tag("unit")
public class MultiLabelTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testOneLabelStillBehaves() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person");

			assertEquals(List.of("Person"), store.nodeLabels(node));
			assertEquals("Person", store.nodeLabel(node), "the single-label accessor must keep working unchanged");
			assertTrue(store.nodeHasLabel(node, "Person"));
			assertFalse(store.nodeHasLabel(node, "Vehicle"));
			assertEquals(List.of(node), store.nodesByLabel("Person"));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testNoLabelIsNotOneEmptyLabel() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode(List.of());
			assertEquals(List.of(), store.nodeLabels(node));
			assertFalse(store.nodeHasLabel(node, "Person"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/** Up to three labels live in the record itself; the spill path must not be reached. */
	@Test
	public void testInlineLabels() throws Exception {
		try (GraphStore store = stores.open()) {
			long two = store.createNode(List.of("Person", "Employee"));
			long three = store.createNode(List.of("Person", "Employee", "Manager"));

			assertEquals(List.of("Person", "Employee"), store.nodeLabels(two), "order must be preserved");
			assertEquals(List.of("Person", "Employee", "Manager"), store.nodeLabels(three));
			assertEquals(0, store.stats().propertyCellCount(),
				"three labels must fit the record; a property cell here means the spill path ran");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testOverflowBeyondThreeLabels() throws Exception {
		try (GraphStore store = stores.open()) {
			List<String> many = new ArrayList<>();
			for (int i = 0; i < 40; i++) {
				many.add("L" + i);
			}
			long node = store.createNode(many, Properties.of().set("name", "wide"));

			assertEquals(many, store.nodeLabels(node), "every label must survive the spill, in order");
			assertEquals("L0", store.nodeLabel(node));
			for (String label : many) {
				assertTrue(store.nodeHasLabel(node, label), label + " must be present");
				assertEquals(List.of(node), store.nodesByLabel(label),
					"the index must carry an entry per label, not just the first");
			}
			assertEquals("wide", store.nodeProperty(node, "name"),
				"the node's own properties must be unaffected by the overflow block beside them");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testEveryLabelIsIndexedAndDeletingRemovesThemAll() throws Exception {
		try (GraphStore store = stores.open()) {
			long alice = store.createNode(List.of("Person", "Employee"));
			long bob = store.createNode(List.of("Person", "Contractor"));
			long widget = store.createNode(List.of("Thing"));

			assertEquals(2, store.nodesByLabel("Person").size(), "both people, found by a shared label");
			assertEquals(List.of(alice), store.nodesByLabel("Employee"));
			assertEquals(List.of(bob), store.nodesByLabel("Contractor"));
			assertEquals(List.of(widget), store.nodesByLabel("Thing"));

			store.deleteNode(alice);

			assertEquals(List.of(bob), store.nodesByLabel("Person"), "the delete must reach the shared label");
			assertEquals(List.of(), store.nodesByLabel("Employee"),
				"and every other label the node carried, or the index keeps a dangling entry");
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testDeletingAnOverflowedNodeFreesItsBlock() throws Exception {
		try (GraphStore store = stores.open()) {
			List<String> many = new ArrayList<>();
			for (int i = 0; i < 30; i++) {
				many.add("L" + i);
			}
			long node = store.createNode(many);
			assertTrue(store.stats().propertyCellCount() > 0, "the overflow block must exist to be freed");

			store.deleteNode(node);

			assertEquals(0, store.stats().propertyCellCount(),
				"the overflow block is owned by the node and must go with it");
			for (String label : many) {
				assertEquals(List.of(), store.nodesByLabel(label));
			}
			// The orphan sweep is the check that matters here: an unfreed overflow block is leaked space that
			// nothing would ever reclaim.
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testDuplicateLabelsAreDroppedRatherThanStoredTwice() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode(List.of("Person", "Person", "Employee", "Person"));

			assertEquals(List.of("Person", "Employee"), store.nodeLabels(node));
			assertEquals(List.of(node), store.nodesByLabel("Person"),
				"one index entry, or the delete below leaves one behind");

			store.deleteNode(node);
			assertEquals(List.of(), store.nodesByLabel("Person"));
			store.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testLabelsSurviveAReopen() throws Exception {
		long inline;
		long overflowed;
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			many.add("L" + i);
		}
		try (GraphStore store = stores.open()) {
			inline = store.createNode(List.of("Person", "Employee"));
			overflowed = store.createNode(many);
		}
		try (GraphStore reopened = stores.open()) {
			assertEquals(List.of("Person", "Employee"), reopened.nodeLabels(inline));
			assertEquals(many, reopened.nodeLabels(overflowed));
			assertEquals(List.of(inline), reopened.nodesByLabel("Employee"));
			assertEquals(List.of(overflowed), reopened.nodesByLabel("L11"));
			reopened.verify(VerifyLevel.FULL);
		}
	}

	@Test
	public void testLabelsSurviveCompaction() throws Exception {
		try (GraphStore store = stores.open()) {
			List<String> many = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				many.add("L" + i);
			}
			List<Long> keep = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				for (int i = 0; i < 200; i++) {
					// Deleted below, to make the store sparse enough that compaction has work to do.
					store.createNode(List.of("Filler", "Doomed"), Properties.of().set("i", i));
				}
				for (int i = 0; i < 20; i++) {
					keep.add(store.createNode(many, Properties.of().set("keep", i)));
				}
				txn.commit();
			}
			for (long doomed : new ArrayList<>(store.nodesByLabel("Doomed"))) {
				store.deleteNode(doomed);
			}

			store.compact();

			// Compaction is the one operation that invalidates ids, so the survivors are re-found by label rather
			// than by the ids captured above.
			List<Long> found = store.nodesByLabel("L19");
			assertEquals(20, found.size(), "every overflowed node must still be reachable by a spilled label");
			for (long node : found) {
				assertEquals(many, store.nodeLabels(node),
					"a relocated overflow block must have been repointed, not left dangling");
			}
			assertEquals(keep.size(), store.nodesByLabel("L0").size());
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * The compatibility claim: a store written before multi-label existed must read back as single-label.
	 *
	 * <p>
	 * The label set occupies the bytes that held {@code version_ptr} and {@code xmin}, which a version 2 build wrote
	 * as zero on every node. So the claim is really "zeros there decode as one label", and this asserts it against a
	 * record with those bytes explicitly zeroed rather than against an argument.
	 * </p>
	 */
	@Test
	public void testAVersionTwoNodeReadsAsSingleLabel() throws Exception {
		try (GraphStore store = stores.open()) {
			long node = store.createNode("Person", Properties.of().set("name", "legacy"));
			zeroTheFormerMvccFields(store, node);

			assertEquals(List.of("Person"), store.nodeLabels(node));
			assertEquals("Person", store.nodeLabel(node));
			assertTrue(store.nodeHasLabel(node, "Person"));
			assertEquals("legacy", store.nodeProperty(node, "name"));
			assertEquals(List.of(node), store.nodesByLabel("Person"));
			store.verify(VerifyLevel.FULL);
		}
	}

	/**
	 * Writes zeros over offsets 24..39 of a node record, which is exactly what a version 2 build left there.
	 * <p>
	 * Reaches past the public API deliberately: the point is to produce the byte pattern an older build wrote, which
	 * no current code path can produce any more.
	 */
	private static void zeroTheFormerMvccFields(GraphStore store, long nodeId) throws Exception {
		io.metaloom.graph.core.store.GraphStoreImpl impl = (io.metaloom.graph.core.store.GraphStoreImpl) store;
		try (Transaction txn = impl.begin()) {
			java.lang.reflect.Field field = impl.getClass().getDeclaredField("segmentSets");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<io.metaloom.graph.core.id.Kind, io.metaloom.graph.core.segment.SegmentSet> sets =
				(java.util.Map<io.metaloom.graph.core.id.Kind, io.metaloom.graph.core.segment.SegmentSet>) field
					.get(impl);
			java.lang.foreign.MemorySegment record = sets.get(io.metaloom.graph.core.id.Kind.NODE).mutable(nodeId);
			record.set(io.metaloom.graph.core.util.Layouts.LONG, 24, 0L);
			record.set(io.metaloom.graph.core.util.Layouts.LONG, 32, 0L);
			txn.commit();
		}
	}
}
