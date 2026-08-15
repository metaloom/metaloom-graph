package io.metaloom.graph.cypher.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.cypher.QueryAbort;
import io.metaloom.graph.cypher.QueryAbortedException;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherRelationship;

/**
 * The only place the query engine touches the store.
 *
 * <p>
 * Everything above this reads {@link CypherNode} and {@link CypherRelationship}; everything below is the storage API.
 * Keeping the boundary in one class is what makes it verifiable that the query language uses the <em>public</em> API
 * and nothing else — which is both an architectural claim and a real test of that API's completeness. Adding
 * {@code forEachNode} was the one gap this exposed.
 * </p>
 */
public final class GraphAccess {

	/**
	 * How many store touches pass between two consultations of the abort signal.
	 *
	 * <p>
	 * Asking on every touch would be the obvious thing and would cost more than the work it guards: reading the clock
	 * is on the order of twenty nanoseconds against a node read of roughly a hundred. A power of two so the test is a
	 * mask, and small enough that the delay it introduces is microseconds — a query does not become unstoppable, it
	 * becomes stoppable a thousand reads later.
	 * </p>
	 */
	private static final int ABORT_CHECK_INTERVAL = 1024;

	private final GraphStore store;
	private final QueryAbort abort;
	private int ticks;

	public GraphAccess(GraphStore store) {
		this(store, QueryAbort.NEVER);
	}

	public GraphAccess(GraphStore store, QueryAbort abort) {
		this.store = store;
		this.abort = abort == null ? QueryAbort.NEVER : abort;
	}

	public GraphStore store() {
		return store;
	}

	/**
	 * Consults the abort signal, occasionally.
	 *
	 * <p>
	 * <b>Called before entering the store, never from inside a supplier the store runs.</b>
	 * {@code GraphStoreImpl.read} runs its operation optimistically and, when a concurrent apply invalidates the
	 * stamp, discards the attempt — exception and all — and runs it again. Throwing from in there would make an abort
	 * a torn-read symptom rather than a decision. Checking on this side of the call keeps it outside that machinery
	 * entirely.
	 * </p>
	 */
	private void checkAbort() {
		if (abort == QueryAbort.NEVER) {
			return;
		}
		if (++ticks % ABORT_CHECK_INTERVAL != 0) {
			return;
		}
		String reason = abort.abortReason();
		if (reason != null) {
			throw new QueryAbortedException(reason);
		}
	}

	/** Materialises a node id into a value, reading its labels and properties once. */
	public CypherNode node(long id) {
		checkAbort();
		return new CypherNode(id, List.copyOf(store.nodeLabels(id)), properties(store.nodeProperties(id)));
	}

	public boolean exists(long id) {
		checkAbort();
		return store.nodeExists(id);
	}

	/**
	 * A full scan, which is the longest single thing a query can do and therefore the one that most needs a way out.
	 *
	 * <p>
	 * The check goes in the consumer rather than around the call, because around the call it would fire once, before a
	 * scan of ten million nodes. Throwing from inside is safe here specifically: {@code GraphStoreImpl.forEachNode}
	 * uses {@code runRead}, which takes the latch outright rather than gambling on an optimistic pass, precisely
	 * because "the consumer has side effects" and must not run twice.
	 * </p>
	 */
	public void forEachNode(java.util.function.LongConsumer consumer) {
		store.forEachNode(id -> {
			checkAbort();
			consumer.accept(id);
		});
	}

	public List<Long> nodesByLabel(String label) {
		checkAbort();
		return store.nodesByLabel(label);
	}

	public List<Long> nodesByProperty(String key, Object value) {
		checkAbort();
		return store.nodesByProperty(key, value);
	}

	public List<String> uniqueConstraints() {
		return store.uniqueConstraints();
	}

	/**
	 * The edges of a node, as relationship values paired with the neighbour they reach.
	 *
	 * @param types
	 *            null or empty means every type
	 */
	public List<Expansion> expand(long nodeId, Direction direction, Set<String> types) {
		checkAbort();
		List<Edge> edges = types == null || types.isEmpty()
			? store.neighbours(nodeId, direction)
			: store.neighbours(nodeId, direction, types);
		List<Expansion> out = new ArrayList<>(edges.size());
		for (Edge edge : edges) {
			out.add(expansion(nodeId, edge));
		}
		return out;
	}

	private Expansion expansion(long fromId, Edge edge) {
		boolean outgoing = edge.direction() == Direction.OUT;
		long startId = outgoing ? fromId : edge.neighbourId();
		long endId = outgoing ? edge.neighbourId() : fromId;
		Long relationshipId = edge.isMaterialised() ? edge.relationshipId() : null;
		Map<String, Object> props = relationshipId == null
			? Map.of()
			: properties(store.relationshipProperties(relationshipId));
		return new Expansion(new CypherRelationship(relationshipId, edge.type(), startId, endId, props),
			edge.neighbourId());
	}

	/**
	 * One step of a traversal: the relationship crossed and the node reached.
	 *
	 * @param neighbourId
	 *            the far end, relative to the node the expansion started from — which is not the same as
	 *            {@code relationship.endId()} when the edge was traversed against its direction
	 */
	public record Expansion(CypherRelationship relationship, long neighbourId) {
	}

	// ---------------------------------------------------------------- writes

	/**
	 * The write half of the boundary.
	 *
	 * <p>
	 * These run inside a transaction the caller opened — {@code Cypher.stream} does it once per query — and every one
	 * of them joins it, because the engine's mutating methods join whatever transaction the calling thread holds. So
	 * nothing here commits, and a failure anywhere unwinds the lot.
	 * </p>
	 *
	 * <p>
	 * {@link java.io.IOException} becomes {@link java.io.UncheckedIOException} rather than being declared: a disk
	 * failure is not something a clause handler can do anything about, and threading a checked exception through the
	 * evaluator would put it in the signature of every expression as well.
	 * </p>
	 */
	public long createNode(List<String> labels, Map<String, Object> properties) {
		checkAbort();
		try {
			return store.createNode(labels, toStore(properties));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	public long createRelationship(long fromId, String type, long toId, Map<String, Object> properties) {
		checkAbort();
		try {
			return store.createRelationship(fromId, type, toId, toStore(properties));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Replaces a node's whole property map. One call per element per clause — see {@code setNodeProperties}. */
	public void setNodeProperties(long nodeId, Map<String, Object> properties) {
		checkAbort();
		try {
			store.setNodeProperties(nodeId, toStore(properties));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	public void setRelationshipProperties(long relationshipId, Map<String, Object> properties) {
		checkAbort();
		try {
			store.setRelationshipProperties(relationshipId, toStore(properties));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	public boolean addNodeLabel(long nodeId, String label) {
		checkAbort();
		try {
			return store.addNodeLabel(nodeId, label);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	public boolean removeNodeLabel(long nodeId, String label) {
		checkAbort();
		try {
			return store.removeNodeLabel(nodeId, label);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Deletes a node and everything attached to it. The caller decides whether that cascade was asked for. */
	public void deleteNode(long nodeId) {
		checkAbort();
		try {
			store.deleteNode(nodeId);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	public void deleteRelationship(long relationshipId) {
		checkAbort();
		try {
			store.deleteRelationship(relationshipId);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Removes a lightweight edge, which has no id to name it by. False when there was none to remove. */
	public boolean deleteEdge(long fromId, String type, long toId) {
		checkAbort();
		try {
			return store.deleteEdge(fromId, type, toId);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Total degree, which is what plain DELETE has to consult before it may remove a node. */
	public int degree(long nodeId) {
		checkAbort();
		return store.degreeOut(nodeId) + store.degreeIn(nodeId);
	}

	public boolean relationshipExists(long relationshipId) {
		checkAbort();
		return store.relationshipExists(relationshipId);
	}

	/** Re-reads a materialised relationship, which is how a binding is refreshed after a SET touched it. */
	public CypherRelationship relationship(long relationshipId) {
		checkAbort();
		return new CypherRelationship(relationshipId, store.relationshipType(relationshipId),
			store.relationshipFrom(relationshipId), store.relationshipTo(relationshipId),
			properties(store.relationshipProperties(relationshipId)));
	}

	/**
	 * Brings a Cypher value back into the store's type system, which is the narrower of the two.
	 *
	 * <p>
	 * A property must be a primitive or a list of primitives. A map, a node, a relationship or a path is refused here
	 * rather than at the storage layer, so the message can name the offending key and say what a property may hold —
	 * {@code IllegalArgumentException: Unsupported property value type} would be true and unhelpful.
	 * </p>
	 *
	 * <p>
	 * <b>This is not the exact inverse of {@link #normalise}.</b> Reading renders {@code byte[]}, {@code UUID} and
	 * {@code Instant} as strings, so writing one back stores a string. {@code SET n.x = n.x} on such a property is
	 * therefore a type change, and there is no way for the query language to write those three types at all.
	 * </p>
	 */
	private static io.metaloom.graph.core.api.Properties toStore(Map<String, Object> values) {
		io.metaloom.graph.core.api.Properties properties = io.metaloom.graph.core.api.Properties.of();
		if (values != null) {
			for (Map.Entry<String, Object> entry : values.entrySet()) {
				properties.set(entry.getKey(), storable(entry.getKey(), entry.getValue()));
			}
		}
		return properties;
	}

	private static Object storable(String key, Object value) {
		return switch (value) {
			case null -> null;
			case Boolean b -> b;
			case Long l -> l;
			case Double d -> d;
			case String s -> s;
			case List<?> list -> {
				List<Object> out = new ArrayList<>(list.size());
				for (Object element : list) {
					if (element instanceof List<?>) {
						throw new CypherTypeException("The property '" + key + "' cannot hold a nested list");
					}
					out.add(storable(key, element));
				}
				yield out;
			}
			default -> throw new CypherTypeException("The property '" + key + "' cannot hold a "
				+ Values.typeName(value) + "; a property must be a number, a string, a boolean or a list of those");
		};
	}

	private static Map<String, Object> properties(io.metaloom.graph.core.api.Properties properties) {
		if (properties == null || properties.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		for (String key : properties.keys()) {
			out.put(key, normalise(properties.get(key)));
		}
		return out;
	}

	/**
	 * Brings a stored value into Cypher's type system.
	 *
	 * <p>
	 * The store keeps types Cypher has no equivalent for — {@code byte[]}, {@code float[]}, {@code UUID},
	 * {@code Instant}. They are rendered as strings rather than leaked as Java objects, because a value that reaches a
	 * query result has to be something the caller can compare, order and serialise. Vectors become a list of numbers,
	 * which is the one shape a query might genuinely want to index into.
	 * </p>
	 */
	private static Object normalise(Object value) {
		return switch (value) {
			case null -> null;
			case float[] vector -> {
				List<Object> list = new ArrayList<>(vector.length);
				for (float element : vector) {
					list.add((double) element);
				}
				yield list;
			}
			case byte[] bytes -> "0x" + java.util.HexFormat.of().formatHex(bytes);
			case java.util.UUID uuid -> uuid.toString();
			case java.time.Instant instant -> instant.toString();
			case Integer i -> i.longValue();
			case Short s -> s.longValue();
			case Byte b -> b.longValue();
			case Float f -> f.doubleValue();
			case List<?> list -> {
				List<Object> out = new ArrayList<>(list.size());
				for (Object element : list) {
					out.add(normalise(element));
				}
				yield out;
			}
			default -> value;
		};
	}
}
