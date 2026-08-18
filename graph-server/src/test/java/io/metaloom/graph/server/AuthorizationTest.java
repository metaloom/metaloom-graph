package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.server.auth.AuthPolicy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What a principal may do once it has said who it is.
 *
 * <p>
 * The fixture is three users on two databases, because almost everything worth asserting here is a <em>difference</em>:
 * the same request that succeeds for one principal has to fail for another, and the same principal that may act on
 * {@code sales} has to be refused on {@code hr}. A single all-powerful user would make every one of these pass without
 * proving anything.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class AuthorizationTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private AuthStore identities;
	private GraphServer server;
	private HttpClient client;
	private String base;

	private String admin;
	private String reader;
	private String loader;
	private String helpdesk;

	@BeforeEach
	public void start() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		databases.database(GraphDatabases.DEFAULT_NAME);
		databases.create("sales");
		databases.create("hr");

		identities = AuthStore.open(stores.path().resolve("root"));
		identities.bootstrap("admin-pw".toCharArray());

		// Reads everything, writes nothing.
		identities.createRole("reader");
		identities.grantToRole("reader", Grant.on(Privilege.MATCH));
		identities.grantToRole("reader", Grant.on(Privilege.INTROSPECT));
		identities.createUser("reader", "reader-pw".toCharArray(), List.of("reader"));

		// Reads and merges, on 'sales' only. The role a data pipeline gets.
		identities.createRole("loader");
		identities.grantToRole("loader", new Grant(Privilege.MATCH, "sales"));
		identities.grantToRole("loader", new Grant(Privilege.MERGE, "sales"));
		identities.grantToRole("loader", new Grant(Privilege.CREATE_INDEX, "sales"));
		identities.createUser("loader", "loader-pw".toCharArray(), List.of("loader"));

		// May reset passwords and may not grant anything. The split that matters most.
		identities.createRole("helpdesk");
		identities.grantToRole("helpdesk", Grant.on(Privilege.MANAGE_USERS));
		identities.createUser("helpdesk", "helpdesk-pw".toCharArray(), List.of("helpdesk"));

		// allowDrop and allowWrite both on: this class is about what a GRANT permits, so the capability flags are held
		// open and the grant is the only thing deciding. AuthFlagCompositionTest is where the flags do the refusing.
		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, true, true);
		server.authPolicy(AuthPolicy.enabled(identities, GraphServerOptions.DEFAULT_SESSION_TIMEOUT_MILLIS));
		server.allowBackup(true);
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		base = "http://localhost:" + port;

		admin = tokenFor("admin", "admin-pw");
		reader = tokenFor("reader", "reader-pw");
		loader = tokenFor("loader", "loader-pw");
		helpdesk = tokenFor("helpdesk", "helpdesk-pw");
	}

	@AfterEach
	public void stop() throws Exception {
		if (server != null) {
			server.close();
		}
		if (identities != null) {
			identities.close();
		}
		if (databases != null) {
			databases.close();
		}
	}

	// ---------------------------------------------------------------- helpers

	private HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return client.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), token);
	}

	private HttpResponse<String> post(String path, JsonObject body, String token) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(base + path))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.encode())), token);
	}

	private HttpResponse<String> delete(String path, String token) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(base + path)).DELETE(), token);
	}

	private HttpResponse<String> put(String path, String token) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(base + path))
			.PUT(HttpRequest.BodyPublishers.noBody()), token);
	}

	private HttpResponse<String> query(String cypher, String database, String token) throws Exception {
		String path = database == null ? "/api/query" : "/api/query?database=" + database;
		return post(path, new JsonObject().put("query", cypher), token);
	}

	private String tokenFor(String user, String password) throws Exception {
		HttpResponse<String> response = post("/api/login",
			new JsonObject().put("user", user).put("password", password), null);
		assertEquals(200, response.statusCode(), response.body());
		return new JsonObject(response.body()).getString("token");
	}

	private static void assertForbidden(HttpResponse<String> response, String privilege) {
		assertEquals(403, response.statusCode(), response.body());
		JsonObject body = new JsonObject(response.body());
		assertEquals("forbidden", body.getString("error"), body.encode());
		assertTrue(body.getString("message").contains(privilege),
			"the refusal must name the missing privilege: " + body.getString("message"));
	}

	// ---------------------------------------------------------------- queries

	@Test
	public void testAReaderReadsAndCannotWrite() throws Exception {
		assertEquals(200, query("MATCH (n) RETURN n LIMIT 1", null, reader).statusCode());
		assertForbidden(query("CREATE (n:Person)", null, reader), "CREATE");
		assertForbidden(query("MATCH (n) DETACH DELETE n", null, reader), "DELETE");
		assertForbidden(query("MERGE (n:Person {e: 'x'})", null, reader), "MERGE");
	}

	/**
	 * A per-database grant is per database.
	 *
	 * <p>
	 * The gap {@code spec/DATABASES.md} section 6 named: before there was an identity, {@code --allow-write} was the
	 * only lever and it applied to every store at once.
	 * </p>
	 */
	@Test
	public void testAGrantOnOneDatabaseDoesNotReachAnother() throws Exception {
		assertEquals(200, query("MERGE (n:Customer {id: 1})", "sales", loader).statusCode());
		assertForbidden(query("MATCH (n) RETURN n", "hr", loader), "MATCH");
		// The same MERGE that works on 'sales' is refused on 'hr'. It names MATCH rather than MERGE because that is
		// the first privilege the query needs that this principal has not got, and reporting the first genuine gap is
		// more useful than reporting the one whose name happens to be in the query text.
		assertForbidden(query("MERGE (n:Customer {id: 1})", "hr", loader), "MATCH");
	}

	@Test
	public void testAWildcardGrantReachesEveryDatabase() throws Exception {
		assertEquals(200, query("MATCH (n) RETURN n LIMIT 1", "sales", reader).statusCode());
		assertEquals(200, query("MATCH (n) RETURN n LIMIT 1", "hr", reader).statusCode());
	}

	/** MERGE needs MATCH as well, so a principal granted only MERGE cannot use it as a read primitive. */
	@Test
	public void testMergeAlsoNeedsMatch() throws Exception {
		identities.createRole("merge-only");
		identities.grantToRole("merge-only", new Grant(Privilege.MERGE, "sales"));
		identities.createUser("merger", "merger-pw".toCharArray(), List.of("merge-only"));
		String merger = tokenFor("merger", "merger-pw");

		assertForbidden(query("MERGE (n:Thing {id: 1})", "sales", merger), "MATCH");
	}

	// ---------------------------------------------------------------- introspection

	@Test
	public void testIntrospectionIsItsOwnPrivilege() throws Exception {
		assertEquals(200, get("/api/schema", reader).statusCode());
		assertEquals(200, get("/api/stats", reader).statusCode());
		assertEquals(200, get("/api/indexes", reader).statusCode());

		// The loader may write to sales and was never granted INTROSPECT.
		assertForbidden(get("/api/schema?database=sales", loader), "INTROSPECT");
		assertForbidden(get("/api/stats?database=sales", loader), "INTROSPECT");
	}

	// ---------------------------------------------------------------- index DDL

	@Test
	public void testIndexCreationAndDroppingAreSeparatePrivileges() throws Exception {
		assertEquals(200, post("/api/indexes?database=sales",
			new JsonObject().put("label", "Customer").put("key", "id"), loader).statusCode());

		// The loader was granted CREATE_INDEX and not DROP_INDEX: dropping a unique constraint is a correctness
		// event, not a cost one, so the two are not the same permission.
		assertForbidden(delete("/api/indexes/Customer/id?database=sales", loader), "DROP_INDEX");
		assertEquals(200, delete("/api/indexes/Customer/id?database=sales", admin).statusCode());
	}

	@Test
	public void testAReaderCannotDeclareAnIndex() throws Exception {
		assertForbidden(post("/api/indexes", new JsonObject().put("label", "Person").put("key", "email"), reader),
			"CREATE_INDEX");
	}

	// ---------------------------------------------------------------- databases

	@Test
	public void testDatabaseLifecycleNeedsItsOwnPrivileges() throws Exception {
		assertForbidden(put("/api/databases/newone", reader), "CREATE_DATABASE");
		// 201: creating a database is a creation, and the route has always said so.
		assertEquals(201, put("/api/databases/newone", admin).statusCode());

		assertForbidden(delete("/api/databases/newone", reader), "DROP_DATABASE");
		assertEquals(200, delete("/api/databases/newone", admin).statusCode());
	}

	/**
	 * The database list is filtered, not refused.
	 *
	 * <p>
	 * A picker refused outright would have nothing to draw and the caller nothing to act on. Filtering answers what
	 * they actually asked — which databases are there for me — and does not disclose the existence of one they cannot
	 * use.
	 * </p>
	 */
	@Test
	public void testTheDatabaseListIsFilteredRatherThanRefused() throws Exception {
		HttpResponse<String> response = get("/api/databases", loader);
		assertEquals(200, response.statusCode(), response.body());

		JsonArray listed = new JsonObject(response.body()).getJsonArray("databases");
		assertEquals(1, listed.size(), "the loader holds grants on one database only: " + listed.encode());
		assertEquals("sales", listed.getJsonObject(0).getString("name"));

		// And somebody with a wildcard sees all three.
		JsonArray everything = new JsonObject(get("/api/databases", reader).body()).getJsonArray("databases");
		assertEquals(3, everything.size(), everything.encode());
	}

	// ---------------------------------------------------------------- backup

	@Test
	public void testBackupIsItsOwnPrivilege() throws Exception {
		assertForbidden(get("/api/databases/sales/backup", reader), "BACKUP");
		assertEquals(200, get("/api/databases/sales/backup", admin).statusCode());
	}

	// ---------------------------------------------------------------- administration

	@Test
	public void testOnlyAnAdministratorMayListUsers() throws Exception {
		assertForbidden(get("/api/users", reader), "MANAGE_USERS");
		assertEquals(200, get("/api/users", admin).statusCode());
		assertEquals(200, get("/api/users", helpdesk).statusCode());
	}

	/** A user listing must never carry a hash, which is why the public record has no field for one. */
	@Test
	public void testAUserListingCarriesNoCredential() throws Exception {
		String body = get("/api/users", admin).body();
		assertFalse(body.contains("pbkdf2"), body);
		assertFalse(body.contains("hash"), body);
		assertFalse(body.contains("salt"), body);
	}

	/**
	 * The most important assertion in this file.
	 *
	 * <p>
	 * {@code MANAGE_USERS} is the helpdesk's privilege: reset a forgotten password, disable a leaver. If it also
	 * carried {@code MANAGE_ROLES}, anybody trusted with that everyday task could grant themselves anything at all —
	 * so the two are separate, and this is what proves the separation holds through the routes rather than only in
	 * the enum.
	 * </p>
	 */
	@Test
	public void testManagingUsersDoesNotAllowGrantingPrivilegesToOneself() throws Exception {
		// The helpdesk can do its job.
		assertEquals(200, post("/api/users/reader/password", new JsonObject().put("password", "reset-by-helpdesk"),
			helpdesk).statusCode());

		// And cannot promote itself, by any of the routes that would do it.
		assertForbidden(post("/api/roles", new JsonObject().put("name", "superuser"), helpdesk), "MANAGE_ROLES");
		assertForbidden(post("/api/roles/helpdesk/grants", new JsonObject().put("grant", "MERGE ON *"), helpdesk),
			"MANAGE_ROLES");
		assertForbidden(delete("/api/roles/reader", helpdesk), "MANAGE_ROLES");

		// The grant really did not happen.
		assertFalse(identities.principal("helpdesk").orElseThrow().may(Privilege.MERGE, "sales"));
	}

	@Test
	public void testAnAdministratorMayGrantAndRevoke() throws Exception {
		assertEquals(200, post("/api/roles", new JsonObject().put("name", "analyst"), admin).statusCode());
		assertEquals(200, post("/api/roles/analyst/grants", new JsonObject().put("grant", "MERGE ON sales"), admin)
			.statusCode());
		assertTrue(identities.role("analyst").orElseThrow().grants()
			.contains(new Grant(Privilege.MERGE, "sales")));

		assertEquals(200, send(HttpRequest.newBuilder(URI.create(base + "/api/roles/analyst/grants"))
			.header("content-type", "application/json")
			.method("DELETE", HttpRequest.BodyPublishers.ofString(
				new JsonObject().put("grant", "MERGE ON sales").encode())), admin).statusCode());
		assertTrue(identities.role("analyst").orElseThrow().grants().isEmpty());
	}

	/** Anybody may change their own password without being an administrator. */
	@Test
	public void testAUserMayChangeTheirOwnPassword() throws Exception {
		assertEquals(200, post("/api/users/reader/password", new JsonObject().put("password", "chosen-by-me"),
			reader).statusCode());
		assertEquals(200, post("/api/login",
			new JsonObject().put("user", "reader").put("password", "chosen-by-me"), null).statusCode());
	}

	/** But not somebody else's. */
	@Test
	public void testAUserMayNotChangeAnotherPassword() throws Exception {
		assertForbidden(post("/api/users/loader/password", new JsonObject().put("password", "not-yours"), reader),
			"MANAGE_USERS");
	}

	/**
	 * Changing a password ends every session that used the old one.
	 *
	 * <p>
	 * Otherwise a password change is not a password change to anybody who has just been compromised.
	 * </p>
	 */
	@Test
	public void testChangingAPasswordEndsTheOldSessions() throws Exception {
		assertEquals(200, get("/api/stats", reader).statusCode());
		post("/api/users/reader/password", new JsonObject().put("password", "rotated"), admin);
		assertEquals(401, get("/api/stats", reader).statusCode());
	}

	@Test
	public void testAGrantThatCannotBeScopedIsRefusedWithAMessage() throws Exception {
		post("/api/roles", new JsonObject().put("name", "wrong"), admin);
		HttpResponse<String> response = post("/api/roles/wrong/grants",
			new JsonObject().put("grant", "MANAGE_USERS ON sales"), admin);
		assertEquals(400, response.statusCode(), response.body());
		assertTrue(new JsonObject(response.body()).getString("message").contains("MANAGE_USERS"), response.body());
	}
}
