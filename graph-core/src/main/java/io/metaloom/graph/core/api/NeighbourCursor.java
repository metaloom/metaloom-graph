package io.metaloom.graph.core.api;

/**
 * A node's edges, one at a time, read as the caller asks for them.
 *
 * <p>
 * This is {@link GraphStore#neighbours(long, Direction)} without the list. That method decodes every block of the
 * chain and builds an {@link Edge} for every entry before the caller sees the first one, so a query that wants one row
 * out of a node of degree 10^6 pays 8 MB of {@code long[]} and a million objects to throw away all but one of them.
 * Here the head block is decoded, the first admitted entry is resolved, and nothing else is touched until
 * {@link #next()} is called again.
 * </p>
 *
 * <p>
 * The saving is real only where the caller can stop. A cursor drained to the end costs what the list costs, minus the
 * list — which is why {@code neighbours} is written as a drain of one rather than replaced by one.
 * </p>
 *
 * <h2>It holds a read view, so it has to be closed</h2>
 *
 * <p>
 * A list is built inside one call and can be built optimistically — {@code GraphStoreImpl.read} runs its body without
 * a lock and re-runs it if a commit landed underneath. A cursor outlives its call and cannot be re-run, so it takes
 * the read latch outright and keeps it until {@link #close()}, the same trade {@code forEachNodeWhile} makes and for
 * the same reason. Two consequences the caller owns:
 * </p>
 *
 * <ul>
 * <li><b>Close it.</b> Use it in a try-with-resources. An abandoned cursor holds the latch that a commit's apply step
 * has to exclude readers from, so it stalls every writer, not only the thread that leaked it.</li>
 * <li><b>Do not write on this thread while one is open.</b> The latch is not reentrant with the write side; a
 * mutation attempted from inside a cursor loop deadlocks against the cursor's own thread. Collect the ids and write
 * after the cursor is closed. A cursor opened <em>inside</em> a write transaction is exempt: the transaction's overlay
 * is already a stable view, so no latch is taken and none is released.</li>
 * </ul>
 *
 * <p>
 * The edges arrive in storage order — the two inline entries of the node record first, then the block chain from its
 * head — which is the order the list form returns them in. It is not a meaningful order and no caller should depend
 * on it beyond that equivalence.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> section 5.4
 */
public interface NeighbourCursor extends AutoCloseable {

	/**
	 * Advances to the next edge, decoding a block only when the entries of the previous one have run out.
	 *
	 * @return false once the node's adjacency is exhausted, after which {@link #edge()} has nothing to return
	 */
	boolean next();

	/**
	 * The edge {@link #next()} stopped on.
	 *
	 * @throws IllegalStateException
	 *             if {@code next()} has not been called, or returned false
	 */
	Edge edge();

	/** Releases the read view. Idempotent, so a caller may close it twice without checking. */
	@Override
	void close();
}
