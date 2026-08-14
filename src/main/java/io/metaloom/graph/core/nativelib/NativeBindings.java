package io.metaloom.graph.core.nativelib;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * The struct layouts shared with the Rust crate, and the assertions that they still agree.
 *
 * <p>
 * <b>Why this is not a comment.</b> The Java side describes the crate's completion struct by hand, in a
 * {@link MemoryLayout}, and reaps completions into an array of them. Nothing in the type system connects that
 * description to the Rust definition: if a field is added, reordered, or aligned differently by another compiler, the
 * Java reader keeps working — at the wrong stride — and attributes each completion's result to the wrong request. That
 * is a data-corruption bug that appears only under load and looks like a hardware fault.
 * </p>
 *
 * <p>
 * So the crate exports its own size and field offsets, and this class checks them against what Java believes before
 * any of it is used. A mismatch is a refusal to load, in the same way that a mismatched ABI version is: the fallback
 * to pure Java is always available and is always better than a plausible wrong answer.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/NATIVE_FFM.md">spec/NATIVE_FFM.md</a> section 3
 */
public final class NativeBindings {

	/** {@code #[repr(C)] struct Cqe { u64 user_data; i32 result; u32 flags; }} */
	public static final MemoryLayout CQE = MemoryLayout.structLayout(
		ValueLayout.JAVA_LONG.withName("user_data"),
		ValueLayout.JAVA_INT.withName("result"),
		ValueLayout.JAVA_INT.withName("flags"));

	public static final long CQE_USER_DATA = 0;
	public static final long CQE_RESULT = 8;
	public static final long CQE_FLAGS = 12;

	private static final String MISMATCH;

	static {
		String mismatch = null;
		if (NativeCapabilities.isLoaded()) {
			try {
				SymbolLookup library = NativeCapabilities.library().orElseThrow();
				Linker linker = Linker.nativeLinker();
				MethodHandle size = linker.downcallHandle(library.findOrThrow("mlg_cqe_size"),
					FunctionDescriptor.of(ValueLayout.JAVA_LONG));
				MethodHandle offset = linker.downcallHandle(library.findOrThrow("mlg_cqe_offset"),
					FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

				long nativeSize = (long) size.invokeExact();
				if (nativeSize != CQE.byteSize()) {
					mismatch = "Cqe is " + nativeSize + " bytes in the crate and " + CQE.byteSize() + " in Java";
				} else {
					long[] expected = { CQE_USER_DATA, CQE_RESULT, CQE_FLAGS };
					String[] names = { "user_data", "result", "flags" };
					for (int field = 0; field < expected.length && mismatch == null; field++) {
						long actual = (long) offset.invokeExact(field);
						if (actual != expected[field]) {
							mismatch = "Cqe." + names[field] + " is at offset " + actual
								+ " in the crate and " + expected[field] + " in Java";
						}
					}
				}
			} catch (Throwable e) {
				// An older library without the layout exports. Not a mismatch, just nothing to compare against — the
				// ABI version check has already run and is what guards the calls themselves.
				mismatch = null;
			}
		}
		MISMATCH = mismatch;
	}

	private NativeBindings() {
	}

	/** The layout disagreement between Java and the crate, if there is one. */
	public static Optional<String> mismatch() {
		return Optional.ofNullable(MISMATCH);
	}

	/** True when the layouts agree, or when there is no library to disagree with. */
	public static boolean agrees() {
		return MISMATCH == null;
	}
}
