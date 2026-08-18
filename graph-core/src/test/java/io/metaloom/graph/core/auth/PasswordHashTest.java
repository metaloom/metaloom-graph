package io.metaloom.graph.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.AuthFormatException;

/**
 * Password derivation, verification, and the two properties that are easy to lose.
 *
 * <p>
 * The cost is deliberately low here — one iteration — because these tests are about behaviour, not resistance. The
 * cost itself is asserted by {@code PasswordHashCostTest}, which is tagged {@code slow} for the obvious reason.
 * </p>
 */
@Tag("unit")
public class PasswordHashTest {

	private static PasswordHash cheap(String password) {
		return PasswordHash.of(password.toCharArray(), 1);
	}

	@Test
	public void testTheRightPasswordVerifiesAndAWrongOneDoesNot() {
		PasswordHash hash = cheap("correct horse");
		assertTrue(hash.verify("correct horse".toCharArray()));
		assertFalse(hash.verify("correct hors".toCharArray()));
		assertFalse(hash.verify("Correct Horse".toCharArray()));
		assertFalse(hash.verify(new char[0]));
	}

	/** Two hashes of one password must differ, or the file leaks which users share a password. */
	@Test
	public void testTheSamePasswordHashesDifferentlyEachTime() {
		PasswordHash first = cheap("same");
		PasswordHash second = cheap("same");
		assertNotEquals(first.toCanonicalString(), second.toCanonicalString());
		assertTrue(first.verify("same".toCharArray()));
		assertTrue(second.verify("same".toCharArray()));
	}

	@Test
	public void testAnEmptyPasswordIsHandledRatherThanThrowing() {
		// PBEKeySpec refuses an empty char array. That has to be this class's rule rather than an exception escaping
		// from inside the JCA, because "" is exactly what an empty form field sends.
		PasswordHash hash = PasswordHash.of(new char[0], 1);
		assertTrue(hash.verify(new char[0]));
		assertFalse(hash.verify("something".toCharArray()));
	}

	// ---------------------------------------------------------------- the text form

	@Test
	public void testTheCanonicalFormRoundTrips() {
		PasswordHash hash = cheap("secret");
		String text = hash.toCanonicalString();
		assertTrue(text.startsWith(PasswordHash.PBKDF2_SHA512 + "$1$"), text);

		PasswordHash restored = PasswordHash.parse(text);
		assertEquals(text, restored.toCanonicalString());
		assertTrue(restored.verify("secret".toCharArray()), "a hash read back from text still verifies");
	}

	/** base64url without padding, so a hash never needs quoting in a shell, a CSV or a log line. */
	@Test
	public void testTheCanonicalFormNeedsNoQuoting() {
		for (int i = 0; i < 20; i++) {
			String text = cheap("p" + i).toCanonicalString();
			assertFalse(text.contains("="), text);
			assertFalse(text.contains("+"), text);
			assertFalse(text.contains("/"), text);
			assertFalse(text.contains(" "), text);
		}
	}

	@Test
	public void testAMalformedCanonicalFormIsRefused() {
		assertThrows(AuthFormatException.class, () -> PasswordHash.parse("nonsense"));
		assertThrows(AuthFormatException.class, () -> PasswordHash.parse("pbkdf2-sha512$1$abc"));
		assertThrows(AuthFormatException.class, () -> PasswordHash.parse("pbkdf2-sha512$many$abc$def"));
		assertThrows(AuthFormatException.class, () -> PasswordHash.parse("pbkdf2-sha512$1$!!!$def"));
		assertThrows(AuthFormatException.class, () -> PasswordHash.parse(null));
	}

	/** A function this build does not implement is refused by name, not approximated with the one it does. */
	@Test
	public void testAnUnknownPasswordFunctionIsRefusedByName() {
		AuthFormatException refused = assertThrows(AuthFormatException.class,
			() -> PasswordHash.parse("argon2id$3$c2FsdA$aGFzaA"));
		assertTrue(refused.getMessage().contains("argon2id"), refused.getMessage());
		assertTrue(refused.getMessage().contains("newer build"), refused.getMessage());
	}

	// ---------------------------------------------------------------- parameter validation

	@Test
	public void testHostileParametersAreRefused() {
		byte[] salt = new byte[16];
		byte[] hash = new byte[32];
		assertThrows(AuthFormatException.class,
			() -> PasswordHash.restore(PasswordHash.PBKDF2_SHA512, 0, salt, hash));
		assertThrows(AuthFormatException.class,
			() -> PasswordHash.restore(PasswordHash.PBKDF2_SHA512, -1, salt, hash));
		assertThrows(AuthFormatException.class,
			() -> PasswordHash.restore(PasswordHash.PBKDF2_SHA512, PasswordHash.MAX_ITERATIONS + 1, salt, hash));
		assertThrows(AuthFormatException.class,
			() -> PasswordHash.restore(PasswordHash.PBKDF2_SHA512, 1, new byte[0], hash));
		assertThrows(AuthFormatException.class,
			() -> PasswordHash.restore(PasswordHash.PBKDF2_SHA512, 1, new byte[PasswordHash.MAX_SALT_BYTES + 1], hash));
		assertThrows(AuthFormatException.class,
			() -> PasswordHash.restore(PasswordHash.PBKDF2_SHA512, 1, salt, new byte[0]));
	}

	/** The stored arrays are copied on the way in and on the way out, so nothing can mutate a live credential. */
	@Test
	public void testTheStoredBytesCannotBeMutatedThroughTheAccessors() {
		PasswordHash hash = cheap("secret");
		byte[] stolen = hash.hash();
		java.util.Arrays.fill(stolen, (byte) 0);
		assertTrue(hash.verify("secret".toCharArray()), "clearing a copy of the hash must not change the hash");

		byte[] salt = hash.salt();
		java.util.Arrays.fill(salt, (byte) 0);
		assertTrue(hash.verify("secret".toCharArray()), "clearing a copy of the salt must not change the salt");
	}

	// ---------------------------------------------------------------- rehashing

	@Test
	public void testAHashDerivedTooCheaplyAsksToBeRehashed() {
		assertTrue(cheap("secret").needsRehash(), "one iteration is far below the current cost");
		assertFalse(PasswordHash.of("secret".toCharArray(), PasswordHash.DEFAULT_ITERATIONS).needsRehash());
	}

	/**
	 * A hash written at a higher cost than this build's default is left alone.
	 *
	 * <p>
	 * Otherwise a mixed fleet would fight: the older build would re-derive downwards on every login, the newer one
	 * upwards, and the file would be rewritten on every single authentication.
	 * </p>
	 */
	@Test
	public void testAHashDerivedMoreExpensivelyIsNotDowngraded() {
		assertFalse(PasswordHash.of("secret".toCharArray(), PasswordHash.DEFAULT_ITERATIONS + 1000).needsRehash());
	}

	// ---------------------------------------------------------------- the unknown-user hash

	/**
	 * The dummy exists so an unknown user costs the same as a known one.
	 *
	 * <p>
	 * Asserted structurally — that it is a real hash at the real cost which nothing verifies against — rather than by
	 * timing, because a timing assertion on a shared build machine is a flaky test rather than a guarantee.
	 * </p>
	 */
	@Test
	public void testTheUnknownUserHashIsRealAndMatchesNothing() {
		assertEquals(PasswordHash.DEFAULT_ITERATIONS, PasswordHash.UNKNOWN_USER.iterations(),
			"a cheap dummy would make an unknown user answer faster than a real one");
		assertEquals(PasswordHash.PBKDF2_SHA512, PasswordHash.UNKNOWN_USER.kdfId());
		assertFalse(PasswordHash.UNKNOWN_USER.verify("".toCharArray()));
		assertFalse(PasswordHash.UNKNOWN_USER.verify("password".toCharArray()));
		assertFalse(PasswordHash.UNKNOWN_USER.verify("admin".toCharArray()));
	}

	@Test
	public void testClearScrubsAPassword() {
		char[] password = PasswordHash.chars("secret");
		assertEquals('s', password[0]);
		PasswordHash.clear(password);
		assertEquals('\0', password[0]);
		PasswordHash.clear(null);
	}
}
