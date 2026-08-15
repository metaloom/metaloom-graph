package io.metaloom.graph.protocol;

/**
 * What the {@link Frames#COMPLETE} frame carries: how the query went, and what it changed.
 *
 * <p>
 * A write query often returns no rows at all, so without these numbers its result would be indistinguishable from a
 * read that matched nothing. They are the only evidence such a query produces.
 * </p>
 *
 * <p>
 * <b>Fixed fields rather than a map.</b> A map would let a counter be added without a version bump, but this protocol's
 * rule is to bump instead — and a fixed layout is one the hostile-input oracle can exhaust, where a map is a length
 * field and a loop. Nine little-endian {@code i64}s, always all of them: 72 bytes once per query is not worth a branch.
 * </p>
 *
 * <p>
 * This lives in graph-protocol and not in graph-cypher because the client must be able to read it, and the client
 * depends on neither the query engine nor the storage engine — a rule {@code ClientDependencyTest} enforces by loading.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a>
 */
public record QuerySummary(long rows, long elapsedMicros, long nodesCreated, long nodesDeleted,
	long relationshipsCreated, long relationshipsDeleted, long propertiesSet, long labelsAdded, long labelsRemoved) {

	/** Bytes on the wire: nine fixed 64-bit fields. */
	public static final int BYTES = 9 * 8;

	public static QuerySummary read(WireValueCodec.Reader reader) {
		return new QuerySummary(reader.i64(), reader.i64(), reader.i64(), reader.i64(), reader.i64(), reader.i64(),
			reader.i64(), reader.i64(), reader.i64());
	}

	public void write(WireValueCodec.Writer writer) {
		writer.i64(rows);
		writer.i64(elapsedMicros);
		writer.i64(nodesCreated);
		writer.i64(nodesDeleted);
		writer.i64(relationshipsCreated);
		writer.i64(relationshipsDeleted);
		writer.i64(propertiesSet);
		writer.i64(labelsAdded);
		writer.i64(labelsRemoved);
	}

	/**
	 * Whether the store was changed.
	 *
	 * <p>
	 * A write query that matched nothing reports false, which is the useful answer: it separates "this server cannot
	 * write" from "this query wrote nothing this time".
	 * </p>
	 */
	public boolean containsUpdates() {
		return nodesCreated > 0 || nodesDeleted > 0 || relationshipsCreated > 0 || relationshipsDeleted > 0
			|| propertiesSet > 0 || labelsAdded > 0 || labelsRemoved > 0;
	}
}
