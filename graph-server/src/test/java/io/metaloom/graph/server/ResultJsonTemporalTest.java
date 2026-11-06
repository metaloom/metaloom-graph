package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.cypher.value.CypherDuration;
import io.metaloom.graph.cypher.value.CypherNode;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Temporal values on the HTTP and MCP routes: their ISO-8601 strings, as Neo4j's HTTP API sends them.
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md §8</a>
 */
@Tag("unit")
public class ResultJsonTemporalTest {

	@Test
	public void testEveryKindIsItsIsoString() {
		assertEquals("2024-02-29", ResultJson.value(LocalDate.of(2024, 2, 29)));
		assertEquals("10:15:30.123", ResultJson.value(LocalTime.of(10, 15, 30, 123_000_000)));
		assertEquals("10:00+01:00", ResultJson.value(OffsetTime.of(10, 0, 0, 0, ZoneOffset.ofHours(1))));
		assertEquals("2020-01-01T10:00", ResultJson.value(LocalDateTime.of(2020, 1, 1, 10, 0)));
		assertEquals("2020-01-01T10:00+01:00",
			ResultJson.value(ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1))));
		assertEquals("2020-01-01T10:00Z", ResultJson.value(ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC)));
		assertEquals("2020-01-01T10:00+01:00[Europe/Vienna]",
			ResultJson.value(ZonedDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneId.of("Europe/Vienna"))));
		assertEquals("P1Y2M3DT4H5M6.5S", ResultJson.value(new CypherDuration(14, 3, 14_706, 500_000_000)));
	}

	@Test
	public void testAnyTemporalAmountIsRenderedAsACypherDuration() {
		// Period's own toString would do here, Duration's would print PT-1.5S too; a mixed amount from the store has
		// no toString worth showing, so every amount goes through CypherDuration's.
		assertEquals("P1Y2M3D", ResultJson.value(Period.of(1, 2, 3)));
		assertEquals("PT-1.5S", ResultJson.value(Duration.ofMillis(-1_500)));
		assertEquals("PT26H", ResultJson.value(Duration.ofHours(26)), "seconds never fold into days");
	}

	@Test
	public void testTemporalValuesInsideContainersAndProperties() {
		JsonArray list = (JsonArray) ResultJson.value(List.of(LocalDate.of(2020, 1, 1), new CypherDuration(0, 1, 0, 0)));
		assertEquals(new JsonArray().add("2020-01-01").add("P1D"), list);

		JsonObject node = (JsonObject) ResultJson.value(new CypherNode(1L, List.of("Event"),
			Map.of("at", LocalDateTime.of(2020, 1, 1, 10, 0))));
		assertEquals("2020-01-01T10:00", node.getJsonObject("properties").getString("at"));
		// And it encodes: Vert.x refuses arbitrary Java objects, so a raw java.time value would fail here.
		node.encode();
	}
}
