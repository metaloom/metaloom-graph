package io.metaloom.graph.core.nativelib;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Negotiation with the Rust crate: load it if it is there, refuse it if it is the wrong ABI, and report what it can do.
 *
 * <p>
 * The contract from spec/NATIVE_FFM.md section 3 is that the Java side <b>asks</b> rather than assumes. A shared object
 * built from a different revision of the crate is worse than none at all — the codecs write to the same pages the Java
 * ones read — so the version is checked before any other symbol is looked up, and a mismatch disables the library
 * rather than trusting it.
 * </p>
 *
 * <p>
 * Nothing in the engine's data path calls through here. That is deliberate and is what the measurement in
 * spec/reports/PHASE_STATUS.md section 3 concluded: the crate's edge codec is 1.38x faster than the Java one and its
 * CRC32C is 200x slower than the JDK's hardware-backed intrinsic, so wiring it in would trade a dependency for
 * approximately nothing. This class exists so that when a capability arrives that <em>is</em> worth having — io_uring
 * is the one the design is waiting for — the negotiation is already built and tested.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/NATIVE_FFM.md">spec/NATIVE_FFM.md</a> section 3
 */
public final class NativeCapabilities {

	private static final Logger logger = LoggerFactory.getLogger(NativeCapabilities.class);

	/** The ABI this build speaks. A library reporting anything else is not loaded. */
	public static final int SUPPORTED_ABI_VERSION = 2;

	public static final long CAP_CRC32C = 1L << 0;
	public static final long CAP_EDGE_CODEC = 1L << 1;
	public static final long CAP_XXH3 = 1L << 2;
	public static final long CAP_LZ4 = 1L << 3;
	public static final long CAP_URING = 1L << 4;

	private static final String LIBRARY_FILE = "libmetaloom_graph_native.so";

	private static final String[] SEARCH_PATHS = {
		"native-rust-lib/target/release/libmetaloom_graph_native.so",
		"target/native/libmetaloom_graph_native.so"
	};

	private static final Arena ARENA;
	private static final SymbolLookup LIBRARY;
	private static final int VERSION;
	private static final long CAPABILITIES;
	private static final String STATUS;

	static {
		Arena arena = null;
		SymbolLookup library = null;
		int version = 0;
		long capabilities = 0;
		String status;

		Path path = locate();
		if (!NativeLib.isEnabled()) {
			status = "disabled";
		} else if (path == null) {
			status = "not built";
		} else {
			try {
				arena = Arena.ofShared();
				SymbolLookup candidate = SymbolLookup.libraryLookup(path.toAbsolutePath(), arena);
				Linker linker = Linker.nativeLinker();
				MethodHandle versionHandle = linker.downcallHandle(candidate.findOrThrow("mlg_version"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT));
				version = (int) versionHandle.invokeExact();
				if (version != SUPPORTED_ABI_VERSION) {
					// Refusing is the whole point of asking. A crate that has changed its edge encoding underneath a
					// store would corrupt every block it touched.
					status = "ABI " + version + ", this build speaks " + SUPPORTED_ABI_VERSION + "; not loaded";
					arena.close();
					arena = null;
				} else {
					MethodHandle capabilityHandle = linker.downcallHandle(candidate.findOrThrow("mlg_capabilities"),
						FunctionDescriptor.of(ValueLayout.JAVA_LONG));
					capabilities = (long) capabilityHandle.invokeExact();
					library = candidate;
					status = "ABI " + version;
					logger.debug("Rust native layer loaded from {}: {}", path, describeCapabilities(capabilities));
				}
			} catch (Throwable e) {
				status = "failed to load: " + e;
				if (arena != null) {
					arena.close();
					arena = null;
				}
				version = 0;
				capabilities = 0;
			}
		}

		ARENA = arena;
		LIBRARY = library;
		VERSION = version;
		CAPABILITIES = capabilities;
		STATUS = status;
	}

	private NativeCapabilities() {
	}

	private static Path locate() {
		String override = System.getProperty("metaloom.graph.native.library");
		if (override != null) {
			Path path = Path.of(override);
			return Files.isRegularFile(path) ? path : null;
		}
		for (String candidate : SEARCH_PATHS) {
			Path path = Path.of(candidate);
			if (Files.isRegularFile(path)) {
				return path;
			}
		}
		return fromClasspath();
	}

	/**
	 * The library as packaged inside the jar, unpacked to a temporary file so it can be dlopened.
	 *
	 * <p>
	 * The search paths above are relative to the working directory, which is fine for a build tree and useless for a
	 * deployed application: nothing guarantees a consumer runs from the directory the crate was built in. Shipping the
	 * shared object as a resource under its platform classifier is what makes the native layer available to anyone who
	 * puts the jar on their path, and the classifier in the resource name is what keeps a linux-x86_64 build from
	 * being loaded on aarch64.
	 * </p>
	 *
	 * <p>
	 * Unpacking is necessary because {@code dlopen} takes a filesystem path and cannot read out of a jar. The copy
	 * goes to a temporary file marked delete-on-exit; failing to unpack is not an error, it just means the pure-Java
	 * path is used.
	 * </p>
	 */
	private static Path fromClasspath() {
		String classifier = System.getProperty("os.arch", "").contains("aarch64") ? "linux-aarch64" : "linux-x86_64";
		String resource = "/native/" + classifier + "/" + LIBRARY_FILE;
		try (java.io.InputStream stream = NativeCapabilities.class.getResourceAsStream(resource)) {
			if (stream == null) {
				return null;
			}
			Path unpacked = Files.createTempFile("metaloom-graph-native-", ".so");
			unpacked.toFile().deleteOnExit();
			Files.copy(stream, unpacked, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return unpacked;
		} catch (java.io.IOException e) {
			return null;
		}
	}

	public static boolean isLoaded() {
		return LIBRARY != null;
	}

	public static int abiVersion() {
		return VERSION;
	}

	public static long capabilities() {
		return CAPABILITIES;
	}

	public static boolean has(long capability) {
		return (CAPABILITIES & capability) != 0;
	}

	/** The symbol lookup, for a caller that has checked the capability it needs. Empty when nothing is loaded. */
	public static java.util.Optional<SymbolLookup> library() {
		return java.util.Optional.ofNullable(LIBRARY);
	}

	public static String status() {
		return STATUS;
	}

	public static String describeCapabilities(long bits) {
		List<String> names = new ArrayList<>();
		if ((bits & CAP_CRC32C) != 0) {
			names.add("crc32c");
		}
		if ((bits & CAP_EDGE_CODEC) != 0) {
			names.add("edge-codec");
		}
		if ((bits & CAP_XXH3) != 0) {
			names.add("xxh3");
		}
		if ((bits & CAP_LZ4) != 0) {
			names.add("lz4");
		}
		if ((bits & CAP_URING) != 0) {
			names.add("io_uring");
		}
		return names.isEmpty() ? "none" : String.join(",", names);
	}

	static String describeSuffix() {
		if (!isLoaded()) {
			return "; rust: " + STATUS;
		}
		return "; rust " + STATUS + " [" + describeCapabilities(CAPABILITIES) + ", unused]";
	}

	static Arena arena() {
		return ARENA;
	}
}
