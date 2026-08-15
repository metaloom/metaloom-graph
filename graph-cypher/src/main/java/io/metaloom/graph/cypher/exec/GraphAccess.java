package io.metaloom.graph.cypher.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
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

	private final GraphStore store;

	public GraphAccess(GraphStore store) {
		this.store = store;
	}

	public GraphStore store() {
		return store;
	}

	/** Materialises a node id into a value, reading its labels and properties once. */
	public CypherNode node(long id) {
		return new CypherNode(id, List.copyOf(store.nodeLabels(id)), properties(store.nodeProperties(id)));
	}

	public boolean exists(long id) {
		return store.nodeExists(id);
	}

	public void forEachNode(java.util.function.LongConsumer consumer) {
		store.forEachNode(consumer);
	}

	public List<Long> nodesByLabel(String label) {
		return store.nodesByLabel(label);
	}

	public List<Long> nodesByProperty(String key, Object value) {
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
