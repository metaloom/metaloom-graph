package io.metaloom.graph.core.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

import io.metaloom.graph.core.wal.WalReader;
import io.metaloom.graph.core.wal.WalRecord;

/**
 * Whether a store's log still holds work the data files may not have.
 *
 * <p>
 * The log is redo-only and holds byte-run differences against the data files as this build lays them out. A migration
 * rewrites those files into a different layout, after which no old record can be applied to them — so a store whose
 * log has a committed transaction past the last checkpoint is refused, and the runner does not copy {@code wal/} at
 * all. A cleanly closed store has nothing past its checkpoint but the checkpoint records themselves and, under the
 * direct writer, a padding record.
 * </p>
 *
 * <p>
 * Uncommitted page images past the checkpoint do not count: recovery discards them, and under no-steal they never
 * reached a data file.
 * </p>
 */
final class LogInspector {

	private LogInspector() {
	}

	/** An empty list when nothing needs replaying, otherwise one line saying what does and how to get rid of it. */
	static List<String> unreplayedWork(Path store, long checkpointLsn) throws IOException {
		Path dir = store.resolve("wal");
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		TreeMap<Long, Path> segments = new TreeMap<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String name = file.getFileName().toString();
				if (!name.startsWith("wal-") || !name.endsWith(".log")) {
					continue;
				}
				try {
					segments.put(Long.parseUnsignedLong(name.substring(4, name.indexOf('.')), 16), file);
				} catch (NumberFormatException e) {
					// Not one of ours.
				}
			}
		}
		if (segments.isEmpty()) {
			return List.of();
		}
		var containing = segments.floorEntry(checkpointLsn);
		if (containing == null) {
			return List.of("The log starts at LSN " + segments.firstKey() + ", after the checkpoint at " + checkpointLsn
				+ ", so whether anything needs replaying cannot be told. The store directory looks incomplete; restore "
				+ "it whole, or open it with the build that wrote it and close it cleanly.");
		}
		long segmentBytes = Files.size(containing.getValue());
		if (segmentBytes <= 0) {
			return List.of();
		}
		int committed = 0;
		List<String> problems = new ArrayList<>();
		try (WalReader reader = new WalReader(store, segmentBytes, checkpointLsn)) {
			byte[] record;
			while ((record = reader.next()) != null) {
				if (WalRecord.type(record, 0) == WalRecord.TYPE_TXN_COMMIT) {
					committed++;
				}
			}
		} catch (IOException | RuntimeException e) {
			problems.add("The log past the checkpoint at LSN " + checkpointLsn + " cannot be read (" + e.getMessage()
				+ "). Open the store with the build that wrote it and close it cleanly.");
			return problems;
		}
		if (committed > 0) {
			problems.add("The log holds " + committed + " committed transaction(s) past the last checkpoint (LSN "
				+ checkpointLsn + "): the store was not closed cleanly, and only the build that wrote them can replay"
				+ " them. Open it once with that build and close it normally — stop the server with SIGTERM, not"
				+ " kill -9 — which replays the log and checkpoints. Then migrate.");
		}
		return problems;
	}
}
