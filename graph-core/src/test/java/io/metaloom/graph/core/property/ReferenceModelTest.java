package io.metaloom.graph.core.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.GraphStoreImpl;

/**
 * The differential harness: a random operation sequence applied to the store and to a naive in-memory model, compared
 * after every batch.
 * <p>
 * The specification calls this the single highest-value test in the suite, and the reason is that it does not encode
 * anyone's idea of what might go wrong. A hand-written test asserts what its author thought to check; this one asserts
 * that the store and a hundred lines of {@code HashMap} agree about <em>everything observable</em>, after thousands of
 * operations, across rollbacks, crashes and a compaction.
 * <p>
 * <b>Every run prints its seed.</b> A property test that cannot be reproduced is noise, so a failure prints the seed
 * and the operation index, and {@link #testReplayASpecificSeed()} exists to re-run one.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 4
 */
@Tag("unit")
public class ReferenceModelTest {

	private static final String[] LABELS = { "Person", "Asset", "Face", "Tag" };
	private static final String[] TYPES = { "OWNS", "DEPICTS", "TAGGED", "DERIVED_FROM" };
	private static final String[] KEYS = { "name", "size", "score", "active" };

	@Test
	public void testRandomOperationsMatchTheModel() throws Exception {
		long seed = 20260813L;
		System.out.println("ReferenceModelTest seed=" + seed);
		runSequence(seed, 60, 25, false);
	}

	@Test
	public void testRandomOperationsSurviveCrashesAndCompaction() throws Exception {
		long seed = 990113L;
		System.out.println("ReferenceModelTest (crash + compaction) seed=" + seed);
		runSequence(seed, 40, 25, true);
	}

	/** A hook for reproducing a failure: paste the printed seed here and run this one test. */
	@Test
	public void testReplayASpecificSeed() throws Exception {
		runSequence(4242L, 20, 20, true);
	}

	// ---------------------------------------------------------------- the harness

	private void runSequence(long seed, int batches, int opsPerBatch, boolean disruptions) throws Exception {
		Random random = new Random(seed);
		Path dir = Files.createTempDirectory("graphstore-refmodel-");
		GraphModel committed = new GraphModel();
		List<Long> nodeIds = new ArrayList<>();
		List<Long> relIds = new ArrayList<>();
		int operation = 0;
		boolean failed = false;

		GraphStoreImpl store = (GraphStoreImpl) GraphStore.open(dir, TempStore.options());
		try {
			for (int batch = 0; batch < batches; batch++) {
				GraphModel pending = committed.copy();
				List<Long> pendingNodes = new ArrayList<>(nodeIds);
				List<Long> pendingRels = new ArrayList<>(relIds);
				// A third of the batches are rolled back rather than committed, which is what proves that a discarded
				// transaction leaves nothing behind.
				boolean rollback = disruptions && random.nextInt(3) == 0;

				Transaction txn = store.begin();
				try {
					for (int i = 0; i < opsPerBatch; i++) {
						applyOne(store, pending, pendingNodes, pendingRels, random, operation++);
					}
					if (rollback) {
						txn.rollback();
					} else {
						txn.commit();
						committed = pending;
						nodeIds = pendingNodes;
						relIds = pendingRels;
					}
				} finally {
					txn.close();
				}

				if (disruptions && random.nextInt(6) == 0) {
					// A crash: no checkpoint, no flush. Everything committed must come back from the log.
					store.abandon();
					store = (GraphStoreImpl) GraphStore.open(dir, TempStore.options());
				}
				compare(store, committed, seed, operation);
				store.verify(VerifyLevel.FULL);
			}

			if (disruptions) {
				// Compaction invalidates ids, so the comparison after it is done through a stable property rather
				// than through the ids the model is holding.
				Map<String, String> before = fingerprintByName(store);
				store.compact(CompactionOptions.always());
				store.verify(VerifyLevel.FULL);
				assertEquals(before, fingerprintByName(store),
					"seed=" + seed + ": compaction changed the observable graph");
			}
		} catch (Throwable t) {
			failed = true;
			System.err.println("FAILED at seed=" + seed + " operation=" + operation + ", store kept at " + dir);
			throw t;
		} finally {
			try {
				store.close();
			} catch (IOException | IllegalStateException ignored) {
				// An abandoned store is already closed; the original failure is the interesting one.
			}
			if (!failed) {
				deleteRecursively(dir);
			}
		}
	}

	private void applyOne(GraphStore store, GraphModel model, List<Long> nodes, List<Long> rels, Random random,
		int operation) throws IOException {
		int roll = random.nextInt(100);
		if (nodes.size() < 5 || roll < 30) {
			String label = LABELS[random.nextInt(LABELS.length)];
			Map<String, Object> props = randomProperties(random);
			long id = store.createNode(label, toProperties(props));
			model.createNode(id, label, props);
			nodes.add(id);
		} else if (roll < 42) {
			long id = nodes.get(random.nextInt(nodes.size()));
			Map<String, Object> props = randomProperties(random);
			store.setNodeProperties(id, toProperties(props));
			model.setProperties(id, props);
		} else if (roll < 62) {
			long from = nodes.get(random.nextInt(nodes.size()));
			long to = nodes.get(random.nextInt(nodes.size()));
			String type = TYPES[random.nextInt(TYPES.length)];
			store.createEdge(from, type, to);
			model.createEdge(from, type, to);
		} else if (roll < 72 && !model.edges().isEmpty()) {
			GraphModel.Edge edge = model.edges().get(random.nextInt(model.edges().size()));
			boolean removed = store.deleteEdge(edge.from(), edge.type(), edge.to());
			assertTrue(removed, "operation " + operation + ": the store did not have an edge the model did");
			model.deleteEdge(edge.from(), edge.type(), edge.to());
		} else if (roll < 84) {
			long from = nodes.get(random.nextInt(nodes.size()));
			long to = nodes.get(random.nextInt(nodes.size()));
			String type = TYPES[random.nextInt(TYPES.length)];
			Map<String, Object> props = randomProperties(random);
			long id = store.createRelationship(from, type, to, toProperties(props));
			model.createRelationship(id, from, type, to, props);
			rels.add(id);
		} else if (roll < 90 && !rels.isEmpty()) {
			long id = rels.get(random.nextInt(rels.size()));
			if (model.relationship(id) != null) {
				Map<String, Object> props = randomProperties(random);
				store.setRelationshipProperties(id, toProperties(props));
				model.setRelationshipProperties(id, props);
			}
		} else if (roll < 95 && !rels.isEmpty()) {
			long id = rels.get(random.nextInt(rels.size()));
			if (model.relationship(id) != null) {
				store.deleteRelationship(id);
				model.deleteRelationship(id);
			}
		} else if (nodes.size() > 10) {
			long id = nodes.remove(random.nextInt(nodes.size()));
			if (model.hasNode(id)) {
				store.deleteNode(id);
				model.deleteNode(id);
			}
		}
	}

	// ---------------------------------------------------------------- comparison

	private void compare(GraphStore store, GraphModel model, long seed, int operation) {
		String where = "seed=" + seed + " after operation " + operation;

		for (long id : model.nodeIds()) {
			assertTrue(store.nodeExists(id), where + ": the store lost node " + id);
			assertEquals(model.label(id), store.nodeLabel(id), where + ": label of node " + id);
			assertEquals(normalise(model.properties(id)), asMap(store.nodeProperties(id)),
				where + ": properties of node " + id);
			assertEquals(model.degreeOut(id), store.degreeOut(id), where + ": degree_out of node " + id);
			assertEquals(model.degreeIn(id), store.degreeIn(id), where + ": degree_in of node " + id);
			assertEquals(model.neighbours(id, true, true), neighbours(store, id),
				where + ": neighbours of node " + id);
		}

		for (long id : model.relationshipIds()) {
			GraphModel.Relationship expected = model.relationship(id);
			assertTrue(store.relationshipExists(id), where + ": the store lost relationship " + id);
			assertEquals(expected.type(), store.relationshipType(id), where + ": type of relationship " + id);
			assertEquals(expected.from(), store.relationshipFrom(id), where + ": from of relationship " + id);
			assertEquals(expected.to(), store.relationshipTo(id), where + ": to of relationship " + id);
			assertEquals(normalise(expected.properties()), asMap(store.relationshipProperties(id)),
				where + ": properties of relationship " + id);
		}

		for (String label : LABELS) {
			assertEquals(model.nodesByLabel(label), new TreeSet<>(store.nodesByLabel(label)),
				where + ": the label index disagrees for " + label);
		}
		for (String key : KEYS) {
			for (Object value : sampleValues(model, key)) {
				assertEquals(model.nodesByProperty(key, value), new TreeSet<>(store.nodesByProperty(key, value)),
					where + ": the property index disagrees for " + key + "=" + value);
			}
		}
		assertEquals(model.nodeIds().size(), store.stats().nodeCount(), where + ": live node count");
		assertEquals(model.relationshipIds().size(), store.stats().relationshipCount(),
			where + ": live relationship count");
	}

	/** A few values actually present in the model, so the index comparison covers hits as well as misses. */
	private Set<Object> sampleValues(GraphModel model, String key) {
		Set<Object> values = new java.util.LinkedHashSet<>();
		for (long id : model.nodeIds()) {
			Object value = model.properties(id).get(key);
			if (value != null && values.size() < 4) {
				values.add(value);
			}
		}
		values.add("absent-value-that-nothing-has");
		return values;
	}

	private List<String> neighbours(GraphStore store, long id) {
		List<String> result = new ArrayList<>();
		for (Edge edge : store.neighbours(id, Direction.BOTH)) {
			result.add(edge.direction() + ":" + edge.type() + ":" + edge.neighbourId());
		}
		result.sort(null);
		return result;
	}

	/**
	 * Every node keyed by its unique {@code name}, with its whole neighbourhood flattened. Ids do not appear, which is
	 * what makes this comparable across a compaction.
	 */
	private Map<String, String> fingerprintByName(GraphStore store) {
		Map<Long, String> names = new java.util.HashMap<>();
		for (String label : LABELS) {
			for (long id : store.nodesByLabel(label)) {
				names.put(id, String.valueOf(store.nodeProperties(id).getString("name")));
			}
		}
		Map<String, String> result = new java.util.TreeMap<>();
		names.forEach((id, name) -> {
			List<String> around = new ArrayList<>();
			for (Edge edge : store.neighbours(id, Direction.BOTH)) {
				around.add(edge.direction() + ":" + edge.type() + ":" + names.get(edge.neighbourId()));
			}
			around.sort(null);
			String key = name + "#" + store.nodeLabel(id);
			// Names are random and may repeat, so collisions are folded rather than silently dropped.
			result.merge(key, around.toString(), (a, b) -> a + "|" + b);
		});
		return result;
	}

	// ---------------------------------------------------------------- values

	private Map<String, Object> randomProperties(Random random) {
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("name", "n" + random.nextInt(10_000));
		if (random.nextBoolean()) {
			props.put("size", (long) random.nextInt(1000));
		}
		if (random.nextBoolean()) {
			props.put("score", random.nextDouble());
		}
		if (random.nextBoolean()) {
			props.put("active", random.nextBoolean());
		}
		if (random.nextInt(20) == 0) {
			// Past the 255 byte inline limit, so the value escapes into the blob store.
			props.put("blob", "b".repeat(300 + random.nextInt(2000)));
		}
		return props;
	}

	private Properties toProperties(Map<String, Object> props) {
		Properties result = Properties.of();
		props.forEach(result::set);
		return result;
	}

	private Map<String, Object> normalise(Map<String, Object> props) {
		Map<String, Object> result = new java.util.TreeMap<>();
		props.forEach((key, value) -> result.put(key, GraphModel.normalise(value)));
		return result;
	}

	private Map<String, Object> asMap(Properties props) {
		Map<String, Object> result = new java.util.TreeMap<>();
		props.asMap().forEach((key, value) -> result.put(key, GraphModel.normalise(value)));
		return result;
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}

	@Test
	public void testTheModelItselfIsSane() {
		// A harness whose oracle is broken proves nothing, so the oracle gets a test too.
		GraphModel model = new GraphModel();
		model.createNode(1, "A", Map.of("k", 1L));
		model.createNode(2, "B", Map.of());
		model.createEdge(1, "E", 2);
		model.createEdge(1, "E", 2);
		assertEquals(2, model.degreeOut(1));
		assertEquals(2, model.degreeIn(2));
		assertTrue(model.deleteEdge(1, "E", 2));
		assertEquals(1, model.degreeOut(1));
		model.deleteNode(2);
		assertEquals(0, model.degreeOut(1));
		assertEquals(Set.of(1L), model.nodesByLabel("A"));
		assertEquals(Set.of(1L), model.nodesByProperty("k", 1));
		assertEquals(Set.of(), model.nodesByProperty("k", 2));
		if (model.hasNode(2)) {
			fail("a deleted node must not remain in the model");
		}
	}
}
