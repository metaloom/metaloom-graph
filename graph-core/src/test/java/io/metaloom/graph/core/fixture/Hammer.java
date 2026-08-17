package io.metaloom.graph.core.fixture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * A weighted, seeded, multi-threaded load generator.
 *
 * <p>
 * Every concurrency test in this suite needs the same five things: N threads, a reproducible random operation mix, a
 * bound (op count for the default build, wall-clock for {@code -Pslow}), the first failure captured with its stack
 * rather than the last one printed, and a watchdog — because this project has twice shipped a defect whose only symptom
 * was a build that stopped making progress, and a hang reports as "still running" rather than as a failure.
 * </p>
 *
 * <p>
 * It also counts what it actually ran, per operation. A load test that silently stopped exercising writes still passes
 * every assertion at the end, which is the "green test that cannot fail" this suite's conventions warn about
 * ({@code spec/TESTING.md} section 9). {@link Result#assertRan(String...)} is how a test refuses to be that.
 * </p>
 *
 * <p>
 * Threads are platform threads: the operations block on mapped-file page faults, which is the case virtual threads
 * handle worst, and the engine's own server pool avoids them for the same reason.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 1
 */
public final class Hammer {

	/**
	 * Wall-clock duration for the scaled forms, via {@code -Dgraph.hammer.seconds=N}. Zero — the default, and what the
	 * default build always uses — selects the bounded op-count form instead, so {@code mvn test} stays deterministic.
	 */
	public static final int SECONDS = Integer.getInteger("graph.hammer.seconds", 0);

	/** Whether this JVM was asked for the duration-scaled form. */
	public static boolean scaled() {
		return SECONDS > 0;
	}

	@FunctionalInterface
	public interface Op {
		void run(Random rnd) throws Exception;
	}

	private record Weighted(String name, int weight, Op op) {
	}

	private final List<Weighted> ops = new ArrayList<>();
	private final String name;
	private int threads = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));
	private int opsPerThread = 500;
	private long seed = 20260816L;
	private long watchdogSeconds = 120;

	private Hammer(String name) {
		this.name = name;
	}

	public static Hammer named(String name) {
		return new Hammer(name);
	}

	public Hammer threads(int threads) {
		this.threads = threads;
		return this;
	}

	/** Operations per thread for the bounded form. Ignored when {@code -Dgraph.hammer.seconds} is set. */
	public Hammer opsPerThread(int opsPerThread) {
		this.opsPerThread = opsPerThread;
		return this;
	}

	public Hammer seed(long seed) {
		this.seed = seed;
		return this;
	}

	public Hammer watchdogSeconds(long watchdogSeconds) {
		this.watchdogSeconds = watchdogSeconds;
		return this;
	}

	public Hammer op(String opName, int weight, Op op) {
		if (weight > 0) {
			ops.add(new Weighted(opName, weight, op));
		}
		return this;
	}

	/**
	 * Runs the mix and rethrows the first failure any thread saw.
	 * <p>
	 * The seed is printed before the run rather than on failure: a run that hangs never reaches its failure path, and
	 * the seed is exactly what is needed to reproduce one that did.
	 */
	public Result run() throws Exception {
		if (ops.isEmpty()) {
			throw new IllegalStateException("Hammer '" + name + "' has no operations");
		}
		int totalWeight = ops.stream().mapToInt(Weighted::weight).sum();
		Map<String, LongAdder> counts = new LinkedHashMap<>();
		for (Weighted w : ops) {
			counts.put(w.name(), new LongAdder());
		}

		long deadlineNanos = scaled() ? System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS) : 0;
		System.out.println("[hammer] " + name + " seed=" + seed + " threads=" + threads
			+ (scaled() ? " seconds=" + SECONDS : " opsPerThread=" + opsPerThread));

		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicBoolean stop = new AtomicBoolean();
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Thread> workers = new ArrayList<>();

		for (int t = 0; t < threads; t++) {
			final int index = t;
			Thread worker = Thread.ofPlatform().name(name + "-" + t).unstarted(() -> {
				// Per-thread stream off one seed: reproducible as a whole, and independent per thread.
				Random rnd = new Random(seed * 31 + index);
				ready.countDown();
				try {
					go.await();
					for (int i = 0; !stop.get(); i++) {
						if (scaled()) {
							if (System.nanoTime() >= deadlineNanos) {
								break;
							}
						} else if (i >= opsPerThread) {
							break;
						}
						Weighted chosen = pick(rnd.nextInt(totalWeight));
						chosen.op().run(rnd);
						counts.get(chosen.name()).increment();
					}
				} catch (Throwable e) {
					// First failure wins; the rest are almost always consequences of it.
					if (failure.compareAndSet(null, e)) {
						stop.set(true);
					}
				}
			});
			workers.add(worker);
		}

		workers.forEach(Thread::start);
		ready.await();
		go.countDown();

		long watchdog = scaled() ? SECONDS + 120 : watchdogSeconds;
		long joinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(watchdog);
		for (Thread worker : workers) {
			long remaining = joinDeadline - System.nanoTime();
			worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
		}

		List<String> stuck = workers.stream().filter(Thread::isAlive).map(Thread::getName).toList();
		if (!stuck.isEmpty()) {
			stop.set(true);
			// A stack for each stuck thread, because "which lock" is the whole diagnosis and the threads are about to
			// be abandoned.
			StringBuilder sb = new StringBuilder("Hammer '" + name + "' did not finish within " + watchdog
				+ "s; still running: " + stuck);
			for (Thread worker : workers) {
				if (worker.isAlive()) {
					sb.append("\n--- ").append(worker.getName()).append(" ---");
					for (StackTraceElement frame : worker.getStackTrace()) {
						sb.append("\n\tat ").append(frame);
					}
				}
			}
			throw new AssertionError(sb.toString());
		}

		Throwable thrown = failure.get();
		if (thrown != null) {
			throw new AssertionError("Hammer '" + name + "' failed (seed=" + seed + ")", thrown);
		}

		Map<String, Long> totals = new LinkedHashMap<>();
		counts.forEach((k, v) -> totals.put(k, v.sum()));
		System.out.println("[hammer] " + name + " done " + totals);
		return new Result(name, seed, totals);
	}

	private Weighted pick(int roll) {
		int cursor = 0;
		for (Weighted w : ops) {
			cursor += w.weight();
			if (roll < cursor) {
				return w;
			}
		}
		return ops.get(ops.size() - 1);
	}

	/** What the run actually did, so a test can assert the workload rather than assume it. */
	public record Result(String name, long seed, Map<String, Long> counts) {

		public long count(String opName) {
			return counts.getOrDefault(opName, 0L);
		}

		public long total() {
			return counts.values().stream().mapToLong(Long::longValue).sum();
		}

		/** Fails unless every named operation ran at least once. */
		public void assertRan(String... opNames) {
			for (String opName : opNames) {
				if (count(opName) == 0) {
					throw new AssertionError("Hammer '" + name + "' never ran '" + opName
						+ "', so nothing it was supposed to prove was exercised (seed=" + seed + ", counts=" + counts + ")");
				}
			}
		}
	}
}
