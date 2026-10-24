package io.metaloom.graph.core.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import io.metaloom.graph.core.api.AuthFormatException;

/**
 * A derived password hash, its parameters, and the two operations worth getting exactly right.
 *
 * <h2>PBKDF2, because it is in the JDK</h2>
 *
 * <p>
 * Argon2id would be the modern choice and neither it nor bcrypt is on this project's classpath. Adding a dependency to
 * a build whose entire third-party surface is Vert.x, Micrometer, SLF4J and JUnit is a larger decision than the
 * marginal resistance it buys, so this uses {@code PBKDF2WithHmacSHA512} at the OWASP iteration count for that
 * function. If that trade is ever revisited, {@link #kdfId} is the field that makes it survivable.
 * </p>
 *
 * <h2>The parameters are stored, not assumed</h2>
 *
 * <p>
 * Every hash carries its own algorithm, iteration count and salt. Raising the cost later therefore does not invalidate
 * anybody: an old hash still verifies at its own cost, and {@link #needsRehash()} tells the store to re-derive it at
 * the new one on the next successful login. A build that assumed today's constants would have to choose between
 * locking everyone out and never raising the cost again.
 * </p>
 *
 * <h2>Not a record</h2>
 *
 * <p>
 * A record with {@code byte[]} components would carry an {@code equals} that compares array identity and a
 * {@code toString} that prints a hash into whatever log line printed the object. Both are wrong here, and the second is
 * a credential leak, so this is a class with no {@code toString} of its own.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 4
 */
public final class PasswordHash {

	/** The one algorithm this build writes. A hash naming another is refused rather than guessed at. */
	public static final String PBKDF2_SHA512 = "pbkdf2-sha512";

	/** The JCA name behind {@link #PBKDF2_SHA512}. */
	private static final String JCA_ALGORITHM = "PBKDF2WithHmacSHA512";

	/** OWASP's figure for PBKDF2-HMAC-SHA512. Stored per hash, so raising it later costs nobody their password. */
	public static final int DEFAULT_ITERATIONS = 210_000;

	/** 128 bits. A salt only has to be unique, and this is comfortably past the birthday bound for any real user table. */
	public static final int SALT_BYTES = 16;

	/** 256 bits, matching the security level anybody would ask of this and no more. */
	public static final int HASH_BYTES = 32;

	/** Refused above this, so a hostile file cannot ask for hours of CPU per login attempt. */
	public static final int MAX_ITERATIONS = 10_000_000;

	public static final int MAX_SALT_BYTES = 64;

	public static final int MAX_HASH_BYTES = 64;

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final String kdfId;

	private final int iterations;

	private final byte[] salt;

	private final byte[] hash;

	private PasswordHash(String kdfId, int iterations, byte[] salt, byte[] hash) {
		this.kdfId = kdfId;
		this.iterations = iterations;
		this.salt = salt;
		this.hash = hash;
	}

	/**
	 * The hash a login attempt is checked against when the user does not exist.
	 *
	 * <p>
	 * Verifying against this costs the same as verifying against a real one, which is the whole reason it exists: a
	 * login endpoint that returned faster for an unknown name than for a wrong password is a user enumerator, and the
	 * difference is a hundred milliseconds — not a timing subtlety anyone would need equipment to measure.
	 * </p>
	 *
	 * <p>
	 * Derived from random bytes at class initialisation, so no password verifies against it.
	 * </p>
	 */
	public static final PasswordHash UNKNOWN_USER = unknownUser();

	private static PasswordHash unknownUser() {
		byte[] noise = new byte[32];
		RANDOM.nextBytes(noise);
		char[] impossible = ENCODER.encodeToString(noise).toCharArray();
		try {
			return of(impossible);
		} finally {
			Arrays.fill(impossible, '\0');
		}
	}

	/** Derives a hash for a new password, with a fresh salt and this build's parameters. */
	public static PasswordHash of(char[] password) {
		return of(password, DEFAULT_ITERATIONS);
	}

	/** Derives at an explicit cost. Tests use this; nothing else should. */
	public static PasswordHash of(char[] password, int iterations) {
		if (password == null || password.length == 0) {
			// The one place every new password passes through: AuthStore, the bootstrap and the CLI all land here, so
			// none of them can set a login that needs no secret.
			throw new IllegalArgumentException("A password may not be empty");
		}
		byte[] salt = new byte[SALT_BYTES];
		RANDOM.nextBytes(salt);
		return new PasswordHash(PBKDF2_SHA512, iterations, salt, derive(password, salt, iterations, HASH_BYTES));
	}

	/** Rebuilds a stored hash. Validates every parameter, because these arrive from a file. */
	public static PasswordHash restore(String kdfId, int iterations, byte[] salt, byte[] hash) {
		if (!PBKDF2_SHA512.equals(kdfId)) {
			throw new AuthFormatException("This build does not know the password function '" + kdfId + "'; it writes "
				+ PBKDF2_SHA512 + ". A newer build wrote this file.");
		}
		if (iterations < 1 || iterations > MAX_ITERATIONS) {
			throw new AuthFormatException("An iteration count of " + iterations + " is outside 1.." + MAX_ITERATIONS);
		}
		if (salt.length == 0 || salt.length > MAX_SALT_BYTES) {
			throw new AuthFormatException("A salt of " + salt.length + " bytes is outside 1.." + MAX_SALT_BYTES);
		}
		if (hash.length == 0 || hash.length > MAX_HASH_BYTES) {
			throw new AuthFormatException("A hash of " + hash.length + " bytes is outside 1.." + MAX_HASH_BYTES);
		}
		return new PasswordHash(kdfId, iterations, salt.clone(), hash.clone());
	}

	/**
	 * Whether {@code password} derives to this hash.
	 *
	 * <p>
	 * The comparison is {@link MessageDigest#isEqual}, which does not stop at the first differing byte. An
	 * {@code Arrays.equals} here would leak how much of a guess was right, one byte at a time.
	 * </p>
	 */
	public boolean verify(char[] password) {
		byte[] candidate = derive(password, salt, iterations, hash.length);
		try {
			// Derived first and refused after, so an empty attempt costs what any wrong one costs. Refused even
			// against a hash an older build made from "", because nothing should log in without a secret.
			boolean empty = password == null || password.length == 0;
			return !empty && MessageDigest.isEqual(hash, candidate);
		} finally {
			Arrays.fill(candidate, (byte) 0);
		}
	}

	/** Whether this was derived at weaker parameters than the build now uses, and should be re-derived on next login. */
	public boolean needsRehash() {
		return !PBKDF2_SHA512.equals(kdfId) || iterations < DEFAULT_ITERATIONS || hash.length < HASH_BYTES;
	}

	public String kdfId() {
		return kdfId;
	}

	public int iterations() {
		return iterations;
	}

	/** A copy, so nothing can mutate a live credential through the accessor. */
	public byte[] salt() {
		return salt.clone();
	}

	/** A copy, for the same reason. */
	public byte[] hash() {
		return hash.clone();
	}

	/**
	 * The text form, which is what {@code metaloom-graph-cli auth list} prints.
	 *
	 * <p>
	 * {@code pbkdf2-sha512$210000$<salt>$<hash>}, base64url without padding so it never needs quoting and never grows a
	 * {@code =} that a shell or a CSV would treat as special. Deliberately not {@code toString()}: printing a
	 * credential must be something a caller asks for by name.
	 * </p>
	 */
	public String toCanonicalString() {
		return kdfId + "$" + iterations + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(hash);
	}

	/** Reads {@link #toCanonicalString()}. */
	public static PasswordHash parse(String text) {
		if (text == null) {
			throw new AuthFormatException("A password hash is required");
		}
		String[] parts = text.split("\\$");
		if (parts.length != 4) {
			throw new AuthFormatException("A password hash is '<kdf>$<iterations>$<salt>$<hash>', was '" + text + "'");
		}
		int iterations;
		try {
			iterations = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			throw new AuthFormatException("'" + parts[1] + "' is not an iteration count");
		}
		try {
			return restore(parts[0], iterations, DECODER.decode(parts[2]), DECODER.decode(parts[3]));
		} catch (IllegalArgumentException e) {
			throw new AuthFormatException("A password hash holds base64url that does not decode: " + e.getMessage());
		}
	}

	private static byte[] derive(char[] password, byte[] salt, int iterations, int length) {
		// An empty password would make PBEKeySpec throw. of() refuses one and verify() answers false for one; this only
		// keeps the derivation itself running, so that refusal costs the same as a wrong password.
		char[] material = password == null || password.length == 0 ? new char[] { '\0' } : password;
		PBEKeySpec spec = new PBEKeySpec(material, salt, iterations, length * 8);
		try {
			return SecretKeyFactory.getInstance(JCA_ALGORITHM).generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException e) {
			// Reachable in a GraalVM native image whose reachability metadata is missing the provider, and nowhere
			// else. The message names that, because the alternative is somebody debugging their password.
			throw new PasswordFunctionUnavailableException(e);
		} catch (InvalidKeySpecException e) {
			throw new IllegalStateException("PBKDF2 refused a key specification this class built", e);
		} finally {
			spec.clearPassword();
		}
	}

	/** A password a caller holds as a {@code String} and cannot help holding that way. */
	public static char[] chars(String password) {
		return password == null ? new char[0] : password.toCharArray();
	}

	/**
	 * Best-effort scrub of a char array a caller is done with.
	 *
	 * <p>
	 * Hygiene rather than a guarantee, and the javadoc says so because the difference matters: the password very often
	 * arrived as a {@code String} from a JSON body or an argument vector, and that copy stays on the heap until it is
	 * collected. This closes the window it can, and no more.
	 * </p>
	 */
	public static void clear(char[] password) {
		if (password != null) {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Named apart so the GraalVM case reads as itself in a stack trace rather than as a generic failure.
	 *
	 * <p>
	 * A missing JCA registration fails at run time under {@code --no-fallback}, never at build time, so this message is
	 * the only thing standing between that mistake and somebody concluding they have forgotten their password.
	 * </p>
	 */
	public static final class PasswordFunctionUnavailableException extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		PasswordFunctionUnavailableException(Throwable cause) {
			super(JCA_ALGORITHM + " is not available from this JVM. In a native image this means the JCA provider is "
				+ "missing from graph-core's reachability-metadata.json; see spec/GRAALVM.md and spec/AUTH.md "
				+ "section 4.2.", cause);
		}
	}
}
