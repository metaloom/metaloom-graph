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
 * crash loses nothing that was reported as committed.
 * <p>
 * <b>Isolation.</b> Writes are serialised — one writer at a time — and a writer always reads the current state, so
 * lost updates and write skew cannot occur and writes are effectively serializable. Reads are concurrent with writing
 * and with each other. A read on its own sees the latest commit and never half of one; {@link #beginRead()} fixes a
 * view across many reads at {@link IsolationLevel#SNAPSHOT}, which is what a long traversal or a consistent export
 * needs.
 * <p>
 * <b>Not present:</b> concurrent writers. The single writer is what makes the above true so cheaply, and the version
 * machinery that would replace it is described in spec/TRANSACTIONS.md.
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

	/**
	 * Opens a {@link IsolationLevel#SNAPSHOT} read view: every read on this thread sees the store as it was at this
	 * instant until the view is closed, however many commits land underneath.
	 *
	 * <p>
	 * Readers do not block the writer and the writer does not block readers. The cost is memory — see
	 * {@link #snapshotStats()} — and it is only paid while a view is open.
	 * </p>
	 */
	ReadTransaction beginRead();

	ReadTransaction beginRead(IsolationLevel level);

	/**
	 * Whether this thread already has a read view open.
	 * <p>
	 * Views are thread-confined and {@link #beginRead()} refuses to nest, so a library that wants to run inside a
	 * view but must also work outside one needs to be able to ask rather than to find out by being refused.
	 */
	boolean hasReadTransaction();

	/**
	 * Runs the work in a write transaction, committing it, and retrying up to {@code attempts} times if the store
	 * reports contention.
	 *
	 * <p>
	 * Failures that repeating cannot fix — a constraint violation, an abandoned snapshot — are raised immediately
	 * rather than retried into the same answer.
	 * </p>
	 */
	<T> T inTransaction(TransactionalWork<T> work, int attempts) throws IOException;

	default <T> T inTransaction(TransactionalWork<T> work) throws IOException {
		return inTransaction(work, 3);
	}

	/** Work performed inside a transaction opened by {@link #inTransaction}. */
	@FunctionalInterface
	interface TransactionalWork<T> {
		T run() throws IOException;
	}

	// ---------------------------------------------------------------- nodes

	long createNode(String label) throws IOException;

	long createNode(String label, Properties properties) throws IOException;

	/**
	 * Creates a node carrying several labels, in the order given.
	 * <p>
	 * Duplicates are dropped rather than refused. Three labels live in the node record itself; beyond that the set
	 * spills into a property block, so a node with many labels costs one extra read to enumerate them and nothing to
	 * find by any one of them — the label index carries an entry per label either way.
	 */
	long createNode(List<String> labels) throws IOException;

	long createNode(List<String> labels, Properties properties) throws IOException;

	boolean nodeExists(long nodeId);

	/** The node's first label, or null when it has none. Equivalent to the head of {@link #nodeLabels}. */
	String nodeLabel(long nodeId);

	/** Every label the node carries, in creation order. */
	List<String> nodeLabels(long nodeId);

	boolean nodeHasLabel(long nodeId, String label);

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

	/**
	 * Calls the consumer with the id of every live node.
	 *
	 * <p>
	 * A callback rather than a list: this is the operation a query language reaches for when it has no label and no
	 * indexed property to start from, and materialising eight bytes per live record to hand it back would make the
	 * unindexed case the one that runs out of memory. It runs inside the caller's read view, so a scan sees one
	 * consistent state however long it takes.
	 * </p>
	 */
	void forEachNode(java.util.function.LongConsumer consumer);

	/** Every node carrying the label, via the label index. */
	List<Long> nodesByLabel(String label);

	/** Every node whose property equals the value, via the property index plus verification. */
	List<Long> nodesByProperty(String key, Object value);

	/**
	 * Declares a property key unique across live nodes: a later write carrying a value another node already holds is
	 * refused with {@link ConstraintViolationException}.
	 * <p>
	 * The existing data is checked first, so declaring a constraint that is already violated fails rather than
	 * quietly recording something untrue. The declaration is durable and survives a reopen.
	 */
	void createUniqueConstraint(String key) throws IOException;

	/** Property keys currently declared unique. */
	List<String> uniqueConstraints();

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
	 * <p>
	 * <b>Refused while any {@link ReadTransaction} is open.</b> A punched hole reads back as zeros, which a view
	 * entitled to the old content cannot distinguish from data — so this fails loudly rather than serving one a page
	 * of nothing.
	 * </p>
	 *
	 * @return the number of pages reclaimed
	 * @throws GraphStoreException
	 *             if a read view is open
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
	 *
	 * <p>
	 * <b>Refused while any {@link ReadTransaction} is open</b>, because retiring an emptied segment unlinks the file a
	 * view may still be reading.
	 *
	 * @throws GraphStoreException
	 *             if a read view is open
	 */
	CompactionReport compact() throws IOException;

	CompactionReport compact(CompactionOptions options) throws IOException;

	StoreStats stats();

	/** What open read views currently cost. Every field is zero when none is open. */
	SnapshotStats snapshotStats();

	/**
	 * Counters and gauges: log volume and lag, fsyncs, record reads and writes per kind, checkpoint and recovery
	 * timings, pages punched, records relocated. Subtract two snapshots to cost an interval.
	 */
	StoreMetrics metrics();

	@Override
	void close() throws IOException;
}
