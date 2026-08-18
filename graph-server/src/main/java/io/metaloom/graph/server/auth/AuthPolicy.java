package io.metaloom.graph.server.auth;

import java.util.Optional;

import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Principal;

/**
 * Whether this server authenticates, and how a principal is obtained when it does.
 *
 * <p>
 * Every front end takes one of these and asks it for a principal. There is no {@code if (authEnabled)} at any
 * enforcement site — the branch is here, once — which is what keeps every check to the single shape
 * {@code principal.may(privilege, database)} and makes it impossible for a route added later to forget one.
 * </p>
 *
 * <h2>{@link #disabled()} is the only source of an unauthenticated principal</h2>
 *
 * <p>
 * {@link Principal#ANONYMOUS} permits everything, so where it can come from matters more than what it does.
 * {@link #disabled()} is the one factory that produces it, and {@link #enabled} never returns it — asserted by
 * {@code AuthPolicyTest}, because "the permissive principal cannot appear on a configured server" is the sentence the
 * whole design rests on.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 8
 */
public final class AuthPolicy {

	private final AuthStore store;

	private final SessionTable sessions;

	private final LoginThrottle throttle;

	private AuthPolicy(AuthStore store, SessionTable sessions, LoginThrottle throttle) {
		this.store = store;
		this.sessions = sessions;
		this.throttle = throttle;
	}

	/**
	 * A server that does not authenticate: every request is {@link Principal#ANONYMOUS} and may do anything.
	 *
	 * <p>
	 * "May do anything" stops at the capability flags, which are a separate and earlier check — so a server started
	 * this way behaves exactly as it did before authentication existed. That equivalence is what
	 * {@code AuthFlagCompositionTest} pins down.
	 * </p>
	 */
	public static AuthPolicy disabled() {
		return new AuthPolicy(null, null, null);
	}

	public static AuthPolicy enabled(AuthStore store, long sessionTimeoutMillis) {
		return new AuthPolicy(store, new SessionTable(sessionTimeoutMillis), new LoginThrottle());
	}

	public boolean isEnabled() {
		return store != null;
	}

	/** The identity store, for the routes that administer it. Absent when authentication is off. */
	public AuthStore store() {
		if (store == null) {
			throw new IllegalStateException("This server has authentication disabled and has no identity store");
		}
		return store;
	}

	public SessionTable sessions() {
		return sessions;
	}

	public LoginThrottle throttle() {
		return throttle;
	}

	/** The principal a request gets when it presented nothing: anonymous-and-permitted, or nobody. */
	public Optional<Principal> unauthenticated() {
		return store == null ? Optional.of(Principal.ANONYMOUS) : Optional.empty();
	}

	/**
	 * Verifies a password and issues nothing — the caller decides whether that becomes a session.
	 *
	 * <p>
	 * <b>Blocking, by design.</b> One call is on the order of a hundred milliseconds of PBKDF2. Never call it on an
	 * event loop; {@code LoginHandler} runs it on a worker.
	 * </p>
	 */
	public Optional<Principal> authenticate(String user, char[] password) {
		if (store == null) {
			return Optional.of(Principal.ANONYMOUS);
		}
		// Before the derivation, not after: this is where an offline `auth reset-admin` becomes visible, and it is one
		// stat against the hundred milliseconds that follow it.
		store.reloadIfChanged();
		return store.authenticate(user, password);
	}

	/**
	 * The principal behind a session token that is still valid.
	 *
	 * <p>
	 * Resolved through the identity store every time rather than from anything cached in the session, so a revoked
	 * grant or a disabled user takes effect on the next request instead of at the next login.
	 * </p>
	 */
	public Optional<Principal> resolve(String token) {
		if (store == null) {
			return Optional.of(Principal.ANONYMOUS);
		}
		return sessions.userFor(token).flatMap(store::principal);
	}

	public void close() {
		if (sessions != null) {
			sessions.close();
		}
	}
}
