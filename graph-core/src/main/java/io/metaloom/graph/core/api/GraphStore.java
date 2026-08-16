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
	 * Whether this thread already holds a write transaction.
	 *
	 * <p>
	 * Unlike {@link #beginRead()}, {@link #begin()} does <b>not</b> refuse to nest, and a nested transaction is not a
	 * caught mistake but a silent one: the inner transaction publishes its own page overlay, and committing it clears
	 * the overlay while the outer transaction is still live, after which the outer transaction's writes go straight to
	 * the mapping — unlogged, and past the point where a rollback could take them back.
	 * </p>
	 *
	 * <p>
	 * So a library that opens a transaction on the caller's behalf has to be able to ask whether it should. Mutating
	 * methods join the caller's transaction on their own; this is for a caller that needs to know whether to begin one
	 * and therefore whether it owns the commit.
	 * </p>
	 */
	boolean hasWriteTransaction();

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

	/**
	 * Adds a label to a node that already exists, returning false when it already carried it.
	 *
	 * <p>
	 * The label set is stored in the node record's three inline slots and spills into a property block beyond that, so
	 * crossing the boundary in either direction allocates or frees that block. The label index carries an entry per
	 * label, and only the difference is rewritten.
	 * </p>
	 *
	 * <p>
	 * A duplicate is dropped rather than refused, matching {@link #createNode(List)}: {@code :Person} added twice is a
	 * caller's redundancy, and storing it twice would put two index entries on one node and leave one behind on delete.
	 * </p>
	 */
	boolean addNodeLabel(long nodeId, String label) throws IOException;

	/**
	 * Removes a label from a node, returning false when it did not carry it.
	 *
	 * <p>
	 * A node may end up with no labels at all; that is a legal node and {@link #nodeLabels} returns an empty list for
	 * it. Removing the last label does not delete the node.
	 * </p>
	 */
	boolean removeNodeLabel(long nodeId, String label) throws IOException;

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

	/**
	 * The same edges, streamed: nothing is decoded until it is asked for, and a caller that stops early pays only for
	 * what it read.
	 *
	 * <p>
	 * This is to {@link #neighbours(long, Direction, Set)} what {@link #forEachNodeWhile} is to a full scan, one level
	 * down. A node of degree 10^6 costs an 8 MB {@code long[]} and a million {@link Edge} objects to enumerate, and a
	 * query wanting one row pays all of it; through a cursor the same query decodes the head block and stops.
	 * </p>
	 *
	 * <p>
	 * <b>The cursor holds a read view and must be closed</b> — use it in a try-with-resources, and do not mutate the
	 * store from the thread holding one. {@link NeighbourCursor} says what that costs and why the list form does not
	 * pay it.
	 * </p>
	 *
	 * @param types
	 *            relationship types to admit, or null / empty for every type
	 */
	NeighbourCursor neighbourCursor(long nodeId, Direction direction, Set<String> types);

	/**
	 * Whether any edge connects the two nodes, in either direction and of any type.
	 * <p>
	 * Cheaper than {@link #neighbours(long, Direction)} on a dense node: it stops at the first match and walks past
	 * the blocks whose recorded location range cannot hold the target without decoding them. It is a pruned scan of
	 * the block chain, not an index lookup — see RELATIONSHIP_STORAGE.md §5.3.
	 */
	boolean hasEdge(long nodeId, long neighbourId);

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

	/**
	 * The same scan, stopping as soon as the predicate returns false.
	 *
	 * <p>
	 * This is what makes {@code LIMIT} cost what a reader expects. Without it a query wanting ten rows out of ten
	 * million nodes walks all ten million — the scan has no way to be told it is finished, and the limit can only be
	 * applied to a result that has already been built.
	 * </p>
	 *
	 * <p>
	 * A predicate rather than an exception thrown from the consumer, deliberately: {@code GraphStoreImpl}'s optimistic
	 * read path catches {@code RuntimeException} and re-runs the operation, so control flow by exception would turn an
	 * early stop into a repeat of the scan.
	 * </p>
	 *
	 * <p>
	 * A different name rather than an overload: a lambda whose body is an expression returning {@code boolean} —
	 * {@code id -> ids.add(id)} is the obvious one — matches {@code LongConsumer} and {@code LongPredicate} equally,
	 * and every existing caller would have become ambiguous at the call site.
	 * </p>
	 *
	 * @return false if the predicate stopped the scan, true if every live node was visited
	 */
	boolean forEachNodeWhile(java.util.function.LongPredicate consumer);

	/** Every node carrying the label, via the label index. */
	List<Long> nodesByLabel(String label);

	/** Every node whose property equals the value, via the property index plus verification. */
	List<Long> nodesByProperty(String key, Object value);

	/**
	 * Every node carrying the label <b>and</b> the property value.
	 *
	 * <p>
	 * The answer does not depend on whether an index over the pair has been declared; only its cost does. With a
	 * declaration this is one probe of the composite tree; without one it is a property probe followed by a label
	 * verification per candidate, which for a low-selectivity property means reading every node that holds the value.
	 * {@link #hasIndex} is how a planner tells the two apart before choosing.
	 * </p>
	 */
	List<Long> nodesByLabelAndProperty(String label, String key, Object value);

	/**
	 * Declares an index over a label and a property key, and backfills it.
	 *
	 * <p>
	 * The label and property indexes maintain themselves for everything; this one has to be asked for, because there
	 * are {@code labels x keys} possible pairs and maintaining them all would multiply the write cost of every node by
	 * the size of its label set. Declared, it makes {@code MATCH (n:Person {email: ...})} one probe instead of a
	 * property probe plus a label verification — which matters most for a property that is <em>not</em> selective:
	 * {@code {active: true}} over ten million nodes returns five million candidates to verify, and the composite probe
	 * returns only the labelled ones.
	 * </p>
	 *
	 * <p>
	 * Every live node carrying the label and the key is indexed before the call returns, in the same transaction, so
	 * the index is complete the moment it exists. Re-declaring an existing pair does nothing.
	 * </p>
	 */
	void createIndex(String label, String key) throws IOException;

	/** Whether an index over this label and key has been declared. */
	boolean hasIndex(String label, String key);

	/** Every declared composite index, in declaration order. */
	List<CompositeIndex> compositeIndexes();

	/**
	 * Declares a property key unique across live nodes: a later write carrying a value another node already holds is
	 * refused with {@link ConstraintViolationException}.
	 * <p>
	 * The existing data is checked first, so declaring a constraint that is already violated fails rather than
	 * quietly recording something untrue. The declaration is durable and survives a reopen.
	 */
	void createUniqueConstraint(String key) throws IOException;

	/**
	 * Declares a property key unique <b>among the nodes carrying one label</b>: "no two {@code :Person} share an
	 * email", which is what an application usually means and what {@link #createUniqueConstraint(String)} cannot say.
	 *
	 * <p>
	 * A label-scoped constraint is a declared composite index plus a flag, so declaring one also gives the pair its
	 * index — {@link #hasIndex} reports true afterwards and {@link #compositeIndexes} lists it as unique. As with the
	 * store-wide form, the existing data is checked first and the declaration refused rather than recorded untrue; the
	 * scan covers only the nodes carrying the label.
	 * </p>
	 *
	 * <p>
	 * It is enforced on a label change as well as on a property write: adding the label to a node that already holds a
	 * value another labelled node has is refused, and refused before anything is written.
	 * </p>
	 */
	void createUniqueConstraint(String label, String key) throws IOException;

	/** Property keys currently declared unique across every node. Label-scoped ones are in {@link #compositeIndexes}. */
	List<String> uniqueConstraints();

	/**
	 * The {@code k} nodes whose vector property is nearest the query, <b>found by scanning</b>.
	 *
	 * <p>
	 * Exact, and linear in the number of candidates: it reads every candidate's vector and compares it. There is no
	 * approximation and no index — which is the point. This is the answer an approximate index is measured against,
	 * so it must be computable whether or not one exists, and it must not share code with one.
	 * </p>
	 *
	 * <p>
	 * {@code label} selects the candidates through the label index; passing null scans every live node instead. Nodes
	 * that do not carry the key, or whose value is not a vector, are not candidates. <b>A vector of a different length
	 * is skipped rather than refused</b>: a property key is free-form, so one node holding a 512-dimension value is a
	 * fact about that node and not a reason to fail everybody else's query. A declared index will refuse the write
	 * instead, which is the right place for that refusal.
	 * </p>
	 *
	 * <p>
	 * Equal distances break on the lower node id, so the answer is the same on every run over the same data. That is
	 * not cosmetic: a corpus with any repeated vector produces ties, and an oracle that reorders them between runs
	 * cannot be compared against anything.
	 * </p>
	 *
	 * @return at most {@code k} neighbours, <b>nearest first</b> under the metric — see {@link Neighbour#score} for why
	 *         the order rather than the number is the answer
	 */
	List<Neighbour> nearestByScan(String label, String key, float[] query, int k, VectorMetric metric);

	/**
	 * Declares an approximate-nearest-neighbour index over a label's vector property.
	 *
	 * <p>
	 * Like {@link #createUniqueConstraint}, the existing data is walked first — every node carrying the label is
	 * checked and, if it holds a vector of the declared dimension, made a member. A node whose vector is a different
	 * length is refused rather than skipped: the whole point of pinning the dimension is that a member set of mixed
	 * lengths is not an index, and silently leaving some nodes out would make the index answer confidently with a
	 * subset.
	 * </p>
	 *
	 * <p>
	 * The store must have been created with vector storage of the same dimension
	 * ({@link GraphStoreOptions.Builder#vectorDimensions}); the fixed-size records are what the index is built over.
	 * </p>
	 */
	void createVectorIndex(VectorIndexDefinition definition) throws IOException;

	/**
	 * Links every member that is not linked yet, in batches, and marks the index ready.
	 *
	 * <p>
	 * Called by {@link #createVectorIndex} and safe to call again: a member already in the structure is skipped, so an
	 * interrupted build resumes rather than restarting. The marker is per record rather than a position in a list —
	 * ids are not ordered by insertion once a compaction has run, so a watermark would resume in the wrong place.
	 * </p>
	 */
	void buildVectorIndex(String label, String key) throws IOException;

	/** Drops a declaration. The vectors themselves are properties and are untouched. */
	void dropVectorIndex(String label, String key) throws IOException;

	/** Every declared vector index, in declaration order. */
	List<VectorIndexDefinition> vectorIndexes();

	/**
	 * The {@code k} nearest members of a declared index.
	 *
	 * <p>
	 * Answers from the index's own member set rather than from a scan of the store, and applies {@code filter} — when
	 * one is given — to each candidate before admitting it. That filter is the reason this lives inside the graph
	 * engine at all: an external vector index knows nothing about the graph, so it has to over-fetch and let the
	 * caller filter afterwards, with no way to know how far to over-fetch.
	 * </p>
	 *
	 * @param filter
	 *            an extra condition on the node, or null for none. Applied when admitting a result
	 * @throws IllegalStateException
	 *             if no such index is declared, or it is still building. "No index" and "no matches" are opposite
	 *             answers and must never look the same
	 */
	List<Neighbour> nearest(String label, String key, float[] query, int k,
		java.util.function.LongPredicate filter) throws IOException;

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
	 * <p>
	 * <b>Ids deleted before the call stay stale afterwards</b>, which is a stronger promise than "no id breaks" and
	 * the one that actually matters. A punched page reads as zeros including the generation counter inside each
	 * record, so the slots would restart at generation 1 and hand out ids identical to deleted ones — resolving to
	 * the new records with no error rather than failing as stale. The counters are saved into the page metadata
	 * array, which is never punched, before the punch destroys them.
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
