package io.metaloom.graph.core.nativelib;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Optional;

import io.metaloom.graph.core.util.Layouts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The native boundary, reached through the Foreign Function and Memory API.
 * <p>
 * What lives here is only what the JVM genuinely cannot express:
 * <ul>
 * <li><b>Hole punching</b> — the JDK offers no way to return the blocks of a sparse region to the filesystem, and
 * without it a store can free records forever and never give a byte back to the operating system.</li>
 * <li><b>{@code O_DIRECT}</b> — {@link java.nio.channels.FileChannel} cannot open with it, and it is what lets the
 * write-ahead log bypass the page cache instead of dirtying it with data nothing will ever read again.</li>
 * <li><b>{@code posix_fadvise(DONTNEED)}</b> — evicting a file from the page cache. Without it, "cold read" cannot be
 * measured on a machine where dropping caches needs root, which is why two performance targets went unmeasured for
 * three phases.</li>
 * </ul>
 * <p>
 * Everything is capability-probed and has a pure-Java fallback, so a platform without the call keeps its blocks, uses
 * buffered writes, and cannot measure cold reads. See spec/NATIVE_FFM.md section 4 for the fallback contract.
 * <p>
 * The Rust crate under {@code native-rust-lib/} carries the capabilities that need more than a libc call — the SIMD
 * adjacency codec and io_uring. {@link NativeCapabilities} negotiates with it; nothing in the engine depends on it.
 */
public final class NativeLib {

	private static final Logger logger = LoggerFactory.getLogger(NativeLib.class);

	/** {@code fallocate} modes from {@code linux/falloc.h}. */
	private static final int FALLOC_FL_KEEP_SIZE = 0x01;
	private static final int FALLOC_FL_PUNCH_HOLE = 0x02;

	private static final int O_RDWR = 2;
	private static final int O_CREAT = 0100;
	/**
	 * {@code O_DIRECT} is one of the flags arm64 does <em>not</em> take from {@code asm-generic/fcntl.h}: its own header
	 * defines it as {@code 0200000}, and {@code 040000} there is {@code O_DIRECTORY}. With the generic value every
	 * direct open on aarch64 failed and fell back to buffered I/O without saying so — and the jar ships an aarch64
	 * library. x86-64 and riscv use the generic value.
	 */
	private static final int O_DIRECT = System.getProperty("os.arch", "").contains("aarch64") ? 0200000 : 040000;
	private static final int O_DSYNC = 010000;

	/** {@code POSIX_FADV_DONTNEED} from {@code fcntl.h}. */
	private static final int POSIX_FADV_DONTNEED = 4;

	/** Alignment {@code O_DIRECT} requires of the buffer, the offset and the length. */
	public static final int DIRECT_IO_ALIGNMENT = 4096;

	private static final boolean ENABLED = !"false".equalsIgnoreCase(
		System.getProperty("metaloom.graph.native.enabled", "true"));

	private static final MethodHandle FALLOCATE;
	private static final MethodHandle OPEN;
	private static final MethodHandle CLOSE;
	private static final MethodHandle PWRITE;
	private static final MethodHandle PREAD;
	private static final MethodHandle FSYNC;
	private static final MethodHandle FADVISE;
	private static final MethodHandle MADVISE;
	private static final MethodHandle STATX;
	private static final MethodHandle IOCTL;
	private static final boolean AVAILABLE;
	private static final boolean DIRECT_AVAILABLE;

	static {
		MethodHandle fallocate = null;
		MethodHandle open = null;
		MethodHandle close = null;
		MethodHandle pwrite = null;
		MethodHandle pread = null;
		MethodHandle fsync = null;
		MethodHandle fadvise = null;
		MethodHandle madvise = null;
		MethodHandle statx = null;
		MethodHandle ioctl = null;
		boolean available = false;
		boolean direct = false;
		if (ENABLED) {
			Linker linker = Linker.nativeLinker();
			SymbolLookup libc = linker.defaultLookup();
			try {
				fallocate = linker.downcallHandle(libc.findOrThrow("fallocate"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
				open = linker.downcallHandle(libc.findOrThrow("open"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT));
				close = linker.downcallHandle(libc.findOrThrow("close"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
				available = true;
				logger.debug("Native hole punching is available");
			} catch (RuntimeException e) {
				logger.info("Native hole punching is unavailable ({}); freed pages will keep their blocks",
					e.toString());
			}
			// The direct-IO group is probed separately: a platform can have fallocate and not the rest, and the WAL
			// must fall back to buffered writes rather than fail to open.
			try {
				pwrite = linker.downcallHandle(libc.findOrThrow("pwrite"),
					FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
				pread = linker.downcallHandle(libc.findOrThrow("pread"),
					FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
				fsync = linker.downcallHandle(libc.findOrThrow("fsync"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
				fadvise = linker.downcallHandle(libc.findOrThrow("posix_fadvise"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
				direct = available;
			} catch (RuntimeException e) {
				logger.info("Direct IO is unavailable ({}); the log will use buffered writes", e.toString());
			}
			// madvise stands apart from the direct-IO group: it is what prefetching means on a memory mapped data
			// path, and it is useful whether or not O_DIRECT works here.
			try {
				madvise = linker.downcallHandle(libc.findOrThrow("madvise"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
						ValueLayout.JAVA_INT));
			} catch (RuntimeException e) {
				logger.info("madvise is unavailable ({}); prefetch requests will be ignored", e.toString());
			}
			// statx reports allocated blocks rather than apparent length, which is the only way to see what a sparse
			// segment with punched holes actually costs. Probed on its own: it is a Linux 4.11 syscall.
			try {
				statx = linker.downcallHandle(libc.findOrThrow("statx"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			} catch (RuntimeException e) {
				logger.debug("statx is unavailable ({}); allocated size will be unknown", e.toString());
			}
			// ioctl carries FICLONE, the reflink primitive. Variadic in C, bound here for the one three-argument form
			// this uses.
			try {
				ioctl = linker.downcallHandle(libc.findOrThrow("ioctl"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT));
			} catch (RuntimeException e) {
				logger.debug("ioctl is unavailable ({}); reflink cloning will not be offered", e.toString());
			}
		}
		FALLOCATE = fallocate;
		OPEN = open;
		CLOSE = close;
		PWRITE = pwrite;
		PREAD = pread;
		FSYNC = fsync;
		FADVISE = fadvise;
		MADVISE = madvise;
		STATX = statx;
		IOCTL = ioctl;
		AVAILABLE = available;
		DIRECT_AVAILABLE = direct;
	}

	private NativeLib() {
	}

	public static boolean isAvailable() {
		return AVAILABLE;
	}

	/** Whether {@code O_DIRECT} opens, positional reads and writes, and cache eviction are all reachable. */
	public static boolean isDirectIoAvailable() {
		return DIRECT_AVAILABLE;
	}

	/**
	 * Returns the blocks of a byte range to the filesystem, leaving a hole. The file keeps its length and the region
	 * reads back as zeros.
	 *
	 * @return true when the range was punched, false when the capability is unavailable or the call failed
	 */
	public static boolean punchHole(Path path, long offset, long length) {
		if (!AVAILABLE || length <= 0) {
			return false;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(path.toAbsolutePath().toString());
			int fd = open(name, O_RDWR);
			if (fd < 0) {
				return false;
			}
			try {
				int result = (int) FALLOCATE.invokeExact(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE,
					offset, length);
				return result == 0;
			} finally {
				close(fd);
			}
		} catch (Throwable e) {
			logger.debug("punchHole failed for {} at {}+{}: {}", path, offset, length, e.toString());
			return false;
		}
	}

	// ---------------------------------------------------------------- direct IO

	/**
	 * Opens a file for reading and writing, optionally bypassing the page cache.
	 *
	 * @param direct
	 *            add {@code O_DIRECT}. Every read and write must then be aligned to
	 *            {@link #DIRECT_IO_ALIGNMENT} in buffer address, file offset and length.
	 * @return the descriptor, or a negative value on failure
	 */
	public static int openFile(Path path, boolean direct, boolean create) {
		if (!DIRECT_AVAILABLE) {
			return -1;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(path.toAbsolutePath().toString());
			int flags = O_RDWR | (create ? O_CREAT : 0) | (direct ? O_DIRECT : 0);
			int fd = open(name, flags);
			if (fd < 0 && direct) {
				// tmpfs and some overlay and network filesystems reject O_DIRECT outright. Falling back to a buffered
				// descriptor is correct: durability comes from the fsync, not from the flag.
				logger.debug("O_DIRECT rejected for {}, falling back to a buffered descriptor", path);
				return open(name, flags & ~O_DIRECT);
			}
			return fd;
		} catch (Throwable e) {
			logger.debug("open failed for {}: {}", path, e.toString());
			return -1;
		}
	}

	public static long pwrite(int fd, MemorySegment buffer, long length, long offset) {
		try {
			return (long) PWRITE.invokeExact(fd, buffer, length, offset);
		} catch (Throwable e) {
			return -1;
		}
	}

	public static long pread(int fd, MemorySegment buffer, long length, long offset) {
		try {
			return (long) PREAD.invokeExact(fd, buffer, length, offset);
		} catch (Throwable e) {
			return -1;
		}
	}

	public static boolean fsync(int fd) {
		try {
			return (int) FSYNC.invokeExact(fd) == 0;
		} catch (Throwable e) {
			return false;
		}
	}

	public static void closeFile(int fd) {
		if (fd >= 0) {
			close(fd);
		}
	}

	/**
	 * Evicts a file's pages from the page cache.
	 * <p>
	 * This is what makes a cold-read measurement possible without root: {@code /proc/sys/vm/drop_caches} needs
	 * privileges, {@code posix_fadvise} needs only the descriptor. Clean pages go immediately; dirty ones are written
	 * back first, so callers should force before asking.
	 *
	 * @return true when the advice was accepted
	 */
	public static boolean evictFromPageCache(Path path) {
		if (!DIRECT_AVAILABLE) {
			return false;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(path.toAbsolutePath().toString());
			int fd = open(name, O_RDWR);
			if (fd < 0) {
				return false;
			}
			try {
				if (!fsync(fd)) {
					return false;
				}
				return (int) FADVISE.invokeExact(fd, 0L, 0L, POSIX_FADV_DONTNEED) == 0;
			} finally {
				close(fd);
			}
		} catch (Throwable e) {
			logger.debug("posix_fadvise failed for {}: {}", path, e.toString());
			return false;
		}
	}

	private static int open(MemorySegment name, int flags) {
		try {
			// 0666 matters only when O_CREAT is set; the umask trims it.
			return (int) OPEN.invokeExact(name, flags, 0666);
		} catch (Throwable e) {
			return -1;
		}
	}

	private static void close(int fd) {
		try {
			int ignored = (int) CLOSE.invokeExact(fd);
		} catch (Throwable e) {
			logger.debug("close({}) failed: {}", fd, e.toString());
		}
	}

	/** A one-line description of what the native layer can do, for {@code StoreStats} and logs. */
	/** {@code MADV_WILLNEED}: start reading these pages now, do not wait for them. */
	public static final int MADV_WILLNEED = 3;

	/** {@code MADV_RANDOM}: this range will be read out of order, so readahead is wasted on it. */
	public static final int MADV_RANDOM = 1;

	/**
	 * Advises the kernel about a range of a mapping. Capability N3, and the prefetch primitive for a memory mapped
	 * data path.
	 *
	 * <p>
	 * <b>Why this and not the ring.</b> The io_uring engine can fetch eight scattered blocks in the time one
	 * synchronous read takes, which is worth 12.4x — but only for a caller that reads through a file descriptor. This
	 * store's data path is a mapping, so a traversal does not issue reads at all; it touches addresses and takes page
	 * faults. The way to overlap those is to tell the kernel which pages are coming, which is what this does. The ring
	 * becomes the right answer for the data path on the day it stops being memory mapped, and is already the right one
	 * for the log.
	 * </p>
	 *
	 * <p>
	 * Advisory in the strict sense: the kernel is free to ignore it, and a failure is not an error for the caller. The
	 * address must be page aligned, which is why callers go through {@link io.metaloom.graph.core.io.IoEngine#prefetch}
	 * rather than calling this directly.
	 * </p>
	 *
	 * @return false when the call was refused or the binding is unavailable
	 */
	public static boolean madvise(MemorySegment range, long length, int advice) {
		if (MADVISE == null || length <= 0) {
			return false;
		}
		try {
			return (int) MADVISE.invokeExact(range, length, advice) == 0;
		} catch (Throwable e) {
			return false;
		}
	}

	public static boolean isMadviseAvailable() {
		return MADVISE != null;
	}

	// ---------------------------------------------------------------- N3: statx and reflink

	/** {@code STATX_BLOCKS}: ask only for the allocated block count. */
	private static final int STATX_BLOCKS = 0x400;
	/** Offsets into {@code struct statx}, which is stable kernel ABI. */
	private static final long STATX_OFF_SIZE = 40;
	private static final long STATX_OFF_BLOCKS = 48;
	/** A statx block is always 512 bytes, whatever the filesystem's block size is. */
	private static final int STATX_BLOCK_BYTES = 512;
	private static final int AT_FDCWD = -100;

	/**
	 * Bytes actually allocated to a file, as opposed to its apparent length.
	 *
	 * <p>
	 * Capability N3. The segment files are sparse and {@code reclaim()} punches holes in them, so
	 * {@link java.nio.file.Files#size} reports what the file spans rather than what it costs — the two differ by
	 * gigabytes on a store that has freed pages. Until now that difference could only be seen by shelling out to
	 * {@code du}, which is not something an engine should do to answer a question about itself.
	 * </p>
	 *
	 * @return allocated bytes, or -1 when statx is unavailable or the call failed
	 */
	public static long allocatedBytes(Path path) {
		if (STATX == null) {
			return -1;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(path.toString());
			// struct statx is 256 bytes of stable ABI; only the two fields read below are touched.
			MemorySegment buffer = arena.allocate(256, 8);
			int result = (int) STATX.invokeExact(AT_FDCWD, name, 0, STATX_BLOCKS, buffer);
			if (result != 0) {
				return -1;
			}
			return buffer.get(Layouts.LONG, STATX_OFF_BLOCKS) * STATX_BLOCK_BYTES;
		} catch (Throwable e) {
			return -1;
		}
	}

	/** Apparent length through statx, so a caller can compare it with {@link #allocatedBytes} from one syscall family. */
	public static long apparentBytes(Path path) {
		if (STATX == null) {
			return -1;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(path.toString());
			MemorySegment buffer = arena.allocate(256, 8);
			if ((int) STATX.invokeExact(AT_FDCWD, name, 0, STATX_BLOCKS, buffer) != 0) {
				return -1;
			}
			return buffer.get(Layouts.LONG, STATX_OFF_SIZE);
		} catch (Throwable e) {
			return -1;
		}
	}

	public static boolean isStatxAvailable() {
		return STATX != null;
	}

	/** {@code FICLONE}: make this file a copy-on-write clone of another. */
	private static final int FICLONE = 0x40049409;

	/**
	 * Clones a file by reference, sharing its blocks copy-on-write.
	 *
	 * <p>
	 * Capability N3, and the primitive a snapshot would be built on: cloning a segment file costs no data copy and no
	 * space until one of the two copies is written to. <b>Nothing in the engine calls this yet</b> — there is no
	 * backup or snapshot feature to call it — so it is bound, tested for behaviour, and otherwise unused. It is here
	 * because the alternative when that feature arrives is discovering that the filesystem support was never checked.
	 * </p>
	 *
	 * <p>
	 * Works on btrfs, xfs with reflink, and bcachefs. ext4 has no reflink and returns {@code EOPNOTSUPP}, which is a
	 * false rather than an error: a caller falls back to copying.
	 * </p>
	 *
	 * @return true when the clone was made
	 */
	public static boolean reflink(Path source, Path target) {
		if (IOCTL == null) {
			return false;
		}
		int sourceFd = openFile(source, false, false);
		if (sourceFd < 0) {
			return false;
		}
		int targetFd = openFile(target, false, true);
		if (targetFd < 0) {
			closeFile(sourceFd);
			return false;
		}
		try {
			return (int) IOCTL.invokeExact(targetFd, FICLONE, sourceFd) == 0;
		} catch (Throwable e) {
			return false;
		} finally {
			closeFile(targetFd);
			closeFile(sourceFd);
		}
	}

	public static boolean isReflinkAvailable() {
		return IOCTL != null;
	}

	public static String describe() {
		if (!ENABLED) {
			return "disabled by metaloom.graph.native.enabled=false";
		}
		if (!AVAILABLE) {
			return "unavailable, pure-Java fallback";
		}
		return "libc: punch-hole" + (DIRECT_AVAILABLE ? ", O_DIRECT, fadvise" : "")
			+ NativeCapabilities.describeSuffix();
	}

	public static Optional<String> unavailableReason() {
		return AVAILABLE ? Optional.empty() : Optional.of(describe());
	}

	/** The flag that decides whether the whole native path is tried at all. Tests flip it to prove equivalence. */
	public static boolean isEnabled() {
		return ENABLED;
	}

	static int directFlag() {
		return O_DIRECT;
	}

	static int dsyncFlag() {
		return O_DSYNC;
	}
}
