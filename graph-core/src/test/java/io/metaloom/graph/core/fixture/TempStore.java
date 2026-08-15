package io.metaloom.graph.core.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.segment.Geometry;

/**
 * An isolated store directory per test, deleted on success and <b>retained on failure</b> with its path printed.
 * <p>
 * A corrupted store is the primary diagnostic artefact of a storage engine bug, and deleting it on failure is the most
 * frustrating thing a test suite can do.
 *
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 8
 */
public final class TempStore implements BeforeEachCallback, AfterEachCallback, TestWatcher {

	/**
	 * Small segments keep test files tiny and make segment rollover reachable. Production stores use the 4 GiB
	 * default.
	 */
	public static final long TEST_SEGMENT_CAPACITY = 8L * 1024 * 1024;

	private Path path;
	private boolean failed;

	public static GraphStoreOptions options() {
		return GraphStoreOptions.builder()
			.pageSize(Geometry.DEFAULT_PAGE_SIZE)
			.segmentCapacity(TEST_SEGMENT_CAPACITY)
			.build();
	}

	@Override
	public void beforeEach(ExtensionContext context) throws IOException {
		failed = false;
		path = Files.createTempDirectory("graphstore-" + context.getRequiredTestMethod().getName() + "-");
	}

	@Override
	public void testFailed(ExtensionContext context, Throwable cause) {
		failed = true;
	}

	@Override
	public void afterEach(ExtensionContext context) throws IOException {
		if (path == null) {
			return;
		}
		if (failed) {
			System.err.println("Store retained for diagnosis: " + path.toAbsolutePath());
			return;
		}
		deleteRecursively(path);
		path = null;
	}

	public Path path() {
		return path;
	}

	public GraphStore open() throws IOException {
		return GraphStore.open(path, options());
	}

	public GraphStore open(GraphStoreOptions options) throws IOException {
		return GraphStore.open(path, options);
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}

	public Optional<Path> pathIfPresent() {
		return Optional.ofNullable(path);
	}
}
