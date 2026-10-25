package io.metaloom.graph.core.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Named workers — platform and virtual — that loop until a deadline or the first failure, joined under a watchdog.
 *
 * <p>
 * The difference from {@link Hammer} is shape rather than purpose: a Hammer runs one weighted mix on identical threads,
 * and the stress tests here need <em>roles</em> — writers, auditors, a maintenance thread — each with its own loop and
 * its own oracle. What the two share is the rule that matters most for a lock bug: <b>a hang becomes a failure with a
 * thread dump</b>, not a stalled build. Lock waits in the engine are uninterruptible, so a JUnit timeout cannot unstick
 * a deadlocked worker; the watchdog instead dumps every thread in the JVM, with the monitors and synchronizers each one
 * holds, and abandons the workers.
 * </p>
 *
 * <p>
 * Every worker gets its own {@link Random} off one printed seed, so a failing run names the seed that reproduces its
 * choices — not its interleaving, which no seed can.
 * </p>
 */
public final class StressRun {

	/** The watchdog fired: some worker is stuck, very likely on an engine lock, and the store cannot be closed. */
	public static final class Hung extends AssertionError {

		private static final long serialVersionUID = 1L;

		Hung(String message) {
			super(message);
		}
	}

	/** What a test does with an open store. */
	@FunctionalInterface
	public interface StoreWork {
		void run(io.metaloom.graph.core.api.GraphStore store) throws Exception;
	}

	/**
	 * Runs {@code work} and closes the store afterwards — unless the run {@link Hung}, in which case closing would wait
	 * for the stuck worker's lock forever and turn a failure with a thread dump into a hung build.
	 */
	public static void withStore(io.metaloom.graph.core.api.GraphStore store, StoreWork work) throws Exception {
		boolean hung = false;
		try {
			work.run(store);
		} catch (Hung e) {
			hung = true;
			throw e;
		} finally {
			if (!hung) {
				store.close();
			}
		}
	}

	/** One worker's loop body; return false to stop that worker early. */
	@FunctionalInterface
	public interface Body {
		boolean step(Random rnd) throws Exception;
	}

	private record Role(String name, boolean virtual, Body body) {
	}

	/** Seconds a run lasts when {@code -Dgraph.stress.seconds} is not set. */
	private final String name;
	private final long seed;
	private final List<Role> roles = new ArrayList<>();
	private long millis = 1_500;
	private long watchdogSeconds = 60;
	private final AtomicBoolean stop = new AtomicBoolean();
	private final AtomicReference<Throwable> failure = new AtomicReference<>();

	private StressRun(String name, long seed) {
		this.name = name;
		this.seed = seed;
	}

	public static StressRun named(String name) {
		return new StressRun(name, Long.getLong("graph.stress.seed", System.nanoTime()));
	}

	public StressRun millis(long millis) {
		this.millis = Long.getLong("graph.stress.millis", millis);
		return this;
	}

	public StressRun watchdogSeconds(long seconds) {
		this.watchdogSeconds = seconds;
		return this;
	}

	public StressRun platform(String role, int count, Body body) {
		for (int i = 0; i < count; i++) {
			roles.add(new Role(role + "-" + i, false, body));
		}
		return this;
	}

	public StressRun virtual(String role, int count, Body body) {
		for (int i = 0; i < count; i++) {
			roles.add(new Role(role + "-v" + i, true, body));
		}
		return this;
	}

	public boolean stopped() {
		return stop.get();
	}

	public long seed() {
		return seed;
	}

	/** Runs every role until the deadline, the first failure, or the watchdog. */
	public void run() throws Exception {
		System.out.println("[stress] " + name + " seed=" + seed + " millis=" + millis + " workers=" + roles.size());
		CountDownLatch go = new CountDownLatch(1);
		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < roles.size(); i++) {
			Role role = roles.get(i);
			Random rnd = new Random(seed * 31 + i);
			Runnable loop = () -> {
				try {
					go.await();
					while (!stop.get()) {
						if (!role.body().step(rnd)) {
							break;
						}
					}
				} catch (Throwable e) {
					if (failure.compareAndSet(null, new AssertionError(role.name() + " failed (seed=" + seed + ")", e))) {
						stop.set(true);
					}
				}
			};
			threads.add(role.virtual() ? Thread.ofVirtual().name(role.name()).unstarted(loop)
				// Daemon, so a run abandoned by the watchdog does not keep the test JVM from exiting afterwards.
				: Thread.ofPlatform().name(role.name()).daemon(true).unstarted(loop));
		}
		threads.forEach(Thread::start);
		go.countDown();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		while (System.nanoTime() < deadline && !stop.get()) {
			Thread.sleep(10);
		}
		stop.set(true);

		long joinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(watchdogSeconds);
		for (Thread thread : threads) {
			long remaining = joinDeadline - System.nanoTime();
			thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
		}
		List<String> stuck = threads.stream().filter(Thread::isAlive).map(Thread::getName).toList();
		if (!stuck.isEmpty()) {
			StringBuilder dump = new StringBuilder("Stress run '" + name + "' (seed=" + seed + ") did not stop within "
				+ watchdogSeconds + " s; still running: " + stuck + "\n");
			for (Thread thread : threads) {
				if (thread.isAlive() && thread.isVirtual()) {
					dump.append("--- virtual ").append(thread.getName()).append(" ---\n");
					for (StackTraceElement frame : thread.getStackTrace()) {
						dump.append("\tat ").append(frame).append('\n');
					}
				}
			}
			// Every platform thread, not only the workers: the lock a worker waits on is often held by a thread the
			// engine started. java.lang.management is not readable from this module, so no owner information — the
			// frames name the lock, and the frames of the other threads name who has it.
			for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
				Thread thread = entry.getKey();
				dump.append('"').append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
				for (StackTraceElement frame : entry.getValue()) {
					dump.append("\tat ").append(frame).append('\n');
				}
			}
			throw new Hung(dump.toString());
		}
		if (failure.get() != null) {
			throw (AssertionError) failure.get();
		}
	}
}
