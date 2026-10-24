package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.server.auth.AuthPolicy;
import io.vertx.core.json.JsonObject;

/**
 * Saying who you are: what is required, what is not, and what stops working when.
 *
 * <p>
 * {@code AuthorizationTest} covers what a principal may then do. What is here is the layer before that — whether a
 * request is admitted at all, which routes are deliberately outside it, and which port is deliberately exempt.
 * </p>
 */
@Tag("unit")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class AuthenticationTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private AuthStore identities;
	private GraphServer server;
	private HttpClient client;
	private String base;

	private static final char[] ADMIN_PASSWORD = "admin-password".toCharArray();

	@BeforeEach
	public void start() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		databases.database(GraphDatabases.DEFAULT_NAME);

		identities = AuthStore.open(stores.path().resolve("root"));
		identities.bootstrap(ADMIN_PASSWORD);
		identities.createRole("reader");
		identities.grantToRole("reader", Grant.on(Privilege.MATCH));
		identities.grantToRole("reader", Grant.on(Privilege.INTROSPECT));
		identities.createUser("reader", "reader-password".toCharArray(), List.of("reader"));

		server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME,
			GraphServerOptions.DEFAULT_QUERY_TIMEOUT_MILLIS, true);
		server.authPolicy(AuthPolicy.enabled(identities, GraphServerOptions.DEFAULT_SESSION_TIMEOUT_MILLIS));
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		base = "http://localhost:" + port;
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

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return client.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return send(request);
	}

	private HttpResponse<String> query(String cypher, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/api/query"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("query", cypher).encode()));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return send(request);
	}

	private HttpResponse<String> login(String user, String password) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
				new JsonObject().put("user", user).put("password", password).encode())));
	}

	private String tokenFor(String user, String password) throws Exception {
		HttpResponse<String> response = login(user, password);
		assertEquals(200, response.statusCode(), response.body());
		return new JsonObject(response.body()).getString("token");
	}

	// ---------------------------------------------------------------- the gate

	@Test
	public void testEveryApiRouteRefusesARequestWithNoCredential() throws Exception {
		for (String path : List.of("/api/stats", "/api/schema", "/api/databases", "/api/indexes", "/api/users",
			"/api/roles")) {
			HttpResponse<String> response = get(path, null);
			assertEquals(401, response.statusCode(), path + " was served without a credential: " + response.body());
			assertEquals("unauthenticated", new JsonObject(response.body()).getString("error"), path);
		}
		assertEquals(401, query("MATCH (n) RETURN n", null).statusCode());
	}

	/** The header a client looks for, naming the scheme rather than only saying no. */
	@Test
	public void testARefusalNamesTheSchemeItWouldAccept() throws Exception {
		HttpResponse<String> response = get("/api/stats", null);
		assertEquals(401, response.statusCode());
		assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").contains("Bearer"),
			response.headers().map().toString());
	}

	@Test
	public void testAnUnknownTokenIsRefused() throws Exception {
		assertEquals(401, get("/api/stats", "not-a-real-token").statusCode());
		assertEquals(401, get("/api/stats", "").statusCode());
	}

	// ---------------------------------------------------------------- logging in

	@Test
	public void testLoggingInReturnsATokenAndACookie() throws Exception {
		HttpResponse<String> response = login("admin", "admin-password");
		assertEquals(200, response.statusCode(), response.body());

		JsonObject body = new JsonObject(response.body());
		assertEquals("admin", body.getString("user"));
		assertFalse(body.getString("token").isEmpty());
		assertFalse(body.getJsonArray("grants").isEmpty(), "the grants come back, so a UI can render what is allowed");

		String cookie = response.headers().firstValue("set-cookie").orElseThrow();
		assertTrue(cookie.startsWith("graph_session="), cookie);
		assertTrue(cookie.toLowerCase().contains("httponly"),
			"JavaScript must not be able to read the token: " + cookie);
		assertTrue(cookie.toLowerCase().contains("samesite=strict"), "the CSRF defence: " + cookie);
		assertTrue(cookie.contains("Path=/"), cookie);
	}

	/**
	 * No {@code Secure} over plain HTTP.
	 *
	 * <p>
	 * Setting it unconditionally would be the more obviously "secure" choice and would make the cookie unusable on the
	 * plain HTTP this server actually serves: the browser would silently drop it, and the login would appear to
	 * succeed and then do nothing.
	 * </p>
	 */
	@Test
	public void testTheCookieIsNotMarkedSecureOverPlainHttp() throws Exception {
		String cookie = login("admin", "admin-password").headers().firstValue("set-cookie").orElseThrow();
		assertFalse(cookie.toLowerCase().contains("secure"), cookie);
	}

	@Test
	public void testTheCookieIsMarkedSecureBehindATlsProxy() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
			.header("content-type", "application/json")
			.header("X-Forwarded-Proto", "https")
			.POST(HttpRequest.BodyPublishers.ofString(
				new JsonObject().put("user", "admin").put("password", "admin-password").encode())));
		assertTrue(response.headers().firstValue("set-cookie").orElseThrow().toLowerCase().contains("secure"),
			response.headers().firstValue("set-cookie").orElseThrow());
	}

	@Test
	public void testABearerTokenIsAccepted() throws Exception {
		String token = tokenFor("admin", "admin-password");
		assertEquals(200, get("/api/stats", token).statusCode());
	}

	@Test
	public void testACookieIsAccepted() throws Exception {
		String cookie = login("admin", "admin-password").headers().firstValue("set-cookie").orElseThrow();
		String value = cookie.substring(0, cookie.indexOf(';'));
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/api/stats"))
			.header("Cookie", value).GET());
		assertEquals(200, response.statusCode(), response.body());
	}

	/**
	 * Every failure looks the same.
	 *
	 * <p>
	 * A wrong password, a user that does not exist and a disabled account all answer 401 with one message. Telling
	 * them apart is exactly what an attacker wants, and there is nothing a legitimate user does differently on the
	 * strength of knowing which it was.
	 * </p>
	 */
	@Test
	public void testEveryKindOfFailedLoginLooksIdentical() throws Exception {
		HttpResponse<String> wrongPassword = login("admin", "not-the-password");
		HttpResponse<String> noSuchUser = login("nobody-at-all", "not-the-password");

		assertEquals(401, wrongPassword.statusCode());
		assertEquals(401, noSuchUser.statusCode());
		assertEquals(wrongPassword.body(), noSuchUser.body(),
			"a different message for an unknown user would enumerate the user list");

		identities.setEnabled("reader", false);
		HttpResponse<String> disabled = login("reader", "reader-password");
		assertEquals(401, disabled.statusCode());
		assertEquals(wrongPassword.body(), disabled.body());
	}

	@Test
	public void testALoginNeedsBothFields() throws Exception {
		assertEquals(400, send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("user", "admin").encode()))).statusCode());
	}

	// ---------------------------------------------------------------- logging out

	@Test
	public void testLoggingOutEndsTheSession() throws Exception {
		String token = tokenFor("admin", "admin-password");
		assertEquals(200, get("/api/stats", token).statusCode());

		assertEquals(200, send(HttpRequest.newBuilder(URI.create(base + "/api/logout"))
			.header("Authorization", "Bearer " + token)
			.POST(HttpRequest.BodyPublishers.noBody())).statusCode());

		assertEquals(401, get("/api/stats", token).statusCode(), "the token stops working immediately");
	}

	/** Two logins are two sessions, so signing out of one browser does not sign out of the other. */
	@Test
	public void testTwoLoginsAreIndependentSessions() throws Exception {
		String first = tokenFor("admin", "admin-password");
		String second = tokenFor("admin", "admin-password");
		assertNotEquals(first, second);

		send(HttpRequest.newBuilder(URI.create(base + "/api/logout"))
			.header("Authorization", "Bearer " + first).POST(HttpRequest.BodyPublishers.noBody()));

		assertEquals(401, get("/api/stats", first).statusCode());
		assertEquals(200, get("/api/stats", second).statusCode());
	}

	// ---------------------------------------------------------------- whoami

	/**
	 * {@code whoami} answers without a credential, and that is deliberate.
	 *
	 * <p>
	 * It is the question a browser asks <em>before</em> it has anything to present, and the answer decides whether a
	 * login form is drawn at all. Behind the filter it would be useless.
	 * </p>
	 */
	@Test
	public void testWhoamiAnswersWithoutASession() throws Exception {
		HttpResponse<String> response = get("/api/whoami", null);
		assertEquals(200, response.statusCode(), response.body());

		JsonObject body = new JsonObject(response.body());
		assertTrue(body.getBoolean("authEnabled"));
		assertFalse(body.getBoolean("authenticated"));
		assertNull(body.getString("user"));
	}

	@Test
	public void testWhoamiNamesTheUserOnceThereIsOne() throws Exception {
		JsonObject body = new JsonObject(get("/api/whoami", tokenFor("reader", "reader-password")).body());
		assertTrue(body.getBoolean("authenticated"));
		assertEquals("reader", body.getString("user"));
		assertTrue(body.getJsonArray("grants").contains("MATCH ON *"), body.encode());
	}

	// ---------------------------------------------------------------- what an edit does to a session

	/**
	 * Disabling a user ends their live session, rather than waiting for it to time out.
	 *
	 * <p>
	 * This is what "grants are resolved per request, not frozen at login" buys, and it is the difference between an
	 * account you can switch off and one you can only stop from logging in again.
	 * </p>
	 */
	@Test
	public void testDisablingAUserEndsTheirLiveSession() throws Exception {
		String token = tokenFor("reader", "reader-password");
		assertEquals(200, get("/api/stats", token).statusCode());

		identities.setEnabled("reader", false);

		assertEquals(401, get("/api/stats", token).statusCode());
	}

	/**
	 * And so does a disable made by another process — the CLI, editing {@code .auth} while the server runs.
	 *
	 * <p>
	 * The server re-reads the file when it changes. Doing that only on login would let an existing session outlive
	 * the edit until its idle timeout; the check is rate-limited, so this allows a moment rather than demanding the
	 * very next request.
	 * </p>
	 */
	@Test
	public void testADisableMadeByAnotherProcessEndsTheLiveSession() throws Exception {
		String token = tokenFor("reader", "reader-password");
		assertEquals(200, get("/api/stats", token).statusCode());

		try (AuthStore elsewhere = AuthStore.open(stores.path().resolve("root"))) {
			elsewhere.setEnabled("reader", false);
		}

		int status = 200;
		for (int attempt = 0; attempt < 40 && status != 401; attempt++) {
			Thread.sleep(100);
			status = get("/api/stats", token).statusCode();
		}
		assertEquals(401, status, "a user disabled on disk kept their session");
	}

	/** And a revoked grant takes effect just as immediately. */
	@Test
	public void testARevokedGrantTakesEffectOnTheNextRequest() throws Exception {
		String token = tokenFor("reader", "reader-password");
		assertEquals(200, get("/api/stats", token).statusCode());

		identities.revokeFromRole("reader", Grant.on(Privilege.INTROSPECT));

		assertEquals(403, get("/api/stats", token).statusCode(),
			"still logged in, but no longer permitted — 403, not 401");
	}

	// ---------------------------------------------------------------- what is deliberately open

	/**
	 * The monitoring port carries no credential, on purpose.
	 *
	 * <p>
	 * A kubelet probe and a Prometheus scrape have none to give, and a probe that failed because a secret was
	 * misconfigured would turn an authentication mistake into a restart loop. Asserted here so the exemption is a
	 * decision on the record rather than an oversight somebody later "fixes".
	 * </p>
	 */
	@Test
	public void testTheMonitoringPortIsExempt() throws Exception {
		MetricsServer monitoring = new MetricsServer(io.vertx.core.Vertx.vertx(),
			new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
				io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT),
			() -> true);
		try {
			int port = monitoring.listen(0).toCompletionStage().toCompletableFuture().join();
			for (String path : List.of("/metrics", "/health", "/ready")) {
				HttpResponse<String> response = client.send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
						.timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
				assertEquals(200, response.statusCode(), path + " asked for a credential a probe cannot give");
			}
		} finally {
			monitoring.close().toCompletionStage().toCompletableFuture().join();
		}
	}

	/**
	 * The static bundle is served without a session.
	 *
	 * <p>
	 * It has to be: the login page is part of it. A filter mounted on {@code /*} instead of {@code /api/*} would
	 * refuse the bundle to a browser that has not logged in, and the symptom is a blank page with nothing in the log.
	 * </p>
	 */
	@Test
	public void testTheStaticBundleIsServedWithoutASession() throws Exception {
		HttpResponse<String> response = client.send(
			HttpRequest.newBuilder(URI.create(base + "/")).timeout(Duration.ofSeconds(10)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		// 200 with a bundle, or 503 with the "no UI" page when npm did not run. Never 401.
		assertNotEquals(401, response.statusCode(),
			"the login page cannot be behind the login: " + response.statusCode());
	}

	// ---------------------------------------------------------------- authentication off

	/**
	 * With authentication off, nothing asks and everything is permitted.
	 *
	 * <p>
	 * The equivalence that let this feature land in stages: a server started this way behaves exactly as one built
	 * before any of it existed.
	 * </p>
	 */
	@Test
	public void testWithAuthenticationOffNothingIsRequired() throws Exception {
		server.authPolicy(AuthPolicy.disabled());

		assertEquals(200, get("/api/stats", null).statusCode());
		assertEquals(200, query("MATCH (n) RETURN n LIMIT 1", null).statusCode());

		JsonObject who = new JsonObject(get("/api/whoami", null).body());
		assertFalse(who.getBoolean("authEnabled"));
		assertEquals("anonymous", who.getString("user"));

		// And logging in is a different failure from a wrong password, so a UI can tell them apart.
		HttpResponse<String> attempted = login("admin", "admin-password");
		assertEquals(400, attempted.statusCode(), attempted.body());
		assertEquals("authdisabled", new JsonObject(attempted.body()).getString("error"));
	}

	/** An anonymous principal reports no grants, rather than claiming every one of them. */
	@Test
	public void testAnonymousReportsNoGrants() throws Exception {
		server.authPolicy(AuthPolicy.disabled());
		assertTrue(new JsonObject(get("/api/whoami", null).body()).getJsonArray("grants").isEmpty());
	}

	// ---------------------------------------------------------------- throttling

	/**
	 * Repeated failures are made to wait, and the refusal costs no derivation.
	 *
	 * <p>
	 * The point is not only that guessing is slowed: it is that an unauthenticated caller cannot make the server spend
	 * a hundred milliseconds of CPU per attempt indefinitely. A 429 arrives without the KDF running at all.
	 * </p>
	 */
	@Test
	public void testRepeatedFailuresAreThrottled() throws Exception {
		Optional<HttpResponse<String>> throttled = Optional.empty();
		for (int attempt = 0; attempt < 12; attempt++) {
			HttpResponse<String> response = login("admin", "wrong-" + attempt);
			if (response.statusCode() == 429) {
				throttled = Optional.of(response);
				break;
			}
			assertEquals(401, response.statusCode(), response.body());
		}
		HttpResponse<String> refusal = throttled.orElseThrow(
			() -> new AssertionError("twelve failed logins were never throttled"));
		assertTrue(refusal.headers().firstValue("retry-after").isPresent(), "a client is told how long to wait");
		assertEquals("throttled", new JsonObject(refusal.body()).getString("error"));
	}
}
