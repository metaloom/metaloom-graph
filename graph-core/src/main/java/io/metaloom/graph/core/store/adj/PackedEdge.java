package io.metaloom.graph.core.store.adj;

import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;

/**
 * One adjacency entry in 8 bytes: {@code dir:2 | type:14 | location:48}.
 * <p>
 * The location is the neighbour's {@code segment:slot} without kind or generation. The generation is deliberately
 * omitted: adjacency is maintained alongside the node, so an entry is never stale in the way an externally held id
 * can be, and the neighbour's record is read anyway.
 * <p>
 * Direction 3 means the entry is <em>materialised</em>: the 48 bit field is a relationship location rather than a
 * neighbour, and direction and endpoints come from the relationship record.
 * <p>
 * The type of a materialised entry is a <em>cache</em> of the record's type, written so that a type-filtered traversal
 * can rule the entry out without the random 64 byte read the record costs. {@link #TYPE_NOT_RECORDED} means it was not
 * cached and the record has to be read, which is what every entry written before this store format did and what an
 * unrepresentable type id still does. Direction is not cached and cannot be: the 48 bits hold the relationship's
 * location, not the neighbour's, so {@code outgoing} is only answerable from the record.
 *
 * @see <a href="../../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> sections 3
 *      and 3.1
 */
public final class PackedEdge {

	public static final int DIR_OUT = 0;
	public static final int DIR_IN = 1;
	public static final int DIR_UNDIRECTED = 2;
	public static final int DIR_MATERIALISED = 3;

	public static final int MAX_TYPE = (1 << 14) - 1;
	public static final long MAX_LOCATION = (1L << 48) - 1;

	/**
	 * The type field of a materialised entry when the type is not cached in it and the relationship record has to be
	 * read to learn it.
	 * <p>
	 * Zero, which costs nothing: {@code Catalog} hands out label id 0 for "unknown" and {@code lookupLabel} returns it
	 * for a label it has never seen, so no relationship ever has type 0 and the value was free to take. That is what
	 * makes this compatible with a store written before the type was cached — every materialised entry in one holds
	 * zero, and zero means exactly what it did then.
	 */
	public static final int TYPE_NOT_RECORDED = 0;

	private static final int DIR_SHIFT = 62;
	private static final int TYPE_SHIFT = 48;
	private static final long LOCATION_MASK = MAX_LOCATION;

	/**
	 * Every field but the type. Matching an entry through this mask ignores the cached type, which is what deleting a
	 * materialised entry has to do: the cache is advisory, and an entry written before it existed holds
	 * {@link #TYPE_NOT_RECORDED} where a fresh one holds the real type.
	 */
	public static final long TYPE_AGNOSTIC_MASK = ~((long) MAX_TYPE << TYPE_SHIFT);

	/**
	 * The tombstone is the all-ones value. It is unreachable through the public constructors: {@code dir=3} with the
	 * all-ones type field is rejected by {@link #pack}, which is why {@link #MAX_TYPE} is the one type id a
	 * materialised entry cannot cache and falls back to {@link #TYPE_NOT_RECORDED}.
	 * <p>
	 * Location 0 is deliberately <em>not</em> the tombstone: it is the perfectly valid location of slot 0 in segment
	 * 0, which is the first node any store creates.
	 */
	public static final long TOMBSTONE = -1L;

	private PackedEdge() {
	}

	public static long pack(int dir, int typeId, long location) {
		if (dir < 0 || dir > 3) {
			throw new IllegalArgumentException("dir out of range: " + dir);
		}
		if (typeId < 0 || typeId > MAX_TYPE) {
			throw new IllegalArgumentException("type id " + typeId + " does not fit the 14 bit adjacency field");
		}
		if (dir == DIR_MATERIALISED && typeId == MAX_TYPE) {
			// The whole tombstone argument rests on this one line: dir 3 plus an all-ones type field is -1 once the
			// location fills up, so a materialised entry may cache any type but that one.
			throw new IllegalArgumentException("A materialised entry cannot cache type " + MAX_TYPE
				+ ": dir 3 with an all-ones type field is the tombstone");
		}
		if (location < 0 || location > MAX_LOCATION) {
			throw new IllegalArgumentException("location out of range: " + location);
		}
		return ((long) dir << DIR_SHIFT) | ((long) typeId << TYPE_SHIFT) | location;
	}

	public static long ofNeighbour(int dir, int typeId, long neighbourId) {
		return pack(dir, typeId, ElementId.location(neighbourId));
	}

	/** A materialised entry that caches no type, so every reader has to go to the relationship record. */
	public static long ofRelationship(long relationshipId) {
		return pack(DIR_MATERIALISED, TYPE_NOT_RECORDED, ElementId.location(relationshipId));
	}

	/**
	 * A materialised entry caching the relationship's type, so a type-filtered traversal can discard it without
	 * reading the record. A type that cannot be cached falls back to {@link #ofRelationship(long)}.
	 */
	public static long ofRelationship(long relationshipId, int typeId) {
		return pack(DIR_MATERIALISED, cacheableType(typeId), ElementId.location(relationshipId));
	}

	/**
	 * What a materialised entry stores for a relationship of the given type: the type itself, or
	 * {@link #TYPE_NOT_RECORDED} when it cannot be represented.
	 * <p>
	 * Anything outside {@code 1..MAX_TYPE-1} falls back rather than throwing, and deliberately so: the argument is
	 * usually a type id read straight out of a relationship record, which is untrusted bytes off a page. A corrupt one
	 * must degrade to "read the record", not break a delete.
	 */
	public static int cacheableType(int typeId) {
		return typeId <= 0 || typeId >= MAX_TYPE ? TYPE_NOT_RECORDED : typeId;
	}

	public static int dir(long edge) {
		return (int) (edge >>> DIR_SHIFT);
	}

	/**
	 * The type field. On a lightweight entry it is the type; on a materialised one it is the cached type, or
	 * {@link #TYPE_NOT_RECORDED}.
	 */
	public static int typeId(long edge) {
		return (int) ((edge >>> TYPE_SHIFT) & MAX_TYPE);
	}

	/**
	 * True when the entry alone proves it cannot match a type filter — so it can be discarded without resolving
	 * anything it points at.
	 * <p>
	 * A materialised entry that caches no type is never excluded here: type 0 is not a type, it is the absence of the
	 * cache, and treating it as a mismatch would silently drop every edge of a store written before the cache existed.
	 * That is the one direction this must never get wrong; a false <em>negative</em> only costs the record read that
	 * used to be unconditional.
	 */
	public static boolean excludedByType(long edge, TypeFilter typeIds) {
		if (typeIds == null) {
			return false;
		}
		int typeId = typeId(edge);
		if (typeId == TYPE_NOT_RECORDED && isMaterialised(edge)) {
			return false;
		}
		return !typeIds.admits(typeId);
	}

	public static long location(long edge) {
		return edge & LOCATION_MASK;
	}

	public static boolean isMaterialised(long edge) {
		return dir(edge) == DIR_MATERIALISED;
	}

	public static boolean isTombstone(long edge) {
		return edge == TOMBSTONE;
	}

	/** The neighbour node id, with generation 0. Callers read the neighbour record to recover the real generation. */
	public static long neighbourId(long edge) {
		return ElementId.fromLocation(Kind.NODE, location(edge));
	}

	/** The relationship id of a materialised entry, with generation 0. */
	public static long relationshipId(long edge) {
		return ElementId.fromLocation(Kind.REL, location(edge));
	}

	/** The 16 bit group key used by the codec: identical dir and type entries share a group header. */
	public static int groupKey(long edge) {
		return (int) (edge >>> TYPE_SHIFT);
	}

	public static String toString(long edge) {
		return "Edge[dir=" + dir(edge) + ",type=" + typeId(edge) + ",loc=" + location(edge) + "]";
	}
}
