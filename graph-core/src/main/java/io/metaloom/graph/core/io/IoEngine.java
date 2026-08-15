package io.metaloom.graph.core.io;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

import io.metaloom.graph.core.nativelib.NativeLib;

/**
 * How the engine reaches a file.
 *
 * <p>
 * Deferred from Phase 1 to here, because an abstraction with one implementation is a guess. It has three now, and the
 * shape they share is deliberately narrow: <b>open, map, read, write, force, evict</b>. Everything above it —
 * addressing, records, transactions — is unaware of which one it is talking to.
 * </p>
 *
 * <p>
 * The data path is memory mapped and stays that way: a record read is an address computation and a page fault, which
 * is the whole basis of the engine's read numbers, and no interface can improve on it. What an engine choice changes
 * is the <b>write and durability path</b>, where {@code O_DIRECT} means the log stops filling the page cache with
 * bytes nothing will read again.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/NATIVE_FFM.md">spec/NATIVE_FFM.md</a> section 1, capabilities N1 and N2
 * @see <a href="../../../../../../../../spec/SSD_IO.md">spec/SSD_IO.md</a> section 4
 */
public interface IoEngine extends AutoCloseable {

	/** A short name for logs and {@code StoreMetrics}. */
	String name();

	/**
	 * Maps a whole file for reading and writing. The mapping lives for the arena's lifetime.
	 * <p>
	 * Every engine supports this, including the direct one: {@code O_DIRECT} governs explicit reads and writes, not
	 * mappings, and the segment files are mapped whatever the log does.
	 */
	MemorySegment map(Path path, long size, Arena arena) throws IOException;

	/** Opens a file for positional reads and writes, creating it when asked. */
	FileHandle open(Path path, boolean create) throws IOException;

	/**
	 * Evicts a file's pages from the operating system's cache, so that the next read of it is a real device read.
	 * <p>
	 * Only the direct engine can do this. It is what makes "cold read" a measurement rather than an assumption.
	 *
	 * @return true when the cache was dropped
	 */
	boolean evict(Path path);

	/** Alignment that {@link FileHandle#write} requires of offset, length and buffer. 1 when there is no requirement. */
	int alignment();

	/**
	 * How many reads this engine can have outstanding at once. 1 for any engine whose reads are synchronous.
	 * <p>
	 * This is the number the whole native argument rests on: a page fault and a {@code FileChannel.read} are both
	 * synchronous, so eight pages cost eight round trips. Only a ring can ask for eight at once.
	 */
	default int queueDepth() {
		return 1;
	}

	/**
	 * Reads several ranges, issuing them together where the engine can.
	 * <p>
	 * The default is a loop, which is exactly what the synchronous engines can do and no worse than what the caller
	 * would write. {@code UringIoEngine} overrides it with a real batch, and that difference is the only reason the
	 * native layer exists.
	 *
	 * @return bytes read per request, in the order given
	 */
	default long[] readBatch(FileHandle handle, MemorySegment[] buffers, long[] lengths, long[] offsets)
		throws IOException {
		long[] read = new long[buffers.length];
		for (int i = 0; i < buffers.length; i++) {
			read[i] = handle.read(buffers[i], lengths[i], offsets[i]);
		}
		return read;
	}

	/**
	 * Asks the kernel to start bringing a range of a mapping into memory, without waiting for it.
	 *
	 * <p>
	 * This is what prefetch means on a memory mapped data path. A traversal does not read through a descriptor — it
	 * touches addresses and takes page faults — so {@link #readBatch} has nothing to batch on that path however deep
	 * the ring is. Naming the pages ahead of the walk is the only way to get them in flight concurrently, and it is
	 * the mapped-file counterpart of the batch the ring gives the log.
	 * </p>
	 *
	 * <p>
	 * Advisory: the kernel may ignore it entirely, and a caller must behave identically whether it does or not. The
	 * range is rounded out to page boundaries here, because {@code madvise} rejects an unaligned address.
	 * </p>
	 *
	 * @return true when the advice was accepted, false when it was refused or unavailable
	 */
	default boolean prefetch(MemorySegment mapping, long offset, long length) {
		if (length <= 0 || offset < 0 || offset + length > mapping.byteSize()) {
			return false;
		}
		long pageSize = 4096;
		long start = offset / pageSize * pageSize;
		long end = Math.min(mapping.byteSize(), (offset + length + pageSize - 1) / pageSize * pageSize);
		return NativeLib.madvise(mapping.asSlice(start, end - start), end - start, NativeLib.MADV_WILLNEED);
	}

	/** Counters, or {@link IoCounts#ZERO} for an engine that does not count. */
	IoCounts counts();

	@Override
	void close() throws IOException;

	/** An open file. */
	interface FileHandle extends AutoCloseable {

		/** Writes {@code length} bytes at {@code offset}. Returns the bytes written. */
		long write(MemorySegment buffer, long length, long offset) throws IOException;

		/** Reads up to {@code length} bytes from {@code offset}. Returns the bytes read. */
		long read(MemorySegment buffer, long length, long offset) throws IOException;

		/** Forces everything written so far onto the device. */
		void force() throws IOException;

		/**
		 * A buffer suitable for {@link #write}, allocated from {@code arena} and aligned as the engine requires.
		 * Direct IO rejects a buffer whose address is not aligned, and the JDK gives no alignment guarantee for a
		 * heap array, so the allocation has to come from here.
		 */
		MemorySegment buffer(Arena arena, long length);

		@Override
		void close() throws IOException;
	}

	/**
	 * Device-level operation counts.
	 * <p>
	 * Distinct from {@code IoStats}, which counts <em>record</em> touches. A record touch that hits the page cache
	 * costs nothing; a major fault is a real trip to the device. The budgets in spec/TESTING.md section 7 are stated
	 * in device reads, and until this existed they could only be measured in record reads and hoped about.
	 *
	 * @param reads
	 *            explicit positional reads issued
	 * @param writes
	 *            explicit positional writes issued
	 * @param forces
	 *            fsyncs issued
	 * @param majorFaults
	 *            page faults that went to the device, read from {@code /proc/self/stat}
	 */
	record IoCounts(long reads, long writes, long forces, long majorFaults) {

		public static final IoCounts ZERO = new IoCounts(0, 0, 0, 0);

		public IoCounts since(IoCounts earlier) {
			return new IoCounts(reads - earlier.reads, writes - earlier.writes, forces - earlier.forces,
				majorFaults - earlier.majorFaults);
		}

		/** Everything that actually reached the device on the read side. */
		public long deviceReads() {
			return reads + majorFaults;
		}
	}
}
