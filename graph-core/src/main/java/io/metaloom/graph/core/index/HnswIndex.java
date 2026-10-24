package io.metaloom.graph.core.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongPredicate;

import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.id.ElementId;
import io.metaloom.graph.core.store.vec.VectorRecord;
import io.metaloom.graph.core.store.vec.VectorStore;

/**
 * A hierarchical navigable small-world graph over the store's vector records.
 *
 * <p>
 * The links live in the same record as the vector they belong to, so one visited node costs one page read — the
 * property the whole {@code VEC} kind exists for. Everything here is therefore about which links to write, not about
 * where to put them.
 * </p>
 *
 * <p>
 * <b>A dead link is skipped, never resolved.</b> Deleting a node frees its vector record with its property block, so a
 * neighbour's link to it goes stale immediately. Resolving one would throw; the generation in the id is what makes
 * "stale" detectable at all, and a slot that has been reused cannot alias because freeing advances that generation.
 * The cost is that connectivity decays as members are deleted, which is what a rebuild is for.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/VECTOR.md">spec/VECTOR.md</a>
 */
public final class HnswIndex {

	private final VectorStore vectors;
	private final IndexCatalog catalog;

	public HnswIndex(VectorStore vectors, IndexCatalog catalog) {
		this.vectors = vectors;
		this.catalog = catalog;
	}

	/** One candidate and its distance, ordered nearest first. Ties break on the id so a search is reproducible. */
	private record Candidate(long vectorId, double distance) {
	}

	private static final Comparator<Candidate> NEAREST_FIRST =
		Comparator.comparingDouble(Candidate::distance).thenComparingLong(Candidate::vectorId);

	/**
	 * The layer a record belongs to, drawn from the standard exponential distribution.
	 *
	 * <p>
	 * The randomness comes from a hash of the <b>vector's own bytes</b>, not from a generator and not from the record
	 * id. That makes a rebuild produce the same graph as the original — which is what lets a read-budget test assert
	 * an exact number of record reads — and it survives compaction, which changes every id but no vector.
	 * </p>
	 */
	public static int levelOf(float[] vector, int m, int maxLevel) {
		long h = 1125899906842597L;
		for (float component : vector) {
			h = h * 1099511628211L + Float.floatToRawIntBits(component);
		}
		// Mix, then take 53 bits as a uniform in (0, 1].
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		double u = ((h >>> 11) + 1) / (double) (1L << 53);
		int level = (int) Math.floor(-Math.log(u) / Math.log(m));
		return Math.max(0, Math.min(level, maxLevel));
	}

	/**
	 * The links one insert would write, worked out without writing anything.
	 *
	 * <p>
	 * This is the expensive half of an insert and it is <b>pure</b>, which is the entire point of separating it: at
	 * {@code efConstruction=200} it is on the order of two thousand distance evaluations, and a bulk build that ran it
	 * under the writer lock made a competing writer wait seconds ({@code VectorBuildBenchmark}). Computed in a read
	 * view and applied in a short write, the lock is held for the writes alone.
	 * </p>
	 *
	 * @param level
	 *            the record's top layer, derived from the vector's own bytes and so stable across a rebuild
	 * @param linksByLayer
	 *            index {@code i} holds the chosen neighbours for layer {@code i}, so the array's length is
	 *            {@code level + 1}. Empty when the graph was empty
	 * @param foundEmptyGraph
	 *            the graph had no usable entry point, so this record would become it. Re-checked on apply, because a
	 *            plan made against an empty graph is worthless against a populated one
	 * @param entryLevel
	 *            the entry point's level when the plan was made, so apply can tell whether this record outranks it
	 */
	public record Plan(int level, long[][] linksByLayer, boolean foundEmptyGraph, int entryLevel) {
	}

	/**
	 * Works out where a record belongs, reading only.
	 *
	 * <p>
	 * Safe to run in a read view: it touches no page for writing and takes nothing from the catalog it does not
	 * re-check on apply.
	 * </p>
	 *
	 * @return null if the record is already linked and there is nothing to do
	 */
	public Plan plan(int ord, VectorIndexDefinition definition, long vectorId) {
		if ((vectors.flags(vectorId) & VectorRecord.FLAG_LINKED) != 0) {
			return null;
		}
		int m = definition.m();
		float[] vector = vectors.read(vectorId);
		int level = levelOf(vector, m, vectors.maxLevel(m));

		long entry = catalog.vectorEntryPoint(ord);
		int entryLevel = catalog.vectorMaxLevel(ord);
		if (ElementId.isNull(entry) || !member(entry, ord)) {
			return new Plan(level, new long[0][], true, 0);
		}

		VectorMetric metric = definition.metric();
		double vectorNorm = queryNorm(vector, metric);
		long current = entry;
		for (int layer = entryLevel; layer > level; layer--) {
			current = descend(ord, current, vector, vectorNorm, metric, layer, m);
		}
		long[][] linksByLayer = new long[level + 1][];
		for (int layer = Math.min(entryLevel, level); layer >= 0; layer--) {
			List<Candidate> found = searchLayer(ord, current, vector, vectorNorm, metric, definition.efConstruction(),
				layer, m);
			List<Candidate> chosen = select(found, vectors.capacityAt(layer, m), vectorId, vector, metric);
			long[] links = new long[chosen.size()];
			for (int i = 0; i < links.length; i++) {
				links[i] = chosen.get(i).vectorId();
			}
			linksByLayer[layer] = links;
			if (!found.isEmpty()) {
				current = found.get(0).vectorId();
			}
		}
		for (int layer = 0; layer < linksByLayer.length; layer++) {
			// Layers above the entry point's were never searched, and an unset element would be a null to remember
			// about at every use site.
			if (linksByLayer[layer] == null) {
				linksByLayer[layer] = new long[0];
			}
		}
		return new Plan(level, linksByLayer, false, entryLevel);
	}

	/**
	 * Writes a plan into the graph. Must run in a write transaction, and does no searching.
	 *
	 * <p>
	 * <b>Every id in the plan is re-checked here.</b> The plan was made against an earlier view and an ordinary write
	 * may have deleted a chosen neighbour since; a link to a freed slot is exactly the dangling reference the
	 * generation check exists to catch, and writing one deliberately would be worse than the deletion that caused it.
	 * A neighbour that has gone is dropped rather than replaced — this is an approximate index, and one fewer link is a
	 * recall question, while a stale link is a correctness one.
	 * </p>
	 *
	 * @return false when the plan was made against a state too different to use, and the caller should re-plan
	 */
	public boolean apply(int ord, VectorIndexDefinition definition, long vectorId, Plan plan) {
		if ((vectors.flags(vectorId) & VectorRecord.FLAG_LINKED) != 0) {
			return true;
		}
		long entry = catalog.vectorEntryPoint(ord);
		int entryLevel = catalog.vectorMaxLevel(ord);
		boolean emptyNow = ElementId.isNull(entry) || !member(entry, ord);
		if (plan.foundEmptyGraph() != emptyNow) {
			// Either the graph gained an entry point while this was planned against nothing — in which case applying
			// the plan would strand this record with no links at all — or it lost the one the plan was built on. Both
			// are rare and both are answered by planning again against what is actually there.
			return false;
		}

		int m = definition.m();
		vectors.topLevel(vectorId, plan.level());
		if (plan.foundEmptyGraph()) {
			catalog.vectorEntryPoint(ord, vectorId, plan.level());
			vectors.flags(vectorId, vectors.flags(vectorId) | VectorRecord.FLAG_LINKED);
			return true;
		}

		VectorMetric metric = definition.metric();
		for (int layer = 0; layer < plan.linksByLayer().length; layer++) {
			long[] planned = plan.linksByLayer()[layer];
			long[] links = new long[planned.length];
			int kept = 0;
			for (long neighbour : planned) {
				// A member when planned may have left the index since, and a link to it would be a bridge into
				// whatever graph it has joined.
				if (member(neighbour, ord)) {
					links[kept++] = neighbour;
				}
			}
			if (kept < links.length) {
				links = java.util.Arrays.copyOf(links, kept);
			}
			vectors.links(vectorId, layer, m, links);
			// The back-links. Without them the graph is directed and a search that arrives from the other side never
			// finds this record at all. These read the neighbour's own list, which is bounded by M and cheap; it is the
			// beam search, not this, that had to leave the writer lock.
			for (long neighbour : links) {
				linkBack(ord, neighbour, vectorId, layer, m, metric);
			}
		}
		if (plan.level() > entryLevel) {
			catalog.vectorEntryPoint(ord, vectorId, plan.level());
		}
		vectors.flags(vectorId, vectors.flags(vectorId) | VectorRecord.FLAG_LINKED);
		return true;
	}

	/**
	 * Adds a record to the graph, planning and writing in one go. Idempotent: a record already marked linked is left
	 * alone.
	 * <p>
	 * This is the incremental path, used by the maintenance hooks, where the caller is already inside the write
	 * transaction that made the record and one insert's search is the cost of one write. The bulk build splits the two
	 * halves instead — see {@link #plan} for why.
	 */
	public void insert(int ord, VectorIndexDefinition definition, long vectorId) throws IOException {
		Plan plan = plan(ord, definition, vectorId);
		if (plan == null) {
			return;
		}
		if (!apply(ord, definition, vectorId, plan)) {
			// Unreachable as things stand — nothing can change the graph between these two calls, because they are in
			// one transaction on the one writer thread. Handled rather than asserted because the alternative is a
			// record silently left with no links, which no test would see and every later search would be worse for.
			Plan again = plan(ord, definition, vectorId);
			if (again != null && !apply(ord, definition, vectorId, again)) {
				throw new IllegalStateException("Could not link vector record " + ElementId.toString(vectorId)
					+ " into index " + ord + ": the graph's entry point changed twice inside one transaction");
			}
		}
	}

	/**
	 * Removes a record from the graph before its slot is freed.
	 * <p>
	 * The neighbours' links are left to go stale — rewriting them would be a random write per link — but the
	 * <b>entry point</b> is not: it is the one link whose loss disconnects everything rather than degrading it, so it
	 * is repaired by promoting a surviving neighbour.
	 */
	public void remove(int ord, VectorIndexDefinition definition, long vectorId) {
		if (catalog.vectorEntryPoint(ord) != vectorId) {
			return;
		}
		int m = definition.m();
		for (int layer = vectors.topLevel(vectorId); layer >= 0; layer--) {
			for (long neighbour : vectors.links(vectorId, layer, m)) {
				if (member(neighbour, ord) && !vectors.tombstoned(neighbour)) {
					catalog.vectorEntryPoint(ord, neighbour, vectors.topLevel(neighbour));
					return;
				}
			}
		}
		// Nothing survives that this record could hand over to, so the graph is empty.
		catalog.vectorEntryPoint(ord, ElementId.NULL, 0);
	}

	/**
	 * The {@code k} nearest members, as vector record ids.
	 *
	 * <p>
	 * {@code filter} is applied when <b>admitting</b> a result, never to decide where to walk. Filtering the
	 * candidates a greedy descent moves through is the classic way to collapse an approximate search's recall: the
	 * walk never reaches the region where the matches are and returns almost nothing, confidently. The cost of doing
	 * it correctly is that a selective filter needs a wider {@code ef} to find its {@code k}, which is why the beam
	 * widens with the filter rather than the other way round.
	 * </p>
	 */
	public List<Long> search(int ord, VectorIndexDefinition definition, float[] query, int k, int ef,
		LongPredicate filter) {
		long entry = catalog.vectorEntryPoint(ord);
		if (ElementId.isNull(entry) || !member(entry, ord)) {
			return List.of();
		}
		int m = definition.m();
		VectorMetric metric = definition.metric();
		double queryNorm = queryNorm(query, metric);
		long current = entry;
		for (int layer = catalog.vectorMaxLevel(ord); layer > 0; layer--) {
			current = descend(ord, current, query, queryNorm, metric, layer, m);
		}
		List<Candidate> found = searchLayer(ord, current, query, queryNorm, metric, Math.max(ef, k), 0, m);
		List<Long> result = new ArrayList<>(Math.min(k, found.size()));
		for (Candidate candidate : found) {
			if (vectors.tombstoned(candidate.vectorId())) {
				continue;
			}
			if (filter != null && !filter.test(candidate.vectorId())) {
				continue;
			}
			result.add(candidate.vectorId());
			if (result.size() == k) {
				break;
			}
		}
		return result;
	}

	/**
	 * Whether a link still names a member of <b>this</b> index, which is the only test that makes a link safe to follow.
	 *
	 * <p>
	 * A record that leaves an index is unlinked and stamped with its new ordinal, but the links <em>to</em> it from its
	 * old neighbours are left in place, exactly like a link to a deleted record. "Still allocated" is therefore not
	 * enough: the walk would reach the departed record and return it, and if it has since joined another index its own
	 * links lead into that graph, so a query for one index would be answered with another's members.
	 * </p>
	 */
	private boolean member(long vectorId, int ord) {
		return vectors.exists(vectorId) && vectors.indexOrdinal(vectorId) == ord;
	}

	/** Greedy descent through one upper layer: step to a nearer neighbour until none is nearer. */
	private long descend(int ord, long from, float[] query, double queryNorm, VectorMetric metric, int layer, int m) {
		long current = from;
		double best = distance(current, query, queryNorm, metric);
		boolean moved = true;
		while (moved) {
			moved = false;
			for (long neighbour : vectors.links(current, layer, m)) {
				if (!member(neighbour, ord)) {
					continue;
				}
				double d = distance(neighbour, query, queryNorm, metric);
				if (d < best) {
					best = d;
					current = neighbour;
					moved = true;
				}
			}
		}
		return current;
	}

	/** The standard beam search of one layer, returning its candidates nearest first. */
	private List<Candidate> searchLayer(int ord, long entry, float[] query, double queryNorm, VectorMetric metric,
		int ef, int layer, int m) {
		VisitedSet visited = new VisitedSet();
		PriorityQueue<Candidate> frontier = new PriorityQueue<>(NEAREST_FIRST);
		PriorityQueue<Candidate> best = new PriorityQueue<>(NEAREST_FIRST.reversed());

		Candidate start = new Candidate(entry, distance(entry, query, queryNorm, metric));
		frontier.add(start);
		best.add(start);
		visited.add(entry);

		while (!frontier.isEmpty()) {
			Candidate nearest = frontier.poll();
			if (best.size() >= ef && nearest.distance() > best.peek().distance()) {
				break;
			}
			for (long neighbour : vectors.links(nearest.vectorId(), layer, m)) {
				if (!visited.add(neighbour) || !member(neighbour, ord)) {
					continue;
				}
				double d = distance(neighbour, query, queryNorm, metric);
				// Allocated only once it is admitted: most neighbours of a converged beam are not.
				if (best.size() < ef || d < best.peek().distance()) {
					Candidate candidate = new Candidate(neighbour, d);
					frontier.add(candidate);
					best.add(candidate);
					if (best.size() > ef) {
						best.poll();
					}
				}
			}
		}
		List<Candidate> ordered = new ArrayList<>(best);
		ordered.sort(NEAREST_FIRST);
		return ordered;
	}

	/**
	 * Chooses which neighbours to keep, favouring <b>diversity</b> over pure proximity.
	 *
	 * <p>
	 * A candidate is kept only if it is nearer to the anchor than to any neighbour already chosen. Simply keeping the
	 * nearest {@code capacity} candidates is the obvious alternative and it silently breaks the graph: on a corpus
	 * with any clustering, every member of a tight cluster fills its budget with other members of that cluster, and a
	 * point outside it ends up with no in-links at all. It is then unreachable — present in the index, returned by
	 * nothing, and no error anywhere.
	 * </p>
	 *
	 * <p>
	 * This is the neighbour-selection heuristic from the HNSW paper, and it was added because a test inserted one
	 * outlier into forty clustered vectors and could not find it again.
	 * </p>
	 */
	private List<Candidate> select(List<Candidate> found, int capacity, long excluding, float[] anchor,
		VectorMetric metric) {
		List<Candidate> chosen = new ArrayList<>(Math.min(capacity, found.size()));
		List<float[]> chosenVectors = new ArrayList<>(chosen.size());
		List<Candidate> discarded = new ArrayList<>();
		for (Candidate candidate : found) {
			if (candidate.vectorId() == excluding || chosen.size() == capacity) {
				continue;
			}
			float[] vector = vectors.read(candidate.vectorId());
			boolean diverse = true;
			for (float[] already : chosenVectors) {
				if (distanceBetween(vector, already, metric) < candidate.distance()) {
					diverse = false;
					break;
				}
			}
			if (diverse) {
				chosen.add(candidate);
				chosenVectors.add(vector);
			} else {
				discarded.add(candidate);
			}
		}
		// Fill any remaining budget with the nearest of what the heuristic passed over. Leaving the slots empty would
		// trade one connectivity problem for a sparser graph.
		for (Candidate candidate : discarded) {
			if (chosen.size() == capacity) {
				break;
			}
			chosen.add(candidate);
		}
		chosen.sort(NEAREST_FIRST);
		return chosen;
	}

	private static double distanceBetween(float[] a, float[] b, VectorMetric metric) {
		return metric.rank(a, b);
	}

	/**
	 * Adds a back-link, pruning the neighbour's list to the nearest when it is full.
	 * <p>
	 * Pruning by distance rather than dropping the new link is what keeps the graph navigable: a node that filled its
	 * budget early would otherwise never acquire a better neighbour, and the region around it stays reachable only
	 * through whatever happened to arrive first.
	 */
	private void linkBack(int ord, long neighbour, long vectorId, int layer, int m, VectorMetric metric) {
		long[] existing = vectors.links(neighbour, layer, m);
		int capacity = vectors.capacityAt(layer, m);
		if (existing.length < capacity) {
			long[] grown = java.util.Arrays.copyOf(existing, existing.length + 1);
			grown[existing.length] = vectorId;
			vectors.links(neighbour, layer, m, grown);
			return;
		}
		float[] anchor = vectors.read(neighbour);
		double anchorNorm = queryNorm(anchor, metric);
		List<Candidate> candidates = new ArrayList<>(existing.length + 1);
		for (long link : existing) {
			if (member(link, ord)) {
				candidates.add(new Candidate(link, distance(link, anchor, anchorNorm, metric)));
			}
		}
		candidates.add(new Candidate(vectorId, distance(vectorId, anchor, anchorNorm, metric)));
		candidates.sort(NEAREST_FIRST);
		// The same diversity heuristic as on the way in. Pruning by proximity alone here is what strands an outlier:
		// its one in-link is the first thing a full, tightly clustered neighbour drops.
		List<Candidate> kept = select(candidates, capacity, neighbour, anchor, metric);
		long[] pruned = new long[kept.size()];
		for (int i = 0; i < pruned.length; i++) {
			pruned[i] = kept.get(i).vectorId();
		}
		vectors.links(neighbour, layer, m, pruned);
	}

	/**
	 * Distance from a stored record to a query, lower being nearer.
	 * <p>
	 * Cosine and dot are computed against the mapped bytes without materialising the stored vector, because this runs
	 * hundreds of times per query and the allocation would cost more than the arithmetic. Euclidean needs both vectors
	 * in hand and is the one that pays for it.
	 */
	private double distance(long vectorId, float[] query, double queryNorm, VectorMetric metric) {
		return switch (metric) {
			case DOT -> -vectors.dot(vectorId, query);
			case COSINE -> {
				double storedNorm = vectors.norm(vectorId);
				yield storedNorm == 0 || queryNorm == 0 ? 0
					: -(vectors.dot(vectorId, query) / (storedNorm * queryNorm));
			}
			case EUCLIDEAN -> io.metaloom.graph.core.api.Vectors.euclidean(vectors.read(vectorId), query);
		};
	}

	/**
	 * The query's norm, computed once per walk rather than once per distance. Only cosine needs it, and recomputing it
	 * per call cost as much as the dot product it divides — a pass over the query for every record visited.
	 */
	private static double queryNorm(float[] query, VectorMetric metric) {
		return metric == VectorMetric.COSINE ? io.metaloom.graph.core.api.Vectors.norm(query) : 0;
	}

	/**
	 * The ids one layer search has seen, without boxing them.
	 *
	 * <p>
	 * A {@code HashSet<Long>} allocated a {@code Long} and a map node per visited record, which at
	 * {@code efConstruction = 200} is a few thousand allocations per insert for a question that is one probe of a
	 * flat array. Open addressing with linear probing; zero is the empty marker because a vector id is never
	 * {@link ElementId#NULL}.
	 * </p>
	 */
	private static final class VisitedSet {

		private long[] slots = new long[256];
		private int size;

		/** Adds the id and reports whether it was new, as {@link Set#add} does. */
		boolean add(long id) {
			int mask = slots.length - 1;
			int at = mix(id) & mask;
			while (slots[at] != 0) {
				if (slots[at] == id) {
					return false;
				}
				at = (at + 1) & mask;
			}
			slots[at] = id;
			if (++size * 2 > slots.length) {
				grow();
			}
			return true;
		}

		private void grow() {
			long[] old = slots;
			slots = new long[old.length * 2];
			int mask = slots.length - 1;
			for (long id : old) {
				if (id != 0) {
					int at = mix(id) & mask;
					while (slots[at] != 0) {
						at = (at + 1) & mask;
					}
					slots[at] = id;
				}
			}
		}

		private static int mix(long id) {
			long x = id * 0x9E3779B97F4A7C15L;
			return (int) (x ^ (x >>> 32));
		}
	}
}
