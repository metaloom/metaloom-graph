package io.metaloom.graph.core.api;

/**
 * Everything an operator needs to answer "is this store healthy and what is it spending its time on".
 * <p>
 * {@link StoreStats} answers "how much is in it"; this answers "what is it doing". The two are separate because the
 * first is cheap and the second is a snapshot of counters that only mean anything when two of them are subtracted.
 *
 * @param io
 *            the raw counters; subtract two snapshots to cost an interval
 * @param walLagBytes
 *            appended to the log but not yet fsynced. Nonzero only under {@code GROUP_COMMIT} or {@code ASYNC}, and it
 *            is exactly what those modes put at risk
 * @param walBytesSinceCheckpoint
 *            how much the log has grown since the last checkpoint, which is what recovery would have to replay
 * @param unflushedPages
 *            data pages written but not yet forced
 * @param averageCheckpointMillis
 *            mean duration of the checkpoints taken so far
 * @param lastRecoveryMillis
 *            how long the recovery at open took, or 0 when there was nothing to replay
 * @param nativeLayer
 *            what the native layer can do here, which changes whether {@code reclaim} does anything at all
 * @see <a href="../../../../../../../../spec/ARCHITECTURE.md">spec/ARCHITECTURE.md</a>
 */
public record StoreMetrics(
	IoStats io,
	long walLagBytes,
	long walBytesSinceCheckpoint,
	int unflushedPages,
	double averageCheckpointMillis,
	double lastRecoveryMillis,
	String nativeLayer) {

	/**
	 * Bytes written per byte of live record payload. The floor for this engine is 2: a no-steal log writes every change
	 * once to the log and once to the data file. Anything much above that is the page-image granularity of the log.
	 */
	public double writeAmplification(long logicalBytes) {
		return io.writeAmplification(logicalBytes);
	}

	@Override
	public String toString() {
		return "StoreMetrics[walLag=" + walLagBytes + "B, sinceCheckpoint=" + walBytesSinceCheckpoint
			+ "B, unflushedPages=" + unflushedPages
			+ String.format(", checkpoint=%.1fms, recovery=%.1fms", averageCheckpointMillis, lastRecoveryMillis)
			+ ", native=" + nativeLayer + ", " + io + "]";
	}
}
