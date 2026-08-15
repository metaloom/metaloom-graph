package io.metaloom.graph.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The process's major fault count, read from {@code /proc/self/stat}.
 *
 * <p>
 * <b>This is the only honest way to count device reads on a memory-mapped data path.</b> A record read is an address
 * computation; whether it costs a trip to the device depends on whether the page is resident, and Java cannot see
 * that. The kernel can, and it counts: a <em>major</em> fault is a fault that had to read from the device, a minor one
 * was served from memory.
 * </p>
 *
 * <p>
 * Two performance targets in spec/TESTING.md section 7 — cold node read, and indexed lookup device reads — sat
 * unmeasured for three phases because nothing here could count them. Combined with
 * {@link io.metaloom.graph.core.nativelib.NativeLib#evictFromPageCache} to make a store genuinely cold, this closes
 * both.
 * </p>
 *
 * <p>
 * Linux only, and it counts the <b>whole process</b>: a concurrent test in the same JVM inflates it. Measurements
 * using this must be single threaded and should subtract two snapshots taken close together.
 * </p>
 */
public final class PageFaults {

	private static final Path STAT = Path.of("/proc/self/stat");

	/** Field 12 of {@code /proc/self/stat}, one-based, per {@code proc(5)}. */
	private static final int MAJFLT_FIELD = 12;

	private static final boolean AVAILABLE = Files.isReadable(STAT);

	private PageFaults() {
	}

	public static boolean isAvailable() {
		return AVAILABLE;
	}

	/** Major faults this process has taken, or -1 where the counter is unavailable. */
	public static long major() {
		if (!AVAILABLE) {
			return -1;
		}
		try {
			String stat = Files.readString(STAT);
			// The second field is the executable name in parentheses and may contain spaces, so parsing starts after
			// the last ')' rather than splitting the whole line.
			int close = stat.lastIndexOf(')');
			if (close < 0) {
				return -1;
			}
			String[] fields = stat.substring(close + 2).split(" ");
			// After the closing parenthesis, field 3 (state) is index 0.
			int index = MAJFLT_FIELD - 3;
			return index < fields.length ? Long.parseLong(fields[index]) : -1;
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}
}
