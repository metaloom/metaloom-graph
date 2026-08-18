package io.metaloom.graph.core.api;

/**
 * The identity store refused: a name that is not a name, an edit against a file that moved underneath it, a lock
 * somebody else holds.
 *
 * <p>
 * Deliberately <b>not</b> thrown for a wrong password. Authentication returning an empty result rather than throwing is
 * what keeps a caller from turning a failed login into a stack trace, and what stops the two cases — "no such user" and
 * "wrong password" — from being distinguishable by the shape of the failure. {@code AuthStore.authenticate} returns an
 * {@code Optional}; this is for the things that are genuinely wrong.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public class AuthException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	public AuthException(String message) {
		super(message);
	}

	public AuthException(String message, Throwable cause) {
		super(message, cause);
	}
}
