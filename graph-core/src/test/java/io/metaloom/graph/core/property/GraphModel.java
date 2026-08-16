package io.metaloom.graph.core.property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The graph as plain Java collections, with the same semantics as the store and none of its cleverness.
 * <p>
 * This is the oracle for the differential harness. It is deliberately the dumbest correct implementation: a few maps
 * and lists, no ids that decode into offsets, no adjacency tiers, no blocks. When the two disagree, the model is
 * presumed right, because there is almost nothing in it to be wrong.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 4
 */
public final class GraphModel {

	/** One lightweight edge. Duplicates are meaningful, so these live in a list, not a set. */
	public record Edge(long from, String type, long to) {
	}

	public record Relationship(long from, String type, long to, Map<String, Object> properties) {
	}

	/**
	 * A node's labels, in the order the store keeps them.
	 * <p>
	 * A list rather than a single label because {@code addNodeLabel} and {@code removeNodeLabel} are in the workload:
	 * they are the only writes that reach {@code onLabelsChanged}, and that hook had no differential coverage at all
	 * while this was one string.
	 */
	private final Map<Long, List<String>> labels = new LinkedHashMap<>();
	private final Map<Long, Map<String, Object>> properties = new HashMap<>();
	private final List<Edge> edges = new ArrayList<>();
	private final Map<Long, Relationship> relationships = new LinkedHashMap<>();

	public void createNode(long id, String label, Map<String, Object> props) {
		labels.put(id, new ArrayList<>(List.of(label)));
		properties.put(id, props == null ? Map.of() : new LinkedHashMap<>(props));
	}

	public void setProperties(long id, Map<String, Object> props) {
		properties.put(id, new LinkedHashMap<>(props));
	}

	public void deleteNode(long id) {
		labels.remove(id);
		properties.remove(id);
		edges.removeIf(edge -> edge.from() == id || edge.to() == id);
		relationships.entrySet().removeIf(entry -> entry.getValue().from() == id || entry.getValue().to() == id);
	}

	public void createEdge(long from, String type, long to) {
		edges.add(new Edge(from, type, to));
	}

	/** Removes one matching edge, as the store does. Returns false when there was none. */
	public boolean deleteEdge(long from, String type, long to) {
		return edges.remove(new Edge(from, type, to));
	}

	public void createRelationship(long id, long from, String type, long to, Map<String, Object> props) {
		relationships.put(id, new Relationship(from, type, to, props == null ? Map.of() : new LinkedHashMap<>(props)));
	}

	public void setRelationshipProperties(long id, Map<String, Object> props) {
		Relationship existing = relationships.get(id);
		if (existing != null) {
			relationships.put(id, new Relationship(existing.from(), existing.type(), existing.to(),
				new LinkedHashMap<>(props)));
		}
	}

	public void deleteRelationship(long id) {
		relationships.remove(id);
	}

	// ---------------------------------------------------------------- queries

	public Set<Long> nodeIds() {
		return new TreeSet<>(labels.keySet());
	}

	public Set<Long> relationshipIds() {
		return new TreeSet<>(relationships.keySet());
	}

	public boolean hasNode(long id) {
		return labels.containsKey(id);
	}

	/** The first label, which is what {@code GraphStore.nodeLabel} returns. */
	public String label(long id) {
		List<String> held = labels.get(id);
		return held == null || held.isEmpty() ? null : held.get(0);
	}

	public List<String> labels(long id) {
		return labels.getOrDefault(id, List.of());
	}

	/** @return false if the node already carried it, which is what the store reports */
	public boolean addLabel(long id, String label) {
		List<String> held = labels.get(id);
		if (held == null || held.contains(label)) {
			return false;
		}
		held.add(label);
		return true;
	}

	public boolean removeLabel(long id, String label) {
		List<String> held = labels.get(id);
		return held != null && held.remove(label);
	}

	public Map<String, Object> properties(long id) {
		return properties.getOrDefault(id, Map.of());
	}

	public Relationship relationship(long id) {
		return relationships.get(id);
	}

	public Set<Long> nodesByLabel(String label) {
		Set<Long> result = new TreeSet<>();
		labels.forEach((id, held) -> {
			if (held.contains(label)) {
				result.add(id);
			}
		});
		return result;
	}

	public Set<Long> nodesByProperty(String key, Object value) {
		Set<Long> result = new TreeSet<>();
		properties.forEach((id, props) -> {
			if (labels.containsKey(id) && Objects.deepEquals(normalise(props.get(key)), normalise(value))
				&& props.containsKey(key)) {
				result.add(id);
			}
		});
		return result;
	}

	/**
	 * The composite lookup, as the dumbest correct implementation: every node that carries the label and holds the
	 * value. The store answers it from one B+tree probe when the pair is declared and from the property tree when it
	 * is not, and both have to agree with this.
	 */
	public Set<Long> nodesByLabelAndProperty(String label, String key, Object value) {
		Set<Long> result = new TreeSet<>();
		for (long id : nodesByProperty(key, value)) {
			if (labels.getOrDefault(id, List.of()).contains(label)) {
				result.add(id);
			}
		}
		return result;
	}

	/**
	 * The comparable form of a stored value.
	 * <p>
	 * Two things are folded here. The store widens every stored integer to a long, so the model has to compare the
	 * same way. And an array compares by identity, which would make every vector property differ from itself the
	 * moment it was read back from the store — so a {@code float[]} becomes a list, which compares by content.
	 */
	public static Object normalise(Object value) {
		if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
			return ((Number) value).longValue();
		}
		if (value instanceof float[] vector) {
			List<Float> list = new ArrayList<>(vector.length);
			for (float component : vector) {
				list.add(component);
			}
			return list;
		}
		return value;
	}

	public int degreeOut(long id) {
		int count = 0;
		for (Edge edge : edges) {
			if (edge.from() == id) {
				count++;
			}
		}
		for (Relationship rel : relationships.values()) {
			if (rel.from() == id) {
				count++;
			}
		}
		return count;
	}

	public int degreeIn(long id) {
		int count = 0;
		for (Edge edge : edges) {
			if (edge.to() == id) {
				count++;
			}
		}
		for (Relationship rel : relationships.values()) {
			if (rel.to() == id) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Neighbours as a sorted multiset of {@code direction:type:neighbour} strings, which is the comparable form: order
	 * is not part of the contract but multiplicity is.
	 */
	public List<String> neighbours(long id, boolean out, boolean in) {
		List<String> result = new ArrayList<>();
		for (Edge edge : edges) {
			if (out && edge.from() == id) {
				result.add("OUT:" + edge.type() + ":" + edge.to());
			}
			if (in && edge.to() == id) {
				result.add("IN:" + edge.type() + ":" + edge.from());
			}
		}
		for (Relationship rel : relationships.values()) {
			if (out && rel.from() == id) {
				result.add("OUT:" + rel.type() + ":" + rel.to());
			}
			if (in && rel.to() == id) {
				result.add("IN:" + rel.type() + ":" + rel.from());
			}
		}
		result.sort(null);
		return result;
	}

	public List<Edge> edges() {
		return edges;
	}

	public int size() {
		return labels.size();
	}

	/** A deep copy, which is how the harness remembers the last committed state across a rollback or a crash. */
	public GraphModel copy() {
		GraphModel copy = new GraphModel();
		// Per entry, not putAll: the values are mutable lists now, and a shallow copy would let a write into the
		// pending model reach the committed one — which makes a rolled-back batch keep its labels and the harness
		// report the engine as wrong.
		labels.forEach((id, held) -> copy.labels.put(id, new ArrayList<>(held)));
		properties.forEach((id, props) -> copy.properties.put(id, new LinkedHashMap<>(props)));
		copy.edges.addAll(edges);
		copy.relationships.putAll(relationships);
		return copy;
	}
}
