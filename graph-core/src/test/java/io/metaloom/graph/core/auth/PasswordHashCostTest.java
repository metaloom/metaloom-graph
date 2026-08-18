package io.metaloom.graph.core.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * That deriving a password actually costs something.
 *
 * <h2>Why this is a test at all</h2>
 *
 * <p>
 * Every functional assertion about passwords passes just as happily at one iteration as at two hundred thousand. So
 * the single most damaging change anyone could make to this code — dropping {@code DEFAULT_ITERATIONS} to something
 * small, whether to speed up a test suite or by a stray edit — is invisible to the entire rest of the suite. This is
 * the only thing that would notice, and a stolen identity file is the scenario it is about.
 * </p>
 *
 * <h2>Why the bounds are so wide</h2>
 *
 * <p>
 * Twenty milliseconds to a second, against a target of roughly a hundred. Not a benchmark: this runs on shared and
 * wildly varying hardware, and a tight bound would make it a flaky test rather than a guarantee. The lower bound is
 * what catches the defect — a mis-set cost is out by three orders of magnitude, not by a factor of two — and the upper
 * one catches a cost accidentally raised to something that would take the login endpoint down.
 * </p>
 *
 * <p>
 * Tagged {@code slow} because it deliberately burns a second of CPU and belongs in the profile that expects to.
 * </p>
 */
@Tag("slow")
public class PasswordHashCostTest {

	@Test
	public void testOneVerificationCostsBetweenTwentyMillisecondsAndASecond() {
		char[] password = "a reasonable password".toCharArray();
		PasswordHash hash = PasswordHash.of(password);

		// Once to let the JIT and the JCA provider warm up, so the measurement is of the derivation rather than of
		// class loading.
		hash.verify(password);

		long start = System.nanoTime();
		assertTrue(hash.verify(password));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertTrue(elapsed.toMillis() >= 20,
			"one verification took " + elapsed.toMillis() + "ms. PBKDF2 is meant to be slow: at this speed an "
				+ "identity file that leaked could be brute-forced. Check PasswordHash.DEFAULT_ITERATIONS, which "
				+ "should be " + PasswordHash.DEFAULT_ITERATIONS + ".");
		assertTrue(elapsed.toSeconds() < 1,
			"one verification took " + elapsed.toMillis() + "ms, which would make the login endpoint a denial of "
				+ "service against itself");
	}

	/** The dummy an unknown user is checked against has to cost the same, or login enumerates users. */
	@Test
	public void testTheUnknownUserHashCostsTheSameAsARealOne() {
		char[] password = "another password".toCharArray();
		PasswordHash real = PasswordHash.of(password);
		real.verify(password);
		PasswordHash.UNKNOWN_USER.verify(password);

		long realStart = System.nanoTime();
		real.verify(password);
		long realNanos = System.nanoTime() - realStart;

		long dummyStart = System.nanoTime();
		PasswordHash.UNKNOWN_USER.verify(password);
		long dummyNanos = System.nanoTime() - dummyStart;

		// An order of magnitude, not a percentage: the failure this catches is a dummy derived at one iteration, which
		// would be thousands of times faster, and a tighter bound would just be scheduler noise.
		assertTrue(dummyNanos * 10 > realNanos,
			"verifying against the unknown-user hash took " + (dummyNanos / 1_000_000) + "ms against a real "
				+ (realNanos / 1_000_000) + "ms. A cheaper dummy makes the login endpoint a user enumerator.");
	}
}
