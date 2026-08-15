package io.metaloom.graph.server.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;
import io.metaloom.graph.protocol.WireNode;
import io.metaloom.graph.protocol.WirePath;
import io.metaloom.graph.protocol.WireRelationship;

/**
 * Turns a query cell into the shape the wire codec knows.
 *
 * <h2>Why a conversion exists at all</h2>
 *
 * <p>
 * The two sets of records are field-for-field identical, which looks like duplication until you ask what the
 * alternative costs. For the codec to encode {@code CypherNode} directly, graph-protocol would have to depend on
 * graph-cypher, which depends on graph-core, which is the storage engine and the FFM layer — and every one of those
 * would then be on the classpath of a client whose entire job is to read rows off a socket. This conversion is what
 * buys a client jar with no storage engine in it.
 * </p>
 *
 * <p>
 * The cost is one small record per node, relationship and path in the result, reusing the label list and property map
 * by reference rather than copying them. Against the ~500 bytes a node row already allocates in the executor, it is
 * noise; against a dependency on the whole engine, it is a bargain.
 * </p>
 *
 * <p>
 * <b>Everything else is returned unchanged.</b> {@code GraphAccess.normalise} has already brought stored values into
 * Cypher's type system — {@code byte[]} to a hex string, {@code float[]} to a list of doubles, {@code Integer} to
 * {@code Long} — so a scalar arriving here is already something the codec has a tag for. Anything that is not is
 * refused by the codec rather than stringified, which is how a gap between the two type systems becomes a loud failure
 * instead of a quietly wrong value.
 * </p>
 */
final class CypherWireValues {

	private CypherWireValues() {
	}

	/**
	 * @return the wire form, or {@code value} itself when no conversion is needed — which is the common case, and the
	 *         reason this returns the same instance rather than always rebuilding
	 */
	static Object of(Object value) {
		return switch (value) {
			case null -> null;
			case CypherNode node -> node(node);
			case CypherRelationship rel -> relationship(rel);
			case CypherPath path -> path(path);
			case List<?> list -> list(list);
			case Map<?, ?> map -> map(map);
			default -> value;
		};
	}

	private static WireNode node(CypherNode node) {
		return new WireNode(node.id(), node.labels(), properties(node.properties()));
	}

	private static WireRelationship relationship(CypherRelationship rel) {
		return new WireRelationship(rel.id(), rel.type(), rel.startId(), rel.endId(), properties(rel.properties()));
	}

	private static WirePath path(CypherPath path) {
		List<WireNode> nodes = new ArrayList<>(path.nodes().size());
		for (CypherNode node : path.nodes()) {
			nodes.add(node(node));
		}
		List<WireRelationship> rels = new ArrayList<>(path.relationships().size());
		for (CypherRelationship rel : path.relationships()) {
			rels.add(relationship(rel));
		}
		return new WirePath(nodes, rels);
	}

	/**
	 * A property map, rebuilt only if something inside it actually changed.
	 *
	 * <p>
	 * A node's properties are almost always scalars, so almost always this returns the map it was given and the whole
	 * conversion of a node costs exactly one record.
	 * </p>
	 */
	private static Map<String, Object> properties(Map<String, Object> properties) {
		Map<String, Object> converted = null;
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			Object wire = of(entry.getValue());
			if (wire != entry.getValue() && converted == null) {
				converted = new LinkedHashMap<>(properties);
			}
			if (converted != null) {
				converted.put(entry.getKey(), wire);
			}
		}
		return converted == null ? properties : converted;
	}

	private static Object list(List<?> list) {
		List<Object> converted = null;
		for (int i = 0; i < list.size(); i++) {
			Object element = list.get(i);
			Object wire = of(element);
			if (wire != element && converted == null) {
				converted = new ArrayList<>(list);
			}
			if (converted != null) {
				converted.set(i, wire);
			}
		}
		return converted == null ? list : converted;
	}

	private static Object map(Map<?, ?> map) {
		Map<String, Object> converted = null;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			Object wire = of(entry.getValue());
			if (wire != entry.getValue() && converted == null) {
				converted = new LinkedHashMap<>();
				for (Map.Entry<?, ?> copy : map.entrySet()) {
					converted.put(String.valueOf(copy.getKey()), copy.getValue());
				}
			}
			if (converted != null) {
				converted.put(String.valueOf(entry.getKey()), wire);
			}
		}
		return converted == null ? map : converted;
	}
}
