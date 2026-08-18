package io.metaloom.graph.core.auth;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.metaloom.graph.core.api.AuthException;
import io.metaloom.graph.core.api.AuthFormatException;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.api.Role;
import io.metaloom.graph.core.api.User;
import io.metaloom.graph.core.util.Crc;

/**
 * The identity file's byte format, as a pure function in each direction.
 *
 * <pre>
 * magic     "MLGAUTH\0"                     8 bytes, so the body starts 8-aligned
 * u16       formatVersion = 1
 * u16       reserved (0)
 * u32       flags (0)
 * u64       generation
 * varInt    roleCount
 *   per role:  string name, varInt grantCount, {string privilege, string database} per grant
 * varInt    userCount
 *   per user:  string name, u8 flags, u64 createdAt, u64 passwordChangedAt,
 *              string kdfId, u32 iterations, varInt saltLength + salt, varInt hashLength + hash,
 *              varInt roleCount + names, varInt grantCount + grants
 * u32       crc32c over every preceding byte
 * </pre>
 *
 * <h2>No filesystem here, on purpose</h2>
 *
 * <p>
 * {@link #decode(byte[])} takes bytes and returns an image, exactly as {@code WireValueCodec} does and for the same
 * reason: the whole hostile-input surface is then one function that a test can call a hundred thousand times without a
 * directory, a lock or a clock. {@link AuthStore} does the I/O.
 * </p>
 *
 * <h2>The one rule</h2>
 *
 * <p>
 * <b>Every length is checked against the bytes actually remaining before anything is allocated for it.</b> Seven
 * defects of exactly that kind were found in this project's storage parsers and three more in the wire decoder, and
 * every one of them was a length field that was believed. The caps below are a second line, not the first: the first is
 * that a count can never exceed what is left in the array.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 3
 */
public final class AuthCodec {

	/** {@code MLGAUTH\0} — eight bytes, so the body starts 8-aligned. */
	public static final byte[] MAGIC = { 'M', 'L', 'G', 'A', 'U', 'T', 'H', 0 };

	/** The format's own version. An unknown one is refused by number rather than guessed at. */
	public static final int VERSION = 1;

	/**
	 * The largest identity file this build will read.
	 *
	 * <p>
	 * Comfortably above {@link #MAX_USERS} users at their maximum size and far below anything that could be an
	 * allocation problem, so a file larger than this is a mistake or an attack rather than a big deployment.
	 * </p>
	 */
	public static final int MAX_FILE_BYTES = 1024 * 1024;

	public static final int MAX_USERS = 4096;

	public static final int MAX_ROLES = 256;

	/** Per user and per role. Fifteen privileges times a realistic number of databases, with room to spare. */
	public static final int MAX_GRANTS = 512;

	/** Per user. A user in more roles than there are roles is corrupt by definition. */
	public static final int MAX_ROLE_REFERENCES = MAX_ROLES;

	/** The fixed part: magic, version, reserved, flags, generation, and the trailing checksum. */
	private static final int FIXED_BYTES = MAGIC.length + 2 + 2 + 4 + 8 + 4;

	private AuthCodec() {
	}

	// ---------------------------------------------------------------- encode

	public static byte[] encode(AuthFile file) {
		Writer out = new Writer();
		out.bytes(MAGIC);
		out.u16(VERSION);
		out.u16(0);
		out.u32(0);
		out.u64(file.generation());

		out.varInt(file.roles().size());
		for (Role role : file.roles()) {
			out.string(role.name());
			out.varInt(role.grants().size());
			for (Grant grant : role.grants()) {
				out.string(grant.privilege().name());
				out.string(grant.database());
			}
		}

		out.varInt(file.users().size());
		for (StoredUser stored : file.users()) {
			User user = stored.user();
			PasswordHash hash = stored.hash();
			out.string(user.name());
			out.u8(user.enabled() ? 0 : 1);
			out.u64(user.createdAtEpochMillis());
			out.u64(user.passwordChangedAtEpochMillis());
			out.string(hash.kdfId());
			out.u32(hash.iterations());
			byte[] salt = hash.salt();
			out.varInt(salt.length);
			out.bytes(salt);
			byte[] digest = hash.hash();
			out.varInt(digest.length);
			out.bytes(digest);
			out.varInt(user.roles().size());
			for (String role : user.roles()) {
				out.string(role);
			}
			out.varInt(user.grants().size());
			for (Grant grant : user.grants()) {
				out.string(grant.privilege().name());
				out.string(grant.database());
			}
		}

		byte[] body = out.toByteArray();
		byte[] result = new byte[body.length + 4];
		System.arraycopy(body, 0, result, 0, body.length);
		int crc = Crc.crc32c(body, 0, body.length);
		result[body.length] = (byte) crc;
		result[body.length + 1] = (byte) (crc >>> 8);
		result[body.length + 2] = (byte) (crc >>> 16);
		result[body.length + 3] = (byte) (crc >>> 24);
		if (result.length > MAX_FILE_BYTES) {
			throw new AuthException(result.length + " bytes of identity is more than the " + MAX_FILE_BYTES
				+ " byte limit this build will read back. Reduce the number of users, roles or grants.");
		}
		return result;
	}

	// ---------------------------------------------------------------- decode

	/**
	 * Reads an identity file.
	 *
	 * @throws AuthFormatException
	 *             and nothing else. That is the entire contract {@code AuthHostileInputTest} asserts
	 */
	public static AuthFile decode(byte[] bytes) {
		if (bytes == null) {
			throw new AuthFormatException("There are no identity bytes to read");
		}
		if (bytes.length > MAX_FILE_BYTES) {
			throw new AuthFormatException("An identity file of " + bytes.length + " bytes is larger than the "
				+ MAX_FILE_BYTES + " byte limit");
		}
		if (bytes.length < FIXED_BYTES) {
			throw new AuthFormatException("An identity file is at least " + FIXED_BYTES + " bytes, this is "
				+ bytes.length + ". It is truncated.");
		}
		for (int i = 0; i < MAGIC.length; i++) {
			if (bytes[i] != MAGIC[i]) {
				throw new AuthFormatException("This is not an identity file: the magic is wrong at byte " + i);
			}
		}

		// The checksum first, before a single field is acted on. A file that fails it is corrupt however plausible its
		// lengths look, and parsing it to find out would mean acting on bytes already known to be untrustworthy.
		int bodyLength = bytes.length - 4;
		int expected = Crc.crc32c(bytes, 0, bodyLength);
		int actual = (bytes[bodyLength] & 0xFF)
			| ((bytes[bodyLength + 1] & 0xFF) << 8)
			| ((bytes[bodyLength + 2] & 0xFF) << 16)
			| ((bytes[bodyLength + 3] & 0xFF) << 24);
		if (expected != actual) {
			throw new AuthFormatException("The identity file's checksum does not match: it is corrupt. Recover it "
				+ "with 'metaloom-graph-cli auth reset-admin --path=<root> --force', which replaces it.");
		}

		Reader in = new Reader(bytes, bodyLength);
		in.skip(MAGIC.length);
		int version = in.u16();
		if (version != VERSION) {
			throw new AuthFormatException("This build reads identity format version " + VERSION + " and the file is "
				+ "version " + version + ". A newer build wrote it.");
		}
		in.u16();
		in.u32();
		long generation = in.u64();
		if (generation < 0) {
			throw new AuthFormatException("A generation of " + generation + " is not a generation");
		}

		int roleCount = in.count("roles", MAX_ROLES);
		List<Role> roles = new ArrayList<>(roleCount);
		Set<String> roleNames = new HashSet<>();
		for (int i = 0; i < roleCount; i++) {
			String name = in.name("role");
			if (!roleNames.add(name)) {
				throw new AuthFormatException("The identity file defines the role '" + name + "' twice, so which "
					+ "grants it carries is ambiguous");
			}
			roles.add(new Role(name, in.grants()));
		}

		int userCount = in.count("users", MAX_USERS);
		List<StoredUser> users = new ArrayList<>(userCount);
		Set<String> userNames = new HashSet<>();
		for (int i = 0; i < userCount; i++) {
			String name = in.name("user");
			if (!userNames.add(name)) {
				throw new AuthFormatException("The identity file defines the user '" + name + "' twice, so which "
					+ "credential is theirs is ambiguous");
			}
			boolean enabled = (in.u8() & 1) == 0;
			long createdAt = in.u64();
			long passwordChangedAt = in.u64();

			String kdfId = in.string("a password function");
			int iterations = in.u32();
			byte[] salt = in.lengthPrefixedBytes("a salt", PasswordHash.MAX_SALT_BYTES);
			byte[] digest = in.lengthPrefixedBytes("a hash", PasswordHash.MAX_HASH_BYTES);
			PasswordHash hash = PasswordHash.restore(kdfId, iterations, salt, digest);

			int referenced = in.count("role references", MAX_ROLE_REFERENCES);
			List<String> userRoles = new ArrayList<>(referenced);
			for (int r = 0; r < referenced; r++) {
				String role = in.name("role");
				if (!roleNames.contains(role)) {
					// Refused rather than dropped. Silently ignoring it would turn a corrupt file into a user with
					// fewer grants than the file says they have, which is a wrong answer that looks like a right one.
					throw new AuthFormatException("The user '" + name + "' holds the role '" + role
						+ "', which this file does not define");
				}
				userRoles.add(role);
			}

			users.add(new StoredUser(new User(name, enabled, createdAt, passwordChangedAt, userRoles, in.grants()),
				hash));
		}

		if (in.position() != bodyLength) {
			throw new AuthFormatException("The identity file has " + (bodyLength - in.position())
				+ " bytes after the last user, which this build does not understand");
		}
		return new AuthFile(generation, roles, users);
	}

	// ---------------------------------------------------------------- the reader

	/** Bounds-checked, and every method that allocates asks {@link #checkAvailable} first. */
	private static final class Reader {

		private final byte[] src;

		private final int limit;

		private int position;

		Reader(byte[] src, int limit) {
			this.src = src;
			this.limit = limit;
		}

		int position() {
			return position;
		}

		void skip(int bytes) {
			checkAvailable(bytes);
			position += bytes;
		}

		/**
		 * Refuses a length that cannot be satisfied, before it is used to allocate.
		 *
		 * <p>
		 * Against the bytes genuinely left rather than against a constant: a cap big enough for a real file would be
		 * far larger than most corrupt lengths, and one small enough to catch them would refuse real data.
		 * </p>
		 */
		void checkAvailable(long bytes) {
			if (bytes < 0) {
				throw new AuthFormatException("A negative length (" + bytes + ") at byte " + position);
			}
			if (bytes > limit - position) {
				throw new AuthFormatException("A length of " + bytes + " at byte " + position + ", but only "
					+ (limit - position) + " bytes remain. The identity file is truncated or corrupt.");
			}
		}

		int u8() {
			checkAvailable(1);
			return src[position++] & 0xFF;
		}

		int u16() {
			return u8() | (u8() << 8);
		}

		int u32() {
			return u8() | (u8() << 8) | (u8() << 16) | (u8() << 24);
		}

		long u64() {
			long value = 0;
			for (int i = 0; i < 8; i++) {
				value |= (long) u8() << (8 * i);
			}
			return value;
		}

		/** LEB128, bounded to {@code int} because every use of it is a count or a length. */
		int varInt() {
			long value = 0;
			int shift = 0;
			while (true) {
				// Ten groups of seven bits is more than a long holds, so a run of continuation bytes terminates here
				// rather than spinning to the end of the file.
				if (shift > 63) {
					throw new AuthFormatException("A varint at byte " + position + " does not terminate");
				}
				int b = u8();
				value |= (long) (b & 0x7F) << shift;
				if ((b & 0x80) == 0) {
					break;
				}
				shift += 7;
			}
			if (value < 0 || value > Integer.MAX_VALUE) {
				throw new AuthFormatException("A count of " + value + " at byte " + position + " is out of range");
			}
			return (int) value;
		}

		/**
		 * A count, checked twice: against its declared cap, and against the bytes left.
		 *
		 * <p>
		 * The second check is what makes the allocation safe. A file claiming 4,000 users in eighty bytes passes the
		 * cap and is still a lie, and the cheapest lower bound on a record is one byte.
		 * </p>
		 */
		int count(String what, int max) {
			int value = varInt();
			if (value > max) {
				throw new AuthFormatException("An identity file may hold at most " + max + " " + what + ", this one "
					+ "claims " + value);
			}
			checkAvailable(value);
			return value;
		}

		String string(String what) {
			int length = varInt();
			checkAvailable(length);
			String value = new String(src, position, length, StandardCharsets.UTF_8);
			position += length;
			if (value.isEmpty()) {
				throw new AuthFormatException(what + " at byte " + position + " is empty");
			}
			return value;
		}

		/** A user or role name, through the same allow-list every other name in this project faces. */
		String name(String kind) {
			String value = string("a " + kind + " name");
			try {
				AuthNames.validate(kind, value);
			} catch (IllegalArgumentException e) {
				throw new AuthFormatException("The identity file holds " + e.getMessage());
			}
			return value;
		}

		byte[] lengthPrefixedBytes(String what, int max) {
			int length = varInt();
			if (length > max) {
				throw new AuthFormatException(what + " of " + length + " bytes is longer than the " + max
					+ " byte limit");
			}
			checkAvailable(length);
			byte[] value = new byte[length];
			System.arraycopy(src, position, value, 0, length);
			position += length;
			return value;
		}

		List<Grant> grants() {
			int count = count("grants", MAX_GRANTS);
			List<Grant> grants = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				String privilege = string("a privilege name");
				String database = string("a database name");
				try {
					// An unknown privilege is refused, never skipped: a build that half understood this file and then
					// rewrote it would silently drop the grant, and losing authority quietly is as bad as gaining it.
					grants.add(new Grant(Privilege.parse(privilege), database));
				} catch (IllegalArgumentException e) {
					throw new AuthFormatException("The identity file holds a grant this build cannot read: "
						+ e.getMessage());
				}
			}
			return grants;
		}
	}

	// ---------------------------------------------------------------- the writer

	private static final class Writer {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream(4096);

		void u8(int value) {
			out.write(value & 0xFF);
		}

		void u16(int value) {
			u8(value);
			u8(value >>> 8);
		}

		void u32(int value) {
			u8(value);
			u8(value >>> 8);
			u8(value >>> 16);
			u8(value >>> 24);
		}

		void u64(long value) {
			for (int i = 0; i < 8; i++) {
				u8((int) (value >>> (8 * i)));
			}
		}

		void varInt(long value) {
			long v = value;
			while ((v & ~0x7FL) != 0) {
				u8((int) ((v & 0x7F) | 0x80));
				v >>>= 7;
			}
			u8((int) v);
		}

		void bytes(byte[] value) {
			out.write(value, 0, value.length);
		}

		void string(String value) {
			byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
			varInt(encoded.length);
			bytes(encoded);
		}

		byte[] toByteArray() {
			return out.toByteArray();
		}
	}
}
