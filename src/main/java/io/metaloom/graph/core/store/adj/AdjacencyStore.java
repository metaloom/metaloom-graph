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
 * Edge storage in three tiers: the two inline slots of the node record, a single adjacency block, and a chain of
 * blocks for dense nodes.
 * <p>
 * A node of degree one or two costs no adjacency I/O at all, because its edges live in the node record that a
 * traversal has already read. Beyond that, one page-sized block serves thousands of edges. The proof of concept's
 * singly linked list cost one random read and one fresh memory mapping per edge.
 * <p>
 * New blocks are pushed onto the <em>head</em> of the chain so that appending is O(1) rather than a walk to the tail.
 *
 * @see <a href="../../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a>
 */
public final class AdjacencyStore {

	private final SegmentSet segments;
	private final NodeStore nodes;
	private final int pageSize;

	public AdjacencyStore(SegmentSet segments, NodeStore nodes) {
		if (segments.kind() != Kind.ADJ) {
			throw new IllegalArgumentException("AdjacencyStore requires an ADJ segment set");
		}
		this.segments = segments;
		this.nodes = nodes;
		this.pageSize = segments.stride();
	}

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

	/** Read view of a block. */
	private AdjacencyBlock block(long blockId) {
		return new AdjacencyBlock(segments.resolve(blockId), pageSize);
	}

	/** Write view of a block: copies the page into the transaction overlay on first touch. */
	private AdjacencyBlock blockForWrite(long blockId) {
		return new AdjacencyBlock(segments.mutable(blockId), pageSize);
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
		target.markFull();
		segments.touched(head);

		long fresh = allocateBlock(nodeId);
		AdjacencyBlock freshBlock = blockForWrite(fresh);
		freshBlock.nextBlockId(head);
		if (!freshBlock.appendRaw(packedEdge)) {
			throw new IllegalStateException("A fresh adjacency block rejected its first entry");
		}
		segments.touched(fresh);
		nodes.adjHeadId(nodeId, fresh);
	}

	private long allocateBlock(long nodeId) throws IOException {
		long id = segments.allocate();
		AdjacencyBlock fresh = blockForWrite(id);
		fresh.initialise(nodeId);
		return id;
	}

	private long migrateInlineToBlock(long nodeId) throws IOException {
		long id = allocateBlock(nodeId);
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
			long next = block(step(nodeId, blockId, ++hops, limit)).nextBlockId();
			segments.free(blockId);
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

	/** Replaces a block's entries. Returns false when the re-encoded form does not fit. */
	public boolean rewriteBlock(long blockId, long[] entries) {
		if (!blockForWrite(blockId).rewrite(entries)) {
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
