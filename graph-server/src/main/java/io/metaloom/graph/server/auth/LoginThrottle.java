package io.metaloom.graph.server.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many failed logins a name or an address may have before it is made to wait.
 *
 * <h2>It rejects before the derivation, not after</h2>
 *
 * <p>
 * PBKDF2 at 210,000 iterations is roughly a hundred milliseconds of CPU that an <b>unauthenticated</b> caller can ask
 * for at will, on a port that binds every interface. A throttle placed after the derivation would count attempts
 * correctly and still let each one cost a tenth of a second of the server — it would be the amplifier rather than the
 * defence. So this is consulted first, and a refused attempt costs a map lookup.
 * </p>
 *
 * <h2>Both a name and an address</h2>
 *
 * <p>
 * Per name alone, anybody can lock a known user out by failing five logins as them. Per address alone, a distributed
 * attempt against one name is unbounded. Counting both means an attack on one account is stopped by the name counter,
 * and a spray across many accounts from one place is stopped by the address counter — and a legitimate user is only
 * ever inconvenienced by their own mistakes and their own network.
 * </p>
 *
 * <h2>No flag</h2>
 *
 * <p>
 * Fixed policy, deliberately. A tunable lockout is a tunable that gets set to zero the first time somebody is locked
 * out during a demonstration, and the setting outlives the demonstration.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 4.1
 */
public final class LoginThrottle {

	/** Failures before the wait starts. Generous enough that a mistyped password twice is not an incident. */
	public static final int FAILURES_BEFORE_DELAY = 5;

	/** The wait, once it starts. Long enough to make an online guess pointless, short enough to be survivable. */
	public static final long LOCKOUT_MILLIS = 60_000;

	/** Failures older than this are forgotten, so yesterday's typo does not count against today. */
	public static final long WINDOW_MILLIS = 15 * 60 * 1000L;

	/** Bounded, because the address key comes from the network and is otherwise an unbounded map. */
	private static final int MAX_TRACKED = 10_000;

	private static final class Failures {

		private int count;

		private long firstFailedAtMillis;

		private long lastFailedAtMillis;
	}

	private final Map<String, Failures> tracked = new ConcurrentHashMap<>();

	/**
	 * How long the caller must wait before this attempt may be made at all.
	 *
	 * @return 0 when the attempt may proceed, otherwise the milliseconds remaining
	 */
	public synchronized long retryAfterMillis(String user, String address) {
		long now = System.currentTimeMillis();
		return Math.max(waitFor(key("u", user), now), waitFor(key("a", address), now));
	}

	private long waitFor(String key, long now) {
		Failures failures = tracked.get(key);
		if (failures == null) {
			return 0;
		}
		if (now - failures.firstFailedAtMillis > WINDOW_MILLIS) {
			tracked.remove(key);
			return 0;
		}
		if (failures.count < FAILURES_BEFORE_DELAY) {
			return 0;
		}
		long until = failures.lastFailedAtMillis + LOCKOUT_MILLIS;
		return Math.max(0, until - now);
	}

	/** Records a failure against both keys. */
	public synchronized void recordFailure(String user, String address) {
		long now = System.currentTimeMillis();
		if (tracked.size() > MAX_TRACKED) {
			// Under an attack from many addresses this is the cheapest correct thing to do: forget everything and
			// start counting again. It loses history, and the alternative is unbounded memory.
			tracked.clear();
		}
		record(key("u", user), now);
		record(key("a", address), now);
	}

	private void record(String key, long now) {
		Failures failures = tracked.computeIfAbsent(key, ignored -> new Failures());
		if (now - failures.firstFailedAtMillis > WINDOW_MILLIS) {
			failures.count = 0;
			failures.firstFailedAtMillis = now;
		}
		if (failures.count == 0) {
			failures.firstFailedAtMillis = now;
		}
		failures.count++;
		failures.lastFailedAtMillis = now;
	}

	/**
	 * Forgets a name's failures after a successful login.
	 *
	 * <p>
	 * The <b>name</b> only. The address keeps its count, because somebody who has just guessed one password correctly
	 * out of many attempts is exactly who should still be throttled.
	 * </p>
	 */
	public synchronized void recordSuccess(String user) {
		tracked.remove(key("u", user));
	}

	private static String key(String kind, String value) {
		return kind + ":" + (value == null ? "" : value);
	}
}
