package io.metaloom.graph.core.io;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

import io.metaloom.graph.core.nativelib.NativeLib;

/**
 * Positional reads and writes through libc with {@code O_DIRECT}, bypassing the page cache.
 *
 * <p>
 * Capability N2 of spec/NATIVE_FFM.md, and reached through FFM rather than the Rust crate — {@code open}, {@code
 * pwrite}, {@code pread} and {@code fsync} are libc calls, so the crate would add nothing but a build dependency.
 * </p>
 *
 * <p>
 * <b>What it is for is the write-ahead log.</b> The log is written once and read only by recovery, so every byte of it
 * that lands in the page cache evicts a byte of data that a reader wanted. Bypassing the cache for it is the textbook
 * use of direct IO, and unlike the data path it needs no page cache of the engine's own: the log is append-only and
 * already flushed in aligned blocks.
 * </p>
 *
 * <p>
 * The alignment rules are unforgiving — buffer address, file offset and length must all be multiples of
 * {@link NativeLib#DIRECT_IO_ALIGNMENT}, and a violation is an {@code EINVAL} rather than a slow path — so
 * {@link FileHandle#buffer} is the only supported way to allocate one.
 * </p>
 *
 * <p>
 * Falls back to a buffered descriptor where the filesystem rejects {@code O_DIRECT}, which tmpfs and some overlay
 * filesystems do. Durability comes from the fsync either way; what is lost is the cache bypass.
 * </p>
 */
public final class DirectIoEngine implements IoEngine {

	private final LongAdder reads = new LongAdder();
	private final LongAdder writes = new LongAdder();
	private final LongAdder forces = new LongAdder();
	private final MmapIoEngine mappings = new MmapIoEngine();

	/** Whether this engine can be used at all here. */
	public static boolean isSupported() {
		return NativeLib.isDirectIoAvailable();
	}

	@Override
	public String name() {
		return "direct";
	}

	@Override
	public MemorySegment map(Path path, long size, Arena arena) throws IOException {
		// Mapping is orthogonal to O_DIRECT: the segment files are mapped whatever the log does.
		return mappings.map(path, size, arena);
	}

	@Override
	public FileHandle open(Path path, boolean create) throws IOException {
		int fd = NativeLib.openFile(path, true, create);
		if (fd < 0) {
			throw new IOException("Could not open " + path + " for direct IO");
		}
		return new DirectHandle(fd, path);
	}

	@Override
	public boolean evict(Path path) {
		return NativeLib.evictFromPageCache(path);
	}

	@Override
	public int alignment() {
		return NativeLib.DIRECT_IO_ALIGNMENT;
	}

	@Override
	public IoCounts counts() {
		return new IoCounts(reads.sum(), writes.sum(), forces.sum(), PageFaults.major());
	}

	@Override
	public void close() {
	}

	/** Package-visible so {@link UringIoEngine} can reach the descriptor it must submit against. */
	final class DirectHandle implements FileHandle {

		private final int fd;
		private final Path path;

		DirectHandle(int fd, Path path) {
			this.fd = fd;
			this.path = path;
		}

		int fd() {
			return fd;
		}

		@Override
		public long write(MemorySegment buffer, long length, long offset) throws IOException {
			long written = 0;
			while (written < length) {
				long result = NativeLib.pwrite(fd, buffer.asSlice(written), length - written, offset + written);
				if (result <= 0) {
					throw new IOException("pwrite of " + (length - written) + " bytes at " + (offset + written)
						+ " on " + path + " returned " + result
						+ " (offset, length and buffer must be multiples of " + alignment() + ")");
				}
				written += result;
			}
			writes.increment();
			return written;
		}

		@Override
		public long read(MemorySegment buffer, long length, long offset) throws IOException {
			long total = 0;
			while (total < length) {
				long result = NativeLib.pread(fd, buffer.asSlice(total), length - total, offset + total);
				if (result < 0) {
					throw new IOException("pread on " + path + " returned " + result);
				}
				if (result == 0) {
					break;
				}
				total += result;
			}
			reads.increment();
			return total;
		}

		@Override
		public void force() throws IOException {
			if (!NativeLib.fsync(fd)) {
				throw new IOException("fsync failed on " + path);
			}
			forces.increment();
		}

		@Override
		public MemorySegment buffer(Arena arena, long length) {
			long aligned = align(length);
			// Aligning the allocation is not optional: O_DIRECT rejects an unaligned address outright, and the JDK
			// makes no alignment promise about anything smaller than a page.
			return arena.allocate(aligned, NativeLib.DIRECT_IO_ALIGNMENT);
		}

		@Override
		public void close() {
			NativeLib.closeFile(fd);
		}
	}

	/** Rounds up to the alignment direct IO requires. */
	public static long align(long value) {
		long alignment = NativeLib.DIRECT_IO_ALIGNMENT;
		return (value + alignment - 1) / alignment * alignment;
	}
}
