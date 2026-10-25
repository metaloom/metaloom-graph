package io.metaloom.graph.cli.dump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.CompositeIndex;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.api.VerifyLevel;

/**
 * Dump a graph, restore it into a fresh store, and require the two to be the same graph.
 *
 * <h2>The comparison is an isomorphism, not an equality</h2>
 *
 * <p>
 * A restore does not preserve element ids — a fresh store allocates its own slots — so an id-by-id comparison would be
 * asserting something the design explicitly does not promise. The graph is therefore compared through a <b>key
 * property</b> that every fixture node carries: nodes are matched by key, and an edge is compared as
 * {@code (fromKey, type, toKey, materialised, properties)}. That is the strongest statement that is actually true, and
 * it is the one an operator cares about.
 * </p>
 *
 * <h2>The fixture is deliberately awkward</h2>
 *
 * <p>
 * Every shape that has its own code path in the engine or its own branch in the dump: a node with several labels and a
 * node with none, a lightweight edge beside a materialised one, a self-loop, two edges between one pair, and every
 * property type the storage layer can hold — including the three the query language cannot write ({@code byte[]},
 * {@code Instant}, {@code UUID}) and the one the wire codec cannot carry ({@code float[]}). Those four are exactly the
 * reason this format has its own codec, so a round trip that did not contain them would be testing the easy half.
 * </p>
 */
@Tag("unit")
public class BackupRoundTripTest {

	@TempDir
	Path tmp;

	private static final int DIMENSIONS = 8;

	/** A node as the comparison sees it: what it is, with no reference to where it lives. */
	private record NodeShape(List<String> labels, Map<String, String> properties) {
	}

	private record EdgeShape(String from, String type, String to, boolean materialised,
		Map<String, String> properties) {
	}

	private static GraphStoreOptions options() {
		return GraphStoreOptions.builder()
			.pageSize(8192)
			.segmentCapacity(8L * 1024 * 1024)
			.vectorDimensions(DIMENSIONS)
			.build();
	}

	private static float[] vector(float seed) {
		float[] v = new float[DIMENSIONS];
		for (int i = 0; i < DIMENSIONS; i++) {
			v[i] = seed + i;
		}
		return v;
	}

	/**
	 * Seeds the awkward graph.
	 *
	 * @return the key property every node carries, so the comparison has something id-independent to match on
	 */
	private static void seed(GraphStore store) throws Exception {
		try (Transaction txn = store.begin()) {
			long alice = store.createNode(List.of("Person", "Employee"), Properties.of()
				.set("key", "alice")
				.set("name", "Alice")
				.set("age", 34L)
				.set("score", 1.5d)
				.set("active", true)
				.set("blob", new byte[] { 1, 2, 3, (byte) 0xFF })
				.set("seen", Instant.ofEpochSecond(1_700_000_000L, 123_456_789))
				.set("uuid", UUID.fromString("0b3d84c4-1c8f-4a1e-9c31-2f2c3d4e5f60"))
				.set("embedding", vector(0.5f))
				.set("tags", List.of("a", "b", "c"))
				// Long enough that the storage codec would escape it to the blob store, which is exactly why the dump
				// cannot reuse that codec.
				.set("bio", "x".repeat(1000)));
			long bob = store.createNode(List.of("Person"), Properties.of().set("key", "bob").set("name", "Bob"));
			// A node with no labels at all, which is legal and has its own path.
			long anon = store.createNode(List.of(), Properties.of().set("key", "anon"));
			long widget = store.createNode(List.of("Product"), Properties.of()
				.set("key", "widget")
				.set("name", "Widget")
				.set("embedding", vector(9.5f)));

			// Materialised, with properties.
			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L).set("close", true));
			// A second relationship between the same pair, same direction: parallel edges must survive as two.
			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2021L));
			// Lightweight: no record, no id. Must come back lightweight.
			store.createEdge(alice, "BOUGHT", widget);
			store.createEdge(bob, "BOUGHT", widget);
			// A self-loop, which must appear exactly once.
			store.createEdge(alice, "SUPERVISES", alice);
			// An edge touching the label-less node.
			store.createRelationship(anon, "WATCHES", widget, Properties.of().set("note", "quietly"));
			txn.commit();
		}

		store.createIndex("Person", "name");
		store.createUniqueConstraint("Person", "key");
		store.createUniqueConstraint("uuid");
		store.createVectorIndex(VectorIndexDefinition.of("Person", "embedding", DIMENSIONS, VectorMetric.COSINE));
	}

	// ---------------------------------------------------------------- comparison

	private static String describe(Object value) {
		if (value instanceof byte[] bytes) {
			return "bytes" + java.util.Arrays.toString(bytes);
		}
		if (value instanceof float[] floats) {
			return "vector" + java.util.Arrays.toString(floats);
		}
		return value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
	}

	private static Map<String, String> propertiesOf(Properties properties) {
		Map<String, String> out = new TreeMap<>();
		for (String key : properties.keys()) {
			out.put(key, describe(properties.get(key)));
		}
		return out;
	}

	private static Map<String, NodeShape> nodesOf(GraphStore store) {
		Map<String, NodeShape> shapes = new LinkedHashMap<>();
		store.forEachNode(id -> {
			Properties properties = store.nodeProperties(id);
			String key = properties.getString("key");
			shapes.put(key, new NodeShape(store.nodeLabels(id), propertiesOf(properties)));
		});
		return shapes;
	}

	private static List<EdgeShape> edgesOf(GraphStore store) {
		List<EdgeShape> edges = new ArrayList<>();
		store.forEachNode(from -> {
			String fromKey = store.nodeProperties(from).getString("key");
			for (Edge edge : store.neighbours(from, Direction.OUT)) {
				String toKey = store.nodeProperties(edge.neighbourId()).getString("key");
				Map<String, String> properties = edge.isMaterialised()
					? propertiesOf(store.relationshipProperties(edge.relationshipId()))
					: Map.of();
				edges.add(new EdgeShape(fromKey, edge.type(), toKey, edge.isMaterialised(), properties));
			}
		});
		// Sorted, because neither the scan order nor the adjacency order is part of what a restore promises.
		edges.sort(Comparator.comparing(EdgeShape::from).thenComparing(EdgeShape::type)
			.thenComparing(EdgeShape::to).thenComparing(e -> e.properties().toString()));
		return edges;
	}

	private static List<String> schemaOf(GraphStore store) {
		List<String> schema = new ArrayList<>();
		for (CompositeIndex index : store.compositeIndexes()) {
			schema.add("composite " + index.label() + "." + index.key() + (index.unique() ? " unique" : ""));
		}
		for (String key : store.uniqueConstraints()) {
			schema.add("unique " + key);
		}
		for (VectorIndexDefinition index : store.vectorIndexes()) {
			schema.add("vector " + index.label() + "." + index.key() + " " + index.dimensions() + " "
				+ index.metric() + " m=" + index.m() + " ef=" + index.efConstruction());
		}
		schema.sort(Comparator.naturalOrder());
		return schema;
	}

	// ---------------------------------------------------------------- the round trip

	@Test
	public void testEveryAwkwardShapeSurvivesADumpAndRestore() throws Exception {
		Map<String, NodeShape> sourceNodes;
		List<EdgeShape> sourceEdges;
		List<String> sourceSchema;
		byte[] dump;
		GraphBackup.Summary summary;

		try (GraphStore source = GraphStore.open(tmp.resolve("source"), options())) {
			seed(source);
			source.verify(VerifyLevel.FULL);
			sourceNodes = nodesOf(source);
			sourceEdges = edgesOf(source);
			sourceSchema = schemaOf(source);

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			summary = GraphBackup.write(source, "source", out, 1_700_000_000_000L);
			dump = out.toByteArray();
		}

		assertEquals(4, summary.nodes());
		assertEquals(3, summary.relationships(), "KNOWS twice and WATCHES once");
		assertEquals(3, summary.lightweightEdges(), "two BOUGHT and the self-loop");

		try (GraphStore target = GraphStore.open(tmp.resolve("target"), options())) {
			GraphRestore.Summary restored;
			try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
				restored = GraphRestore.read(target, in);
			}
			assertEquals(4, restored.nodes());
			assertEquals(3, restored.relationships());
			assertEquals(3, restored.lightweightEdges());

			// The engine's own oracle, entirely outside this code: bidirectional edges, degree counters, adjacency
			// chains, no dangling references, no orphan blocks.
			target.verify(VerifyLevel.FULL);

			assertEquals(sourceNodes, nodesOf(target), "every node, its labels and every property value and type");
			assertEquals(sourceEdges, edgesOf(target), "every edge, including which ones are lightweight");
			assertEquals(sourceSchema, schemaOf(target), "and the declared schema, which is not derivable from data");
		}
	}

	/**
	 * The four types the query language and the wire codec cannot carry, checked value by value.
	 *
	 * <p>
	 * <b>Compared against what the source store holds, not against what was written into it.</b> Those are different
	 * things for at least one type: the engine encodes an {@code Instant} as epoch <em>micros</em>
	 * ({@code ValueCodec}), so a nanosecond-precision value is already truncated by the time any backup could see it.
	 * A dump's promise is to reproduce the store, and asserting anything stronger would be testing the engine's
	 * precision through the wrong lens — and would fail here for a reason that has nothing to do with backups.
	 * </p>
	 */
	@Test
	public void testTheTypesNoOtherCodecCanCarryRoundTripExactly() throws Exception {
		byte[] dump;
		Properties before;
		try (GraphStore source = GraphStore.open(tmp.resolve("source"), options())) {
			seed(source);
			before = source.nodeProperties(source.nodesByProperty("key", "alice").get(0));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			GraphBackup.write(source, "source", out, 0);
			dump = out.toByteArray();
		}
		try (GraphStore target = GraphStore.open(tmp.resolve("target"), options())) {
			try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
				GraphRestore.read(target, in);
			}
			Properties after = target.nodeProperties(target.nodesByProperty("key", "alice").get(0));

			assertArrayEquals(before.getBytes("blob"), after.getBytes("blob"));
			assertArrayEquals(new byte[] { 1, 2, 3, (byte) 0xFF }, after.getBytes("blob"), "and it is the real bytes");
			assertEquals(before.getInstant("seen"), after.getInstant("seen"));
			assertEquals(Instant.ofEpochSecond(1_700_000_000L, 123_456_000), after.getInstant("seen"),
				"microseconds, which is the precision the store itself keeps");
			assertEquals(before.getUuid("uuid"), after.getUuid("uuid"));
			assertEquals(UUID.fromString("0b3d84c4-1c8f-4a1e-9c31-2f2c3d4e5f60"), after.getUuid("uuid"));
			assertArrayEquals(before.getVector("embedding"), after.getVector("embedding"), 0f);
			assertArrayEquals(vector(0.5f), after.getVector("embedding"), 0f);
			assertEquals(before.get("tags"), after.get("tags"));
			assertEquals("x".repeat(1000), after.getString("bio"), "and a value the storage codec would blob");
		}
	}

	/**
	 * A store that has had most of itself deleted and then been compacted dumps exactly what is left.
	 *
	 * <p>
	 * Compaction relocates records and rewrites every reference to them, and deletion leaves free slots, reused
	 * generations and holes in the adjacency. A dump walks whatever the store says is live, so this is the shape most
	 * likely to show a walk that trusts a stale reference — and a restore that reproduces it, which would put the
	 * defect into the copy too. Vectors are in it on purpose: compaction remaps their links and entry points.
	 * </p>
	 */
	@Test
	public void testAStoreAfterDeletesAndCompactionRoundTrips() throws Exception {
		byte[] dump;
		Map<String, NodeShape> sourceNodes;
		List<EdgeShape> sourceEdges;
		List<String> sourceSchema;
		try (GraphStore source = GraphStore.open(tmp.resolve("source"), options())) {
			seed(source);
			List<Long> ring = new ArrayList<>();
			try (Transaction txn = source.begin()) {
				for (int i = 0; i < 600; i++) {
					ring.add(source.createNode(List.of("Item"), Properties.of().set("key", "item" + i)
						.set("n", (long) i).set("embedding", vector(i))));
				}
				for (int i = 0; i < ring.size(); i++) {
					long next = ring.get((i + 1) % ring.size());
					if (i % 2 == 0) {
						source.createRelationship(ring.get(i), "NEXT", next, Properties.of().set("i", (long) i));
					} else {
						source.createEdge(ring.get(i), "NEXT", next);
					}
					source.createEdge(ring.get(i), "SKIP", ring.get((i + 7) % ring.size()));
				}
				txn.commit();
			}
			source.createVectorIndex(VectorIndexDefinition.of("Item", "embedding", DIMENSIONS, VectorMetric.EUCLIDEAN));
			try (Transaction txn = source.begin()) {
				// Two of every three go, edges with them, so the survivors sit in relocatable holes.
				for (int i = 0; i < ring.size(); i++) {
					if (i % 3 != 0) {
						// Its relationships and both kinds of adjacency entry go with it.
						source.deleteNode(ring.get(i));
					}
				}
				txn.commit();
			}
			source.compact();
			source.verify(VerifyLevel.FULL);
			sourceNodes = nodesOf(source);
			sourceEdges = edgesOf(source);
			sourceSchema = schemaOf(source);
			assertEquals(4 + 200, sourceNodes.size(), "the fixture's own count after the deletes");

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			GraphBackup.write(source, "source", out, 0);
			dump = out.toByteArray();
		}
		try (GraphStore target = GraphStore.open(tmp.resolve("target"), options())) {
			try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
				GraphRestore.read(target, in);
			}
			target.verify(VerifyLevel.FULL);
			assertEquals(sourceNodes, nodesOf(target));
			assertEquals(sourceEdges, edgesOf(target));
			assertEquals(sourceSchema, schemaOf(target));
			// The restored vector index answers, and answers with a survivor.
			var nearest = target.nearestByScan("Item", "embedding", vector(300), 1, VectorMetric.EUCLIDEAN);
			assertEquals(1, nearest.size());
			assertEquals("item300", target.nodeProperties(nearest.get(0).nodeId()).getString("key"));
		}
	}

	/** An empty database dumps and restores to an empty database rather than to a failure. */
	@Test
	public void testAnEmptyDatabase() throws Exception {
		byte[] dump;
		try (GraphStore source = GraphStore.open(tmp.resolve("source"), options())) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			GraphBackup.Summary summary = GraphBackup.write(source, "source", out, 0);
			assertEquals(0, summary.nodes());
			dump = out.toByteArray();
		}
		try (GraphStore target = GraphStore.open(tmp.resolve("target"), options())) {
			try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
				assertEquals(0, GraphRestore.read(target, in).nodes());
			}
			target.verify(VerifyLevel.FULL);
		}
	}

	/** A dump is readable without a store, which is what makes `inspect` possible and a bad file cheap to diagnose. */
	@Test
	public void testTheHeaderCanBeReadWithoutOpeningAnything() throws Exception {
		byte[] dump;
		try (GraphStore source = GraphStore.open(tmp.resolve("source"), options())) {
			seed(source);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			GraphBackup.write(source, "mydb", out, 1_700_000_000_000L);
			dump = out.toByteArray();
		}
		try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
			GraphRestore.Header header = GraphRestore.header(in);
			assertEquals(DumpFormat.VERSION, header.version());
			assertEquals("mydb", header.database());
			assertEquals(1_700_000_000_000L, header.takenAtEpochMillis());
			assertEquals(DIMENSIONS, header.geometry().vectorDimensions());
			assertEquals(8192, header.geometry().pageSize());
		}
	}

	/** A dump with vectors cannot be restored into a store that has no vector storage, and says why. */
	@Test
	public void testAGeometryMismatchIsRefusedWithTheReason() throws Exception {
		byte[] dump;
		try (GraphStore source = GraphStore.open(tmp.resolve("source"), options())) {
			seed(source);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			GraphBackup.write(source, "source", out, 0);
			dump = out.toByteArray();
		}
		GraphStoreOptions noVectors = GraphStoreOptions.builder()
			.pageSize(8192).segmentCapacity(8L * 1024 * 1024).build();
		try (GraphStore target = GraphStore.open(tmp.resolve("target"), noVectors)) {
			try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
				DumpFormatException refused = assertThrows(DumpFormatException.class,
					() -> GraphRestore.read(target, in));
				assertTrue(refused.getMessage().contains("fixed when a store is created"), refused.getMessage());
			}
			assertEquals(0, target.stats().nodeCount(), "and nothing was written into it");
		}
	}

	/** A dump on disk behaves as one in memory, which is the shape the CLI actually uses. */
	@Test
	public void testThroughAFileOnDisk() throws Exception {
		Path file = tmp.resolve("graph.dump");
		try (GraphStore source = GraphStore.open(tmp.resolve("source"), options())) {
			seed(source);
			try (var out = Files.newOutputStream(file)) {
				GraphBackup.write(source, "source", out, 0);
			}
		}
		assertTrue(Files.size(file) > 0);
		try (GraphStore target = GraphStore.open(tmp.resolve("target"), options())) {
			try (DumpReader in = new DumpReader(Files.newInputStream(file), Files.size(file))) {
				assertEquals(4, GraphRestore.read(target, in).nodes());
			}
			target.verify(VerifyLevel.FULL);
		}
	}
}
