package io.metaloom.graph.core.nativelib;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The crate's codecs, as the engine uses them. Today that means lz4 for blobs.
 *
 * <p>
 * <b>Compression is optional and decompression is not.</b> This class only compresses. Reading a compressed blob back
 * goes through {@link io.metaloom.graph.core.util.Lz4}, which is pure Java, so a store written on a machine with the
 * native library is readable on one without it. Nothing here may become load-bearing for reading a store — that is the
 * fallback rule in spec/NATIVE_FFM.md section 5, and compression is exactly the kind of capability that would quietly
 * break it.
 * </p>
 *
 * <p>
 * The crate's CRC32C and edge codec are deliberately <em>not</em> exposed. Both were measured: the edge codec is 1.38x
 * faster than Java including the boundary crossing, on a loop that is under one percent of a traversal, and CRC32C is
 * 7.8x <em>slower</em> than {@code java.util.zip.CRC32C} because the JDK's is an intrinsic for the hardware
 * instruction. Neither is worth a downcall.
 * </p>
 */
public final class NativeCodec {

	private static final MethodHandle COMPRESS;
	private static final boolean AVAILABLE;

	static {
		MethodHandle compress = null;
		boolean available = false;
		if (NativeCapabilities.has(NativeCapabilities.CAP_LZ4)) {
			try {
				SymbolLookup library = NativeCapabilities.library().orElseThrow();
				compress = Linker.nativeLinker().downcallHandle(library.findOrThrow("mlg_lz4_compress"),
					FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
						ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
				available = true;
			} catch (RuntimeException e) {
				available = false;
			}
		}
		COMPRESS = compress;
		AVAILABLE = available;
	}

	private NativeCodec() {
	}

	/** Whether blobs can be compressed here. When false they are stored whole, which is always readable. */
	public static boolean isCompressionAvailable() {
		return AVAILABLE;
	}

	/**
	 * Compresses with lz4.
	 *
	 * @return the compressed bytes, or null when compression is unavailable or did not help
	 */
	public static byte[] compress(byte[] data) {
		if (!AVAILABLE || data.length == 0) {
			return null;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment source = arena.allocate(data.length);
			MemorySegment.copy(MemorySegment.ofArray(data), 0, source, 0, data.length);
			// Twice the input plus a floor. lz4's theoretical worst case is only a fraction over the input, but
			// lz4_flex refuses a buffer sized that tightly — it returned EINVAL on a 50,260 byte buffer for 50,000
			// bytes of input — so the bound is set where the crate is known to accept it. The buffer is confined to
			// this call and freed on the way out, so being generous costs nothing that outlives the compression.
			long bound = data.length * 2L + 64;
			MemorySegment target = arena.allocate(bound);
			long written = (long) COMPRESS.invokeExact(source, (long) data.length, target, bound);
			if (written <= 0 || written >= data.length) {
				return null;
			}
			byte[] result = new byte[(int) written];
			MemorySegment.copy(target, 0, MemorySegment.ofArray(result), 0, written);
			return result;
		} catch (Throwable e) {
			// A codec that fails is a reason to store the blob whole, not a reason to fail the write.
			return null;
		}
	}
}
