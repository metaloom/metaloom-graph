package io.metaloom.graph.server.wire;

import io.metaloom.graph.protocol.Protocol;
import io.metaloom.graph.server.GraphServerOptions;

/**
 * How the binary port is configured, and the one number an operator has to multiply.
 *
 * <h2>The memory bound</h2>
 *
 * <p>
 * <b>{@link #maxConcurrentStreams} × ({@link #writeQueueMaxBytes} + {@link #socketSendBufferBytes}) is the most this
 * server can be holding on behalf of clients that have stopped reading.</b> Those three are next to each other in this
 * record, and validated together, because they are not independent knobs — they are the factors of one number, and that
 * number is how you size the server.
 * </p>
 *
 * <p>
 * The send buffer is in the product because it is real memory held for a peer that is not reading, even though it is
 * the kernel's rather than the heap's. Leaving it out was not a rounding error: Linux autotunes a loopback socket's
 * buffers into the megabytes under a fast writer, so a four-megabyte result went out in full against a deliberately
 * slow client while the write queue never once looked full. A bound that a test could walk past is not a bound.
 * </p>
 *
 * <p>
 * This is stated so plainly because it is the known weakness of the transport that was chosen. The measured trial found
 * gRPC holding 4.7x less under a slow client, for the structural reason that HTTP/2's window is a limit the <em>peer</em>
 * grants while a write queue is a local reaction to bytes already accepted. The custom protocol won on other grounds
 * and this is the price; it is only worth paying if the bound is written down and enforced rather than left to be
 * discovered. {@link #MAX_BUFFERED_BYTES} is the enforcement.
 * </p>
 *
 * @param host
 *            what to bind. Loopback by default: the port has no authentication and no transport security, and a new
 *            unauthenticated port should not default to every interface
 * @param port
 *            0 for any free one
 * @param maxConcurrentStreams
 *            queries that may run at once, across every connection. The n+1th is refused with
 *            {@code OVERLOADED} rather than queued — a queued query still holds nothing, but a client waiting on one
 *            cannot tell the difference between a slow server and a stuck one
 * @param writeQueueMaxBytes
 *            how much one stream may have accepted and not yet flushed before the producer stops handing it rows
 * @param socketSendBufferBytes
 *            {@code SO_SNDBUF} on every accepted connection. Set explicitly rather than left to autotuning, because
 *            autotuning optimises for throughput and this port needs a number an operator can multiply
 * @param rowBatchBytes
 *            rows are encoded into a batch of about this size before a frame is emitted, so per-frame overhead stays
 *            off the throughput
 * @param flushIntervalMillis
 *            ...but a partial batch is never held longer than this. Without it a query producing a row a second would
 *            sit in the batch buffer for a minute, and time to first row — the thing streaming is for — would be worse
 *            than materialising
 * @param queryTimeoutMillis
 *            how long one query may run, 0 for no limit. <b>This is also the bound on how long a read view stays
 *            open</b>, because the view is opened and closed inside the query
 * @param retentionSoftLimitBytes
 *            when the store's retained bytes cross this, the oldest running stream is abandoned. 0 disables it
 * @param watchdogIntervalMillis
 *            how often retention is checked
 */
public record WireServerOptions(
	String host,
	int port,
	int maxConcurrentStreams,
	int writeQueueMaxBytes,
	int socketSendBufferBytes,
	int rowBatchBytes,
	long flushIntervalMillis,
	long queryTimeoutMillis,
	long retentionSoftLimitBytes,
	long watchdogIntervalMillis) {

	public static final String DEFAULT_HOST = "127.0.0.1";
	public static final int DEFAULT_MAX_CONCURRENT_STREAMS = 8;
	public static final int DEFAULT_WRITE_QUEUE_MAX_BYTES = 4 * 1024 * 1024;

	/**
	 * 256 KiB of kernel send buffer per connection.
	 *
	 * <p>
	 * Comfortably above what a loopback or LAN round trip needs to stay full, and small enough that eight of them are
	 * a number rather than a surprise. A high-latency link would want more; that link would also want TLS and
	 * authentication, and this port has neither.
	 * </p>
	 */
	public static final int DEFAULT_SOCKET_SEND_BUFFER_BYTES = 256 * 1024;

	public static final int DEFAULT_ROW_BATCH_BYTES = 64 * 1024;
	public static final long DEFAULT_FLUSH_INTERVAL_MILLIS = 20;

	/**
	 * The ceiling on {@link #maxBufferedBytes()}.
	 *
	 * <p>
	 * 128 MiB, which the defaults use a quarter of. It exists to refuse the configuration nobody would write on
	 * purpose and everybody could write by accident: raising the write queue to 40 MiB and the stream count to 80 is
	 * two reasonable-looking edits that together commit the server to 3.2 GiB of buffered rows.
	 * </p>
	 */
	public static final long MAX_BUFFERED_BYTES = 128L * 1024 * 1024;

	/**
	 * 256 MiB of retained pages.
	 *
	 * <p>
	 * Measured: an open read view held across 60k commits retains about 200 MiB. Past its own budget the store
	 * abandons <b>the oldest</b> view, which need not be the one misbehaving — so one slow reader breaks a
	 * well-behaved one. Abandoning the oldest stream ourselves, before the store has to, means the client gets a typed
	 * error naming the reason instead of an opaque {@code SnapshotTooOldException} arriving somewhere else.
	 * </p>
	 */
	public static final long DEFAULT_RETENTION_SOFT_LIMIT_BYTES = 256L * 1024 * 1024;

	public static final long DEFAULT_WATCHDOG_INTERVAL_MILLIS = 250;

	public WireServerOptions {
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("A bind address is required");
		}
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("Port must be between 0 and 65535, was " + port);
		}
		if (maxConcurrentStreams < 1) {
			throw new IllegalArgumentException("maxConcurrentStreams must be at least 1, was " + maxConcurrentStreams);
		}
		if (writeQueueMaxBytes < DEFAULT_ROW_BATCH_BYTES) {
			throw new IllegalArgumentException("writeQueueMaxBytes must be at least one row batch ("
				+ DEFAULT_ROW_BATCH_BYTES + "), was " + writeQueueMaxBytes);
		}
		if (rowBatchBytes < 1024) {
			throw new IllegalArgumentException("rowBatchBytes must be at least 1024, was " + rowBatchBytes);
		}
		if (flushIntervalMillis < 1) {
			throw new IllegalArgumentException("flushIntervalMillis must be at least 1, was " + flushIntervalMillis);
		}
		if (queryTimeoutMillis < 0) {
			throw new IllegalArgumentException("queryTimeoutMillis must not be negative, was " + queryTimeoutMillis);
		}
		if (retentionSoftLimitBytes < 0) {
			throw new IllegalArgumentException("retentionSoftLimitBytes must not be negative, was "
				+ retentionSoftLimitBytes);
		}
		if (watchdogIntervalMillis < 1) {
			throw new IllegalArgumentException("watchdogIntervalMillis must be at least 1, was "
				+ watchdogIntervalMillis);
		}
		if (socketSendBufferBytes < 16 * 1024) {
			throw new IllegalArgumentException("socketSendBufferBytes must be at least 16384, was "
				+ socketSendBufferBytes);
		}
		// From the parameters, not from maxBufferedBytes(): inside a compact constructor the fields are not assigned
		// yet, so the accessor would read zeros and this check would pass for every configuration ever written.
		long buffered = (long) maxConcurrentStreams * (writeQueueMaxBytes + (long) socketSendBufferBytes);
		if (buffered > MAX_BUFFERED_BYTES) {
			// Named as a multiplication rather than as "too big", because the mistake is always made one factor at a
			// time and the product is what nobody looked at.
			throw new IllegalArgumentException("maxConcurrentStreams (" + maxConcurrentStreams
				+ ") x (writeQueueMaxBytes (" + writeQueueMaxBytes + ") + socketSendBufferBytes ("
				+ socketSendBufferBytes + ")) is " + buffered
				+ " bytes this server would hold for clients that stopped reading, over the "
				+ MAX_BUFFERED_BYTES + " byte ceiling. Lower any factor.");
		}
	}

	public static WireServerOptions defaults() {
		return new WireServerOptions(DEFAULT_HOST, Protocol.DEFAULT_PORT, DEFAULT_MAX_CONCURRENT_STREAMS,
			DEFAULT_WRITE_QUEUE_MAX_BYTES, DEFAULT_SOCKET_SEND_BUFFER_BYTES, DEFAULT_ROW_BATCH_BYTES,
			DEFAULT_FLUSH_INTERVAL_MILLIS,
			// The same budget the HTTP route uses, from the same constant. Two front ends over one store that
			// disagreed about how long a query may run would be a confusing thing to debug.
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, DEFAULT_RETENTION_SOFT_LIMIT_BYTES,
			DEFAULT_WATCHDOG_INTERVAL_MILLIS);
	}

	/**
	 * Everything the server may be holding for peers that are not reading: heap and kernel together.
	 *
	 * <p>
	 * Reported at startup so the number is in the log rather than only in this javadoc.
	 * </p>
	 */
	public long maxBufferedBytes() {
		return (long) maxConcurrentStreams * (writeQueueMaxBytes + (long) socketSendBufferBytes);
	}

	/** The heap half of it — the part {@code WireServer.bufferedBytes()} can actually measure. */
	public long maxWriteQueueBytes() {
		return (long) maxConcurrentStreams * writeQueueMaxBytes;
	}

	public WireServerOptions withHost(String host) {
		return with(host, port, maxConcurrentStreams, writeQueueMaxBytes, socketSendBufferBytes, queryTimeoutMillis,
			retentionSoftLimitBytes);
	}

	public WireServerOptions withPort(int port) {
		return with(host, port, maxConcurrentStreams, writeQueueMaxBytes, socketSendBufferBytes, queryTimeoutMillis,
			retentionSoftLimitBytes);
	}

	public WireServerOptions withMaxConcurrentStreams(int streams) {
		return with(host, port, streams, writeQueueMaxBytes, socketSendBufferBytes, queryTimeoutMillis,
			retentionSoftLimitBytes);
	}

	public WireServerOptions withWriteQueueMaxBytes(int bytes) {
		return with(host, port, maxConcurrentStreams, bytes, socketSendBufferBytes, queryTimeoutMillis,
			retentionSoftLimitBytes);
	}

	public WireServerOptions withSocketSendBufferBytes(int bytes) {
		return with(host, port, maxConcurrentStreams, writeQueueMaxBytes, bytes, queryTimeoutMillis,
			retentionSoftLimitBytes);
	}

	public WireServerOptions withQueryTimeoutMillis(long millis) {
		return with(host, port, maxConcurrentStreams, writeQueueMaxBytes, socketSendBufferBytes, millis,
			retentionSoftLimitBytes);
	}

	public WireServerOptions withRetentionSoftLimitBytes(long bytes) {
		return with(host, port, maxConcurrentStreams, writeQueueMaxBytes, socketSendBufferBytes, queryTimeoutMillis,
			bytes);
	}

	/** One place the unchanged fields are carried, so adding a component cannot silently drop one of them. */
	private WireServerOptions with(String host, int port, int maxConcurrentStreams, int writeQueueMaxBytes,
		int socketSendBufferBytes, long queryTimeoutMillis, long retentionSoftLimitBytes) {
		return new WireServerOptions(host, port, maxConcurrentStreams, writeQueueMaxBytes, socketSendBufferBytes,
			rowBatchBytes, flushIntervalMillis, queryTimeoutMillis, retentionSoftLimitBytes, watchdogIntervalMillis);
	}
}
