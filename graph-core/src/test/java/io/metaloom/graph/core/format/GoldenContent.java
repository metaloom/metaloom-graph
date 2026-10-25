package io.metaloom.graph.core.format;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;

import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.CompositeIndex;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Neighbour;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Role;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.User;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;
import io.metaloom.graph.core.store.prop.ValueCodec;

/**
 * What a golden fixture holds, and the canonical text form it is compared in.
 *
 * <p>
 * The content is chosen for the code paths it takes rather than for realism: every property value type including the
 * internal element reference, strings either side of the 255 byte inline limit and one long enough for a multi-extent
 * blob, vectors that go to {@code VEC} records and one of another length that goes to a blob, a node with five labels
 * (two past the three the record holds inline), a node with none, lightweight and materialised edges, parallel edges,
 * a self-loop, a hub whose adjacency outgrows the inline slots and its first cell, deleted nodes and relationships
 * whose slots are then reused at a higher generation, and the declared schema — a composite index, a store-wide and a
 * label-scoped unique constraint and a built vector index. The geometry is tiny (4 KiB pages, 32 KiB segments) so the
 * store rolls over into several segments per kind and still compresses to a few kilobytes.
 * </p>
 *
 * <h2>Rules for changing this class</h2>
 *
 * <ul>
 * <li><b>{@link #canonical} must render every committed fixture's content exactly as it did when that fixture was
 * generated.</b> Each fixture's {@code expected.txt} was written by the version of this method current at the time,
 * and an old fixture is never regenerated. So a new section of output may only print something for content an older
 * fixture cannot contain — a new kind of index prints nothing when there is none.</li>
 * <li>{@link #write} may grow freely: a new fixture captures whatever it writes at the time.</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../../spec/MIGRATION.md">spec/MIGRATION.md</a> section 6
 */
public final class GoldenContent {

	/** The database directory name inside a fixture's root. */
	public static final String DATABASE = "golden";

	public static final int DIMENSIONS = 8;

	public static final String USER = "analyst";

	public static final String PASSWORD = "golden-fixture-password";

	private GoldenContent() {
	}

	public static GraphStoreOptions options() {
		return GraphStoreOptions.builder()
			.pageSize(4096)
			.segmentCapacity(32L * 1024)
			.walSegmentBytes(1024L * 1024)
			.vectorDimensions(DIMENSIONS)
			.build();
	}

	static float[] vector(float seed) {
		float[] v = new float[DIMENSIONS];
		for (int i = 0; i < DIMENSIONS; i++) {
			v[i] = seed + i * 0.25f;
		}
		return v;
	}

	/**
	 * {@code length} characters of seeded noise. Not a repeated pattern: blobs are compressed, and a compressible
	 * string would fit in far fewer extents than its length suggests. {@link java.util.Random} rather than anything
	 * newer because its sequence is specified, so the same seed is the same string on every JDK.
	 */
	static String text(String seed, int length) {
		java.util.Random random = new java.util.Random(seed.hashCode());
		char[] out = new char[length];
		for (int i = 0; i < length; i++) {
			int c = random.nextInt(27);
			out[i] = c == 26 ? ' ' : (char) ('a' + c);
		}
		return new String(out);
	}

	/** Writes the fixture's graph into an empty store. Deterministic: the same calls in the same order every time. */
	public static void write(GraphStore store) throws IOException {
		// Slots that are used, freed and reused, so the fixture holds generations above 1 and a bitmap with holes.
		List<Long> ghosts = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 12; i++) {
				ghosts.add(store.createNode("Ghost", Properties.of().set("uid", "ghost-" + i)));
			}
			txn.commit();
		}
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < ghosts.size(); i += 2) {
				store.deleteNode(ghosts.get(i));
			}
			txn.commit();
		}

		long temporary;
		long bob;
		long anon;
		try (Transaction txn = store.begin()) {
			long carol = store.createNode(List.of("Person", "Manager", "Admin", "Auditor", "Remote"), Properties.of()
				.set("uid", "carol")
				.set("name", "Carol")
				.set("embedding", vector(3.0f))
				// Ten page-sized extents: more than the eight one 32 KiB blob segment holds, so the store rolls over.
				.set("memoir", text("memoir", 40_000)));
			long alice = store.createNode(List.of("Person", "Employee"), Properties.of()
				.set("uid", "alice")
				.set("name", "Alice")
				.set("age", 34L)
				.set("small", 7)
				.set("score", 1.5d)
				.set("ratio", 0.25f)
				.set("active", true)
				.set("bytes", new byte[] { 1, 2, 3, (byte) 0xFF })
				.set("seen", Instant.ofEpochSecond(1_700_000_000L, 123_456_789))
				.set("uuid", UUID.fromString("0b3d84c4-1c8f-4a1e-9c31-2f2c3d4e5f60"))
				.set("tags", List.of("a", "b", "c"))
				.set("mixed", List.of(1L, "two", List.of(3.0d, false)))
				.set("embedding", vector(0.5f))
				.set("manager", new ValueCodec.ElementRef(carol))
				// Just past the inline limit: one blob extent.
				.set("bio", text("bio", 300)));
			bob = store.createNode(List.of("Person"), Properties.of()
				.set("uid", "bob")
				.set("name", "Bob")
				// Just inside it.
				.set("motto", text("motto", 255))
				.set("embedding", vector(9.0f))
				.setNull("nickname"));
			anon = store.createNode(List.of(), Properties.of().set("uid", "anon").set("note", ""));
			long widget = store.createNode(List.of("Product"), Properties.of()
				.set("uid", "widget")
				.set("name", "Widget")
				.set("price", 9.99d)
				// Not the store's dimension, so a blob and not a VEC record.
				.set("embedding", new float[] { 1f, 2f, 3f, 4f, 5f }));

			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L).set("close", true));
			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2021L));
			store.createEdge(alice, "BOUGHT", widget);
			store.createEdge(bob, "BOUGHT", widget);
			store.createEdge(alice, "SUPERVISES", alice);
			store.createRelationship(carol, "MANAGES", alice, Properties.of().set("note", text("note", 400)));
			store.createRelationship(anon, "WATCHES", widget, Properties.of().set("note", "quietly"));
			temporary = store.createRelationship(alice, "TEMPORARY", bob, Properties.of().set("gone", true));
			store.createEdge(bob, "FLEETING", anon);

			long hub = store.createNode("Hub", Properties.of().set("uid", "hub"));
			for (int i = 0; i < 40; i++) {
				long leaf = store.createNode("Leaf", Properties.of().set("uid", String.format("leaf-%02d", i))
					.set("i", (long) i));
				if (i % 10 == 0) {
					store.createRelationship(hub, "LINKS", leaf, Properties.of().set("i", (long) i));
				} else {
					store.createEdge(hub, "LINKS", leaf);
				}
			}
			txn.commit();
		}
		try (Transaction txn = store.begin()) {
			store.deleteRelationship(temporary);
			store.deleteEdge(bob, "FLEETING", anon);
			txn.commit();
		}

		// Reuses the slots the deletions above freed.
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 4; i++) {
				store.createNode("Reborn", Properties.of().set("uid", "reborn-" + i));
			}
			txn.commit();
		}

		store.createIndex("Person", "name");
		store.createUniqueConstraint("uid");
		store.createUniqueConstraint("Product", "name");
		store.createVectorIndex(VectorIndexDefinition.of("Person", "embedding", DIMENSIONS, VectorMetric.COSINE));
		store.buildVectorIndex("Person", "embedding");
	}

	/** The identity file the fixture's root carries: one role with one grant, one user in it. */
	public static void writeAuth(Path root) throws IOException {
		try (AuthStore auth = AuthStore.open(root)) {
			auth.createRole("readers");
			auth.grantToRole("readers", new Grant(Privilege.MATCH, DATABASE));
			auth.grantToRole("readers", new Grant(Privilege.INTROSPECT, Grant.ALL_DATABASES));
			auth.createUser(USER, PASSWORD.toCharArray(), List.of("readers"));
		}
	}

	// ---------------------------------------------------------------- canonical form

	/**
	 * The store's content as sorted text, with no ids in it: nodes by {@code uid}, references rendered as the
	 * {@code uid} they point at, edges as {@code (from, type, to)}. Ids are not part of what a migration promises to
	 * keep — a step may relocate records — so they are not part of what the fixture checks.
	 */
	public static String canonical(GraphStore store) throws IOException {
		StringBuilder out = new StringBuilder("# golden fixture, canonical form 1\n");
		TreeMap<String, String> nodes = new TreeMap<>();
		List<String> edges = new ArrayList<>();
		store.forEachNode(id -> {
			Properties properties = store.nodeProperties(id);
			String uid = properties.getString("uid");
			StringBuilder node = new StringBuilder("node ").append(uid).append(' ').append(store.nodeLabels(id))
				.append('\n');
			for (String key : new java.util.TreeSet<>(properties.keys())) {
				node.append("  ").append(key).append(" = ").append(render(store, properties.get(key))).append('\n');
			}
			nodes.put(uid, node.toString());
			for (Edge edge : store.neighbours(id, Direction.OUT)) {
				String to = store.nodeProperties(edge.neighbourId()).getString("uid");
				StringBuilder line = new StringBuilder("edge ").append(uid).append(" -[").append(edge.type())
					.append("]-> ").append(to);
				if (edge.isMaterialised()) {
					Properties relationship = store.relationshipProperties(edge.relationshipId());
					line.append(" materialised {");
					String separator = "";
					for (String key : new java.util.TreeSet<>(relationship.keys())) {
						line.append(separator).append(key).append(": ").append(render(store, relationship.get(key)));
						separator = ", ";
					}
					line.append('}');
				} else {
					line.append(" lightweight");
				}
				edges.add(line.toString());
			}
		});
		nodes.values().forEach(out::append);
		edges.sort(null);
		edges.forEach(line -> out.append(line).append('\n'));

		List<String> schema = new ArrayList<>();
		for (CompositeIndex index : store.compositeIndexes()) {
			schema.add("index " + index.label() + "." + index.key() + (index.unique() ? " unique" : ""));
		}
		for (String key : store.uniqueConstraints()) {
			schema.add("unique " + key);
		}
		for (VectorIndexDefinition index : store.vectorIndexes()) {
			schema.add("vector " + index.label() + "." + index.key() + " dims=" + index.dimensions() + " metric="
				+ index.metric() + " m=" + index.m() + " ef=" + index.efConstruction() + " state=" + index.state());
		}
		schema.sort(null);
		schema.forEach(line -> out.append(line).append('\n'));

		var stats = store.stats();
		out.append("count nodes=").append(stats.nodeCount()).append(" relationships=")
			.append(stats.relationshipCount()).append(" labels=").append(stats.labelCount()).append(" keys=")
			.append(stats.keyCount()).append('\n');

		// Answers through each access path, so an index whose bytes moved is caught even where the data reads fine.
		out.append("lookup label Person -> ").append(uids(store, store.nodesByLabel("Person"))).append('\n');
		out.append("lookup name=Alice -> ").append(uids(store, store.nodesByProperty("name", "Alice"))).append('\n');
		out.append("lookup Person.name=Bob -> ")
			.append(uids(store, store.nodesByLabelAndProperty("Person", "name", "Bob"))).append('\n');
		out.append("lookup uid=leaf-17 -> ").append(uids(store, store.nodesByProperty("uid", "leaf-17"))).append('\n');
		out.append("nearest scan -> ").append(neighbours(store,
			store.nearestByScan("Person", "embedding", vector(1.0f), 3, VectorMetric.COSINE))).append('\n');
		out.append("nearest index -> ").append(neighbours(store,
			store.nearest("Person", "embedding", vector(1.0f), 3, null))).append('\n');
		return out.toString();
	}

	/** The identity file's content without timestamps or hashes, and whether the fixture's password still works. */
	public static String canonicalAuth(Path root) throws IOException {
		StringBuilder out = new StringBuilder("# golden identity file, canonical form 1\n");
		try (AuthStore auth = AuthStore.open(root)) {
			for (User user : auth.users()) {
				out.append("user ").append(user.name()).append(user.enabled() ? "" : " disabled").append(" roles=")
					.append(user.roles()).append(" grants=").append(user.grants()).append('\n');
			}
			for (Role role : auth.roles()) {
				out.append("role ").append(role.name()).append(" grants=").append(role.grants()).append('\n');
			}
			out.append("authenticate ").append(USER).append(" -> ")
				.append(auth.authenticate(USER, PASSWORD.toCharArray()).isPresent()).append('\n');
		}
		return out.toString();
	}

	private static String render(GraphStore store, Object value) {
		return switch (value) {
			case null -> "null";
			case byte[] bytes -> "bytes" + Arrays.toString(bytes);
			case float[] floats -> "vector" + Arrays.toString(floats);
			case String s when s.length() > 40 -> "String(" + s.length() + "):" + Integer.toHexString(s.hashCode());
			case ValueCodec.ElementRef ref -> "ref:" + store.nodeProperties(ref.id()).getString("uid");
			case List<?> list -> "List" + list.stream().map(item -> render(store, item)).toList();
			default -> value.getClass().getSimpleName() + ":" + value;
		};
	}

	private static List<String> uids(GraphStore store, List<Long> ids) {
		return ids.stream().map(id -> store.nodeProperties(id).getString("uid")).sorted().toList();
	}

	private static String neighbours(GraphStore store, List<Neighbour> found) {
		return found.stream().map(n -> store.nodeProperties(n.nodeId()).getString("uid") + "@"
			+ String.format(Locale.ROOT, "%.6f", n.score())).toList().toString();
	}
}
