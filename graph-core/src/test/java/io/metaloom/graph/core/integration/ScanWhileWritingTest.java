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
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * A node scan reads the free-slot bitmap a word — sixty-four slots — at a time. Inside a write transaction the scan's
 * own callback can free or claim slots in the word being walked, and the scan has to see that exactly as the
 * bit-at-a-time walk it replaced did: a node deleted ahead of the cursor is not reported, and a slot claimed ahead of
 * it is.
 */
@Tag("unit")
public class ScanWhileWritingTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testANodeDeletedAheadOfTheScanInTheSameWordIsNotReported() throws Exception {
		try (GraphStore store = stores.open()) {
			long[] ids = new long[10];
			for (int i = 0; i < ids.length; i++) {
				ids[i] = store.createNode("N");
			}
			List<Long> seen = new ArrayList<>();
			try (Transaction txn = store.begin()) {
				store.forEachNodeWhile(id -> {
					seen.add(id);
					if (id == ids[0]) {
						try {
							// Slots 0..9 share one bitmap word, so this clears a bit the scan has already read.
							store.deleteNode(ids[5]);
						} catch (java.io.IOException e) {
							throw new java.io.UncheckedIOException(e);
						}
					}
					return true;
				});
				txn.rollback();
			}
			assertEquals(ids.length - 1, seen.size(), "every node but the one deleted mid-scan: " + seen);
			assertFalse(seen.contains(ids[5]), "a node deleted ahead of the scan must not be reported");
			assertTrue(store.nodeExists(ids[5]), "and the rollback put it back");
		}
	}

	@Test
	public void testANodeCreatedAheadOfTheScanInTheSameWordIsReported() throws Exception {
		try (GraphStore store = stores.open()) {
			long[] ids = new long[4];
			for (int i = 0; i < ids.length; i++) {
				ids[i] = store.createNode("N");
			}
			List<Long> seen = new ArrayList<>();
			long[] created = new long[1];
			try (Transaction txn = store.begin()) {
				store.forEachNodeWhile(id -> {
					seen.add(id);
					if (id == ids[0]) {
						try {
							created[0] = store.createNode("New");
						} catch (java.io.IOException e) {
							throw new java.io.UncheckedIOException(e);
						}
					}
					return true;
				});
				txn.rollback();
			}
			// The new node takes the next slot, inside the word the scan is on but past the high water mark the scan read
			// when it started — so neither walk reports it, the bit-at-a-time one included.
			assertEquals(List.of(ids[0], ids[1], ids[2], ids[3]), seen);
		}
	}
}
