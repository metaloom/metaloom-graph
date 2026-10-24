package io.metaloom.graph.server.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.AuthException;
import io.metaloom.graph.core.api.AuthStore;
import io.metaloom.graph.core.api.Grant;
import io.metaloom.graph.core.api.Principal;
import io.metaloom.graph.core.api.Privilege;
import io.metaloom.graph.core.api.Role;
import io.metaloom.graph.core.api.User;
import io.vertx.core.Vertx;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Logging in, logging out, saying who you are, and administering who else there is.
 *
 * <h2>What is outside the filter, and why</h2>
 *
 * <p>
 * {@code /api/login}, {@code /api/logout} and {@code /api/whoami} need no session, and the third is the one that has to
 * be thought about: it is how the browser discovers whether this server wants a login at all, so making it require one
 * would leave the UI unable to decide what to render. It answers with a name or with null, and nothing else.
 * </p>
 *
 * <p>
 * The filter is mounted on {@code /api/*} rather than on {@code /*} for a reason that is easy to get wrong: the login
 * page is served by {@code StaticHandler} from the same origin. A filter over everything would refuse the bundle to a
 * browser that has not logged in, and the result is a blank page with no error and nothing in the log.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 6.1
 */
public final class AuthRoutes {

	private static final Logger logger = LoggerFactory.getLogger(AuthRoutes.class);

	/** Where the resolved principal is put for the handlers downstream. */
	public static final String PRINCIPAL = "graph.principal";

	public static final String COOKIE = "graph_session";

	private static final String BEARER = "Bearer ";

	/**
	 * The policy, read per request rather than captured.
	 *
	 * <p>
	 * A supplier and not a value, because the routes are mounted once when the server binds and the policy is set
	 * through a setter that a caller may reach for at any time. Capturing it would make {@code authPolicy(...)}
	 * silently do nothing after {@code listen()} — a setter that appears to work and does not, which is the shape of
	 * bug that costs an afternoon. It also matches how {@code allowBackup} and the CSV source are read.
	 * </p>
	 */
	private final Supplier<AuthPolicy> policy;

	private final Vertx vertx;

	public AuthRoutes(Supplier<AuthPolicy> policy, Vertx vertx) {
		this.policy = policy;
		this.vertx = vertx;
	}

	private AuthPolicy auth() {
		return policy.get();
	}

	/**
	 * Installs the filter and the routes.
	 *
	 * <p>
	 * Called before the query and administration routes are registered, because Vert.x matches in order and a filter
	 * that ran after them would not be a filter.
	 * </p>
	 */
	public void mount(Router router) {
		router.post("/api/login").handler(this::login);
		router.post("/api/logout").handler(this::logout);
		router.get("/api/whoami").handler(this::whoami);

		router.route("/api/*").handler(this::resolve);

		// Registered unconditionally, and each one checks at request time. Registering them only when authentication
		// happened to be on at mount time would make the policy setter's effect depend on when it was called.
		{
			router.get("/api/users").handler(this::listUsers);
			router.post("/api/users").handler(this::createUser);
			router.delete("/api/users/:name").handler(this::dropUser);
			router.post("/api/users/:name/password").handler(this::setPassword);
			router.post("/api/users/:name/enabled").handler(this::setEnabled);
			router.get("/api/roles").handler(this::listRoles);
			router.post("/api/roles").handler(this::createRole);
			router.delete("/api/roles/:name").handler(this::dropRole);
			router.post("/api/roles/:name/grants").handler(this::grant);
			router.delete("/api/roles/:name/grants").handler(this::revoke);
		}
	}

	// ---------------------------------------------------------------- the filter

	private void resolve(RoutingContext context) {
		Optional<Principal> principal = auth().isEnabled()
			? auth().resolve(token(context))
			: Optional.of(Principal.ANONYMOUS);
		if (principal.isEmpty()) {
			// 401 rather than 403: the caller has not said who they are, which is a different thing from having said
			// and not being allowed, and leads to a different reaction in every client.
			unauthenticated(context);
			return;
		}
		context.put(PRINCIPAL, principal.get());
		context.next();
	}

	/** The principal a handler should use. Never null once the filter has run. */
	public static Principal principal(RoutingContext context) {
		Principal principal = context.get(PRINCIPAL);
		return principal == null ? Principal.ANONYMOUS : principal;
	}

	private static String token(RoutingContext context) {
		String header = context.request().getHeader("Authorization");
		if (header != null && header.startsWith(BEARER)) {
			return header.substring(BEARER.length()).trim();
		}
		Cookie cookie = context.request().getCookie(COOKIE);
		return cookie == null ? null : cookie.getValue();
	}

	// ---------------------------------------------------------------- login

	private void login(RoutingContext context) {
		if (!auth().isEnabled()) {
			// 400 rather than 404, so a client can tell "this server has no accounts" from "wrong password" and from
			// "this is not a graph server".
			fail(context, 400, "authdisabled", "This server runs with authentication disabled, so there is nothing "
				+ "to log in to.");
			return;
		}
		JsonObject body = context.body() == null ? null : context.body().asJsonObject();
		String user = body == null ? null : body.getString("user");
		String password = body == null ? null : body.getString("password");
		if (user == null || password == null) {
			fail(context, 400, "empty", "Send {\"user\": \"...\", \"password\": \"...\"}");
			return;
		}

		String address = context.request().remoteAddress() == null ? "unknown"
			: context.request().remoteAddress().host();

		// Before the derivation, which is the whole point of having a throttle: refusing after it would count the
		// attempts correctly and still let each one cost the server a tenth of a second.
		long wait = auth().throttle().retryAfterMillis(user, address);
		if (wait > 0) {
			context.response().putHeader("retry-after", String.valueOf((wait + 999) / 1000));
			fail(context, 429, "throttled", "Too many failed attempts. Try again in "
				+ ((wait + 999) / 1000) + " seconds.");
			return;
		}

		// Bounded across every front end at once — see AuthPolicy.tryBeginVerification. Held as one policy, so the
		// release goes back to the semaphore the permit came from even if the policy is swapped meanwhile.
		AuthPolicy policy = auth();
		if (!policy.tryBeginVerification()) {
			fail(context, 503, "busy", "This server is verifying as many passwords as it can at once. Retry.");
			return;
		}

		char[] characters = password.toCharArray();
		vertx.<Optional<Principal>>executeBlocking(() -> policy.authenticate(user, characters), false)
			.onComplete(outcome -> {
				policy.endVerification();
				java.util.Arrays.fill(characters, '\0');
				if (outcome.failed()) {
					logger.error("Verifying a password failed", outcome.cause());
					fail(context, 500, "internal", "The server could not verify that password");
					return;
				}
				Optional<Principal> principal = outcome.result();
				if (principal.isEmpty()) {
					auth().throttle().recordFailure(user, address);
					// One message for every reason. Which of "no such user", "wrong password" and "disabled" it was is
					// not something the network is told.
					logger.info("A failed login for '{}' from {}", user, address);
					fail(context, 401, "unauthenticated", "That user name and password do not match.");
					return;
				}
				auth().throttle().recordSuccess(user);
				issue(context, principal.get());
			});
	}

	private void issue(RoutingContext context, Principal principal) {
		String token;
		try {
			token = auth().sessions().issue(principal.name());
		} catch (IllegalStateException full) {
			fail(context, 503, "busy", full.getMessage());
			return;
		}
		Cookie cookie = Cookie.cookie(COOKIE, token)
			.setPath("/")
			.setHttpOnly(true)
			.setSameSite(CookieSameSite.STRICT);
		if (secure(context)) {
			// Only over TLS. Setting it unconditionally would make the cookie unusable on the plain HTTP this server
			// actually serves, which presents as a login that appears to succeed and then does nothing.
			cookie.setSecure(true);
		}
		context.response().addCookie(cookie);
		logger.info("'{}' logged in", principal.name());
		json(context, 200, new JsonObject()
			.put("user", principal.name())
			// Also in the body, for a client that is not a browser and would rather hold a bearer token than a cookie.
			.put("token", token)
			.put("grants", grantsOf(principal)));
	}

	/** TLS terminated here, or by something in front that said so. */
	private static boolean secure(RoutingContext context) {
		if (context.request().isSSL()) {
			return true;
		}
		return "https".equalsIgnoreCase(context.request().getHeader("X-Forwarded-Proto"));
	}

	private void logout(RoutingContext context) {
		if (auth().isEnabled()) {
			// Unknown tokens are ignored rather than refused: a logout is not somewhere to learn whether a token was
			// real.
			auth().sessions().invalidate(token(context));
		}
		context.response().removeCookie(COOKIE);
		json(context, 200, new JsonObject().put("ok", true));
	}

	/**
	 * Who the caller is, and whether this server even asks.
	 *
	 * <p>
	 * Answers 200 with a null user rather than 401, because it is the question a client asks <em>before</em> it has
	 * anything to present. A 401 here would leave the UI unable to tell an anonymous server from one it should show a
	 * login form for.
	 * </p>
	 */
	private void whoami(RoutingContext context) {
		Optional<Principal> principal = auth().isEnabled()
			? auth().resolve(token(context))
			: Optional.of(Principal.ANONYMOUS);
		JsonObject body = new JsonObject()
			.put("authEnabled", auth().isEnabled())
			.put("authenticated", principal.map(Principal::authenticated).orElse(false))
			.put("user", principal.map(Principal::name).orElse(null))
			.put("grants", principal.map(AuthRoutes::grantsOf).orElse(new JsonArray()));
		json(context, 200, body);
	}

	private static JsonArray grantsOf(Principal principal) {
		JsonArray grants = new JsonArray();
		for (Grant grant : principal.grants()) {
			grants.add(grant.toString());
		}
		return grants;
	}

	// ---------------------------------------------------------------- administration

	private void listUsers(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_USERS)) {
			return;
		}
		JsonArray users = new JsonArray();
		for (User user : auth().store().users()) {
			users.add(new JsonObject()
				.put("name", user.name())
				.put("enabled", user.enabled())
				.put("createdAt", user.createdAtEpochMillis())
				.put("passwordChangedAt", user.passwordChangedAtEpochMillis())
				.put("roles", new JsonArray(new ArrayList<Object>(user.roles())))
				.put("grants", stringify(user.grants())));
		}
		json(context, 200, new JsonObject().put("users", users));
	}

	private void createUser(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_USERS)) {
			return;
		}
		JsonObject body = body(context);
		if (body == null) {
			return;
		}
		String name = body.getString("name");
		String password = body.getString("password");
		if (name == null || password == null) {
			fail(context, 400, "empty", "Send {\"name\": \"...\", \"password\": \"...\", \"roles\": []}");
			return;
		}
		List<String> roles = new ArrayList<>();
		JsonArray requested = body.getJsonArray("roles", new JsonArray());
		for (int i = 0; i < requested.size(); i++) {
			roles.add(requested.getString(i));
		}
		// Putting a user in a role hands them its grants, which is MANAGE_ROLES's business. Without this a helpdesk
		// account creates a second user in the admin role and logs in as it (spec/AUTH.md section 1.1).
		if (!roles.isEmpty() && !permit(context, Privilege.MANAGE_ROLES)) {
			return;
		}
		char[] characters = password.toCharArray();
		mutate(context, () -> auth().store().createUser(name, characters, roles), () -> {
			java.util.Arrays.fill(characters, '\0');
			return new JsonObject().put("name", name);
		});
	}

	private void dropUser(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_USERS)) {
			return;
		}
		String name = context.pathParam("name");
		mutate(context, () -> auth().store().dropUser(name), () -> {
			// Their sessions go with them, or a removed user keeps working until their idle timeout.
			auth().sessions().invalidateUser(name);
			return new JsonObject().put("name", name);
		});
	}

	/**
	 * Sets a password: an administrator's for anyone, or anybody's own.
	 *
	 * <p>
	 * Letting somebody change their own password without {@code MANAGE_USERS} is the difference between a system
	 * people can use and one where every rotation is a ticket.
	 * </p>
	 */
	private void setPassword(RoutingContext context) {
		String name = context.pathParam("name");
		Principal principal = principal(context);
		boolean self = principal.name().equals(name);
		if (!auth().isEnabled()) {
			fail(context, 400, "authdisabled", "This server runs with authentication disabled.");
			return;
		}
		if (!self && !permit(context, Privilege.MANAGE_USERS)) {
			return;
		}
		if (!self) {
			// A reset is a login as that user. Resetting a reader's password is the helpdesk's job; resetting the
			// password of somebody who may manage roles would be becoming them.
			Privilege beyond = administrativeBeyond(principal, name);
			if (beyond != null) {
				forbidden(context, beyond, Grant.ALL_DATABASES);
				return;
			}
		}
		JsonObject body = body(context);
		if (body == null) {
			return;
		}
		String password = body.getString("password");
		if (password == null) {
			fail(context, 400, "empty", "Send {\"password\": \"...\"}");
			return;
		}
		char[] characters = password.toCharArray();
		mutate(context, () -> auth().store().setPassword(name, characters), () -> {
			java.util.Arrays.fill(characters, '\0');
			// Every other session for this user ends. A password change that left old sessions working would not be a
			// password change as far as anybody who has just been compromised is concerned.
			auth().sessions().invalidateUser(name);
			return new JsonObject().put("name", name);
		});
	}

	private void setEnabled(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_USERS)) {
			return;
		}
		String name = context.pathParam("name");
		JsonObject body = body(context);
		if (body == null) {
			return;
		}
		boolean enabled = body.getBoolean("enabled", Boolean.TRUE);
		mutate(context, () -> auth().store().setEnabled(name, enabled), () -> {
			if (!enabled) {
				auth().sessions().invalidateUser(name);
			}
			return new JsonObject().put("name", name).put("enabled", enabled);
		});
	}

	private void listRoles(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_ROLES)) {
			return;
		}
		JsonArray roles = new JsonArray();
		for (Role role : auth().store().roles()) {
			roles.add(new JsonObject().put("name", role.name()).put("grants", stringify(role.grants())));
		}
		json(context, 200, new JsonObject().put("roles", roles));
	}

	private void createRole(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_ROLES)) {
			return;
		}
		JsonObject body = body(context);
		if (body == null) {
			return;
		}
		String name = body.getString("name");
		if (name == null) {
			fail(context, 400, "empty", "Send {\"name\": \"...\"}");
			return;
		}
		mutate(context, () -> auth().store().createRole(name), () -> new JsonObject().put("name", name));
	}

	private void dropRole(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_ROLES)) {
			return;
		}
		String name = context.pathParam("name");
		mutate(context, () -> auth().store().dropRole(name), () -> new JsonObject().put("name", name));
	}

	private void grant(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_ROLES)) {
			return;
		}
		Grant grant = grantFrom(context);
		if (grant == null) {
			return;
		}
		String role = context.pathParam("name");
		mutate(context, () -> auth().store().grantToRole(role, grant),
			() -> new JsonObject().put("role", role).put("grant", grant.toString()));
	}

	private void revoke(RoutingContext context) {
		if (!permit(context, Privilege.MANAGE_ROLES)) {
			return;
		}
		Grant grant = grantFrom(context);
		if (grant == null) {
			return;
		}
		String role = context.pathParam("name");
		mutate(context, () -> auth().store().revokeFromRole(role, grant),
			() -> new JsonObject().put("role", role).put("grant", grant.toString()));
	}

	/** Accepts either {@code {"grant": "MERGE ON sales"}} or the two fields apart. */
	private Grant grantFrom(RoutingContext context) {
		JsonObject body = body(context);
		if (body == null) {
			return null;
		}
		try {
			String text = body.getString("grant");
			if (text != null) {
				return Grant.parse(text);
			}
			String privilege = body.getString("privilege");
			String database = body.getString("database", Grant.ALL_DATABASES);
			if (privilege == null) {
				fail(context, 400, "empty", "Send {\"grant\": \"MERGE ON sales\"} or {\"privilege\": \"MERGE\", "
					+ "\"database\": \"sales\"}");
				return null;
			}
			return new Grant(Privilege.parse(privilege), database);
		} catch (IllegalArgumentException invalid) {
			fail(context, 400, "grant", invalid.getMessage());
			return null;
		}
	}

	// ---------------------------------------------------------------- plumbing

	/** Every administrative privilege is global, so the check never needs a database. */
	private boolean permit(RoutingContext context, Privilege privilege) {
		if (!auth().isEnabled()) {
			// There is no identity store to administer. 400 rather than 403, because the caller is not being refused
			// permission — there is nothing here to have permission to.
			fail(context, 400, "authdisabled", "This server runs with authentication disabled, so it has no users "
				+ "or roles to manage.");
			return false;
		}
		if (principal(context).may(privilege)) {
			return true;
		}
		forbidden(context, privilege, Grant.ALL_DATABASES);
		return false;
	}

	/**
	 * An administrative privilege {@code target} holds and {@code caller} does not, or null.
	 *
	 * <p>
	 * Administrative only, the global-only ones: a helpdesk resetting a reader's password does take on that reader's
	 * access for a moment, and that is the job {@code MANAGE_USERS} exists for. What it must not reach is an account
	 * that could grant anything. Disabled users count, because re-enabling one is another {@code MANAGE_USERS} call.
	 * </p>
	 */
	private Privilege administrativeBeyond(Principal caller, String target) {
		for (Grant grant : auth().store().effectiveGrants(target)) {
			if (grant.privilege().isGlobalOnly() && !caller.may(grant.privilege())) {
				return grant.privilege();
			}
		}
		return null;
	}

	private interface Edit {

		void apply() throws java.io.IOException;
	}

	private interface Result {

		JsonObject build();
	}

	/**
	 * Runs an edit on a worker and answers with what it produced.
	 *
	 * <p>
	 * On a worker because {@code createUser} and {@code setPassword} derive a password, and because every edit rewrites
	 * and fsyncs a file. Neither belongs on an event loop.
	 * </p>
	 */
	private void mutate(RoutingContext context, Edit edit, Result result) {
		vertx.<JsonObject>executeBlocking(() -> {
			edit.apply();
			return result.build();
		}, false).onComplete(outcome -> {
			if (outcome.succeeded()) {
				json(context, 200, outcome.result());
				return;
			}
			Throwable cause = outcome.cause();
			if (cause instanceof AuthException || cause instanceof IllegalArgumentException) {
				fail(context, 400, "auth", cause.getMessage());
				return;
			}
			logger.error("An identity edit failed", cause);
			fail(context, 500, "internal", "The identity store could not be changed");
		});
	}

	private static JsonArray stringify(List<Grant> grants) {
		JsonArray out = new JsonArray();
		for (Grant grant : grants) {
			out.add(grant.toString());
		}
		return out;
	}

	private JsonObject body(RoutingContext context) {
		JsonObject body = context.body() == null ? null : context.body().asJsonObject();
		if (body == null) {
			fail(context, 400, "empty", "This route needs a JSON body");
			return null;
		}
		return body;
	}

	public static void unauthenticated(RoutingContext context) {
		// The header a well-behaved client looks for, naming the scheme it may use rather than only that it failed.
		context.response().putHeader("WWW-Authenticate", "Bearer realm=\"metaloom-graph\"");
		fail(context, 401, "unauthenticated", "This server requires a login. POST /api/login, or send "
			+ "Authorization: Bearer <token>.");
	}

	/**
	 * A refusal that names the missing grant.
	 *
	 * <p>
	 * Distinct in kind from the capability-flag refusal, which reads {@code readonly} and advises a restart. The
	 * remedy for this one is a grant and the person who can apply it is an administrator, so saying "start it with
	 * --allow-write" here would send somebody after a change that would not help.
	 * </p>
	 */
	public static void forbidden(RoutingContext context, Privilege privilege, String database) {
		Principal principal = principal(context);
		fail(context, 403, "forbidden", "'" + principal.name() + "' has not been granted " + privilege
			+ (Grant.ALL_DATABASES.equals(database) ? "" : " on '" + database + "'")
			+ ". An administrator can grant it with POST /api/roles/<role>/grants.");
	}

	private static void json(RoutingContext context, int status, JsonObject body) {
		context.response()
			.setStatusCode(status)
			.putHeader("content-type", "application/json")
			.end(body.encode());
	}

	private static void fail(RoutingContext context, int status, String kind, String message) {
		context.response()
			.setStatusCode(status)
			.putHeader("content-type", "application/json")
			.end(new JsonObject().put("error", kind).put("message", message).encode());
	}

	/** For a caller that needs the store to bootstrap it. */
	public AuthStore store() {
		return auth().store();
	}
}
