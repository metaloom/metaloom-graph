package io.metaloom.graph.core.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.core.api.VerifyOptions;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.id.Kind;
import io.metaloom.graph.core.segment.SegmentSet;
import io.metaloom.graph.core.store.adj.AdjacencyStore;
import io.metaloom.graph.core.store.adj.PackedEdge;
import io.metaloom.graph.core.store.blob.BlobStore;
import io.metaloom.graph.core.store.node.NodeStore;
import io.metaloom.graph.core.store.prop.PropertyStore;
import io.metaloom.graph.core.store.rel.RelStore;

/**
 * The structural invariants, checked over a whole store.
 * <p>
 * Every violation is reported, not just the first: a corruption pattern is far more diagnostic than a single
 * instance.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 5
 */
public final class InvariantChecker {

	/**
	 * What a throttled scan does between batches. Implemented by the store as "drop the read latch, pause, take it
	 * again", which is what stops a full verification of a large store from being one long stall.
	 */
	@FunctionalInterface
	public interface Pacer {

		/** Returns false to stop the scan early, for instance when a time budget has run out. */
		boolean pause();

		Pacer NONE = () -> true;
	}

	private final NodeStore nodes;
	private final RelStore rels;
	private final AdjacencyStore adjacency;
	private final PropertyStore properties;
	private final BlobStore blobs;
	private final Map<Kind, SegmentSet> segmentSets;

	private VerifyOptions options = VerifyOptions.consistentSnapshot();
	private Pacer pacer = Pacer.NONE;
	private int sinceLastPause;
	private long deadlineNanos;
	private boolean stopped;

	public InvariantChecker(NodeStore nodes, RelStore rels, AdjacencyStore adjacency, PropertyStore properties,
		BlobStore blobs, Map<Kind, SegmentSet> segmentSets) {
		this.nodes = nodes;
		this.rels = rels;
		this.adjacency = adjacency;
		this.properties = properties;
		this.blobs = blobs;
		this.segmentSets = segmentSets;
	}

	/** Runs every check and returns the violations, empty when the store is structurally sound. */
	public List<String> check() {
		return check(VerifyOptions.consistentSnapshot(), Pacer.NONE);
	}

	/**
	 * Runs every check, yielding as the options ask.
	 * <p>
	 * A truncated scan is reported as truncated rather than as clean: silently returning "no violations" because the
	 * time budget ran out is exactly the kind of green that a verification tool must never produce.
	 */
	public List<String> check(VerifyOptions options, Pacer pacer) {
		this.options = options;
		this.pacer = pacer;
		this.sinceLastPause = 0;
		this.stopped = false;
		this.deadlineNanos = options.timeBudget() == null
			? Long.MAX_VALUE
			: System.nanoTime() + options.timeBudget().toNanos();

		List<String> violations = new ArrayList<>();
		checkNodes(violations);
		checkRelationships(violations);
		checkPropertyBlocks(violations);
		checkAdjacencyChains(violations);
		checkBlobChains(violations);
		if (stopped) {
			violations.add("(scan stopped early: " + (violations.size() >= options.maxViolations()
				? "violation limit of " + options.maxViolations() + " reached"
				: "time budget exhausted") + "; this result is not a clean bill of health)");
		}
		return violations;
	}

	/** True when the scan should keep going. Counts one element and yields when a batch is complete. */
	private boolean step(List<String> violations) {
		if (stopped) {
			return false;
		}
		if (violations.size() >= options.maxViolations() || System.nanoTime() >= deadlineNanos) {
			stopped = true;
			return false;
		}
		if (options.isThrottled() && ++sinceLastPause >= options.batchSize()) {
			sinceLastPause = 0;
			if (!pacer.pause()) {
				stopped = true;
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- nodes and relationships

	private void checkNodes(List<String> violations) {
		// Every out-edge must have a matching in-edge on the neighbour, and the degree counters must agree with what
		// is actually stored.
		for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
			if (!step(violations)) {
				return;
			}
			int outSeen = 0;
			int inSeen = 0;
			Map<MirrorKey, Integer> expectedMirrors = new HashMap<>();
			for (long edge : adjacency.edges(nodeId)) {
				if (PackedEdge.isMaterialised(edge)) {
					long relId = withCurrentGeneration(Kind.REL, PackedEdge.relationshipId(edge));
					if (ElementId.isNull(relId)) {
						violations.add("Node " + ElementId.toString(nodeId)
							+ " has an adjacency entry for a dead relationship");
						continue;
					}
					// The type cached in the entry is what a type-filtered traversal decides on before it reads the
					// record, so a stale one does not slow a query down — it silently drops an edge from its answer.
					int cachedType = PackedEdge.typeId(edge);
					if (cachedType != PackedEdge.TYPE_NOT_RECORDED && cachedType != rels.typeId(relId)) {
						violations.add("Node " + ElementId.toString(nodeId) + " caches type " + cachedType
							+ " for relationship " + ElementId.toString(relId) + ", whose record says "
							+ rels.typeId(relId));
					}
					if (rels.fromId(relId) == nodeId && rels.toId(relId) == nodeId) {
						// A self-loop is one stored entry standing for both an outgoing and an incoming edge.
						outSeen++;
						inSeen++;
					} else if (rels.fromId(relId) == nodeId) {
						outSeen++;
					} else if (rels.toId(relId) == nodeId) {
						inSeen++;
					} else {
						violations.add("Relationship " + ElementId.toString(relId) + " is listed on node "
							+ ElementId.toString(nodeId) + " but connects neither endpoint to it");
					}
					continue;
				}
				long neighbour = withCurrentGeneration(Kind.NODE, PackedEdge.neighbourId(edge));
				if (ElementId.isNull(neighbour)) {
					violations.add("Node " + ElementId.toString(nodeId)
						+ " has an adjacency entry pointing at a dead node");
					continue;
				}
				int dir = PackedEdge.dir(edge);
				if (dir == PackedEdge.DIR_OUT) {
					outSeen++;
				} else {
					inSeen++;
				}
				int mirrorDir = dir == PackedEdge.DIR_OUT ? PackedEdge.DIR_IN : PackedEdge.DIR_OUT;
				long mirror = PackedEdge.ofNeighbour(mirrorDir, PackedEdge.typeId(edge), nodeId);
				expectedMirrors.merge(new MirrorKey(neighbour, mirror), 1, Integer::sum);
			}

			if (nodes.degreeOut(nodeId) != outSeen) {
				violations.add("Node " + ElementId.toString(nodeId) + " has degree_out " + nodes.degreeOut(nodeId)
					+ " but " + outSeen + " outgoing entries");
			}
			if (nodes.degreeIn(nodeId) != inSeen) {
				violations.add("Node " + ElementId.toString(nodeId) + " has degree_in " + nodes.degreeIn(nodeId)
					+ " but " + inSeen + " incoming entries");
			}
			for (Map.Entry<MirrorKey, Integer> entry : expectedMirrors.entrySet()) {
				long neighbour = entry.getKey().neighbour();
				long mirror = entry.getKey().entry();
				int found = 0;
				for (long candidate : adjacency.edges(neighbour)) {
					if (candidate == mirror) {
						found++;
					}
				}
				if (found < entry.getValue()) {
					violations.add("Node " + ElementId.toString(nodeId) + " expects " + entry.getValue()
						+ " mirror entries on " + ElementId.toString(neighbour) + " but found " + found);
				}
			}
		}
	}

	private void checkRelationships(List<String> violations) {
		for (long relId : segmentSets.get(Kind.REL).liveIds()) {
			if (!step(violations)) {
				return;
			}
			if (!nodes.exists(rels.fromId(relId))) {
				violations.add("Relationship " + ElementId.toString(relId) + " has a dead from endpoint");
			}
			if (!nodes.exists(rels.toId(relId))) {
				violations.add("Relationship " + ElementId.toString(relId) + " has a dead to endpoint");
			}
		}
	}

	// ---------------------------------------------------------------- property blocks

	/**
	 * Property blocks: every live block has an owner that points back at it, and every allocated property cell belongs
	 * to some block.
	 * <p>
	 * The enumeration goes through the owners, not through the free slot bitmap, and that is not a stylistic choice. A
	 * block is a <em>run</em> of cells of which only the first carries a header; the bitmap marks every cell of the run
	 * as allocated and cannot say which is which. Treating each allocated cell as a block header reads payload bytes as
	 * an {@code owner_id}, and payload that happens to look like an id produces a corruption report for a perfectly
	 * healthy store. A verification tool that cries wolf is worse than none.
	 */
	private void checkPropertyBlocks(List<String> violations) {
		Set<Long> coveredCells = new HashSet<>();

		for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
			if (!step(violations)) {
				return;
			}
			coverBlock(violations, coveredCells, nodeId, nodes.propBlockId(nodeId));
			// A node's label set spills into a second property block once it outgrows the record. It is owned by the
			// node exactly as its properties are, so it is covered the same way — without this the orphan sweep below
			// reports every multi-label node's overflow as leaked space.
			coverBlock(violations, coveredCells, nodeId, nodes.labelOverflowId(nodeId));
		}
		for (long relId : segmentSets.get(Kind.REL).liveIds()) {
			if (!step(violations)) {
				return;
			}
			coverBlock(violations, coveredCells, relId, rels.propBlockId(relId));
		}

		// Anything allocated and not covered is leaked space that nothing will ever free.
		for (long cellId : segmentSets.get(Kind.PROP).liveIds()) {
			if (!step(violations)) {
				return;
			}
			if (!coveredCells.contains(ElementId.location(cellId))) {
				violations.add("Property cell " + ElementId.toString(cellId)
					+ " is orphaned; it is allocated but belongs to no live node or relationship");
			}
		}
	}

	/** Validates one owner-to-block link and records every cell the block occupies. */
	private void coverBlock(List<String> violations, Set<Long> coveredCells, long ownerId, long blockId) {
		if (ElementId.isNull(blockId)) {
			return;
		}
		if (!segmentSets.get(Kind.PROP).isLive(blockId)) {
			violations.add(ElementId.toString(ownerId) + " references a dead property block "
				+ ElementId.toString(blockId));
			return;
		}
		long recorded = properties.owner(blockId);
		if (recorded != ownerId) {
			violations.add("Property block " + ElementId.toString(blockId) + " is referenced by "
				+ ElementId.toString(ownerId) + " but its header names " + ElementId.toString(recorded));
		}
		long first = ElementId.location(blockId);
		int cells = properties.cellCount(blockId);
		for (int i = 0; i < cells; i++) {
			if (!coveredCells.add(first + i)) {
				violations.add("Property cell at location " + (first + i) + " is claimed by more than one block; "
					+ ElementId.toString(ownerId) + " is one of the claimants");
			}
		}
	}

	// ---------------------------------------------------------------- adjacency and blobs

	/**
	 * Adjacency chains: every block belongs to exactly one node, says so in its header, and is reachable from it.
	 * <p>
	 * Nothing else checks the block header or the chain pointers, and both are rewritten by compaction. An orphaned
	 * block is leaked space; a block whose header names the wrong node makes compaction relocate its overflow onto a
	 * stranger.
	 */
	private void checkAdjacencyChains(List<String> violations) {
		Set<Long> reachable = new HashSet<>();
		int limit = segmentSets.get(Kind.ADJ).segmentCount() * segmentSets.get(Kind.ADJ).slotsPerPage() + 1;
		for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
			if (!step(violations)) {
				return;
			}
			long blockId = nodes.adjHeadId(nodeId);
			int hops = 0;
			while (!ElementId.isNull(blockId)) {
				if (!segmentSets.get(Kind.ADJ).isLive(blockId)) {
					violations.add("Node " + ElementId.toString(nodeId) + " has a dead block "
						+ ElementId.toString(blockId) + " in its adjacency chain");
					break;
				}
				if (!reachable.add(blockId)) {
					violations.add("Adjacency block " + ElementId.toString(blockId)
						+ " is reachable from more than one chain, or the chain from node "
						+ ElementId.toString(nodeId) + " is a cycle");
					break;
				}
				long owner = adjacency.blockOwner(blockId);
				if (owner != nodeId) {
					violations.add("Adjacency block " + ElementId.toString(blockId) + " is in the chain of node "
						+ ElementId.toString(nodeId) + " but its header names " + ElementId.toString(owner));
				}
				blockId = adjacency.nextBlock(blockId);
				if (++hops > limit) {
					violations.add("Adjacency chain of node " + ElementId.toString(nodeId) + " does not terminate");
					break;
				}
			}
		}
		// A block spans a run of cells, so the live-slot sweep sees continuation cells too. Every one of them must be
		// covered by exactly one reachable block: a cell that is allocated but belongs to no block is leaked space,
		// and one claimed by two blocks is the worst corruption the allocator can produce.
		Set<Long> covered = new HashSet<>();
		for (long blockId : reachable) {
			int cells = adjacency.blockCells(blockId);
			for (int i = 0; i < cells; i++) {
				long cell = ElementId.encode(Kind.ADJ, ElementId.segment(blockId), ElementId.slot(blockId) + i,
					ElementId.gen(blockId));
				if (!covered.add(ElementId.location(cell))) {
					violations.add("Adjacency cell " + ElementId.toString(cell) + " is claimed by more than one block");
				}
			}
		}
		for (long cellId : segmentSets.get(Kind.ADJ).liveIds()) {
			if (!step(violations)) {
				return;
			}
			if (!covered.contains(ElementId.location(cellId))) {
				violations.add("Adjacency cell " + ElementId.toString(cellId)
					+ " is allocated but no node's chain reaches a block covering it");
			}
		}
	}

	/** Every blob a live property block references must be live, unshared, and have an intact extent chain. */
	private void checkBlobChains(List<String> violations) {
		Set<Long> reachable = new HashSet<>();
		for (long nodeId : segmentSets.get(Kind.NODE).liveIds()) {
			if (!step(violations)) {
				return;
			}
			checkBlobs(violations, reachable, nodeId, nodes.propBlockId(nodeId));
		}
		for (long relId : segmentSets.get(Kind.REL).liveIds()) {
			if (!step(violations)) {
				return;
			}
			checkBlobs(violations, reachable, relId, rels.propBlockId(relId));
		}
		for (long extentId : segmentSets.get(Kind.BLOB).liveIds()) {
			if (!step(violations)) {
				return;
			}
			if (!reachable.contains(extentId)) {
				violations.add("Blob extent " + ElementId.toString(extentId)
					+ " is orphaned; no live property block references it");
			}
		}
	}

	private void checkBlobs(List<String> violations, Set<Long> reachable, long ownerId, long blockId) {
		if (ElementId.isNull(blockId) || !segmentSets.get(Kind.PROP).isLive(blockId)) {
			return;
		}
		// A chain cannot visit more extents than exist, so the live count is both a correct bound and a tight one.
		//
		// This was segmentCount() * slotsPerPage() + 1. A BLOB extent is page sized, so slotsPerPage() is 1 and the
		// bound came out at 2 for a single-segment store: every blob needing three or more extents — anything past
		// about 16 KiB — was reported as a chain that "does not terminate", and its unvisited extents were then
		// reported as orphaned on top. A false alarm on healthy data, which is worse than a missed one: it sends
		// somebody hunting corruption that is not there, and it made verify(FULL) unusable on any store holding real
		// blobs. Found by RollbackTest, which was the first test to write a blob and then verify.
		long limit = segmentSets.get(Kind.BLOB).liveCount() + 1;
		for (long blobId : properties.blobIds(blockId)) {
			long extent = blobId;
			int hops = 0;
			while (!ElementId.isNull(extent)) {
				if (!segmentSets.get(Kind.BLOB).isLive(extent)) {
					violations.add("Property block " + ElementId.toString(blockId) + " of "
						+ ElementId.toString(ownerId) + " references dead blob extent "
						+ ElementId.toString(extent));
					break;
				}
				if (!reachable.add(extent)) {
					violations.add("Blob extent " + ElementId.toString(extent)
						+ " is shared, or its chain is a cycle; reached from " + ElementId.toString(ownerId));
					break;
				}
				extent = blobs.nextExtent(extent);
				if (++hops > limit) {
					violations.add("Blob chain from " + ElementId.toString(blobId) + " does not terminate");
					break;
				}
			}
		}
	}

	private long withCurrentGeneration(Kind kind, long idWithoutGen) {
		int gen = segmentSets.get(kind).generationAt(ElementId.segment(idWithoutGen), ElementId.slot(idWithoutGen));
		return gen == 0 ? ElementId.NULL : ElementId.withGen(idWithoutGen, gen);
	}

	/** The neighbour that must carry a mirror entry, and the entry it must carry. */
	private record MirrorKey(long neighbour, long entry) {
	}
}
