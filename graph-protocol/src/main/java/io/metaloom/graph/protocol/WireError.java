package io.metaloom.graph.protocol;

/**
 * Why a query stopped, as a number the client can branch on.
 *
 * <p>
 * A code rather than only a message, because the distinctions here lead to genuinely different reactions and a client
 * cannot be asked to match on English. Rewriting the query is the answer to {@link #SYNTAX}; narrowing it is the answer
 * to {@link #TIMEOUT}; retrying later is the answer to {@link #OVERLOADED}; and nothing the caller does will help with
 * {@link #INTERNAL}, which is the server's problem and is logged as one.
 * </p>
 *
 * <p>
 * The message travels alongside and is the engine's own — for a syntax error, including the caret line that names the
 * position — so the code decides the reaction and the message explains it.
 * </p>
 */
public enum WireError {

	/** The server broke. Logged server-side with a stack trace; the client gets the message and nothing to fix. */
	INTERNAL(0),
	/** The query does not parse. {@code line} and {@code column} are set. */
	SYNTAX(1),
	/** The query parses but asks for something ill-typed, and only running it could have found that out. */
	TYPE(2),
	/** The query ran past its budget and was abandoned. The answer was never wrong, it was given up on. */
	TIMEOUT(3),
	/** The store refused: a closed store, a snapshot too old, a corrupt page. */
	STORE(4),
	/** Every stream slot is taken. Fail fast rather than queue — see {@code WireServerOptions}. */
	OVERLOADED(5),
	/** The peer sent something that is not this protocol. The connection closes; other connections are unaffected. */
	PROTOCOL(6),
	/** The client speaks a version this server does not. */
	UNSUPPORTED_VERSION(7),
	/** The client asked for this stream to stop, and it has. */
	CANCELLED(8);

	private final int code;

	WireError(int code) {
		this.code = code;
	}

	public int code() {
		return code;
	}

	/**
	 * @return the error for a code, or {@link #INTERNAL} for one this client does not know. A newer server may send a
	 *         code that is not in this enum yet, and a client that threw on the error frame rather than reporting it
	 *         would turn a legible failure into an illegible one.
	 */
	public static WireError fromCode(int code) {
		for (WireError error : values()) {
			if (error.code == code) {
				return error;
			}
		}
		return INTERNAL;
	}
}
