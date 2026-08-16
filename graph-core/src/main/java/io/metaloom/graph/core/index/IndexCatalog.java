package io.metaloom.graph.core.index;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorIndexState;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.catalog.Catalog;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.util.Layouts;

/**
 * The secondary indexes: label to nodes, and (property key, value) to nodes.
 * <p>
 * Both are B+trees. The root pointers live in slot 0 of the first {@code INDEX} segment, which is itself an index page
 * and therefore transactional: a root change made by a transaction is committed or discarded with everything else.
 * Putting them in the manifest instead would leave the index inconsistent after a crash, because the manifest is only
 * written at a checkpoint.
 * <p>
 * The property index is keyed on a hash of the value, so a lookup returns candidates that the caller verifies against
 * the stored property. That keeps arbitrary-length values out of a fixed-size key without ever returning a wrong
 * answer.
 *
 * @see <a href="../../../../../../../../spec/PLAN.md">spec/PLAN.md</a> Phase 7
 */
public final class IndexCatalog {

	private static final long CATALOG_MAGIC = 0x00584449_4C4DL;

	private static final int OFF_MAGIC = 0;
	private static final int OFF_LABEL_ROOT = 8;
	private static final int OFF_PROP_ROOT = 16;
	/**
	 * Unique-constraint key ids: a count, then that many ints.
	 * <p>
	 * Deliberately past offset 64. {@code Kind.INDEX} keeps its <b>generation at offset 30</b>, maintained by the slot
	 * allocator and invisible to this class, so anything written between 24 and 32 silently destroys it and every id
	 * naming this page becomes stale. The first version of this field sat at 28 and did exactly that.
	 */
	private static final int OFF_UNIQUE_COUNT = 64;
	private static final int OFF_UNIQUE_KEYS = 68;
	private static final int MAX_UNIQUE_KEYS = 256;

	/**
	 * Declared vector indexes: a count, then that many fixed-size entries.
	 * <p>
	 * Past the unique-key list, which reaches {@code 68 + 4 * 256 = 1092} when it is full. Rounded up to leave that
	 * list room to grow without either of them having to move — a field that moves is a format change, and this page
	 * has already cost the project one defect for being reasoned about too cleverly (see the offset-30 note above).
	 */
	private static final int OFF_VECTOR_COUNT = 1152;
	private static final int OFF_VECTOR_ENTRIES = 1156;
	private static final int VECTOR_ENTRY_BYTES = 64;
	private static final int MAX_VECTOR_INDEXES = 16;

	// Field offsets within one vector-index entry.
	private static final int VI_LABEL_ID = 0;
	private static final int VI_KEY_ID = 4;
	private static final int VI_DIMENSIONS = 8;
	private static final int VI_METRIC = 12;
	private static final int VI_STATE = 13;
	private static final int VI_M = 14;
	private static final int VI_EF_CONSTRUCTION = 16;
	private static final int VI_MODEL_ID = 20;
	private static final int VI_ENTRY_POINT = 24;
	private static final int VI_MAX_LEVEL = 32;
	private static final int VI_BUILT_COUNT = 40;

	private final SegmentSet segments;
	private final Catalog catalog;
	private final BTree tree;

	private long catalogPageId = ElementId.NULL;

	public IndexCatalog(SegmentSet segments, Catalog catalog) {
		this.segments = segments;
		this.catalog = catalog;
		this.tree = new BTree(segments);
		if (segments.segmentCount() > 0 && segments.isLive(firstSlotId())) {
			catalogPageId = firstSlotId();
		}
	}

	private long firstSlotId() {
		// The catalog page is always the very first INDEX allocation, so its slot is 0 of segment 0.
		int gen = segments.generationAt(0, 0);
		return gen == 0 ? ElementId.NULL : ElementId.encode(segments.kind(), 0, 0, gen);
	}

	/**
	 * Whether the catalog page exists and is readable.
	 * <p>
	 * {@code catalogPageId} is in-memory state, and {@link #ensureInitialised()} sets it inside a transaction that may
	 * then roll back — which frees the page underneath it and leaves the field naming a slot that is no longer
	 * allocated. So the field is a hint, not an authority: it is re-derived from the segment whenever it does not
	 * resolve, which turns "the index was created by a transaction that was discarded" back into "there is no index
	 * yet" instead of a {@code StaleIdException} on the next lookup.
	 */
	private boolean initialised() {
		if (!segments.isLive(catalogPageId)) {
			catalogPageId = firstSlotId();
			if (!segments.isLive(catalogPageId)) {
				catalogPageId = ElementId.NULL;
				return false;
			}
		}
		return segments.resolve(catalogPageId).get(Layouts.LONG, OFF_MAGIC) == CATALOG_MAGIC;
	}

	private void ensureInitialised() throws IOException {
		if (initialised()) {
			return;
		}
		long id = segments.allocate();
		if (ElementId.slot(id) != 0 || ElementId.segment(id) != 0) {
			throw new IllegalStateException("The index catalog page must be the first INDEX allocation, got "
				+ ElementId.toString(id));
		}
		catalogPageId = id;
		MemorySegment page = segments.mutable(id);
		page.set(Layouts.LONG, OFF_MAGIC, CATALOG_MAGIC);
		page.set(Layouts.LONG, OFF_LABEL_ROOT, tree.create());
		page.set(Layouts.LONG, OFF_PROP_ROOT, tree.create());
		segments.touched(id);
	}

	private long labelRoot() {
		return segments.resolve(catalogPageId).get(Layouts.LONG, OFF_LABEL_ROOT);
	}

	private void labelRoot(long rootId) {
		segments.mutable(catalogPageId).set(Layouts.LONG, OFF_LABEL_ROOT, rootId);
		segments.touched(catalogPageId);
	}

	private long propRoot() {
		return segments.resolve(catalogPageId).get(Layouts.LONG, OFF_PROP_ROOT);
	}

	private void propRoot(long rootId) {
		segments.mutable(catalogPageId).set(Layouts.LONG, OFF_PROP_ROOT, rootId);
		segments.touched(catalogPageId);
	}

	// ---------------------------------------------------------------- unique constraints

	/**
	 * Declares that no two live elements may share a value for this property key.
	 * <p>
	 * Enforced at write time by the same index that answers lookups, so a violation costs one probe rather than a
	 * scan. The caller checks the existing data first: declaring a constraint that is already violated is refused,
	 * because a constraint that was never true is worse than none — everything downstream is entitled to assume it.
	 */
	public void addUniqueConstraint(int keyId) throws IOException {
		ensureInitialised();
		int[] existing = uniqueKeys();
		for (int key : existing) {
			if (key == keyId) {
				return;
			}
		}
		if (existing.length >= MAX_UNIQUE_KEYS) {
			throw new IllegalStateException("At most " + MAX_UNIQUE_KEYS + " unique constraints are supported");
		}
		int count = existing.length;
		MemorySegment page = segments.mutable(catalogPageId);
		page.set(Layouts.INT, OFF_UNIQUE_KEYS + 4L * count, keyId);
		page.set(Layouts.INT, OFF_UNIQUE_COUNT, count + 1);
		segments.touched(catalogPageId);
	}

	public int[] uniqueKeys() {
		if (!initialised()) {
			return new int[0];
		}
		MemorySegment page = segments.resolve(catalogPageId);
		int count = page.get(Layouts.INT, OFF_UNIQUE_COUNT);
		if (count < 0 || count > MAX_UNIQUE_KEYS) {
			throw new io.metaloom.graph.core.api.PageCorruptException(
				"The index catalog declares " + count + " unique constraints, which is outside 0.." + MAX_UNIQUE_KEYS);
		}
		int[] keys = new int[count];
		for (int i = 0; i < count; i++) {
			keys[i] = page.get(Layouts.INT, OFF_UNIQUE_KEYS + 4L * i);
		}
		return keys;
	}

	public boolean isUnique(int keyId) {
		for (int key : uniqueKeys()) {
			if (key == keyId) {
				return true;
			}
		}
		return false;
	}

	/** Candidate ids already carrying this key and value, excluding one element. Empty when the key is not unique. */
	public List<Long> conflicts(int keyId, Object value, long excluding) {
		if (!initialised() || value == null) {
			return List.of();
		}
		List<Long> candidates = tree.scanPrefix(propRoot(), propertyKey(keyId, value));
		List<Long> conflicting = new java.util.ArrayList<>(1);
		for (long id : candidates) {
			if (id != excluding) {
				conflicting.add(id);
			}
		}
		return conflicting;
	}

	// ---------------------------------------------------------------- declared vector indexes

	/**
	 * Declares an approximate-nearest-neighbour index, and returns its ordinal.
	 * <p>
	 * The ordinal is how a vector record says which index owns it, in a field two bytes wide — so it is a position in
	 * this list rather than an id, and the list is append-only within the life of a declaration. Dropping an index
	 * clears its slot without moving the ones after it, because moving them would silently reassign every vector
	 * record that named one.
	 */
	public int addVectorIndex(VectorIndexDefinition definition, int labelId, int keyId, int modelId)
		throws IOException {
		ensureInitialised();
		List<VectorIndexDefinition> existing = vectorIndexes();
		for (VectorIndexDefinition other : existing) {
			if (other != null && other.covers(definition.label(), definition.key())) {
				throw new IllegalStateException("A vector index on :" + definition.label() + "." + definition.key()
					+ " already exists");
			}
		}
		int ord = -1;
		for (int i = 0; i < existing.size(); i++) {
			if (existing.get(i) == null) {
				ord = i;
				break;
			}
		}
		if (ord < 0) {
			if (existing.size() >= MAX_VECTOR_INDEXES) {
				throw new IllegalStateException("At most " + MAX_VECTOR_INDEXES + " vector indexes are supported");
			}
			ord = existing.size();
		}
		MemorySegment page = segments.mutable(catalogPageId);
		long base = OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES;
		page.set(Layouts.INT, base + VI_LABEL_ID, labelId);
		page.set(Layouts.INT, base + VI_KEY_ID, keyId);
		page.set(Layouts.INT, base + VI_DIMENSIONS, definition.dimensions());
		page.set(Layouts.BYTE, base + VI_METRIC, (byte) (definition.metric().ordinal() + 1));
		page.set(Layouts.BYTE, base + VI_STATE, (byte) (definition.state().ordinal() + 1));
		page.set(Layouts.BYTE, base + VI_M, (byte) definition.m());
		page.set(Layouts.INT, base + VI_EF_CONSTRUCTION, definition.efConstruction());
		page.set(Layouts.INT, base + VI_MODEL_ID, modelId);
		page.set(Layouts.LONG, base + VI_ENTRY_POINT, ElementId.NULL);
		page.set(Layouts.INT, base + VI_MAX_LEVEL, 0);
		page.set(Layouts.LONG, base + VI_BUILT_COUNT, 0L);
		if (ord >= existing.size()) {
			page.set(Layouts.INT, OFF_VECTOR_COUNT, ord + 1);
		}
		segments.touched(catalogPageId);
		return ord;
	}

	/**
	 * Every declared vector index by ordinal, with a null for a slot that has been dropped.
	 * <p>
	 * The nulls are the point: the position <i>is</i> the identity, so the list is returned with its holes rather than
	 * compacted. A caller wanting only the live ones filters.
	 */
	public List<VectorIndexDefinition> vectorIndexes() {
		if (!initialised()) {
			return List.of();
		}
		MemorySegment page = segments.resolve(catalogPageId);
		int count = page.get(Layouts.INT, OFF_VECTOR_COUNT);
		if (count < 0 || count > MAX_VECTOR_INDEXES) {
			throw new io.metaloom.graph.core.api.PageCorruptException("The index catalog declares " + count
				+ " vector indexes, which is outside 0.." + MAX_VECTOR_INDEXES);
		}
		List<VectorIndexDefinition> result = new java.util.ArrayList<>(count);
		for (int ord = 0; ord < count; ord++) {
			result.add(readVectorIndex(page, ord));
		}
		return result;
	}

	private VectorIndexDefinition readVectorIndex(MemorySegment page, int ord) {
		long base = OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES;
		int state = Byte.toUnsignedInt(page.get(Layouts.BYTE, base + VI_STATE));
		if (state == 0) {
			// A dropped slot, or one never used. Both read as "no index here", which is what a zeroed page means
			// everywhere else in this engine.
			return null;
		}
		int metric = Byte.toUnsignedInt(page.get(Layouts.BYTE, base + VI_METRIC));
		if (metric < 1 || metric > VectorMetric.values().length || state > VectorIndexState.values().length) {
			throw new io.metaloom.graph.core.api.PageCorruptException("Vector index " + ord
				+ " declares metric " + metric + " and state " + state + ", which are not values this build knows");
		}
		String label = catalog.label(page.get(Layouts.INT, base + VI_LABEL_ID));
		String key = catalog.key(page.get(Layouts.INT, base + VI_KEY_ID));
		int modelId = page.get(Layouts.INT, base + VI_MODEL_ID);
		return new VectorIndexDefinition(label, key,
			page.get(Layouts.INT, base + VI_DIMENSIONS),
			VectorMetric.values()[metric - 1],
			modelId == 0 ? "" : catalog.key(modelId),
			Byte.toUnsignedInt(page.get(Layouts.BYTE, base + VI_M)),
			page.get(Layouts.INT, base + VI_EF_CONSTRUCTION),
			VectorIndexState.values()[state - 1]);
	}

	/** The ordinal of the index covering this label and key, or -1. */
	public int vectorIndexOrdinal(String label, String key) {
		List<VectorIndexDefinition> declared = vectorIndexes();
		for (int ord = 0; ord < declared.size(); ord++) {
			VectorIndexDefinition definition = declared.get(ord);
			if (definition != null && definition.covers(label, key)) {
				return ord;
			}
		}
		return -1;
	}

	/** Moves a declaration into a new state, which is how a finished build becomes queryable. */
	public void vectorIndexState(int ord, VectorIndexState state) {
		requireDeclared(ord);
		MemorySegment page = segments.mutable(catalogPageId);
		page.set(Layouts.BYTE, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_STATE,
			(byte) (state.ordinal() + 1));
		segments.touched(catalogPageId);
	}

	/** The ANN graph's entry point, and the highest layer it appears in. Null until the first member is linked. */
	public long vectorEntryPoint(int ord) {
		requireDeclared(ord);
		return segments.resolve(catalogPageId)
			.get(Layouts.LONG, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_ENTRY_POINT);
	}

	public int vectorMaxLevel(int ord) {
		requireDeclared(ord);
		return segments.resolve(catalogPageId)
			.get(Layouts.INT, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_MAX_LEVEL);
	}

	public void vectorEntryPoint(int ord, long vectorId, int maxLevel) {
		requireDeclared(ord);
		long base = OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES;
		MemorySegment page = segments.mutable(catalogPageId);
		page.set(Layouts.LONG, base + VI_ENTRY_POINT, vectorId);
		page.set(Layouts.INT, base + VI_MAX_LEVEL, maxLevel);
		segments.touched(catalogPageId);
	}

	/** How many members the build has linked so far, so a BUILDING index can report progress rather than a shrug. */
	public long vectorBuiltCount(int ord) {
		requireDeclared(ord);
		return segments.resolve(catalogPageId)
			.get(Layouts.LONG, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_BUILT_COUNT);
	}

	public void vectorBuiltCount(int ord, long built) {
		requireDeclared(ord);
		segments.mutable(catalogPageId)
			.set(Layouts.LONG, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_BUILT_COUNT, built);
		segments.touched(catalogPageId);
	}

	/** Clears a declaration. The slot is left in place so no other index's ordinal changes. */
	public void removeVectorIndex(int ord) {
		requireDeclared(ord);
		MemorySegment page = segments.mutable(catalogPageId);
		long base = OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES;
		page.asSlice(base, VECTOR_ENTRY_BYTES).fill((byte) 0);
		segments.touched(catalogPageId);
	}

	private void requireDeclared(int ord) {
		if (!initialised() || ord < 0 || ord >= vectorIndexes().size() || vectorIndexes().get(ord) == null) {
			throw new IllegalArgumentException("No vector index with ordinal " + ord);
		}
	}

	// ---------------------------------------------------------------- maintenance hooks

	/**
	 * Indexes a new node under <b>every</b> label it carries.
	 * <p>
	 * The tree is keyed {@code labelId -> nodeId}, so several labels are several entries rather than a different
	 * structure. A node reachable through only its first label would make {@code MATCH (n:Second)} silently miss it.
	 */
	public void onNodeCreated(long nodeId, int[] labelIds, Map<Integer, Object> properties) throws IOException {
		ensureInitialised();
		if (labelIds != null) {
			for (int labelId : labelIds) {
				if (labelId != 0) {
					labelRoot(tree.put(labelRoot(), labelId, nodeId, nodeId));
				}
			}
		}
		if (properties != null) {
			for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
				propRoot(tree.put(propRoot(), propertyKey(entry.getKey(), entry.getValue()), nodeId, nodeId));
			}
		}
	}

	/** Removes the node from the index under every label it carried. Missing one leaves a dangling index entry. */
	public void onNodeDeleted(long nodeId, int[] labelIds, Map<Integer, Object> properties) throws IOException {
		if (!initialised()) {
			return;
		}
		if (labelIds != null) {
			for (int labelId : labelIds) {
				if (labelId != 0) {
					tree.remove(labelRoot(), labelId, nodeId);
				}
			}
		}
		if (properties != null) {
			for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
				tree.remove(propRoot(), propertyKey(entry.getKey(), entry.getValue()), nodeId);
			}
		}
	}

	/**
	 * Moves a node's label entries from one label set to the other.
	 *
	 * <p>
	 * Only the difference is touched. Removing and reinserting every entry would give the same answer, but it would
	 * dirty index pages nothing asked about, and a dirtied page is a byte run in the commit and an entry in every open
	 * reader's version chain — the cost of a relabelling would then depend on how many labels the node already had.
	 * </p>
	 *
	 * <p>
	 * Both directions are needed and neither is optional: a label left in the tree makes {@code MATCH (n:Gone)} return
	 * a node that no longer carries it, and a label missing from it makes {@code MATCH (n:Added)} silently miss one.
	 * </p>
	 */
	public void onLabelsChanged(long nodeId, int[] before, int[] after) throws IOException {
		ensureInitialised();
		for (int labelId : before) {
			if (labelId != 0 && !holds(after, labelId)) {
				tree.remove(labelRoot(), labelId, nodeId);
			}
		}
		for (int labelId : after) {
			if (labelId != 0 && !holds(before, labelId)) {
				labelRoot(tree.put(labelRoot(), labelId, nodeId, nodeId));
			}
		}
	}

	private static boolean holds(int[] labelIds, int labelId) {
		for (int held : labelIds) {
			if (held == labelId) {
				return true;
			}
		}
		return false;
	}

	public void onPropertiesChanged(long nodeId, Map<Integer, Object> before,
		Map<Integer, Object> after) throws IOException {
		ensureInitialised();
		for (Map.Entry<Integer, Object> entry : before.entrySet()) {
			Object now = after.get(entry.getKey());
			if (!valueEquals(now, entry.getValue())) {
				tree.remove(propRoot(), propertyKey(entry.getKey(), entry.getValue()), nodeId);
			}
		}
		for (Map.Entry<Integer, Object> entry : after.entrySet()) {
			Object was = before.get(entry.getKey());
			if (!valueEquals(was, entry.getValue())) {
				propRoot(tree.put(propRoot(), propertyKey(entry.getKey(), entry.getValue()), nodeId, nodeId));
			}
		}
	}

	// ---------------------------------------------------------------- queries

	public List<Long> nodesByLabel(String label) {
		if (!initialised()) {
			return List.of();
		}
		int labelId = catalog.lookupLabel(label);
		if (labelId == 0) {
			return List.of();
		}
		return tree.scanPrefix(labelRoot(), labelId);
	}

	/** Candidate nodes for a property value. The caller verifies each against the stored property. */
	public List<Long> nodesByProperty(String key, Object value) {
		if (!initialised()) {
			return List.of();
		}
		int keyId = catalog.lookupKey(key);
		if (keyId == 0) {
			return List.of();
		}
		return tree.scanPrefix(propRoot(), propertyKey(keyId, value));
	}

	public boolean isInitialised() {
		return initialised();
	}

	/**
	 * Frees every page of both trees and installs two fresh empty roots, returning the number of pages given back.
	 * <p>
	 * This is how the index is compacted. Relocating B+tree pages individually would mean fixing up a parent pointer, a
	 * leaf sibling pointer and possibly a root pointer for each one; rebuilding is both simpler and denser, and the
	 * only reason it is affordable is that index ids are internal and never handed out. The caller is responsible for
	 * re-inserting every node afterwards — a store whose index is reset and not refilled answers every indexed lookup
	 * with nothing.
	 */
	public int resetTrees() throws IOException {
		if (!initialised()) {
			return 0;
		}
		List<Long> pages = new java.util.ArrayList<>(tree.pages(labelRoot()));
		pages.addAll(tree.pages(propRoot()));
		for (long pageId : pages) {
			segments.free(pageId);
		}
		labelRoot(tree.create());
		propRoot(tree.create());
		return pages.size();
	}

	public long labelIndexSize() {
		return initialised() ? tree.size(labelRoot()) : 0;
	}

	public long propertyIndexSize() {
		return initialised() ? tree.size(propRoot()) : 0;
	}

	public BTree tree() {
		return tree;
	}

	/** The property tree root. Exposed for the read-budget test, which asserts against the tree's actual depth. */
	public long propertyRootForTest() {
		return propRoot();
	}

	public long labelRootForTest() {
		return labelRoot();
	}

	// ---------------------------------------------------------------- key derivation

	/**
	 * The 64 bit index key for a property: the key id in the high bits, a value hash in the low bits. Collisions are
	 * safe because the caller verifies candidates.
	 */
	static long propertyKey(int keyId, Object value) {
		return ((long) keyId << 32) | (valueHash(value) & 0xFFFFFFFFL);
	}

	/**
	 * A hash that depends only on the <b>content</b> of the value, never on the identity of the object holding it.
	 * <p>
	 * That is the whole contract, and every branch below exists because some type breaks it by default. The numeric
	 * branches normalise across the widths, so a property written as {@code 1} and searched for as {@code 1L} agrees.
	 * The array branches exist because {@code Object::hashCode} on an array is the identity hash: a {@code float[]}
	 * used to land in the {@code default} case, so every vector property was indexed under a key nothing could
	 * reproduce — the lookup missed the node, and the delete missed the entry and left it dangling. A list gets the
	 * same treatment recursively rather than {@code List::hashCode}, because a list's hash delegates to its elements
	 * and so inherits the problem from any array inside it.
	 * <p>
	 * Vectors are hashed by content rather than skipped at the maintenance hooks, which is the narrow half of the
	 * decision in <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>: it makes an exact-equality
	 * lookup on a vector work and index maintenance sound. It is not nearest-neighbour search and never becomes it —
	 * that is a different structure, not a differently-keyed tree.
	 */
	static int valueHash(Object value) {
		if (value == null) {
			return 0;
		}
		return switch (value) {
			case String s -> hash(s.getBytes(StandardCharsets.UTF_8));
			case byte[] b -> hash(b);
			case Byte b -> Long.hashCode(b.longValue());
			case Short s -> Long.hashCode(s.longValue());
			case Integer i -> Long.hashCode(i.longValue());
			case Long l -> Long.hashCode(l);
			case Boolean b -> b ? 1 : 0;
			case Float f -> Double.hashCode(f.doubleValue());
			case Double d -> Double.hashCode(d);
			case Instant i -> Long.hashCode(i.getEpochSecond() * 1_000_000L + i.getNano() / 1000L);
			case UUID u -> Long.hashCode(u.getMostSignificantBits() ^ u.getLeastSignificantBits());
			// Arrays.hashCode(float[]) compares the same bits Arrays.equals does, so it agrees with the
			// Objects.deepEquals verification the caller applies to every candidate.
			case float[] v -> Arrays.hashCode(v);
			case List<?> list -> listHash(list);
			// Any other array shape — a value type added later, or one nested in a list. deepHashCode dispatches on
			// the component type, so this keeps the next array-shaped value from reintroducing the identity hash.
			default -> value.getClass().isArray() ? Arrays.deepHashCode(new Object[] { value }) : value.hashCode();
		};
	}

	/**
	 * Value equality as the index means it, and the partner of {@link #valueHash}: the two must agree, or a candidate
	 * the tree found is thrown away by the verification that was supposed to confirm it.
	 * <p>
	 * {@link java.util.Objects#deepEquals} is deep for an array and shallow for everything else, so a list holding a
	 * {@code float[]} compares its elements with {@code Object::equals} and reports two identical embeddings as
	 * different. That is the same defect as the identity hash, one level down, and it has the same symptom: a lookup
	 * that finds the entry and then silently discards the node it names.
	 * <p>
	 * The integer widths are normalised here for the same reason they are in {@link #valueHash}: the store widens
	 * every stored integer to a {@code long}, so a list written as {@code [1, 2]} comes back as a list of longs and
	 * would otherwise never equal the one the caller searched with.
	 */
	public static boolean valueEquals(Object a, Object b) {
		if (a instanceof List<?> listA && b instanceof List<?> listB) {
			if (listA.size() != listB.size()) {
				return false;
			}
			java.util.Iterator<?> itA = listA.iterator();
			java.util.Iterator<?> itB = listB.iterator();
			while (itA.hasNext()) {
				if (!valueEquals(itA.next(), itB.next())) {
					return false;
				}
			}
			return true;
		}
		return java.util.Objects.deepEquals(widen(a), widen(b));
	}

	private static Object widen(Object value) {
		if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
			return ((Number) value).longValue();
		}
		return value;
	}

	/** {@code List::hashCode}'s mixing, with every element routed back through {@link #valueHash}. */
	private static int listHash(List<?> list) {
		int h = 1;
		for (Object element : list) {
			h = 31 * h + valueHash(element);
		}
		return h;
	}

	private static int hash(byte[] data) {
		int h = 0x811C9DC5;
		for (byte b : data) {
			h = (h ^ (b & 0xFF)) * 0x01000193;
		}
		return h;
	}
}
