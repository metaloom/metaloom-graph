package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.vertx.core.json.JsonObject;

/**
 * That a grant can never widen what the server was started to permit.
 *
 * <p>
 * Two independent gates, and the rule is that <b>both</b> must say yes. The failure this class exists to make
 * impossible is the one where switching authentication on turns a deliberately read-only deployment into a writable
 * one — an operator's start-time decision quietly overridden by an administrator's runtime one.
 * </p>
 *
 * <p>
 * It also pins down the equivalence that let the feature land in stages: with {@code --no-auth} the server answers
 * exactly what it answered before any of this existed, including the wording.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class AuthFlagCompositionTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private AuthStore identities;
	private GraphServer server;
	private HttpClient client;
	private String base;

	@BeforeEach
	public void open() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		databases.database(GraphDatabases.DEFAULT_NAME);
		identities = AuthStore.open(stores.path().resolve("root"));
		identities.bootstrap("admin-pw".toCharArray());
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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

	/** @param allowWrite the capability flag; authentication is on unless {@code authenticated} is false */
	private void serve(boolean allowWrite, boolean authenticated) throws Exception {
		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, false, allowWrite);
		if (authenticated) {
			server.authPolicy(AuthPolicy.enabled(identities, GraphServerOptions.DEFAULT_SESSION_TIMEOUT_MILLIS));
		}
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();
		base = "http://localhost:" + port;
	}

	private HttpResponse<String> query(String cypher, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/api/query"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("query", cypher).encode()));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return client.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private String adminToken() throws Exception {
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
				new JsonObject().put("user", "admin").put("password", "admin-pw").encode()))
			.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		return new JsonObject(response.body()).getString("token");
	}

	// ---------------------------------------------------------------- the rule

	/**
	 * Granted everything, and still read-only.
	 *
	 * <p>
	 * The administrator holds {@code CREATE ON *}. The server was started without {@code --allow-write}. The write is
	 * refused, and the refusal is the <b>flag's</b> — because the remedy is a restart, which only an operator can
	 * perform, and telling an administrator to grant themselves something they already have would send them in a
	 * circle.
	 * </p>
	 */
	@Test
	public void testAGrantCannotWidenWhatTheFlagAllows() throws Exception {
		serve(false, true);
		String admin = adminToken();
		assertTrue(identities.principal("admin").orElseThrow().may(Privilege.CREATE, "default"),
			"the fixture only means something if the administrator really does hold CREATE");

		HttpResponse<String> response = query("CREATE (n:Person)", admin);

		assertEquals(403, response.statusCode(), response.body());
		JsonObject body = new JsonObject(response.body());
		assertEquals("readonly", body.getString("error"), "the FLAG refused, not the grant: " + body.encode());
		assertTrue(body.getString("message").contains("--allow-write"), body.getString("message"));
	}

	/**
	 * Writable server, ungranted user: the refusal is the grant's.
	 *
	 * <p>
	 * The mirror image, and the reason the two are distinguishable at all. Somebody who simply has not been granted
	 * {@code CREATE} must not be told to restart the server — they cannot, and it would not help.
	 * </p>
	 */
	@Test
	public void testWithoutAGrantTheRefusalIsTheGrantsAndNotTheFlags() throws Exception {
		identities.createRole("reader");
		identities.grantToRole("reader", Grant.on(Privilege.MATCH));
		identities.createUser("reader", "reader-pw".toCharArray(), List.of("reader"));

		serve(true, true);
		HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
				new JsonObject().put("user", "reader").put("password", "reader-pw").encode()))
			.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
		String reader = new JsonObject(login.body()).getString("token");

		HttpResponse<String> response = query("CREATE (n:Person)", reader);

		assertEquals(403, response.statusCode(), response.body());
		JsonObject body = new JsonObject(response.body());
		assertEquals("forbidden", body.getString("error"), "the GRANT refused, not the flag: " + body.encode());
		assertTrue(body.getString("message").contains("CREATE"), body.getString("message"));
		assertTrue(!body.getString("message").contains("--allow-write"),
			"a user who cannot restart the server must not be told to: " + body.getString("message"));
	}

	@Test
	public void testBothGatesOpenMeansTheWriteHappens() throws Exception {
		serve(true, true);
		assertEquals(200, query("CREATE (n:Person)", adminToken()).statusCode());
	}

	// ---------------------------------------------------------------- the equivalence

	/**
	 * With authentication off, the response is what it always was.
	 *
	 * <p>
	 * Byte for byte, including the wording that names the flag. This is the assertion that made it safe to land the
	 * feature in stages: an operator who does not want any of it gets a server that has not changed.
	 * </p>
	 */
	@Test
	public void testWithAuthenticationOffTheReadOnlyRefusalIsUnchanged() throws Exception {
		serve(false, false);
		HttpResponse<String> response = query("CREATE (n:Person)", null);

		assertEquals(403, response.statusCode());
		JsonObject body = new JsonObject(response.body());
		assertEquals("readonly", body.getString("error"));
		assertEquals("This server does not accept write queries. Start it with --allow-write to enable CREATE, SET, "
			+ "REMOVE and DELETE.", body.getString("message"),
			"the message an unauthenticated server gives must not have drifted");
	}

	@Test
	public void testWithAuthenticationOffAWriteOnAWritableServerStillWorks() throws Exception {
		serve(true, false);
		assertEquals(200, query("CREATE (n:Person)", null).statusCode());
	}

	/** A read is a read whichever way the flags are set, and needs no grant when nobody is being asked. */
	@Test
	public void testWithAuthenticationOffAReadNeedsNothing() throws Exception {
		serve(false, false);
		assertEquals(200, query("MATCH (n) RETURN n LIMIT 1", null).statusCode());
	}
}
