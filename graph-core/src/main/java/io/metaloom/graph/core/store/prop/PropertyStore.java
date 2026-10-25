package io.metaloom.graph.core.store.prop;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.store.blob.BlobStore;
import io.metaloom.graph.core.store.vec.VectorStore;
import io.metaloom.graph.core.util.Layouts;

/**
 * All properties of one element in a single contiguous block.
 * <p>
 * The unit of read and write is the block, not the individual property: elements overwhelmingly want all their
 * properties at once, and rewriting a two-cell block is cheaper than maintaining per-property records. This replaces
 * the proof of concept's 1024 byte array of property offsets in every element with one 8 byte block id.
 *
 * <pre>
 * Block header (16 bytes):
 *  0  2  cell_count
 *  2  2  entry_count
 *  4  2  used_bytes      payload bytes following the header
 *  6  2  gen
 *  8  8  owner_id
 * </pre>
 *
 * Entries follow, sorted ascending by key id: {@code [u24 key_id][tag][payload]}.
 *
 * @see <a href="../../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 2
 */
public final class PropertyStore {

	public static final int HEADER_BYTES = 16;

	private static final int OFF_CELL_COUNT = 0;
	private static final int OFF_ENTRY_COUNT = 2;
	private static final int OFF_USED_BYTES = 4;
	private static final int OFF_OWNER = 8;

	private final SegmentSet segments;
	private final BlobStore blobs;
	private final VectorStore vectors;
	private final int cellBytes;
	private final int maxPayloadBytes;
	private java.util.function.LongConsumer vectorRelease = vectorId -> {
	};

	public PropertyStore(SegmentSet segments, BlobStore blobs) {
		this(segments, blobs, null);
	}

	/**
	 * @param vectors
	 *            the store's vector records, or null when it has none. A {@code float[]} of that store's dimension is
	 *            then written there instead of to a blob — one fixed-size record rather than a whole page — and every
	 *            other vector still escapes to a blob.
	 */
	public PropertyStore(SegmentSet segments, BlobStore blobs, VectorStore vectors) {
		if (segments.kind() != Kind.PROP) {
			throw new IllegalArgumentException("PropertyStore requires a PROP segment set");
		}
		this.segments = segments;
		this.blobs = blobs;
		this.vectors = vectors;
		this.cellBytes = segments.stride();
		// A block never straddles a page, so it can never be larger than one page.
		this.maxPayloadBytes = segments.slotsPerPage() * cellBytes - HEADER_BYTES;
	}

	/**
	 * Writes a property block and returns its id. An empty map produces {@link ElementId#NULL} rather than an empty
	 * block, so property-free elements cost nothing.
	 */
	public long store(Map<Integer, Object> properties, long ownerId) throws IOException {
		if (properties == null || properties.isEmpty()) {
			return ElementId.NULL;
		}
		byte[] payload = encode(properties, ownerId);
		if (payload.length > maxPayloadBytes) {
			throw new IllegalArgumentException("Encoded property block of " + payload.length
				+ " bytes exceeds the " + maxPayloadBytes + " bytes that fit one page");
		}
		int cells = (HEADER_BYTES + payload.length + cellBytes - 1) / cellBytes;
		long id = segments.allocateRun(cells);
		MemorySegment block = segments.mutableRun(id, cells);
		block.set(Layouts.SHORT, OFF_CELL_COUNT, (short) cells);
		block.set(Layouts.SHORT, OFF_ENTRY_COUNT, (short) properties.size());
		block.set(Layouts.SHORT, OFF_USED_BYTES, (short) payload.length);
		block.set(Layouts.LONG, OFF_OWNER, ownerId);
		MemorySegment.copy(MemorySegment.ofArray(payload), 0, block, HEADER_BYTES, payload.length);
		segments.touched(id);
		return id;
	}

	private byte[] encode(Map<Integer, Object> properties, long ownerId) throws IOException {
		List<Integer> keys = new ArrayList<>(properties.keySet());
		// Entries are sorted by key id so that lookup is a binary search rather than a scan.
		keys.sort(null);
		ValueCodec.Writer writer = new ValueCodec.Writer(32 + properties.size() * 12);
		for (Integer key : keys) {
			if (key == null || key <= 0 || key > 0xFFFFFF) {
				throw new IllegalArgumentException("Property key id out of range: " + key);
			}
			writer.u24(key);
			ValueCodec.encode(writer, properties.get(key), blobs, vectors, ownerId);
		}
		return writer.toArray();
	}

	/** Reads every property of a block. Returns an empty map for {@link ElementId#NULL}. */
	public Map<Integer, Object> read(long blockId) {
		Map<Integer, Object> result = new LinkedHashMap<>();
		if (ElementId.isNull(blockId)) {
			return result;
		}
		MemorySegment block = segments.resolveRunToPageEnd(blockId);
		byte[] payload = payloadOf(blockId, block);
		int used = payload.length;

		ValueCodec.Reader reader = new ValueCodec.Reader(payload, 0, used);
		while (reader.hasRemaining()) {
			int key = reader.u24();
			result.put(key, ValueCodec.decode(reader, blobs, vectors, true, null));
		}
		return result;
	}

	/** Receives each property of a block, in key id order. */
	@FunctionalInterface
	public interface PropertyVisitor {

		void property(int keyId, Object value);
	}

	/**
	 * Reads every property of a block into a visitor, for a caller building its own result.
	 *
	 * <p>
	 * {@link #read(long)} builds a map keyed by boxed key ids that the API layer then copies into a second map keyed by
	 * name. The intermediate map was a third of what a {@code nodeProperties} call allocated.
	 * </p>
	 */
	public void read(long blockId, PropertyVisitor visitor) {
		if (ElementId.isNull(blockId)) {
			return;
		}
		MemorySegment block = segments.resolveRunToPageEnd(blockId);
		byte[] payload = payloadOf(blockId, block);
		ValueCodec.Reader reader = new ValueCodec.Reader(payload, 0, payload.length);
		while (reader.hasRemaining()) {
			int key = reader.u24();
			visitor.property(key, ValueCodec.decode(reader, blobs, vectors, true, null));
		}
	}

	/** Reads a single property without materialising the rest of the block. */
	public Object read(long blockId, int keyId) {
		if (ElementId.isNull(blockId)) {
			return null;
		}
		MemorySegment block = segments.resolveRunToPageEnd(blockId);
		byte[] payload = payloadOf(blockId, block);
		int used = payload.length;

		ValueCodec.Reader reader = new ValueCodec.Reader(payload, 0, used);
		while (reader.hasRemaining()) {
			int key = reader.u24();
			boolean wanted = key == keyId;
			Object value = ValueCodec.decode(reader, blobs, vectors, wanted, null);
			if (wanted) {
				return value;
			}
			if (key > keyId) {
				// Entries are sorted, so the key cannot appear later.
				return null;
			}
		}
		return null;
	}

	/** True if the block contains the key. Distinguishes an absent key from a stored null. */
	public boolean contains(long blockId, int keyId) {
		if (ElementId.isNull(blockId)) {
			return false;
		}
		MemorySegment block = segments.resolveRunToPageEnd(blockId);
		byte[] payload = payloadOf(blockId, block);
		int used = payload.length;

		ValueCodec.Reader reader = new ValueCodec.Reader(payload, 0, used);
		while (reader.hasRemaining()) {
			int key = reader.u24();
			if (key == keyId) {
				return true;
			}
			ValueCodec.decode(reader, blobs, vectors, false, null);
			if (key > keyId) {
				return false;
			}
		}
		return false;
	}

	public long owner(long blockId) {
		if (ElementId.isNull(blockId)) {
			return ElementId.NULL;
		}
		return segments.resolve(blockId).get(Layouts.LONG, OFF_OWNER);
	}

	/** Repoints the back-pointer. Compaction is the only caller: an owner never changes otherwise. */
	public void owner(long blockId, long ownerId) {
		if (ElementId.isNull(blockId)) {
			return;
		}
		segments.mutable(blockId).set(Layouts.LONG, OFF_OWNER, ownerId);
		segments.touched(blockId);
	}

	/**
	 * Cells the block occupies. A block is a run of consecutive slots, so this is what free and relocate need.
	 * <p>
	 * The value is read straight out of a page and is therefore untrusted: a block cannot span more cells than remain
	 * in its page, and a header claiming otherwise would have {@code free} release slots belonging to other blocks.
	 */
	public int cellCount(long blockId) {
		if (ElementId.isNull(blockId)) {
			return 0;
		}
		int cells = Short.toUnsignedInt(segments.resolve(blockId).get(Layouts.SHORT, OFF_CELL_COUNT));
		int available = segments.slotsPerPage() - ElementId.slot(blockId) % segments.slotsPerPage();
		if (cells < 1 || cells > available) {
			throw new PageCorruptException("Property block " + ElementId.toString(blockId) + " claims " + cells
				+ " cells but only " + available + " remain in its page");
		}
		return cells;
	}

	/**
	 * The block's payload, with its declared length validated against the space that actually exists.
	 * <p>
	 * {@code used_bytes} is a sixteen bit field read out of a page, so it can name up to 65535 bytes while the run it
	 * describes holds at most one page. Copying that many bytes is an out of bounds read, and allocating them first is
	 * a way to be told how much heap the caller has. Both are one flipped byte away without this check.
	 */
	private byte[] payloadOf(long blockId, MemorySegment block) {
		int used = Short.toUnsignedInt(block.get(Layouts.SHORT, OFF_USED_BYTES));
		long available = block.byteSize() - HEADER_BYTES;
		if (used > available) {
			throw new PageCorruptException("Property block " + ElementId.toString(blockId) + " declares " + used
				+ " payload bytes but only " + available + " remain in its page");
		}
		byte[] payload = new byte[used];
		MemorySegment.copy(block, HEADER_BYTES, MemorySegment.ofArray(payload), 0, used);
		return payload;
	}

	/**
	 * Rewrites every element id embedded in the block's payload and reports whether anything changed. The payload
	 * length is unaffected, so the block keeps its id and its cells.
	 *
	 * @see ValueCodec#remapIds(byte[], int, int, java.util.function.LongUnaryOperator)
	 */
	public boolean remapReferences(long blockId, java.util.function.LongUnaryOperator map) {
		if (ElementId.isNull(blockId)) {
			return false;
		}
		int cells = cellCount(blockId);
		MemorySegment block = segments.resolveRun(blockId, cells);
		byte[] payload = payloadOf(blockId, block);
		int used = payload.length;
		if (!ValueCodec.remapIds(payload, 0, used, map)) {
			return false;
		}
		MemorySegment writable = segments.mutableRun(blockId, cells);
		MemorySegment.copy(MemorySegment.ofArray(payload), 0, writable, HEADER_BYTES, used);
		segments.touched(blockId);
		return true;
	}

	/** Every blob id the block references, in encounter order. */
	public List<Long> blobIds(long blockId) {
		List<Long> result = new ArrayList<>();
		for (long id : ownedIds(blockId)) {
			if (ElementId.kind(id) == Kind.BLOB) {
				result.add(id);
			}
		}
		return result;
	}

	/**
	 * The record one specific key's value owns, or {@link ElementId#NULL} when it owns none.
	 * <p>
	 * {@link #ownedIds} answers "what does this block own", which is what the free path needs. This answers "what does
	 * <em>this property</em> own", which is what a caller needs to act on one value — an index stamping the vector
	 * record behind {@code :Person.embedding} cannot use a list it has no way to attribute to a key.
	 */
	public long ownedIdOf(long blockId, int keyId) {
		if (ElementId.isNull(blockId)) {
			return ElementId.NULL;
		}
		int cells = cellCount(blockId);
		MemorySegment block = segments.resolveRun(blockId, cells);
		byte[] payload = payloadOf(blockId, block);
		ValueCodec.Reader reader = new ValueCodec.Reader(payload, 0, payload.length);
		while (reader.hasRemaining()) {
			int key = reader.u24();
			List<Long> owned = key == keyId ? new ArrayList<>(1) : null;
			ValueCodec.decode(reader, blobs, vectors, false, owned);
			if (key == keyId) {
				return owned.isEmpty() ? ElementId.NULL : owned.get(0);
			}
			if (key > keyId) {
				// Entries are sorted, so the key cannot appear later.
				return ElementId.NULL;
			}
		}
		return ElementId.NULL;
	}

	/**
	 * Every record this block <b>owns</b>, of whatever kind: blob extent chains and vector records both.
	 * <p>
	 * The distinction matters on the free path. A property block is the only thing that points at these, so the
	 * cascade has to see every one of them or the record outlives its owner — and it has to know which kind each is,
	 * because they are freed by different stores.
	 */
	public List<Long> ownedIds(long blockId) {
		List<Long> ownedIds = new ArrayList<>();
		if (ElementId.isNull(blockId)) {
			return ownedIds;
		}
		int cells = cellCount(blockId);
		MemorySegment block = segments.resolveRun(blockId, cells);
		byte[] payload = payloadOf(blockId, block);
		int used = payload.length;
		ValueCodec.Reader reader = new ValueCodec.Reader(payload, 0, used);
		while (reader.hasRemaining()) {
			reader.u24();
			ValueCodec.decode(reader, blobs, vectors, false, ownedIds);
		}
		return ownedIds;
	}

	public SegmentSet segments() {
		return segments;
	}

	/**
	 * Called with every vector record a {@link #free} is about to release, before its slot goes.
	 * <p>
	 * Every free of a block runs through here — a deleted node, an overwritten property, a deleted relationship — so
	 * this is the one place an ANN index can be told a member is leaving. Left to the callers, the path that forgot
	 * freed the entry point and the index answered every later query with nothing.
	 */
	public void onVectorRelease(java.util.function.LongConsumer release) {
		this.vectorRelease = release;
	}

	/**
	 * Frees a block and everything it owns. This cascade is what the proof of concept never had: deleting an element
	 * there left its property records behind forever.
	 * <p>
	 * A value can own a blob extent chain or a vector record, and the two are released by different stores — so the
	 * sweep dispatches on the id's kind rather than assuming. Assuming was safe only while blobs were the sole escape.
	 */
	public void free(long blockId) {
		if (ElementId.isNull(blockId)) {
			return;
		}
		int cells = cellCount(blockId);
		MemorySegment block = segments.resolveRunToPageEnd(blockId);
		byte[] payload = payloadOf(blockId, block);
		int used = payload.length;

		List<Long> ownedIds = new ArrayList<>();
		ValueCodec.Reader reader = new ValueCodec.Reader(payload, 0, used);
		while (reader.hasRemaining()) {
			reader.u24();
			ValueCodec.decode(reader, blobs, vectors, false, ownedIds);
		}
		for (long ownedId : ownedIds) {
			if (ElementId.kind(ownedId) == Kind.VEC) {
				if (vectors != null) {
					vectorRelease.accept(ownedId);
					vectors.free(ownedId);
				}
			} else {
				blobs.free(ownedId);
			}
		}
		segments.free(blockId, cells);
	}

	/** Replaces a block, freeing the old one. Returns the new block id. */
	public long replace(long oldBlockId, Map<Integer, Object> properties, long ownerId) throws IOException {
		long newId = store(properties, ownerId);
		free(oldBlockId);
		return newId;
	}

	public int maxPayloadBytes() {
		return maxPayloadBytes;
	}
}
