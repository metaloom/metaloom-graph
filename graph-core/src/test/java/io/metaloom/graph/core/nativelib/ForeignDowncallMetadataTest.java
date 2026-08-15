package io.metaloom.graph.core.nativelib;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GraalVM foreign metadata has to list every downcall shape the engine uses, and nothing tells you when it stops
 * doing so.
 *
 * <p>
 * A native image cannot generate a downcall stub at run time, so every distinct {@code FunctionDescriptor} has to be
 * declared in {@code META-INF/native-image/io.metaloom.graph/graph-core/reachability-metadata.json}. Adding a libc
 * binding and forgetting the metadata does not break the JVM build, does not break the native <em>build</em> either,
 * and surfaces as a {@code MissingForeignRegistrationError} the first time that particular call is made — which for
 * half of these is a code path a smoke test never reaches. This test closes that gap by reading the source and the
 * metadata and comparing them.
 * </p>
 *
 * <p>
 * It compares in both directions on purpose. A missing entry is a latent crash; a stale entry is a claim about the
 * engine that stopped being true, and this file is the only place a reader can look up which call each shape belongs
 * to.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/GRAALVM.md">spec/GRAALVM.md</a> section 3
 */
public class ForeignDowncallMetadataTest {

	/** Every class that builds a {@link java.lang.foreign.FunctionDescriptor}. */
	private static final List<String> SOURCES = List.of(
		"src/main/java/io/metaloom/graph/core/nativelib/NativeLib.java",
		"src/main/java/io/metaloom/graph/core/nativelib/NativeCapabilities.java",
		"src/main/java/io/metaloom/graph/core/nativelib/NativeBindings.java",
		"src/main/java/io/metaloom/graph/core/nativelib/NativeCodec.java",
		"src/main/java/io/metaloom/graph/core/io/UringIoEngine.java");

	private static final Path METADATA =
		Path.of("src/main/resources/META-INF/native-image/io.metaloom.graph/graph-core/reachability-metadata.json");

	/**
	 * Java carrier to the name GraalVM's parser accepts, which is the key set of
	 * {@code Linker.canonicalLayouts()} and not the name of the {@code ValueLayout} constant.
	 */
	private static final Map<String, String> LAYOUTS = Map.of(
		"JAVA_BOOLEAN", "jboolean",
		"JAVA_BYTE", "jbyte",
		"JAVA_CHAR", "jchar",
		"JAVA_SHORT", "jshort",
		"JAVA_INT", "jint",
		"JAVA_LONG", "jlong",
		"JAVA_FLOAT", "jfloat",
		"JAVA_DOUBLE", "jdouble",
		"ADDRESS", "void*");

	@Test
	@DisplayName("every downcall the engine makes is registered, and every registration is still used")
	public void testTheMetadataMatchesTheCode() throws IOException {
		Set<String> used = new TreeSet<>();
		for (String source : SOURCES) {
			Path path = Path.of(source);
			assertTrue(Files.isRegularFile(path), path + " has moved; this test lists the classes by hand");
			used.addAll(descriptorsIn(Files.readString(path)));
		}
		assertTrue(used.size() > 10, "found only " + used + "; the source scan is broken, not the metadata");

		Set<String> registered = registeredIn(Files.readString(METADATA));

		Set<String> missing = new TreeSet<>(used);
		missing.removeAll(registered);
		assertTrue(missing.isEmpty(), "not registered in " + METADATA + ", so a native image will fail with "
			+ "MissingForeignRegistrationError the first time one is called: " + missing);

		Set<String> stale = new TreeSet<>(registered);
		stale.removeAll(used);
		assertTrue(stale.isEmpty(), "registered in " + METADATA + " but no longer used by any of " + SOURCES
			+ "; remove the entry and its line from the comment block: " + stale);
	}

	/**
	 * Every {@code FunctionDescriptor.of(...)} and {@code FunctionDescriptor.ofVoid(...)} in a source file, rendered
	 * as {@code returnType(param,param)}.
	 *
	 * <p>
	 * Reading the source rather than the compiled handles is deliberate: the descriptors are private static state
	 * built inside {@code static} blocks that swallow their own failures, so there is nothing to reflect on. The scan
	 * matches the argument list by counting parentheses rather than by regex, because a regex that stops at the first
	 * {@code )} silently truncates a five-argument descriptor into a three-argument one and the test would pass while
	 * registering the wrong shape.
	 * </p>
	 */
	private static Set<String> descriptorsIn(String source) {
		Set<String> found = new LinkedHashSet<>();
		Matcher matcher = Pattern.compile("FunctionDescriptor\\.(of|ofVoid)\\(").matcher(source);
		while (matcher.find()) {
			boolean isVoid = "ofVoid".equals(matcher.group(1));
			String arguments = balancedArguments(source, matcher.end());
			List<String> layouts = new ArrayList<>();
			for (String argument : arguments.split(",")) {
				String trimmed = argument.trim().replace("ValueLayout.", "");
				if (trimmed.isEmpty()) {
					continue;
				}
				String layout = LAYOUTS.get(trimmed);
				assertTrue(layout != null, "unknown layout '" + trimmed + "'; add it to LAYOUTS");
				layouts.add(layout);
			}
			String returnType = isVoid ? "void" : layouts.remove(0);
			found.add(returnType + "(" + String.join(",", layouts) + ")");
		}
		return found;
	}

	/** The text between an already-opened parenthesis and its match. */
	private static String balancedArguments(String source, int afterOpeningParen) {
		int depth = 1;
		for (int i = afterOpeningParen; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return source.substring(afterOpeningParen, i);
			}
		}
		throw new IllegalStateException("unbalanced parentheses after offset " + afterOpeningParen);
	}

	/**
	 * The {@code foreign.downcalls} entries, in the same rendering.
	 *
	 * <p>
	 * Hand-parsed because graph-core has no JSON dependency and is not getting one for a test. The file is written by
	 * this project and checked by native-image on every native build, so the parser only has to handle the shape that
	 * is actually there — and a malformed file fails the build long before it reaches here.
	 * </p>
	 */
	private static Set<String> registeredIn(String json) {
		Set<String> found = new TreeSet<>();
		Matcher entry = Pattern.compile(
			"\\{\\s*\"returnType\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"parameterTypes\"\\s*:\\s*\\[([^\\]]*)\\]\\s*\\}")
			.matcher(json);
		while (entry.find()) {
			List<String> parameters = new ArrayList<>();
			Matcher parameter = Pattern.compile("\"([^\"]+)\"").matcher(entry.group(2));
			while (parameter.find()) {
				parameters.add(parameter.group(1));
			}
			found.add(entry.group(1) + "(" + String.join(",", parameters) + ")");
		}
		assertTrue(!found.isEmpty(), "no downcall entries parsed out of " + METADATA);
		return found;
	}
}
