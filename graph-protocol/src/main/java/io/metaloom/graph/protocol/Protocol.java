package io.metaloom.graph.protocol;

/**
 * What the two ends have to agree on before anything else happens.
 *
 * <h2>Negotiation</h2>
 *
 * <p>
 * The client sends {@link Frames#HELLO} with its version and waits; the server answers {@link Frames#WELCOME} with its
 * own version and its limits, or an {@link Frames#ERROR} carrying {@link WireError#UNSUPPORTED_VERSION} and closes.
 * A client that connected and started sending queries without waiting would get a stream of frames it could not read
 * and no explanation, which is the failure mode a handshake exists to replace.
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
	 * Version 1.
	 *
	 * <p>
	 * There is no compatibility range yet because there is nothing to be compatible with: exactly one version has ever
	 * existed. When a second appears, this is where the accepted range goes, and the handshake is already the place it
	 * would be enforced.
	 * </p>
	 */
	public static final int VERSION = 1;

	/** Not 8080, so the binary port and the HTTP port can both run over one store without an argument about it. */
	public static final int DEFAULT_PORT = 8081;

	private Protocol() {
	}
}
