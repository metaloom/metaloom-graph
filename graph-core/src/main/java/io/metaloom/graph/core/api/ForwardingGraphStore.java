package io.metaloom.graph.core.api;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/**
 * A {@link GraphStore} that forwards every call to another one: the base for a decorator — counting, tracing,
 * metrics, access checks — around a store.
 *
 * <p>
 * {@code GraphStore} is sealed, and this class is the one permitted way to implement it outside the engine. Extend it,
 * override what you want to observe or change, and call {@code super} (or {@link #delegate()}) for the rest; every
 * method you do not override is forwarded unchanged.
 * </p>
 *
 * <p>
 * <b>Why it exists.</b> A decorator written against the interface itself would stop compiling the day the interface
 * gained a method. This class is concrete, so the build refuses to compile it until every method of
 * {@code GraphStore} is forwarded here — which means a new method arrives with its forwarding implementation in the
 * same change, and a subclass keeps compiling and keeps working. That is also what lets the API lock count a new
 * {@code GraphStore} method as a compatible addition (spec/API_LOCK.md).
 * </p>
 *
 * <p>
 * Closing the decorator closes the store it wraps.
 * </p>
 */
public non-sealed class ForwardingGraphStore implements GraphStore {

	private final GraphStore delegate;

	public ForwardingGraphStore(GraphStore delegate) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
	}

	/** The store every call is forwarded to. */
	protected final GraphStore delegate() {
		return delegate;
	}

	@Override
	public boolean addNodeLabel(long nodeId, String label) throws IOException {
		return delegate.addNodeLabel(nodeId, label);
	}

	@Override
	public Transaction begin() {
		return delegate.begin();
	}

	@Override
	public Transaction begin(IsolationLevel level) {
		return delegate.begin(level);
	}

	@Override
	public ReadTransaction beginRead() {
		return delegate.beginRead();
	}

	@Override
	public ReadTransaction beginRead(IsolationLevel level) {
		return delegate.beginRead(level);
	}

	@Override
	public void buildVectorIndex(String label, String key) throws IOException {
		delegate.buildVectorIndex(label, key);
	}

	@Override
	public void checkpoint() throws IOException {
		delegate.checkpoint();
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}

	@Override
	public CompactionReport compact() throws IOException {
		return delegate.compact();
	}

	@Override
	public CompactionReport compact(CompactionOptions options) throws IOException {
		return delegate.compact(options);
	}

	@Override
	public List<CompositeIndex> compositeIndexes() {
		return delegate.compositeIndexes();
	}

	@Override
	public void createEdge(long fromId, String type, long toId) throws IOException {
		delegate.createEdge(fromId, type, toId);
	}

	@Override
	public void createIndex(String label, String key) throws IOException {
		delegate.createIndex(label, key);
	}

	@Override
	public long createNode(String label, Properties properties) throws IOException {
		return delegate.createNode(label, properties);
	}

	@Override
	public long createNode(String label) throws IOException {
		return delegate.createNode(label);
	}

	@Override
	public long createNode(List<String> labels, Properties properties) throws IOException {
		return delegate.createNode(labels, properties);
	}

	@Override
	public long createNode(List<String> labels) throws IOException {
		return delegate.createNode(labels);
	}

	@Override
	public long createRelationship(long fromId, String type, long toId, Properties properties) throws IOException {
		return delegate.createRelationship(fromId, type, toId, properties);
	}

	@Override
	public long createRelationship(long fromId, String type, long toId) throws IOException {
		return delegate.createRelationship(fromId, type, toId);
	}

	@Override
	public void createUniqueConstraint(String label, String key) throws IOException {
		delegate.createUniqueConstraint(label, key);
	}

	@Override
	public void createUniqueConstraint(String key) throws IOException {
		delegate.createUniqueConstraint(key);
	}

	@Override
	public void createVectorIndex(VectorIndexDefinition definition) throws IOException {
		delegate.createVectorIndex(definition);
	}

	@Override
	public int degreeIn(long nodeId) {
		return delegate.degreeIn(nodeId);
	}

	@Override
	public int degreeOut(long nodeId) {
		return delegate.degreeOut(nodeId);
	}

	@Override
	public boolean deleteEdge(long fromId, String type, long toId) throws IOException {
		return delegate.deleteEdge(fromId, type, toId);
	}

	@Override
	public void deleteNode(long nodeId) throws IOException {
		delegate.deleteNode(nodeId);
	}

	@Override
	public void deleteRelationship(long relationshipId) throws IOException {
		delegate.deleteRelationship(relationshipId);
	}

	@Override
	public void dropIndex(String label, String key) throws IOException {
		delegate.dropIndex(label, key);
	}

	@Override
	public void dropVectorIndex(String label, String key) throws IOException {
		delegate.dropVectorIndex(label, key);
	}

	@Override
	public void forEachNode(LongConsumer consumer) {
		delegate.forEachNode(consumer);
	}

	@Override
	public boolean forEachNodeWhile(LongPredicate consumer, ScanOptions options) {
		return delegate.forEachNodeWhile(consumer, options);
	}

	@Override
	public boolean forEachNodeWhile(LongPredicate consumer) {
		return delegate.forEachNodeWhile(consumer);
	}

	@Override
	public boolean hasEdge(long nodeId, long neighbourId) {
		return delegate.hasEdge(nodeId, neighbourId);
	}

	@Override
	public boolean hasIndex(String label, String key) {
		return delegate.hasIndex(label, key);
	}

	@Override
	public boolean hasReadTransaction() {
		return delegate.hasReadTransaction();
	}

	@Override
	public boolean hasWriteTransaction() {
		return delegate.hasWriteTransaction();
	}

	@Override
	public <T> T inOpenTransaction(GraphStore.TransactionalWork<T> work) throws IOException {
		return delegate.inOpenTransaction(work);
	}

	@Override
	public <T> T inStatement(GraphStore.TransactionalWork<T> work) throws IOException {
		return delegate.inStatement(work);
	}

	@Override
	public <T> T inTransaction(GraphStore.TransactionalWork<T> work, int attempts, IsolationLevel level) throws IOException {
		return delegate.inTransaction(work, attempts, level);
	}

	@Override
	public <T> T inTransaction(GraphStore.TransactionalWork<T> work, int attempts) throws IOException {
		return delegate.inTransaction(work, attempts);
	}

	@Override
	public StoreMetrics metrics() {
		return delegate.metrics();
	}

	@Override
	public List<Neighbour> nearest(String label, String key, float[] query, int k, LongPredicate filter) throws IOException {
		return delegate.nearest(label, key, query, k, filter);
	}

	@Override
	public List<Neighbour> nearestByScan(String label, String key, float[] query, int k, VectorMetric metric) {
		return delegate.nearestByScan(label, key, query, k, metric);
	}

	@Override
	public NeighbourCursor neighbourCursor(long nodeId, Direction direction, Set<String> types) {
		return delegate.neighbourCursor(nodeId, direction, types);
	}

	@Override
	public List<Edge> neighbours(long nodeId, Direction direction, Set<String> types) {
		return delegate.neighbours(nodeId, direction, types);
	}

	@Override
	public List<Edge> neighbours(long nodeId, Direction direction) {
		return delegate.neighbours(nodeId, direction);
	}

	@Override
	public boolean nodeExists(long nodeId) {
		return delegate.nodeExists(nodeId);
	}

	@Override
	public boolean nodeHasLabel(long nodeId, String label) {
		return delegate.nodeHasLabel(nodeId, label);
	}

	@Override
	public String nodeLabel(long nodeId) {
		return delegate.nodeLabel(nodeId);
	}

	@Override
	public List<String> nodeLabels(long nodeId) {
		return delegate.nodeLabels(nodeId);
	}

	@Override
	public Properties nodeProperties(long nodeId) {
		return delegate.nodeProperties(nodeId);
	}

	@Override
	public Object nodeProperty(long nodeId, String key) {
		return delegate.nodeProperty(nodeId, key);
	}

	@Override
	public List<Long> nodesByLabel(String label) {
		return delegate.nodesByLabel(label);
	}

	@Override
	public List<Long> nodesByLabelAndProperty(String label, String key, Object value) {
		return delegate.nodesByLabelAndProperty(label, key, value);
	}

	@Override
	public List<Long> nodesByProperty(String key, Object value) {
		return delegate.nodesByProperty(key, value);
	}

	@Override
	public GraphStoreOptions options() {
		return delegate.options();
	}

	@Override
	public int reclaim() throws IOException {
		return delegate.reclaim();
	}

	@Override
	public boolean relationshipExists(long relationshipId) {
		return delegate.relationshipExists(relationshipId);
	}

	@Override
	public long relationshipFrom(long relationshipId) {
		return delegate.relationshipFrom(relationshipId);
	}

	@Override
	public Properties relationshipProperties(long relationshipId) {
		return delegate.relationshipProperties(relationshipId);
	}

	@Override
	public long relationshipTo(long relationshipId) {
		return delegate.relationshipTo(relationshipId);
	}

	@Override
	public String relationshipType(long relationshipId) {
		return delegate.relationshipType(relationshipId);
	}

	@Override
	public boolean removeNodeLabel(long nodeId, String label) throws IOException {
		return delegate.removeNodeLabel(nodeId, label);
	}

	@Override
	public void setNodeProperties(long nodeId, Properties properties) throws IOException {
		delegate.setNodeProperties(nodeId, properties);
	}

	@Override
	public void setRelationshipProperties(long relationshipId, Properties properties) throws IOException {
		delegate.setRelationshipProperties(relationshipId, properties);
	}

	@Override
	public SnapshotStats snapshotStats() {
		return delegate.snapshotStats();
	}

	@Override
	public StoreStats stats() {
		return delegate.stats();
	}

	@Override
	public List<Edge> traverse(long startNodeId, Direction direction, Set<String> types, int maxDepth) {
		return delegate.traverse(startNodeId, direction, types, maxDepth);
	}

	@Override
	public List<String> uniqueConstraints() {
		return delegate.uniqueConstraints();
	}

	@Override
	public List<VectorIndexDefinition> vectorIndexes() {
		return delegate.vectorIndexes();
	}

	@Override
	public void verify() {
		delegate.verify();
	}

	@Override
	public void verify(VerifyLevel level, VerifyOptions options) {
		delegate.verify(level, options);
	}

	@Override
	public void verify(VerifyLevel level) {
		delegate.verify(level);
	}
}
