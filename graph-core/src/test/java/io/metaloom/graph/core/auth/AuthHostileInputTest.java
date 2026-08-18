package io.metaloom.graph.core.auth;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.AuthFormatException;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.api.Role;
import io.metaloom.graph.core.api.User;

/**
 * The identity file is untrusted input, and this is the oracle that says so.
 *
 * <p>
 * The rule, the same one {@code HostileInputTest} holds the storage parsers to, {@code WireDecoderHostileInputTest}
 * holds the wire decoder to and {@code DumpHostileInputTest} holds the dump reader to: decoding either produces an
 * image or throws {@link AuthFormatException}. Never an {@code OutOfMemoryError} from a believed length, never a
 * {@code NegativeArraySizeException}, never a {@code StackOverflowError}, and always in bounded time.
 * </p>
 *
 * <p>
 * There is one assertion here the other three cannot make. Their formats carry data; this one carries <b>authority</b>,
 * so it is not enough that a corrupt file fails to crash — it must not decode into a <em>different, valid</em> set of
 * grants. That is what {@link #testEverySingleBitMutationIsRefused()} is for, and it is the reason the format has a
 * checksum at all.
 * </p>
 */
@Tag("unit")
public class AuthHostileInputTest {

	/** Small enough to mutate exhaustively, wide enough to exercise every field the format has. */
	private static byte[] valid() {
		Role analyst = new Role("analyst", List.of(
			new Grant(Privilege.MATCH, Grant.ALL_DATABASES),
			new Grant(Privilege.MERGE, "sales")));
		Role admin = new Role(Role.ADMIN, List.of(Grant.on(Privilege.MANAGE_ROLES)));
		StoredUser alice = new StoredUser(
			new User("alice", true, 1000L, 2000L, List.of("analyst"), List.of(new Grant(Privilege.BACKUP, "sales"))),
			PasswordHash.of("secret".toCharArray(), 1));
		StoredUser root = new StoredUser(
			new User("admin", false, 3000L, 4000L, List.of(Role.ADMIN), List.of()),
			PasswordHash.of("other".toCharArray(), 1));
		return AuthCodec.encode(new AuthFile(7, List.of(analyst, admin), List.of(alice, root)));
	}

	/** Decodes, and reports whatever escaped rather than letting it fail the test directly. */
	private static Throwable attempt(byte[] bytes) {
		try {
			AuthCodec.decode(bytes);
			return null;
		} catch (Throwable t) {
			return t;
		}
	}

	private static void mustBeWellBehaved(Throwable thrown, String what) {
		if (thrown == null) {
			return;
		}
		assertTrue(!(thrown instanceof OutOfMemoryError), what + " ran out of memory");
		assertTrue(!(thrown instanceof StackOverflowError), what + " overflowed the stack");
		assertTrue(!(thrown instanceof NegativeArraySizeException), what + " allocated a negative array");
		assertTrue(thrown instanceof AuthFormatException,
			what + " threw " + thrown.getClass().getName() + " rather than AuthFormatException: " + thrown);
	}

	@Test
	public void testTheFixtureItselfDecodes() {
		AuthFile file = AuthCodec.decode(valid());
		assertNotNull(file);
		assertTrue(file.generation() == 7);
		assertTrue(file.users().size() == 2);
		assertTrue(file.roles().size() == 2);
	}

	@Test
	public void testAWrongMagicIsRefused() {
		byte[] bytes = valid();
		bytes[0] = 'X';
		AuthFormatException refused = assertThrows(AuthFormatException.class, () -> AuthCodec.decode(bytes));
		assertTrue(refused.getMessage().contains("not an identity file"), refused.getMessage());
	}

	/** Refused by number, so the message names both versions rather than failing somewhere inside the body. */
	@Test
	public void testAnUnknownVersionIsRefusedByNumber() {
		byte[] bytes = valid();
		bytes[AuthCodec.MAGIC.length] = (byte) 99;
		bytes[AuthCodec.MAGIC.length + 1] = 0;
		// The checksum covers the version, so fix it up: this test is about the version check, not the CRC.
		byte[] repaired = recheck(bytes);
		AuthFormatException refused = assertThrows(AuthFormatException.class, () -> AuthCodec.decode(repaired));
		assertTrue(refused.getMessage().contains("99"), refused.getMessage());
		assertTrue(refused.getMessage().contains("version " + AuthCodec.VERSION), refused.getMessage());
	}

	/** Every prefix. The commonest real corruption there is, and none of them may look like success. */
	@Test
	public void testEveryTruncationIsRefused() {
		byte[] full = valid();
		for (int length = 0; length < full.length; length++) {
			byte[] bytes = new byte[length];
			System.arraycopy(full, 0, bytes, 0, length);
			Throwable thrown = attempt(bytes);
			mustBeWellBehaved(thrown, "truncating to " + length);
			assertTrue(thrown != null, "an identity file truncated to " + length + " bytes decoded as if it were whole");
		}
	}

	/**
	 * Every single-bit mutation is refused.
	 *
	 * <p>
	 * The assertion this format needs that the others do not. A flipped bit in a dump produces a wrong graph, which is
	 * bad; a flipped bit here could produce a <em>valid</em> file granting something nobody granted — flipping a
	 * database name, an enabled flag, or a byte of a privilege name into another one that also parses. Nothing but the
	 * checksum stands between that and a silent change of authority, so nothing here is allowed through: not "the
	 * overwhelming majority", every single one.
	 * </p>
	 */
	@Test
	public void testEverySingleBitMutationIsRefused() {
		byte[] full = valid();
		for (int index = 0; index < full.length; index++) {
			for (int bit = 0; bit < 8; bit++) {
				byte[] bytes = full.clone();
				bytes[index] ^= (byte) (1 << bit);
				Throwable thrown = attempt(bytes);
				mustBeWellBehaved(thrown, "flipping bit " + bit + " of byte " + index);
				assertTrue(thrown != null,
					"flipping bit " + bit + " of byte " + index + " produced a file that decoded anyway");
			}
		}
	}

	/** Random garbage at every length: the case that finds what structured mutation does not. */
	@Test
	public void testRandomGarbageNeverCrashes() {
		Random random = new Random(20260817);
		for (int i = 0; i < 500; i++) {
			byte[] bytes = new byte[random.nextInt(2048)];
			random.nextBytes(bytes);
			// Half keep the magic, so the parser gets past the first check and into the length fields, which is where
			// the interesting failures live.
			if (i % 2 == 0 && bytes.length >= AuthCodec.MAGIC.length) {
				System.arraycopy(AuthCodec.MAGIC, 0, bytes, 0, AuthCodec.MAGIC.length);
			}
			mustBeWellBehaved(attempt(bytes), "random file " + i);
		}
	}

	/**
	 * A count that claims more than the file could possibly hold is refused rather than allocated.
	 *
	 * <p>
	 * This is the defect class that has cost this project the most: seven in the storage parsers, three in the wire
	 * decoder, every one a length field that was believed.
	 * </p>
	 */
	@Test
	public void testAHugeCountIsRefusedRatherThanAllocated() {
		// A hand-built file: valid header, then a role count of 2^31-1 in five continuation-encoded bytes.
		byte[] header = new byte[AuthCodec.MAGIC.length + 2 + 2 + 4 + 8];
		System.arraycopy(AuthCodec.MAGIC, 0, header, 0, AuthCodec.MAGIC.length);
		header[AuthCodec.MAGIC.length] = AuthCodec.VERSION;
		byte[] huge = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07 };

		byte[] body = new byte[header.length + huge.length];
		System.arraycopy(header, 0, body, 0, header.length);
		System.arraycopy(huge, 0, body, header.length, huge.length);

		AuthFormatException refused = assertThrows(AuthFormatException.class, () -> AuthCodec.decode(recheck(body)));
		assertTrue(refused.getMessage().contains("at most") || refused.getMessage().contains("bytes remain"),
			refused.getMessage());
	}

	/** A varint of nothing but continuation bytes must terminate rather than run to the end of the file. */
	@Test
	public void testANonTerminatingVarIntIsRefused() {
		byte[] body = new byte[AuthCodec.MAGIC.length + 2 + 2 + 4 + 8 + 64];
		System.arraycopy(AuthCodec.MAGIC, 0, body, 0, AuthCodec.MAGIC.length);
		body[AuthCodec.MAGIC.length] = AuthCodec.VERSION;
		java.util.Arrays.fill(body, AuthCodec.MAGIC.length + 16, body.length, (byte) 0x80);
		Throwable thrown = attempt(recheck(body));
		mustBeWellBehaved(thrown, "a non-terminating varint");
		assertTrue(thrown != null, "a varint of continuation bytes decoded");
	}

	/** A file larger than the cap is refused before it is parsed, not while it is. */
	@Test
	public void testAFileLargerThanTheCapIsRefused() {
		byte[] bytes = new byte[AuthCodec.MAX_FILE_BYTES + 1];
		System.arraycopy(AuthCodec.MAGIC, 0, bytes, 0, AuthCodec.MAGIC.length);
		AuthFormatException refused = assertThrows(AuthFormatException.class, () -> AuthCodec.decode(bytes));
		assertTrue(refused.getMessage().contains("larger than"), refused.getMessage());
	}

	@Test
	public void testNoBytesAtAllIsRefused() {
		mustBeWellBehaved(attempt(new byte[0]), "an empty file");
		assertThrows(AuthFormatException.class, () -> AuthCodec.decode(null));
	}

	/**
	 * Recomputes the trailing checksum over a hand-built or hand-edited body.
	 *
	 * <p>
	 * Without this, every one of the structural tests above would be caught by the CRC and would prove only that the
	 * CRC works — which {@link #testEverySingleBitMutationIsRefused()} already proves. Repairing it is what forces each
	 * of them through the check it is actually about.
	 * </p>
	 */
	private static byte[] recheck(byte[] withOrWithoutTrailer) {
		int bodyLength = withOrWithoutTrailer.length >= 4 && looksLikeItHasATrailer(withOrWithoutTrailer)
			? withOrWithoutTrailer.length - 4
			: withOrWithoutTrailer.length;
		byte[] result = new byte[bodyLength + 4];
		System.arraycopy(withOrWithoutTrailer, 0, result, 0, bodyLength);
		int crc = io.metaloom.graph.core.util.Crc.crc32c(result, 0, bodyLength);
		result[bodyLength] = (byte) crc;
		result[bodyLength + 1] = (byte) (crc >>> 8);
		result[bodyLength + 2] = (byte) (crc >>> 16);
		result[bodyLength + 3] = (byte) (crc >>> 24);
		return result;
	}

	/** True for a buffer produced by {@code AuthCodec.encode}, which is the only case that already has one. */
	private static boolean looksLikeItHasATrailer(byte[] bytes) {
		if (bytes.length < AuthCodec.MAGIC.length + 4) {
			return false;
		}
		int bodyLength = bytes.length - 4;
		int expected = io.metaloom.graph.core.util.Crc.crc32c(bytes, 0, bodyLength);
		int actual = (bytes[bodyLength] & 0xFF) | ((bytes[bodyLength + 1] & 0xFF) << 8)
			| ((bytes[bodyLength + 2] & 0xFF) << 16) | ((bytes[bodyLength + 3] & 0xFF) << 24);
		return expected == actual;
	}
}
