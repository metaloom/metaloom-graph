package io.metaloom.graph.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.ReadTransaction;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What is in the store: which node labels exist, which relationship types, and how many of each.
 *
 * <h2>Why this is a scan and not a lookup</h2>
 *
 * <p>
 * The engine interns labels and relationship types into <b>one</b> dictionary — {@code Catalog.internEdgeType}
 * delegates to {@code internLabel} — so the dictionary can say that {@code "KNOWS"} exists but not that it is a
 * relationship type rather than a node label. Nor does it know what is still live: a label survives in the dictionary
 * after the last node carrying it is deleted, because ids are never reused. A sidebar built from the dictionary would
 * therefore show relationship types among the node labels and list labels that match nothing.
 * </p>
 *
 * <p>
 * So the counts come from the data. One pass over the live nodes reads each node's labels and its outgoing edges,
 * which is enough for both halves of the sidebar and costs one traversal of the node segment rather than two.
 * </p>
 *
 * <h2>The cost, and what is done about it</h2>
 *
 * <p>
 * It is O(nodes + edges) and there is no cheaper way to get a truthful answer. Three things keep it from being a
 * problem: it runs on a worker thread, its result is cached until {@link #TTL_MILLIS} has passed, and the response
 * carries {@code elapsedMillis} and {@code stale} so a user can see what it cost and ask for a fresh one. On a store
 * large enough for the scan to hurt, the sidebar is the wrong tool anyway and the cache is what makes the UI usable.
 * </p>
 *
 * <p>
 * The scan runs inside a snapshot view, so the counts are consistent with each other even if a writer commits
 * underneath it. Without that, a relationship could be counted whose end node had already been deleted from the
 * count.
 * </p>
 */
final class SchemaScanner {

	/** How long a scan result is served before another one is run. */
	static final long TTL_MILLIS = 60_000;

	private final GraphStore store;

	/** Guarded by {@code this}; both fields are only ever read and written together. */
	private JsonObject cached;
	private long cachedAtNanos;

	SchemaScanner(GraphStore store) {
		this.store = store;
	}

	/**
	 * The schema, from cache when it is younger than the TTL.
	 *
	 * @param force
	 *            run a scan whatever the cache says — what the UI's refresh button sends
	 */
	synchronized JsonObject schema(boolean force) {
		if (!force && cached != null && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cachedAtNanos) < TTL_MILLIS) {
			return cached.copy().put("cached", true);
		}
		long started = System.nanoTime();
		JsonObject fresh = scan().put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
		cached = fresh;
		cachedAtNanos = System.nanoTime();
		return fresh.copy().put("cached", false);
	}

	private JsonObject scan() {
		Map<String, long[]> labels = new HashMap<>();
		Map<String, long[]> types = new HashMap<>();
		AtomicLong nodes = new AtomicLong();
		AtomicLong relationships = new AtomicLong();
		AtomicLong unlabelled = new AtomicLong();

		try (ReadTransaction view = store.beginRead(IsolationLevel.SNAPSHOT)) {
			store.forEachNode(nodeId -> {
				nodes.incrementAndGet();
				List<String> nodeLabels = store.nodeLabels(nodeId);
				if (nodeLabels.isEmpty()) {
					unlabelled.incrementAndGet();
				}
				for (String label : nodeLabels) {
					labels.computeIfAbsent(label, key -> new long[1])[0]++;
				}
				// Outgoing only. Every edge is outgoing from exactly one node, so this counts each one once —
				// Direction.BOTH would count every relationship twice.
				for (Edge edge : store.neighbours(nodeId, Direction.OUT)) {
					relationships.incrementAndGet();
					types.computeIfAbsent(edge.type(), key -> new long[1])[0]++;
				}
			});
		}

		return new JsonObject()
			.put("labels", counted(labels))
			.put("relationshipTypes", counted(types))
			.put("nodeCount", nodes.get())
			.put("relationshipCount", relationships.get())
			.put("unlabelledNodeCount", unlabelled.get());
	}

	/** Sorted by count descending then by name, so the sidebar has a stable and useful order. */
	private static JsonArray counted(Map<String, long[]> counts) {
		List<Map.Entry<String, long[]>> entries = new ArrayList<>(counts.entrySet());
		entries.sort(Comparator.<Map.Entry<String, long[]>> comparingLong(entry -> -entry.getValue()[0])
			.thenComparing(Map.Entry::getKey));
		JsonArray array = new JsonArray();
		for (Map.Entry<String, long[]> entry : entries) {
			array.add(new JsonObject().put("name", entry.getKey()).put("count", entry.getValue()[0]));
		}
		return array;
	}
}
