package io.metaloom.graph.server.nativeimage;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * The one substitution the native build needs, and the only file in this source root.
 *
 * <p>
 * This root is compiled only by the {@code native} profile, against a {@code provided}-scope GraalVM
 * dependency. Nothing here is on the classpath of the ordinary jar or of the test suite.
 * </p>
 *
 * <h2>The problem</h2>
 *
 * <p>
 * Netty allocates direct buffers through {@code io.netty.util.internal.CleanerJava24Linker}, which calls libc
 * {@code malloc} and {@code free} over the FFM API. It has to build those two downcall handles
 * <b>reflectively</b>, because netty compiles against Java 8 and cannot name {@code java.lang.foreign} in its
 * source. Native image cannot see through that: the call site is a plain {@code MethodHandle.invokeExact} on a
 * handle of unknown provenance, so the analysis reaches {@code MethodHandle.linkToNative} and stops with
 * </p>
 *
 * <pre>
 * Error encountered while parsing io.netty.util.internal.CleanerJava24Linker.malloc
 *   unexpected input could not be handled: linkToNative
 * </pre>
 *
 * <p>
 * Registering the two descriptors in {@code reachability-metadata.json} does not help. Registration lets
 * <em>run-time</em> {@code Linker.downcallHandle} calls find a stub; it does not teach the static analysis how to
 * compile an invoke it cannot resolve. The engine's own downcalls are unaffected — they name the API directly, and
 * spec/GRAALVM.md section 3 records that they build and run untouched.
 * </p>
 *
 * <h2>The fix, and why it is not a disable</h2>
 *
 * <p>
 * The two methods are replaced with the same two libc calls written the way native-image can compile: a direct
 * {@link Linker#downcallHandle} against a descriptor the image has a stub for. Netty keeps its direct buffers and
 * keeps freeing them; only the route to {@code malloc} changes.
 * </p>
 *
 * <p>
 * The obvious alternative — substituting {@code isSupported()} to return {@code false} so netty picks another
 * cleaner — was tried first and does not work, in a way worth recording: netty's {@code PlatformDependent} is
 * initialised at <b>build</b> time, so it chooses its cleaner on the builder JVM, where substitutions do not apply.
 * The image then holds a {@code CleanerJava24Linker} instance that was selected before {@code isSupported()} could
 * answer. The server started, bound its port, and returned an empty reply to every request, because the first
 * direct-buffer allocation on the event loop threw and netty logged it nowhere.
 * </p>
 *
 * <p>
 * Delete this class the day netty ships FFM metadata of its own; {@code GraphServerNativeTest} covers the
 * behaviour it protects, not the mechanism.
 * </p>
 *
 * @see <a href="../../../../../../../../../spec/GRAALVM.md">spec/GRAALVM.md</a> section 5
 */
@TargetClass(className = "io.netty.util.internal.CleanerJava24Linker")
final class Target_io_netty_util_internal_CleanerJava24Linker {

	/** Netty's contract: a zero return means the allocation failed, and the caller raises {@link OutOfMemoryError}. */
	@Substitute
	static long malloc(int size) {
		// The Math.max mirrors netty's own body: malloc(0) may legally return null, which netty would read as
		// out of memory.
		return Libc.malloc(Math.max(size, 1));
	}

	@Substitute
	static void free(long address) {
		Libc.free(address);
	}
}

/**
 * {@code malloc} and {@code free}, bound the way the rest of this project binds libc.
 *
 * <p>
 * The descriptors are registered in graph-server's {@code reachability-metadata.json}. A mismatch between the two is
 * a {@code MissingForeignRegistrationError} on the first HTTP request rather than a build failure, which is why the
 * native profile's smoke test asks for a page and not just a version string.
 * </p>
 */
final class Libc {

	private static final MethodHandle MALLOC;
	private static final MethodHandle FREE;

	static {
		Linker linker = Linker.nativeLinker();
		MALLOC = linker.downcallHandle(linker.defaultLookup().findOrThrow("malloc"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		FREE = linker.downcallHandle(linker.defaultLookup().findOrThrow("free"),
			FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG));
	}

	private Libc() {
	}

	static long malloc(long size) {
		try {
			return (long) MALLOC.invokeExact(size);
		} catch (Throwable e) {
			throw new Error("malloc failed", e);
		}
	}

	static void free(long address) {
		try {
			FREE.invokeExact(address);
		} catch (Throwable e) {
			throw new Error("free failed", e);
		}
	}
}
