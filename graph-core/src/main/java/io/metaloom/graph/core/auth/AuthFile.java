package io.metaloom.graph.core.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.Role;
import io.metaloom.graph.core.api.User;

/**
 * The whole identity file as one immutable in-memory image.
 *
 * <p>
 * Whole rather than incremental, and that is a size decision rather than a design preference: the caps are 4096 users
 * and 256 roles, so the file is tens of kilobytes and rewriting it costs less than maintaining an edit log would. It
 * also makes every write atomic for free — there is no partially-applied state to reason about, because the unit of
 * change is the file.
 * </p>
 *
 * <p>
 * {@link #generation} increments on every successful write and is what lets a read-modify-write notice that somebody
 * else changed the file underneath it. It is not a timestamp: two edits in the same millisecond are still two
 * generations.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 3
 */
public record AuthFile(long generation, List<Role> roles, List<StoredUser> users) {

	public AuthFile {
		roles = List.copyOf(roles);
		users = List.copyOf(users);
	}

	/** The image a root with no identity file yet has: nothing, at generation zero. */
	public static AuthFile empty() {
		return new AuthFile(0, List.of(), List.of());
	}

	public AuthFile withGeneration(long next) {
		return new AuthFile(next, roles, users);
	}

	public StoredUser user(String name) {
		for (StoredUser user : users) {
			if (user.user().name().equals(name)) {
				return user;
			}
		}
		return null;
	}

	public Role role(String name) {
		for (Role role : roles) {
			if (role.name().equals(name)) {
				return role;
			}
		}
		return null;
	}

	/**
	 * Every grant a user effectively holds: their own, plus every role's.
	 *
	 * <p>
	 * A role a user names but the file does not define cannot occur — {@link AuthCodec} refuses such a file rather than
	 * ignoring the reference — so this does not have to decide what a dangling role means.
	 * </p>
	 */
	public List<Grant> effectiveGrants(String userName) {
		StoredUser stored = user(userName);
		if (stored == null) {
			return List.of();
		}
		// Ordered and de-duplicated: two roles granting the same thing is ordinary, and a principal reporting it twice
		// would show up in the handshake and in the UI as a duplicate nobody can explain.
		Map<Grant, Boolean> effective = new LinkedHashMap<>();
		for (Grant grant : stored.user().grants()) {
			effective.put(grant, Boolean.TRUE);
		}
		for (String roleName : stored.user().roles()) {
			Role role = role(roleName);
			if (role != null) {
				for (Grant grant : role.grants()) {
					effective.put(grant, Boolean.TRUE);
				}
			}
		}
		return List.copyOf(effective.keySet());
	}

	/** The users, without their hashes, for anything that lists them. */
	public List<User> publicUsers() {
		List<User> out = new ArrayList<>(users.size());
		for (StoredUser stored : users) {
			out.add(stored.user());
		}
		return List.copyOf(out);
	}
}
