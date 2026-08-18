package io.metaloom.graph.core.api;

import java.util.List;

/**
 * A user, as anything outside the identity store is allowed to see one.
 *
 * <h2>There is no password hash here, and that is the point</h2>
 *
 * <p>
 * The hash lives inside {@code io.metaloom.graph.core.auth}, which is not an exported package, and never reaches this
 * record. So listing users cannot leak one, a REST response cannot serialise one by accident, and — the failure this
 * is really aimed at — a generated {@code toString()} on a record cannot print one into a log line. A field that is not
 * on the object is the only version of that guarantee that cannot be undone by somebody adding a getter later.
 * </p>
 *
 * <p>
 * Verification therefore happens in one place, {@code AuthStore.authenticate}, rather than wherever a caller happens to
 * hold a user.
 * </p>
 *
 * @param name
 *            letters, digits, {@code -} and {@code _}, at most 64 characters — the same allow-list a database name
 *            faces, because this one also becomes a token in a file and a value in a log line
 * @param enabled
 *            a disabled user cannot log in and <b>loses any live session immediately</b>, because the flag is
 *            re-checked per request rather than frozen at login
 * @param createdAtEpochMillis
 *            when the user was created
 * @param passwordChangedAtEpochMillis
 *            when the password was last set; the one piece of credential history worth keeping, and it says nothing
 *            about the credential itself
 * @param roles
 *            role names, resolved against the store's roles. A name with no role is refused when the file is read
 * @param grants
 *            grants held directly, in addition to every role's. Direct grants exist for the one-off that does not
 *            deserve a role; roles are the intended mechanism
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public record User(String name, boolean enabled, long createdAtEpochMillis, long passwordChangedAtEpochMillis,
	List<String> roles, List<Grant> grants) {

	public User {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("A user needs a name");
		}
		roles = List.copyOf(roles);
		grants = List.copyOf(grants);
	}

	/** True when {@code role} is one of this user's, matched exactly — names are case sensitive, like a database's. */
	public boolean hasRole(String role) {
		return roles.contains(role);
	}
}
