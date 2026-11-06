package io.metaloom.graph.core.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.FormatVersionException;
import io.metaloom.graph.core.api.MigrationException;
import io.metaloom.graph.core.api.StoreFormat;

/** Plan computation: the chain, its gaps, and the mistakes a build can make when registering steps. */
@Tag("unit")
public class MigrationRegistryTest {

	private static FormatMigration step(int from) {
		return step(from, from + 1);
	}

	private static FormatMigration step(int from, int to) {
		return new FormatMigration() {

			@Override
			public int from() {
				return from;
			}

			@Override
			public int to() {
				return to;
			}

			@Override
			public String description() {
				return from + " to " + to;
			}

			@Override
			public void migrate(MigrationContext context) {
			}
		};
	}

	@Test
	public void testThePathIsTheChainInOrder() {
		MigrationRegistry registry = new MigrationRegistry(4, List.of(step(3), step(1), step(2)));
		assertEquals(List.of(1, 2, 3), registry.path(1).stream().map(FormatMigration::from).toList());
		assertEquals(List.of(3), registry.path(3).stream().map(FormatMigration::from).toList());
		assertTrue(registry.path(4).isEmpty(), "a store at the target needs no step");
		assertEquals(1, registry.oldestMigratable());
		assertTrue(registry.hasPath(1));
		assertTrue(registry.hasPath(4));
	}

	@Test
	public void testAGapIsNamed() {
		MigrationRegistry registry = new MigrationRegistry(5, List.of(step(1), step(3), step(4)));
		MigrationException gap = assertThrows(MigrationException.class, () -> registry.path(1));
		assertTrue(gap.getMessage().contains("from format version 2 (on the way from 1)"), gap.getMessage());
		assertTrue(gap.getMessage().contains("from version 3 onwards"), gap.getMessage());
		assertFalse(registry.hasPath(1));
		assertEquals(3, registry.oldestMigratable());
		assertEquals(2, registry.path(3).size());
	}

	@Test
	public void testAVersionBelowTheChainHasNoPath() {
		MigrationRegistry registry = new MigrationRegistry(3, List.of(step(2)));
		assertThrows(MigrationException.class, () -> registry.path(1));
		assertFalse(registry.hasPath(1));
	}

	@Test
	public void testANewerVersionIsRefused() {
		MigrationRegistry registry = new MigrationRegistry(3, List.of(step(2)));
		assertThrows(FormatVersionException.class, () -> registry.path(4));
		assertFalse(registry.hasPath(4));
	}

	@Test
	public void testRegistrationMistakesFailAtConstruction() {
		assertThrows(IllegalArgumentException.class, () -> new MigrationRegistry(5, List.of(step(1, 3))),
			"a step that skips a version");
		assertThrows(IllegalArgumentException.class, () -> new MigrationRegistry(5, List.of(step(2), step(2))),
			"two steps from one version");
		assertThrows(IllegalArgumentException.class, () -> new MigrationRegistry(3, List.of(step(3))),
			"a step past the target");
	}

	/**
	 * The shipped registry ends at the current version and starts at 4, the first version with a migration process.
	 * When a step is added this starts failing on the oldest version or the count, which is the reminder to check
	 * spec/MIGRATION.md section 6 was followed.
	 */
	@Test
	public void testTheStandardRegistryEndsAtTheCurrentVersion() {
		MigrationRegistry standard = MigrationRegistry.standard();
		assertEquals(StoreFormat.CURRENT_VERSION, standard.target());
		assertEquals(4, standard.oldestMigratable());
		assertTrue(standard.path(StoreFormat.CURRENT_VERSION).isEmpty());
		assertEquals(StoreFormat.CURRENT_VERSION - 4, standard.path(4).size());
		assertTrue(standard.hasPath(4));
		assertFalse(standard.hasPath(3), "versions before 4 have no step and never will");
	}
}
