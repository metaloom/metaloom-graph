package io.metaloom.graph.apilock;

import static io.metaloom.graph.apilock.ApiScope.Root.moduleExports;
import static io.metaloom.graph.apilock.ApiScope.Root.pkg;
import static io.metaloom.graph.apilock.ApiScope.Root.type;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The modules whose public Java API is locked, and what their API is.
 *
 * <p>
 * This is the one place the scope is defined. {@code ApiLockTest} in each module and the accept tool both read it, and
 * the resolved scope is part of every dump, so the two cannot disagree and narrowing a scope here fails the lock like
 * any other API change. graph-server and graph-cli are applications and are not locked: their contract is their
 * command line, their HTTP routes and the wire protocol, each covered by its own tests. See spec/API_LOCK.md section 2.
 * </p>
 *
 * <p>
 * Classes are named by string on purpose. This module depends on none of the modules it locks — graph-protocol and
 * graph-client must never see graph-core, not even in test scope — so the anchors are loaded from the test classpath
 * of whichever module is being checked.
 * </p>
 */
public enum LockedModule {

	/** Exactly the packages {@code module-info.java} exports, read from the compiled descriptor. */
	GRAPH_CORE("graph-core", ApiScope.of("io.metaloom.graph.core.api.GraphStore", moduleExports())),

	/**
	 * The top-level package callers use ({@code Cypher}, its results and its exceptions), the value types a row hands
	 * back, and the few types out of the internal packages that the top-level signatures and the server need: the
	 * parsed query a caller may hold on to, the CSV policy an embedder plugs in, and the exceptions a caller catches.
	 * The rest of {@code ast}, {@code exec}, {@code parse}, {@code func} and {@code io} is implementation.
	 */
	GRAPH_CYPHER("graph-cypher", ApiScope.of("io.metaloom.graph.cypher.Cypher",
		pkg("io.metaloom.graph.cypher"),
		pkg("io.metaloom.graph.cypher.value"),
		type("io.metaloom.graph.cypher.ast.Ast$Query"),
		type("io.metaloom.graph.cypher.io.CsvSource"),
		type("io.metaloom.graph.cypher.io.CsvAccessException"),
		type("io.metaloom.graph.cypher.io.CsvFormatException"),
		type("io.metaloom.graph.cypher.parse.CypherSyntaxException"),
		type("io.metaloom.graph.cypher.exec.CypherTypeException"))),

	/** The whole package: frames, the value codec and the wire records are shared by the server and the client. */
	GRAPH_PROTOCOL("graph-protocol", ApiScope.of("io.metaloom.graph.protocol.Protocol", pkg("io.metaloom.graph.protocol"))),

	/** The whole package: the client and its options, rows and exceptions. */
	GRAPH_CLIENT("graph-client", ApiScope.of("io.metaloom.graph.client.GraphClient", pkg("io.metaloom.graph.client")));

	private final String artifactId;
	private final ApiScope scope;

	LockedModule(String artifactId, ApiScope scope) {
		this.artifactId = artifactId;
		this.scope = scope;
	}

	/** The Maven artifactId, which is also the module's directory and its baseline's file name. */
	public String artifactId() {
		return artifactId;
	}

	public ApiScope scope() {
		return scope;
	}

	/** The baseline's path relative to the module directory. */
	public String baselineFile() {
		return "api/" + artifactId + ".api";
	}

	/** What the check and the tool act on for this module, in a module directory. */
	Target target(Path moduleDir) {
		return new Target(artifactId, scope, moduleDir.resolve(baselineFile()));
	}

	public static LockedModule forArtifactId(String artifactId) {
		for (LockedModule module : values()) {
			if (module.artifactId.equals(artifactId)) {
				return module;
			}
		}
		throw new IllegalArgumentException("Not a locked module: " + artifactId + ". Locked modules are "
			+ Arrays.stream(values()).map(LockedModule::artifactId).collect(Collectors.joining(", ")));
	}
}
