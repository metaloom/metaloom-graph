package io.metaloom.graph.core.id;

/**
 * The 64 bit element id: {@code kind:4 | segment:24 | slot:24 | gen:12}.
 * <p>
 * Ids are plain {@code long} values everywhere in the engine; this class holds only the encode and decode arithmetic so
 * that the shift constants live in exactly one place. There is deliberately no wrapper object: an id is stored in
 * adjacency entries, endpoint fields and index keys, and it must cost 8 bytes and no allocation.
 *
 * @see <a href="../../../../../../../../spec/ID_ENCODING.md">spec/ID_ENCODING.md</a>
 */
public final class ElementId {

	public static final long NULL = 0L;

	public static final int KIND_BITS = 4;
	public static final int SEGMENT_BITS = 24;
	public static final int SLOT_BITS = 24;
	public static final int GEN_BITS = 12;

	public static final int GEN_SHIFT = 0;
	public static final int SLOT_SHIFT = GEN_BITS;
	public static final int SEGMENT_SHIFT = SLOT_SHIFT + SLOT_BITS;
	public static final int KIND_SHIFT = SEGMENT_SHIFT + SEGMENT_BITS;

	public static final int MAX_SEGMENT = (1 << SEGMENT_BITS) - 1;
	public static final int MAX_SLOT = (1 << SLOT_BITS) - 1;
	public static final int MAX_GEN = (1 << GEN_BITS) - 1;

	private static final long SEGMENT_MASK = (1L << SEGMENT_BITS) - 1;
	private static final long SLOT_MASK = (1L << SLOT_BITS) - 1;
	private static final long GEN_MASK = (1L << GEN_BITS) - 1;

	private ElementId() {
	}

	public static long encode(Kind kind, int segment, int slot, int gen) {
		return encode(kind.code(), segment, slot, gen);
	}

	public static long encode(int kindCode, int segment, int slot, int gen) {
		if (kindCode < 0 || kindCode > 15) {
			throw new IllegalArgumentException("kind out of range: " + kindCode);
		}
		if (segment < 0 || segment > MAX_SEGMENT) {
			throw new IllegalArgumentException("segment out of range: " + segment);
		}
		if (slot < 0 || slot > MAX_SLOT) {
			throw new IllegalArgumentException("slot out of range: " + slot);
		}
		if (gen < 0 || gen > MAX_GEN) {
			throw new IllegalArgumentException("gen out of range: " + gen);
		}
		return ((long) kindCode << KIND_SHIFT)
			| ((long) segment << SEGMENT_SHIFT)
			| ((long) slot << SLOT_SHIFT)
			| gen;
	}

	public static int kindCode(long id) {
		return (int) (id >>> KIND_SHIFT);
	}

	public static Kind kind(long id) {
		return Kind.byCode(kindCode(id));
	}

	public static int segment(long id) {
		return (int) ((id >>> SEGMENT_SHIFT) & SEGMENT_MASK);
	}

	public static int slot(long id) {
		return (int) ((id >>> SLOT_SHIFT) & SLOT_MASK);
	}

	public static int gen(long id) {
		return (int) (id & GEN_MASK);
	}

	public static boolean isNull(long id) {
		return id == NULL;
	}

	/** Returns the id with its generation replaced. Used when rebuilding a full id from a stored record. */
	public static long withGen(long id, int gen) {
		return (id & ~GEN_MASK) | (gen & GEN_MASK);
	}

	/** The {@code segment:slot} pair without kind and generation, as used by packed adjacency entries (48 bits). */
	public static long location(long id) {
		return (id >>> SLOT_SHIFT) & ((1L << (SEGMENT_BITS + SLOT_BITS)) - 1);
	}

	/** Rebuilds an id from a 48 bit location, with generation 0 (which never matches a live record). */
	public static long fromLocation(Kind kind, long location) {
		return ((long) kind.code() << KIND_SHIFT) | (location << SLOT_SHIFT);
	}

	public static void expect(long id, Kind kind) {
		if (kindCode(id) != kind.code()) {
			throw new IllegalArgumentException(
				"Expected a " + kind + " id but got " + toString(id));
		}
	}

	public static String toString(long id) {
		if (id == NULL) {
			return "NULL";
		}
		Kind kind;
		try {
			kind = kind(id);
		} catch (IllegalArgumentException e) {
			return "0x" + Long.toHexString(id) + "(invalid kind)";
		}
		return kind + "[seg=" + segment(id) + ",slot=" + slot(id) + ",gen=" + gen(id) + ",raw=0x"
			+ String.format("%016x", id) + "]";
	}
}
