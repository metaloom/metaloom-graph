package io.metaloom.graph.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.ConstraintViolationException;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.core.store.prop.StoredDuration;

/**
 * Temporal values through the property index: the key and the verification compare content, a date-time by the
 * instant it denotes whatever its zone, a time with an offset by its UTC time of day, and a duration by its four
 * components whatever {@code TemporalAmount} class carried it.
 */
@Tag("unit")
public class TemporalIndexTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private static final ZonedDateTime VIENNA = ZonedDateTime.of(2024, 10, 27, 2, 30, 0, 500, ZoneId.of("Europe/Vienna"))
		.withLaterOffsetAtOverlap();

	@Test
	public void testEqualityAndHashAgree() {
		assertSame(VIENNA, VIENNA.withZoneSameInstant(ZoneOffset.UTC));
		assertSame(VIENNA, VIENNA.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.ofHours(-7)));
		assertSame(OffsetTime.of(12, 0, 0, 0, ZoneOffset.ofHours(1)), OffsetTime.of(11, 0, 0, 0, ZoneOffset.UTC));
		assertSame(new StoredDuration(14, 3, 0, 0), Period.of(1, 2, 3));
		assertSame(new StoredDuration(0, 0, -1, 500_000_000), Duration.ofMillis(-500));
		assertSame(List.of(LocalDate.EPOCH, Period.ofMonths(12)), List.of(LocalDate.EPOCH, new StoredDuration(12, 0, 0, 0)));

		assertFalse(IndexCatalog.valueEquals(VIENNA, VIENNA.toInstant()), "a zoned date-time is not an Instant");
		assertFalse(IndexCatalog.valueEquals(VIENNA, VIENNA.plusNanos(1)));
		assertFalse(IndexCatalog.valueEquals(Period.ofDays(1), Duration.ofDays(1)), "a day is not 86,400 seconds");
		assertFalse(IndexCatalog.valueEquals(Period.ofYears(1), Period.ofDays(365)));
		assertFalse(IndexCatalog.valueEquals(LocalDateTime.of(2024, 1, 1, 0, 0),
			ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)), "a local date-time denotes no instant");
		assertFalse(IndexCatalog.valueEquals(LocalTime.NOON, OffsetTime.of(LocalTime.NOON, ZoneOffset.UTC)));
	}

	private static void assertSame(Object a, Object b) {
		assertTrue(IndexCatalog.valueEquals(a, b), a + " equals " + b);
		assertTrue(IndexCatalog.valueEquals(b, a), b + " equals " + a);
		assertEquals(IndexCatalog.valueHash(a), IndexCatalog.valueHash(b), a + " hashes as " + b);
	}

	@Test
	public void testIndexedLookupsOfEveryTemporalKind() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createIndex("Event", "day");
			store.createIndex("Event", "at");
			store.createIndex("Event", "length");
			store.createIndex("Event", "clock");
			long launch;
			try (Transaction txn = store.begin()) {
				launch = store.createNode("Event", Properties.of()
					.set("day", LocalDate.of(2024, 2, 29))
					.set("at", VIENNA)
					.set("length", Duration.ofHours(1).plusMinutes(30))
					.set("clock", OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(2))));
				store.createNode("Event", Properties.of()
					.set("day", LocalDate.of(2024, 3, 1))
					.set("at", VIENNA.plusHours(1))
					.set("length", Period.of(0, 14, 3))
					.set("clock", OffsetTime.of(9, 0, 0, 0, ZoneOffset.UTC)));
				txn.commit();
			}
			List<Long> launchOnly = List.of(launch);
			assertEquals(launchOnly, store.nodesByLabelAndProperty("Event", "day", LocalDate.of(2024, 2, 29)));
			assertEquals(launchOnly, store.nodesByLabelAndProperty("Event", "at", VIENNA));
			assertEquals(launchOnly, store.nodesByLabelAndProperty("Event", "at",
				VIENNA.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.ofHours(9))), "same instant, other offset");
			assertEquals(launchOnly, store.nodesByLabelAndProperty("Event", "length", Duration.ofSeconds(5400)));
			assertEquals(launchOnly, store.nodesByLabelAndProperty("Event", "clock",
				OffsetTime.of(7, 0, 0, 0, ZoneOffset.UTC)), "09:00+02:00 is 07:00Z");
			assertEquals(1, store.nodesByLabelAndProperty("Event", "length", Period.of(1, 2, 3)).size(),
				"a Period probe finds the stored duration");
			assertEquals(List.of(), store.nodesByLabelAndProperty("Event", "at", VIENNA.toInstant()));
			assertEquals(List.of(), store.nodesByLabelAndProperty("Event", "length", Duration.ofHours(1)));

			// Without an index: the scan compares with the same equality.
			assertEquals(launchOnly, store.nodesByProperty("day", LocalDate.of(2024, 2, 29)));
			assertEquals(launchOnly, store.nodesByProperty("at", VIENNA.withZoneSameInstant(ZoneId.of("Asia/Tokyo"))));

			// Updating removes the old entry: the key the delete computes is the one the insert wrote.
			try (Transaction txn = store.begin()) {
				store.setNodeProperties(launch, Properties.of().set("day", LocalDate.of(2025, 1, 1)));
				txn.commit();
			}
			assertEquals(List.of(), store.nodesByLabelAndProperty("Event", "day", LocalDate.of(2024, 2, 29)));
			assertEquals(launchOnly, store.nodesByLabelAndProperty("Event", "day", LocalDate.of(2025, 1, 1)));
		}
	}

	@Test
	public void testAUniqueConstraintSeesTheSameInstantInAnotherZone() throws Exception {
		try (GraphStore store = stores.open()) {
			store.createUniqueConstraint("Slot", "starts");
			try (Transaction txn = store.begin()) {
				store.createNode("Slot", Properties.of().set("starts", VIENNA));
				txn.commit();
			}
			assertThrows(ConstraintViolationException.class, () -> {
				try (Transaction txn = store.begin()) {
					store.createNode("Slot", Properties.of().set("starts", VIENNA.toOffsetDateTime()
						.withOffsetSameInstant(ZoneOffset.UTC)));
					txn.commit();
				}
			});
			try (Transaction txn = store.begin()) {
				store.createNode("Slot", Properties.of().set("starts", Instant.from(VIENNA)));
				txn.commit();
			}
		}
	}
}
