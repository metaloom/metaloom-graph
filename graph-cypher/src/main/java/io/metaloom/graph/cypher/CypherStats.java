package io.metaloom.graph.cypher;

/**
 * What a query changed.
 *
 * <p>
 * A write query often returns no rows at all — {@code CREATE (n:Person {name:'Ada'})} has nothing to project — so
 * without counters its result would be indistinguishable from a query that matched nothing and did nothing. These are
 * the only evidence such a query produces, which is why they travel beside the rows rather than in a log line.
 * </p>
 *
 * <p>
 * <b>{@code propertiesSet} counts keys whose value actually changed</b>, in either direction: writing a key that was
 * absent, changing one that was present, and removing one all count as one. Writing a key the value it already had
 * counts as nothing. That makes the number a report of what the store now differs by, rather than of how many
 * assignments the query text contained.
 * </p>
 *
 * @see <a href="../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
public record CypherStats(long nodesCreated, long nodesDeleted, long relationshipsCreated, long relationshipsDeleted,
	long propertiesSet, long labelsAdded, long labelsRemoved) {

	/** What a read query reports: nothing happened. */
	public static final CypherStats NONE = new CypherStats(0, 0, 0, 0, 0, 0, 0);

	/**
	 * Whether the store was changed at all.
	 *
	 * <p>
	 * A write query that matched nothing reports false, which is correct and is the point: it distinguishes "this
	 * query cannot write" from "this query wrote nothing this time", and the caller that has to decide whether to
	 * invalidate a cached schema needs the second question answered rather than the first.
	 * </p>
	 */
	public boolean containsUpdates() {
		return nodesCreated > 0 || nodesDeleted > 0 || relationshipsCreated > 0 || relationshipsDeleted > 0
			|| propertiesSet > 0 || labelsAdded > 0 || labelsRemoved > 0;
	}

	/** A one-line summary naming only the non-zero counters, for a status line or a log. */
	public String summary() {
		StringBuilder text = new StringBuilder();
		append(text, nodesCreated, "node", "created");
		append(text, relationshipsCreated, "relationship", "created");
		append(text, nodesDeleted, "node", "deleted");
		append(text, relationshipsDeleted, "relationship", "deleted");
		append(text, propertiesSet, "property", "set");
		append(text, labelsAdded, "label", "added");
		append(text, labelsRemoved, "label", "removed");
		return text.isEmpty() ? "nothing changed" : text.toString();
	}

	private static void append(StringBuilder text, long count, String noun, String verb) {
		if (count == 0) {
			return;
		}
		if (!text.isEmpty()) {
			text.append(", ");
		}
		text.append(count).append(' ').append(noun).append(count == 1 ? "" : "s").append(' ').append(verb);
	}
}
