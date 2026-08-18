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
	/**
	 * The server declined to spend more: every stream slot is taken, or it is shedding load to protect the store's
	 * reclamation horizon. Fail fast rather than queue — see {@code WireServerOptions}.
	 */
	OVERLOADED(5),
	/** The peer sent something that is not this protocol. The connection closes; other connections are unaffected. */
	PROTOCOL(6),
	/** The client speaks a version this server does not. */
	UNSUPPORTED_VERSION(7),
	/** The client asked for this stream to stop, and it has. */
	CANCELLED(8),
	/**
	 * The client named a database this server does not have.
	 *
	 * <p>
	 * Its own code because it is the one handshake failure a caller can fix without changing anything about its build:
	 * list the databases, or create the one it wanted. Folding it into {@link #PROTOCOL} would say "your client is
	 * wrong" about a typo.
	 * </p>
	 */
	NO_SUCH_DATABASE(9),
	/**
	 * The query would have written and this server does not accept writes.
	 *
	 * <p>
	 * Distinct from {@link #TYPE} because nothing is wrong with the query: the same text against a server started with
	 * {@code --allow-write} would run. It is a property of the server, so a client should report it rather than retry.
	 * </p>
	 */
	READ_ONLY(10),
	/**
	 * A write would have put two live elements on the same value of a unique key.
	 *
	 * <p>
	 * Its own code rather than {@link #STORE} because it is about the data the caller sent meeting data already there —
	 * the one store refusal that a caller can act on, by sending different data.
	 * </p>
	 */
	CONSTRAINT_VIOLATION(11),
	/**
	 * No credential was presented, or it was wrong.
	 *
	 * <p>
	 * Ends the connection, like every other handshake failure. Distinct from {@link #UNAUTHORIZED} because the remedy
	 * is different and is held by a different person: this one is fixed by presenting different credentials, and a
	 * client can say so to whoever is running it.
	 * </p>
	 *
	 * <p>
	 * It says nothing about which part was wrong. A server that answered differently for a user that does not exist
	 * would be a user enumerator on an unauthenticated port.
	 * </p>
	 */
	UNAUTHENTICATED(12),
	/**
	 * Authenticated, and not granted this.
	 *
	 * <p>
	 * Per stream, and the connection survives — structurally the same as {@link #READ_ONLY}, and for the same reason:
	 * the next query on it may well be one this principal may run.
	 * </p>
	 *
	 * <p>
	 * Distinct from {@link #READ_ONLY}, which is a property of the <em>server</em> and is fixed by restarting it with
	 * a flag. This is a property of the <em>caller</em> and is fixed by an administrator granting something. Reporting
	 * one as the other sends somebody after a change that would not help.
	 * </p>
	 */
	UNAUTHORIZED(13);

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
