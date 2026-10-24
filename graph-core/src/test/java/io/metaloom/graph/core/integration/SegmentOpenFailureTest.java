package io.metaloom.graph.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.PageCorruptException;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.segment.Geometry;

/**
 * What opening a store does with a segment file that is not what it should be.
 *
 * <p>
 * Two failures in opposite directions. A segment that cannot be opened must not leave its mapping and descriptor
 * behind — a server that retries a damaged database leaked a segment-sized mapping and a descriptor per attempt, for
 * every segment opened before the bad one too. And a segment file that was created and never got its header, which is
 * what a crash inside {@code addSegment} leaves, is an empty segment rather than a corrupt one; refusing it made the
 * whole store unopenable over a file that never held anything.
 * </p>
 */
@Tag("unit")
public class SegmentOpenFailureTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testAFailedOpenLeaksNoMappingAndNoDescriptor() throws Exception {
		assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "needs /proc to count descriptors and mappings");
		try (GraphStore store = stores.open()) {
			for (int i = 0; i < 100; i++) {
				store.createNode("N", Properties.of().set("i", (long) i));
			}
		}
		Path segment = stores.path().resolve("seg").resolve("node").resolve("node-000000.seg");
		try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
			ByteBuffer junk = ByteBuffer.allocate(Geometry.HEADER_BYTES);
			while (junk.hasRemaining()) {
				junk.put((byte) 0x5A);
			}
			junk.flip();
			channel.write(junk, 0);
		}

		// Warm up once, so whatever the first failure loads for good is not counted against the loop.
		assertThrows(PageCorruptException.class, () -> stores.open().close());
		long descriptors = count(Path.of("/proc/self/fd"));
		long mappings = Files.readAllLines(Path.of("/proc/self/maps")).size();
		for (int i = 0; i < 20; i++) {
			assertThrows(PageCorruptException.class, () -> stores.open().close());
		}
		assertTrue(count(Path.of("/proc/self/fd")) <= descriptors + 2,
			"twenty failed opens must not leave descriptors behind");
		assertTrue(Files.readAllLines(Path.of("/proc/self/maps")).size() <= mappings + 4,
			"twenty failed opens must not leave mappings behind");
	}

	@Test
	public void testASegmentFileWithNoHeaderYetIsAnEmptySegment() throws Exception {
		long kept;
		try (GraphStore store = stores.open()) {
			kept = store.createNode("Kept", Properties.of().set("k", "v"));
		}
		// What a crash between creating the next segment's file and forcing its header leaves behind.
		Files.createFile(stores.path().resolve("seg").resolve("node").resolve("node-000001.seg"));

		try (GraphStore store = stores.open()) {
			assertEquals("v", store.nodeProperties(kept).getString("k"));
			assertEquals(1, store.stats().nodeCount());
			store.createNode("After");
			store.verify(VerifyLevel.FULL);
		}
	}

	private static long count(Path dir) throws IOException {
		try (Stream<Path> entries = Files.list(dir)) {
			return entries.count();
		}
	}
}
