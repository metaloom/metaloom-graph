package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.TransactionConflictException;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.id.ElementId;

/**
 * Writers overlap, and what happens when they meet.
 *
 * <p>
 * This is the direct evidence for the thing {@code TRANSACTIONS.md} spent a phase saying was not built. Everything else
 * in this package exercises concurrency through a workload; these are the four statements the feature actually makes,
 * each written so that it could only pass with concurrent writers and would have failed — or deadlocked — against the
 * single writer lock:
 * </p>
 *
 * <ol>
 * <li>Two transactions are open at the same time. Under one writer lock the second {@code begin()} blocks until the
 * first commits, so a barrier reached from inside both is a deadlock rather than a pass.</li>
 * <li>Two writers on <b>one page</b>, writing different records, both commit and both survive. This is the defect that
 * was named as the blocker: the commit used to work out what it had changed by diffing against the live mapping, so
 * whichever applied second carried the other's bytes as they were <em>before</em> and silently reverted them.</li>
 * <li>Two writers on <b>one record</b>: the first to commit keeps its value and the second is refused, rather than
 * overwriting a state it never read.</li>
 * <li>A refused transaction leaves nothing behind — no half-applied write, no leaked slot, no accounting drift.</li>
 * </ol>
 *
 * <p>
 * <b>Seen to fail.</b> Putting the commit back to diffing against the live mapping — the pre-concurrency behaviour —
 * fails the first two of these. What it does <em>not</em> do is lose data, and that is worth knowing: the stale bytes
 * the diff picks up are bytes the other writer changed, so validation refuses the commit instead of applying the
 * revert. The base image is what lets two writers on one page both succeed; conflict detection is what stops the
 * absence of it being silent.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 6
 */
@Tag("unit")
public class ConcurrentWriterTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	/**
	 * Gets the shared index structures built and settled before the writers start.
	 *
	 * <p>
	 * Every one of these tests is about what two writers do to <em>their own</em> records, and without this they
	 * measure something else entirely: the first labelled node in a store builds the index catalog page, which has to
	 * be the first INDEX allocation and therefore cannot be built twice, and the first few property writes grow a
	 * B+tree whose root pointer both writers then rewrite. Both are real conflicts and neither is the subject. Warming
	 * them turns "two writers collide on a structure they share by accident" into "two writers collide only where the
	 * test puts them".
	 * </p>
	 */
	private void warmSharedStructures(GraphStore store) throws Exception {
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 64; i++) {
				store.createNode("Warm", Properties.of().set("mark", (long) i));
			}
			txn.commit();
		}
	}

	/**
	 * Two write transactions are open at once.
	 *
	 * <p>
	 * The barrier is the whole test: both threads have to be inside a transaction, having written something, for
	 * either of them to get past it. Against a serialising writer lock the second {@code begin()} would still be
	 * waiting and this would time out, which is why the timeout is preemptive and short.
	 * </p>
	 */
	@Test
	public void testTwoWriteTransactionsAreOpenAtTheSameTime() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			try (GraphStore store = stores.open()) {
				warmSharedStructures(store);
				long before = store.stats().nodeCount();
				CyclicBarrier bothInside = new CyclicBarrier(2);
				AtomicReference<Throwable> failed = new AtomicReference<>();
				List<Thread> writers = new ArrayList<>();

				for (int t = 0; t < 2; t++) {
					int index = t;
					writers.add(Thread.ofPlatform().name("writer-" + t).start(() -> {
						try (Transaction txn = store.begin()) {
							// Deliberately unlabelled and property-free: a label goes into an index both writers would
							// then be inserting into, and the subject here is whether they can be open at once at all.
							store.createNode(List.<String>of());
							// Neither of us has committed. If writers were serialised, one of us would not be here.
							bothInside.await(15, TimeUnit.SECONDS);
							txn.commit();
						} catch (Throwable e) {
							failed.compareAndSet(null, e);
						}
					}));
				}
				for (Thread writer : writers) {
					writer.join();
				}

				assertNull(failed.get(), "a writer failed: " + failed.get());
				assertEquals(before + 2, store.stats().nodeCount(), "both transactions must have committed");
				store.verify(VerifyLevel.FULL);
			}
		});
	}

	/**
	 * Two writers changing different records on one page. Both survive.
	 *
	 * <p>
	 * The two nodes are created in one transaction and are therefore adjacent, which is what puts them on one page —
	 * and one page is exactly the granularity the old overlay resolved by last-writer-wins. Each writer changes only
	 * its own node, so there is nothing here for conflict detection to find and nothing for it to excuse: the correct
	 * answer is that both changes are present, and the failure mode this pins down is one of them silently not being.
	 * </p>
	 *
	 * <p>
	 * <b>An edge rather than a property, and that took two attempts to get right.</b> Setting a property writes the
	 * node record — which is the point — but it also inserts into the property index, and both writers land in the same
	 * B+tree leaf and collide there instead. The collision is real and the retry would resolve it, but the test would
	 * then be passing on the retry rather than on the composition. Adding an edge writes the source node's degree
	 * counter and nothing shared: the relationship record and the adjacency block are freshly allocated, and a slot a
	 * transaction allocated itself is exempt from conflict detection because nothing else can be depending on it.
	 * </p>
	 */
	@Test
	public void testTwoWritersOnOnePageDoNotOverwriteEachOther() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			try (GraphStore store = stores.open()) {
				warmSharedStructures(store);
				long first;
				long second;
				long targetOfFirst;
				long targetOfSecond;
				try (Transaction txn = store.begin()) {
					first = store.createNode("Neighbour", padded(0L));
					second = store.createNode("Neighbour", padded(0L));
					// Distant targets, so the two writers do not meet on a target's in-degree counter either.
					for (int i = 0; i < 64; i++) {
						store.createNode("Spacer");
					}
					targetOfFirst = store.createNode("Target");
					for (int i = 0; i < 64; i++) {
						store.createNode("Spacer");
					}
					targetOfSecond = store.createNode("Target");
					txn.commit();
				}
				assertEquals(ElementId.segment(first), ElementId.segment(second));
				assertEquals(ElementId.slot(first) + 1, ElementId.slot(second),
					"the two nodes must be adjacent, or this is not testing what it says it is");
				assertEquals(ElementId.slot(first) / recordsPerNodePage(store),
					ElementId.slot(second) / recordsPerNodePage(store),
					"and on one page, which is the granularity the defect resolved at");

				CyclicBarrier bothRead = new CyclicBarrier(2);
				AtomicReference<Throwable> failed = new AtomicReference<>();
				List<Thread> writers = new ArrayList<>();
				long[] sources = { first, second };
				long[] targets = { targetOfFirst, targetOfSecond };

				for (int t = 0; t < 2; t++) {
					long source = sources[t];
					long target = targets[t];
					writers.add(Thread.ofPlatform().name("writer-" + t).start(() -> {
						try (Transaction txn = store.begin()) {
							// Both take their copy of the shared page before either applies, which is the interleaving
							// the defect needed.
							store.degreeOut(source);
							bothRead.await(15, TimeUnit.SECONDS);
							store.createEdge(source, "LINKS", target);
							txn.commit();
						} catch (Throwable e) {
							failed.compareAndSet(null, e);
						}
					}));
				}
				for (Thread writer : writers) {
					writer.join();
				}

				assertNull(failed.get(), "a writer failed: " + failed.get());
				assertEquals(1, store.degreeOut(first),
					"the first writer's edge is gone, so the second commit reverted its half of the page");
				assertEquals(1, store.degreeOut(second),
					"the second writer's edge is gone, so the first commit reverted its half of the page");
				assertEquals(1, store.neighbours(first, io.metaloom.graph.core.api.Direction.OUT).size());
				assertEquals(1, store.neighbours(second, io.metaloom.graph.core.api.Direction.OUT).size());
				store.verify(VerifyLevel.FULL);
			}
		});
	}

	/**
	 * Two writers changing one record. One of them is refused.
	 *
	 * <p>
	 * Both read the same value and both intend to replace it, so one of them is deciding from a state that no longer
	 * holds by the time it commits. First committer wins; the loser is told, which is the difference between this and
	 * a lost update.
	 * </p>
	 */
	@Test
	public void testTwoWritersOnOneRecordEndWithOneRefused() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			try (GraphStore store = stores.open()) {
				warmSharedStructures(store);
				long contested = store.createNode("Contested", Properties.of().set("mark", 0L));

				CyclicBarrier bothRead = new CyclicBarrier(2);
				AtomicInteger committed = new AtomicInteger();
				List<TransactionConflictException> refused =
					java.util.Collections.synchronizedList(new ArrayList<>());
				AtomicReference<Throwable> failed = new AtomicReference<>();
				List<Thread> writers = new ArrayList<>();

				for (int t = 0; t < 2; t++) {
					long value = t + 1L;
					writers.add(Thread.ofPlatform().name("writer-" + t).start(() -> {
						try (Transaction txn = store.begin()) {
							store.nodeProperty(contested, "mark");
							bothRead.await(15, TimeUnit.SECONDS);
							store.setNodeProperties(contested, Properties.of().set("mark", value));
							txn.commit();
							committed.incrementAndGet();
						} catch (TransactionConflictException e) {
							refused.add(e);
						} catch (Throwable e) {
							failed.compareAndSet(null, e);
						}
					}));
				}
				for (Thread writer : writers) {
					writer.join();
				}

				assertNull(failed.get(), "a writer failed with something other than a conflict: " + failed.get());
				assertEquals(1, committed.get(), "exactly one of the two must have committed");
				assertEquals(1, refused.size(), "and exactly one must have been told it lost");

				TransactionConflictException conflict = refused.get(0);
				assertTrue(conflict.conflictingLsn() > conflict.snapshotLsn(),
					"the conflict must name a commit that landed after the loser's snapshot: " + conflict.getMessage());
				assertTrue(conflict.getMessage().contains("Retry"),
					"a conflict has to tell the caller what to do about it: " + conflict.getMessage());

				long mark = (Long) store.nodeProperty(contested, "mark");
				assertTrue(mark == 1L || mark == 2L, "the surviving value must be one of the two, not a mixture");
				store.verify(VerifyLevel.FULL);
			}
		});
	}

	/**
	 * A refused transaction leaves the store exactly as it found it.
	 *
	 * <p>
	 * A conflict is discovered at the commit, after the transaction has allocated slots, written records and queued
	 * metadata changes — so "nothing happened" is a claim about several kinds of state at once, and each of them has
	 * been wrong at some point in this engine's history. The node must not exist, the allocator must not have leaked
	 * the slot it reserved, and the bitmap must still agree with the superblock, which is what {@code verify()}
	 * checks and what an abandoned half-fold used to break.
	 * </p>
	 */
	@Test
	public void testARefusedTransactionLeavesNothingBehind() throws Exception {
		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			try (GraphStore store = stores.open()) {
				warmSharedStructures(store);
				long contested = store.createNode("Contested", Properties.of().set("mark", 0L));
				long nodesBefore = store.stats().nodeCount();
				AtomicReference<Long> doomed = new AtomicReference<>();

				CountDownLatch loserHasRead = new CountDownLatch(1);
				CountDownLatch winnerHasCommitted = new CountDownLatch(1);
				AtomicReference<Throwable> failed = new AtomicReference<>();

				Thread loser = Thread.ofPlatform().name("loser").start(() -> {
					try (Transaction txn = store.begin()) {
						store.nodeProperty(contested, "mark");
						loserHasRead.countDown();
						assertTrue(winnerHasCommitted.await(15, TimeUnit.SECONDS));
						// A node of its own as well as the contested write, so the rollback has an allocation to undo
						// rather than only an overwrite.
						doomed.set(store.createNode("Doomed", Properties.of().set("mark", 99L)));
						store.setNodeProperties(contested, Properties.of().set("mark", 1L));
						assertThrows(TransactionConflictException.class, txn::commit);
					} catch (Throwable e) {
						failed.compareAndSet(null, e);
					}
				});

				assertTrue(loserHasRead.await(15, TimeUnit.SECONDS));
				store.setNodeProperties(contested, Properties.of().set("mark", 2L));
				winnerHasCommitted.countDown();
				loser.join();

				assertNull(failed.get(), "the losing thread failed for the wrong reason: " + failed.get());
				assertEquals(2L, store.nodeProperty(contested, "mark"), "the winner's value must stand");
				assertEquals(0, store.nodesByLabel("Doomed").size(),
					"a node created by the refused transaction survived it");
				assertEquals(nodesBefore, store.stats().nodeCount(), "the refused transaction left a node behind");

				// The slot came back, which is the part a byte total cannot show: the winner committed in between and
				// legitimately moved the high water marks, so what is asserted is that the next node lands exactly
				// where the doomed one did rather than one slot further up.
				long next = store.createNode("After", Properties.of().set("mark", 3L));
				assertEquals(ElementId.slot(doomed.get()), ElementId.slot(next),
					"the slot the refused transaction reserved was not given back to the allocator");

				// The accounting check inside verify() is the one an abandoned metadata fold used to break: it compares
				// the free-slot bitmap against the superblock's live count, and a conflict raised after the fold left
				// the second advanced over the first.
				store.verify(VerifyLevel.FULL);
			}
		});
	}

	/**
	 * A property payload of more than half a page, so the block holding it has that page to itself.
	 *
	 * <p>
	 * Two neighbouring property blocks are a write-write conflict between the transactions that own them, and not only
	 * when they overlap: the commit merges changed runs that are within sixty-four bytes of each other into one record,
	 * which is right for the log and means a transaction's run can span a neighbour's bytes it never touched. That is a
	 * false conflict — sound, since it only ever costs a retry — but it would hide what this test is looking for, which
	 * is whether the <em>node</em> records on one page compose.
	 * </p>
	 */
	private static Properties padded(long mark) {
		return Properties.of().set("mark", mark).set("pad", "x".repeat(5_000));
	}

	private int recordsPerNodePage(GraphStore store) {
		return ((io.metaloom.graph.core.store.GraphStoreImpl) store).segmentSets()
			.get(io.metaloom.graph.core.id.Kind.NODE).slotsPerPage();
	}
}
