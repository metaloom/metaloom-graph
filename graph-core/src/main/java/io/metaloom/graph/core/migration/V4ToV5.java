package io.metaloom.graph.core.migration;

import java.io.IOException;

/**
 * Version 4 to 5: the property value codec gains {@code T_TEMPORAL} (tag 12), Cypher's dates, times, date-times and
 * durations.
 *
 * <p>
 * A stamp, with no data to rewrite. A version 4 store cannot hold tag 12 — the version 4 codec refused every tag above
 * 11 — so every byte of it already means the same under version 5. The bump exists for the other direction: a store
 * that may hold a temporal value must be refused by a build whose codec would throw on it in the middle of a read,
 * and the version is what refuses it up front. The runner stamps 5 after this step, which also ends the legacy
 * encoding of the version field (a store without vectors was written as 3): spec/MIGRATION.md section 2.2.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/MIGRATION.md">spec/MIGRATION.md</a> section 6
 */
final class V4ToV5 implements FormatMigration {

	@Override
	public int from() {
		return 4;
	}

	@Override
	public String description() {
		return "adds the temporal property value type; no data is rewritten, only the version is stamped";
	}

	@Override
	public void migrate(MigrationContext context) throws IOException {
		context.progress("nothing to rewrite: a version 4 store holds no temporal values");
	}
}
