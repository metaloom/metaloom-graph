package io.metaloom.graph.core.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.fixture.StoreFiles;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.nativelib.NativeLib;

/** The working copy: every byte of the data, none of the holes, and neither the lock nor the log. */
@Tag("unit")
public class StoreCopierTest {

	@TempDir
	Path root;

	@Test
	public void testAStoreIsCopiedWithoutItsLockOrItsLog() throws IOException {
		Path source = root.resolve("graph");
		try (GraphStore graph = GraphStore.open(source, TempStore.options())) {
			for (int i = 0; i < 50; i++) {
				graph.createNode("Person", Properties.of().set("name", "n" + i).set("bio", "x".repeat(600)));
			}
		}
		Path target = root.resolve("copy");
		StoreCopier.Copied copied = StoreCopier.copy(source, target);

		Map<String, String> expected = new TreeMap<>(StoreFiles.digest(source));
		expected.keySet().removeIf(name -> name.startsWith("wal/"));
		assertEquals(expected, StoreFiles.digest(target), "every data file, byte for byte");
		assertFalse(Files.exists(target.resolve("LOCK")));
		assertFalse(Files.exists(target.resolve("wal")));
		assertEquals(expected.size(), copied.files());
		for (String name : expected.keySet()) {
			assertEquals(Files.size(source.resolve(name)), Files.size(target.resolve(name)), name + " keeps its length");
		}
		try (GraphStore graph = GraphStore.open(target, TempStore.options())) {
			assertEquals(50, graph.nodesByLabel("Person").size(), "and the copy is a store");
		}
	}

	/** A 64 MiB file with three short runs of data must come out as three short runs of data, not 64 MiB. */
	@Test
	public void testHolesStayHoles() throws IOException {
		Path source = Files.createDirectories(root.resolve("sparse"));
		Path file = source.resolve("big.seg");
		long length = 64L << 20;
		byte[] run = new byte[10_000];
		java.util.Arrays.fill(run, (byte) 7);
		try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
			out.setLength(length);
			for (long at : new long[] { 0, 5_000_000 + 13, length - run.length }) {
				out.seek(at);
				out.write(run);
			}
		}
		Path target = root.resolve("sparse-copy");
		StoreCopier.Copied copied = StoreCopier.copy(source, target);
		Path copy = target.resolve("big.seg");
		assertEquals(length, Files.size(copy));
		assertEquals(StoreFiles.digest(source), StoreFiles.digest(target));
		if (copied.reflinked() == 0) {
			assertTrue(copied.bytesWritten() < 64 * 1024, "only the blocks holding data are written: "
				+ copied.bytesWritten());
		}
		long allocated = NativeLib.allocatedBytes(copy);
		if (allocated >= 0) {
			assertTrue(allocated < 1 << 20, "the copy is sparse on disk: " + allocated + " bytes allocated");
		}
		byte[] tail = new byte[run.length];
		try (RandomAccessFile in = new RandomAccessFile(copy.toFile(), "r")) {
			in.seek(length - run.length);
			in.readFully(tail);
		}
		assertArrayEquals(run, tail);
	}
}
