package io.metaloom.graph.client;

import java.time.Duration;

import io.metaloom.graph.protocol.Credentials;

/**
 * How the client behaves, independently of any one query.
 *
 * @param connectTimeout
 *            how long {@link GraphClient#connect} waits for the TCP connection <em>and</em> the version handshake. Both,
 *            because a server that accepts a connection and never answers HELLO is indistinguishable from a hang, and
 *            the point of a connect timeout is not to have one.
 * @param readBufferBytes
 *            how many undelivered payload bytes may sit in a connection's queues before the client stops reading the
 *            socket. This is the client's heap bound and the mechanism by which a slow consumer becomes the server's
 *            problem rather than the client's memory
 * @param socketReceiveBufferBytes
 *            {@code SO_RCVBUF}, the kernel half of the same bound. Set explicitly because Linux autotunes a loopback
 *            socket into the megabytes under a fast sender, and a client that pauses at four mebibytes of heap while
 *            the kernel holds another six has not paused at four mebibytes
 */
public record ClientOptions(Duration connectTimeout, int readBufferBytes, int socketReceiveBufferBytes,
	Credentials credentials) {

	/**
	 * The four-argument form, so every existing caller keeps compiling.
	 *
	 * <p>
	 * Credentials live here rather than in a {@code connect} overload because there are already six of those and
	 * adding the pair would make twelve. This record is the place for everything about how a client behaves that is
	 * not its address, it already has {@code with*} copy methods, and a component gets a paragraph of javadoc where a
	 * seventh positional parameter gets a name.
	 * </p>
	 */
	public ClientOptions(Duration connectTimeout, int readBufferBytes, int socketReceiveBufferBytes) {
		this(connectTimeout, readBufferBytes, socketReceiveBufferBytes, null);
	}

	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Four mebibytes, which is 64 row batches at the server's default batch size.
	 *
	 * <p>
	 * Enough that a consumer doing ordinary per-row work never stalls the socket, small enough that a consumer doing
	 * something slow stalls it long before the heap notices.
	 * </p>
	 */
	public static final int DEFAULT_READ_BUFFER_BYTES = 4 * 1024 * 1024;

	/** Matching the server's default send buffer, so neither end is the one that quietly holds more. */
	public static final int DEFAULT_SOCKET_RECEIVE_BUFFER_BYTES = 256 * 1024;

	public ClientOptions {
		if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
			throw new IllegalArgumentException("connectTimeout must be positive, was " + connectTimeout);
		}
		if (readBufferBytes < 64 * 1024) {
			// Below one row batch the socket would pause after every single frame, which is not backpressure, it is
			// a stall with extra steps.
			throw new IllegalArgumentException("readBufferBytes must be at least 65536, was " + readBufferBytes);
		}
		if (socketReceiveBufferBytes < 16 * 1024) {
			throw new IllegalArgumentException("socketReceiveBufferBytes must be at least 16384, was "
				+ socketReceiveBufferBytes);
		}
	}

	public static ClientOptions defaults() {
		return new ClientOptions(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_BUFFER_BYTES,
			DEFAULT_SOCKET_RECEIVE_BUFFER_BYTES);
	}

	/** Heap and kernel together: what one connection may be holding that the caller has not read yet. */
	public long maxBufferedBytes() {
		return readBufferBytes + (long) socketReceiveBufferBytes;
	}

	public ClientOptions withReadBufferBytes(int bytes) {
		return new ClientOptions(connectTimeout, bytes, socketReceiveBufferBytes, credentials);
	}

	public ClientOptions withSocketReceiveBufferBytes(int bytes) {
		return new ClientOptions(connectTimeout, readBufferBytes, bytes, credentials);
	}

	/** A copy that will log in as {@code user}. Null credentials mean this client offers none. */
	public ClientOptions withCredentials(Credentials credentials) {
		return new ClientOptions(connectTimeout, readBufferBytes, socketReceiveBufferBytes, credentials);
	}

	public ClientOptions withCredentials(String user, String password) {
		return withCredentials(new Credentials(user, password));
	}

	/**
	 * Everything except the password.
	 *
	 * <p>
	 * A record's generated {@code toString} prints every component, and this one holds a credential. Anything that
	 * logs its options — a connection failure, a debugger, a diagnostic dump — would otherwise put a password
	 * wherever that went. {@code Credentials} redacts its own half as well, so both layers have to be removed before
	 * this can leak.
	 * </p>
	 */
	@Override
	public String toString() {
		return "ClientOptions[connectTimeout=" + connectTimeout + ", readBufferBytes=" + readBufferBytes
			+ ", socketReceiveBufferBytes=" + socketReceiveBufferBytes
			+ ", credentials=" + (credentials == null ? "<none>" : credentials) + "]";
	}

	public ClientOptions withConnectTimeout(Duration timeout) {
		return new ClientOptions(timeout, readBufferBytes, socketReceiveBufferBytes, credentials);
	}
}
