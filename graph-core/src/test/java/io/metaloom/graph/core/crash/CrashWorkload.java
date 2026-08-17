package io.metaloom.graph.core.crash;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.VerifyLevel;

/**
 * A standalone workload for the {@code kill -9} loop, and the checker that runs after it.
 *
 * <pre>
 * java -cp ... CrashWorkload write &lt;dir&gt; &lt;seconds&gt;
 * java -cp ... CrashWorkload check &lt;dir&gt;
 * </pre>
 *
 * The whole point of this class, and the reason it cannot be an in-process test, is the acknowledgement file. A crash
 * test that only checks "the store opens and verifies" cannot distinguish <em>losing a commit</em> from <em>never
 * having made one</em>, and losing a commit is the failure that matters. So the writer records every id the store
 * reported as committed into a separate file that it fsyncs itself, and the checker asserts that every acknowledged id
 * is present with the value it was written with.
 * <p>
 * The ack file is written <b>after</b> the commit returns and fsynced before the next one, so the invariant is
 * one-directional and exact: everything in the ack file must be in the store. The reverse does not hold, because the
 * process can die between the commit and the ack — that is the ambiguous window the specification explicitly allows.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 6
 */
public final class CrashWorkload {

	private static final String LABEL = "Acked";

	private CrashWorkload() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: CrashWorkload write <dir> <seconds> | check <dir>");
			System.exit(2);
		}
		Path dir = Path.of(args[1]);
		switch (args[0]) {
			case "write" -> write(dir, Long.parseLong(args.length > 2 ? args[2] : "10"));
			case "check" -> System.exit(check(dir) ? 0 : 1);
			default -> {
				System.err.println("unknown mode " + args[0]);
				System.exit(2);
			}
		}
	}

	/**
	 * Number of writer threads. More than one so that the {@code kill -9} loop exercises the writer lock, the commit
	 * queue and the acknowledgement path under contention rather than in the quiet single-threaded case — a crash
	 * landing while several threads are queued behind the writer lock is a different state from one landing on an idle
	 * store, and it is the state a real deployment is usually in. Override with {@code -Dcrash.workload.threads=N}.
	 */
	private static final int WRITERS = Integer.getInteger("crash.workload.threads", 4);

	/** Writes until killed or until the time is up, acknowledging each commit to an independently synced file. */
	public static void write(Path dir, long seconds) throws IOException {
		Files.createDirectories(dir);
		long deadline = System.nanoTime() + seconds * 1_000_000_000L;
		try (GraphStore store = GraphStore.open(dir, GraphStoreOptions.defaults());
			FileOutputStream ackFile = new FileOutputStream(ackPath(dir).toFile(), true)) {

			OutputStream acks = ackFile;
			java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong();
			// One lock for the ack file. It has to be a different lock from the store's, and the ordering has to be
			// commit-then-acknowledge: acknowledging first would let a crash between the two produce an ack for a
			// commit that never happened, and the checker would report a lost commit that was never made.
			Object ackLock = new Object();
			System.out.println("workload: writing to " + dir + " for up to " + seconds + "s on "
				+ WRITERS + " thread(s)");

			List<Thread> writers = new ArrayList<>();
			for (int w = 0; w < WRITERS; w++) {
				writers.add(Thread.ofPlatform().name("crash-writer-" + w).start(() -> {
					try {
						while (System.nanoTime() < deadline) {
							long value = counter.getAndIncrement();
							// The commit happens here; the store has reported it durable when createNode returns.
							long id = store.createNode(LABEL, Properties.of().set("value", value));
							synchronized (ackLock) {
								acks.write((id + " " + value + "\n").getBytes(StandardCharsets.UTF_8));
								acks.flush();
								ackFile.getFD().sync();
							}
						}
					} catch (IOException e) {
						// A crash arriving mid-write is the point of this program, not a failure of it.
						System.out.println("workload: writer stopped: " + e);
					}
				}));
			}
			for (Thread writer : writers) {
				try {
					writer.join();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			System.out.println("workload: finished cleanly after " + counter.get() + " commits");
		}
	}

	/**
	 * Reopens the store and asserts the post-conditions from TESTING.md section 6: it opens, its invariants hold, and
	 * every acknowledged commit is present with the right value.
	 *
	 * @return true when the store is sound
	 */
	public static boolean check(Path dir) throws IOException {
		List<String> acked = Files.exists(ackPath(dir)) ? Files.readAllLines(ackPath(dir)) : List.of();
		List<String> failures = new ArrayList<>();

		try (GraphStore store = GraphStore.open(dir, GraphStoreOptions.defaults())) {
			store.verify(VerifyLevel.FULL);
			for (String line : acked) {
				String[] parts = line.trim().split(" ");
				if (parts.length != 2) {
					// A torn final line: the process died mid-write to the ack file, which is allowed.
					continue;
				}
				long id = Long.parseLong(parts[0]);
				long expected = Long.parseLong(parts[1]);
				if (!store.nodeExists(id)) {
					failures.add("lost an acknowledged commit: node " + id + " (value " + expected + ")");
					continue;
				}
				Object actual = store.nodeProperties(id).get("value");
				if (actual == null || ((Number) actual).longValue() != expected) {
					failures.add("acknowledged commit " + id + " has value " + actual + ", expected " + expected);
				}
			}
			long unacked = store.stats().nodeCount() - acked.size();
			System.out.println("check: " + acked.size() + " acknowledged, " + store.stats().nodeCount()
				+ " present (" + unacked + " committed but never acknowledged, which is the allowed window)");
		} catch (RuntimeException e) {
			failures.add("the store did not open or did not verify: " + e);
		}

		failures.forEach(System.err::println);
		if (failures.isEmpty()) {
			System.out.println("check: PASS");
			return true;
		}
		System.err.println("check: FAIL (" + failures.size() + " problems)");
		return false;
	}

	private static Path ackPath(Path dir) {
		return dir.resolve("acknowledged.log");
	}
}
