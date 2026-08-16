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
	UNDO(7, "undo", 64, 44),
	/**
	 * Fixed-size vectors, one record per indexed vector, with its ANN links in the same record.
	 * <p>
	 * The only kind whose stride is <b>not</b> a property of the format: it is chosen when the store is created, from
	 * the vector dimension the application declares, and recorded in the manifest. A store that has never asked for
	 * vector storage has a stride of zero here, and then this kind does not exist — no segment set, no directory, no
	 * geometry ([VECTOR.md](../../../../../../../../spec/VECTOR.md)).
	 * <p>
	 * Generation at 30, matching {@code ADJ} and {@code INDEX}. Bytes 24 to 32 of a vector record are therefore not
	 * available to it, exactly as they are not available on an index page — see the trap in
	 * {@code IndexCatalog}, which cost a defect.
	 */
	VEC(8, "vec", -2, 30);

	/** Stride marker meaning "one page", resolved by the {@link io.metaloom.graph.core.segment.Geometry}. */
	public static final int PAGE_SIZED = -1;

	/**
	 * Stride marker meaning "decided when the store is created and recorded in the manifest", resolved by the
	 * {@link io.metaloom.graph.core.segment.Geometry} from a value it is handed. A kind marked this way and given a
	 * stride of zero is <b>absent</b> rather than broken.
	 */
	public static final int DECLARED = -2;

	/**
	 * How many kind codes the id encoding can express: {@code ElementId} gives the kind 4 bits.
	 * <p>
	 * Anything sized per kind must use this rather than "however many constants there are today". Two arrays were
	 * sized at 8 by hand — {@code Geometry}'s layout tables and {@code IoCounters}' per-kind counters — and a ninth
	 * kind was therefore not a compile error: {@code Geometry} skipped it silently and then threw
	 * {@code ArrayIndexOutOfBoundsException} from the read path, which is the worst of the available failure modes.
	 */
	public static final int MAX_KINDS = 16;

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

	private static final Kind[] BY_CODE = new Kind[MAX_KINDS];

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
		if (code < 0 || code >= MAX_KINDS) {
			throw new IllegalArgumentException("Kind code out of range: " + code);
		}
		Kind kind = BY_CODE[code];
		if (kind == null) {
			throw new IllegalArgumentException("Reserved kind code: " + code);
		}
		return kind;
	}
}
