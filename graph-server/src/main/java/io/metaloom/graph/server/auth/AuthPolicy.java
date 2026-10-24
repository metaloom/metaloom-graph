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

	/**
	 * How many passwords may be verified at once, across every front end.
	 *
	 * <p>
	 * One derivation is around a hundred milliseconds of CPU, so a hundred concurrent logins is fifteen CPU-seconds.
	 * Bounding it turns a flood into a refusal — 503 on HTTP, {@code OVERLOADED} on the wire — rather than into a
	 * server that stops answering queries because it is busy hashing. Here rather than in one front end, because the
	 * CPU being rationed is the process's: a bound per port would let a flood use each port's share at once.
	 * </p>
	 */
	private final java.util.concurrent.Semaphore verifications =
		new java.util.concurrent.Semaphore(Runtime.getRuntime().availableProcessors());

	/** @return false when the bound is reached; the caller refuses rather than waits. A true must be released. */
	public boolean tryBeginVerification() {
		return verifications.tryAcquire();
	}

	public void endVerification() {
		verifications.release();
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
		reloadAtMostEverySecond();
		return sessions.userFor(token).flatMap(store::principal);
	}

	/**
	 * The principal a user name resolves to <b>now</b>: for a connection authenticated earlier that holds no token —
	 * the binary port. Empty once the user is disabled or dropped, so a front end can end the connection.
	 */
	public Optional<Principal> principalFor(String user) {
		if (store == null) {
			return Optional.of(Principal.ANONYMOUS);
		}
		reloadAtMostEverySecond();
		return store.principal(user);
	}

	/** When {@link #resolve} last looked at the file, in {@code System.nanoTime} terms. */
	private volatile long lastReloadCheck = System.nanoTime();

	private static final long RELOAD_CHECK_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

	/**
	 * Picks up an edit another process made to {@code .auth} — the CLI disabling a user while the server runs.
	 *
	 * <p>
	 * Without it only a login re-read the file, and a disabled user's existing session kept working until its idle
	 * timeout. Rate-limited because this runs on the event loop for every request: one {@code stat} a second, not one
	 * per request. Two threads racing past the check both stat, which is harmless.
	 * </p>
	 */
	private void reloadAtMostEverySecond() {
		long now = System.nanoTime();
		if (now - lastReloadCheck >= RELOAD_CHECK_NANOS) {
			lastReloadCheck = now;
			store.reloadIfChanged();
		}
	}

	public void close() {
		if (sessions != null) {
			sessions.close();
		}
	}
}
