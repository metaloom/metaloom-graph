package io.metaloom.graph.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;

import io.metaloom.graph.protocol.WireFormatException;
import io.metaloom.graph.protocol.QuerySummary;
import io.metaloom.graph.protocol.WireValueCodec;

/**
 * The rows of one query, as they arrive.
 *
 * <pre>{@code
 * try (RowStream rows = client.query("MATCH (a:Airport) RETURN a.code, a.name")) {
 *     for (Row row : rows) {
 *         System.out.println(row.string("a.code"));
 *     }
 * }
 * }</pre>
 *
 * <h2>What is and is not bounded</h2>
 *
 * <p>
 * <b>The client never holds the whole result.</b> Rows arrive in batches, are decoded on the consuming thread, and are
 * discarded as they are read; a query returning ten million rows costs the client one batch of memory at a time. When
 * the consumer falls behind, the queued bytes cross {@link ClientOptions#readBufferBytes} and the client stops reading
 * the socket, which closes the receive window, which is what the server discovers as backpressure.
 * </p>
 *
 * <p>
 * The <em>server</em> is a different question, and the honest answer today is that it still materialises the executor's
 * intermediate result before streaming it. What this bounds is the encoded payload, the client's memory, and the time
 * to the first row. See spec/WIRE.md.
 * </p>
 *
 * <h2>Reading slowly is fine; reading two streams at different speeds is not</h2>
 *
 * <p>
 * One connection multiplexes many streams, but a plain TCP socket has one flow-control point for all of them. So a
 * consumer that stalls on stream A stalls stream B on the same connection too. That is the cost of framing over TCP
 * rather than HTTP/2, it was known when the transport was chosen, and the answer is a second connection rather than a
 * cleverer client — see the transport decision in spec/WIRE.md.
 * </p>
 *
 * <h2>Errors</h2>
 *
 * <p>
 * A {@link QueryFailedException} is thrown at exactly the row where the error arrived, because the rows before it are
 * not a result — they are the first part of an answer that was never finished. {@link #close()} throws one that nothing
 * else observed, so a caller that stops reading early still finds out.
 * </p>
 *
 * <p>
 * Not thread-safe. One stream is consumed by one thread; several streams may be consumed by several threads.
 * </p>
 */
public final class RowStream implements AutoCloseable, Iterable<Row> {

	private final GraphClient client;
	private final int streamId;

	/** Filled by the event loop, read by the consumer. */
	private final CompletableFuture<List<String>> columnsArrived = new CompletableFuture<>();
	private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

	// ------------------------------------------------ consumer-thread state, touched by nothing else

	private List<String> columns;
	private WireValueCodec.Reader batch;
	private int rowsLeftInBatch;
	private boolean finished;
	private long rowsRead;
	private QueryFailedException pending;
	private boolean pendingObserved;
	private QuerySummary summary;
	private boolean closed;

	/**
	 * Set by {@link #close()} before it empties the queue, read by the event loop after it adds a batch.
	 *
	 * <p>
	 * The event loop looks a stream up, counts a batch against the connection's budget, and then queues it — and the
	 * consumer can close the stream between the lookup and the queueing. Close released only what it found in the
	 * queue, so the batch landing behind it stayed counted for ever. Each was up to a batch of budget gone, and once the
	 * leaked total sat above half of {@link ClientOptions#readBufferBytes} the next pause had nothing to lift it: a
	 * connection that hung on its next large result. With this flag one side or the other always sees the batch — the
	 * consumer in the queue, or the event loop in the flag — and whichever does gives it back.
	 * </p>
	 */
	private volatile boolean discarded;

	RowStream(GraphClient client, int streamId) {
		this.client = client;
		this.streamId = streamId;
	}

	/** The column names, in projection order. Blocks until the server has sent them, which it does before any row. */
	public List<String> columns() {
		if (columns == null) {
			if (closed && !columnsArrived.isDone()) {
				// Nothing will complete it: a closed stream is unregistered, so its COLUMNS frame is dropped on arrival.
				throw closedBeforeFinishing();
			}
			try {
				columns = columnsArrived.join();
			} catch (CompletionException e) {
				RuntimeException failure = unwrap(e);
				if (failure instanceof QueryFailedException reported) {
					// Reported here, so close() must not report it a second time. A syntax error thrown once out of
					// columns() and again out of the try-with-resources reads as two failures.
					pending = reported;
					pendingObserved = true;
				}
				throw failure;
			}
		}
		return columns;
	}

	/**
	 * @return false when the result is finished
	 * @throws QueryFailedException
	 *             at the row where the server's error arrived
	 */
	public boolean hasNext() {
		while (true) {
			if (rowsLeftInBatch > 0) {
				return true;
			}
			if (finished) {
				report();
				return false;
			}
			if (closed) {
				throw closedBeforeFinishing();
			}
			take();
		}
	}

	public Row next() {
		if (!hasNext()) {
			throw new NoSuchElementException("The result has " + rowsRead + " rows and they have all been read");
		}
		List<String> names = columns();
		List<Object> values = new ArrayList<>(names.size());
		try {
			for (int i = 0; i < names.size(); i++) {
				values.add(WireValueCodec.decode(batch));
			}
		} catch (WireFormatException e) {
			// The server sent a row batch this client cannot read. Nothing after it can be trusted either, so the
			// stream ends here rather than skipping ahead and returning a row that is quietly wrong.
			finished = true;
			rowsLeftInBatch = 0;
			throw new GraphClientException("Row " + rowsRead + " of stream " + streamId + " did not decode", e);
		}
		rowsLeftInBatch--;
		rowsRead++;
		return new Row(names, values);
	}

	/** How many rows have been handed out so far. */
	public long rowsRead() {
		return rowsRead;
	}

	/**
	 * What the query did, once it has finished.
	 *
	 * <p>
	 * Blocks until the server's terminal frame arrives, draining any rows still to come — so calling it before reading
	 * the rows discards them. For a write query with no RETURN this is the entire result: there are no rows, and
	 * nothing else says that three nodes were created.
	 * </p>
	 *
	 * @throws QueryFailedException
	 *             if the query failed, because there is no summary of a query that did not finish
	 */
	public QuerySummary summary() {
		while (!finished) {
			if (closed) {
				throw closedBeforeFinishing();
			}
			take();
		}
		report();
		return summary;
	}

	@Override
	public Iterator<Row> iterator() {
		return new Iterator<>() {

			@Override
			public boolean hasNext() {
				return RowStream.this.hasNext();
			}

			@Override
			public Row next() {
				return RowStream.this.next();
			}
		};
	}

	/**
	 * Stops the query if it is still running, and reports an error nothing else has.
	 *
	 * <p>
	 * Cancelling is a frame to the server, not a disconnect: the server stops producing, releases its worker and closes
	 * its read view, and then sends a terminal frame of its own. Until that arrives the stream id stays reserved, so a
	 * later query cannot be handed frames belonging to this one.
	 * </p>
	 */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (!finished) {
			// Look for a terminal that arrived while the caller was not asking. Non-blocking: a caller who stopped
			// reading must not be made to wait here.
			drainWithoutBlocking();
		}
		if (!finished) {
			client.cancel(streamId);
		}
		// Rows still in the current batch go with the stream: asking for them after close() is the same mistake as
		// asking for rows that had not arrived yet, and is answered the same way.
		rowsLeftInBatch = 0;
		batch = null;
		// Before the queue is emptied, so a batch the event loop queues behind the emptying is given back by it.
		discarded = true;
		client.discard(streamId, releaseQueued());
		reportIfUnobserved();
	}

	/**
	 * The answer to reading a stream that was closed before its result finished.
	 *
	 * <p>
	 * An exception rather than a wait. A closed stream is unregistered, so nothing the server sends for it — rows or
	 * terminal — reaches it again, and waiting used to mean parking in {@code take()} for ever.
	 * </p>
	 */
	private IllegalStateException closedBeforeFinishing() {
		return new IllegalStateException("Stream " + streamId + " was closed after " + rowsRead
			+ " rows, before its result finished");
	}

	// ---------------------------------------------------------------- consumer plumbing

	private void take() {
		Object item;
		try {
			item = queue.take();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GraphClientException("Interrupted while waiting for rows of stream " + streamId, e);
		}
		accept(item);
	}

	private void drainWithoutBlocking() {
		Object item;
		while (!finished && (item = queue.poll()) != null) {
			accept(item);
		}
	}

	private void accept(Object item) {
		if (item instanceof Batch b) {
			batch = new WireValueCodec.Reader(b.payload());
			rowsLeftInBatch = batch.i32();
			client.consumed(b.payload().length);
		} else {
			Terminal terminal = (Terminal) item;
			finished = true;
			rowsLeftInBatch = 0;
			pending = terminal.error();
			summary = terminal.summary();
		}
	}

	/** @return the bytes still sitting unread, so the connection's budget gets them back */
	private long releaseQueued() {
		long released = 0;
		Object item;
		while ((item = queue.poll()) != null) {
			if (item instanceof Batch b) {
				released += b.payload().length;
			}
		}
		return released;
	}

	/**
	 * Throws the server's error, every time it is asked.
	 *
	 * <p>
	 * Every time, not once: a caller that catches it and keeps iterating must not then be told the result simply
	 * ended. What happens once is {@link #close()} reporting it, and only when nothing else has.
	 * </p>
	 */
	private void report() {
		if (pending != null) {
			pendingObserved = true;
			throw pending;
		}
	}

	/** The backstop: an error nobody asked about still has to reach the caller. */
	private void reportIfUnobserved() {
		if (pending != null && !pendingObserved) {
			pendingObserved = true;
			throw pending;
		}
	}

	private static RuntimeException unwrap(CompletionException e) {
		return e.getCause() instanceof RuntimeException runtime
			? runtime
			: new GraphClientException("The query failed", e.getCause());
	}

	// ---------------------------------------------------------------- event-loop callbacks

	private record Batch(byte[] payload) {
	}

	/** A null error means the query completed, and then the summary is the server's. */
	private record Terminal(QueryFailedException error, QuerySummary summary) {
	}

	void onColumns(List<String> names) {
		columnsArrived.complete(names);
	}

	void onRows(byte[] payload) {
		queue.add(new Batch(payload));
		if (discarded) {
			// Closed while this batch was on its way in: the consumer has already emptied the queue and gone, so this
			// side gives the bytes back. See discarded for why both sides look.
			long released = releaseQueued();
			if (released > 0) {
				client.consumed(released);
			}
		}
	}

	void onComplete(QuerySummary summary) {
		columnsArrived.complete(List.of());
		queue.add(new Terminal(null, summary));
	}

	void onError(QueryFailedException error) {
		// A syntax error arrives before any COLUMNS frame, so a caller blocked in columns() has to be woken by the
		// error rather than left waiting for a header that is never coming.
		columnsArrived.completeExceptionally(error);
		queue.add(new Terminal(error, null));
	}

	int streamId() {
		return streamId;
	}
}
