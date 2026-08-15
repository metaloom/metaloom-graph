package io.metaloom.graph.server.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.server.demo.DemoData.Summary;

/**
 * The demo generator.
 *
 * <p>
 * The assertions are about the two things that would make the demo useless without failing anything: that the graph is
 * <b>connected the way it claims to be</b> — every airport in a city, every city in a country, every route between two
 * airports — and that it is <b>the same graph every time</b>, which is what lets the browser suite name Heathrow and
 * expect an answer.
 * </p>
 */
@Tag("unit")
public class DemoDataTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testTheSmallGraphIsWhatItSaysItIs() throws Exception {
		GraphStore store = stores.open();
		Summary summary = DemoData.seed(store, DemoData.SMALL);

		assertEquals(DemoData.SMALL, summary.airports());
		assertEquals(58, store.nodesByLabel("Airport").size());
		// Three of the real cities have two airports — London, Paris and Tokyo — which is why cities are fewer.
		assertEquals(55, summary.cities());
		assertEquals(summary.nodes(), store.stats().nodeCount());

		// The invariant checker walks every record, index entry and adjacency block. A generator that produced a
		// broken store would otherwise only show up as a strange query result.
		store.verify(VerifyLevel.FULL);
	}

	@Test
	public void testEveryAirportIsPlaced() throws Exception {
		GraphStore store = stores.open();
		DemoData.seed(store, DemoData.SMALL);

		for (long airport : store.nodesByLabel("Airport")) {
			List<Edge> cities = store.neighbours(airport, Direction.OUT, Set.of("IN_CITY"));
			assertEquals(1, cities.size(), "an airport belongs to exactly one city");
			// IN_CITY carries nothing, so it must be a lightweight edge: no relationship record, no id.
			assertTrue(!cities.get(0).isMaterialised(), "IN_CITY should cost 16 bytes, not a record");

			long city = cities.get(0).neighbourId();
			assertEquals(1, store.neighbours(city, Direction.OUT, Set.of("IN_COUNTRY")).size(),
				"a city belongs to exactly one country");
		}
	}

	@Test
	public void testRoutesJoinAirportsAndCarryTheirDistance() throws Exception {
		GraphStore store = stores.open();
		DemoData.seed(store, DemoData.SMALL);

		int routes = 0;
		for (long airport : store.nodesByLabel("Airport")) {
			for (Edge edge : store.neighbours(airport, Direction.OUT, Set.of("ROUTE_TO"))) {
				routes++;
				assertTrue(store.nodeHasLabel(edge.neighbourId(), "Airport"), "a route ends at an airport");
				assertTrue(edge.isMaterialised(), "a route carries properties, so it has a record");
				Properties properties = store.relationshipProperties(edge.relationshipId());
				assertTrue(properties.getDouble("distanceKm") >= 0);
				assertTrue(properties.getLong("durationMinutes") > 0);
				assertTrue(properties.getString("flightNumber").startsWith(properties.getString("airline")));
			}
		}
		assertTrue(routes > 300, "the small graph should be worth looking at, had " + routes);
	}

	/**
	 * The distances are computed from real coordinates, so they can be checked against the world.
	 * <p>
	 * London to Paris is about 350 km and London to Sydney about 17,000. If the haversine or the coordinate table were
	 * wrong, the demo would still look plausible on screen and be nonsense in every query that ordered by distance.
	 */
	@Test
	public void testDistancesAreRoughlyTrue() throws Exception {
		GraphStore store = stores.open();
		DemoData.seed(store, DemoData.SMALL);

		assertTrue(Math.abs(distanceBetween(store, "LHR", "CDG") - 350) < 40,
			"London to Paris, got " + distanceBetween(store, "LHR", "CDG"));
		assertTrue(Math.abs(distanceBetween(store, "LHR", "SYD") - 17_000) < 500,
			"London to Sydney, got " + distanceBetween(store, "LHR", "SYD"));
		assertTrue(distanceBetween(store, "LHR", "LGW") < 60, "the two London airports are close together");
	}

	private static double distanceBetween(GraphStore store, String from, String to) {
		long a = airportByCode(store, from);
		long b = airportByCode(store, to);
		for (Edge edge : store.neighbours(a, Direction.OUT, Set.of("ROUTE_TO"))) {
			if (edge.neighbourId() == b) {
				return store.relationshipProperties(edge.relationshipId()).getDouble("distanceKm");
			}
		}
		// Not every pair is flown; fall back to the coordinates, which is what the route would have been built from.
		double lat1 = store.nodeProperties(a).getDouble("latitude");
		double lon1 = store.nodeProperties(a).getDouble("longitude");
		double lat2 = store.nodeProperties(b).getDouble("latitude");
		double lon2 = store.nodeProperties(b).getDouble("longitude");
		return DemoData.distanceKm(
			new DemoData.Place(from, from, from, from, "XX", "Europe", lat1, lon1),
			new DemoData.Place(to, to, to, to, "XX", "Europe", lat2, lon2));
	}

	private static long airportByCode(GraphStore store, String code) {
		return store.nodesByProperty("code", code).stream()
			.filter(id -> store.nodeHasLabel(id, "Airport"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no airport " + code));
	}

	@Test
	public void testTheAwkwardShapesAreAllPresent() throws Exception {
		// Each of these exists in the demo because the UI or the engine has a case for it, and a demo that does not
		// contain them leaves those cases unexercised by anything a person actually looks at.
		GraphStore store = stores.open();
		DemoData.seed(store, DemoData.SMALL);

		long hub = store.nodesByLabel("Hub").get(0);
		assertEquals(List.of("Airport", "Hub"), store.nodeLabels(hub), "a hub carries two labels");

		assertEquals(1, store.nodesByLabel("Cargo Carrier").size(), "a label with a space in it");

		long selfLoops = store.nodesByLabel("Airport").stream()
			.flatMap(airport -> store.neighbours(airport, Direction.OUT, Set.of("ROUTE_TO")).stream()
				.filter(edge -> edge.neighbourId() == airport))
			.count();
		assertEquals(1, selfLoops, "the sightseeing flight");

		boolean parallel = store.nodesByLabel("Airport").stream().anyMatch(airport -> {
			Set<Long> seen = new HashSet<>();
			return store.neighbours(airport, Direction.OUT, Set.of("ROUTE_TO")).stream()
				.anyMatch(edge -> !seen.add(edge.neighbourId()));
		});
		assertTrue(parallel, "some pair is flown by two airlines");
	}

	@Test
	public void testEveryRouteHasAReturn() throws Exception {
		// A route you can only fly one way is not what anyone means by a route, and half the intuitive queries would
		// come back empty.
		GraphStore store = stores.open();
		DemoData.seed(store, DemoData.TINY);

		for (long airport : store.nodesByLabel("Airport")) {
			for (Edge out : store.neighbours(airport, Direction.OUT, Set.of("ROUTE_TO"))) {
				boolean back = store.neighbours(out.neighbourId(), Direction.OUT, Set.of("ROUTE_TO")).stream()
					.anyMatch(edge -> edge.neighbourId() == airport);
				assertTrue(back, "no return leg for " + store.nodeProperty(airport, "code")
					+ " to " + store.nodeProperty(out.neighbourId(), "code"));
			}
		}
	}

	@Test
	public void testTheSameSizeAlwaysProducesTheSameGraph(@TempDir Path elsewhere) throws Exception {
		GraphStore first = stores.open();
		Summary one = DemoData.seed(first, DemoData.TINY);
		Summary two;
		try (GraphStore second = GraphStore.open(elsewhere, TempStore.options())) {
			two = DemoData.seed(second, DemoData.TINY);
			assertEquals(first.stats().nodeCount(), second.stats().nodeCount());
			assertEquals(first.stats().relationshipCount(), second.stats().relationshipCount());
			// Not just the counts: the same airport must have the same generated properties both times.
			assertEquals(first.nodeProperties(airportByCode(first, "LHR")).asMap(),
				second.nodeProperties(airportByCode(second, "LHR")).asMap());
		}

		assertEquals(one.airports(), two.airports());
		assertEquals(one.cities(), two.cities());
		assertEquals(one.countries(), two.countries());
		assertEquals(one.routes(), two.routes());
	}

	@Test
	public void testASyntheticGraphIsObviouslyFictional() throws Exception {
		// Past the real airports the generator invents them, and it must not invent facts about real places: a made-up
		// city in a real country would be wrong data about somewhere that exists.
		GraphStore store = stores.open();
		DemoData.seed(store, DemoData.SMALL + 20);

		assertEquals(DemoData.SMALL + 20, store.nodesByLabel("Airport").size());
		long synthetic = store.nodesByLabel("Country").stream()
			.filter(id -> store.nodeProperties(id).getString("iso").startsWith("X"))
			.count();
		assertTrue(synthetic > 0, "the invented countries use the ISO user-assigned range");

		Set<String> codes = new HashSet<>();
		for (long airport : store.nodesByLabel("Airport")) {
			assertTrue(codes.add(store.nodeProperties(airport).getString("code")), "airport codes must be unique");
		}
	}

	@Test
	public void testSizesAreNamedOrCounted() {
		assertEquals(DemoData.TINY, DemoData.airportsFor("tiny"));
		assertEquals(DemoData.LARGE, DemoData.airportsFor("LARGE"));
		assertEquals(250, DemoData.airportsFor("250"));

		assertThrows(IllegalArgumentException.class, () -> DemoData.airportsFor("enormous"));
		assertThrows(IllegalArgumentException.class, () -> DemoData.airportsFor("1"));
		assertNotEquals(DemoData.TINY, DemoData.SMALL);
	}

	@Test
	public void testAnEmptyStoreIsTheOnlyOneWorthSeeding() throws Exception {
		GraphStore store = stores.open();
		assertTrue(DemoData.isEmpty(store));
		DemoData.seed(store, DemoData.TINY);
		assertTrue(!DemoData.isEmpty(store), "the server uses this to refuse to seed twice");
	}
}
