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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The native boundary, reached through the Foreign Function and Memory API.
 * <p>
 * What lives here is only what the JVM genuinely cannot express. Hole punching is the first such capability: the JDK
 * offers no way to return the blocks of a sparse region to the filesystem, and without it a store can free records
 * forever and never give a byte back to the operating system.
 * <p>
 * Everything is capability-probed and has a pure-Java fallback, so a platform without the call simply keeps its
 * blocks. See spec/NATIVE_FFM.md section 4 for the fallback contract.
 * <p>
 * The Rust crate under {@code native-rust-lib/} carries the capabilities that need more than a libc call — the SIMD
 * adjacency codec and io_uring. It is not wired into this class yet; see spec/NATIVE_FFM.md.
 */
public final class NativeLib {

	private static final Logger logger = LoggerFactory.getLogger(NativeLib.class);

	/** {@code fallocate} modes from {@code linux/falloc.h}. */
	private static final int FALLOC_FL_KEEP_SIZE = 0x01;
	private static final int FALLOC_FL_PUNCH_HOLE = 0x02;

	private static final int O_RDWR = 2;

	private static final boolean ENABLED = !"false".equalsIgnoreCase(
		System.getProperty("metaloom.graph.native.enabled", "true"));

	private static final MethodHandle FALLOCATE;
	private static final MethodHandle OPEN;
	private static final MethodHandle CLOSE;
	private static final boolean AVAILABLE;

	static {
		MethodHandle fallocate = null;
		MethodHandle open = null;
		MethodHandle close = null;
		boolean available = false;
		if (ENABLED) {
			try {
				Linker linker = Linker.nativeLinker();
				SymbolLookup libc = linker.defaultLookup();
				fallocate = linker.downcallHandle(libc.findOrThrow("fallocate"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
				open = linker.downcallHandle(libc.findOrThrow("open"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
				close = linker.downcallHandle(libc.findOrThrow("close"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
				available = true;
				logger.debug("Native hole punching is available");
			} catch (RuntimeException e) {
				logger.info("Native hole punching is unavailable ({}); freed pages will keep their blocks",
					e.toString());
			}
		}
		FALLOCATE = fallocate;
		OPEN = open;
		CLOSE = close;
		AVAILABLE = available;
	}

	private NativeLib() {
	}

	public static boolean isAvailable() {
		return AVAILABLE;
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
			int fd = (int) OPEN.invokeExact(name, O_RDWR);
			if (fd < 0) {
				return false;
			}
			try {
				int result = (int) FALLOCATE.invokeExact(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE,
					offset, length);
				return result == 0;
			} finally {
				int ignored = (int) CLOSE.invokeExact(fd);
			}
		} catch (Throwable e) {
			logger.debug("punchHole failed for {} at {}+{}: {}", path, offset, length, e.toString());
			return false;
		}
	}

	/** A one-line description of what the native layer can do, for {@code StoreStats} and logs. */
	public static String describe() {
		if (!ENABLED) {
			return "disabled by metaloom.graph.native.enabled=false";
		}
		return AVAILABLE ? "libc: fallocate punch-hole" : "unavailable, pure-Java fallback";
	}

	public static Optional<String> unavailableReason() {
		return AVAILABLE ? Optional.empty() : Optional.of(describe());
	}
}
