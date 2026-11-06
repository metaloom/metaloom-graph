package io.metaloom.graph.core.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.StoreFormat;
import io.metaloom.graph.core.api.StoreMigration;
import io.metaloom.graph.core.api.VerifyLevel;

/**
 * Every committed golden fixture, of every version, migrated to the current version by the migration this build
 * ships, and then read back as exactly the content that fixture was generated with.
 *
 * <p>
 * The v4 fixture goes through {@code V4ToV5} on every build, and through every later step as well once there are
 * more; the current version's fixture is the identity — the plan is empty and the store is left alone. The day
 * {@link StoreFormat#CURRENT_VERSION} moves again, this fails until a step is registered that takes every older
 * fixture there with its content intact.
 * </p>
 */
@Tag("unit")
public class GoldenFixtureMigrationTest {

	@TempDir
	Path tmp;

	@Test
	public void testEveryFixtureMigratesToTheCurrentVersionWithItsContent() throws IOException {
		List<GoldenFixtures.Fixture> fixtures = GoldenFixtures.all();
		assertFalse(fixtures.isEmpty(), "no golden fixtures on the classpath");
		for (GoldenFixtures.Fixture fixture : fixtures) {
			Path root = tmp.resolve("v" + fixture.version());
			Path store = fixture.unpack(root);
			StoreMigration migration = StoreMigration.standard();

			StoreMigration.Plan plan = migration.plan(store);
			assertTrue(plan.canProceed(), "v" + fixture.version() + ": " + plan.blockers());
			assertEquals(StoreFormat.CURRENT_VERSION - fixture.version(), plan.steps().size(),
				"v" + fixture.version() + " needs one step per version");

			StoreMigration.Result result = migration.migrate(store);
			assertEquals(fixture.version() != StoreFormat.CURRENT_VERSION, result.changed());
			StoreFormat.Info info = StoreFormat.inspect(store);
			assertEquals(StoreFormat.CURRENT_VERSION, info.version());
			try (GraphStore graph = GraphStore.open(store, info.options())) {
				assertEquals(fixture.expected(), GoldenContent.canonical(graph),
					"v" + fixture.version() + " read back differently after migrating");
				graph.verify(VerifyLevel.FULL);
			}
			if (result.changed()) {
				assertTrue(Files.isDirectory(result.backupDirectory()), "the original is kept");
				assertEquals(fixture.version(), StoreFormat.inspect(result.backupDirectory()).version());
			}
			assertEquals(fixture.expectedAuth(), GoldenContent.canonicalAuth(root),
				"the identity file is outside the store and no store migration touches it");
		}
	}
}
