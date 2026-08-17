package io.metaloom.graph.cli.dump;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.graph.core.api.CompositeIndex;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.IsolationLevel;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.core.api.ScanOptions;
import io.metaloom.graph.core.api.SnapshotTooOldException;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.api.VectorIndexDefinition;

/**
 * Writes a database out as a dump, while it is being used.
 *
 * <h2>Online, and what that actually costs</h2>
 *
 * <p>
 * The whole walk runs inside one {@code SNAPSHOT} read view, so the dump is a single point in time: a relationship's
 * endpoints are both present or both absent, and a node created after the backup started is not in it. That holds
 * across the <em>paced</em> scan this uses, and the reason is worth stating because it is what makes the feature
 * possible at all — the free-slot bitmap the scan walks is read through the same snapshot as everything else, so
 * dropping the apply latch between batches cannot change which nodes the scan sees. {@code ScanOptions} has the
 * argument; {@code SnapshotScanTest} has the proof.
 * </p>
 *
 * <p>
 * What it does cost, stated rather than implied:
 * </p>
 *
 * <ul>
 * <li><b>Writers wait one batch, not the whole walk.</b> Without pacing a full scan holds the apply latch throughout
 * and stops every writer in the process until it finishes.</li>
 * <li><b>Reclamation is held back for the duration.</b> An open view retains every superseded page version, so
 * {@code reclaim()} and {@code compact()} are refused while a backup runs and retention grows with write volume.</li>
 * <li><b>A busy store can outrun the retention budget.</b> Past {@code retainedVersionBytes} the engine abandons the
 * oldest view, and this is usually the oldest. That surfaces as {@link SnapshotTooOldException}, and this class
 * re-throws it with what to do about it rather than letting the engine's message stand alone.</li>
 * </ul>
 *
 * <h2>One thread</h2>
 *
 * <p>
 * A read view is thread-confined — the holder is a {@code ThreadLocal}, so a second thread silently gets no view and
 * reads current data instead. Everything here therefore happens on the calling thread, and a server route driving it
 * must do the whole backup inside one blocking task rather than hopping between an event loop and a worker.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/BACKUP.md">spec/BACKUP.md</a>
 */
public final class GraphBackup {

	/** Told what has been written so far, so a CLI can print progress without this class knowing what a terminal is. */
	@FunctionalInterface
	public interface Progress {

		void nodes(long done, long total);

		Progress NONE = (done, total) -> {
		};
	}

	/** What a completed backup contains. */
	public record Summary(long nodes, long relationships, long lightweightEdges, long bytes, long snapshotLsn) {
	}

	/** The fixed-size trailer: three counts and a checksum, in that order. */
	public static final int TRAILER_BYTES = 4 * Long.BYTES;

	/**
	 * Reads the counts back out of a dump's last {@link #TRAILER_BYTES} bytes.
	 *
	 * <p>
	 * For the caller that moved a dump somewhere without encoding it — the CLI copying an HTTP response. It has the
	 * bytes and none of the counts, and the counts are in what it just copied. That is a great deal simpler than
	 * teaching the transport to carry a summary alongside the body, and it reports what is <i>in the dump</i> rather
	 * than what the far end said it sent.
	 * </p>
	 *
	 * <p>
	 * A tail rather than a file, because the destination may be a pipe. {@code --out=-} is the spelling that exists
	 * for exactly the case where there is nothing to seek back into, and it would be the one to report zeroes.
	 * </p>
	 *
	 * @param trailer
	 *            the last {@link #TRAILER_BYTES} bytes of the dump
	 * @param bytes
	 *            how long the whole dump was
	 */
	public static Summary summaryOf(byte[] trailer, long bytes, long snapshotLsn) {
		if (trailer.length < TRAILER_BYTES) {
			throw new DumpFormatException("A dump of " + bytes + " bytes is too short to hold a trailer");
		}
		return new Summary(readLong(trailer, 0), readLong(trailer, 8), readLong(trailer, 16), bytes, snapshotLsn);
	}

	private static long readLong(byte[] bytes, int offset) {
		long value = 0;
		for (int i = 0; i < 8; i++) {
			value |= (long) (bytes[offset + i] & 0xFF) << (8 * i);
		}
		return value;
	}

	private GraphBackup() {
	}

	public static Summary write(GraphStore store, String databaseName, OutputStream out, long takenAtEpochMillis)
		throws IOException {
		return write(store, databaseName, out, takenAtEpochMillis, Progress.NONE);
	}

	public static Summary write(GraphStore store, String databaseName, OutputStream out, long takenAtEpochMillis,
		Progress progress) throws IOException {
		try (ReadTransaction view = store.beginRead(IsolationLevel.SNAPSHOT)) {
			try {
				return writeInView(store, databaseName, out, takenAtEpochMillis, progress, view.snapshotLsn());
			} catch (SnapshotTooOldException tooOld) {
				throw new BackupAbandonedException(tooOld);
			}
		}
	}

	private static Summary writeInView(GraphStore store, String databaseName, OutputStream out,
		long takenAtEpochMillis, Progress progress, long snapshotLsn) throws IOException {
		StoreStats stats = store.stats();
		DumpWriter writer = new DumpWriter(out);

		writer.bytes(DumpFormat.MAGIC);
		writer.u16(DumpFormat.VERSION);
		writer.string(databaseName);
		writer.u64(takenAtEpochMillis);
		writer.u64(snapshotLsn);
		// The three geometry settings a restore cannot choose for itself: they are fixed when a store is created and
		// a store created without vector storage cannot hold the vectors this dump may contain.
		GraphStoreOptions geometry = store.options();
		writer.u32(geometry.pageSize());
		writer.u64(geometry.segmentCapacity());
		writer.u32(geometry.vectorDimensions());
		// Everything from here is covered, so a truncated or damaged body is caught before a restore applies any of it.
		writer.beginChecksum();

		writeSchema(store, writer);

		// Dump ids are the walk order, so this map is only needed to translate the *edge* endpoints. It is the one
		// unavoidable cost of the whole design: eight bytes and a hash entry per node, on the machine taking the
		// backup rather than the one restoring it.
		//
		// stats() is a hint for the initial capacity and NOTHING MORE. It is not snapshot-resolved — it reports the
		// store as it is now, not as this view sees it — so under a concurrent writer it disagrees with the scan.
		// That is correct behaviour from both, and it is why neither section below is length-prefixed.
		Map<Long, Integer> dumpIds = new HashMap<>(Math.max(16, (int) Math.min(stats.nodeCount() * 2, 1 << 20)));

		long hint = stats.nodeCount();
		long[] written = { 0 };
		Chunker nodes = new Chunker(writer);
		store.forEachNodeWhile(id -> {
			nodes.next();
			dumpIds.put(id, (int) written[0]);
			writeNode(store, writer, id);
			written[0]++;
			if (written[0] % 10_000 == 0) {
				progress.nodes(written[0], hint);
			}
			return true;
		}, ScanOptions.throttled());
		nodes.end();
		progress.nodes(written[0], written[0]);

		return writeEdges(store, writer, dumpIds, written[0], snapshotLsn);
	}

	/**
	 * Walks every node's outgoing edges.
	 *
	 * <p>
	 * <b>{@code OUT} only, which is complete and does not double-count.</b> Every edge is outgoing from exactly one
	 * endpoint, so one pass over every node's out-list visits each edge once — including a self-loop, which appears in
	 * its own node's out-list once and not twice.
	 * </p>
	 *
	 * <p>
	 * A second pass rather than one, because an edge names both its endpoints by dump id and the map is only complete
	 * once every node has been visited. Both passes run under the same view, so they see one state.
	 * </p>
	 */
	private static Summary writeEdges(GraphStore store, DumpWriter writer, Map<Long, Integer> dumpIds, long nodes,
		long snapshotLsn) {
		Chunker edges = new Chunker(writer);
		long[] counts = { 0, 0 };

		for (Map.Entry<Long, Integer> entry : dumpIds.entrySet()) {
			long nodeId = entry.getKey();
			int fromDumpId = entry.getValue();
			for (Edge edge : store.neighbours(nodeId, Direction.OUT)) {
				Integer toDumpId = dumpIds.get(edge.neighbourId());
				if (toDumpId == null) {
					// Cannot happen under one snapshot — both endpoints of a live edge are live nodes — so it is a
					// broken invariant rather than an edge to skip quietly.
					throw new DumpFormatException("Edge " + edge + " from node " + nodeId + " points at a node that "
						+ "the same snapshot's scan did not visit. The store may be damaged; run verify(FULL).");
				}
				edges.next();
				writer.varInt(fromDumpId);
				writer.varInt(toDumpId);
				writer.string(edge.type());
				if (edge.isMaterialised()) {
					// A relationship record: it has an id, so it can carry properties and be addressed later.
					writer.u8(1);
					writeProperties(writer, store.relationshipProperties(edge.relationshipId()));
					counts[0]++;
				} else {
					// A lightweight edge, which has no record and no id at all. It must round-trip as one:
					// materialising it on restore would change its identity and cost 64 bytes it does not need.
					writer.u8(0);
					counts[1]++;
				}
			}
		}
		edges.end();

		writer.u64(nodes);
		writer.u64(counts[0]);
		writer.u64(counts[1]);
		long crc = writer.checksum();
		writer.u64(crc);
		writer.flush();
		return new Summary(nodes, counts[0], counts[1], writer.bytesWritten(), snapshotLsn);
	}

	/**
	 * Frames a section whose length is not known in advance.
	 *
	 * <p>
	 * <b>Neither count can be written before its section.</b> {@code StoreStats} is not snapshot-resolved — it reports
	 * the store as it is now rather than as this view sees it, so under a concurrent writer it is a different number
	 * from what the scan produces — and the engine has no count of lightweight edges at all. The alternatives were
	 * buffering a whole section in order to count it, which defeats the streaming this exists for, and seeking back to
	 * patch a placeholder, which a pipe cannot do.
	 * </p>
	 *
	 * <p>
	 * So each entry is preceded by a one-byte marker and the section ends with a zero. One byte per element, no
	 * buffering, and no count that can disagree with its contents — the totals are in the trailer, where they are
	 * checked against what was actually read. A block-framed alternative would save those bytes and buy a partial
	 * final block that has to be special-cased; on a dump whose nodes are tens of bytes each, that is not a trade
	 * worth the complexity.
	 * </p>
	 */
	static final class Chunker {

		static final int MORE = 1;
		static final int END = 0;

		private final DumpWriter writer;

		Chunker(DumpWriter writer) {
			this.writer = writer;
		}

		/** Called immediately before each entry. */
		void next() {
			writer.u8(MORE);
		}

		/** Closes the section. A section with no entries at all is just this byte. */
		void end() {
			writer.u8(END);
		}
	}

	private static void writeNode(GraphStore store, DumpWriter writer, long id) {
		List<String> labels = store.nodeLabels(id);
		writer.varInt(labels.size());
		for (String label : labels) {
			writer.string(label);
		}
		writeProperties(writer, store.nodeProperties(id));
	}

	private static void writeProperties(DumpWriter writer, Properties properties) {
		Set<String> keys = properties == null ? Set.of() : properties.keys();
		writer.varInt(keys.size());
		for (String key : keys) {
			writer.string(key);
			DumpValueCodec.write(writer, properties.get(key));
		}
	}

	/**
	 * The declared schema: composite indexes, store-wide unique constraints, and vector indexes.
	 *
	 * <p>
	 * Written first and restored <b>last</b>. It is here rather than derived on restore because it cannot be derived:
	 * an index is a declaration, not a property of the data, and a restore that guessed would produce a store that
	 * answered the same questions at a different cost.
	 * </p>
	 */
	private static void writeSchema(GraphStore store, DumpWriter writer) {
		List<CompositeIndex> composites = store.compositeIndexes();
		writer.varInt(composites.size());
		for (CompositeIndex index : composites) {
			writer.string(index.label());
			writer.string(index.key());
			writer.u8(index.unique() ? 1 : 0);
		}

		List<String> unique = store.uniqueConstraints();
		writer.varInt(unique.size());
		for (String key : unique) {
			writer.string(key);
		}

		List<VectorIndexDefinition> vectors = store.vectorIndexes();
		writer.varInt(vectors.size());
		for (VectorIndexDefinition index : vectors) {
			writer.string(index.label());
			writer.string(index.key());
			writer.u32(index.dimensions());
			writer.u8(index.metric().ordinal());
			writer.string(index.model() == null ? "" : index.model());
			writer.u32(index.m());
			writer.u32(index.efConstruction());
		}
	}

	/**
	 * The backup's view was reclaimed while it ran.
	 *
	 * <p>
	 * Its own type because the remedy is specific and an operator meeting the engine's message alone would have to
	 * work it out: this is a busy store outrunning the retention budget, not a corrupt one.
	 * </p>
	 */
	public static final class BackupAbandonedException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public BackupAbandonedException(SnapshotTooOldException cause) {
			super("The backup's snapshot was reclaimed before the walk finished: the store wrote more than its "
				+ "retained-version budget while this was running. Either raise retainedVersionBytes (the default is "
				+ "256 MiB), take the backup when less is being written, or stop the server and take it offline with "
				+ "--path. " + cause.getMessage(), cause);
		}
	}
}
