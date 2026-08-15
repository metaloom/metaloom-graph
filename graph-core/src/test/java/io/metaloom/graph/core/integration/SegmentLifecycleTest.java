package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.id.StaleIdException;
import io.metaloom.graph.core.segment.Geometry;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * Slot reuse, generations, segment rollover, sparseness and corruption detection.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 12
 */
@Tag("unit")
public class SegmentLifecycleTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testFreedSlotsAreReusedAndOldIdsGoStale() throws Exception {
		try (GraphStore store = stores.open()) {
			List<Long> created = new ArrayList<>();
			long liveBefore;
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < 100; i++) {
					created.add(store.createNode("N", Properties.of().set("i", i)));
				}
				liveBefore = store.stats().nodeCount();
				for (long id : created) {
					store.deleteNode(id);
				}
				txn.commit();
			}
			assertEquals(liveBefore - 100, store.stats().nodeCount());

			// Reuse is FIFO, so the first slot freed is the first handed back.
			long reused = store.createNode("Fresh");
			long first = created.get(0);
			assertEquals(ElementId.slot(first), ElementId.slot(reused), "FIFO reuse must recycle the oldest slot");
			assertNotEquals(ElementId.gen(first), ElementId.gen(reused), "The generation must advance");
			assertNotEquals(first, reused);

			// The old id must be detected, not silently resolved to the new occupant.
			assertFalse(store.nodeExists(first));
			assertThrows(StaleIdException.class, () -> store.nodeLabel(first));
			assertEquals("Fresh", store.nodeLabel(reused));
			store.verify();
		}
	}

	@Test
	public void testFreeListSurvivesAReopen() throws Exception {
		List<Long> created = new ArrayList<>();
		try (GraphStore store = stores.open()) {
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < 50; i++) {
					created.add(store.createNode("N"));
				}
				for (int i = 0; i < 50; i += 2) {
					store.deleteNode(created.get(i));
				}
				txn.commit();
			}
			store.checkpoint();
		}
		try (GraphStore store = stores.open()) {
			// The bitmap is the durable free list; the proof of concept kept it only in memory and lost it here.
			assertEquals(25, store.stats().nodeCount());
			for (int i = 1; i < 50; i += 2) {
				assertTrue(store.nodeExists(created.get(i)), "A surviving node must still resolve");
			}
			for (int i = 0; i < 50; i += 2) {
				assertFalse(store.nodeExists(created.get(i)));
			}
			store.verify();
		}
	}

	@Test
	public void testSegmentRollover() throws Exception {
		// One MiB of node capacity is 16384 slots, so this crosses into a second segment.
		GraphStoreOptions options = GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(1024 * 1024)
			.build();
		long firstOfSecondSegment = ElementId.NULL;
		try (GraphStore store = stores.open(options)) {
			GraphStoreImpl impl = (GraphStoreImpl) store;
			int slotsPerSegment = impl.geometry().slotsPerSegment(Kind.NODE);
			try (io.metaloom.graph.core.api.Transaction txn = store.begin()) {
				for (int i = 0; i < slotsPerSegment + 10; i++) {
					long id = store.createNode("N");
					if (ElementId.segment(id) == 1 && firstOfSecondSegment == ElementId.NULL) {
						firstOfSecondSegment = id;
					}
				}
				txn.commit();
			}
			assertEquals(2, impl.segments(Kind.NODE).segmentCount());
			assertEquals(1, ElementId.segment(firstOfSecondSegment));
			assertEquals(0, ElementId.slot(firstOfSecondSegment));
			assertTrue(store.nodeExists(firstOfSecondSegment));
			store.checkpoint();
		}
		try (GraphStore store = stores.open(options)) {
			assertTrue(store.nodeExists(firstOfSecondSegment));
			assertEquals("N", store.nodeLabel(firstOfSecondSegment));
		}
	}

	@Test
	public void testSegmentsCommitFarLessThanTheyReserve() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("N");
			store.checkpoint();
			long nominal = nominalSize(stores.path());
			long committed = store.stats().storageBytes();
			assertTrue(nominal > 8 * 1024 * 1024, "Segments are nominally large: " + nominal);
			assertTrue(committed < nominal / 16,
				"A one node store must commit almost nothing: " + committed + " of " + nominal + " nominal");
			// True block level sparseness needs the native statx binding of Phase 6; the JDK does not expose it.
		}
	}

	@Test
	public void testGeometryMismatchIsRefused() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createNode("N");
		}
		GraphStoreOptions other = GraphStoreOptions.builder()
			.pageSize(4096)
			.segmentCapacity(TempStore.TEST_SEGMENT_CAPACITY)
			.build();
		assertThrows(FormatVersionException.class, () -> stores.open(other).close());
	}

	@Test
	public void testCorruptionIsDetected() throws Exception {
		long id;
		try (GraphStore store = stores.open()) {
			id = store.createNode("Person", Properties.of().set("name", "Wes Anderson"));
			store.checkpoint();
			store.verify();
		}

		// Flip a byte inside the node data region, as a failing drive would.
		GraphStoreImpl probe = (GraphStoreImpl) stores.open();
		long dataStart = probe.geometry().dataStart(Kind.NODE);
		probe.close();
		Path segment = stores.path().resolve("seg").resolve("node").resolve("node-000000.seg");
		try (RandomAccessFile file = new RandomAccessFile(segment.toFile(), "rw")) {
			file.seek(dataStart + 1);
			int original = file.readByte();
			file.seek(dataStart + 1);
			file.writeByte(original ^ 0x40);
		}

		try (GraphStore store = stores.open()) {
			assertThrows(PageCorruptException.class, store::verify);
			assertTrue(id != ElementId.NULL);
		}
	}

	private static long nominalSize(Path root) throws IOException {
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(Files::isRegularFile).mapToLong(file -> {
				try {
					return Files.size(file);
				} catch (IOException e) {
					return 0L;
				}
			}).sum();
		}
	}
}
