package io.metaloom.graph.core.api;

import java.util.List;

/**
 * A named set of grants.
 *
 * <p>
 * Roles exist because grants are given to <b>jobs</b> rather than to people: "the importer", "the dashboard", "the
 * analyst". Two consequences make them worth the indirection — a new person doing an existing job needs no decisions
 * taken about them, and withdrawing a job is one edit instead of one per person, which is the edit somebody makes in a
 * hurry and must not get half right.
 * </p>
 *
 * <p>
 * A user's effective grants are their own plus every role's. There is <b>no deny rule and no precedence order</b>: a
 * grant is additive and the absence of one is the refusal. A deny rule would need an ordering against the grants, and
 * every ordering anyone picks surprises somebody.
 * </p>
 *
 * @param name
 *            the same allow-list a user name faces
 * @param grants
 *            what holding this role permits
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public record Role(String name, List<Grant> grants) {

	public Role {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("A role needs a name");
		}
		grants = List.copyOf(grants);
	}

	/** The role a first start creates, holding every privilege on every database. */
	public static final String ADMIN = "admin";
}
