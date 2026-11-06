package io.metaloom.graph.protocol;

/**
 * What the two ends have to agree on before anything else happens.
 *
 * <h2>Negotiation</h2>
 *
 * <p>
 * The client sends {@link Frames#HELLO} with its version and the database it wants, and waits; the server answers
 * {@link Frames#WELCOME} with its own version, its limits and the database it resolved, or an {@link Frames#ERROR}
 * carrying {@link WireError#UNSUPPORTED_VERSION} or {@link WireError#NO_SUCH_DATABASE} and closes. A client that
 * connected and started sending queries without waiting would get a stream of frames it could not read and no
 * explanation, which is the failure mode a handshake exists to replace.
 * </p>
 *
 * <h2>The database is per connection</h2>
 *
 * <p>
 * Chosen once, at the handshake, and fixed for the life of the connection — so a stream never has to say which
 * database it belongs to and the server resolves the store once instead of per query. Querying two databases at once
 * means two connections, which is the same shape a JDBC URL has and the same shape the read view wants: a view belongs
 * to one store, and a connection that could change store mid-flight would be a connection whose open views outlive
 * their subject.
 * </p>
 *
 * <p>
 * <b>The server's limits are told to the client rather than assumed by it.</b> Frame size and concurrent-stream count
 * are configurable on the server, so a client that hard-coded them would either refuse frames the server is entitled to
 * send or send more streams than it is allowed and discover it one {@link WireError#OVERLOADED} at a time.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
public final class Protocol {

	/**
	 * Version 4: the connection says who it is.
	 *
	 * <p>
	 * A server that wants a credential answers {@link Frames#HELLO} with {@link Frames#AUTH_REQUIRED} instead of
	 * {@link Frames#WELCOME}, and the client replies with {@link Frames#AUTH}. {@code WELCOME} then also carries the
	 * principal's name and its grants.
	 * </p>
	 *
	 * <p>
	 * <b>Credentials are not in {@code HELLO}</b>, and the reason is worth keeping: a client holding a password and
	 * talking to a server that does not want one completes the handshake without ever sending it. Putting them in
	 * {@code HELLO} would hand a password to any port that answered. It also keeps the two failures apart — the
	 * database a connection asked for depends on who is asking, and one frame with two error codes is worse than two
	 * frames with one each.
	 * </p>
	 *
	 * <p>
	 * Version 3 gave {@link Frames#COMPLETE} what the query changed; version 2 added the database to
	 * {@link Frames#HELLO} and {@link Frames#WELCOME}; version 1 had neither.
	 * </p>
	 *
	 * <p>
	 * A bump rather than an extension, and the handshake is why that costs nothing: an older client meets a clean
	 * {@link WireError#UNSUPPORTED_VERSION} naming both numbers instead of a stream of frames it cannot read. Nothing
	 * has shipped on any version, so there is no range to accept — when there is, this is where it goes.
	 * </p>
	 *
	 * <p>
	 * <b>Owed: version 5, for the temporal value tag.</b> Tag 15 was added to {@link WireValueCodec} at version 4,
	 * because this constant's value is part of the locked API and changing it is a person's decision
	 * (spec/API_LOCK.md). Until then a version-4 peer without the tag fails on the first temporal cell with a
	 * {@link WireFormatException} rather than at the handshake.
	 * </p>
	 */
	public static final int VERSION = 4;

	/** Not 8080, so the binary port and the HTTP port can both run over one store without an argument about it. */
	public static final int DEFAULT_PORT = 8081;

	private Protocol() {
	}
}
