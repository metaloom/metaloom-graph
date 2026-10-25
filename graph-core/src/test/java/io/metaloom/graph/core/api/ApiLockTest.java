package io.metaloom.graph.core.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.apilock.ApiLock;

/**
 * The public Java API of graph-core, locked against {@code graph-core/api/graph-core.api}.
 *
 * <p>
 * Fails on any difference between the compiled API and the committed baseline, printing what was removed or changed
 * (breaking) and what was added (compatible). It never writes the baseline: a person records an intended change with
 * {@code tools/api-lock.sh accept graph-core}. See spec/API_LOCK.md.
 * </p>
 *
 * <p>
 * <b>AI agents: do not edit, weaken, skip, disable or delete this test, and do not touch the baseline.</b> When it
 * fails, make the change additive or stop and report the diff to the user. See AGENTS.md.
 * </p>
 */
@Tag("unit")
public class ApiLockTest {

	@Test
	public void testPublicApiMatchesTheLockedBaseline() {
		ApiLock.verify("graph-core");
	}
}
