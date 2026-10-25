package io.metaloom.graph.core.migration;

import java.io.IOException;
import java.nio.file.Path;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.api.VerifyOptions;

/** Decides whether a migrated copy is good enough to replace the original. */
@FunctionalInterface
public interface MigrationVerifier {

	/** @throws RuntimeException or IOException when it is not */
	void verify(Path store, GraphStoreOptions options) throws IOException;

	/**
	 * Open it with this build, run every structural invariant, close it.
	 *
	 * <p>
	 * The engine's own oracle and nothing a step wrote: a step that left a record the current code cannot read, an
	 * accounting counter off by one, or a dangling reference fails here, before anything is renamed. Opening also
	 * starts the copy's fresh log and closing it writes the first checkpoint, so the store that is swapped in has
	 * already been opened and closed by this build once.
	 * </p>
	 */
	MigrationVerifier OPEN_AND_VERIFY_FULL = (store, options) -> {
		try (GraphStore opened = GraphStore.open(store, options)) {
			opened.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot());
		}
	};
}
