package io.metaloom.graph.core.store.adj;

import java.io.IOException;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.api.Kind;
import io.metaloom.graph.core.metrics.IoCounters;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.store.node.NodeStore;

/**
 * Edge storage in tiers: the two inline slots of the node record, then a block that <b>grows with the node</b>, then a
 * chain of page-sized blocks for dense nodes.
 * <p>
 * A node of degree one or two costs no adjacency I/O at all, because its edges live in the node record that a
 * traversal has already read. Beyond that, a block starts at one 512 byte cell and doubles as it fills — 512, 1 KiB,
 * 2 KiB, 4 KiB, one page — and only a node that fills a whole page starts a chain.
 * <p>
 * <b>The growth is the point.</b> The block used to be a page from the first edge that did not fit inline, so a node
 * of degree three cost 8 KiB. On a random graph of mean degree 4 that is roughly three quarters of all nodes, and it
 * measured as 59 of the 61 GiB of a ten million node store (spec/reports/SCALE.md). Doubling costs one copy per
 * doubling — five copies to reach a page, amortised O(1) per edge — and leaves a full page holding a full page's
 * worth of edges, exactly as before.
 * <p>
 * Growing rather than chaining also keeps reads cheap: a chain costs a read per block on every traversal, a larger run
 * costs none, because a block never straddles a page.
 * <p>
 * New blocks are pushed onto the <em>head</em> of the chain so that appending is O(1) rather than a walk to the tail.
 *
 * @see <a href="../../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a>
 */
public final class AdjacencyStore {

	private final SegmentSet segments;
	private final NodeStore nodes;
	private final int cellBytes;
	private final int maxCells;

	public AdjacencyStore(SegmentSet segments, NodeStore nodes) {
		if (segments.kind() != Kind.ADJ) {
			throw new IllegalArgumentException("AdjacencyStore requires an ADJ segment set");
		}
		this.segments = segments;
		this.nodes = nodes;
		this.cellBytes = segments.stride();
		this.maxCells = segments.slotsPerPage();
	}

	/** Cells in the smallest block. One, which is the whole point of the tier. */
	public static final int MIN_CELLS = 1;

	/**
	 * Upper bound on the length of any chain: every block slot below the high water marks.
	 * <p>
	 * A chain is a linked list read out of pages, so a single corrupt {@code next} pointer can make it a ring. Every
	 * walk is bounded, because "follow pointers until null" against untrusted data is a process that never returns.
	 * The bound is computed once per walk rather than per hop, since it costs a scan of the segment headers.
	 * <p>
	 * Not the live count: a snapshot view walks a chain whose blocks a later commit may have freed, so the current
	 * live count can be smaller than the chain the view is entitled to see. The high water mark never drops while a
	 * view is open.
	 */
	long chainLimit() {
		return segments.highWaterTotal() + 1;
	}

	long step(long nodeId, long blockId, long hops, long limit) {
		if (hops > limit) {
			throw new PageCorruptException("The adjacency chain of node " + ElementId.toString(nodeId)
				+ " does not terminate after " + hops + " blocks; it contains a cycle");
		}
		return blockId;
	}

	/**
	 * Read view of a block.
	 * <p>
	 * A block declares its own length, so reading one means knowing the length before slicing it. The run from the
	 * block's first cell to the end of its page is always safe to acquire — a block never straddles a page — so a
	 * single acquisition yields both the header and the payload, and the slice is then cut to the declared size.
	 */
	AdjacencyBlock block(long blockId) {
		return view(segments.resolveRunToPageEnd(blockId), blockId);
	}

	/** Write view of a block: copies the pages into the transaction overlay on first touch. */
	private AdjacencyBlock blockForWrite(long blockId) {
		int cells = block(blockId).cells();
		return new AdjacencyBlock(segments.mutableRun(blockId, cells), cells * cellBytes);
	}

	private AdjacencyBlock view(java.lang.foreign.MemorySegment toPageEnd, long blockId) {
		int cells = AdjacencyBlock.cellsOf(toPageEnd);
		if (cells < MIN_CELLS || cells > maxCells || (long) cells * cellBytes > toPageEnd.byteSize()) {
			throw new PageCorruptException("Adjacency block " + ElementId.toString(blockId) + " declares " + cells
				+ " cells, which is outside 1.." + maxCells + " or runs past the end of its page");
		}
		return new AdjacencyBlock(toPageEnd.asSlice(0, (long) cells * cellBytes), cells * cellBytes);
	}

	/** Cells a block occupies. Compaction needs it to relocate the whole run rather than its first cell. */
	public int blockCells(long blockId) {
		return block(blockId).cells();
	}

	/**
	 * Every block in the store, found by walking the chain of every node.
	 * <p>
	 * A block spans a run of cells and the free-slot bitmap is per cell, so a sweep over live ADJ slots sees
	 * continuation cells as well as block headers — and a continuation cell holds compressed edge data, so nothing
	 * in it distinguishes it from a header. (Zeroing continuation cells at allocation does not help: the payload
	 * overwrites them as soon as the block fills.) The owners are the only reliable authority, exactly as they are
	 * for property blocks, so enumeration goes through them.
	 * <p>
	 * An orphaned block — allocated but reachable from no node — is therefore invisible here. That is deliberate and
	 * matches the property store: {@code InvariantChecker} reports orphans, and maintenance leaves them alone rather
	 * than guessing at their shape.
	 */
	public long[] blockIds() {
		long[] blocks = new long[64];
		int written = 0;
		long limit = chainLimit();
		for (long nodeId : nodes.segments().liveIds()) {
			long blockId = nodes.adjHeadId(nodeId);
			long hops = 0;
			while (!ElementId.isNull(blockId)) {
				if (written == blocks.length) {
					blocks = java.util.Arrays.copyOf(blocks, blocks.length * 2);
				}
				blocks[written++] = blockId;
				blockId = block(step(nodeId, blockId, ++hops, limit)).nextBlockId();
			}
		}
		return java.util.Arrays.copyOf(blocks, written);
	}

	/** Adds one adjacency entry to a node, promoting it between tiers as needed. */
	public void add(long nodeId, long packedEdge) throws IOException {
		long head = nodes.adjHeadId(nodeId);
		if (ElementId.isNull(head) && nodes.addInlineEdge(nodeId, packedEdge)) {
			return;
		}
		if (ElementId.isNull(head)) {
			head = migrateInlineToBlock(nodeId);
		}
		AdjacencyBlock target = blockForWrite(head);
		if (target.appendRaw(packedEdge)) {
			segments.touched(head);
			return;
		}
		// The tail met the sorted region: recompress and retry before giving up on the block.
		if (target.compact() && target.appendRaw(packedEdge)) {
			segments.touched(head);
			return;
		}

		// Full at its current size. Doubling is cheaper than chaining for as long as there is room in the page: a
		// chain costs a read per block on every traversal, a larger run costs none.
		if (target.cells() < maxCells) {
			long grown = grow(nodeId, head, target.cells() * 2);
			if (!blockForWrite(grown).appendRaw(packedEdge)) {
				throw new IllegalStateException("A grown adjacency block rejected the entry that grew it");
			}
			segments.touched(grown);
			return;
		}

		target.markFull();
		segments.touched(head);

		long fresh = allocateBlock(nodeId, maxCells);
		AdjacencyBlock freshBlock = blockForWrite(fresh);
		freshBlock.nextBlockId(head);
		if (!freshBlock.appendRaw(packedEdge)) {
			throw new IllegalStateException("A fresh adjacency block rejected its first entry");
		}
		segments.touched(fresh);
		nodes.adjHeadId(nodeId, fresh);
	}

	/**
	 * Copies a full block into a larger run and frees the old one, leaving the chain and the node's head pointer
	 * consistent. Returns the id of the replacement, which is the node's new head.
	 */
	private long grow(long nodeId, long blockId, int cells) throws IOException {
		AdjacencyBlock old = block(blockId);
		long[] entries = old.readAll();
		long next = old.nextBlockId();
		int oldCells = old.cells();

		long grown = allocateBlock(nodeId, Math.min(cells, maxCells));
		AdjacencyBlock target = blockForWrite(grown);
		target.nextBlockId(next);
		if (!target.rewrite(entries)) {
			throw new IllegalStateException("A larger adjacency block did not fit the entries of a smaller one");
		}
		segments.touched(grown);

		// The old block is only reachable through the node's head pointer, so repointing it before the free is what
		// makes this safe to crash in the middle of: the transaction either applies both or neither.
		nodes.adjHeadId(nodeId, grown);
		segments.free(blockId, oldCells);
		return grown;
	}

	private long allocateBlock(long nodeId, int cells) throws IOException {
		long id = cells == 1 ? segments.allocate() : segments.allocateRun(cells);
		AdjacencyBlock fresh = new AdjacencyBlock(segments.mutableRun(id, cells), cells * cellBytes);
		fresh.initialise(nodeId, cells);
		segments.touched(id);
		return id;
	}

	private long migrateInlineToBlock(long nodeId) throws IOException {
		long id = allocateBlock(nodeId, MIN_CELLS);
		AdjacencyBlock target = blockForWrite(id);
		int inline = nodes.inlineEdgeCount(nodeId);
		for (int i = 0; i < inline; i++) {
			target.appendRaw(nodes.inlineEdge(nodeId, i));
		}
		nodes.clearInlineEdges(nodeId);
		nodes.adjHeadId(nodeId, id);
		segments.touched(id);
		return id;
	}

	/** Every adjacency entry of a node, inline entries first. */
	public long[] edges(long nodeId) {
		return edges(nodeId, null);
	}

	/**
	 * The adjacency entries of a node that a type filter admits, inline entries first.
	 * <p>
	 * <b>The filter runs at two levels and they are not redundant.</b> Per block it is
	 * {@link AdjacencyBlock#admitsAnyType}, which decides whether up to 8,000 entries are varint-decoded at all; per
	 * entry it is {@link PackedEdge#excludedByType}, which is the single authority on what one entry's bits prove.
	 * The block level never re-derives the entry rule — it defers to the {@code FLAG_TYPES_CACHED} bit for the one
	 * case the entry rule exists to protect, the materialised entry that caches no type.
	 * <p>
	 * A filtered scan costs the same {@code ADJ} record reads as an unfiltered one and that is not a defect: the
	 * chain's {@code next} pointer is in each block's header, so every block is acquired before it can be judged.
	 * What the skip saves is the decode, counted as {@link io.metaloom.graph.core.api.IoStats#adjacencyBlocksSkipped}.
	 * <p>
	 * <b>This materialises the whole chain</b>, which is right for a caller that wants all of it and wrong for one
	 * that may stop early. It is written as a drain of {@link #cursor(long, TypeFilter)} so that the walk and its
	 * filters exist in one place.
	 *
	 * @param typeIds
	 *            relationship type ids to admit, or null for every entry
	 */
	public long[] edges(long nodeId, TypeFilter typeIds) {
		EdgeCursor cursor = cursor(nodeId, typeIds);
		long[] result = new long[16];
		int written = 0;
		while (cursor.next()) {
			if (written == result.length) {
				result = java.util.Arrays.copyOf(result, result.length * 2);
			}
			result[written++] = cursor.edge();
		}
		return written == result.length ? result : java.util.Arrays.copyOf(result, written);
	}

	/**
	 * The same walk, streamed: entries arrive one at a time and a block is decoded only when the previous one has been
	 * consumed.
	 * <p>
	 * {@link #edges(long, TypeFilter)} is a drain of this, which is the point — the chain walk and its two filter
	 * levels exist once. Use this when the caller may stop early and the array form when it will not.
	 * <p>
	 * <b>The cursor does not hold a read view.</b> It reads pages lazily, across as many calls as the caller makes, so
	 * the caller has to keep the view stable for as long as it lives; inside {@code GraphStoreImpl.read} it must be
	 * drained before the supplier returns, because that read may be re-run.
	 *
	 * @param typeIds
	 *            relationship type ids to admit, or null for every entry
	 */
	public EdgeCursor cursor(long nodeId, TypeFilter typeIds) {
		int inline = nodes.inlineEdgeCount(nodeId);
		long[] head = new long[inline];
		for (int i = 0; i < inline; i++) {
			head[i] = nodes.inlineEdge(nodeId, i);
		}
		// Eager, and cheap: there are at most two of them and they live in the node record the caller has already read.
		// Copying them out here is what lets the cursor treat the inline tier and a block as the same kind of chunk.
		return new EdgeCursor(this, nodeId, typeIds, head, nodes.adjHeadId(nodeId), chainLimit());
	}

	/**
	 * True when some adjacency entry of {@code nodeId} connects it to {@code neighbourId}, in either direction and
	 * whatever the type.
	 * <p>
	 * This is the operation {@code min_location} / {@code max_location} were written for on the read path. A block
	 * whose recorded range excludes the target cannot hold a lightweight entry naming it and is walked past without
	 * decoding; the walk also stops at the first match, so a neighbour in the head block costs one decode however long
	 * the chain is.
	 * <p>
	 * <b>It is O(blocks), not O(log blocks).</b> Each block is sorted internally but the chain is not sorted across
	 * blocks — new blocks are pushed onto the head (§5.1), so block order is insertion order, not key order, and there
	 * is nothing to binary search over. The range check prunes; it does not index.
	 * <p>
	 * A block holding any materialised entry is never pruned: the 48 bit field of such an entry is a relationship
	 * location rather than a neighbour's, so the block's range mixes two address spaces and rules nothing out.
	 *
	 * @param materialisedReaches
	 *            tested against each surviving materialised entry, which only the relationship record can resolve;
	 *            given the packed entry
	 */
	public boolean hasEdge(long nodeId, long neighbourId, java.util.function.LongPredicate materialisedReaches) {
		long target = ElementId.location(neighbourId);
		// Resolved once rather than per entry: a lightweight entry records a location and no generation, so whether
		// it can name the id the caller asked about is one question about the neighbour's slot. A slot that has been
		// freed reads as generation 0 and a reused one as a different generation, and neither is the node asked for.
		int gen = nodes.segments().generationAt(ElementId.segment(neighbourId), ElementId.slot(neighbourId));
		boolean lightweightCanMatch = gen != 0 && gen == ElementId.gen(neighbourId);
		int inline = nodes.inlineEdgeCount(nodeId);
		for (int i = 0; i < inline; i++) {
			if (reaches(nodes.inlineEdge(nodeId, i), target, lightweightCanMatch, materialisedReaches)) {
				return true;
			}
		}
		long blockId = nodes.adjHeadId(nodeId);
		long hops = 0;
		long limit = chainLimit();
		while (!ElementId.isNull(blockId)) {
			AdjacencyBlock current = block(step(nodeId, blockId, ++hops, limit));
			if (!current.admitsNeighbour(target)) {
				countSkip();
				blockId = current.nextBlockId();
				continue;
			}
			countDecode();
			for (long edge : current.readAll()) {
				if (reaches(edge, target, lightweightCanMatch, materialisedReaches)) {
					return true;
				}
			}
			blockId = current.nextBlockId();
		}
		return false;
	}

	private boolean reaches(long edge, long target, boolean lightweightCanMatch,
		java.util.function.LongPredicate materialisedReaches) {
		if (PackedEdge.isTombstone(edge)) {
			return false;
		}
		if (PackedEdge.isMaterialised(edge)) {
			return materialisedReaches.test(edge);
		}
		return lightweightCanMatch && PackedEdge.location(edge) == target;
	}

	void countDecode() {
		IoCounters counters = segments.counters();
		if (counters != null) {
			counters.adjacencyBlockDecoded();
		}
	}

	void countSkip() {
		IoCounters counters = segments.counters();
		if (counters != null) {
			counters.adjacencyBlockSkipped();
		}
	}

	/** Removes one entry. Returns false if the node had no such entry. */
	public boolean remove(long nodeId, long packedEdge) {
		return remove(nodeId, packedEdge, false);
	}

	/**
	 * Removes the entry naming a materialised relationship, whatever type it caches.
	 * <p>
	 * A materialised entry's type field is a cache of the relationship record's type and deletion must not depend on
	 * it: an entry written before the cache existed holds {@link PackedEdge#TYPE_NOT_RECORDED} where a fresh one holds
	 * the type, and matching the whole packed value would walk straight past the older form and leave it behind as
	 * dangling adjacency.
	 */
	public boolean removeMaterialised(long nodeId, long relationshipId) {
		return remove(nodeId, PackedEdge.ofRelationship(relationshipId), true);
	}

	private boolean remove(long nodeId, long packedEdge, boolean ignoreType) {
		long mask = ignoreType ? PackedEdge.TYPE_AGNOSTIC_MASK : -1L;
		long probe = packedEdge & mask;
		if (nodes.removeInlineEdge(nodeId, packedEdge, mask)) {
			return true;
		}
		long blockId = nodes.adjHeadId(nodeId);
		long hops = 0;
		long limit = chainLimit();
		while (!ElementId.isNull(blockId)) {
			AdjacencyBlock current = block(step(nodeId, blockId, ++hops, limit));
			// The type bitmap can only be consulted when the type is part of the match; a masked-out type would make
			// the bitmap answer a question the probe is not asking, and its "no" is binding.
			if (current.mayContainLocation(PackedEdge.location(packedEdge))
				&& (ignoreType || current.mayContainType(PackedEdge.typeId(packedEdge)))) {
				long[] entries = current.readAll();
				boolean found = false;
				for (int i = 0; i < entries.length; i++) {
					// The tombstone is excluded explicitly. Masking the type out of it leaves dir 3 with an all-ones
					// location, which is a value a probe could in principle carry, and re-tombstoning an already dead
					// entry would report a removal that did not happen.
					if (!PackedEdge.isTombstone(entries[i]) && (entries[i] & mask) == probe) {
						entries[i] = PackedEdge.TOMBSTONE;
						found = true;
						break;
					}
				}
				if (found) {
					current = blockForWrite(blockId);
					if (!current.rewrite(entries)) {
						throw new IllegalStateException("Rewriting a block with one fewer entry did not fit");
					}
					segments.touched(blockId);
					return true;
				}
			}
			blockId = current.nextBlockId();
		}
		return false;
	}

	/** Frees the whole block chain of a node. Used when the node itself is deleted. */
	public void freeChain(long nodeId) {
		long blockId = nodes.adjHeadId(nodeId);
		long hops = 0;
		long limit = chainLimit();
		while (!ElementId.isNull(blockId)) {
			AdjacencyBlock current = block(step(nodeId, blockId, ++hops, limit));
			long next = current.nextBlockId();
			segments.free(blockId, current.cells());
			blockId = next;
		}
		nodes.adjHeadId(nodeId, ElementId.NULL);
		nodes.clearInlineEdges(nodeId);
	}

	/** Number of blocks in a node's chain. Diagnostic, used by the traversal cost tests. */
	public int blockCount(long nodeId) {
		int count = 0;
		long blockId = nodes.adjHeadId(nodeId);
		long limit = chainLimit();
		while (!ElementId.isNull(blockId)) {
			blockId = block(step(nodeId, blockId, ++count, limit)).nextBlockId();
		}
		return count;
	}

	// ---------------------------------------------------------------- block level access

	/** The owning node recorded in a block header. */
	public long blockOwner(long blockId) {
		return block(blockId).nodeId();
	}

	public void blockOwner(long blockId, long nodeId) {
		blockForWrite(blockId).nodeId(nodeId);
		segments.touched(blockId);
	}

	public long nextBlock(long blockId) {
		return block(blockId).nextBlockId();
	}

	public void nextBlock(long blockId, long nextId) {
		blockForWrite(blockId).nextBlockId(nextId);
		segments.touched(blockId);
	}

	/** Every entry of one block, tombstones included. */
	public long[] blockEntries(long blockId) {
		return block(blockId).readAll();
	}

	/**
	 * Replaces a block's entries. Returns false when the re-encoded form does not fit, leaving both the block and the
	 * given array untouched.
	 * <p>
	 * The copy is not incidental. {@link AdjacencyBlock#rewrite} consumes what it is given, and compaction calls this
	 * in a loop, shortening the array by a few entries after each refusal — so it must get back exactly what it passed
	 * in. Handing over the caller's own array would let a failed attempt reorder it and leave stale duplicates past
	 * the live prefix, which the next attempt would then move to another block.
	 */
	public boolean rewriteBlock(long blockId, long[] entries) {
		if (!blockForWrite(blockId).rewrite(entries.clone())) {
			return false;
		}
		segments.touched(blockId);
		return true;
	}

	/** Blocks and live entries of a node's chain, which is what the defragmentation heuristic decides on. */
	public record ChainStats(int blocks, int entries) {
	}

	public ChainStats chainStats(long nodeId) {
		int blocks = 0;
		int entries = 0;
		long blockId = nodes.adjHeadId(nodeId);
		long limit = chainLimit();
		while (!ElementId.isNull(blockId)) {
			AdjacencyBlock current = block(step(nodeId, blockId, ++blocks, limit));
			for (long edge : current.readAll()) {
				if (!PackedEdge.isTombstone(edge)) {
					entries++;
				}
			}
			blockId = current.nextBlockId();
		}
		return new ChainStats(blocks, entries);
	}

	/**
	 * Rebuilds a node's whole chain from its live entries, dropping tombstones and packing the survivors into as few
	 * blocks as the codec allows.
	 * <p>
	 * A removal only tombstones an entry, and a chain that has been churned can end up with many nearly empty blocks
	 * that a traversal still has to read. This is the adjacency half of compaction and, unlike record relocation, it
	 * breaks no ids at all: block ids are internal.
	 *
	 * @return the number of blocks the chain gave back, which may be negative if it somehow grew
	 */
	public int defragment(long nodeId) throws IOException {
		int before = blockCount(nodeId);
		if (before == 0) {
			return 0;
		}
		long[] live = edges(nodeId);
		freeChain(nodeId);
		for (long edge : live) {
			add(nodeId, edge);
		}
		return before - blockCount(nodeId);
	}

	public SegmentSet segments() {
		return segments;
	}
}
