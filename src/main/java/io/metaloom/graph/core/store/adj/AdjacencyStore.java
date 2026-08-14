package io.metaloom.graph.core.store.adj;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
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
	 * Upper bound on the length of any chain: every live block in the store.
	 * <p>
	 * A chain is a linked list read out of pages, so a single corrupt {@code next} pointer can make it a ring. Every
	 * walk is bounded, because "follow pointers until null" against untrusted data is a process that never returns.
	 * The bound is computed once per walk rather than per hop, since it costs a scan of the segment headers.
	 */
	private long chainLimit() {
		return segments.liveCount() + 1;
	}

	private long step(long nodeId, long blockId, long hops, long limit) {
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
	private AdjacencyBlock block(long blockId) {
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
		List<long[]> chunks = new ArrayList<>();
		int total = 0;

		int inline = nodes.inlineEdgeCount(nodeId);
		if (inline > 0) {
			long[] head = new long[inline];
			for (int i = 0; i < inline; i++) {
				head[i] = nodes.inlineEdge(nodeId, i);
			}
			chunks.add(head);
			total += inline;
		}
		long blockId = nodes.adjHeadId(nodeId);
		long hops = 0;
		long limit = chainLimit();
		while (!ElementId.isNull(blockId)) {
			AdjacencyBlock current = block(step(nodeId, blockId, ++hops, limit));
			long[] entries = current.readAll();
			chunks.add(entries);
			total += entries.length;
			blockId = current.nextBlockId();
		}

		long[] result = new long[total];
		int written = 0;
		for (long[] chunk : chunks) {
			for (long edge : chunk) {
				if (!PackedEdge.isTombstone(edge)) {
					result[written++] = edge;
				}
			}
		}
		return written == result.length ? result : java.util.Arrays.copyOf(result, written);
	}

	/** Removes one entry. Returns false if the node had no such entry. */
	public boolean remove(long nodeId, long packedEdge) {
		if (nodes.removeInlineEdge(nodeId, packedEdge)) {
			return true;
		}
		long blockId = nodes.adjHeadId(nodeId);
		long hops = 0;
		long limit = chainLimit();
		while (!ElementId.isNull(blockId)) {
			AdjacencyBlock current = block(step(nodeId, blockId, ++hops, limit));
			if (current.mayContainLocation(PackedEdge.location(packedEdge))
				&& current.mayContainType(PackedEdge.typeId(packedEdge))) {
				long[] entries = current.readAll();
				boolean found = false;
				for (int i = 0; i < entries.length; i++) {
					if (entries[i] == packedEdge) {
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
