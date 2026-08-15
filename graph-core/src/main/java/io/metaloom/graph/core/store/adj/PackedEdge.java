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
 * neighbour, and direction, type and endpoints come from the relationship record.
 *
 * @see <a href="../../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> section 3
 */
public final class PackedEdge {

	public static final int DIR_OUT = 0;
	public static final int DIR_IN = 1;
	public static final int DIR_UNDIRECTED = 2;
	public static final int DIR_MATERIALISED = 3;

	public static final int MAX_TYPE = (1 << 14) - 1;
	public static final long MAX_LOCATION = (1L << 48) - 1;

	private static final int DIR_SHIFT = 62;
	private static final int TYPE_SHIFT = 48;
	private static final long LOCATION_MASK = MAX_LOCATION;

	/**
	 * The tombstone is the all-ones value. It is unreachable through the public constructors: a materialised entry
	 * (dir 3) always carries type 0, so {@code dir=3, type=MAX_TYPE} can never be produced.
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
		if (dir == DIR_MATERIALISED && typeId != 0) {
			throw new IllegalArgumentException(
				"A materialised entry carries its type in the relationship record and must use type 0");
		}
		if (typeId < 0 || typeId > MAX_TYPE) {
			throw new IllegalArgumentException("type id " + typeId + " does not fit the 14 bit adjacency field");
		}
		if (location < 0 || location > MAX_LOCATION) {
			throw new IllegalArgumentException("location out of range: " + location);
		}
		return ((long) dir << DIR_SHIFT) | ((long) typeId << TYPE_SHIFT) | location;
	}

	public static long ofNeighbour(int dir, int typeId, long neighbourId) {
		return pack(dir, typeId, ElementId.location(neighbourId));
	}

	public static long ofRelationship(long relationshipId) {
		return pack(DIR_MATERIALISED, 0, ElementId.location(relationshipId));
	}

	public static int dir(long edge) {
		return (int) (edge >>> DIR_SHIFT);
	}

	public static int typeId(long edge) {
		return (int) ((edge >>> TYPE_SHIFT) & MAX_TYPE);
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
