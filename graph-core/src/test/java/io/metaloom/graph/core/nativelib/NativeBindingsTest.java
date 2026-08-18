package io.metaloom.graph.core.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ABI contract: the crate's structs are laid out where Java thinks they are.
 *
 * <p>
 * Everything else in the native layer is checked by comparing outputs — the codecs against the Java reference, the
 * engines against each other. A struct layout cannot be checked that way, because a Java reader using the wrong
 * offsets still produces plausible values. It just produces the wrong ones, for the wrong request, under load.
 * </p>
 */
@Tag("unit")
public class NativeBindingsTest {

	/**
	 * When the build says the native layer is required, "not loaded" is a failure and not a skip.
	 *
	 * <p>
	 * Every other native test is guarded by an assumption, which is right: a Java-only contributor with no cargo must
	 * be able to run the suite, and a capability that is absent cannot be tested. The cost is that absence and success
	 * are reported identically, so a CI leg whose entire purpose is to exercise the native path passes unchanged when
	 * the shared object silently failed to build — and the run that was meant to prove the two paths agree quietly
	 * compared the pure-Java path against itself. This test is the one place that difference is asserted, and it only
	 * has an opinion when the build states one by setting the property.
	 * </p>
	 */
	@Test
	public void testTheNativeLayerIsPresentWhenTheBuildRequiresIt() {
		assumeTrue(Boolean.getBoolean("metaloom.graph.native.required"),
			"the build did not require the native layer");
		assertTrue(NativeCapabilities.isLoaded(),
			"metaloom.graph.native.required=true, but the native library is not loaded: " + NativeCapabilities.status());
	}

	@Test
	public void testTheLayoutsAgree() {
		assertTrue(NativeBindings.agrees(),
			"Java and the crate disagree about a struct layout: " + NativeBindings.mismatch().orElse("none"));
	}

	@Test
	public void testTheCrateReportsTheLayoutJavaAssumes() throws Throwable {
		assumeTrue(NativeCapabilities.isLoaded(), "the native library is not loaded: " + NativeCapabilities.status());

		SymbolLookup library = NativeCapabilities.library().orElseThrow();
		Linker linker = Linker.nativeLinker();
		MethodHandle size = linker.downcallHandle(library.findOrThrow("mlg_cqe_size"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG));
		MethodHandle offset = linker.downcallHandle(library.findOrThrow("mlg_cqe_offset"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

		assertEquals(16, (long) size.invokeExact(), "Cqe must be 16 bytes");
		assertEquals(NativeBindings.CQE.byteSize(), (long) size.invokeExact());
		assertEquals(NativeBindings.CQE_USER_DATA, (long) offset.invokeExact(0), "user_data offset");
		assertEquals(NativeBindings.CQE_RESULT, (long) offset.invokeExact(1), "result offset");
		assertEquals(NativeBindings.CQE_FLAGS, (long) offset.invokeExact(2), "flags offset");
		assertEquals(-1L, (long) offset.invokeExact(99), "an unknown field must be refused, not guessed at");
	}

	/**
	 * The library is packaged into the build output, so a consumer with only the jar can load it.
	 * <p>
	 * The paths tried before this one are relative to the working directory, which works in a build tree and nowhere
	 * else. Without the resource, a deployed application silently gets the pure-Java path forever and no test notices.
	 */
	@Test
	public void testTheLibraryIsPackagedOnTheClasspath() throws Exception {
		assumeTrue(NativeCapabilities.isLoaded(), "nothing was built to package");
		String classifier = System.getProperty("os.arch", "").contains("aarch64") ? "linux-aarch64" : "linux-x86_64";
		try (java.io.InputStream stream = NativeCapabilities.class
			.getResourceAsStream("/native/" + classifier + "/libmetaloom_graph_native.so")) {
			assertTrue(stream != null, "the shared object must be packaged under /native/" + classifier);
			assertTrue(stream.readAllBytes().length > 1024, "and must not be empty");
		}
	}

	/** The version negotiation itself, which runs before any other symbol is touched. */
	@Test
	public void testTheAbiVersionIsChecked() {
		assumeTrue(NativeCapabilities.isLoaded(), "the native library is not loaded");
		assertEquals(NativeCapabilities.SUPPORTED_ABI_VERSION, NativeCapabilities.abiVersion(),
			"a library with a different ABI version must not have been accepted");
	}
}
