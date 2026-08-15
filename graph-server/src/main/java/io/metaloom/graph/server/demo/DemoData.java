package io.metaloom.graph.server.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;

/**
 * A demo graph: an air route network.
 *
 * <h2>Why this domain</h2>
 *
 * <p>
 * A graph is only interesting to look at if the reader already knows what the edges mean, and air routes need no
 * explanation: airports sit in cities, cities in countries, airlines have hubs, and a route goes from one airport to
 * another and carries a distance and a duration. Every question a graph database is good at has an obvious form here —
 * <em>where can I fly from Lisbon</em>, <em>how do I get from Auckland to Helsinki in two hops</em>, <em>which hub has
 * the most routes</em> — so the demo teaches the query language rather than the dataset.
 * </p>
 *
 * <p>
 * It also contains, without being contrived, every shape the storage engine and the browser have to handle:
 * </p>
 *
 * <ul>
 * <li><b>Several labels on one node.</b> A hub is {@code :Airport:Hub}, which is how the record's inline label slots
 * get used at all.</li>
 * <li><b>A label that is not an identifier.</b> A freight airline is {@code :Airline:`Cargo Carrier`}, so anything
 * turning a label into a query has to quote it.</li>
 * <li><b>Lightweight edges.</b> {@code IN_CITY} and {@code IN_COUNTRY} carry no properties, so they are stored in the
 * adjacency list with no relationship record — 16 bytes, no id.</li>
 * <li><b>Parallel relationships.</b> Two airlines fly the same pair, which is two edges between the same nodes and
 * must be drawn as two.</li>
 * <li><b>A self-relationship.</b> A sightseeing flight returns to the airport it left from.</li>
 * <li><b>Supernodes.</b> A hub has an order of magnitude more edges than a spoke, which is the degree distribution the
 * adjacency tiers exist for.</li>
 * </ul>
 *
 * <h2>What is real and what is not</h2>
 *
 * <p>
 * <b>The airport names, codes, cities, countries and coordinates are real</b> — coordinates rounded to a tenth of a
 * degree, which is a few kilometres and enough for distances to come out about right. <b>Everything else is
 * generated</b>: the airlines are invented, and so is every route, flight number, frequency and hub assignment. This
 * is demo data. It is not an aeronautical database and no route in it should be taken as one.
 * </p>
 *
 * <p>
 * Past the {@value #SMALL} real airports, further ones are synthesised with invented names in invented countries, so a
 * large demo store stays obviously fictional rather than inventing plausible-looking facts about real places. Their
 * country codes come from the ISO 3166-1 user-assigned range ({@code XA} to {@code XZ}), which exists for exactly
 * this.
 * </p>
 *
 * <h2>Determinism</h2>
 *
 * <p>
 * The generator is seeded, so a given size always produces the same graph, byte for byte. That is what lets the
 * browser tests assert on counts and on named nodes rather than on shapes.
 * </p>
 */
public final class DemoData {

	private static final Logger logger = LoggerFactory.getLogger(DemoData.class);

	/** Small enough to see whole on one screen. */
	public static final int TINY = 12;
	/** Every real airport in the table below, and the default. */
	public static final int SMALL = 58;
	public static final int MEDIUM = 400;
	public static final int LARGE = 3000;

	/** Fixed, because a demo that differs between two runs cannot be asserted on or reported. */
	private static final long SEED = 20260815L;

	/** Elements per transaction. Durability costs an fsync per commit, so a large seed must not commit per node. */
	private static final int BATCH = 4000;

	/** Every hub pair is joined, so this bounds the one quadratic term in the generator. */
	private static final int MAX_HUBS = 60;

	private DemoData() {
	}

	/**
	 * A size name or a plain number of airports.
	 *
	 * @throws IllegalArgumentException
	 *             for anything else, naming what is accepted
	 */
	public static int airportsFor(String size) {
		return switch (size.toLowerCase(Locale.ROOT)) {
			case "tiny" -> TINY;
			case "small" -> SMALL;
			case "medium" -> MEDIUM;
			case "large" -> LARGE;
			default -> {
				int airports;
				try {
					airports = Integer.parseInt(size);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException(
						"Demo size must be tiny, small, medium, large or a number of airports, was '" + size + "'");
				}
				if (airports < 2) {
					throw new IllegalArgumentException("A demo graph needs at least 2 airports, was " + airports);
				}
				yield airports;
			}
		};
	}

	/** What was written. */
	public record Summary(int airports, int cities, int countries, int airlines, int routes, long millis) {

		public int nodes() {
			return airports + cities + countries + airlines;
		}

		@Override
		public String toString() {
			return "%,d airports in %,d cities in %,d countries, %,d airlines, %,d routes (%,d ms)"
				.formatted(airports, cities, countries, airlines, routes, millis);
		}
	}

	/**
	 * Writes the demo graph into a store.
	 *
	 * <p>
	 * The caller decides whether this is allowed to happen; {@link #isEmpty(GraphStore)} is the check the server makes.
	 * Seeding on top of existing data would work — nothing here deletes anything — but it would silently mix invented
	 * routes into somebody's real graph, so nothing in this project ever calls it that way.
	 * </p>
	 *
	 * @param airports
	 *            how many airports; everything else scales from it
	 */
	public static Summary seed(GraphStore store, int airports) throws IOException {
		if (airports < 2) {
			throw new IllegalArgumentException("A demo graph needs at least 2 airports, was " + airports);
		}
		long started = System.nanoTime();
		List<Place> places = places(airports);
		Random random = new Random(SEED);

		Map<String, Long> countries = new LinkedHashMap<>();
		Map<String, Long> cities = new LinkedHashMap<>();
		long[] airportIds = new long[places.size()];
		int routes = 0;

		List<Integer> hubs = chooseHubs(places);
		boolean[] isHub = new boolean[places.size()];
		hubs.forEach(index -> isHub[index] = true);

		List<Airline> airlines = airlines(places.size());
		long[] airlineIds = new long[airlines.size()];

		try (Batch batch = new Batch(store)) {
			for (Airline airline : airlines) {
				// A freight operator carries a second label with a space in it, so that anything turning a label back
				// into a query has to quote it.
				List<String> labels = airline.cargo()
					? List.of("Airline", "Cargo Carrier")
					: List.of("Airline");
				airlineIds[airlines.indexOf(airline)] = store.createNode(labels, Properties.of()
					.set("name", airline.name())
					.set("code", airline.code())
					.set("founded", (long) airline.founded())
					.set("fleetSize", (long) airline.fleet()));
				batch.step();
			}

			for (int i = 0; i < places.size(); i++) {
				Place place = places.get(i);

				Long country = countries.get(place.iso());
				if (country == null) {
					country = store.createNode(List.of("Country"), Properties.of()
						.set("name", place.country())
						.set("iso", place.iso())
						.set("continent", place.continent()));
					countries.put(place.iso(), country);
					batch.step();
				}

				String cityKey = place.city() + "|" + place.iso();
				Long city = cities.get(cityKey);
				if (city == null) {
					city = store.createNode(List.of("City"), Properties.of().set("name", place.city()));
					cities.put(cityKey, city);
					// No properties, so no relationship record: this is a lightweight edge.
					store.createEdge(city, "IN_COUNTRY", country);
					batch.step();
				}

				List<String> labels = isHub[i] ? List.of("Airport", "Hub") : List.of("Airport");
				airportIds[i] = store.createNode(labels, Properties.of()
					.set("code", place.code())
					.set("name", place.name())
					.set("latitude", place.latitude())
					.set("longitude", place.longitude())
					.set("runways", (long) (1 + (isHub[i] ? 3 : random.nextInt(2))))
					.set("international", true));
				store.createEdge(airportIds[i], "IN_CITY", city);
				batch.step();
			}

			// Each airline is based at one or two hubs. HUB_AT carries a year, so unlike IN_CITY it is materialised
			// and has an id — the two kinds of edge side by side in one graph.
			for (int a = 0; a < airlines.size(); a++) {
				int count = 1 + random.nextInt(2);
				for (int h = 0; h < count && h < hubs.size(); h++) {
					int hub = hubs.get((a * 2 + h) % hubs.size());
					store.createRelationship(airlineIds[a], "HUB_AT", airportIds[hub],
						Properties.of().set("since", (long) (1975 + random.nextInt(45))));
					batch.step();
				}
			}

			for (Pair pair : pairs(places, hubs, isHub, random)) {
				Airline airline = airlines.get(random.nextInt(airlines.size()));
				double km = distanceKm(places.get(pair.from()), places.get(pair.to()));
				routes += route(store, batch, airportIds, pair, airline, km, random);

				// About one pair in eight is flown by a second airline, which is two edges between the same two nodes.
				if (random.nextInt(8) == 0) {
					Airline other = airlines.get(random.nextInt(airlines.size()));
					if (!other.code().equals(airline.code())) {
						routes += route(store, batch, airportIds, pair, other, km, random);
					}
				}
			}

			// A sightseeing flight, which leaves and returns to the same airport. Every graph has one so that the
			// self-relationship case is always present, however small the demo.
			int scenic = hubs.get(0);
			store.createRelationship(airportIds[scenic], "ROUTE_TO", airportIds[scenic], Properties.of()
				.set("airline", airlines.get(0).code())
				.set("flightNumber", airlines.get(0).code() + "9001")
				.set("distanceKm", 240.0)
				.set("durationMinutes", 75L)
				.set("weeklyFlights", 4L)
				.set("scenic", true));
			routes++;
			batch.step();
		}

		store.checkpoint();
		Summary summary = new Summary(places.size(), cities.size(), countries.size(), airlines.size(), routes,
			TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
		logger.info("Demo data: {}", summary);
		return summary;
	}

	/** True when the store holds no nodes at all, which is the only state the server will seed into. */
	public static boolean isEmpty(GraphStore store) {
		return store.stats().nodeCount() == 0;
	}

	private static int route(GraphStore store, Batch batch, long[] airportIds, Pair pair, Airline airline, double km,
		Random random) throws IOException {
		// Both directions, because a route you can only fly one way is not what anyone means by a route, and every
		// intuitive query — outbound and return — should work without a special case.
		for (int direction = 0; direction < 2; direction++) {
			int from = direction == 0 ? pair.from() : pair.to();
			int to = direction == 0 ? pair.to() : pair.from();
			store.createRelationship(airportIds[from], "ROUTE_TO", airportIds[to], Properties.of()
				.set("airline", airline.code())
				.set("flightNumber", airline.code() + (100 + random.nextInt(8900)))
				.set("distanceKm", Math.round(km * 10) / 10.0)
				// 800 km/h in the air, half an hour on the ground at each end.
				.set("durationMinutes", Math.round(km / 800.0 * 60) + 60)
				.set("weeklyFlights", (long) (1 + random.nextInt(21))));
			batch.step();
		}
		return 2;
	}

	// ---------------------------------------------------------------- topology

	private record Pair(int from, int to) {
	}

	/**
	 * Hubs, spread across continents.
	 *
	 * <p>
	 * Taking the first N airports of the table would make every hub American, and the resulting network would look
	 * nothing like the thing it is modelling. Round-robin over continents gives one hub per region first, which is
	 * both realistic and much more interesting to traverse.
	 * </p>
	 */
	private static List<Integer> chooseHubs(List<Place> places) {
		Map<String, List<Integer>> byContinent = new LinkedHashMap<>();
		for (int i = 0; i < places.size(); i++) {
			byContinent.computeIfAbsent(places.get(i).continent(), key -> new ArrayList<>()).add(i);
		}
		// Capped, because every hub pair is joined and that term is quadratic: 300 hubs is 90,000 routes on its own,
		// which made a 3,000-airport demo three times bigger than a 10,000-airport one has any reason to be. Real
		// networks do not have thousands of mutually connected hubs either.
		int wanted = Math.min(MAX_HUBS, Math.max(Math.min(byContinent.size(), places.size() / 2), places.size() / 10));
		List<Integer> hubs = new ArrayList<>();
		int round = 0;
		while (hubs.size() < wanted) {
			boolean progressed = false;
			for (List<Integer> continent : byContinent.values()) {
				if (round < continent.size() && hubs.size() < wanted) {
					hubs.add(continent.get(round));
					progressed = true;
				}
			}
			if (!progressed) {
				break;
			}
			round++;
		}
		return hubs;
	}

	/**
	 * Which airports are joined.
	 *
	 * <p>
	 * Hub and spoke, because that is what airline networks are and because it produces the degree distribution the
	 * engine's adjacency tiers are built for: a handful of nodes with hundreds of edges, and a long tail with three.
	 * Spokes attach to their <em>nearest</em> hubs, so the routes make geographic sense — European airports feed
	 * European hubs — and every hub pair is joined, which is what makes any two airports reachable in three legs.
	 * </p>
	 */
	private static List<Pair> pairs(List<Place> places, List<Integer> hubs, boolean[] isHub, Random random) {
		Map<String, List<Integer>> byContinent = new LinkedHashMap<>();
		for (int i = 0; i < places.size(); i++) {
			byContinent.computeIfAbsent(places.get(i).continent(), key -> new ArrayList<>()).add(i);
		}
		List<Pair> pairs = new ArrayList<>();
		for (int i = 0; i < hubs.size(); i++) {
			for (int j = i + 1; j < hubs.size(); j++) {
				pairs.add(new Pair(hubs.get(i), hubs.get(j)));
			}
		}
		for (int i = 0; i < places.size(); i++) {
			if (isHub[i]) {
				continue;
			}
			final int spoke = i;
			List<Integer> nearest = new ArrayList<>(hubs);
			nearest.sort(Comparator.comparingDouble(hub -> distanceKm(places.get(spoke), places.get(hub))));
			for (int n = 0; n < Math.min(2, nearest.size()); n++) {
				pairs.add(new Pair(i, nearest.get(n)));
			}
			// One point-to-point route so the network is not a pure star — but within the same continent. Drawing the
			// peer from the whole world produced routes like Auckland to Casablanca nonstop, which is not what the
			// data is meant to look like even when every route in it is invented.
			List<Integer> neighbours = byContinent.get(places.get(i).continent());
			int peer = neighbours.get(random.nextInt(neighbours.size()));
			if (peer != i && !isHub[peer]) {
				pairs.add(new Pair(i, peer));
			}
		}
		return pairs;
	}

	/** Great-circle distance. Rounded coordinates make this good to a few kilometres, which is what a demo needs. */
	static double distanceKm(Place from, Place to) {
		double earthRadius = 6371.0;
		double dLat = Math.toRadians(to.latitude() - from.latitude());
		double dLon = Math.toRadians(to.longitude() - from.longitude());
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
			+ Math.cos(Math.toRadians(from.latitude())) * Math.cos(Math.toRadians(to.latitude()))
				* Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}

	// ---------------------------------------------------------------- the places

	/** One airport and where it is. */
	public record Place(String code, String name, String city, String country, String iso, String continent,
		double latitude, double longitude) {
	}

	private static List<Place> places(int airports) {
		List<Place> places = new ArrayList<>(CURATED.length);
		for (int i = 0; i < Math.min(airports, CURATED.length); i++) {
			String[] fields = CURATED[i].split("\\|");
			places.add(new Place(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
				Double.parseDouble(fields[6]), Double.parseDouble(fields[7])));
		}
		if (airports > CURATED.length) {
			places.addAll(synthesise(airports - CURATED.length, places));
		}
		return places;
	}

	/**
	 * Airports beyond the real ones.
	 *
	 * <p>
	 * Invented names in invented countries, deliberately. Generating more airports by making up plausible facts about
	 * real places would put wrong data about somewhere real into a store somebody might later mistake for a source.
	 * These are obviously fictional, and their country codes come from the ISO 3166-1 user-assigned range, which is
	 * what that range is for.
	 * </p>
	 */
	private static List<Place> synthesise(int count, List<Place> existing) {
		List<Place> places = new ArrayList<>(count);
		java.util.Set<String> usedCodes = new java.util.HashSet<>();
		existing.forEach(place -> usedCodes.add(place.code()));
		Random random = new Random(SEED * 31);

		for (int i = 0; i < count; i++) {
			String city = CITY_STEMS[i % CITY_STEMS.length] + CITY_TAILS[(i / CITY_STEMS.length) % CITY_TAILS.length]
				+ (i >= CITY_STEMS.length * CITY_TAILS.length ? " " + (i / (CITY_STEMS.length * CITY_TAILS.length) + 1) : "");
			int nation = i % COUNTRIES.length;
			places.add(new Place(
				nextCode(usedCodes),
				city + (i % 4 == 0 ? " International" : " Airport"),
				city,
				COUNTRIES[nation],
				// XA to XZ: ISO 3166-1 leaves these to be assigned by the user, which is exactly this case.
				"X" + (char) ('A' + nation),
				CONTINENTS[nation % CONTINENTS.length],
				Math.round((random.nextDouble() * 140 - 60) * 10) / 10.0,
				Math.round((random.nextDouble() * 360 - 180) * 10) / 10.0));
		}
		return places;
	}

	/** The next unused three-letter code, in order, so a given size always produces the same codes. */
	private static String nextCode(java.util.Set<String> used) {
		for (char a = 'A'; a <= 'Z'; a++) {
			for (char b = 'A'; b <= 'Z'; b++) {
				for (char c = 'A'; c <= 'Z'; c++) {
					String code = "" + a + b + c;
					if (used.add(code)) {
						return code;
					}
				}
			}
		}
		throw new IllegalStateException("Ran out of three-letter codes, which needs more than 17,576 airports");
	}

	private record Airline(String name, String code, int founded, int fleet, boolean cargo) {
	}

	private static List<Airline> airlines(int airports) {
		int wanted = Math.max(3, Math.min(AIRLINE_NAMES.length, airports / 8));
		Random random = new Random(SEED * 17);
		List<Airline> airlines = new ArrayList<>(wanted);
		for (int i = 0; i < wanted; i++) {
			String name = AIRLINE_NAMES[i];
			boolean cargo = name.contains("Cargo") || name.contains("Freight");
			airlines.add(new Airline(name, AIRLINE_CODES[i], 1946 + random.nextInt(60),
				12 + random.nextInt(280), cargo));
		}
		return airlines;
	}

	/**
	 * Every one of these is invented, so that no route in the graph reads as a claim about a real airline.
	 *
	 * <p>
	 * A freight operator is third rather than eighth on purpose: the smallest demo has three airlines, and it should
	 * still contain the {@code Cargo Carrier} label — a label with a space in it — because that is the case anything
	 * turning a label back into a Cypher query has to get right.
	 * </p>
	 */
	private static final String[] AIRLINE_NAMES = {
		"Northwind Air", "Meridian Airways", "Beacon Air Cargo", "Halcyon Air", "Aurora Skyways",
		"Kestrel Airlines", "Solstice Air", "Cirrus Atlantic", "Vantage Airways", "Palisade Freight",
		"Lyra Air", "Quarrow Airlines"
	};

	private static final String[] AIRLINE_CODES = {
		"NW", "MD", "BC", "HL", "AK", "KS", "SO", "CA", "VG", "PF", "LY", "QW"
	};

	private static final String[] CITY_STEMS = {
		"Ash", "Bell", "Cedar", "Dun", "East", "Fair", "Glen", "Har", "Iron", "Kes",
		"Lin", "Mor", "North", "Oak", "Pine", "Ridge", "Stone", "Thorn", "Vale", "West"
	};

	private static final String[] CITY_TAILS = {
		"ford", "bridge", "haven", "mere", "dale", "wick", "stead", "cliff", "gate", "brook"
	};

	private static final String[] COUNTRIES = {
		"Aldoria", "Brennia", "Caldera", "Dunmark", "Eskaria", "Fenwold", "Gallia Nova", "Harrowen",
		"Ilmaria", "Jorvik", "Kestrel Isles", "Lorentia"
	};

	private static final String[] CONTINENTS = {
		"North America", "South America", "Europe", "Asia", "Africa", "Oceania"
	};

	/**
	 * Real airports: code, name, city, country, ISO code, continent, latitude, longitude.
	 *
	 * <p>
	 * Ordered so that a small demo is still a plausible global network — the first entries are the busiest airports of
	 * each region — and so that {@code --demo-size=tiny} is a set of well known names. Coordinates are rounded to a
	 * tenth of a degree. Cities with two airports are included on purpose: they are what makes "which airports serve
	 * London" a question with an interesting answer.
	 * </p>
	 */
	private static final String[] CURATED = {
		"ATL|Hartsfield-Jackson Atlanta International|Atlanta|United States|US|North America|33.6|-84.4",
		"LHR|Heathrow|London|United Kingdom|GB|Europe|51.5|-0.5",
		"DXB|Dubai International|Dubai|United Arab Emirates|AE|Asia|25.3|55.4",
		"SYD|Sydney Kingsford Smith|Sydney|Australia|AU|Oceania|-33.9|151.2",
		"JNB|O. R. Tambo International|Johannesburg|South Africa|ZA|Africa|-26.1|28.2",
		"GRU|Sao Paulo-Guarulhos International|Sao Paulo|Brazil|BR|South America|-23.4|-46.5",
		"JFK|John F. Kennedy International|New York|United States|US|North America|40.6|-73.8",
		"CDG|Charles de Gaulle|Paris|France|FR|Europe|49.0|2.5",
		"HND|Haneda|Tokyo|Japan|JP|Asia|35.5|139.8",
		"MEL|Melbourne|Melbourne|Australia|AU|Oceania|-37.7|144.8",
		"CAI|Cairo International|Cairo|Egypt|EG|Africa|30.1|31.4",
		"EZE|Ministro Pistarini|Buenos Aires|Argentina|AR|South America|-34.8|-58.5",
		"LAX|Los Angeles International|Los Angeles|United States|US|North America|33.9|-118.4",
		"FRA|Frankfurt|Frankfurt|Germany|DE|Europe|50.0|8.6",
		"SIN|Changi|Singapore|Singapore|SG|Asia|1.4|104.0",
		"AKL|Auckland|Auckland|New Zealand|NZ|Oceania|-37.0|174.8",
		"NBO|Jomo Kenyatta International|Nairobi|Kenya|KE|Africa|-1.3|36.9",
		"BOG|El Dorado International|Bogota|Colombia|CO|South America|4.7|-74.1",
		"ORD|O'Hare International|Chicago|United States|US|North America|42.0|-87.9",
		"AMS|Schiphol|Amsterdam|Netherlands|NL|Europe|52.3|4.8",
		"HKG|Hong Kong International|Hong Kong|Hong Kong SAR|HK|Asia|22.3|113.9",
		"CPT|Cape Town International|Cape Town|South Africa|ZA|Africa|-34.0|18.6",
		"SCL|Arturo Merino Benitez|Santiago|Chile|CL|South America|-33.4|-70.8",
		"DFW|Dallas Fort Worth International|Dallas|United States|US|North America|32.9|-97.0",
		"IST|Istanbul|Istanbul|Turkiye|TR|Europe|41.3|28.7",
		"ICN|Incheon International|Seoul|South Korea|KR|Asia|37.5|126.4",
		"LOS|Murtala Muhammed International|Lagos|Nigeria|NG|Africa|6.6|3.3",
		"YYZ|Toronto Pearson International|Toronto|Canada|CA|North America|43.7|-79.6",
		"MAD|Adolfo Suarez Madrid-Barajas|Madrid|Spain|ES|Europe|40.5|-3.6",
		"PVG|Shanghai Pudong International|Shanghai|China|CN|Asia|31.1|121.8",
		"CMN|Mohammed V International|Casablanca|Morocco|MA|Africa|33.4|-7.6",
		"SFO|San Francisco International|San Francisco|United States|US|North America|37.6|-122.4",
		"MUC|Munich|Munich|Germany|DE|Europe|48.4|11.8",
		"DEL|Indira Gandhi International|Delhi|India|IN|Asia|28.6|77.1",
		"MEX|Benito Juarez International|Mexico City|Mexico|MX|North America|19.4|-99.1",
		"FCO|Leonardo da Vinci-Fiumicino|Rome|Italy|IT|Europe|41.8|12.3",
		"BKK|Suvarnabhumi|Bangkok|Thailand|TH|Asia|13.7|100.7",
		"YVR|Vancouver International|Vancouver|Canada|CA|North America|49.2|-123.2",
		"ZRH|Zurich|Zurich|Switzerland|CH|Europe|47.5|8.5",
		"BOM|Chhatrapati Shivaji Maharaj International|Mumbai|India|IN|Asia|19.1|72.9",
		"SEA|Seattle-Tacoma International|Seattle|United States|US|North America|47.4|-122.3",
		"CPH|Copenhagen|Copenhagen|Denmark|DK|Europe|55.6|12.6",
		"DOH|Hamad International|Doha|Qatar|QA|Asia|25.3|51.6",
		"MIA|Miami International|Miami|United States|US|North America|25.8|-80.3",
		"VIE|Vienna|Vienna|Austria|AT|Europe|48.1|16.6",
		"NRT|Narita International|Tokyo|Japan|JP|Asia|35.8|140.4",
		"LGW|Gatwick|London|United Kingdom|GB|Europe|51.2|-0.2",
		"ARN|Stockholm Arlanda|Stockholm|Sweden|SE|Europe|59.7|18.0",
		"PEK|Beijing Capital International|Beijing|China|CN|Asia|40.1|116.6",
		"ORY|Orly|Paris|France|FR|Europe|48.7|2.4",
		"OSL|Oslo Gardermoen|Oslo|Norway|NO|Europe|60.2|11.1",
		"BER|Berlin Brandenburg|Berlin|Germany|DE|Europe|52.4|13.5",
		"DUB|Dublin|Dublin|Ireland|IE|Europe|53.4|-6.3",
		"HEL|Helsinki-Vantaa|Helsinki|Finland|FI|Europe|60.3|25.0",
		"LIS|Humberto Delgado|Lisbon|Portugal|PT|Europe|38.8|-9.1",
		"BCN|Josep Tarradellas Barcelona-El Prat|Barcelona|Spain|ES|Europe|41.3|2.1",
		"WAW|Warsaw Chopin|Warsaw|Poland|PL|Europe|52.2|21.0",
		"AUH|Zayed International|Abu Dhabi|United Arab Emirates|AE|Asia|24.4|54.7"
	};

	/**
	 * A transaction that commits every {@value #BATCH} elements.
	 *
	 * <p>
	 * One transaction for the whole seed would hold every page it touched in memory until the end; one per element
	 * would fsync tens of thousands of times. A large demo is the only thing in this project that writes at this
	 * volume, so the batching lives here rather than in the engine.
	 * </p>
	 */
	private static final class Batch implements AutoCloseable {

		private final GraphStore store;
		private Transaction txn;
		private int pending;

		Batch(GraphStore store) {
			this.store = store;
			this.txn = store.begin();
		}

		void step() throws IOException {
			if (++pending >= BATCH) {
				txn.commit();
				txn.close();
				txn = store.begin();
				pending = 0;
			}
		}

		@Override
		public void close() throws IOException {
			txn.commit();
			txn.close();
		}
	}
}
