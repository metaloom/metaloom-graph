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
import io.metaloom.graph.core.api.TransactionConflictException;
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

	/**
	 * The composite {@code (label, key)} index: a root pointer, a count, then that many fixed-size declarations.
	 * <p>
	 * Past the vector list, which reaches {@code 1156 + 64 * 16 = 2180} when it is full, and the whole region ends at
	 * {@code 2320 + 16 * 64 = 3344} — inside the smallest page this engine allows, which is 4 KiB. The same rule as
	 * everywhere else on this page applies: a field that moves is a format change, so each list is given room to grow
	 * rather than packed against the next one.
	 * <p>
	 * A zeroed region reads as "no composite index is declared", which is what an older store's catalog page holds and
	 * is exactly the right answer for one. The root is {@code NULL} until the first declaration, because a store that
	 * declares none must not pay a page for a tree nothing writes to.
	 */
	private static final int OFF_COMPOSITE_ROOT = 2304;
	private static final int OFF_COMPOSITE_COUNT = 2312;
	private static final int OFF_COMPOSITE_ENTRIES = 2320;
	private static final int COMPOSITE_ENTRY_BYTES = 16;
	private static final int MAX_COMPOSITE_INDEXES = 64;

	// Field offsets within one composite-index declaration.
	private static final int CI_LABEL_ID = 0;
	private static final int CI_KEY_ID = 4;
	private static final int CI_FLAGS = 8;

	/** Bit 0 of {@code CI_FLAGS}: the declaration is also a label-scoped unique constraint. */
	private static final int CI_FLAG_UNIQUE = 1;

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

	public IndexCatalog(SegmentSet segments, Catalog catalog) {
		this.segments = segments;
		this.catalog = catalog;
		this.tree = new BTree(segments);
	}

	/**
	 * The catalog page: remembered once it is committed, derived until then.
	 *
	 * <p>
	 * It used to be a plain field, cleared and refreshed by {@link #initialised()} whenever it stopped resolving, so
	 * that a rolled-back creation turned back into "there is no index yet" instead of a stale id. That is safe for one
	 * writer and a hazard for several. The answer is <em>per transaction</em> — a page one writer has created but not
	 * committed is live for it and absent for everybody else — so a field written from inside an uncommitted
	 * transaction hands another thread an id it cannot resolve, and a field cleared by one thread leaves another
	 * holding {@link ElementId#NULL} in the middle of a lookup.
	 * </p>
	 *
	 * <p>
	 * The rule that makes it safe is that only a <b>committed</b> id is ever published here — see
	 * {@link SegmentSet#isLiveInMapping} — so the cache can never name something a concurrent reader cannot see. Until
	 * then it stays null and the id is derived, which costs a generation read; caching matters because every indexed
	 * lookup goes through the catalog and the read budgets count what a lookup touches.
	 * </p>
	 */
	private volatile long catalogPageId = ElementId.NULL;

	private long catalogPage() {
		long cached = catalogPageId;
		return ElementId.isNull(cached) ? firstSlotId() : cached;
	}

	private long firstSlotId() {
		// The catalog page is always the very first INDEX allocation, so its slot is 0 of segment 0.
		int gen = segments.generationAt(0, 0);
		return gen == 0 ? ElementId.NULL : ElementId.encode(segments.kind(), 0, 0, gen);
	}

	/**
	 * Whether the catalog page exists and is readable.
	 * <p>
	 * {@code catalogPage()} is in-memory state, and {@link #ensureInitialised()} sets it inside a transaction that may
	 * then roll back — which frees the page underneath it and leaves the field naming a slot that is no longer
	 * allocated. So the field is a hint, not an authority: it is re-derived from the segment whenever it does not
	 * resolve, which turns "the index was created by a transaction that was discarded" back into "there is no index
	 * yet" instead of a {@code StaleIdException} on the next lookup.
	 */
	private boolean initialised() {
		if (segments.segmentCount() == 0) {
			return false;
		}
		long id = catalogPage();
		if (ElementId.isNull(id) || !segments.isLive(id)) {
			// The cached id does not resolve for this transaction: either it was never committed, or this transaction
			// is older than the commit that made it. Re-derive before giving up.
			id = firstSlotId();
			if (ElementId.isNull(id) || !segments.isLive(id)) {
				return false;
			}
		}
		if (segments.resolve(id).get(Layouts.LONG, OFF_MAGIC) != CATALOG_MAGIC) {
			return false;
		}
		if (catalogPageId != id && segments.isLiveInMapping(id)) {
			catalogPageId = id;
		}
		return true;
	}

	private void ensureInitialised() throws IOException {
		if (initialised()) {
			return;
		}
		// Asked for by position rather than allocated and checked. The catalog has to be at INDEX (0, 0) because that
		// is where a reopen looks for it, and a null answer means another transaction is building it right now or has
		// already built it since this one's snapshot — a race this transaction has lost, not a broken invariant.
		long id = segments.allocateAt(0, 0);
		if (ElementId.isNull(id)) {
			throw new TransactionConflictException(0,
				"the index catalog page at INDEX (0, 0) is being created by another transaction");
		}
		MemorySegment page = segments.mutable(id);
		page.set(Layouts.LONG, OFF_MAGIC, CATALOG_MAGIC);
		page.set(Layouts.LONG, OFF_LABEL_ROOT, tree.create());
		page.set(Layouts.LONG, OFF_PROP_ROOT, tree.create());
		segments.touched(id);
	}

	private long labelRoot() {
		return segments.resolve(catalogPage()).get(Layouts.LONG, OFF_LABEL_ROOT);
	}

	private void labelRoot(long rootId) {
		segments.mutable(catalogPage()).set(Layouts.LONG, OFF_LABEL_ROOT, rootId);
		segments.touched(catalogPage());
	}

	/**
	 * Inserts into one of the trees and records its root only if the insert moved it.
	 *
	 * <p>
	 * A root moves only when the root itself splits, which is once per level over the life of a tree. Writing the
	 * unchanged id back on every insert cost each writing transaction a copy of the whole catalog page into its overlay
	 * and a diff of it at commit, for bytes that came out identical — and made the one page every indexed write shares
	 * a page every indexed write had dirtied.
	 * </p>
	 */
	private void putLabel(int labelId, long nodeId) throws IOException {
		long root = labelRoot();
		long moved = tree.put(root, labelId, nodeId, nodeId);
		if (moved != root) {
			labelRoot(moved);
		}
	}

	private void putProperty(int keyId, Object value, long nodeId) throws IOException {
		long root = propRoot();
		long moved = tree.put(root, propertyKey(keyId, value), nodeId, nodeId);
		if (moved != root) {
			propRoot(moved);
		}
	}

	private long propRoot() {
		return segments.resolve(catalogPage()).get(Layouts.LONG, OFF_PROP_ROOT);
	}

	private void propRoot(long rootId) {
		segments.mutable(catalogPage()).set(Layouts.LONG, OFF_PROP_ROOT, rootId);
		segments.touched(catalogPage());
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
		MemorySegment page = segments.mutable(catalogPage());
		page.set(Layouts.INT, OFF_UNIQUE_KEYS + 4L * count, keyId);
		page.set(Layouts.INT, OFF_UNIQUE_COUNT, count + 1);
		segments.touched(catalogPage());
	}

	public int[] uniqueKeys() {
		if (!initialised()) {
			return new int[0];
		}
		MemorySegment page = segments.resolve(catalogPage());
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

	/**
	 * Candidate ids already carrying this key and value, excluding one element — and a claim on the leaves the value
	 * would be inserted into. Write transactions only.
	 *
	 * <p>
	 * The claim is what makes the check hold between concurrent writers; {@link BTree#claimPrefix} has the case it
	 * closes. It is skipped when the excluded element is itself a candidate: that element already holds an entry
	 * under this value, so the write calling this inserts nothing here and has nothing to race for.
	 * </p>
	 */
	public List<Long> conflicts(int keyId, Object value, long excluding) {
		if (!initialised() || value == null) {
			return List.of();
		}
		long key = propertyKey(keyId, value);
		return claimed(propRoot(), key, tree.scanPrefix(propRoot(), key), excluding);
	}

	private List<Long> claimed(long rootId, long key, List<Long> candidates, long excluding) {
		List<Long> conflicting = new java.util.ArrayList<>(1);
		boolean held = false;
		for (long id : candidates) {
			if (id != excluding) {
				conflicting.add(id);
			} else {
				held = true;
			}
		}
		if (!held) {
			tree.claimPrefix(rootId, key);
		}
		return conflicting;
	}

	// ---------------------------------------------------------------- declared composite indexes

	/**
	 * One declared composite index, in the dictionary ids the maintenance hooks work in.
	 *
	 * @param labelId
	 *            the label a node must carry for its property to be indexed here
	 * @param keyId
	 *            the property key
	 * @param unique
	 *            whether the declaration is also a <b>label-scoped</b> unique constraint
	 */
	public record Composite(int labelId, int keyId, boolean unique) {
	}

	private long compositeRoot() {
		return segments.resolve(catalogPage()).get(Layouts.LONG, OFF_COMPOSITE_ROOT);
	}

	private void compositeRoot(long rootId) {
		segments.mutable(catalogPage()).set(Layouts.LONG, OFF_COMPOSITE_ROOT, rootId);
		segments.touched(catalogPage());
	}

	/**
	 * Declares an index over {@code (label, key)}, and reports whether this call is what declared it.
	 * <p>
	 * Unlike the two implicit trees, a composite index is <b>declared rather than maintained for everything</b>: there
	 * are {@code labels x keys} possible pairs, and indexing them all would multiply the write cost of every node by
	 * the size of its label set. So the hooks consult this list, and a pair that is not on it costs nothing at all.
	 * <p>
	 * Re-declaring an existing pair is not an error and returns {@code false}, which is what lets the caller skip the
	 * backfill scan: the entries are already there. Asking for uniqueness on a pair declared without it upgrades the
	 * declaration in place — the entries a unique check probes are the same ones.
	 *
	 * @return true if the pair was newly declared, so the caller still owes it a backfill
	 */
	public boolean addCompositeIndex(int labelId, int keyId, boolean unique) throws IOException {
		if (labelId == 0 || keyId == 0) {
			throw new IllegalArgumentException("A composite index needs an interned label and key, got "
				+ labelId + " and " + keyId);
		}
		ensureInitialised();
		List<Composite> existing = composites();
		for (int i = 0; i < existing.size(); i++) {
			Composite declared = existing.get(i);
			if (declared.labelId() == labelId && declared.keyId() == keyId) {
				if (unique && !declared.unique()) {
					MemorySegment page = segments.mutable(catalogPage());
					page.set(Layouts.BYTE, OFF_COMPOSITE_ENTRIES + (long) i * COMPOSITE_ENTRY_BYTES + CI_FLAGS,
						(byte) CI_FLAG_UNIQUE);
					segments.touched(catalogPage());
				}
				return false;
			}
		}
		if (existing.size() >= MAX_COMPOSITE_INDEXES) {
			throw new IllegalStateException("At most " + MAX_COMPOSITE_INDEXES + " composite indexes are supported");
		}
		// The tree is created with the first declaration rather than with the catalog page: a store that declares none
		// must not pay a page for a tree nothing ever writes to.
		if (ElementId.isNull(compositeRoot())) {
			compositeRoot(tree.create());
		}
		MemorySegment page = segments.mutable(catalogPage());
		long base = OFF_COMPOSITE_ENTRIES + (long) existing.size() * COMPOSITE_ENTRY_BYTES;
		page.set(Layouts.INT, base + CI_LABEL_ID, labelId);
		page.set(Layouts.INT, base + CI_KEY_ID, keyId);
		page.set(Layouts.BYTE, base + CI_FLAGS, (byte) (unique ? CI_FLAG_UNIQUE : 0));
		page.set(Layouts.INT, OFF_COMPOSITE_COUNT, existing.size() + 1);
		segments.touched(catalogPage());
		return true;
	}

	/**
	 * Removes a declaration, and reports whether there was one.
	 * <p>
	 * <b>The list compacts</b>: everything after the removed entry shifts down and the count drops, so the declarations
	 * keep the order they were made in. That is the opposite of what the vector list does, and the difference is what
	 * a position <i>means</i>. A vector index's ordinal is its identity — every {@code VEC} record names it — so moving
	 * one would silently reassign its members, which is why that list keeps its holes instead. Nothing anywhere names a
	 * composite index by position: its entries are keyed on {@code mix64(labelId, keyId, valueHash)}, so this list is
	 * free to be dense, and staying in declaration order is worth the memmove of at most a kilobyte on one page.
	 * <p>
	 * When the last declaration goes, so does the tree — every page freed and the root back to {@code NULL}, which is
	 * where it started. A store that has dropped its only composite index is indistinguishable from one that never
	 * declared it, which is the state the rest of this class already handles.
	 * <p>
	 * <b>The caller removes the entries first.</b> This only edits the declaration; a call that skipped the entry sweep
	 * would leave every entry of that pair in the tree with nothing left to say which pair they belonged to.
	 */
	public boolean removeCompositeIndex(int labelId, int keyId) throws IOException {
		if (!initialised()) {
			return false;
		}
		List<Composite> existing = composites();
		int at = -1;
		for (int i = 0; i < existing.size(); i++) {
			if (existing.get(i).labelId() == labelId && existing.get(i).keyId() == keyId) {
				at = i;
				break;
			}
		}
		if (at < 0) {
			return false;
		}
		MemorySegment page = segments.mutable(catalogPage());
		int last = existing.size() - 1;
		for (int i = at; i < last; i++) {
			Composite moved = existing.get(i + 1);
			long base = OFF_COMPOSITE_ENTRIES + (long) i * COMPOSITE_ENTRY_BYTES;
			page.set(Layouts.INT, base + CI_LABEL_ID, moved.labelId());
			page.set(Layouts.INT, base + CI_KEY_ID, moved.keyId());
			page.set(Layouts.BYTE, base + CI_FLAGS, (byte) (moved.unique() ? CI_FLAG_UNIQUE : 0));
		}
		page.asSlice(OFF_COMPOSITE_ENTRIES + (long) last * COMPOSITE_ENTRY_BYTES, COMPOSITE_ENTRY_BYTES)
			.fill((byte) 0);
		page.set(Layouts.INT, OFF_COMPOSITE_COUNT, last);
		segments.touched(catalogPage());
		if (last == 0 && !ElementId.isNull(compositeRoot())) {
			for (long pageId : tree.pages(compositeRoot())) {
				segments.free(pageId);
			}
			compositeRoot(ElementId.NULL);
		}
		return true;
	}

	/** Removes one composite entry, which is how a dropped declaration gives its entries back. */
	public void removeCompositeEntry(long nodeId, int labelId, int keyId, Object value) {
		if (initialised()) {
			compositeRemove(nodeId, labelId, keyId, value);
		}
	}

	/**
	 * Every declared composite index, in the ids the hooks work in.
	 * <p>
	 * Empty is the common case and the one that has to stay cheap, because every maintenance hook consults this list.
	 */
	public List<Composite> composites() {
		if (!initialised()) {
			return List.of();
		}
		MemorySegment page = segments.resolve(catalogPage());
		int count = page.get(Layouts.INT, OFF_COMPOSITE_COUNT);
		if (count == 0) {
			return List.of();
		}
		if (count < 0 || count > MAX_COMPOSITE_INDEXES) {
			throw new io.metaloom.graph.core.api.PageCorruptException("The index catalog declares " + count
				+ " composite indexes, which is outside 0.." + MAX_COMPOSITE_INDEXES);
		}
		List<Composite> declared = new java.util.ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			long base = OFF_COMPOSITE_ENTRIES + (long) i * COMPOSITE_ENTRY_BYTES;
			declared.add(new Composite(page.get(Layouts.INT, base + CI_LABEL_ID),
				page.get(Layouts.INT, base + CI_KEY_ID),
				(page.get(Layouts.BYTE, base + CI_FLAGS) & CI_FLAG_UNIQUE) != 0));
		}
		return declared;
	}

	/** Every declared composite index by name. */
	public List<io.metaloom.graph.core.api.CompositeIndex> compositeIndexes() {
		List<Composite> declared = composites();
		List<io.metaloom.graph.core.api.CompositeIndex> named = new java.util.ArrayList<>(declared.size());
		for (Composite composite : declared) {
			named.add(new io.metaloom.graph.core.api.CompositeIndex(catalog.label(composite.labelId()),
				catalog.key(composite.keyId()), composite.unique()));
		}
		return named;
	}

	public boolean hasComposite(int labelId, int keyId) {
		for (Composite declared : composites()) {
			if (declared.labelId() == labelId && declared.keyId() == keyId) {
				return true;
			}
		}
		return false;
	}

	/** Whether any declared composite index over this label is also a unique constraint. */
	public boolean hasUniqueComposite(int labelId) {
		for (Composite declared : composites()) {
			if (declared.unique() && declared.labelId() == labelId) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Candidate nodes carrying the label and the value, in one probe.
	 * <p>
	 * Candidates, not answers: the key is a hash of the triple, so the caller verifies both the label and the value
	 * against the stored record — with {@link #valueEquals}, which is the partner of {@link #valueHash}, and never with
	 * {@code Objects.deepEquals} directly.
	 */
	public List<Long> nodesByLabelAndProperty(int labelId, int keyId, Object value) {
		if (!initialised() || value == null || ElementId.isNull(compositeRoot())) {
			return List.of();
		}
		return tree.scanPrefix(compositeRoot(), compositeKey(labelId, keyId, value));
	}

	/** The same probe by name, which is what a test and the planner-facing store method use. */
	public List<Long> nodesByLabelAndProperty(String label, String key, Object value) {
		int labelId = catalog.lookupLabel(label);
		int keyId = catalog.lookupKey(key);
		if (labelId == 0 || keyId == 0) {
			return List.of();
		}
		return nodesByLabelAndProperty(labelId, keyId, value);
	}

	/** Candidates for a label-scoped unique check, excluding one element, with the claim {@link #conflicts} makes. */
	public List<Long> compositeConflicts(int labelId, int keyId, Object value, long excluding) {
		if (!initialised() || value == null || ElementId.isNull(compositeRoot())) {
			return List.of();
		}
		long key = compositeKey(labelId, keyId, value);
		return claimed(compositeRoot(), key, tree.scanPrefix(compositeRoot(), key), excluding);
	}

	/**
	 * Inserts one composite entry, which is how a declaration backfills the nodes that already exist.
	 * <p>
	 * Idempotent: the key is {@code (mix64(label, key, value), nodeId)} and {@code BTree.put} replaces an identical
	 * key, so a backfill that runs twice leaves one entry rather than two.
	 */
	public void addCompositeEntry(long nodeId, int labelId, int keyId, Object value) throws IOException {
		ensureInitialised();
		compositePut(nodeId, labelId, keyId, value);
	}

	private void compositePut(long nodeId, int labelId, int keyId, Object value) throws IOException {
		long root = compositeRoot();
		long moved = tree.put(root, compositeKey(labelId, keyId, value), nodeId, nodeId);
		if (moved != root) {
			compositeRoot(moved);
		}
	}

	private void compositeRemove(long nodeId, int labelId, int keyId, Object value) {
		if (!ElementId.isNull(compositeRoot())) {
			tree.remove(compositeRoot(), compositeKey(labelId, keyId, value), nodeId);
		}
	}

	public long compositeIndexSize() {
		return initialised() && !ElementId.isNull(compositeRoot()) ? tree.size(compositeRoot()) : 0;
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
		MemorySegment page = segments.mutable(catalogPage());
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
		segments.touched(catalogPage());
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
		MemorySegment page = segments.resolve(catalogPage());
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
		MemorySegment page = segments.mutable(catalogPage());
		page.set(Layouts.BYTE, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_STATE,
			(byte) (state.ordinal() + 1));
		segments.touched(catalogPage());
	}

	/** The ANN graph's entry point, and the highest layer it appears in. Null until the first member is linked. */
	public long vectorEntryPoint(int ord) {
		requireDeclared(ord);
		return segments.resolve(catalogPage())
			.get(Layouts.LONG, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_ENTRY_POINT);
	}

	public int vectorMaxLevel(int ord) {
		requireDeclared(ord);
		return segments.resolve(catalogPage())
			.get(Layouts.INT, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_MAX_LEVEL);
	}

	public void vectorEntryPoint(int ord, long vectorId, int maxLevel) {
		requireDeclared(ord);
		long base = OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES;
		MemorySegment page = segments.mutable(catalogPage());
		page.set(Layouts.LONG, base + VI_ENTRY_POINT, vectorId);
		page.set(Layouts.INT, base + VI_MAX_LEVEL, maxLevel);
		segments.touched(catalogPage());
	}

	/** How many members the build has linked so far, so a BUILDING index can report progress rather than a shrug. */
	public long vectorBuiltCount(int ord) {
		requireDeclared(ord);
		return segments.resolve(catalogPage())
			.get(Layouts.LONG, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_BUILT_COUNT);
	}

	public void vectorBuiltCount(int ord, long built) {
		requireDeclared(ord);
		segments.mutable(catalogPage())
			.set(Layouts.LONG, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_BUILT_COUNT, built);
		segments.touched(catalogPage());
	}

	/** Clears a declaration. The slot is left in place so no other index's ordinal changes. */
	public void removeVectorIndex(int ord) {
		requireDeclared(ord);
		MemorySegment page = segments.mutable(catalogPage());
		long base = OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES;
		page.asSlice(base, VECTOR_ENTRY_BYTES).fill((byte) 0);
		segments.touched(catalogPage());
	}

	/**
	 * Refuses an ordinal with no declaration behind it.
	 * <p>
	 * Reads the count and the one state byte rather than {@link #vectorIndexes()}, which decodes every declaration and
	 * looks its names up in the dictionary — and this guards every entry-point read, which a search makes twice and a
	 * build twice per member.
	 */
	private void requireDeclared(int ord) {
		if (!initialised() || ord < 0) {
			throw new IllegalArgumentException("No vector index with ordinal " + ord);
		}
		MemorySegment page = segments.resolve(catalogPage());
		int count = page.get(Layouts.INT, OFF_VECTOR_COUNT);
		if (ord >= Math.min(count, MAX_VECTOR_INDEXES)
			|| page.get(Layouts.BYTE, OFF_VECTOR_ENTRIES + (long) ord * VECTOR_ENTRY_BYTES + VI_STATE) == 0) {
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
					putLabel(labelId, nodeId);
				}
			}
		}
		if (properties != null) {
			for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
				putProperty(entry.getKey(), entry.getValue(), nodeId);
			}
		}
		for (Composite declared : composites()) {
			Object value = value(properties, declared.keyId());
			if (value != null && holds(labelIds, declared.labelId())) {
				compositePut(nodeId, declared.labelId(), declared.keyId(), value);
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
		for (Composite declared : composites()) {
			Object value = value(properties, declared.keyId());
			if (value != null && holds(labelIds, declared.labelId())) {
				compositeRemove(nodeId, declared.labelId(), declared.keyId(), value);
			}
		}
	}

	private static Object value(Map<Integer, Object> properties, int keyId) {
		return properties == null ? null : properties.get(keyId);
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
	 *
	 * <p>
	 * <b>A composite index makes this hook interesting.</b> Adding a label to a node has to insert a composite entry
	 * for every declared key that node <em>already</em> carries — the value did not change, but the pair
	 * {@code (label, key)} it belongs under only came into existence with the label. Which is why the node's
	 * properties are a parameter here at all; they are read through a supplier because nothing needs them until a
	 * composite index is declared, and relabelling must not start reading property blocks in a store that has none.
	 * </p>
	 */
	public void onLabelsChanged(long nodeId, int[] before, int[] after,
		java.util.function.Supplier<Map<Integer, Object>> properties) throws IOException {
		ensureInitialised();
		for (int labelId : before) {
			if (labelId != 0 && !holds(after, labelId)) {
				tree.remove(labelRoot(), labelId, nodeId);
			}
		}
		for (int labelId : after) {
			if (labelId != 0 && !holds(before, labelId)) {
				putLabel(labelId, nodeId);
			}
		}
		List<Composite> declared = composites();
		if (declared.isEmpty()) {
			return;
		}
		Map<Integer, Object> held = properties.get();
		for (Composite composite : declared) {
			boolean had = holds(before, composite.labelId());
			boolean has = holds(after, composite.labelId());
			if (had == has) {
				continue;
			}
			Object value = value(held, composite.keyId());
			if (value == null) {
				continue;
			}
			if (has) {
				compositePut(nodeId, composite.labelId(), composite.keyId(), value);
			} else {
				compositeRemove(nodeId, composite.labelId(), composite.keyId(), value);
			}
		}
	}

	private static boolean holds(int[] labelIds, int labelId) {
		if (labelIds == null) {
			return false;
		}
		for (int held : labelIds) {
			if (held == labelId) {
				return true;
			}
		}
		return false;
	}

	public void onPropertiesChanged(long nodeId, int[] labelIds, Map<Integer, Object> before,
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
				putProperty(entry.getKey(), entry.getValue(), nodeId);
			}
		}
		for (Composite composite : composites()) {
			if (!holds(labelIds, composite.labelId())) {
				continue;
			}
			Object was = value(before, composite.keyId());
			Object now = value(after, composite.keyId());
			if (valueEquals(was, now)) {
				continue;
			}
			if (was != null) {
				compositeRemove(nodeId, composite.labelId(), composite.keyId(), was);
			}
			if (now != null) {
				compositePut(nodeId, composite.labelId(), composite.keyId(), now);
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
	 * <p>
	 * The composite tree is reset with them, but only if one was ever created. The <b>declarations survive</b>: they
	 * live on the catalog page, not in the tree, so {@code onNodeCreated} refills the composite entries during the same
	 * rebuild that refills the other two.
	 */
	public int resetTrees() throws IOException {
		if (!initialised()) {
			return 0;
		}
		boolean hadComposite = !ElementId.isNull(compositeRoot());
		List<Long> pages = new java.util.ArrayList<>(tree.pages(labelRoot()));
		pages.addAll(tree.pages(propRoot()));
		if (hadComposite) {
			pages.addAll(tree.pages(compositeRoot()));
		}
		for (long pageId : pages) {
			segments.free(pageId);
		}
		// Everything the trees held is now free, so hand the refill the bottom of the segment rather than the order the
		// pages happened to be freed in. A scattered refill leaves a live page near the top, shrinkHighWater() can then
		// trim nothing, and the mark ratchets up on every rebuild for the life of the store — measured at 57 live index
		// pages under a high water mark of 252, and a real disk leak rather than a cosmetic one anywhere hole punching
		// is unavailable. Safe here precisely because index ids are internal and never handed out, which is the same
		// property that makes resetTrees() affordable at all.
		segments.packFreeRing();
		labelRoot(tree.create());
		propRoot(tree.create());
		compositeRoot(hadComposite ? tree.create() : ElementId.NULL);
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

	// ---------------------------------------------------------------- verification

	/** The label tree's entries as {@code k1, k2, value} triples, or none when there is no index yet. */
	public long[] labelEntries() {
		return initialised() ? tree.entries(labelRoot()) : new long[0];
	}

	/** The property tree's entries, in the same shape. */
	public long[] propertyEntries() {
		return initialised() ? tree.entries(propRoot()) : new long[0];
	}

	/** The composite tree's entries, in the same shape; none when nothing is declared. */
	public long[] compositeEntries() {
		return initialised() && !ElementId.isNull(compositeRoot()) ? tree.entries(compositeRoot()) : new long[0];
	}

	public boolean hasLabelEntry(long nodeId, int labelId) {
		return initialised() && tree.get(labelRoot(), labelId, nodeId) != null;
	}

	public boolean hasPropertyEntry(long nodeId, int keyId, Object value) {
		return initialised() && tree.get(propRoot(), propertyKey(keyId, value), nodeId) != null;
	}

	public boolean hasCompositeEntry(long nodeId, int labelId, int keyId, Object value) {
		return initialised() && !ElementId.isNull(compositeRoot())
			&& tree.get(compositeRoot(), compositeKey(labelId, keyId, value), nodeId) != null;
	}

	/** Whether a property-tree key is the one this value would be indexed under. */
	public static boolean isPropertyKey(long k1, int keyId, Object value) {
		return propertyKey(keyId, value) == k1;
	}

	/** Whether a composite-tree key is the one this triple would be indexed under. */
	public static boolean isCompositeKey(long k1, int labelId, int keyId, Object value) {
		return compositeKey(labelId, keyId, value) == k1;
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
	 * The 64 bit index key for a composite {@code (label, key, value)}: all three <b>hashed together</b>.
	 * <p>
	 * Deliberately not three fields packed into fixed sub-widths. A label id and a key id are {@code int}s handed out
	 * by the dictionary, and a width chosen for them here would become a format constraint for the life of the store —
	 * the moment one overflowed, every existing entry would be keyed under a scheme nothing could reproduce. Hashing
	 * has no width to run out of, and it is what the property tree already does with its value.
	 * <p>
	 * Collisions are survivable for the same reason they are there: the caller verifies every candidate against the
	 * stored record, and a composite lookup verifies the label as well as the value. The verification is what makes
	 * hashing safe, and it has to use {@link #valueEquals} — the partner of {@link #valueHash} — rather than
	 * {@code Objects.deepEquals}, which is shallow inside a list and would discard the very candidate the tree found.
	 */
	static long compositeKey(int labelId, int keyId, Object value) {
		long pair = mix64(((long) labelId << 32) | (keyId & 0xFFFFFFFFL));
		return mix64(pair ^ (valueHash(value) & 0xFFFFFFFFL));
	}

	/** The SplitMix64 finaliser: it spreads the three fields across the whole key rather than into three ranges. */
	private static long mix64(long value) {
		long x = value;
		x ^= x >>> 33;
		x *= 0xFF51AFD7ED558CCDL;
		x ^= x >>> 33;
		x *= 0xC4CEB9FE1A85EC53L;
		x ^= x >>> 33;
		return x;
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

	/**
	 * A value as a map key, with the index's notion of equality rather than the value's own.
	 *
	 * <p>
	 * For a scan that has to find duplicates without the tree — the one a unique-constraint declaration runs. Keying a
	 * map on the raw value hashes a {@code float[]} by identity, and every vector read back is a fresh array, so two
	 * identical embeddings never met and a constraint was declared over data that already broke it. This is
	 * {@link #valueHash} and {@link #valueEquals} put where a {@code HashMap} will use them, so a declaration's idea of
	 * "the same value" is the one enforcement uses afterwards.
	 * </p>
	 */
	public record ValueKey(Object value) {

		@Override
		public boolean equals(Object other) {
			return other instanceof ValueKey key && valueEquals(value, key.value);
		}

		@Override
		public int hashCode() {
			return valueHash(value);
		}
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
