package io.metaloom.graph.core.api;

import io.metaloom.graph.core.id.Kind;

/**
 * A point-in-time snapshot of the store's I/O counters.
 * <p>
 * Subtract two snapshots to get the cost of whatever happened between them, which is how the reads-per-operation
 * budgets in {@code ReadBudgetTest} and the write amplification gate are expressed.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 7
 */
public record IoStats(
	long[] recordReadsByKind,
	long[] recordWritesByKind,
	long pageApplies,
	long bytesApplied,
	long walBytesAppended,
	long walRecords,
	long walSyncs,
	long dataForces,
	long pagesPunched,
	long checkpoints,
	long checkpointNanos,
	long recoveryNanos,
	long recoveredPageImages,
	long compactions,
	long recordsRelocated) {

	/**
	 * Defensive copies. The components are arrays, so a record's generated accessors hand out the internals and a
	 * caller could write to the counters it is supposed to be reading.
	 */
	public IoStats {
		recordReadsByKind = recordReadsByKind.clone();
		recordWritesByKind = recordWritesByKind.clone();
	}

	@Override
	public long[] recordReadsByKind() {
		return recordReadsByKind.clone();
	}

	@Override
	public long[] recordWritesByKind() {
		return recordWritesByKind.clone();
	}

	public long recordReads(Kind kind) {
		return recordReadsByKind[kind.code()];
	}

	public long recordWrites(Kind kind) {
		return recordWritesByKind[kind.code()];
	}

	public long totalRecordReads() {
		long total = 0;
		for (long count : recordReadsByKind) {
			total += count;
		}
		return total;
	}

	public long totalRecordWrites() {
		long total = 0;
		for (long count : recordWritesByKind) {
			total += count;
		}
		return total;
	}

	/** This snapshot minus an earlier one: the cost of everything that happened in between. */
	public IoStats since(IoStats earlier) {
		long[] reads = new long[recordReadsByKind.length];
		long[] writes = new long[recordWritesByKind.length];
		for (int i = 0; i < reads.length; i++) {
			reads[i] = recordReadsByKind[i] - earlier.recordReadsByKind[i];
			writes[i] = recordWritesByKind[i] - earlier.recordWritesByKind[i];
		}
		return new IoStats(reads, writes,
			pageApplies - earlier.pageApplies,
			bytesApplied - earlier.bytesApplied,
			walBytesAppended - earlier.walBytesAppended,
			walRecords - earlier.walRecords,
			walSyncs - earlier.walSyncs,
			dataForces - earlier.dataForces,
			pagesPunched - earlier.pagesPunched,
			checkpoints - earlier.checkpoints,
			checkpointNanos - earlier.checkpointNanos,
			recoveryNanos - earlier.recoveryNanos,
			recoveredPageImages - earlier.recoveredPageImages,
			compactions - earlier.compactions,
			recordsRelocated - earlier.recordsRelocated);
	}

	/**
	 * Bytes the engine wrote for every byte of record payload it was asked to store: log records plus the page images
	 * copied into the data files, over the payload. The engine's floor is 2, because a no-steal log writes every change
	 * once to the log and once to the data file.
	 */
	public double writeAmplification(long logicalBytes) {
		return logicalBytes == 0 ? 0 : (double) (walBytesAppended + bytesApplied) / logicalBytes;
	}

	@Override
	public String toString() {
		StringBuilder out = new StringBuilder("IoStats[reads=");
		appendByKind(out, recordReadsByKind);
		out.append(", writes=");
		appendByKind(out, recordWritesByKind);
		return out.append(", pageApplies=").append(pageApplies)
			.append(", bytesApplied=").append(bytesApplied)
			.append(", walBytes=").append(walBytesAppended)
			.append(", walRecords=").append(walRecords)
			.append(", walSyncs=").append(walSyncs)
			.append(", forces=").append(dataForces)
			.append(", pagesPunched=").append(pagesPunched)
			.append(", checkpoints=").append(checkpoints)
			.append(']').toString();
	}

	private static void appendByKind(StringBuilder out, long[] counts) {
		out.append('{');
		boolean first = true;
		for (Kind kind : Kind.values()) {
			if (kind.code() >= counts.length || counts[kind.code()] == 0) {
				continue;
			}
			if (!first) {
				out.append(", ");
			}
			out.append(kind).append('=').append(counts[kind.code()]);
			first = false;
		}
		out.append('}');
	}
}
