package io.metaloom.graph.server;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAmount;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.cypher.CypherResult;
import io.metaloom.graph.cypher.CypherRow;
import io.metaloom.graph.cypher.CypherStats;
import io.metaloom.graph.cypher.value.CypherDuration;
import io.metaloom.graph.cypher.value.CypherNode;
import io.metaloom.graph.cypher.value.CypherPath;
import io.metaloom.graph.cypher.value.CypherRelationship;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Turns a query result into the two shapes the browser needs: a table, and a graph.
 *
 * <p>
 * <b>Both, from one result, without asking the user to write the query twice.</b> A result row can hold nodes,
 * relationships, paths and scalars in any mix, so the graph view is built by walking every value in every row and
 * collecting whatever is renderable — a node anywhere becomes a vertex, a relationship or path anywhere becomes edges.
 * A query returning only {@code n.name} produces a table and an empty graph, which the UI says rather than showing a
 * blank canvas.
 * </p>
 *
 * <p>
 * Relationships are only drawn when <b>both</b> endpoints are among the collected nodes. An edge to a node the query
 * never returned would otherwise appear as a line to nowhere, and D3's force layout would place a phantom vertex for
 * it. The rest are sent as {@code danglingLinks}: the far end may already be on the canvas from an earlier query, and
 * the UI's merge mode draws one as soon as both of its ends are there.
 * </p>
 *
 * <h2>Why ids cross as strings</h2>
 *
 * <p>
 * An element id is a packed 64-bit value — a real one from this engine is {@code 1152921504606846977} — and a
 * JavaScript number is a double, exact only to 2^53. That id parses in a browser as {@code 1152921504606846976}: a
 * different number. Ids closer together than the double spacing at their magnitude would collapse onto the same value
 * and D3 would draw one vertex where the query returned two.
 * </p>
 *
 * <p>
 * So <b>every id is sent as a string</b>, and so is any integer column outside the exactly-representable range. It
 * costs nothing — {@code forceLink().id()} is happy with strings — and the alternative is a rendering that is quietly
 * correct on a small store and quietly wrong on a large one, which is the worst way for a defect to behave.
 * </p>
 */
public final class ResultJson {

	/** Beyond this a JavaScript number stops being exact, so the value travels as a string instead. */
	private static final long SAFE_INTEGER = 9007199254740991L;

	private ResultJson() {
	}

	static JsonObject of(CypherResult result, long elapsedMillis) {
		JsonArray columns = new JsonArray();
		result.columns().forEach(columns::add);

		JsonArray rows = new JsonArray();
		Map<String, JsonObject> nodes = new LinkedHashMap<>();
		Set<String> seenEdges = new LinkedHashSet<>();
		JsonArray links = new JsonArray();

		for (CypherRow row : result) {
			JsonArray cells = new JsonArray();
			for (Object value : row.values()) {
				cells.add(cell(value));
				collect(value, nodes, links, seenEdges);
			}
			rows.add(cells);
		}

		// A link whose far end was never returned would render as a line to a vertex that does not exist — in this
		// result. The far end may well be on the canvas already from an earlier query, and in merge mode the UI can
		// then draw it, so these are separated rather than discarded.
		JsonArray connected = new JsonArray();
		JsonArray dangling = new JsonArray();
		for (int i = 0; i < links.size(); i++) {
			JsonObject link = links.getJsonObject(i);
			boolean drawable = nodes.containsKey(link.getString("source")) && nodes.containsKey(link.getString("target"));
			(drawable ? connected : dangling).add(link);
		}

		JsonObject json = new JsonObject()
			.put("columns", columns)
			.put("rows", rows)
			.put("rowCount", result.size())
			.put("elapsedMillis", elapsedMillis)
			.put("graph", new JsonObject()
				.put("nodes", new JsonArray(List.copyOf(nodes.values())))
				.put("links", connected)
				.put("danglingLinks", dangling));

		// Only when something changed, so every read response is exactly what it was before writes existed — which is
		// what keeps the existing tests meaning what they meant. A write query that matched nothing reports no stats,
		// correctly: nothing happened.
		if (result.stats().containsUpdates()) {
			json.put("stats", statsJson(result.stats()));
		}
		return json;
	}

	private static JsonObject statsJson(CypherStats stats) {
		return new JsonObject()
			.put("nodesCreated", stats.nodesCreated())
			.put("nodesDeleted", stats.nodesDeleted())
			.put("relationshipsCreated", stats.relationshipsCreated())
			.put("relationshipsDeleted", stats.relationshipsDeleted())
			.put("propertiesSet", stats.propertiesSet())
			.put("labelsAdded", stats.labelsAdded())
			.put("labelsRemoved", stats.labelsRemoved())
			// Rendered once here rather than reassembled in the browser, so the status line and a log line agree.
			.put("summary", stats.summary());
	}

	/** Adds any renderable value to the graph, recursing into lists so {@code collect(n)} still draws. */
	private static void collect(Object value, Map<String, JsonObject> nodes, JsonArray links, Set<String> seen) {
		switch (value) {
			// A pattern switch throws on null unless null is a case, so without this line every query returning a null
			// cell — `RETURN null`, a missing property, an empty CSV field — was a 500 with an NPE and no message.
			// A null draws nothing, which is what the default branch already says about every other scalar.
			case null -> {
				// Nothing to draw.
			}
			case CypherNode node -> nodes.putIfAbsent(String.valueOf(node.id()), nodeJson(node));
			case CypherRelationship relationship -> addLink(relationship, links, seen);
			case CypherPath path -> {
				for (CypherNode node : path.nodes()) {
					nodes.putIfAbsent(String.valueOf(node.id()), nodeJson(node));
				}
				for (CypherRelationship relationship : path.relationships()) {
					addLink(relationship, links, seen);
				}
			}
			case List<?> list -> {
				for (Object element : list) {
					collect(element, nodes, links, seen);
				}
			}
			default -> {
				// A scalar contributes to the table only.
			}
		}
	}

	private static void addLink(CypherRelationship relationship, JsonArray links, Set<String> seen) {
		// A lightweight edge has no id, so identity is its endpoints and type — the same rule the query engine uses
		// for path uniqueness. Without it, one edge returned by several rows would be drawn several times.
		String key = relationship.id() != null
			? "r" + relationship.id()
			: relationship.startId() + "/" + relationship.type() + "/" + relationship.endId();
		if (!seen.add(key)) {
			return;
		}
		links.add(new JsonObject()
			.put("id", key)
			.put("source", String.valueOf(relationship.startId()))
			.put("target", String.valueOf(relationship.endId()))
			.put("type", relationship.type())
			.put("materialised", relationship.id() != null)
			.put("properties", properties(relationship.properties())));
	}

	private static JsonObject nodeJson(CypherNode node) {
		JsonArray labels = new JsonArray();
		node.labels().forEach(labels::add);
		return new JsonObject()
			.put("id", String.valueOf(node.id()))
			.put("labels", labels)
			.put("caption", caption(node))
			.put("properties", properties(node.properties()));
	}

	/**
	 * What to write inside the circle.
	 *
	 * <p>
	 * A node id is useless to a human, so the first property that looks like a name wins, then any short string, then
	 * the first label, then the id. Guessing here is worth it: a graph of forty numbered circles tells you nothing.
	 * </p>
	 */
	private static String caption(CypherNode node) {
		for (String key : List.of("name", "title", "label", "id", "key")) {
			Object value = node.properties().get(key);
			if (value instanceof String text && !text.isBlank()) {
				return text;
			}
		}
		for (Map.Entry<String, Object> entry : node.properties().entrySet()) {
			if (entry.getValue() instanceof String text && text.length() <= 24 && !text.isBlank()) {
				return text;
			}
		}
		return node.labels().isEmpty() ? String.valueOf(node.id()) : node.labels().get(0);
	}

	private static JsonObject properties(Map<String, Object> properties) {
		JsonObject out = new JsonObject();
		properties.forEach((key, value) -> out.put(key, cell(value)));
		return out;
	}

	/**
	 * One result value as JSON, the same shape a table cell has on the HTTP route.
	 *
	 * <p>
	 * Public for the MCP query tool, which put the engine's node and relationship objects into a {@code JsonObject}
	 * directly — something Vert.x's encoder has no mapping for, so every {@code RETURN n} over MCP failed.
	 * </p>
	 */
	public static Object value(Object value) {
		return cell(value);
	}

	/**
	 * One value, as JSON.
	 *
	 * <p>
	 * Nodes and relationships become objects that carry enough to render a table cell; everything else is passed
	 * through as the JSON type it already is. Vert.x's {@code JsonObject} rejects arbitrary Java objects, so anything
	 * unrecognised is stringified rather than thrown — a result should not fail to display because one column held
	 * something exotic.
	 * </p>
	 */
	private static Object cell(Object value) {
		return switch (value) {
			case null -> null;
			case CypherNode node -> new JsonObject()
				.put("_type", "node")
				.put("id", String.valueOf(node.id()))
				.put("labels", new JsonArray(node.labels()))
				.put("properties", properties(node.properties()));
			case CypherRelationship relationship -> new JsonObject()
				.put("_type", "relationship")
				.put("id", relationship.id() == null ? null : String.valueOf(relationship.id()))
				.put("relType", relationship.type())
				.put("start", String.valueOf(relationship.startId()))
				.put("end", String.valueOf(relationship.endId()))
				.put("properties", properties(relationship.properties()));
			case CypherPath path -> new JsonObject()
				.put("_type", "path")
				.put("length", path.length())
				.put("text", path.toString());
			case List<?> list -> {
				JsonArray array = new JsonArray();
				for (Object element : list) {
					array.add(cell(element));
				}
				yield array;
			}
			case Map<?, ?> map -> {
				JsonObject object = new JsonObject();
				map.forEach((key, element) -> object.put(String.valueOf(key), cell(element)));
				yield object;
			}
			case String s -> s;
			// An integer outside the exactly-representable range travels as a string, or the browser
			// silently rounds it. This is what makes `RETURN id(n)` display an id a caller can paste back.
			case Long l -> Math.abs(l) > SAFE_INTEGER ? String.valueOf(l) : (Object) l;
			case Number n -> n;
			case Boolean b -> b;
			// Temporal values travel as their ISO-8601 strings, as Neo4j's HTTP API sends them. java.time's toString
			// is that form already — a region datetime as 2020-01-01T10:00+01:00[Europe/Vienna] — and listing them
			// here makes it a decision rather than the fallback's accident.
			case LocalDate date -> date.toString();
			case LocalTime time -> time.toString();
			case OffsetTime time -> time.toString();
			case LocalDateTime dateTime -> dateTime.toString();
			case ZonedDateTime dateTime -> dateTime.toString();
			// Any amount, not only CypherDuration: the store may hand a duration back as some other TemporalAmount,
			// whose own toString (Period's, say) would print only half of it.
			case TemporalAmount amount -> isoDuration(amount);
			default -> String.valueOf(value);
		};
	}

	private static String isoDuration(TemporalAmount amount) {
		try {
			return CypherDuration.from(amount).toString();
		} catch (DateTimeException | ArithmeticException e) {
			// A unit a duration does not have. Shown as the amount describes itself rather than failing the result.
			return String.valueOf(amount);
		}
	}
}
