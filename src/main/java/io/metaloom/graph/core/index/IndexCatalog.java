package io.metaloom.graph.core.index;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

	private boolean initialised() {
		if (ElementId.isNull(catalogPageId)) {
			return false;
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

	// ---------------------------------------------------------------- maintenance hooks

	public void onNodeCreated(long nodeId, int labelId, Map<Integer, Object> properties) throws IOException {
		ensureInitialised();
		if (labelId != 0) {
			labelRoot(tree.put(labelRoot(), labelId, nodeId, nodeId));
		}
		if (properties != null) {
			for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
				propRoot(tree.put(propRoot(), propertyKey(entry.getKey(), entry.getValue()), nodeId, nodeId));
			}
		}
	}

	public void onNodeDeleted(long nodeId, int labelId, Map<Integer, Object> properties) throws IOException {
		if (!initialised()) {
			return;
		}
		if (labelId != 0) {
			tree.remove(labelRoot(), labelId, nodeId);
		}
		if (properties != null) {
			for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
				tree.remove(propRoot(), propertyKey(entry.getKey(), entry.getValue()), nodeId);
			}
		}
	}

	public void onPropertiesChanged(long nodeId, int labelId, Map<Integer, Object> before,
		Map<Integer, Object> after) throws IOException {
		ensureInitialised();
		for (Map.Entry<Integer, Object> entry : before.entrySet()) {
			Object now = after.get(entry.getKey());
			if (!java.util.Objects.deepEquals(now, entry.getValue())) {
				tree.remove(propRoot(), propertyKey(entry.getKey(), entry.getValue()), nodeId);
			}
		}
		for (Map.Entry<Integer, Object> entry : after.entrySet()) {
			Object was = before.get(entry.getKey());
			if (!java.util.Objects.deepEquals(was, entry.getValue())) {
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
			default -> value.hashCode();
		};
	}

	private static int hash(byte[] data) {
		int h = 0x811C9DC5;
		for (byte b : data) {
			h = (h ^ (b & 0xFF)) * 0x01000193;
		}
		return h;
	}
}
