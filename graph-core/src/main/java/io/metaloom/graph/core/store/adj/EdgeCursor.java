package io.metaloom.graph.core.store.adj;

import java.util.Set;

import io.metaloom.graph.core.id.ElementId;

/**
 * One node's adjacency entries, one block at a time.
 *
 * <p>
 * This is the streaming form of {@link AdjacencyStore#edges(long, java.util.Set)}, and that method is now a drain of
 * it — there is one walk of the chain in this project, not two. The difference is <em>when</em> a block is decoded: the
 * array form decodes the whole chain before the caller sees anything, so a query that wants one edge out of a million
 * pays for a million. Here a block is acquired and decoded only when the entries of the previous one have run out, so
 * stopping after the first row costs the head block and nothing else.
 * </p>
 *
 * <p>
 * <b>The two filter levels are the ones {@link AdjacencyStore#edges(long, java.util.Set)} documents, applied in the
 * same places.</b> Per block it is {@link AdjacencyBlock#admitsAnyType}, which decides whether up to 8,000 entries are
 * varint-decoded at all; per entry it is {@link PackedEdge#excludedByType}, which is the single authority on what one
 * entry's bits prove. Neither rule is re-derived here — a second implementation of "type 0 means the cache is absent,
 * not that the type is zero" is a second place to get it wrong, and getting it wrong silently drops edges
 * (spec/RELATIONSHIP_STORAGE.md §3.1).
 * </p>
 *
 * <p>
 * <b>The read view is the caller's problem, not the cursor's.</b> A cursor outlives the call that made it, so it
 * cannot be run under {@code GraphStoreImpl.read}, which may re-run its body. Whoever hands one out has to hold a
 * stable view for as long as it lives; {@code GraphStoreImpl.neighbourCursor} does that with an outright read latch,
 * for the reason {@code forEachNodeWhile} gives.
 * </p>
 *
 * <p>
 * Entries arrive inline-first and then in chain order, which is the order the array form returns them in.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/RELATIONSHIP_STORAGE.md">spec/RELATIONSHIP_STORAGE.md</a> section 5.4
 */
public final class EdgeCursor {

	private static final long[] EMPTY = new long[0];

	private final AdjacencyStore store;
	private final long nodeId;
	private final Set<Integer> typeIds;
	private final long chainLimit;

	/** The block to visit next, or {@link ElementId#NULL} once the chain is exhausted. */
	private long blockId;
	private long hops;

	/** The entries of the chunk being served: the inline entries first, then one block at a time. */
	private long[] entries;
	private int index;

	private long current;
	private boolean valid;

	EdgeCursor(AdjacencyStore store, long nodeId, Set<Integer> typeIds, long[] inline, long headBlockId,
		long chainLimit) {
		this.store = store;
		this.nodeId = nodeId;
		this.typeIds = typeIds;
		this.chainLimit = chainLimit;
		this.entries = inline;
		this.blockId = headBlockId;
	}

	/**
	 * Advances to the next entry the filter admits.
	 *
	 * @return false when the chain is exhausted, after which {@link #edge()} has nothing to return
	 */
	public boolean next() {
		while (true) {
			while (index < entries.length) {
				long edge = entries[index++];
				// The tombstone is excluded first: it is the all-ones value, so its type field is one no filter can
				// legitimately admit, and letting it reach the type rule would make the rule answer about a dead entry.
				if (PackedEdge.isTombstone(edge) || PackedEdge.excludedByType(edge, typeIds)) {
					continue;
				}
				current = edge;
				valid = true;
				return true;
			}
			if (!advance()) {
				valid = false;
				return false;
			}
		}
	}

	/** The entry {@link #next()} stopped on. */
	public long edge() {
		if (!valid) {
			throw new IllegalStateException("The edge cursor is not positioned on an entry; call next() first");
		}
		return current;
	}

	/**
	 * Loads the next block of the chain, decoding it only if the block-level filter admits it.
	 * <p>
	 * The chain's {@code next} pointer lives inside each block's header, so the block is acquired — one {@code ADJ}
	 * record read — whether or not it is decoded, and the pointer is read out before the block view is dropped. That is
	 * the same accounting the array form has: the skip saves the decode, not the read.
	 *
	 * @return false when there is no next block
	 */
	private boolean advance() {
		if (ElementId.isNull(blockId)) {
			return false;
		}
		AdjacencyBlock block = store.block(store.step(nodeId, blockId, ++hops, chainLimit));
		long next = block.nextBlockId();
		if (block.admitsAnyType(typeIds)) {
			store.countDecode();
			entries = block.readAll();
		} else {
			store.countSkip();
			entries = EMPTY;
		}
		index = 0;
		blockId = next;
		return true;
	}
}
