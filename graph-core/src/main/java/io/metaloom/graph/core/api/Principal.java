package io.metaloom.graph.core.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Who a request is from, and what they may do.
 *
 * <p>
 * Every request carries one of these, on every front end, whether or not authentication is switched on. That is the
 * whole design: {@link #may} is the same call either way, so an enforcement site has exactly one shape and a route
 * added next year cannot forget a null check that was never there to write.
 * </p>
 *
 * <h2>{@link #ANONYMOUS} is a null object, and the asymmetry with {@code CsvSource.DENY} is deliberate</h2>
 *
 * <p>
 * The engine's other injected policy defaults to <b>refusal</b>: {@code CsvSource.DENY} exists so that an embedder who
 * never thought about file access cannot be turned into a file-reading primitive by a query string. The safe default
 * for a policy the caller forgot to set is deny.
 * </p>
 *
 * <p>
 * Authentication is the other case. When an operator has switched it off, the safe default is <b>allow</b>, because
 * the alternative is that {@code --no-auth} means "nothing works". What makes that defensible is that
 * {@link #ANONYMOUS} is not something a configured server can produce by accident: it comes from one factory on the
 * auth-disabled path, and a server with authentication enabled never hands out a principal whose
 * {@link #authenticated()} is false.
 * </p>
 *
 * <p>
 * And it is not a way round the deployment's own limits. {@link #may} returning true does not reach the server-wide
 * flags, which are checked first and separately, so a {@code --no-auth} server behaves exactly as it did before any of
 * this existed. That equivalence is what let the feature land in stages.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public interface Principal {

	/** The user name, or {@code "anonymous"}. Never null, because it goes into log lines and error messages. */
	String name();

	/**
	 * Whether a credential was actually presented and verified.
	 *
	 * <p>
	 * Distinct from "may do things": {@link #ANONYMOUS} may do everything and is not authenticated. A front end uses
	 * this to decide what to <em>say</em> — 401 versus 403 — where {@link #may} decides what to allow.
	 * </p>
	 */
	boolean authenticated();

	/** Every grant this principal holds, directly or through a role, for display and for the handshake. */
	Collection<Grant> grants();

	/**
	 * Whether this principal may exercise {@code privilege} against {@code database}.
	 *
	 * @param database
	 *            the database the request is about. Ignored for a globally-scoped privilege, and may be
	 *            {@link Grant#ALL_DATABASES} when the question genuinely is not about one
	 */
	boolean may(Privilege privilege, String database);

	/** Convenience for the many call sites whose privilege is not per-database. */
	default boolean may(Privilege privilege) {
		return may(privilege, Grant.ALL_DATABASES);
	}

	/**
	 * The principal a request gets on a server with authentication switched off.
	 *
	 * <p>
	 * It reports no grants rather than every grant. Listing all fifteen would be a lie of a particular kind — a UI
	 * would render "you have been granted DROP_DATABASE" to somebody nobody granted anything to — and
	 * {@link #authenticated()} being false already tells a caller which world it is in.
	 * </p>
	 */
	Principal ANONYMOUS = new Principal() {

		@Override
		public String name() {
			return "anonymous";
		}

		@Override
		public boolean authenticated() {
			return false;
		}

		@Override
		public Collection<Grant> grants() {
			return List.of();
		}

		@Override
		public boolean may(Privilege privilege, String database) {
			return true;
		}

		@Override
		public String toString() {
			return "anonymous (authentication disabled)";
		}
	};

	/**
	 * A principal holding exactly the grants given.
	 *
	 * <p>
	 * Used by the identity store once a password has been verified, and by tests that need a principal without one.
	 * The grants are copied, so a later change to the caller's collection cannot widen a live principal.
	 * </p>
	 */
	static Principal of(String name, Collection<Grant> grants) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("A principal needs a name");
		}
		Set<Grant> held = Set.copyOf(grants);
		return new Principal() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public boolean authenticated() {
				return true;
			}

			@Override
			public Collection<Grant> grants() {
				return held;
			}

			@Override
			public boolean may(Privilege privilege, String database) {
				String target = database == null ? Grant.ALL_DATABASES : database;
				for (Grant grant : held) {
					if (grant.permits(privilege, target)) {
						return true;
					}
				}
				return false;
			}

			@Override
			public String toString() {
				return name + " " + held;
			}
		};
	}
}
