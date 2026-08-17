package io.metaloom.graph.core.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.property.GraphModel;

/**
 * The history of everything a store has committed, keyed by commit LSN, so that a reader can be checked against the
 * state it is <em>entitled</em> to see rather than merely against a state that looks plausible.
 *
 * <p>
 * This works here because of two properties the engine happens to have together. Writers are serialised, so there is
 * exactly one history rather than a partial order of interleavings; and the snapshot timestamp <em>is</em> the LSN,
 * so a view names the exact point in that history it was fixed at. A view opened at LSN {@code L} must therefore
 * observe precisely {@link #stateAt(long)} for {@code L} — not "a consistent state", not "some state", <b>that</b>
 * state. There is no weaker version of the assertion to hide behind.
 * </p>
 *
 * <p>
 * That is a strictly stronger oracle than the hand-written anomaly matrix in {@code SnapshotIsolationTest}: a dirty
 * read, a non-repeatable read, a phantom and a lost update are all just specific ways of failing it, and so are the
 * ones nobody thought to name.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/TRANSACTIONS.md">spec/TRANSACTIONS.md</a> section 3
 */
public final class ModelHistory {

	/**
	 * Bounded so a long run cannot exhaust the heap. A reader whose view predates everything retained cannot be checked
	 * and says so rather than guessing — see {@link #stateAt(long)}.
	 */
	private final int retained;

	private final ConcurrentSkipListMap<Long, GraphModel> states = new ConcurrentSkipListMap<>();
	private volatile long highest = -1;

	public ModelHistory() {
		this(512);
	}

	public ModelHistory(int retained) {
		this.retained = retained;
	}

	/**
	 * Records the state the store reached at {@code commitLsn}. Call it from the writer, immediately after the commit
	 * that produced that LSN, with the model already advanced by the same operation.
	 */
	public void record(long commitLsn, GraphModel model) {
		states.put(commitLsn, model.copy());
		highest = commitLsn;
		while (states.size() > retained) {
			states.pollFirstEntry();
		}
	}

	/** The newest state committed at or before {@code lsn}, or null if the history no longer reaches back that far. */
	public GraphModel stateAt(long lsn) {
		Map.Entry<Long, GraphModel> entry = states.floorEntry(lsn);
		return entry == null ? null : entry.getValue();
	}

	public long highestRecorded() {
		return highest;
	}

	/**
	 * Waits until the writer has recorded the state for {@code lsn}.
	 *
	 * <p>
	 * Needed because a commit becomes visible before the test has written it down: {@code appliedLsn} is published
	 * under the apply lock inside {@code commit()}, so a reader can open a view at an LSN whose model state the writer
	 * is, for a few microseconds, still on its way to recording. Without this wait the reader compares against the
	 * previous state and reports a failure that is entirely the harness's own.
	 * </p>
	 *
	 * @return false if the writer stopped without ever reaching that LSN, in which case there is nothing to check
	 */
	public boolean awaitRecorded(long lsn, long timeoutMillis) {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		while (highest < lsn) {
			if (System.nanoTime() > deadline) {
				return false;
			}
			Thread.onSpinWait();
		}
		return true;
	}

	// ---------------------------------------------------------------- comparison

	/**
	 * Compares the store, as the calling thread currently sees it, against {@code expected}, and returns every
	 * difference rather than the first.
	 *
	 * <p>
	 * Every difference, because a visibility defect has a shape — "one node is stale" and "every node created after
	 * LSN N is present" are different bugs and the second is only recognisable from the whole list.
	 * </p>
	 */
	public static List<String> differences(GraphStore store, GraphModel expected) {
		List<String> problems = new ArrayList<>();

		Set<Long> expectedIds = expected.nodeIds();
		List<Long> actualIds = new ArrayList<>();
		store.forEachNode(actualIds::add);

		for (Long id : actualIds) {
			if (!expectedIds.contains(id)) {
				problems.add("node " + id + " is visible but was not committed at this view's LSN");
			}
		}
		for (Long id : expectedIds) {
			if (!actualIds.contains(id)) {
				problems.add("node " + id + " was committed at this view's LSN but is not visible");
				continue;
			}
			compareNode(store, expected, id, problems);
		}
		return problems;
	}

	private static void compareNode(GraphStore store, GraphModel expected, long id, List<String> problems) {
		List<String> expectedLabels = expected.labels(id);
		List<String> actualLabels = store.nodeLabels(id);
		if (!Set.copyOf(expectedLabels).equals(Set.copyOf(actualLabels))) {
			problems.add("node " + id + " labels: expected " + expectedLabels + " but saw " + actualLabels);
		}

		Map<String, Object> expectedProps = expected.properties(id);
		Map<String, Object> actualProps = store.nodeProperties(id).asMap();
		for (Map.Entry<String, Object> e : expectedProps.entrySet()) {
			Object actual = GraphModel.normalise(actualProps.get(e.getKey()));
			Object want = GraphModel.normalise(e.getValue());
			if (!java.util.Objects.equals(want, actual)) {
				problems.add("node " + id + " property '" + e.getKey() + "': expected " + want + " but saw " + actual);
			}
		}
		for (String key : actualProps.keySet()) {
			if (!expectedProps.containsKey(key)) {
				problems.add("node " + id + " has property '" + key + "' that was not committed at this view's LSN");
			}
		}

		int expectedOut = expected.degreeOut(id);
		int actualOut = store.degreeOut(id);
		if (expectedOut != actualOut) {
			problems.add("node " + id + " out-degree: expected " + expectedOut + " but saw " + actualOut);
		}

		// The degree counter and the adjacency list are maintained separately, so a view that reconstructs one page and
		// not another can disagree with itself. Comparing both is what catches that.
		List<Edge> neighbours = store.neighbours(id, Direction.OUT);
		if (neighbours.size() != expectedOut) {
			problems.add("node " + id + " reports out-degree " + actualOut + " but enumerated "
				+ neighbours.size() + " outgoing edges");
		}
	}
}
