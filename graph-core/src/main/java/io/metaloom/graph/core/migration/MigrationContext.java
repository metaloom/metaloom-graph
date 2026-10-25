package io.metaloom.graph.core.migration;

import java.nio.file.Path;
import java.util.function.Consumer;

import io.metaloom.graph.core.api.StoreFormat;

/**
 * What a {@link FormatMigration} is given.
 *
 * @param store
 *            the working copy to rewrite. Never the original
 * @param source
 *            the original store's manifest as the plan read it, before any step ran: its geometry is what the old
 *            layout's offsets are computed from
 * @param from
 *            the version the working copy is at now
 * @param to
 *            the version this step must leave it at
 */
public record MigrationContext(Path store, StoreFormat.Info source, int from, int to, Consumer<String> progress) {

	/** Reports progress through the runner's listener, prefixed with the step. */
	public void progress(String message) {
		progress.accept("  [" + from + " -> " + to + "] " + message);
	}
}
