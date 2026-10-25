package io.metaloom.graph.core.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.metaloom.graph.core.migration.StoreVersions;

/**
 * Synthetic store format versions for testing the migration runner, kept in a file beside the manifest.
 *
 * <p>
 * The runner can only be tested end to end with a chain that finishes at a version the verifier opens, and the only
 * such version is the current one. So a test store stays a genuine current store — the verification at the end is the
 * real open and {@code verify(FULL)} — and its "version" for the runner's purposes lives in {@code TEST_VERSION},
 * which the test's steps advance. See {@code StoreVersions}.
 * </p>
 */
public final class SidecarVersions implements StoreVersions {

	public static final String FILE = "TEST_VERSION";

	@Override
	public int read(Path store) throws IOException {
		return Integer.parseInt(Files.readString(store.resolve(FILE), StandardCharsets.UTF_8).trim());
	}

	@Override
	public void write(Path store, int version) throws IOException {
		Files.writeString(store.resolve(FILE), Integer.toString(version), StandardCharsets.UTF_8);
	}
}
