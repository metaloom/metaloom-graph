package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * {@code OPTIONAL MATCH}: a row the pattern cannot extend is kept once, with what the pattern would have bound set to
 * null.
 *
 * <p>
 * The cases that matter are the ones a plain MATCH plus a filter gets wrong without throwing: the WHERE belongs to the
 * optional pattern (so a row that only fails the WHERE is padded, not dropped), a variable bound before the clause keeps
 * its value, and a padded null matches nothing in a later MATCH. Every query here runs on both execution paths, because
 * the streaming one decides "nothing matched" in a callback.
 * </p>
 */
@Tag("unit")
public class OptionalMatchTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore people() throws Exception {
		GraphStore store = stores.open();
		Cypher.query(store, "CREATE (a:P {name: 'ann'})-[:KNOWS {since: 2010}]->(b:P {name: 'bob'}), "
			+ "(b)-[:KNOWS {since: 2020}]->(c:P {name: 'cid'}), (:P {name: 'dan'})");
		return store;
	}

	/** The query's rows as maps, materialised and streamed, asserted to agree. */
	private static List<Map<String, Object>> both(GraphStore store, String query) {
		CypherResult result = Cypher.query(store, query);
		List<Map<String, Object>> streamed = new java.util.ArrayList<>();
		Cypher.stream(store, query, Map.of(), new RowSink() {
			private List<String> columns;

			@Override
			public void columns(List<String> names) {
				columns = names;
			}

			@Override
			public boolean row(List<Object> values) {
				Map<String, Object> row = new HashMap<>();
				for (int i = 0; i < columns.size(); i++) {
					row.put(columns.get(i), values.get(i));
				}
				streamed.add(row);
				return true;
			}
		});
		List<Map<String, Object>> materialised = new java.util.ArrayList<>();
		for (CypherRow row : result) {
			materialised.add(new HashMap<>(row.asMap()));
		}
		assertEquals(materialised, streamed, "the streaming and materialised paths disagree on " + query);
		return materialised;
	}

	private static List<Object> column(List<Map<String, Object>> rows, String name) {
		return rows.stream().map(row -> row.get(name)).toList();
	}

	@Test
	public void testAnUnmatchedRowIsPaddedWithNulls() throws Exception {
		try (GraphStore store = people()) {
			List<Map<String, Object>> rows = both(store, "MATCH (p:P) OPTIONAL MATCH (p)-[r:KNOWS]->(f) "
				+ "RETURN p.name AS p, f.name AS f, r.since AS since ORDER BY p");
			assertEquals(List.of("ann", "bob", "cid", "dan"), column(rows, "p"));
			assertEquals(Arrays.asList("bob", "cid", null, null), column(rows, "f"));
			assertEquals(Arrays.asList(2010L, 2020L, null, null), column(rows, "since"));
		}
	}

	@Test
	public void testTheWhereBelongsToThePattern() throws Exception {
		try (GraphStore store = people()) {
			// ann's only friend fails the WHERE: ann is kept with a null, not dropped as MATCH … WHERE would drop her.
			List<Map<String, Object>> rows = both(store, "MATCH (p:P) OPTIONAL MATCH (p)-[r:KNOWS]->(f) "
				+ "WHERE r.since > 2015 RETURN p.name AS p, f.name AS f ORDER BY p");
			assertEquals(List.of("ann", "bob", "cid", "dan"), column(rows, "p"));
			assertEquals(Arrays.asList(null, "cid", null, null), column(rows, "f"));
		}
	}

	@Test
	public void testAMatchKeepsEveryMatchAndNoPadding() throws Exception {
		try (GraphStore store = people()) {
			List<Map<String, Object>> rows = both(store,
				"MATCH (p:P {name: 'bob'}) OPTIONAL MATCH (p)-[:KNOWS]-(f) RETURN f.name AS f ORDER BY f");
			assertEquals(List.of("ann", "cid"), column(rows, "f"));
		}
	}

	@Test
	public void testALeadingOptionalMatchOverNothingIsOneNullRow() throws Exception {
		try (GraphStore store = people()) {
			List<Map<String, Object>> rows = both(store, "OPTIONAL MATCH (n:Missing) RETURN n");
			assertEquals(1, rows.size());
			assertNull(rows.get(0).get("n"));
			assertEquals(List.of(0L), Cypher.query(store, "OPTIONAL MATCH (n:Missing) RETURN count(n) AS c")
				.column("c"), "count skips the padded null");
		}
	}

	@Test
	public void testAPaddedNullMatchesNothingLater() throws Exception {
		try (GraphStore store = people()) {
			// cid and dan know nobody, so f is null for them, and the MATCH after it drops them rather than scanning.
			List<Map<String, Object>> rows = both(store, "MATCH (p:P) OPTIONAL MATCH (p)-[:KNOWS]->(f) "
				+ "MATCH (f)-[:KNOWS]->(g) RETURN p.name AS p, g.name AS g");
			assertEquals(List.of("ann"), column(rows, "p"));
			assertEquals(List.of("cid"), column(rows, "g"));
		}
	}

	@Test
	public void testAPathVariableAndARelationshipAreNullToo() throws Exception {
		try (GraphStore store = people()) {
			List<Map<String, Object>> rows = both(store, "MATCH (p:P {name: 'dan'}) "
				+ "OPTIONAL MATCH path = (p)-[r]->() RETURN path, r, p.name AS name");
			assertEquals(1, rows.size());
			assertNull(rows.get(0).get("path"));
			assertNull(rows.get(0).get("r"));
			assertEquals("dan", rows.get(0).get("name"));
		}
	}

	@Test
	public void testCollectOverOptionalFriendsGivesEmptyLists() throws Exception {
		try (GraphStore store = people()) {
			CypherResult result = Cypher.query(store, "MATCH (p:P) OPTIONAL MATCH (p)-[:KNOWS]->(f) "
				+ "RETURN p.name AS p, collect(f.name) AS friends ORDER BY p");
			assertEquals(List.of(List.of("bob"), List.of("cid"), List.of(), List.of()), result.column("friends"));
		}
	}

	@Test
	public void testSetOnAPaddedNullIsANoOp() throws Exception {
		try (GraphStore store = people()) {
			Cypher.query(store, "MATCH (p:P) OPTIONAL MATCH (p)-[:KNOWS]->(f) SET f.known = true");
			assertEquals(List.of("bob", "cid"), Cypher.query(store,
				"MATCH (p:P) WHERE p.known RETURN p.name AS n ORDER BY n").column("n"));
		}
	}

	@Test
	public void testLimitStopsAnOptionalMatchStream() throws Exception {
		try (GraphStore store = people()) {
			assertEquals(2, both(store, "MATCH (p:P) OPTIONAL MATCH (p)-[:KNOWS]->(f) RETURN p, f LIMIT 2").size());
		}
	}
}
