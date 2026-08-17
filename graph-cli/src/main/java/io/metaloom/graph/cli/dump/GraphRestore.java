package io.metaloom.graph.cli.dump;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VectorIndexDefinition;
import io.metaloom.graph.core.api.VectorMetric;

/**
 * Replays a dump into a store, then declares the schema it carried.
 *
 * <h2>Order, and why the schema is last</h2>
 *
 * <ol>
 * <li><b>Header</b>, validated before anything is created. An unknown version or a wrong magic fails here, when the
 * target store is still untouched.</li>
 * <li><b>Nodes</b>, in batches, building the dump-id to element-id map.</li>
 * <li><b>Edges</b>, in batches, translating both endpoints through that map.</li>
 * <li><b>Schema</b> — composite indexes, unique constraints, vector indexes.</li>
 * </ol>
 *
 * <p>
 * The schema goes last for two reasons that pull the same way. A declared index backfills every matching node when it
 * is created, so declaring it first means maintaining it through the whole load instead of building it once at the end.
 * And a unique constraint declared first would be enforced against a half-loaded graph — where a value that is unique
 * in the source may transiently collide with nothing at all, but where a genuine duplicate would abort the load partway
 * instead of at a point where the message can say what happened.
 * </p>
 *
 * <p>
 * {@code createUniqueConstraint} checks the existing data and refuses a declaration that is already violated. A
 * faithful dump from a store that held the constraint cannot violate it, so a failure there means the dump is damaged
 * or the target was not empty — and the message says exactly that rather than repeating the engine's, which would send
 * somebody looking at their data.
 * </p>
 *
 * <h2>Ids are not preserved</h2>
 *
 * <p>
 * A restored node has the same labels, the same properties and the same place in the graph, and a different
 * {@code ElementId} — a fresh store allocates its own slots. Anything outside the store that recorded an id from it is
 * stale after a restore. That is the cost of a logical dump, and the thing a physical copy would have bought instead.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/BACKUP.md">spec/BACKUP.md</a>
 */
public final class GraphRestore {

	/**
	 * Elements per transaction while replaying.
	 *
	 * <p>
	 * The same 4,000 {@code DemoData.Batch} settled on. One transaction for a ten-million-node restore would hold every
	 * page version it touched in memory; one per element would fsync ten million times.
	 * </p>
	 */
	public static final int BATCH = 4_000;

	/** What a dump says about itself, readable without applying any of it. */
	public record Header(int version, String database, long takenAtEpochMillis, long snapshotLsn,
		GraphStoreOptions geometry) {
	}

	public record Summary(long nodes, long relationships, long lightweightEdges, int compositeIndexes,
		int uniqueConstraints, int vectorIndexes) {
	}

	private GraphRestore() {
	}

	/**
	 * Reads and validates the header, leaving the reader positioned at the body.
	 *
	 * <p>
	 * Separate from {@link #read} so a caller can inspect a dump — or check that it fits a target — without opening a
	 * store or applying a byte.
	 * </p>
	 */
	public static Header header(DumpReader in) {
		byte[] magic = in.bytes(DumpFormat.MAGIC.length);
		if (!Arrays.equals(magic, DumpFormat.MAGIC)) {
			throw new DumpFormatException("Not a MetaLoom graph dump: the first bytes are not the expected magic");
		}
		int version = in.u16();
		if (version != DumpFormat.VERSION) {
			// By number, in both directions: "written by a newer version" and "too old to read" are different problems
			// with different answers, and a caller that is told the number can find the build that wrote it.
			throw new DumpFormatException("Dump format version " + version + ", but this build reads version "
				+ DumpFormat.VERSION);
		}
		String database = in.string();
		long takenAt = in.u64();
		long snapshotLsn = in.u64();
		int pageSize = in.u32();
		long segmentCapacity = in.u64();
		int vectorDimensions = in.u32();
		in.beginChecksum();
		GraphStoreOptions geometry = GraphStoreOptions.builder()
			.pageSize(pageSize)
			.segmentCapacity(segmentCapacity)
			.vectorDimensions(vectorDimensions)
			.build();
		return new Header(version, database, takenAt, snapshotLsn, geometry);
	}

	/**
	 * Replays the body into a store that has already been created with a compatible geometry.
	 *
	 * @param store
	 *            <b>expected to be empty.</b> Restoring into a store with data in it would merge two graphs, which is
	 *            not what "restore" means; the caller decides how to guarantee that
	 */
	public static Summary read(GraphStore store, DumpReader in) throws IOException {
		Header header = header(in);
		return readBody(store, in, header);
	}

	/** As {@link #read}, for a caller that has already consumed the header itself. */
	public static Summary readBody(GraphStore store, DumpReader in, Header header) throws IOException {
		checkGeometry(store, header);

		Schema schema = readSchema(in);

		// Grown rather than pre-sized, because the node section carries no count — see GraphBackup.Chunker for why it
		// cannot. Doubling keeps this to O(n) copies, and the trailer's count is checked against it at the end.
		long[] ids = new long[1024];
		int nodeCount = 0;

		Batch batch = new Batch(store);
		try {
			while (in.u8() == GraphBackup.Chunker.MORE) {
				int labelCount = in.varInt();
				in.checkAvailable(labelCount);
				List<String> labels = new ArrayList<>(labelCount);
				for (int l = 0; l < labelCount; l++) {
					labels.add(in.string());
				}
				if (nodeCount == ids.length) {
					ids = Arrays.copyOf(ids, ids.length * 2);
				}
				ids[nodeCount++] = store.createNode(labels, readProperties(in));
				batch.step();
			}

			long materialised = 0;
			long lightweight = 0;
			while (in.u8() == GraphBackup.Chunker.MORE) {
				long from = ids[dumpId(in, nodeCount)];
				long to = ids[dumpId(in, nodeCount)];
				String type = in.string();
				if (in.u8() != 0) {
					store.createRelationship(from, type, to, readProperties(in));
					materialised++;
				} else {
					// Restored as a lightweight edge, not promoted to a relationship: it has no id in the source and
					// must have none here, or it would cost a 64-byte record and change what `MATCH ()-[r]->()` binds.
					store.createEdge(from, type, to);
					lightweight++;
				}
				batch.step();
			}
			batch.close();

			long expectedNodes = in.u64();
			long expectedMaterialised = in.u64();
			long expectedLightweight = in.u64();
			// Snapshotted before the checksum field is read, because the writer snapshots at the same point — the
			// field cannot cover itself. Getting this wrong is not merely an off-by-eight: appending a CRC to the
			// message it covers yields a fixed residue, so every dump would fail with the *same* computed value and
			// look like a systematically broken writer rather than an asymmetric reader.
			long actualCrc = in.checksum();
			long expectedCrc = in.u64();
			if (expectedCrc != actualCrc) {
				throw new DumpFormatException("The dump's checksum does not match its contents: expected "
					+ Long.toHexString(expectedCrc) + ", computed " + Long.toHexString(actualCrc)
					+ ". The file is damaged.");
			}
			if (expectedNodes != nodeCount || expectedMaterialised != materialised
				|| expectedLightweight != lightweight) {
				throw new DumpFormatException("The dump's trailer disagrees with its contents: it claims "
					+ expectedNodes + " nodes, " + expectedMaterialised + " relationships and " + expectedLightweight
					+ " lightweight edges, and holds " + nodeCount + ", " + materialised + " and " + lightweight);
			}

			applySchema(store, schema);
			return new Summary(nodeCount, materialised, lightweight, schema.composites().size(),
				schema.unique().size(), schema.vectors().size());
		} finally {
			batch.abortIfOpen();
		}
	}

	private static int dumpId(DumpReader in, int nodeCount) {
		int id = in.varInt();
		if (id < 0 || id >= nodeCount) {
			throw new DumpFormatException("An edge names node " + id + ", but the dump holds " + nodeCount
				+ " nodes. The file is corrupt.");
		}
		return id;
	}

	/**
	 * Refuses a target whose geometry cannot hold the dump's data.
	 *
	 * <p>
	 * Vector dimensions are the case that matters: they are fixed at store creation because the record stride is
	 * derived from them, so a dump carrying 768-float vectors cannot go into a store created without vector storage.
	 * Page size and segment capacity are reported rather than refused — they change the layout and not what fits.
	 * </p>
	 */
	private static void checkGeometry(GraphStore store, Header header) {
		int required = header.geometry().vectorDimensions();
		int available = store.options().vectorDimensions();
		if (required > 0 && available != required) {
			throw new DumpFormatException("This dump was taken from a store with " + required
				+ "-dimension vector storage and the target has "
				+ (available == 0 ? "none" : available + " dimensions")
				+ ". Vector dimensions are fixed when a store is created, so the target has to be created to match: "
				+ "restore offline with --path, which creates it from the dump's own geometry.");
		}
	}

	private record Schema(List<io.metaloom.graph.core.api.CompositeIndex> composites, List<String> unique,
		List<VectorIndexDefinition> vectors) {
	}

	private static Schema readSchema(DumpReader in) {
		int compositeCount = in.varInt();
		in.checkAvailable(compositeCount);
		List<io.metaloom.graph.core.api.CompositeIndex> composites = new ArrayList<>(compositeCount);
		for (int i = 0; i < compositeCount; i++) {
			composites.add(new io.metaloom.graph.core.api.CompositeIndex(in.string(), in.string(), in.u8() != 0));
		}

		int uniqueCount = in.varInt();
		in.checkAvailable(uniqueCount);
		List<String> unique = new ArrayList<>(uniqueCount);
		for (int i = 0; i < uniqueCount; i++) {
			unique.add(in.string());
		}

		int vectorCount = in.varInt();
		in.checkAvailable(vectorCount);
		List<VectorIndexDefinition> vectors = new ArrayList<>(vectorCount);
		VectorMetric[] metrics = VectorMetric.values();
		for (int i = 0; i < vectorCount; i++) {
			String label = in.string();
			String key = in.string();
			int dimensions = in.u32();
			int metric = in.u8();
			if (metric < 0 || metric >= metrics.length) {
				throw new DumpFormatException("Vector metric " + metric + " is not one this build knows");
			}
			String model = in.string();
			int m = in.u32();
			int efConstruction = in.u32();
			vectors.add(new VectorIndexDefinition(label, key, dimensions, metrics[metric],
				model.isEmpty() ? null : model, m, efConstruction,
				io.metaloom.graph.core.api.VectorIndexState.BUILDING));
		}
		return new Schema(composites, unique, vectors);
	}

	private static void applySchema(GraphStore store, Schema schema) throws IOException {
		for (io.metaloom.graph.core.api.CompositeIndex index : schema.composites()) {
			try {
				// A label-scoped unique constraint IS a composite index plus a flag, so declaring the constraint also
				// declares the index; declaring both would be one redundant backfill.
				if (index.unique()) {
					store.createUniqueConstraint(index.label(), index.key());
				} else {
					store.createIndex(index.label(), index.key());
				}
			} catch (ConstraintViolationException violation) {
				throw new DumpFormatException("Restoring the constraint on :" + index.label() + "." + index.key()
					+ " failed because the restored data already violates it. A dump from a store that held this "
					+ "constraint cannot do that, so either the dump is damaged or the target was not empty. "
					+ violation.getMessage());
			}
		}
		for (String key : schema.unique()) {
			try {
				store.createUniqueConstraint(key);
			} catch (ConstraintViolationException violation) {
				throw new DumpFormatException("Restoring the store-wide unique constraint on '" + key
					+ "' failed because the restored data already violates it. Either the dump is damaged or the "
					+ "target was not empty. " + violation.getMessage());
			}
		}
		for (VectorIndexDefinition index : schema.vectors()) {
			store.createVectorIndex(index);
		}
	}

	private static Properties readProperties(DumpReader in) {
		int count = in.varInt();
		in.checkAvailable(count);
		if (count == 0) {
			return Properties.of();
		}
		Properties properties = Properties.of();
		for (int i = 0; i < count; i++) {
			String key = in.string();
			properties.set(key, DumpValueCodec.read(in));
		}
		return properties;
	}

	/**
	 * A transaction per {@link #BATCH} elements.
	 *
	 * <p>
	 * The same shape as {@code DemoData.Batch}, and it has the same consequence: a restore that fails partway leaves
	 * whole batches committed. That is acceptable here in a way it would not be elsewhere, because the target is
	 * required to be empty and the remedy is to drop it and start again — which is what the CLI tells the operator.
	 * </p>
	 */
	private static final class Batch {

		private final GraphStore store;
		private Transaction txn;
		private int pending;

		Batch(GraphStore store) {
			this.store = store;
			this.txn = store.begin();
		}

		void step() throws IOException {
			if (++pending >= BATCH) {
				txn.commit();
				txn.close();
				txn = store.begin();
				pending = 0;
			}
		}

		void close() throws IOException {
			if (txn != null) {
				txn.commit();
				txn.close();
				txn = null;
			}
		}

		/** Rolls back whatever was in flight when something threw. */
		void abortIfOpen() {
			if (txn != null) {
				txn.close();
				txn = null;
			}
		}
	}
}
