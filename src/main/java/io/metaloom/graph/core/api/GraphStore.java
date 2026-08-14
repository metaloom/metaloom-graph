package io.metaloom.graph.core.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The public store API.
 * <p>
 * Ids are plain {@code long} values that decode arithmetically into a file and a byte offset, so reading an element by
 * id costs no index probe and no extra seek.
 * <p>
 * <b>Durability.</b> Every commit is written to the write-ahead log and fsynced before it touches a data file, so a
 * crash loses nothing that was reported as committed. Isolation is single-writer serialisable: one writer at a time,
 * many concurrent readers, and a reader never observes half of a commit. Snapshot isolation with concurrent writers
 * needs the MVCC version chains of spec/TRANSACTIONS.md and is not implemented.
 *
 * @see <a href="../../../../../../../../spec/API.md">spec/API.md</a>
 */
public interface GraphStore extends AutoCloseable {

	static GraphStore open(Path path) throws IOException {
		return open(path, GraphStoreOptions.defaults());
	}

	static GraphStore open(Path path, GraphStoreOptions options) throws IOException {
		return io.metaloom.graph.core.store.GraphStoreImpl.open(path, options);
	}

	// ---------------------------------------------------------------- transactions

	/**
	 * Starts a write transaction. Only one may be active at a time; other writers block. Every mutating method below
	 * joins the caller's transaction if there is one, and otherwise runs in its own auto-commit transaction.
	 */
	Transaction begin();

	// ---------------------------------------------------------------- nodes

	long createNode(String label) throws IOException;

	long createNode(String label, Properties properties) throws IOException;

	boolean nodeExists(long nodeId);

	String nodeLabel(long nodeId);

	Properties nodeProperties(long nodeId);

	Object nodeProperty(long nodeId, String key);

	void setNodeProperties(long nodeId, Properties properties) throws IOException;

	/** Deletes a node, its properties, its adjacency and every relationship attached to it. */
	void deleteNode(long nodeId) throws IOException;

	int degreeOut(long nodeId);

	int degreeIn(long nodeId);

	// ---------------------------------------------------------------- edges

	/**
	 * Creates a property-free edge. It costs 16 bytes in total and has no external id; use
	 * {@link #createRelationship} when the edge needs properties or must be referenced later.
	 */
	void createEdge(long fromId, String type, long toId) throws IOException;

	long createRelationship(long fromId, String type, long toId) throws IOException;

	long createRelationship(long fromId, String type, long toId, Properties properties) throws IOException;

	boolean relationshipExists(long relationshipId);

	String relationshipType(long relationshipId);

	long relationshipFrom(long relationshipId);

	long relationshipTo(long relationshipId);

	Properties relationshipProperties(long relationshipId);

	void setRelationshipProperties(long relationshipId, Properties properties) throws IOException;

	void deleteRelationship(long relationshipId) throws IOException;

	/** Removes a lightweight edge. Returns false when no such edge exists. */
	boolean deleteEdge(long fromId, String type, long toId) throws IOException;

	// ---------------------------------------------------------------- traversal

	List<Edge> neighbours(long nodeId, Direction direction);

	List<Edge> neighbours(long nodeId, Direction direction, Set<String> types);

	/** Breadth-first expansion up to {@code maxDepth} hops. The start node is not included. */
	List<Edge> traverse(long startNodeId, Direction direction, Set<String> types, int maxDepth);

	// ---------------------------------------------------------------- indexed lookup

	/** Every node carrying the label, via the label index. */
	List<Long> nodesByLabel(String label);

	/** Every node whose property equals the value, via the property index plus verification. */
	List<Long> nodesByProperty(String key, Object value);

	// ---------------------------------------------------------------- maintenance

	/** Flushes everything to disk. Until the log lands this is the only durability point. */
	void checkpoint() throws IOException;

	/** Verifies page checksums and slot accounting across every segment. */
	void verify();

	/**
	 * Verifies at the requested depth. {@link VerifyLevel#FULL} also checks bidirectional adjacency, degree counters,
	 * dangling references, chain termination and orphaned blocks, and is safe to run on a live store — use
	 * {@link #verify(VerifyLevel, VerifyOptions)} with {@link VerifyOptions#throttled()} so it does not stall one.
	 *
	 * @throws GraphStoreException
	 *             listing every violation found
	 */
	void verify(VerifyLevel level);

	/**
	 * Verifies at the requested depth, yielding as the options ask so a long scan does not stall a live store.
	 *
	 * @throws GraphStoreException
	 *             listing every violation found, and saying so explicitly if the scan was cut short
	 */
	void verify(VerifyLevel level, VerifyOptions options);

	/**
	 * Returns the blocks of every all-free page to the filesystem, without moving anything and without breaking a
	 * single id. Requires the native layer; a no-op without it.
	 *
	 * @return the number of pages reclaimed
	 */
	int reclaim() throws IOException;

	/**
	 * Compacts the store with {@link CompactionOptions#defaults()}: kinds whose live records have fallen below half of
	 * their high water marks are made dense again.
	 *
	 * <p>
	 * <b>This is the one operation that invalidates ids.</b> Every id the caller is holding across the call may name a
	 * different record, or nothing, afterwards; a stale one is detected and rejected rather than misresolved, because
	 * the generation of a freed slot is advanced. Nothing stored inside the engine is affected — every internal
	 * reference is rewritten in the same transaction as the move.
	 *
	 * <p>
	 * Prefer {@link #reclaim()}, which returns space without moving anything, and run this only when free space has
	 * become scattered <em>within</em> pages.
	 */
	CompactionReport compact() throws IOException;

	CompactionReport compact(CompactionOptions options) throws IOException;

	StoreStats stats();

	/**
	 * Counters and gauges: log volume and lag, fsyncs, record reads and writes per kind, checkpoint and recovery
	 * timings, pages punched, records relocated. Subtract two snapshots to cost an interval.
	 */
	StoreMetrics metrics();

	@Override
	void close() throws IOException;
}
