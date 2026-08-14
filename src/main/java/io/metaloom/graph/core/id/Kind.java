package io.metaloom.graph.core.id;

/**
 * Record families. The kind occupies the top 4 bits of every {@link ElementId} and selects the segment directory, the
 * record stride and the generation field offset.
 *
 * @see <a href="../../../../../../../../spec/ID_ENCODING.md">spec/ID_ENCODING.md</a> section 2.1
 */
public enum Kind {

	NULL(0, "null", 0, 0),
	NODE(1, "node", 64, 4),
	REL(2, "rel", 64, 20),
	// 512 is ADJ_CELL_BYTES; the constant cannot be referenced from an enum constant declaration.
	ADJ(3, "adj", 512, 30),
	PROP(4, "prop", 32, 6),
	BLOB(5, "blob", -1, 20),
	INDEX(6, "index", -1, 30),
	UNDO(7, "undo", 64, 44);

	/** Stride marker meaning "one page", resolved by the {@link io.metaloom.graph.core.segment.Geometry}. */
	public static final int PAGE_SIZED = -1;

	/**
	 * The allocation unit of an adjacency block.
	 * <p>
	 * An adjacency block is a <b>run of consecutive cells</b>, not a page. It starts at one cell and doubles as it
	 * fills, up to a whole page. Before this, the smallest block was a page: a node with three edges — one more than
	 * the two that fit inline — cost 8 KiB. On a random graph of mean degree 4 that is 76 percent of all nodes, and it
	 * measured as 59 of the 61 GiB in spec/reports/SCALE.md.
	 * <p>
	 * 512 bytes leaves 448 for payload after the 64 byte header, which is 56 raw entries and several hundred
	 * compressed ones — beyond the tail of any ordinary degree distribution, while still costing an eighth of a page.
	 */
	public static final int ADJ_CELL_BYTES = 512;

	private static final Kind[] BY_CODE = new Kind[16];

	static {
		for (Kind k : values()) {
			BY_CODE[k.code] = k;
		}
	}

	private final int code;
	private final String dirName;
	private final int strideSpec;
	private final int genOffset;

	Kind(int code, String dirName, int strideSpec, int genOffset) {
		this.code = code;
		this.dirName = dirName;
		this.strideSpec = strideSpec;
		this.genOffset = genOffset;
	}

	public int code() {
		return code;
	}

	public String dirName() {
		return dirName;
	}

	/** Either a byte count, or {@link #PAGE_SIZED}. */
	public int strideSpec() {
		return strideSpec;
	}

	/**
	 * Byte offset of the unsigned 16 bit generation field within a record of this kind. Every kind stores its
	 * generation at a fixed offset so that the slot allocator can maintain it without knowing the record layout.
	 */
	public int genOffset() {
		return genOffset;
	}

	public static Kind byCode(int code) {
		if (code < 0 || code > 15) {
			throw new IllegalArgumentException("Kind code out of range: " + code);
		}
		Kind kind = BY_CODE[code];
		if (kind == null) {
			throw new IllegalArgumentException("Reserved kind code: " + code);
		}
		return kind;
	}
}
