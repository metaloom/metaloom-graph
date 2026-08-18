package io.metaloom.graph.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opaque browser sessions, and the two clocks that end them.
 *
 * <h2>The table is keyed by a hash of the token, not the token</h2>
 *
 * <p>
 * A heap dump, a debugger, or an exception that printed this map would otherwise hand out live sessions. Storing
 * SHA-256 of the token means the server can recognise a token it is shown and cannot produce one it has not been
 * shown. It costs one hash per request, on the order of a microsecond, against a request that is about to run a
 * query.
 * </p>
 *
 * <h2>Two timeouts, not one</h2>
 *
 * <p>
 * An idle timeout ends a session somebody walked away from. An <b>absolute</b> cap ends one that is being kept alive:
 * without it, anything polling more often than the idle window holds a credential indefinitely, which is precisely
 * what a stolen token would do.
 * </p>
 *
 * <p>
 * The table is also bounded. An unbounded map behind a password gate is still an unbounded map, and the gate is the
 * only thing between it and a machine with a lot of valid credentials.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 6.1
 */
public final class SessionTable {

	/** Twelve hours. A working day, after which even an active session is asked who it is again. */
	public static final long ABSOLUTE_LIFETIME_MILLIS = 12 * 60 * 60 * 1000L;

	/** Past this, a login is refused rather than the table growing without limit. */
	public static final int MAX_SESSIONS = 10_000;

	/** 256 bits of token. Long enough that guessing is not a strategy, short enough to sit in a cookie. */
	private static final int TOKEN_BYTES = 32;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final SecureRandom random = new SecureRandom();

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	private final long idleTimeoutMillis;

	/** A session, identified only by the user it belongs to. Grants are never cached here — see {@link AuthPolicy}. */
	private record Session(String user, long createdAtMillis, AtomicLong lastSeenMillis) {
	}

	public SessionTable(long idleTimeoutMillis) {
		this.idleTimeoutMillis = idleTimeoutMillis;
	}

	/**
	 * Issues a token for a user who has just proved who they are.
	 *
	 * @return the token, which is the only time it exists in a form the server could hand out
	 * @throws IllegalStateException
	 *             if the table is full
	 */
	public String issue(String user) {
		sweep();
		if (sessions.size() >= MAX_SESSIONS) {
			throw new IllegalStateException("There are already " + MAX_SESSIONS + " sessions on this server");
		}
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String token = ENCODER.encodeToString(bytes);
		long now = System.currentTimeMillis();
		sessions.put(key(token), new Session(user, now, new AtomicLong(now)));
		return token;
	}

	/**
	 * The user a token belongs to, if it is still valid, refreshing its idle clock.
	 *
	 * <p>
	 * Expiry is checked here rather than only by the sweep, so a session is dead the moment it is too old even if
	 * nothing has swept yet. A timer that has not fired must never be the reason a stale credential works.
	 * </p>
	 */
	public Optional<String> userFor(String token) {
		if (token == null || token.isEmpty()) {
			return Optional.empty();
		}
		String key = key(token);
		Session session = sessions.get(key);
		if (session == null) {
			return Optional.empty();
		}
		long now = System.currentTimeMillis();
		if (expired(session, now)) {
			sessions.remove(key);
			return Optional.empty();
		}
		session.lastSeenMillis().set(now);
		return Optional.of(session.user());
	}

	/** Ends one session. Unknown tokens are ignored: a logout is not somewhere to learn whether a token was real. */
	public void invalidate(String token) {
		if (token != null && !token.isEmpty()) {
			sessions.remove(key(token));
		}
	}

	/** Ends every session belonging to a user, for a password change or a disable. */
	public void invalidateUser(String user) {
		sessions.entrySet().removeIf(entry -> entry.getValue().user().equals(user));
	}

	public int size() {
		sweep();
		return sessions.size();
	}

	/** Drops what is already dead. Called on issue and on size, so no timer thread is needed. */
	public void sweep() {
		long now = System.currentTimeMillis();
		sessions.entrySet().removeIf(entry -> expired(entry.getValue(), now));
	}

	private boolean expired(Session session, long now) {
		if (idleTimeoutMillis > 0 && now - session.lastSeenMillis().get() > idleTimeoutMillis) {
			return true;
		}
		return now - session.createdAtMillis() > ABSOLUTE_LIFETIME_MILLIS;
	}

	public void close() {
		sessions.clear();
	}

	/** SHA-256 of the token. See the class javadoc for why the token itself is not the key. */
	private static String key(String token) {
		try {
			return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
				.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is required of every JVM. In a native image a missing registration would surface here, and the
			// message says so rather than leaving somebody to wonder why sessions stopped working.
			throw new IllegalStateException("SHA-256 is not available; in a native image see spec/GRAALVM.md", e);
		}
	}
}
