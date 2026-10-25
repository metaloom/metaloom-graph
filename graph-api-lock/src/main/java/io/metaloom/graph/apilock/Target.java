package io.metaloom.graph.apilock;

import java.nio.file.Path;

/**
 * One module as the check and the accept tool see it: its name, its scope and where its baseline lives. Separate from
 * {@link LockedModule} so the tool's own tests can point both at fixture classes and a temporary baseline.
 *
 * @param artifactId
 *            the module's artifactId, recorded in the baseline header
 * @param scope
 *            what to dump
 * @param file
 *            the baseline file
 */
record Target(String artifactId, ApiScope scope, Path file) {
}
