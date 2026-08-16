package io.metaloom.graph.cypher.exec;

/**
 * One stage of the push pipeline.
 *
 * <p>
 * Push rather than pull, and the reason is the source rather than a preference. The unindexed seed is
 * {@code GraphStore.forEachNode}, a callback: inverting it into an iterator needs either a thread to run it on, or
 * eight bytes per live record materialised up front — which is precisely the thing that would make the unindexed case
 * the one that runs out of memory. A chain of {@code Row -> boolean} consumers composes with a callback for nothing,
 * and its terminal stage is the {@link io.metaloom.graph.cypher.RowSink} the caller already wanted.
 * </p>
 *
 * <p>
 * <b>Returning false must propagate all the way to the source.</b> That is the whole point: a {@code LIMIT} satisfied
 * three stages downstream has to reach the bitmap walk, or the limit is applied to a scan that already ran.
 * </p>
 */
@FunctionalInterface
public interface RowConsumer {

	/** @return false to stop producing. Every stage above must return false in turn, up to the scan itself */
	boolean accept(Row row);
}
