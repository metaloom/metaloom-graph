package io.metaloom.graph.core.migration;

import java.io.IOException;
import java.nio.file.Path;

import io.metaloom.graph.core.api.StoreFormat;
import io.metaloom.graph.core.store.Manifest;

/**
 * Where the runner reads and writes a store's format version.
 *
 * <p>
 * A seam with one production implementation, {@link #MANIFEST}. It exists because a runner can only be tested with a
 * chain of steps that ends at a version the verifier can open, and the only such version is the current one — every
 * real version below it is either a legacy encoding or a layout this build cannot open. A test therefore keeps its
 * synthetic versions somewhere else ({@code SidecarVersions}) and leaves the store itself a genuine current store, so
 * the verification at the end is the real one: open, then {@code verify(FULL)}.
 * </p>
 */
public interface StoreVersions {

	int read(Path store) throws IOException;

	void write(Path store, int version) throws IOException;

	/** The manifest's {@code format_version} field, in both copies. */
	StoreVersions MANIFEST = new StoreVersions() {

		@Override
		public int read(Path store) throws IOException {
			return StoreFormat.inspect(store).version();
		}

		@Override
		public void write(Path store, int version) throws IOException {
			Manifest.stampVersion(store, version);
		}
	};
}
