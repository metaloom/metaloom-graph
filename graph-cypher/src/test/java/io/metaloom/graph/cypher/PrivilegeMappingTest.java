package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.cypher.ast.Ast;

/**
 * What a query has to be granted before it may run.
 *
 * <p>
 * The mapping is the authorization layer's whole contract with the query language, and it is wrong in two directions:
 * requiring too much locks somebody out of a query they were granted, and requiring too little lets a query authorise
 * itself. The second is the one that matters, so the table below is exact — {@code assertEquals} on the whole set, not
 * {@code contains} — because a spurious extra privilege is a lockout nobody will diagnose quickly and a missing one is
 * a hole.
 * </p>
 *
 * <p>
 * <b>Nothing here asserts that the switch covers every clause.</b> {@code Ast.Clause} is sealed and the switch has no
 * {@code default}, so the compiler already refuses a build in which a new clause went unclassified. A test asserting
 * the same thing would only be able to check the clauses it had been told about, which is the set that is never the
 * problem.
 * </p>
 */
@Tag("unit")
public class PrivilegeMappingTest {

	private static Set<Privilege> of(String query) {
		return Cypher.parse(query).requiredPrivileges();
	}

	private static Set<Privilege> set(Privilege... privileges) {
		return privileges.length == 0 ? EnumSet.noneOf(Privilege.class) : EnumSet.copyOf(List.of(privileges));
	}

	// ---------------------------------------------------------------- reads

	@Test
	public void testAReadNeedsOnlyMatch() {
		assertEquals(set(Privilege.MATCH), of("MATCH (n:Person) RETURN n"));
		assertEquals(set(Privilege.MATCH), of("MATCH (n) WHERE n.age > 30 RETURN n.name ORDER BY n.name LIMIT 10"));
		assertEquals(set(Privilege.MATCH), of("MATCH (a)-[r:KNOWS]->(b) RETURN a, r, b"));
	}

	/**
	 * A query that touches no store at all still needs {@link Privilege#MATCH}.
	 *
	 * <p>
	 * It authorises no access — but it occupies a worker thread and a deadline, so "may issue a read query" is the
	 * floor rather than "may issue a query that happens to read something".
	 * </p>
	 */
	@Test
	public void testAQueryThatReadsNothingStillNeedsMatch() {
		assertEquals(set(Privilege.MATCH), of("UNWIND [1, 2, 3] AS x RETURN x"));
		assertEquals(set(Privilege.MATCH), of("RETURN 1 AS one"));
	}

	@Test
	public void testWithAndUnwindDoNotAddAPrivilegeOfTheirOwn() {
		assertEquals(set(Privilege.MATCH),
			of("MATCH (n:Person) WITH n.city AS city, count(*) AS c WHERE c > 1 RETURN city, c"));
		assertEquals(set(Privilege.MATCH), of("MATCH (n) UNWIND n.tags AS tag RETURN DISTINCT tag"));
	}

	/** The one procedure is {@code vector.query}, and it reads. */
	@Test
	public void testCallIsARead() {
		assertEquals(set(Privilege.MATCH),
			of("CALL vector.query('Photo', 'embedding', $v, 5) YIELD node RETURN node"));
	}

	// ---------------------------------------------------------------- writes

	@Test
	public void testEachWriteClauseNamesItself() {
		assertEquals(set(Privilege.CREATE), of("CREATE (n:Person {name: 'Ada'})"));
		assertEquals(set(Privilege.MATCH, Privilege.SET), of("MATCH (n:Person) SET n.age = 35"));
		assertEquals(set(Privilege.MATCH, Privilege.REMOVE), of("MATCH (n:Person) REMOVE n.age"));
		assertEquals(set(Privilege.MATCH, Privilege.DELETE), of("MATCH (n:Stale) DETACH DELETE n"));
	}

	/**
	 * {@code MERGE} needs the read as well as the write.
	 *
	 * <p>
	 * It matches before it creates, so a principal who could {@code MERGE} without {@link Privilege#MATCH} would learn
	 * whether a pattern already existed by watching whether one appeared — a read, performed through a write.
	 * </p>
	 */
	@Test
	public void testMergeNeedsMatchAsWellAsMerge() {
		assertEquals(set(Privilege.MATCH, Privilege.MERGE), of("MERGE (n:Person {email: $e})"));
		assertEquals(set(Privilege.MATCH, Privilege.MERGE),
			of("MERGE (n:Person {email: $e}) ON CREATE SET n.at = 1 ON MATCH SET n.seen = true"),
			"ON CREATE SET is part of the MERGE clause, not a SET clause of its own");
	}

	@Test
	public void testAMultiClauseWriteNeedsEveryClausePrivilege() {
		assertEquals(set(Privilege.MATCH, Privilege.CREATE, Privilege.SET),
			of("MATCH (a:Person) CREATE (b:Copy) SET b.name = a.name"));
	}

	// ---------------------------------------------------------------- LOAD CSV

	/**
	 * {@code LOAD CSV … RETURN row} is not a write, and that is exactly why the privilege exists.
	 *
	 * <p>
	 * If file access were folded into {@link Privilege#CREATE}, this query would be served to a principal granted only
	 * reads — which is the same mistake {@code --allow-load-csv} exists to avoid at the deployment level.
	 * </p>
	 */
	@Test
	public void testLoadCsvWithoutAWriteNeedsLoadCsvAndNotCreate() {
		Set<Privilege> required = of("LOAD CSV WITH HEADERS FROM 'file:///x.csv' AS row RETURN row");
		assertEquals(set(Privilege.MATCH, Privilege.LOAD_CSV), required);
		assertFalse(required.contains(Privilege.CREATE), "reading a file creates nothing");
	}

	/**
	 * An import needs no {@link Privilege#MATCH}, and a preview of the same file does.
	 *
	 * <p>
	 * Surprising until it is spelled out, and correct: the importing form binds its rows from the file and creates
	 * from them, reading nothing out of the graph, while the previewing form ends in {@code RETURN} and therefore
	 * hands rows back to the caller. So a loader granted {@code LOAD_CSV} and {@code CREATE} can run the import and
	 * cannot run the preview — which is the right way round, because the preview is the one that returns data.
	 * </p>
	 */
	@Test
	public void testAnImportNeedsNoMatchButAPreviewDoes() {
		assertEquals(set(Privilege.LOAD_CSV, Privilege.CREATE),
			of("LOAD CSV FROM 'file:///x.csv' AS row CREATE (:Row {v: row[0]})"));
		assertEquals(set(Privilege.LOAD_CSV, Privilege.MATCH),
			of("LOAD CSV FROM 'file:///x.csv' AS row RETURN row"));
	}

	// ---------------------------------------------------------------- UNION

	@Test
	public void testAUnionTakesTheUnionOfItsParts() {
		assertEquals(set(Privilege.MATCH, Privilege.CREATE),
			of("MATCH (n:A) RETURN n.name AS name UNION ALL CREATE (m:B) RETURN m.name AS name"),
			"running the query runs both parts, so it needs what both parts need");
	}

	// ---------------------------------------------------------------- agreement with the two older answers

	/**
	 * The three tree-answered policy questions must not disagree.
	 *
	 * <p>
	 * They are computed separately — {@code isWrite()} and {@code readsFiles()} by {@code instanceof} chains, this by
	 * an exhaustive switch — so nothing but this test stops one being updated and the others not. A query the servers
	 * let through because {@code isWrite()} said "read", while the privilege set said otherwise, would be a gate
	 * opening on a disagreement between two lines in the same file.
	 * </p>
	 */
	@Test
	public void testIsWriteAndReadsFilesAgreeWithThePrivilegeSet() {
		List<String> queries = List.of(
			"MATCH (n) RETURN n",
			"RETURN 1 AS one",
			"UNWIND [1] AS x RETURN x",
			"MATCH (n:Person) WITH n WHERE n.age > 1 RETURN n",
			"CALL vector.query('P', 'e', $v, 3) YIELD node RETURN node",
			"CREATE (n:Person)",
			"MERGE (n:Person {e: $e})",
			"MATCH (n) SET n.a = 1",
			"MATCH (n) REMOVE n.a",
			"MATCH (n) DELETE n",
			"MATCH (n) DETACH DELETE n",
			"LOAD CSV FROM 'file:///x.csv' AS row RETURN row",
			"LOAD CSV FROM 'file:///x.csv' AS row CREATE (:R {v: row[0]})",
			"MATCH (a:A) RETURN a.n AS n UNION MATCH (b:B) RETURN b.n AS n",
			"MATCH (a:A) RETURN a.n AS n UNION ALL CREATE (b:B) RETURN b.n AS n");

		for (String text : queries) {
			Ast.Query parsed = Cypher.parse(text);
			Set<Privilege> required = parsed.requiredPrivileges();

			boolean writes = required.stream().anyMatch(Privilege::isWrite);
			assertEquals(parsed.isWrite(), writes,
				"isWrite() and the privilege set disagree about: " + text);

			assertEquals(parsed.readsFiles(), required.contains(Privilege.LOAD_CSV),
				"readsFiles() and the privilege set disagree about: " + text);

			assertFalse(required.isEmpty(), "every query requires something: " + text);
		}
	}

	/** No query can require an administrative privilege; those belong to routes, not to the language. */
	@Test
	public void testNoQueryEverRequiresAnAdministrativePrivilege() {
		List<String> queries = List.of(
			"MATCH (n) RETURN n",
			"CREATE (n:Person)",
			"MERGE (n:Person {e: $e})",
			"MATCH (n) DETACH DELETE n",
			"LOAD CSV FROM 'file:///x.csv' AS row CREATE (:R {v: row[0]})");
		Set<Privilege> administrative = set(Privilege.MANAGE_USERS, Privilege.MANAGE_ROLES, Privilege.CREATE_INDEX,
			Privilege.DROP_INDEX, Privilege.CREATE_DATABASE, Privilege.DROP_DATABASE, Privilege.BACKUP,
			Privilege.INTROSPECT);
		for (String text : queries) {
			for (Privilege privilege : Cypher.parse(text).requiredPrivileges()) {
				assertTrue(!administrative.contains(privilege),
					text + " requires " + privilege + ", which no clause should be able to demand — index and "
						+ "database DDL are routes, not clauses");
			}
		}
	}
}
