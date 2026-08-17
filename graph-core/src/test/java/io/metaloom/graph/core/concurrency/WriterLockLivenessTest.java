package io.metaloom.graph.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Every way a write transaction can end badly, and the writer lock released in all of them.
 *
 * <p>
 * There is one writer lock per store and it is engine-wide, so leaking it does not fail the operation that leaked it —
 * it wedges <b>every future write in the process</b>, including whatever else embeds the store. The symptom is a build
 * or a server that stops making progress with nothing in the log, which is the most expensive kind of defect this
 * suite can miss and one of the cheapest to test for.
 * </p>
 *
 * <p>
 * The assertion is deliberately behavioural rather than introspective: another thread must be able to open a
 * transaction within a short timeout. A test that asked the lock about its own hold count could pass while the lock
 * was unusable.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 6
 */
@Tag("unit")
public class WriterLockLivenessTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testARuntimeExceptionInTheBodyReleasesTheLock() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(IllegalStateException.class, () -> {
				try (Transaction txn = store.begin()) {
					store.createNode("N", Properties.of());
					throw new IllegalStateException("deliberate");
				}
			});
			assertWriterLockIsFree(store, "a runtime exception in the transaction body");
		}
	}

	/**
	 * An {@link Error} rather than an exception. Worth its own case because {@code begin()} catches
	 * {@code RuntimeException} specifically, so anything relying on that catch would not cover this.
	 */
	@Test
	public void testAnErrorInTheBodyReleasesTheLock() throws Exception {
		try (GraphStore store = stores.open()) {
			assertThrows(StackOverflowError.class, () -> {
				try (Transaction txn = store.begin()) {
					store.createNode("N", Properties.of());
					throw new StackOverflowError("deliberate");
				}
			});
			assertWriterLockIsFree(store, "an Error in the transaction body");
		}
	}

	@Test
	public void testClosingWithoutCommittingReleasesTheLock() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("N", Properties.of());
				// No commit and no rollback: close() has to be the one that decides.
			}
			assertWriterLockIsFree(store, "close() without commit");
		}
	}

	@Test
	public void testAnExplicitRollbackReleasesTheLock() throws Exception {
		try (GraphStore store = stores.open()) {
			try (Transaction txn = store.begin()) {
				store.createNode("N", Properties.of());
				txn.rollback();
			}
			assertWriterLockIsFree(store, "an explicit rollback");
		}
	}

	/**
	 * A constraint violation is the one failure {@code inTransaction} refuses to retry, so it is the one that escapes
	 * through the retry loop rather than round it.
	 */
	@Test
	public void testANonRetryableFailureInsideInTransactionReleasesTheLock() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("email");
			store.createNode("User", Properties.of().set("email", "a@example.com"));

			assertThrows(ConstraintViolationException.class,
				() -> store.inTransaction(() -> store.createNode("User", Properties.of().set("email", "a@example.com"))));
			assertWriterLockIsFree(store, "a constraint violation inside inTransaction");
		}
	}

	/**
	 * The thread is interrupted while it holds the transaction. The interrupt must not turn into a lost unlock — and
	 * the flag must survive, because swallowing it is how a caller's own cancellation gets lost.
	 */
	@Test
	public void testAnInterruptedWriterReleasesTheLock() throws Exception {
		try (GraphStore store = stores.open()) {
			AtomicReference<Throwable> failure = new AtomicReference<>();
			CountDownLatch done = new CountDownLatch(1);

			Thread writer = Thread.ofPlatform().name("interrupted-writer").start(() -> {
				try {
					try (Transaction txn = store.begin()) {
						store.createNode("N", Properties.of());
						Thread.currentThread().interrupt();
						txn.commit();
					}
				} catch (Throwable e) {
					failure.set(e);
				} finally {
					done.countDown();
				}
			});

			assertTrue(done.await(30, TimeUnit.SECONDS), "the interrupted writer never finished");
			writer.join(30_000);
			assertWriterLockIsFree(store, "an interrupt during commit");
		}
	}

	/** Many threads contending, several of them failing, and the lock still usable at the end. */
	@Test
	public void testTheLockSurvivesAStormOfFailingWriters() throws Exception {
		try (GraphStore store = stores.open()) {
			int threads = 8;
			CountDownLatch go = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(threads);

			for (int t = 0; t < threads; t++) {
				final int index = t;
				Thread.ofPlatform().name("storm-" + t).start(() -> {
					try {
						go.await();
						for (int i = 0; i < 25; i++) {
							try (Transaction txn = store.begin()) {
								store.createNode("N", Properties.of().set("i", (long) i));
								if ((i + index) % 3 == 0) {
									throw new IllegalStateException("deliberate");
								}
								txn.commit();
							} catch (IllegalStateException | IOException expected) {
								// The point is the next iteration getting the lock, not this one succeeding.
							}
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}

			go.countDown();
			assertTrue(done.await(60, TimeUnit.SECONDS),
				"the storm did not finish, so a failing writer wedged the lock");
			assertWriterLockIsFree(store, "a storm of failing writers");
		}
	}

	// ---------------------------------------------------------------- helper

	/**
	 * Another thread must be able to take the writer lock. Run on a separate thread deliberately: the lock is
	 * reentrant, so a leak is invisible to the thread that leaked it.
	 */
	private void assertWriterLockIsFree(GraphStore store, String after) throws Exception {
		assertFalse(store.hasWriteTransaction(), "this thread still believes it has a transaction after " + after);

		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch acquired = new CountDownLatch(1);
		Thread other = Thread.ofPlatform().name("lock-prober").start(() -> {
			try (Transaction txn = store.begin()) {
				store.createNode("Prober", Properties.of());
				txn.commit();
				acquired.countDown();
			} catch (Throwable e) {
				failure.set(e);
			}
		});

		boolean got = acquired.await(20, TimeUnit.SECONDS);
		other.join(5_000);
		if (failure.get() != null) {
			throw new AssertionError("the probing writer failed after " + after, failure.get());
		}
		assertTrue(got, "the writer lock was never released after " + after
			+ ", so every future write in this process would block forever");
	}
}
