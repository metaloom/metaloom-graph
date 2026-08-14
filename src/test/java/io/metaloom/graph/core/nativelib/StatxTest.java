package io.metaloom.graph.core.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code statx} — capability N3, and the answer to "what does this store actually cost".
 *
 * <p>
 * Every segment file is sparse and {@code reclaim()} punches holes in it, so its apparent length says almost nothing
 * about its footprint: the 10M node scale run reports 10.1 GiB of apparent file size for 6.1 GiB of real allocation.
 * Until this binding the only way to see the difference was to shell out to {@code du}, which is not a reasonable
 * thing for a storage engine to do to answer a question about its own files.
 * </p>
 */
@Tag("unit")
public class StatxTest {

	@TempDir
	Path dir;

	@Test
	public void testAllocatedBytesMatchesADenseFile() throws Exception {
		assumeTrue(NativeLib.isStatxAvailable(), "statx is unavailable: " + NativeLib.describe());

		Path path = dir.resolve("dense.bin");
		byte[] data = new byte[256 * 1024];
		new Random(20260814L).nextBytes(data);
		Files.write(path, data);

		long allocated = NativeLib.allocatedBytes(path);
		long apparent = NativeLib.apparentBytes(path);

		assertEquals(data.length, apparent, "statx must agree with the file's length");
		assertEquals(Files.size(path), apparent, "statx and the JDK must agree about apparent size");
		assertTrue(allocated >= data.length,
			"a densely written file must have at least its own length allocated, got " + allocated);
		assertTrue(allocated < data.length * 4L, "and not wildly more, got " + allocated);
	}

	/** The case the binding exists for: a sparse file costs far less than it spans. */
	@Test
	public void testASparseFileCostsLessThanItSpans() throws Exception {
		assumeTrue(NativeLib.isStatxAvailable(), "statx is unavailable");

		Path path = dir.resolve("sparse.bin");
		long span = 64L * 1024 * 1024;
		try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(path,
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
			// One byte at the far end, which is exactly how a WAL segment is preallocated.
			channel.position(span - 1);
			channel.write(java.nio.ByteBuffer.wrap(new byte[1]));
		}

		long allocated = NativeLib.allocatedBytes(path);
		long apparent = NativeLib.apparentBytes(path);
		assertEquals(span, apparent);
		assumeTrue(allocated >= 0, "statx reported nothing");
		System.out.printf("sparse file: %,d bytes apparent, %,d allocated (%.4f%%)%n",
			apparent, allocated, 100.0 * allocated / apparent);
		assertTrue(allocated < apparent / 2,
			"a file with one byte written must not be fully allocated: " + allocated + " of " + apparent);
	}

	/** A missing file is reported, not guessed at. */
	@Test
	public void testAMissingFileReportsUnknown() {
		assumeTrue(NativeLib.isStatxAvailable(), "statx is unavailable");
		assertEquals(-1, NativeLib.allocatedBytes(dir.resolve("does-not-exist.bin")));
	}

	/**
	 * Reflink, which is bound and unused.
	 * <p>
	 * ext4 has no reflink and refuses with {@code EOPNOTSUPP}; btrfs and xfs clone. Both are correct outcomes, so this
	 * asserts only that the call is well formed and that a successful clone really is a copy.
	 */
	@Test
	public void testReflinkEitherClonesOrDeclines() throws Exception {
		assumeTrue(NativeLib.isReflinkAvailable(), "ioctl is unavailable");

		Path source = dir.resolve("source.bin");
		byte[] data = new byte[128 * 1024];
		new Random(7L).nextBytes(data);
		Files.write(source, data);
		Path target = dir.resolve("clone.bin");

		boolean cloned = NativeLib.reflink(source, target);
		System.out.println("reflink on this filesystem: " + (cloned ? "supported" : "declined"));
		if (cloned) {
			org.junit.jupiter.api.Assertions.assertArrayEquals(data, Files.readAllBytes(target),
				"a clone must hold the same bytes as its source");
		}
	}
}
