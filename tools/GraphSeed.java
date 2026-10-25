import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;

/**
 * Writes a small store for something else to read.
 *
 * <pre>
 * java -cp graph-cli/target/graph-cli-cli.jar tools/GraphSeed.java /tmp/root
 * </pre>
 *
 * <p>
 * Run by {@code tools/native-image.sh} before the CLI smoke test, because the CLI has no way to create data — it backs
 * a database up and puts one back, and both halves need a database that already exists. The server has {@code --demo}
 * for this; the CLI's smoke test would otherwise have to depend on the server having been built, which is the coupling
 * this file exists to avoid.
 * </p>
 *
 * <h2>Why the shaded CLI jar is the classpath</h2>
 *
 * <p>
 * It is the artifact that has just been built and is about to be compiled into a binary, so it is the one classpath
 * guaranteed to be there, and it already contains all of {@code graph-core}. A single-file source-launcher program on
 * top of it needs no build step of its own — the same trade {@code WireSmoke.java} makes, one level up.
 * </p>
 *
 * <h2>What it seeds, and why each part</h2>
 *
 * <p>
 * Not an interesting graph: an interesting <i>dump</i>. Every shape here is one the round trip could lose quietly —
 * a node with several labels and one with none, a lightweight edge that must not come back as a relationship, a
 * self-loop, and the four property types no other codec in this repository can carry ({@code byte[]},
 * {@code Instant}, {@code UUID}, {@code float[]}). {@code BackupRoundTripTest} asserts all of that properly, cell by
 * cell; what this adds is that the <b>binary</b> does it, over FFM, against mapped segment files.
 * </p>
 *
 * @see <a href="../spec/BACKUP.md">spec/BACKUP.md</a>
 */
public final class GraphSeed {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: GraphSeed <root> [database]");
			System.exit(2);
		}
		Path root = Path.of(args[0]);
		String name = args.length > 1 ? args[1] : GraphDatabases.DEFAULT_NAME;

		try (GraphDatabases databases = GraphDatabases.open(root)) {
			GraphStore store = databases.exists(name) ? databases.database(name) : databases.create(name);
			try (Transaction txn = store.begin()) {
				long hub = store.createNode(List.of("City", "Capital"), Properties.of()
					.set("name", "Berlin")
					.set("population", 3_878_000L)
					.set("area", 891.7d)
					.set("capital", true)
					.set("founded", Instant.parse("1237-01-01T00:00:00Z"))
					.set("id", UUID.fromString("2f8a1c44-8d5e-4a2b-9c31-6b0e5a7d1f90"))
					.set("seal", new byte[] { 1, 2, 3, 4 })
					.set("embedding", new float[] { 0.5f, -0.25f, 0.125f })
					.set("aliases", List.of("BER", "Berlin")));
				long other = store.createNode("City", Properties.of().set("name", "Hamburg"));
				// No labels at all, which is legal and is the case a dump that assumed at least one would lose.
				long anonymous = store.createNode(List.of(), Properties.of().set("name", "somewhere"));

				store.createRelationship(hub, "ROAD_TO", other, Properties.of().set("km", 289L));
				// Parallel to the one above, and a self-loop: both must survive as themselves.
				store.createRelationship(hub, "RAIL_TO", other, Properties.of().set("km", 255L));
				store.createRelationship(hub, "RING", hub);
				// Lightweight: no id, no properties. Promoting it on restore would change its storage cost.
				store.createEdge(other, "NEAR", anonymous);
				txn.commit();
			}
			store.createIndex("City", "name");
			System.out.println(store.stats().nodeCount() + " " + store.stats().relationshipCount());
		}
	}
}
