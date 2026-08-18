package io.metaloom.graph.core.auth;

import io.metaloom.graph.core.api.User;

/**
 * A user and the credential that goes with them, which is the pairing that never leaves this package.
 *
 * <p>
 * The split exists so that {@link User} — the type every caller outside the store sees — has no field that could carry
 * a hash into a REST response, a log line or a generated {@code toString()}. Keeping them in one record and trusting
 * callers not to serialise the hash would work right up until somebody added a convenient getter, which is exactly the
 * kind of change that gets waved through.
 * </p>
 *
 * @param user
 *            what anyone may see
 * @param hash
 *            what only this package may
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public record StoredUser(User user, PasswordHash hash) {

	/** Same user, new credential, and the change timestamp moved with it. */
	public StoredUser withPassword(PasswordHash replacement, long changedAtEpochMillis) {
		return new StoredUser(new User(user.name(), user.enabled(), user.createdAtEpochMillis(),
			changedAtEpochMillis, user.roles(), user.grants()), replacement);
	}

	/** Same credential, replaced profile. */
	public StoredUser withUser(User replacement) {
		return new StoredUser(replacement, hash);
	}

	@Override
	public String toString() {
		// Not the default: a record's generated toString would print the PasswordHash field, and this record is the
		// one object in the system whose accidental appearance in a log line is a credential disclosure.
		return "StoredUser[" + user.name() + "]";
	}
}
