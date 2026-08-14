package io.metaloom.graph.core.maintenance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.GraphStoreException;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.index.IndexCatalog;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.store.adj.AdjacencyStore;
import io.metaloom.graph.core.store.adj.PackedEdge;
import io.metaloom.graph.core.store.blob.BlobStore;
import io.metaloom.graph.core.store.node.NodeStore;
import io.metaloom.graph.core.store.prop.PropertyStore;
import io.metaloom.graph.core.store.rel.RelStore;

/**
 * Segment compaction: the only operation in the engine that moves a record, and therefore the only one that breaks an
 * id.
 *
 * <h2>How it works</h2>
 *
 * Live records are relocated <b>downwards</b>, highest first, into the lowest free slot below their own position. Two
 * things follow from that ordering. The pass terminates, because every move strictly decreases a position and a freed
 * source is always above every record still to be considered. And the high water marks can come down afterwards, which
 * is where the space actually reappears: {@code storageBytes} and every full scan are bounded by the marks, not by the
 * live count.
 *
 * <h2>Where this departs from the specification</h2>
 *
 * {@code FILE_FORMAT.md} section 9 describes copying into a freshly allocated destination segment and keeping an
 * on-disk forwarding map that a {@code StaleIdException} handler consults until the rewrite finishes. This
 * implementation instead does the relocation and the reference rewrite <b>inside one transaction per batch</b>, so the
 * forwarding map never has to survive a crash and is never consulted by a reader. The engine's no-steal write-ahead log
 * already guarantees that an uncommitted batch cannot reach a data file, which makes a batch atomic for free: a crash
 * mid-compaction rolls back to the last committed batch and the ids that batch would have changed are simply unchanged.
 * The price is that a batch is bounded by memory, which {@link CompactionOptions#maxRelocationsPerTransaction()}
 * controls, and that the reference sweep runs once per batch rather than once per segment.
 *
 * <h2>The reference classes</h2>
 *
 * A missed reference class is a silent corruption, so the inventory is exhaustive and explicit:
 *
 * <table border="1">
 * <caption>Every place an element id is stored</caption>
 * <tr><th>Moved kind</th><th>Must be rewritten</th></tr>
 * <tr><td>{@code NODE}</td><td>relationship endpoints, adjacency block owners, adjacency entries, node inline edges,
 * property block owners, {@code ElementRef} property values, both secondary indexes</td></tr>
 * <tr><td>{@code REL}</td><td>materialised adjacency entries, node inline edges, property block owners,
 * {@code ElementRef} property values</td></tr>
 * <tr><td>{@code PROP}</td><td>{@code node.prop_block_id}, {@code rel.prop_block_id}</td></tr>
 * <tr><td>{@code ADJ}</td><td>{@code node.adj_head_id}, {@code block.next_block_id}</td></tr>
 * <tr><td>{@code BLOB}</td><td>blob ids inside property payloads, {@code extent.next_extent_id}</td></tr>
 * <tr><td>{@code INDEX}</td><td>nothing: index ids are internal, so the index is rebuilt rather than relocated</td></tr>
 * </table>
 *
 * @see <a href="../../../../../../../../spec/FILE_FORMAT.md">spec/FILE_FORMAT.md</a> section 9
 */
public final class Compactor {

	private static final Logger logger = LoggerFactory.getLogger(Compactor.class);

	/**
	 * Adjacency first, so that the blocks its defragmentation frees are gone before {@code ADJ} is compacted, and the
	 * index last, so that its rebuild sees the node ids the {@code NODE} pass settled on.
	 */
	private static final Kind[] ORDER = { Kind.ADJ, Kind.BLOB, Kind.PROP, Kind.REL, Kind.NODE };

	private final Map<Kind, SegmentSet> sets;
	private final NodeStore nodes;
	private final RelStore rels;
	private final AdjacencyStore adjacency;
	private final PropertyStore properties;
	private final BlobStore blobs;
	private final IndexCatalog indexes;
	private final MaintenanceRunner runner;

	public Compactor(Map<Kind, SegmentSet> sets, NodeStore nodes, RelStore rels, AdjacencyStore adjacency,
		PropertyStore properties, BlobStore blobs, IndexCatalog indexes, MaintenanceRunner runner) {
		this.sets = sets;
		this.nodes = nodes;
		this.rels = rels;
		this.adjacency = adjacency;
		this.properties = properties;
		this.blobs = blobs;
		this.indexes = indexes;
		this.runner = runner;
	}

	/** Accumulates what a run did, so the caller gets one report rather than a log to read. */
	public static final class Result {

		public final Map<Kind, Long> relocated = new EnumMap<>(Kind.class);
		public int batches;
		public long adjacencyBlocksFreed;
		public long indexPagesFreed;
		public long slotsReclaimed;
		public int segmentsRetired;
		public int pagesPunched;

		private void relocated(Kind kind, long count) {
			relocated.merge(kind, count, Long::sum);
		}
	}

	/**
	 * Runs a full compaction. The writer lock is taken and released once per batch rather than held for the whole run,
	 * so a foreground writer waits for at most one batch.
	 */
	public Result compact(CompactionOptions options) throws IOException {
		Result result = new Result();

		if (options.defragmentAdjacency() && options.kinds().contains(Kind.ADJ)) {
			result.adjacencyBlocksFreed = defragmentAdjacency(options, result);
		}
		for (Kind kind : ORDER) {
			if (!options.kinds().contains(kind)) {
				continue;
			}
			SegmentSet set = sets.get(kind);
			if (set.liveness() >= options.livenessThreshold()) {
				logger.debug("Skipping {}: liveness {} is at or above the {} threshold", kind, set.liveness(),
					options.livenessThreshold());
				continue;
			}
			compactKind(kind, options, result);
		}
		if (options.kinds().contains(Kind.INDEX) && indexes.isInitialised()
			&& indexFill() < options.indexFillThreshold()) {
			rebuildIndexes(result);
		}

		// Punching happens before the marks come down: an emptied page above the new mark would otherwise be skipped.
		for (Kind kind : options.kinds()) {
			SegmentSet set = sets.get(kind);
			if (set != null) {
				result.pagesPunched += set.reclaim();
			}
		}
		for (Kind kind : options.kinds()) {
			SegmentSet set = sets.get(kind);
			if (set == null) {
				continue;
			}
			long[] reclaimed = new long[1];
			runner.runWrite(() -> reclaimed[0] = set.shrinkHighWater());
			result.slotsReclaimed += reclaimed[0];
			result.batches++;
		}
		if (options.retireEmptySegments()) {
			// The log addresses page images by segment number, so every record naming a file about to be unlinked has
			// to be behind the replay origin first.
			runner.runCheckpoint();
			for (Kind kind : options.kinds()) {
				SegmentSet set = sets.get(kind);
				if (set != null) {
					result.segmentsRetired += set.retireEmptySegments();
				}
			}
		}
		return result;
	}

	// ---------------------------------------------------------------- record relocation

	private void compactKind(Kind kind, CompactionOptions options, Result result) throws IOException {
		SegmentSet set = sets.get(kind);
		List<Target> targets = targets(kind);
		if (targets.isEmpty()) {
			return;
		}
		// Highest position first: the top of the segment is what has to move for the mark to come down.
		targets.sort((a, b) -> Long.compareUnsigned(ElementId.location(b.id()), ElementId.location(a.id())));

		Set<Integer> exhausted = new HashSet<>();
		int index = 0;
		while (index < targets.size()) {
			int end = Math.min(index + options.maxRelocationsPerTransaction(), targets.size());
			List<Target> slice = targets.subList(index, end);
			index = end;
			if (slice.stream().allMatch(target -> exhausted.contains(target.cells()))) {
				// Nothing in this slice has anywhere lower to go, and neither will anything below it.
				break;
			}
			long[] moved = new long[1];
			runner.runWrite(() -> moved[0] = relocateBatch(kind, slice, exhausted));
			result.batches++;
			result.relocated(kind, moved[0]);
			pause(options);
		}
	}

	/** One live object to relocate: its id, and how many consecutive slots it occupies. */
	private record Target(long id, int cells) {
	}

	private List<Target> targets(Kind kind) throws IOException {
		List<Target> targets = new ArrayList<>();
		runner.runRead(() -> {
			switch (kind) {
				case NODE, REL, ADJ, BLOB -> {
					for (long id : sets.get(kind).liveIds()) {
						targets.add(new Target(id, 1));
					}
				}
				case PROP -> {
					// A property block is a run of cells and only its first slot is a block header, so the free slot
					// bitmap cannot tell a block from a continuation cell. The owners can: every live block is
					// reachable from exactly one node or relationship. A block that is not is an orphan, which
					// InvariantChecker reports and which compaction deliberately leaves alone rather than guessing at.
					for (long nodeId : sets.get(Kind.NODE).liveIds()) {
						addPropertyTarget(targets, nodes.propBlockId(nodeId));
					}
					for (long relId : sets.get(Kind.REL).liveIds()) {
						addPropertyTarget(targets, rels.propBlockId(relId));
					}
				}
				default -> {
					// INDEX is rebuilt rather than relocated, and UNDO has no records yet.
				}
			}
		});
		return targets;
	}

	private void addPropertyTarget(List<Target> targets, long blockId) {
		if (!ElementId.isNull(blockId)) {
			targets.add(new Target(blockId, properties.cellCount(blockId)));
		}
	}

	/**
	 * Copies a slice of records into lower slots, rewrites every reference to them, and only then frees the sources.
	 * <p>
	 * The order matters. Freeing first would advance the source generations and make the old ids unresolvable while the
	 * sweep still needs to read through some of them — property blocks in particular are enumerated through the owner
	 * back-pointers that the sweep is in the middle of rewriting.
	 *
	 * @return the number of records actually moved
	 */
	private long relocateBatch(Kind kind, List<Target> slice, Set<Integer> exhausted) throws IOException {
		SegmentSet set = sets.get(kind);
		ForwardingMap map = new ForwardingMap();
		for (Target target : slice) {
			if (exhausted.contains(target.cells()) || !set.isLive(target.id())) {
				// Not live means a foreground writer deleted it between the scan and now, which is fine: compaction
				// does not hold the writer lock across batches precisely so that this can happen.
				continue;
			}
			long destination = set.allocateLowest(target.cells(), ElementId.segment(target.id()),
				ElementId.slot(target.id()));
			if (ElementId.isNull(destination)) {
				exhausted.add(target.cells());
				continue;
			}
			set.relocate(target.id(), destination, target.cells());
			map.put(target.id(), destination);
		}
		if (map.isEmpty()) {
			return 0;
		}

		rewriteReferences(kind, map);
		if (kind == Kind.NODE) {
			reindex(map);
		}
		for (Map.Entry<Long, Long> entry : map.entries().entrySet()) {
			set.free(entry.getKey(), cellsOf(kind, entry.getValue()));
		}
		return map.size();
	}

	private int cellsOf(Kind kind, long newId) {
		return kind == Kind.PROP ? properties.cellCount(newId) : 1;
	}

	// ---------------------------------------------------------------- the reference sweep

	private void rewriteReferences(Kind moved, ForwardingMap map) throws IOException {
		switch (moved) {
			case NODE -> {
				for (long relId : sets.get(Kind.REL).liveIds()) {
					long from = rels.fromId(relId);
					long mappedFrom = map.mapId(from);
					if (mappedFrom != from) {
						rels.fromId(relId, mappedFrom);
					}
					long to = rels.toId(relId);
					long mappedTo = map.mapId(to);
					if (mappedTo != to) {
						rels.toId(relId, mappedTo);
					}
				}
				for (long blockId : sets.get(Kind.ADJ).liveIds()) {
					long owner = adjacency.blockOwner(blockId);
					long mappedOwner = map.mapId(owner);
					if (mappedOwner != owner) {
						adjacency.blockOwner(blockId, mappedOwner);
					}
					remapBlockEntries(blockId, map, false);
				}
				for (long nodeId : sets.get(Kind.NODE).liveIds()) {
					if (!map.isSource(nodeId)) {
						remapInlineEdges(nodeId, map, false);
					}
				}
				for (long newId : map.entries().values()) {
					properties.owner(nodes.propBlockId(newId), newId);
				}
				remapPropertyPayloads(map);
			}
			case REL -> {
				for (long blockId : sets.get(Kind.ADJ).liveIds()) {
					remapBlockEntries(blockId, map, true);
				}
				for (long nodeId : sets.get(Kind.NODE).liveIds()) {
					remapInlineEdges(nodeId, map, true);
				}
				for (long newId : map.entries().values()) {
					properties.owner(rels.propBlockId(newId), newId);
				}
				remapPropertyPayloads(map);
			}
			case PROP -> {
				for (long nodeId : sets.get(Kind.NODE).liveIds()) {
					long block = nodes.propBlockId(nodeId);
					long mapped = map.mapId(block);
					if (mapped != block) {
						nodes.propBlockId(nodeId, mapped);
					}
				}
				for (long relId : sets.get(Kind.REL).liveIds()) {
					long block = rels.propBlockId(relId);
					long mapped = map.mapId(block);
					if (mapped != block) {
						rels.propBlockId(relId, mapped);
					}
				}
			}
			case ADJ -> {
				for (long nodeId : sets.get(Kind.NODE).liveIds()) {
					long head = nodes.adjHeadId(nodeId);
					long mapped = map.mapId(head);
					if (mapped != head) {
						nodes.adjHeadId(nodeId, mapped);
					}
				}
				for (long blockId : sets.get(Kind.ADJ).liveIds()) {
					if (map.isSource(blockId)) {
						continue;
					}
					long next = adjacency.nextBlock(blockId);
					long mapped = map.mapId(next);
					if (mapped != next) {
						adjacency.nextBlock(blockId, mapped);
					}
				}
			}
			case BLOB -> {
				for (long extentId : sets.get(Kind.BLOB).liveIds()) {
					if (map.isSource(extentId)) {
						continue;
					}
					long next = blobs.nextExtent(extentId);
					long mapped = map.mapId(next);
					if (mapped != next) {
						blobs.nextExtent(extentId, mapped);
					}
				}
				remapPropertyPayloads(map);
			}
			default -> throw new IllegalArgumentException("Compaction does not relocate " + moved);
		}
	}

	/** Rewrites the ids embedded in every live property payload: blob escapes and explicit element references. */
	private void remapPropertyPayloads(ForwardingMap map) {
		for (long nodeId : sets.get(Kind.NODE).liveIds()) {
			if (!map.isSource(nodeId)) {
				properties.remapReferences(nodes.propBlockId(nodeId), map::mapId);
			}
		}
		for (long relId : sets.get(Kind.REL).liveIds()) {
			if (!map.isSource(relId)) {
				properties.remapReferences(rels.propBlockId(relId), map::mapId);
			}
		}
	}

	private void remapInlineEdges(long nodeId, ForwardingMap map, boolean materialised) {
		int count = nodes.inlineEdgeCount(nodeId);
		for (int i = 0; i < count; i++) {
			long edge = nodes.inlineEdge(nodeId, i);
			long remapped = remapEdge(edge, map, materialised);
			if (remapped != edge) {
				nodes.setInlineEdge(nodeId, i, remapped);
			}
		}
	}

	private static long remapEdge(long edge, ForwardingMap map, boolean materialised) {
		if (PackedEdge.isTombstone(edge) || PackedEdge.isMaterialised(edge) != materialised) {
			return edge;
		}
		long location = PackedEdge.location(edge);
		long mapped = map.mapLocation(location);
		return mapped == location ? edge : PackedEdge.pack(PackedEdge.dir(edge), PackedEdge.typeId(edge), mapped);
	}

	/**
	 * Rewrites the entries of one adjacency block.
	 * <p>
	 * Re-encoding can need more bytes than the original even though the entry count is unchanged, because the codec
	 * stores deltas and relocation reorders locations. When the block no longer fits, the tail is pushed back through
	 * {@link AdjacencyStore#add}, which puts it in the chain's head block or in a fresh one. Entries are unordered
	 * across a chain, so that is a legal home for them.
	 */
	private void remapBlockEntries(long blockId, ForwardingMap map, boolean materialised) throws IOException {
		long[] entries = adjacency.blockEntries(blockId);
		boolean changed = false;
		for (int i = 0; i < entries.length; i++) {
			long remapped = remapEdge(entries[i], map, materialised);
			if (remapped != entries[i]) {
				entries[i] = remapped;
				changed = true;
			}
		}
		if (!changed) {
			return;
		}
		long owner = adjacency.blockOwner(blockId);
		long[] fitting = entries;
		List<Long> overflow = new ArrayList<>();
		while (!adjacency.rewriteBlock(blockId, fitting)) {
			int drop = Math.max(1, fitting.length / 64);
			if (drop >= fitting.length) {
				throw new GraphStoreException("Adjacency block " + ElementId.toString(blockId)
					+ " cannot hold even one entry after a compaction rewrite");
			}
			for (int i = fitting.length - drop; i < fitting.length; i++) {
				overflow.add(fitting[i]);
			}
			fitting = Arrays.copyOf(fitting, fitting.length - drop);
		}
		for (long edge : overflow) {
			if (!PackedEdge.isTombstone(edge)) {
				adjacency.add(owner, edge);
			}
		}
	}

	/**
	 * Re-keys the secondary indexes for the nodes this batch moved.
	 * <p>
	 * An index entry cannot be patched in place: the node id is the low half of the composite key, so a moved node
	 * belongs at a different position in the tree. Remove and re-insert is the only correct edit.
	 */
	private void reindex(ForwardingMap map) throws IOException {
		if (!indexes.isInitialised()) {
			return;
		}
		for (Map.Entry<Long, Long> entry : map.entries().entrySet()) {
			long newId = entry.getValue();
			int labelId = nodes.labelId(newId);
			Map<Integer, Object> props = properties.read(nodes.propBlockId(newId));
			indexes.onNodeDeleted(entry.getKey(), labelId, props);
			indexes.onNodeCreated(newId, labelId, props);
		}
	}

	// ---------------------------------------------------------------- adjacency and index

	/**
	 * Rebuilds the adjacency chains that churn has left sparse. This breaks no ids at all — block ids are internal — so
	 * it is the cheap half of adjacency maintenance and runs before any record is relocated.
	 */
	private long defragmentAdjacency(CompactionOptions options, Result result) throws IOException {
		List<Long> pending = new ArrayList<>();
		runner.runRead(() -> {
			for (long nodeId : sets.get(Kind.NODE).liveIds()) {
				AdjacencyStore.ChainStats stats = adjacency.chainStats(nodeId);
				if (stats.blocks() >= 2 && stats.entries() < (long) stats.blocks() * options.adjacencyFillThreshold()) {
					pending.add(nodeId);
				}
			}
		});
		if (pending.isEmpty()) {
			return 0;
		}
		long freed = 0;
		int batch = Math.max(1, options.maxRelocationsPerTransaction() / 16);
		for (int index = 0; index < pending.size(); index += batch) {
			List<Long> slice = pending.subList(index, Math.min(index + batch, pending.size()));
			long[] freedHere = new long[1];
			runner.runWrite(() -> {
				for (long nodeId : slice) {
					if (sets.get(Kind.NODE).isLive(nodeId)) {
						freedHere[0] += adjacency.defragment(nodeId);
					}
				}
			});
			freed += freedHere[0];
			result.batches++;
			pause(options);
		}
		logger.debug("Defragmented {} adjacency chains, {} blocks freed", pending.size(), freed);
		return freed;
	}

	/**
	 * Entries per B+tree page as a fraction of the fan-out.
	 * <p>
	 * Slot liveness is the wrong measure for an index. Removing a key never frees a page — {@link io.metaloom.graph.core.index.BTree}
	 * does not merge underfull nodes — so an index whose entries have all been deleted still reports every one of its
	 * pages as live. What has actually degraded is how full those pages are, and that is what decides whether a rebuild
	 * is worth it.
	 */
	private double indexFill() throws IOException {
		double[] fill = { 1.0 };
		runner.runRead(() -> {
			// One page is the catalog page, which holds the two root pointers and no entries.
			long pages = sets.get(Kind.INDEX).liveCount() - 1;
			if (pages <= 0) {
				return;
			}
			long entries = indexes.labelIndexSize() + indexes.propertyIndexSize();
			fill[0] = (double) entries / (pages * indexes.tree().fanout());
		});
		return fill[0];
	}

	/**
	 * Rebuilds both secondary indexes in a single transaction.
	 * <p>
	 * It has to be one transaction. Batching would commit an index that is missing entries, and a store whose index
	 * disagrees with its records returns wrong answers rather than slow ones. The bound this accepts is that the
	 * rebuild is held in memory, so it is gated on the index actually being sparse.
	 */
	private void rebuildIndexes(Result result) throws IOException {
		long[] freed = new long[1];
		runner.runWrite(() -> {
			freed[0] = indexes.resetTrees();
			for (long nodeId : sets.get(Kind.NODE).liveIds()) {
				indexes.onNodeCreated(nodeId, nodes.labelId(nodeId), properties.read(nodes.propBlockId(nodeId)));
			}
		});
		result.indexPagesFreed = freed[0];
		result.batches++;
	}

	private void pause(CompactionOptions options) {
		if (options.pauseBetweenBatchesMillis() <= 0) {
			return;
		}
		try {
			Thread.sleep(options.pauseBetweenBatchesMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
