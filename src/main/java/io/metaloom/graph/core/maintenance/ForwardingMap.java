package io.metaloom.graph.core.maintenance;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.graph.core.id.ElementId;

/**
 * Old id to new id for the records relocated by one compaction batch.
 * <p>
 * The map exists only for the duration of the batch that built it, and it is consulted only by the reference rewrite
 * sweep — never on a read path. That is the whole point: the design's promise is that an id decodes arithmetically into
 * an offset, and a forwarding table that outlived the rewrite would quietly turn every read into an indirection.
 * <p>
 * Two views are needed because the store references elements in two forms. Typed record fields hold a full 64 bit id
 * with its generation; adjacency entries hold a 48 bit {@code segment:slot} location with neither kind nor generation.
 * A rewrite that fixed one and not the other would leave half the graph pointing at freed slots.
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 9
 */
public final class ForwardingMap {

	/** Insertion ordered so that a batch frees its source slots in the order it relocated them. */
	private final Map<Long, Long> byId = new LinkedHashMap<>();
	private final Map<Long, Long> byLocation = new HashMap<>();

	public void put(long oldId, long newId) {
		byId.put(oldId, newId);
		byLocation.put(ElementId.location(oldId), ElementId.location(newId));
	}

	/** The new id, or the argument unchanged when this batch did not move it. */
	public long mapId(long id) {
		Long moved = byId.get(id);
		return moved == null ? id : moved;
	}

	/** The new 48 bit location, or the argument unchanged. */
	public long mapLocation(long location) {
		Long moved = byLocation.get(location);
		return moved == null ? location : moved;
	}

	/** True when the id names a record this batch has already copied elsewhere, and which is about to be freed. */
	public boolean isSource(long id) {
		return byId.containsKey(id);
	}

	public Map<Long, Long> entries() {
		return byId;
	}

	public int size() {
		return byId.size();
	}

	public boolean isEmpty() {
		return byId.isEmpty();
	}

	public void clear() {
		byId.clear();
		byLocation.clear();
	}
}
